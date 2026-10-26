package com.example.social.api

import com.example.social.application.*
import com.example.social.domain.*
import com.example.social.http.*
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.http4k.core.*
import org.http4k.routing.*
import java.util.UUID

private val commentMapper = jacksonObjectMapper()

fun commentRoutes(threads: CommentThreads, access: AccessControl): RoutingHttpHandler {
    fun handle(action: () -> Response): Response = try { apiSafely(action) }
        catch (error: NoSuchElementException) { apiJson(mapOf("error" to "Comment not found"), Status.NOT_FOUND) }
    fun post(request: Request) = PostId.parse(request.path("id")!!)
    fun comment(request: Request) = CommentId(UUID.fromString(request.path("comment")!!))
    fun page(request: Request, parent: CommentId?): Response {
        val limit = request.query("limit")?.let { it.toIntOrNull() ?: throw IllegalArgumentException("Invalid limit") } ?: 20
        val page = threads.page(post(request), parent, access.session(request)?.accountId, limit, request.query("after")?.let(::decodeCommentCursor))
        return apiJson(mapOf("comments" to page.comments.map(::commentJson), "next" to page.next?.let(::encodeCommentCursor)))
    }
    return routes(
        "/api/posts/{id}/comments" bind Method.GET to { request -> handle { page(request, null) } },
        "/api/posts/{id}/comments/{comment}/replies" bind Method.GET to { request -> handle { page(request, comment(request)) } },
        "/api/posts/{id}/comments/{comment}/replies" bind Method.POST to access.protect { request -> handle {
            val actor = access.session(request)?.accountId ?: throw AuthenticationFailed()
            val body = try { commentMapper.readTree(request.bodyString())?.get("body")?.takeIf { it.isTextual }?.asText() }
                catch (_: com.fasterxml.jackson.core.JsonProcessingException) { throw IllegalArgumentException("Invalid JSON") }
            require(body != null) { "Comment body is required" }
            val reply = threads.reply(post(request), comment(request), actor, body)
            apiJson(commentJson(threads.get(post(request), reply.id, actor)), Status.CREATED)
        } },
        "/api/posts/{id}/comments/{comment}/likes" bind Method.POST to access.protect { request -> handle {
            threads.like(post(request), comment(request), access.session(request)?.accountId ?: throw AuthenticationFailed(), true)
            Response(Status.NO_CONTENT)
        } },
        "/api/posts/{id}/comments/{comment}/likes" bind Method.DELETE to access.protect { request -> handle {
            threads.like(post(request), comment(request), access.session(request)?.accountId ?: throw AuthenticationFailed(), false)
            Response(Status.NO_CONTENT)
        } }
    )
}

private fun commentJson(row: ThreadComment): Map<String, Any?> = row.comment.let {
    mapOf("id" to it.id.value.toString(), "authorId" to it.authorId.value.toString(), "body" to it.body,
        "createdAt" to it.createdAt.toString(), "parentId" to it.parentId?.value?.toString(),
        "likeCount" to row.likeCount, "likedByViewer" to row.likedByViewer, "replyCount" to row.replyCount)
}
