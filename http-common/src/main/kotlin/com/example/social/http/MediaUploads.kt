package com.example.social.http

import com.example.social.application.AttachmentUpload
import com.example.social.application.AttachmentPolicy
import org.http4k.core.*
import org.http4k.lens.Header
import java.io.IOException
import java.io.InputStream
import java.time.Clock
import java.time.Duration
import java.util.concurrent.Semaphore

class MediaUploadTooLarge : IllegalArgumentException("The upload is too large")
class MediaUploadTimedOut : IllegalArgumentException("The upload took too long. Please try again.")
class MediaUploadBusy : IllegalArgumentException("Uploads are busy. Please try again.")

data class MediaForm(val fields: Map<String, String>, val attachments: List<AttachmentUpload>)

private val UPLOAD_GATE = Semaphore(1)

/** Fail-fast single upload gate, separate from the profile image gate; hold it while reading, validating and storing. */
fun <T> withMediaUpload(block: () -> T): T {
    if (!UPLOAD_GATE.tryAcquire()) throw MediaUploadBusy()
    try { return block() } finally { UPLOAD_GATE.release() }
}

fun isMediaMultipart(request: Request) =
    request.header("content-type")?.substringBefore(';')?.trim()?.equals("multipart/form-data", ignoreCase = true) == true

private val UPLOAD_DEADLINE: Duration = Duration.ofSeconds(60)
private const val MAX_PART_BYTES = AttachmentPolicy.MAX_VIDEO_BYTES
private const val MAX_FIELD_BYTES = 64 * 1024
private const val MAX_ATTACHMENTS = 4
private const val OVERHEAD = 64L * 1024
private val FILE_FIELDS = setOf("attachments", "images")

private fun budget(maxAttachments: Int) = maxAttachments.toLong() * MAX_PART_BYTES + OVERHEAD

/** Counts what is really read, so a missing or false Content-Length cannot widen the budget. */
private class BoundedStream(private val source: InputStream, private val cap: Long, private val clock: Clock) : InputStream() {
    private val deadline = clock.instant().plus(UPLOAD_DEADLINE)
    private var count = 0L
    var failure: IllegalArgumentException? = null; private set
    private fun fail(error: IllegalArgumentException): Nothing { failure = error; throw IOException(error.message) }
    private fun checkDeadline() { if (clock.instant().isAfter(deadline)) fail(MediaUploadTimedOut()) }
    private fun counted(n: Int): Int {
        if (n > 0) count += n
        if (count > cap) fail(MediaUploadTooLarge())
        checkDeadline()
        return n
    }
    override fun read(): Int { checkDeadline(); return source.read().also { if (it >= 0) counted(1) else checkDeadline() } }
    override fun read(b: ByteArray, off: Int, len: Int): Int { checkDeadline(); return counted(source.read(b, off, len)) }
    override fun close() = source.close()
}

fun readMediaForm(request: Request, maxAttachments: Int, allowedFields: Set<String>, clock: Clock = Clock.systemUTC()): MediaForm {
    require(maxAttachments in 1..MAX_ATTACHMENTS) { "maxAttachments must be between 1 and $MAX_ATTACHMENTS" }
    if (!hasValidBoundary(request)) throw malformed()
    val cap = budget(maxAttachments)
    if ((request.header("Content-Length")?.toLongOrNull() ?: 0L) > cap) throw MediaUploadTooLarge()
    val body = BoundedStream(request.body.stream, cap, clock)
    return try { readParts(request.body(Body(body)), maxAttachments, allowedFields) }
    catch (error: MediaUploadTooLarge) { throw error }
    catch (error: Exception) { throw body.failure ?: malformed() }
}

private fun readParts(request: Request, maxAttachments: Int, allowedFields: Set<String>): MediaForm {
    val fields = mutableMapOf<String, String>()
    val attachments = mutableListOf<AttachmentUpload>()
    val maxParts = allowedFields.size + maxAttachments + 1 // one slot for an ignored empty file input
    request.multipartIterator().asSequence().forEachIndexed { index, part ->
        if (index >= maxParts) throw malformed()
        when (part) {
            is MultipartEntity.Field -> {
                if (part.name !in allowedFields || part.value.length > MAX_FIELD_BYTES || fields.put(part.name, part.value) != null) throw malformed()
            }
            is MultipartEntity.File -> part.file.content.use { content ->
                if (part.name !in FILE_FIELDS) throw malformed()
                val bytes = content.readNBytes(MAX_PART_BYTES + 1)
                if (bytes.size > MAX_PART_BYTES) throw MediaUploadTooLarge()
                // Browsers submit an unselected file input as an empty part with filename="".
                if (!(bytes.isEmpty() && part.file.filename.isBlank())) {
                    if (attachments.size == maxAttachments) throw malformed()
                    attachments += AttachmentUpload(part.file.filename, bytes)
                }
            }
        }
    }
    return MediaForm(fields, attachments)
}

/** http4k 6.20 takes the boundary from the first Content-Type directive whatever its name, so require exactly that. */
private fun hasValidBoundary(request: Request): Boolean =
    Header.CONTENT_TYPE(request)?.directives?.firstOrNull()
        ?.let { (name, value) -> name == "boundary" && value?.length in 1..70 } == true

private fun malformed() = IllegalArgumentException("The upload could not be read. Please try again.")
