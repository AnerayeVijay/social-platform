package com.example.social.api

import com.example.social.application.*
import com.example.social.domain.*
import com.example.social.http.*
import org.http4k.core.*
import java.time.Instant
import kotlin.test.*

class PostAudienceRoutesTest {
    @Test fun `publish defaults to followers and only owner can edit audience while legacy routes hide restricted posts`() {
        val owner = UserId.new()
        val other = UserId.new()
        val rows = mutableMapOf<PostId, Post>()
        val repository = object : PostRepository {
            override fun save(post: Post) = post.also { rows[it.id] = it }
            override fun find(id: PostId) = rows[id]
            override fun page(limit: Int, before: Instant?) = rows.values.take(limit)
            override fun findByHashtag(tag: Hashtag, limit: Int) = rows.values.filter { tag in it.hashtags }.take(limit)
            override fun update(id: PostId, change: (Post) -> Post) = rows[id]?.let(change)?.also { rows[id] = it }
        }
        val access = AccessControl(object : Sessions {
            override fun find(digest: String, now: Instant) = when (digest) {
                tokenDigest("owner") -> Session(digest, owner, "csrf", now.plusSeconds(60))
                tokenDigest("other") -> Session(digest, other, "csrf", now.plusSeconds(60))
                else -> null
            }
            override fun create(session: Session) = Unit
            override fun delete(digest: String) = Unit
            override fun deleteForAccount(id: UserId) = Unit
        }, false)
        val app = access.protect(routes(PostService(repository), access))
        fun call(method: Method, path: String, who: String? = "owner", body: String = ""): Response {
            val request = Request(method, path).body(body)
            return app(if (who == null) request else request.header("Authorization", "Bearer $who"))
        }
        assertEquals(Status.CREATED, call(Method.POST, "/posts", body = """{"body":"Secret #audience"}""").status)
        val post = rows.values.single()
        assertEquals(PostVisibility.MEMBERS, post.visibility)
        val path = "/posts/${post.id.value}"
        assertEquals(Status.NOT_FOUND, call(Method.GET, path, null).status)
        assertEquals(Status.NOT_FOUND, call(Method.GET, path, "other").status)
        assertFalse(call(Method.GET, "/posts", "other").bodyString().contains("Secret"))
        assertFalse(call(Method.GET, "/hashtags/audience/posts", "other").bodyString().contains("Secret"))
        assertEquals(Status.OK, call(Method.GET, path).status)
        assertEquals(Status.NOT_FOUND, call(Method.POST, "$path/likes", "other", "{}").status)
        assertEquals(Status.NOT_FOUND, call(Method.POST, "$path/comments", "other", """{"body":"No"}""").status)
        assertEquals(Status.NOT_FOUND, call(Method.PUT, "$path/visibility", "other", """{"visibility":"public"}""").status)
        assertEquals(Status.FORBIDDEN, app(Request(Method.PUT, "$path/visibility").header("Cookie", "session=owner").body("""{"visibility":"public"}""")).status)
        assertEquals(Status.NO_CONTENT, call(Method.PUT, "$path/visibility", body = """{"visibility":"public"}""").status)
        assertTrue(call(Method.GET, path, null).bodyString().contains("\"visibility\":\"public\""))
        assertEquals(Status.OK, call(Method.POST, "$path/likes", "other", "{}").status)
        assertEquals(Status.BAD_REQUEST, call(Method.PUT, "$path/visibility", body = """{"visibility":"unknown"}""").status)
        assertEquals(Status.NO_CONTENT, call(Method.PUT, "$path/visibility", body = """{"visibility":"private"}""").status)
        assertEquals(Status.NOT_FOUND, call(Method.GET, path, "other").status)
        assertEquals(Status.BAD_REQUEST, call(Method.POST, "/posts", body = """{"body":"Invalid","visibility":"unknown"}""").status)
    }

