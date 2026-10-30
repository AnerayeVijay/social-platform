package com.example.social.server

import com.example.social.api.routes as postRoutes
import com.example.social.application.*
import com.example.social.domain.*
import com.example.social.http.AccessControl
import com.example.social.web.webRoutes
import org.http4k.core.*
import org.http4k.routing.routes
import java.nio.ByteBuffer
import java.time.Instant
import kotlin.test.*

class MediaRoutingTest {
    @Test fun `composed server exposes multipart publishing and range downloads behind access control`() {
        val actor = UserId.new()
        val posts = Posts()
        val service = PostService(posts)
        val session = Session(tokenDigest("token"), actor, "csrf", Instant.now().plusSeconds(3600))
        val access = AccessControl(object : Sessions {
            override fun create(session: Session) = Unit
            override fun find(digest: String, now: Instant) = session.takeIf { it.digest == digest }
            override fun delete(digest: String) = Unit
            override fun deleteForAccount(id: UserId) = Unit
        }, false)
        val queries = object : PostQueries {
            override fun feed(limit: Int, before: PostCursor?, viewer: UserId?, tag: Hashtag?, author: UserId?) = FeedPage(emptyList(), null)
            override fun comments(post: PostId, limit: Int, after: CommentCursor?) = CommentPage(emptyList(), null)
        }
        val content = routes(webRoutes(queries, service, access), postRoutes(service, access))
        val server = serverRoutes(content, { Response(Status.NOT_FOUND) }, null, access)
        val bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0)
        fun upload() = Request(Method.POST, "/posts").header("Content-Type", "multipart/form-data; boundary=example")
            .body(Body(ByteBuffer.wrap(("--example\r\nContent-Disposition: form-data; name=\"visibility\"\r\n\r\npublic\r\n" +
                "--example\r\nContent-Disposition: form-data; name=\"attachments\"; filename=\"photo.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n")
                .toByteArray() + bytes + "\r\n--example--\r\n".toByteArray())))

        assertEquals(Status.UNAUTHORIZED, server(upload()).status)
        assertEquals(Status.FORBIDDEN, server(upload().header("Cookie", "session=token")).status)
        val published = server(upload().header("Authorization", "Bearer token"))
        assertEquals(Status.CREATED, published.status, published.bodyString())
        val post = posts.saved.values.single()
        assertEquals(actor, post.authorId)
        for (prefix in listOf("/posts", "/api/posts")) {
            val response = server(Request(Method.GET, "$prefix/${post.id.value}/attachments/0").header("Range", "bytes=0-1"))
            assertEquals(Status.PARTIAL_CONTENT, response.status)
            assertEquals("image/jpeg", response.header("Content-Type"))
            assertContentEquals(bytes.copyOfRange(0, 2), response.body.stream.use { it.readBytes() })
        }
    }

    private class Posts : PostRepository {
        val saved = linkedMapOf<PostId, Post>()
        private val media = linkedMapOf<PostId, List<PostImage>>()
        override fun save(post: Post) = post.also { saved[it.id] = it }
        override fun saveWithImages(post: Post, images: List<PostImage>) = save(post).also { media[it.id] = images }
        override fun image(id: PostId, position: Int, viewer: UserId?): PostImage? = error("Download must use the range-openable content")
        override fun imageContent(id: PostId, position: Int, viewer: UserId?) =
            media[id]?.getOrNull(position)?.takeIf { readable(id, viewer) }?.let(MediaContent::from)
        override fun find(id: PostId) = saved[id]
        override fun page(limit: Int, before: Instant?) = saved.values.toList()
        override fun findByHashtag(tag: Hashtag, limit: Int) = emptyList<Post>()
        override fun update(id: PostId, change: (Post) -> Post) = saved[id]?.let(change)?.also { saved[id] = it }
    }
}
