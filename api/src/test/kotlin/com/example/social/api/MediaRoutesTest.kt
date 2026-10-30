package com.example.social.api

import com.example.social.application.*
import com.example.social.domain.*
import com.example.social.http.AccessControl
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.http4k.core.*
import java.time.Instant
import java.util.UUID
import kotlin.test.*

class MediaRoutesTest {
    private val actor = UserId.new()
    private val other = UserId.new()
    private val mapper = jacksonObjectMapper()
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1, 2, 3, 4, 5)

    private val access = AccessControl(object : Sessions {
        override fun create(session: Session) = Unit
        override fun find(digest: String, now: Instant) = when (digest) {
            tokenDigest("actor") -> Session(digest, actor, "csrf", now.plusSeconds(60))
            tokenDigest("other") -> Session(digest, other, "csrf", now.plusSeconds(60))
            else -> null
        }
        override fun delete(digest: String) = Unit
        override fun deleteForAccount(id: UserId) = Unit
    }, false)

    private class Posts : PostRepository {
        val data = linkedMapOf<PostId, Post>()
        val images = mutableMapOf<PostId, List<PostImage>>()
        override fun save(post: Post) = post.also { data[it.id] = it }
        override fun saveWithImages(post: Post, images: List<PostImage>) = post.also { data[it.id] = it; this.images[it.id] = images }
        override fun image(id: PostId, position: Int, viewer: UserId?) =
            if (readable(id, viewer)) images[id]?.getOrNull(position) else null
        val commentFiles = mutableMapOf<CommentId, PostImage>()
        override fun appendCommentWithAttachment(id: PostId, comment: Comment, attachment: PostImage?): Boolean {
            if (!appendComment(id, comment)) return false
            attachment?.let { commentFiles[comment.id] = it }
            return true
        }
        override fun commentAttachment(id: PostId, comment: CommentId, viewer: UserId?) =
            if (readable(id, viewer)) commentFiles[comment] else null
        override fun find(id: PostId) = data[id]
        override fun page(limit: Int, before: Instant?) = data.values.take(limit)
        override fun findByHashtag(tag: Hashtag, limit: Int) = data.values.filter { tag in it.hashtags }.take(limit)
        override fun update(id: PostId, change: (Post) -> Post) = data[id]?.let(change)?.also { data[id] = it }
    }

    private val boundary = "XBOUNDARYX"
    private fun multipart(path: String, fields: Map<String, String>, files: List<ByteArray>, token: String? = "actor"): Request {
        val out = java.io.ByteArrayOutputStream()
        fun w(s: String) = out.write(s.toByteArray())
        fields.forEach { (k, v) -> w("--$boundary\r\nContent-Disposition: form-data; name=\"$k\"\r\n\r\n$v\r\n") }
        files.forEachIndexed { i, b ->
            w("--$boundary\r\nContent-Disposition: form-data; name=\"attachments\"; filename=\"f$i.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n")
            out.write(b); w("\r\n")
        }
        w("--$boundary--\r\n")
        val request = Request(Method.POST, path).header("content-type", "multipart/form-data; boundary=$boundary").body(Body(java.nio.ByteBuffer.wrap(out.toByteArray())))
        return if (token == null) request else request.header("Authorization", "Bearer $token")
    }

    @Test fun `multipart publish uses session actor and exposes attachment descriptors`() {
        val repo = Posts()
        val app = access.protect(routes(PostService(repo), access))
        assertEquals(Status.UNAUTHORIZED, app(multipart("/posts", mapOf("body" to "Hi"), listOf(jpeg), null)).status)
        val response = app(multipart("/posts", mapOf("body" to "", "authorId" to UserId.new().value.toString()), listOf(jpeg)))
        assertEquals(Status.CREATED, response.status)
        val json = mapper.readTree(response.bodyString())
        assertEquals(actor.value.toString(), json["authorId"].asText())
        assertEquals("image/jpeg", json["attachments"][0]["mediaType"].asText())
        assertEquals("/posts/${json["id"].asText()}/attachments/0", json["attachments"][0]["url"].asText())
    }

    private fun publish(app: HttpHandler, visibility: String = "public"): com.fasterxml.jackson.databind.JsonNode =
        mapper.readTree(app(multipart("/posts", mapOf("body" to "x", "visibility" to visibility), listOf(jpeg))).bodyString())

    @Test fun `post originals are served with the post access rules and ranges`() {
        val app = access.protect(routes(PostService(Posts()), access))
        val id = publish(app, "private")["id"].asText()
        val url = "/posts/$id/attachments/0"
        assertEquals(Status.OK, app(Request(Method.GET, url).header("Authorization", "Bearer actor")).status)
        assertEquals(Status.NOT_FOUND, app(Request(Method.GET, url).header("Authorization", "Bearer other")).status)
        val ranged = app(Request(Method.GET, url).header("Authorization", "Bearer actor").header("Range", "bytes=0-1"))
        assertEquals(Status.PARTIAL_CONTENT, ranged.status)
        assertTrue(app(Request(Method.GET, "$url?download=1").header("Authorization", "Bearer actor")).header("Content-Disposition")!!.startsWith("attachment"))
    }

    @Test fun `multipart comment uses session actor and its original is served`() {
        val repo = Posts()
        val app = access.protect(routes(PostService(repo), access))
        val id = publish(app)["id"].asText()
        assertEquals(Status.UNAUTHORIZED, app(multipart("/posts/$id/comments", mapOf("body" to "c"), listOf(jpeg), null)).status)
        val response = app(multipart("/posts/$id/comments", mapOf("body" to "", "authorId" to other.value.toString()), listOf(jpeg)))
        assertEquals(Status.CREATED, response.status)
        val json = mapper.readTree(response.bodyString())
        assertEquals(actor.value.toString(), json["authorId"].asText())
        assertEquals("/posts/$id/comments/${json["id"].asText()}/attachment", json["attachments"][0]["url"].asText())
        assertEquals(Status.OK, app(Request(Method.GET, json["attachments"][0]["url"].asText()).header("Authorization", "Bearer other")).status)
        val get = mapper.readTree(app(Request(Method.GET, "/posts/$id").header("Authorization", "Bearer actor")).bodyString())
        assertEquals("image/jpeg", get["comments"][0]["attachments"][0]["mediaType"].asText())
    }

    @Test fun `oversized multipart maps to 413`() {
        val app = access.protect(routes(PostService(Posts()), access))
        val request = multipart("/posts", mapOf("body" to "x"), listOf(jpeg)).header("Content-Length", "${5L * 1024 * 1024 * 1024}")
        assertEquals(Status.REQUEST_ENTITY_TOO_LARGE, app(request).status)
    }

    @Test fun `feed and comment pages expose attachment descriptors`() {
        val post = PostId.new()
        val comment = Comment(authorId = actor, body = "c", attachmentType = "audio/mpeg")
        val queries = object : PostQueries {
            override fun feed(limit: Int, before: PostCursor?, viewer: UserId?, tag: Hashtag?, author: UserId?) = FeedPage(
                listOf(PostSummary(post, actor, "x", Instant.now(), emptySet(), 0, 0, false, imageCount = 2, mediaTypes = listOf("video/mp4", "image/png"))), null)
            override fun comments(post: PostId, limit: Int, after: CommentCursor?) = CommentPage(listOf(comment), null)
            override fun post(id: PostId, viewer: UserId?) = feed(1).posts.first()
        }
        val app = mobileReadRoutes(queries, object : Accounts {
            override fun create(account: Account) = Unit
            override fun update(account: Account) = Unit
            override fun byEmail(email: String): Account? = null
            override fun byId(id: UserId): Account? = null
            override fun putToken(token: AccountToken) = Unit
            override fun consumeToken(digest: String, purpose: String, now: Instant): UserId? = null
        }, access)
        val feed = mapper.readTree(app(Request(Method.GET, "/api/feed")).bodyString())["posts"][0]
        assertEquals("/api/posts/${post.value}/attachments/1", feed["attachments"][1]["url"].asText())
        assertEquals("video/mp4", feed["attachments"][0]["mediaType"].asText())
        val c = mapper.readTree(app(Request(Method.GET, "/api/posts/${post.value}/comments")).bodyString())["comments"][0]
        assertEquals("/api/posts/${post.value}/comments/${comment.id.value}/attachment", c["attachments"][0]["url"].asText())
    }

    @Test fun `api reply accepts multipart and api comment original is served`() {
        val post = PostId.new()
        val root = Comment(authorId = other, body = "Root")
        val rows = mutableMapOf(root.id to root)
        val files = mutableMapOf<CommentId, PostImage>()
        val repository = object : CommentThreadRepository {
            override fun get(postId: PostId, id: CommentId, viewer: UserId?) = rows[id]?.let { ThreadComment(it, 0, false, 0) }
            override fun page(postId: PostId, parent: CommentId?, viewer: UserId?, limit: Int, after: CommentCursor?) = ThreadPage(emptyList(), null)
            override fun append(postId: PostId, comment: Comment): Boolean = appendWithAttachment(postId, comment, null)
            override fun appendWithAttachment(postId: PostId, comment: Comment, attachment: PostImage?): Boolean {
                rows[comment.id] = comment; attachment?.let { files[comment.id] = it }; return true
            }
            override fun attachment(post: PostId, id: CommentId, viewer: UserId?) = files[id]
            override fun like(postId: PostId, id: CommentId, user: UserId, liked: Boolean) = true
        }
        val app = commentRoutes(CommentThreads(repository), access)
        val base = "/api/posts/${post.value}/comments/${root.id.value}/replies"
        assertEquals(Status.UNAUTHORIZED, app(multipart(base, mapOf("body" to "r"), listOf(jpeg), null)).status)
        val response = app(multipart(base, mapOf("body" to "", "authorId" to other.value.toString()), listOf(jpeg)))
        assertEquals(Status.CREATED, response.status)
        val json = mapper.readTree(response.bodyString())
        assertEquals(actor.value.toString(), json["authorId"].asText())
        val url = json["attachments"][0]["url"].asText()
        assertEquals("/api/posts/${post.value}/comments/${json["id"].asText()}/attachment", url)
        assertEquals(Status.OK, app(Request(Method.GET, url).header("Authorization", "Bearer other")).status)
        assertEquals(Status.NOT_FOUND, app(Request(Method.GET, "/api/posts/${post.value}/comments/${UUID.randomUUID()}/attachment")).status)
    }

    @Test fun `busy upload gate maps to 429 with Retry-After`() {
        val app = access.protect(routes(PostService(Posts()), access))
        val response = com.example.social.http.withMediaUpload { app(multipart("/posts", mapOf("body" to "x"), listOf(jpeg))) }
        assertEquals(Status.TOO_MANY_REQUESTS, response.status)
        assertNotNull(response.header("Retry-After"))
    }

    private val bigJpeg get() = ByteArray(AttachmentPolicy.MAX_IMAGE_BYTES + 1).also { jpeg.copyInto(it) }

    private fun unreadable(path: String): Request = Request(Method.POST, path)
        .header("content-type", "multipart/form-data; boundary=$boundary").header("Authorization", "Bearer actor")
        .body(Body(object : java.io.InputStream() { override fun read(): Int = throw AssertionError("body was consumed") }))

    @Test fun `attachment over the per-type limit maps to 413 on publish and comment`() {
        val repo = Posts()
        val app = access.protect(routes(PostService(repo), access))
        assertEquals(Status.REQUEST_ENTITY_TOO_LARGE, app(multipart("/posts", mapOf("body" to "x"), listOf(bigJpeg))).status)
        val id = publish(app)["id"].asText()
        assertEquals(Status.REQUEST_ENTITY_TOO_LARGE, app(multipart("/posts/$id/comments", mapOf("body" to "x"), listOf(bigJpeg))).status)
    }

    @Test fun `attachment over the per-type limit maps to 413 on api reply`() {
        val root = Comment(authorId = other, body = "Root")
        val repository = object : CommentThreadRepository {
            override fun get(postId: PostId, id: CommentId, viewer: UserId?) = ThreadComment(root, 0, false, 0)
            override fun page(postId: PostId, parent: CommentId?, viewer: UserId?, limit: Int, after: CommentCursor?) = ThreadPage(emptyList(), null)
            override fun append(postId: PostId, comment: Comment) = true
            override fun like(postId: PostId, id: CommentId, user: UserId, liked: Boolean) = true
        }
        val app = commentRoutes(CommentThreads(repository), access)
        val response = app(multipart("/api/posts/${PostId.new().value}/comments/${root.id.value}/replies", mapOf("body" to "x"), listOf(bigJpeg)))
        assertEquals(Status.REQUEST_ENTITY_TOO_LARGE, response.status)
    }

    @Test fun `comment on an unreadable post is 404 without consuming the body`() {
        val repo = Posts()
        val post = Post.publish(other, "secret").copy(visibility = PostVisibility.PRIVATE).also { repo.save(it) }
        val app = access.protect(routes(PostService(repo), access))
        assertEquals(Status.NOT_FOUND, app(unreadable("/posts/${post.id.value}/comments")).status)
    }

    @Test fun `reply on an unreadable post is 404 without consuming the body`() {
        val repository = object : CommentThreadRepository {
            override fun readable(post: PostId, viewer: UserId?) = false
            override fun get(postId: PostId, id: CommentId, viewer: UserId?): ThreadComment? = null
            override fun page(postId: PostId, parent: CommentId?, viewer: UserId?, limit: Int, after: CommentCursor?) = ThreadPage(emptyList(), null)
            override fun append(postId: PostId, comment: Comment) = true
            override fun like(postId: PostId, id: CommentId, user: UserId, liked: Boolean) = true
        }
        val app = commentRoutes(CommentThreads(repository), access)
        assertEquals(Status.NOT_FOUND, app(unreadable("/api/posts/${PostId.new().value}/comments/${UUID.randomUUID()}/replies")).status)
    }

    @Test fun `invalid or lying content length does not decide the outcome`() {
        val app = access.protect(routes(PostService(Posts()), access))
        listOf("abc", "-5", "1").forEach { length ->
            assertEquals(Status.CREATED, app(multipart("/posts", mapOf("body" to "x"), listOf(jpeg)).header("Content-Length", length)).status, length)
        }
    }
}
