package com.example.social.web

import com.example.social.application.CustomizationService
import com.example.social.application.ImageProcessingBusy
import com.example.social.application.ImageTooLarge
import com.example.social.application.PostService
import com.example.social.application.PostUpload
import com.example.social.domain.*
import org.http4k.core.*
import org.http4k.routing.path

internal fun isMultipart(request: Request) =
    request.header("content-type")?.substringBefore(';')?.trim()?.equals("multipart/form-data", ignoreCase = true) == true

private const val MAX_IMAGE_BYTES = CustomizationService.MAX_UPLOAD_BYTES
/** Every image at its limit plus headroom for scalar fields, part headers and boundaries. */
private const val MAX_UPLOAD_BODY = Post.MAX_IMAGES.toLong() * MAX_IMAGE_BYTES + 64 * 1024

private class UploadTooLarge : IllegalArgumentException("Posts can include at most ${Post.MAX_IMAGES} images of 5 MB each")

/**
 * Uploads are read while the shared image gate is held, so the read must be bounded in bytes and time.
 * The deadline is checked around every read; it cannot interrupt a blocked read, which the server's
 * socket read timeout bounds instead (see Main), so the gate is held at most deadline + read timeout.
 */
private val UPLOAD_DEADLINE: java.time.Duration = java.time.Duration.ofSeconds(60)

private class UploadTimedOut : IllegalArgumentException("The upload took too long. Please try again.")

/** Counts what is really read, so a missing or false Content-Length cannot widen the budget. */
private class BoundedUploadStream(private val source: java.io.InputStream, private val cap: Long, private val clock: java.time.Clock) : java.io.InputStream() {
    private val deadline = clock.instant().plus(UPLOAD_DEADLINE)
    private var count = 0L
    var failure: IllegalArgumentException? = null; private set
    private fun fail(error: IllegalArgumentException): Nothing { failure = error; throw java.io.IOException(error.message) }
    private fun checkDeadline() { if (clock.instant().isAfter(deadline)) fail(UploadTimedOut()) }
    private fun counted(n: Int): Int {
        if (n > 0) count += n
        if (count > cap) fail(UploadTooLarge())
        checkDeadline()
        return n
    }
    override fun read(): Int { checkDeadline(); return source.read().also { if (it >= 0) counted(1) else checkDeadline() } }
    override fun read(b: ByteArray, off: Int, len: Int): Int { checkDeadline(); return counted(source.read(b, off, len)) }
    override fun close() = source.close()
}

internal fun publishUpload(request: Request, author: UserId, service: PostService, clock: java.time.Clock): Response = try {
    if (!hasValidBoundary(request)) throw malformed()
    if ((request.header("Content-Length")?.toLongOrNull() ?: 0L) > MAX_UPLOAD_BODY) throw UploadTooLarge()
    val post = service.publishUpload(author) { readPostUpload(request, clock) }
    Response(Status.SEE_OTHER).header("Location", "/web/posts/${post.id.value}")
} catch (_: ImageProcessingBusy) {
    Response(Status.TOO_MANY_REQUESTS).header("Retry-After", "1").body("Image processing is busy. Please try again.")
} catch (error: UploadTimedOut) { checkPost(error, Status.REQUEST_TIMEOUT) }
catch (error: UploadTooLarge) { checkPost(error, Status.REQUEST_ENTITY_TOO_LARGE) }
catch (error: ImageTooLarge) { checkPost(error, Status.REQUEST_ENTITY_TOO_LARGE) }
catch (error: IllegalArgumentException) { checkPost(error, Status.BAD_REQUEST) }

private fun checkPost(error: Exception, status: Status) = htmlPage("Check your post",
    "<section class=\"card\"><h1>Check your post</h1><p>${escapeHtml(error.message.orEmpty())}</p><a href=\"/\">Back to feed</a></section>", status)

private class UploadParts(val fields: Map<String, String>, val images: List<ByteArray>)

private fun readPostUpload(request: Request, clock: java.time.Clock): PostUpload {
    val parts = parts(request, clock)
    val fields = parts.fields
    return PostUpload(fields["body"].orEmpty(), PostVisibility.parse(fields["visibility"] ?: "members"),
        PostFont.parse(fields["font"] ?: "sans"), PostSize.parse(fields["size"] ?: "normal"), parts.images)
}

/**
 * http4k signals malformed input with assorted exceptions (ParseError, IOExceptions, even an NPE for a file part
 * without Content-Type) and may wrap our stream's IOException, so the stream's recorded failure decides 413/408 vs 400.
 */
private fun parts(request: Request, clock: java.time.Clock): UploadParts {
    val body = BoundedUploadStream(request.body.stream, MAX_UPLOAD_BODY, clock)
    return try { readParts(request.body(body)) }
    catch (error: ImageTooLarge) { throw error }
    catch (error: Exception) { throw body.failure ?: malformed() }
}

private fun readParts(request: Request): UploadParts = run {
    val fields = mutableMapOf<String, String>()
    val images = mutableListOf<ByteArray>()
    request.multipartIterator().asSequence().forEachIndexed { index, part ->
        if (index >= MAX_PARTS) throw malformed()
        when (part) {
            is MultipartEntity.Field -> if (part.name !in SCALAR_FIELDS || fields.put(part.name, part.value) != null) throw malformed()
            is MultipartEntity.File -> {
                if (part.name != "images" || images.size == Post.MAX_IMAGES) throw malformed()
                val bytes = part.file.content.readNBytes(MAX_IMAGE_BYTES + 1)
                if (bytes.size > MAX_IMAGE_BYTES) throw ImageTooLarge()
                // Browsers submit an unselected file input as an empty part with filename="".
                if (!(bytes.isEmpty() && part.file.filename.isBlank())) images += bytes
            }
        }
    }
    UploadParts(fields, images)
}

private val SCALAR_FIELDS = setOf("body", "visibility", "font", "size")
private val MAX_PARTS = SCALAR_FIELDS.size + Post.MAX_IMAGES

/** http4k 6.20 takes the boundary from the first Content-Type directive whatever its name, so require exactly that. */
private fun hasValidBoundary(request: Request): Boolean =
    org.http4k.lens.Header.CONTENT_TYPE(request)?.directives?.firstOrNull()
        ?.let { (name, value) -> name == "boundary" && value?.length in 1..70 } == true

private fun malformed() = IllegalArgumentException("The upload could not be read. Please try again.")

internal fun postMedia(request: Request, service: PostService?, viewer: UserId?): Response {
    val id = runCatching { PostId.parse(request.path("id")!!) }.getOrNull()
    val position = request.path("position")?.toIntOrNull()?.takeIf { it in 0 until Post.MAX_IMAGES }
    val image = if (id == null || position == null) null else service?.image(id, position, viewer)
    if (image == null) return Response(Status.NOT_FOUND)
    return Response(Status.OK).header("content-type", image.mediaType).header("X-Content-Type-Options", "nosniff")
        .header("Cache-Control", "private, no-store").body(Body(java.nio.ByteBuffer.wrap(image.bytes)))
}