    @Test fun `owner edits and deletes a post while others and anonymous callers are refused`() {
        val owner = UserId.new()
        val other = UserId.new()
        val rows = mutableMapOf<PostId, Post>()
        val repository = object : PostRepository {
            override fun save(post: Post) = post.also { rows[it.id] = it }
            override fun find(id: PostId) = rows[id]
            override fun page(limit: Int, before: Instant?) = rows.values.take(limit)
            override fun findByHashtag(tag: Hashtag, limit: Int) = emptyList<Post>()
            override fun update(id: PostId, change: (Post) -> Post) = rows[id]?.let(change)?.also { rows[id] = it }
            override fun delete(id: PostId, owner: UserId) = rows[id]?.takeIf { it.authorId == owner }?.let { rows.remove(id) } != null
        }
        val access = AccessControl(object : Sessions {
            override fun find(digest: String, now: Instant) = when (digest) {
                tokenDigest("owner") -> Session(digest, owner, "csrf", now.plusSeconds(60))
                tokenDigest("other") -> Session(digest, other, "csrf", now.plusSeconds(60))
                else -> null
            }
            override fun create(session: Session) = Unit
            override fun delete(digest: String) = Unit
            override fun deleteForAccount(id: UserId) = Unit
        }, false)
        val app = access.protect(routes(PostService(repository), access))
        fun call(method: Method, path: String, who: String? = "owner", body: String = ""): Response {
            val request = Request(method, path).body(body)
            return app(if (who == null) request else request.header("Authorization", "Bearer $who"))
        }
        assertEquals(Status.CREATED, call(Method.POST, "/posts", body = """{"body":"Hello #old"}""").status)
        val path = "/posts/${rows.values.single().id.value}"
        assertEquals(Status.UNAUTHORIZED, call(Method.PUT, path, null, """{"body":"Anon"}""").status)
        assertEquals(Status.NOT_FOUND, call(Method.PUT, path, "other", """{"body":"Hijack"}""").status)
        val edited = call(Method.PUT, path, body = """{"body":"Hello #new"}""")
        assertEquals(Status.OK, edited.status)
        assertTrue(edited.bodyString().contains("\"hashtags\":[\"new\"]"))
        assertEquals(Status.BAD_REQUEST, call(Method.PUT, path, body = """{"body":"   "}""").status)
        assertEquals(Status.UNAUTHORIZED, call(Method.DELETE, path, null).status)
        assertEquals(Status.NOT_FOUND, call(Method.DELETE, path, "other").status)
        assertEquals(Status.NO_CONTENT, call(Method.DELETE, path).status)
        assertEquals(Status.NOT_FOUND, call(Method.GET, path).status)
    }

    @Test fun `publish accepts font and size with sans normal defaults and rejects unknown presets`() {
        val owner = UserId.new()
        val rows = mutableMapOf<PostId, Post>()
        val repository = object : PostRepository {
            override fun save(post: Post) = post.also { rows[it.id] = it }
            override fun find(id: PostId) = rows[id]
            override fun page(limit: Int, before: Instant?) = rows.values.take(limit)
            override fun findByHashtag(tag: Hashtag, limit: Int) = emptyList<Post>()
            override fun update(id: PostId, change: (Post) -> Post) = rows[id]?.let(change)?.also { rows[id] = it }
        }
        val app = routes(PostService(repository))
        fun publish(json: String) = app(Request(Method.POST, "/posts").body(json))
        assertEquals(Status.CREATED, publish("""{"authorId":"${owner.value}","body":"plain"}""").status)
        assertEquals(PostFont.SANS to PostSize.NORMAL, rows.values.single().let { it.font to it.size })
        val styled = publish("""{"authorId":"${owner.value}","body":"styled","font":"serif","size":"large"}""")
        assertTrue(styled.bodyString().contains("\"font\":\"serif\"") && styled.bodyString().contains("\"size\":\"large\""))
        assertEquals(Status.BAD_REQUEST, publish("""{"authorId":"${owner.value}","body":"x","font":"comic-sans"}""").status)
        assertEquals(Status.BAD_REQUEST, publish("""{"authorId":"${owner.value}","body":"x","size":"huge"}""").status)
    }
}
