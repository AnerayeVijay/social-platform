package com.example.social.api

import com.example.social.application.PostService
import com.example.social.domain.PostFont
import com.example.social.domain.PostId
import com.example.social.domain.PostSize
import com.example.social.domain.UserId
import com.example.social.domain.PostVisibility
import com.example.social.http.AccessControl
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
    val font: String = "sans", val size: String = "normal"
)
data class CommentResponse(val id: String, val authorId: String, val body: String, val createdAt: String, val parentId: String? = null)

private fun com.example.social.domain.Post.response() = PostResponse(
    id.value.toString(), authorId.value.toString(), body, hashtags.map { it.value }.sorted(),
    likes.map { it.value.toString() }.sorted(), likes.size,
    comments.map { CommentResponse(it.id.value.toString(), it.authorId.value.toString(), it.body, it.createdAt.toString(), it.parentId?.value?.toString()) },
    createdAt.toString(), visibility.name.lowercase(), font.name.lowercase(), size.name.lowercase()
)

fun routes(service: PostService, access: AccessControl? = null): RoutingHttpHandler = routes(
    "/health" bind GET to { Response(Status.OK).body("""{"status":"ok"}""").header("content-type", "application/json") },

    "/posts" bind POST to { req ->
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

    "/posts/{id}/comments" bind POST to { req ->
        safely {
            val comment: CommentRequest = mapper.readValue(req.bodyString())
            json(Status.CREATED, service.comment(
                PostId.parse(req.path("id")!!), access?.session(req)?.accountId ?: UserId.parse(comment.authorId), comment.body
            ).response())
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
