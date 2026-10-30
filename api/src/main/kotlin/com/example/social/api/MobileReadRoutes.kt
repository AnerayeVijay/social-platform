package com.example.social.api

import com.example.social.http.*

import com.example.social.application.*
import com.example.social.domain.*
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.http4k.core.*
import org.http4k.routing.*
import java.time.Instant
import java.util.UUID

private val apiMapper = jacksonObjectMapper()
internal fun apiJson(value: Any, status: Status = Status.OK): Response = Response(status)
    .header("content-type", "application/json").header("Cache-Control", "no-store").body(apiMapper.writeValueAsString(value))

fun mobileReadRoutes(queries: PostQueries, accounts: Accounts, access: AccessControl? = null, follows: FollowService? = null): RoutingHttpHandler = routes(
    "/api/feed" bind Method.GET to { request ->
        apiSafely {
            val limit = request.query("limit")?.let { it.toIntOrNull() ?: throw IllegalArgumentException("Invalid limit") } ?: 20
            val viewer = access?.session(request)?.accountId
            val before = request.query("before")?.let(::decodePostCursor)
            val tag = request.query("tag")?.let(Hashtag::from)
            val author = request.query("author")?.let(UserId::parse)
            val page = when (request.query("scope") ?: "all") {
                "all" -> queries.feed(limit, before, viewer, tag, author)
                "following" -> queries.followingFeed(viewer ?: throw AuthenticationFailed(), limit, before, tag, author)
                else -> throw IllegalArgumentException("Invalid feed scope")
            }
            apiJson(mapOf("posts" to page.posts.map { post -> mapOf(
                "id" to post.id.value.toString(), "authorId" to post.authorId.value.toString(), "body" to post.body,
                "createdAt" to post.createdAt.toString(), "hashtags" to post.hashtags.map { it.value }.sorted(),
                "likeCount" to post.likeCount, "commentCount" to post.commentCount, "likedByViewer" to post.likedByViewer, "visibility" to post.visibility.name.lowercase(),
                "font" to post.font.name.lowercase(), "size" to post.size.name.lowercase()
            ) }, "next" to page.next?.let(::encodePostCursor)))
        }
    },
    "/api/posts/{id}/comments" bind Method.GET to { request ->
        apiSafely {
            val after = request.query("after")?.let {
                val parts = it.split('|'); require(parts.size == 2)
                CommentCursor(Instant.parse(parts[0]), CommentId(UUID.fromString(parts[1])))
            }
            val limit = request.query("limit")?.let { it.toIntOrNull() ?: throw IllegalArgumentException("Invalid limit") } ?: 20
            val id = PostId.parse(request.path("id")!!)
            if (queries.post(id, access?.session(request)?.accountId) == null) return@apiSafely apiJson(mapOf("error" to "Post not found"), Status.NOT_FOUND)
            val page = queries.visibleComments(id, limit, after, access?.session(request)?.accountId)
            apiJson(mapOf("comments" to page.comments.map { mapOf("id" to it.id.value.toString(), "authorId" to it.authorId.value.toString(),
                "body" to it.body, "createdAt" to it.createdAt.toString()) }, "next" to page.next?.let { "${it.createdAt}|${it.id.value}" }))
        }
    },
    "/api/profiles/{id}" bind Method.GET to { request ->
        apiSafely {
            val id = UserId.parse(request.path("id")!!)
            val viewer = access?.session(request)?.accountId
            apiJson(mapOf("id" to id.value.toString(), "displayName" to (accounts.byId(id)?.displayName ?: "Legacy member"),
                "followState" to if (viewer != null && follows != null) follows.state(viewer, id).name.lowercase() else "none"))
        }
    }
)

internal fun apiSafely(action: () -> Response): Response = try { action() }
catch (_: AuthenticationFailed) { apiJson(mapOf("error" to "Authentication failed"), Status.UNAUTHORIZED) }
catch (error: IllegalArgumentException) { apiJson(mapOf("error" to (error.message ?: "Invalid request")), Status.BAD_REQUEST) }
