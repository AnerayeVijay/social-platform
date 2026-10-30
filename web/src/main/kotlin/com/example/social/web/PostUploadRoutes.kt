package com.example.social.web

import com.example.social.application.AttachmentTooLarge
import com.example.social.application.PostService
import com.example.social.domain.*
import com.example.social.http.*
import org.http4k.core.*
import org.http4k.routing.path

internal fun isMultipart(request: Request) = isMediaMultipart(request)

private const val MAX_COMMENT_ATTACHMENTS = 1
private val POST_FIELDS = setOf("body", "visibility", "font", "size")
private val COMMENT_FIELDS = setOf("body")

/** Reads a multipart form and runs [action] on it inside the single upload gate, mapping failures to status codes. */
internal fun <T> mediaUpload(request: Request, clock: java.time.Clock, max: Int, fields: Set<String>, title: String, success: (T) -> Response,
    action: (MediaForm) -> T): Response = try {
    success(withMediaUpload { action(readMediaForm(request, max, fields, clock)) })
} catch (_: MediaUploadBusy) {
    Response(Status.TOO_MANY_REQUESTS).header("Retry-After", "1").body("Uploads are busy. Please try again.")
} catch (error: MediaUploadTimedOut) { mediaProblem(title, error, Status.REQUEST_TIMEOUT) }
catch (error: MediaUploadTooLarge) { mediaProblem(title, error, Status.REQUEST_ENTITY_TOO_LARGE) }
catch (error: AttachmentTooLarge) { mediaProblem(title, error, Status.REQUEST_ENTITY_TOO_LARGE) }
catch (_: NoSuchElementException) { htmlPage("Not found", "<h1>Not found</h1>", Status.NOT_FOUND) }
catch (error: IllegalArgumentException) { mediaProblem(title, error, Status.BAD_REQUEST) }

private fun mediaProblem(title: String, error: Exception, status: Status) = htmlPage(title,
    "<section class=\"card\"><h1>${escapeHtml(title)}</h1><p>${escapeHtml(error.message.orEmpty())}</p><a href=\"/\">Back to feed</a></section>", status)

internal fun publishUpload(request: Request, author: UserId, service: PostService, clock: java.time.Clock): Response =
    mediaUpload(request, clock, Post.MAX_IMAGES, POST_FIELDS, "Check your post", { post: Post ->
        Response(Status.SEE_OTHER).header("Location", "/web/posts/${post.id.value}")
    }) { form ->
        val fields = form.fields
        service.publishAttachments(author, fields["body"].orEmpty(), form.attachments, PostVisibility.parse(fields["visibility"] ?: "members"),
            PostFont.parse(fields["font"] ?: "sans"), PostSize.parse(fields["size"] ?: "normal"))
    }

internal fun commentUpload(request: Request, clock: java.time.Clock, title: String, location: String, action: (body: String, attachments: List<com.example.social.application.AttachmentUpload>) -> Unit): Response =
    mediaUpload(request, clock, MAX_COMMENT_ATTACHMENTS, COMMENT_FIELDS, title, { _: Unit -> Response(Status.SEE_OTHER).header("Location", location) }) { form ->
        action(form.fields["body"].orEmpty(), form.attachments)
    }

private fun postId(request: Request) = runCatching { PostId.parse(request.path("id")!!) }.getOrNull()

internal fun postMedia(request: Request, service: PostService?, viewer: UserId?): Response {
    val id = postId(request)
    val position = request.path("position")?.toIntOrNull()?.takeIf { it in 0 until Post.MAX_IMAGES }
    return streamOriginalMediaResponse(request, if (id == null || position == null) null else service?.imageContent(id, position, viewer))
}

internal fun commentMedia(request: Request, service: PostService?, threads: com.example.social.application.CommentThreads?, viewer: UserId?): Response {
    val id = postId(request)
    val comment = runCatching { CommentId(java.util.UUID.fromString(request.path("comment")!!)) }.getOrNull()
    val media = if (id == null || comment == null) null
        else try { threads?.attachmentContent(id, comment, viewer) ?: service?.commentAttachmentContent(id, comment, viewer) }
        catch (_: NoSuchElementException) { null }
    return streamOriginalMediaResponse(request, media)
}
