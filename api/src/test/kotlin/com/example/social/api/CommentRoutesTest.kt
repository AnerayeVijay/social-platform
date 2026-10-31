package com.example.social.api

import com.example.social.application.*
import com.example.social.domain.*
import com.example.social.http.*
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.http4k.core.*
import java.time.Instant
import kotlin.test.*

class CommentRoutesTest {
    @Test fun `comment API supports authenticated replies and idempotent likes with viewer-aware pages`() {
        val postId = PostId.new()
        val actor = UserId.new()
        val root = Comment(authorId = UserId.new(), body = "Root")
        val rows = mutableMapOf(root.id to root)
        val likes = mutableSetOf<Pair<CommentId, UserId>>()
        val repository = object : CommentThreadRepository {
            override fun get(post: PostId, id: CommentId, viewer: UserId?) = rows[id]?.takeIf { post == postId }?.let {
                ThreadComment(it, likes.count { pair -> pair.first == id }.toLong(), id to viewer in likes, rows.values.count { reply -> reply.parentId == id }.toLong())
            }
            override fun page(post: PostId, parent: CommentId?, viewer: UserId?, limit: Int, after: CommentCursor?): ThreadPage {
                val comments = rows.values.filter { it.parentId == parent }.sortedBy { it.id.value.toString() }
                    .filter { after == null || it.id.value.toString() > after.id.value.toString() }
                val page = comments.take(limit)
                return ThreadPage(page.map { get(post, it.id, viewer)!! }, if (comments.size > limit) page.last().let { CommentCursor(it.createdAt, it.id) } else null)
            }
            override fun append(post: PostId, comment: Comment): Boolean {
                if (post != postId || comment.parentId !in rows) return false
                rows[comment.id] = comment
                return true
            }
            override fun like(post: PostId, id: CommentId, user: UserId, liked: Boolean): Boolean {
                if (get(post, id, user) == null) return false
                if (liked) likes.add(id to user) else likes.remove(id to user)
                return true
            }
        }
        val access = AccessControl(object : Sessions {
            override fun find(digest: String, now: Instant) = if (digest == tokenDigest("actor")) Session(digest, actor, "csrf", now.plusSeconds(60)) else null
            override fun create(session: Session) = Unit
            override fun delete(digest: String) = Unit
            override fun deleteForAccount(id: UserId) = Unit
        }, false)
        val app = commentRoutes(CommentThreads(repository), access)
        val mapper = jacksonObjectMapper()
        val base = "/api/posts/${postId.value}/comments/${root.id.value}"
        fun call(method: Method, path: String, body: String = "") = app(Request(method, path).header("Authorization", "Bearer actor")
            .header("content-type", "application/json").body(body))
        assertEquals(Status.UNAUTHORIZED, app(Request(Method.POST, "$base/replies").body("{\"body\":\"Reply\"}")).status)
        assertEquals(Status.FORBIDDEN, app(Request(Method.POST, "$base/likes").header("Cookie", "session=actor")).status)
        val reply = call(Method.POST, "$base/replies", """{"body":" Reply ","authorId":"${UserId.new().value}"}""")
        assertEquals(Status.CREATED, reply.status)
        val json = mapper.readTree(reply.bodyString())
        assertEquals(actor.value.toString(), json["authorId"].asText())
        assertEquals(root.id.value.toString(), json["parentId"].asText())
        val replyId = json["id"].asText()
        assertEquals(Status.CREATED, call(Method.POST, "/api/posts/${postId.value}/comments/$replyId/replies", """{"body":"Nested"}""").status)
        repeat(2) { assertEquals(Status.NO_CONTENT, call(Method.POST, "$base/likes").status) }
        val rootPage = mapper.readTree(call(Method.GET, "/api/posts/${postId.value}/comments").bodyString())["comments"][0]
        assertEquals(1, rootPage["likeCount"].asInt())
        assertTrue(rootPage["likedByViewer"].asBoolean())
        assertEquals(1, rootPage["replyCount"].asInt())
        val anonymous = mapper.readTree(app(Request(Method.GET, "/api/posts/${postId.value}/comments")).bodyString())["comments"][0]
        assertFalse(anonymous["likedByViewer"].asBoolean())
        repeat(2) { assertEquals(Status.NO_CONTENT, call(Method.DELETE, "$base/likes").status) }
        assertEquals(0, mapper.readTree(call(Method.GET, "/api/posts/${postId.value}/comments").bodyString())["comments"][0]["likeCount"].asInt())
        call(Method.POST, "$base/replies", """{"body":"Another"}""")
        val first = mapper.readTree(call(Method.GET, "$base/replies?limit=1").bodyString())
        val cursor = java.net.URLEncoder.encode(first["next"].asText(), Charsets.UTF_8)
        val second = mapper.readTree(call(Method.GET, "$base/replies?limit=1&after=$cursor").bodyString())
        assertNotEquals(first["comments"][0]["id"].asText(), second["comments"][0]["id"].asText())
        assertTrue(second["next"].isNull)
        assertEquals(Status.BAD_REQUEST, call(Method.GET, "$base/replies?limit=0").status)
        assertEquals(Status.BAD_REQUEST, call(Method.GET, "$base/replies?after=bad").status)
        assertEquals(Status.BAD_REQUEST, call(Method.POST, "$base/replies", """{"body":" "}""").status)
        assertEquals(Status.BAD_REQUEST, call(Method.POST, "$base/replies", "not json").status)
        assertEquals(Status.NOT_FOUND, call(Method.POST, "/api/posts/${PostId.new().value}/comments/${root.id.value}/likes").status)
        assertEquals(Status.NOT_FOUND, call(Method.GET, "/api/posts/${postId.value}/comments/${CommentId.new().value}/replies").status)
    }
}
