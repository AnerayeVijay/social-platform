package com.example.social.api

import com.example.social.application.AttachmentTooLarge
import com.example.social.application.PostService
import com.example.social.domain.CommentId
import com.example.social.domain.PostFont
import com.example.social.domain.Post
import com.example.social.domain.PostId
import com.example.social.domain.PostSize
import com.example.social.domain.UserId
import com.example.social.domain.PostVisibility
import com.example.social.http.*
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.http4k.core.*
import org.http4k.core.Method.*
import org.http4k.routing.bind
import org.http4k.routing.path
import org.http4k.routing.routes
import org.http4k.routing.RoutingHttpHandler
import java.time.Instant

private val mapper = jacksonObjectMapper()

data class PublishRequest(val authorId: String = "", val body: String, val visibility: String = "members", val font: String = "sans", val size: String = "normal")
data class UserRequest(val userId: String = "")
data class CommentRequest(val authorId: String = "", val body: String)
data class VisibilityRequest(val visibility: String)
data class PostResponse(
    val id: String, val authorId: String, val body: String, val hashtags: List<String>,
    val likes: List<String>, val likeCount: Int, val comments: List<CommentResponse>, val createdAt: String, val visibility: String,
    val font: String = "sans", val size: String = "normal", val attachments: List<AttachmentResponse> = emptyList()
)
data class CommentResponse(val id: String, val authorId: String, val body: String, val createdAt: String, val parentId: String? = null,
    val attachments: List<AttachmentResponse> = emptyList())
data class AttachmentResponse(val url: String, val downloadUrl: String, val mediaType: String)

internal fun commentAttachmentDescriptors(commentsPath: String, id: CommentId, mediaType: String?) =
    if (mediaType == null) emptyList() else listOf(AttachmentResponse("$commentsPath/${id.value}/attachment", "$commentsPath/${id.value}/attachment?download=1", mediaType))

internal fun attachmentDescriptors(basePath: String, mediaTypes: List<String>) = mediaTypes.mapIndexed { index, type ->
    AttachmentResponse("$basePath/$index", "$basePath/$index?download=1", type)
}

private fun com.example.social.domain.Post.response() = PostResponse(
    id.value.toString(), authorId.value.toString(), body, hashtags.map { it.value }.sorted(),
    likes.map { it.value.toString() }.sorted(), likes.size,
    comments.map { CommentResponse(it.id.value.toString(), it.authorId.value.toString(), it.body, it.createdAt.toString(), it.parentId?.value?.toString(),
        commentAttachmentDescriptors("/posts/${id.value}/comments", it.id, it.attachmentType)) },
    createdAt.toString(), visibility.name.lowercase(), font.name.lowercase(), size.name.lowercase(),
    attachmentDescriptors("/posts/${id.value}/attachments", mediaTypes)
)

fun routes(service: PostService, access: AccessControl? = null): RoutingHttpHandler = routes(
    "/health" bind GET to { Response(Status.OK).body("""{"status":"ok"}""").header("content-type", "application/json") },

    "/posts" bind POST to bind@{ req ->
        if (isMediaMultipart(req)) return@bind safelyMedia { publishMultipart(service, access, req) }
        safely {
            val body: PublishRequest = mapper.readValue(req.bodyString())
            val post = service.publish(access?.session(req)?.accountId ?: UserId.parse(body.authorId), body.body, PostVisibility.parse(body.visibility), PostFont.parse(body.font), PostSize.parse(body.size))
            json(Status.CREATED, post.response())
        }
    },

    "/posts" bind GET to { req ->
        safely {
            val limit = req.query("limit")?.toIntOrNull() ?: 20
            val before = req.query("before")?.let(Instant::parse)
            json(Status.OK, service.feed(limit, before, access?.session(req)?.accountId).map { it.response() })
        }
    },

    "/posts/{id}" bind GET to { req ->
        safely {
            val post = service.get(PostId.parse(req.path("id")!!), access?.session(req)?.accountId)
                ?: return@safely json(Status.NOT_FOUND, mapOf("error" to "Post not found"))
            json(Status.OK, post.response())
        }
    },

    "/posts/{id}/visibility" bind PUT to { req ->
        safely {
            val actor = access?.session(req)?.accountId ?: return@safely json(Status.UNAUTHORIZED, mapOf("error" to "Sign in to continue"))
            val body: VisibilityRequest = mapper.readValue(req.bodyString())
            service.changeVisibility(PostId.parse(req.path("id")!!), actor, PostVisibility.parse(body.visibility))
            Response(Status.NO_CONTENT)
        }
    },

    "/posts/{id}/likes" bind POST to { req ->
        safely {
            val user: UserRequest = mapper.readValue(req.bodyString())
            json(Status.OK, service.like(PostId.parse(req.path("id")!!), access?.session(req)?.accountId ?: UserId.parse(user.userId)).response())
        }
    },

    "/posts/{id}/likes" bind DELETE to { req ->
        safely {
            val user: UserRequest = mapper.readValue(req.bodyString())
            json(Status.OK, service.unlike(PostId.parse(req.path("id")!!), access?.session(req)?.accountId ?: UserId.parse(user.userId)).response())
        }
    },

    "/posts/{id}/comments" bind POST to bind@{ req ->
        if (isMediaMultipart(req)) return@bind safelyMedia { commentMultipart(service, access, req) }
        safely {
            val comment: CommentRequest = mapper.readValue(req.bodyString())
            json(Status.CREATED, service.comment(
                PostId.parse(req.path("id")!!), access?.session(req)?.accountId ?: UserId.parse(comment.authorId), comment.body
            ).response())
        }
    },

    "/posts/{id}/attachments/{position}" bind GET to { req -> postAttachment(service, access, req) },
    "/api/posts/{id}/attachments/{position}" bind GET to { req -> postAttachment(service, access, req) },

    "/posts/{id}/comments/{comment}/attachment" bind GET to { req ->
        safely {
            streamOriginalMediaResponse(req, service.commentAttachmentContent(PostId.parse(req.path("id")!!), CommentId(java.util.UUID.fromString(req.path("comment")!!)), access?.session(req)?.accountId))
        }
    },

    "/hashtags/{tag}/posts" bind GET to { req ->
        safely {
            val limit = req.query("limit")?.toIntOrNull() ?: 20
            json(Status.OK, service.byHashtag(req.path("tag")!!, limit, access?.session(req)?.accountId).map { it.response() })
        }
    }
)

private fun json(status: Status, value: Any): Response =
    Response(status).header("content-type", "application/json").body(mapper.writeValueAsString(value))

private inline fun safely(block: () -> Response): Response = try {
    block()
} catch (e: IllegalArgumentException) {
    json(Status.BAD_REQUEST, mapOf("error" to (e.message ?: "Invalid request")))
} catch (e: NoSuchElementException) {
    json(Status.NOT_FOUND, mapOf("error" to (e.message ?: "Not found")))
} catch (e: Exception) {
    json(Status.INTERNAL_SERVER_ERROR, mapOf("error" to "Internal server error"))
}

private const val MAX_ATTACHMENTS = 4
private val PUBLISH_FIELDS = setOf("body", "visibility", "font", "size", "authorId")

private fun publishMultipart(service: PostService, access: AccessControl?, req: Request): Response {
    val actor = access?.session(req)?.accountId ?: return json(Status.UNAUTHORIZED, mapOf("error" to "Sign in to continue"))
    return withMediaUpload {
        val form = readMediaForm(req, MAX_ATTACHMENTS, PUBLISH_FIELDS)
        val post = service.publishAttachments(
            actor, form.fields["body"].orEmpty(), form.attachments,
            PostVisibility.parse(form.fields["visibility"] ?: "members"), PostFont.parse(form.fields["font"] ?: "sans"), PostSize.parse(form.fields["size"] ?: "normal")
        )
        json(Status.CREATED, post.response())
    }
}

internal fun mediaError(status: Status, message: String, retry: Boolean = false): Response =
    json(status, mapOf("error" to message)).let { if (retry) it.header("Retry-After", "5") else it }

internal inline fun safelyMedia(block: () -> Response): Response = safely { mediaGuarded(block) }

internal inline fun mediaGuarded(block: () -> Response): Response = try {
    block()
} catch (e: MediaUploadTooLarge) { mediaError(Status.REQUEST_ENTITY_TOO_LARGE, e.message!!) }
catch (e: AttachmentTooLarge) { mediaError(Status.REQUEST_ENTITY_TOO_LARGE, e.message!!) }
catch (e: MediaUploadTimedOut) { mediaError(Status.REQUEST_TIMEOUT, e.message!!, true) }
catch (e: MediaUploadBusy) { mediaError(Status.TOO_MANY_REQUESTS, e.message!!, true) }

private fun postAttachment(service: PostService, access: AccessControl?, req: Request): Response {
    val id = runCatching { PostId.parse(req.path("id")!!) }.getOrNull()
    val position = req.path("position")?.toIntOrNull()?.takeIf { it in 0 until Post.MAX_IMAGES }
    return streamOriginalMediaResponse(req, if (id == null || position == null) null else service.imageContent(id, position, access?.session(req)?.accountId))
}

private fun commentMultipart(service: PostService, access: AccessControl?, req: Request): Response {
    val actor = access?.session(req)?.accountId ?: return json(Status.UNAUTHORIZED, mapOf("error" to "Sign in to continue"))
    val post = PostId.parse(req.path("id")!!)
    if (service.get(post, actor) == null) return json(Status.NOT_FOUND, mapOf("error" to "Post not found"))
    return withMediaUpload {
        val form = readMediaForm(req, 1, setOf("body", "authorId"))
        val comment = service.addCommentAttachments(post, actor, form.fields["body"].orEmpty(), form.attachments)
        json(Status.CREATED, CommentResponse(comment.id.value.toString(), comment.authorId.value.toString(), comment.body, comment.createdAt.toString(),
            comment.parentId?.value?.toString(), commentAttachmentDescriptors("/posts/${post.value}/comments", comment.id, comment.attachmentType)))
    }
}
