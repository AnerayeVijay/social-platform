package com.example.social.web

import com.example.social.application.*
import com.example.social.domain.*
import com.example.social.http.*
import org.http4k.core.*
import org.jsoup.Jsoup
import java.io.ByteArrayInputStream
import kotlin.test.*

/** Original-file media on posts, comments and replies through the website HTTP seam. */
class MediaWebTest {
    private val m = MediaWebFixture()
    private val boundary = "MediaBoundary123"
    private fun field(name: String, value: String) = "--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray()
    private fun file(bytes: ByteArray, filename: String, name: String = "attachments") =
        "--$boundary\r\nContent-Disposition: form-data; name=\"$name\"; filename=\"$filename\"\r\nContent-Type: application/octet-stream\r\n\r\n".toByteArray() + bytes + "\r\n".toByteArray()
    private fun upload(path: String, vararg parts: ByteArray, who: String = "alice") = m.app(Request(Method.POST, path)
        .header("Cookie", "session=$who").header("X-CSRF-Token", "csrf").header("content-type", "multipart/form-data; boundary=$boundary")
        .body(ByteArrayInputStream(parts.fold(ByteArray(0)) { all, part -> all + part } + "--$boundary--\r\n".toByteArray())))
    private fun get(path: String, who: String? = "alice", range: String? = null) = m.app(Request(Method.GET, path)
        .let { if (who == null) it else it.header("Cookie", "session=$who") }.let { if (range == null) it else it.header("Range", range) })

    private fun publishMixed(visibility: String = "public"): Post {
        val response = upload("/web/posts", field("body", "Media #mix"), field("visibility", visibility),
            file(MediaWebFixture.gif(), "dance.gif"), file(MediaWebFixture.mp4(), "clip.mp4"), file(MediaWebFixture.mp3(), "song.mp3"))
        assertEquals(Status.SEE_OTHER, response.status, response.bodyString())
        return m.rows.values.single()
    }

    @Test fun `post with GIF video and audio keeps the original files and types in order`() {
        val post = publishMixed()
        assertEquals(listOf("image/gif", "video/mp4", "audio/mpeg"), post.mediaTypes)
        assertContentEquals(MediaWebFixture.gif(), get("/media/posts/${post.id.value}/0").body.stream.readAllBytes())
        val video = get("/media/posts/${post.id.value}/1")
        assertEquals("video/mp4", video.header("Content-Type"))
        assertEquals("nosniff", video.header("X-Content-Type-Options"))
        assertEquals("private, no-store", video.header("Cache-Control"))
        assertTrue(video.header("Content-Disposition")!!.startsWith("inline"))
        assertTrue(get("/media/posts/${post.id.value}/1?download=1").header("Content-Disposition")!!.startsWith("attachment"))
        val partial = get("/media/posts/${post.id.value}/1", range = "bytes=0-3")
        assertEquals(Status.PARTIAL_CONTENT, partial.status)
        assertEquals(4, partial.body.stream.readAllBytes().size)
    }

    @Test fun `conversation shows each attachment by type with an always visible download link`() {
        val post = publishMixed()
        val doc = Jsoup.parse(get("/web/posts/${post.id.value}").bodyString())
        val base = "/media/posts/${post.id.value}"
        assertEquals("$base/0", doc.select(".post-gallery img").single().attr("src"))
        assertEquals("$base/1", doc.select(".post-gallery video[controls]").single().attr("src"))
        assertEquals("$base/2", doc.select(".post-gallery audio[controls]").single().attr("src"))
        assertTrue(doc.select(".post-gallery video, .post-gallery audio").none { it.hasAttr("autoplay") })
        assertEquals(listOf("$base/0?download=1", "$base/1?download=1", "$base/2?download=1"), doc.select(".post-gallery a.media-download").map { it.attr("href") })
        assertEquals(listOf("$base/0", "$base/1", "$base/2"), Jsoup.parse(get("/").bodyString()).select(".post-gallery [src]").map { it.attr("src") })
    }

    @Test fun `legacy posts without media types still render as images`() {
        val id = PostId.new()
        m.repository.saveWithImages(Post(id, m.f.alice, "old", emptySet(), java.time.Instant.now(), imageCount = 2, visibility = PostVisibility.PUBLIC), List(2) { PostImage("image/jpeg", byteArrayOf(1)) })
        assertEquals(2, Jsoup.parse(get("/web/posts/${id.value}").bodyString()).select(".post-gallery img").size)
    }

    @Test fun `media only comment and reply are stored and rendered with their attachments`() {
        val post = publishMixed()
        assertEquals(Status.SEE_OTHER, upload("/web/posts/${post.id.value}/comments", file(MediaWebFixture.mp4(), "c.mp4"), who = "bob").status)
        val comment = m.comments.single()
        assertEquals("", comment.body); assertEquals("video/mp4", comment.attachmentType)
        val reply = upload("/web/posts/${post.id.value}/comments/${comment.id.value}/replies", file(MediaWebFixture.mp3(), "r.mp3"), who = "alice")
        assertEquals(Status.SEE_OTHER, reply.status, reply.bodyString())
        assertEquals("audio/mpeg", m.comments.last().attachmentType)
        assertEquals(comment.id, m.comments.last().parentId)
        val commentUrl = "/media/posts/${post.id.value}/comments/${comment.id.value}"
        val doc = Jsoup.parse(get("/web/posts/${post.id.value}").bodyString())
        assertEquals(commentUrl, doc.select("#comment-${comment.id.value} .comment-media video").single().attr("src"))
        assertEquals("$commentUrl?download=1", doc.select("#comment-${comment.id.value} .comment-media a.media-download").single().attr("href"))
        val replyUrl = "/media/posts/${post.id.value}/comments/${m.comments.last().id.value}"
        assertEquals(replyUrl, Jsoup.parse(get("/web/posts/${post.id.value}/comments/${comment.id.value}/replies").bodyString()).select("audio").single().attr("src"))
        assertEquals("video/mp4", get(commentUrl).header("Content-Type"))
        assertEquals("audio/mpeg", get(replyUrl).header("Content-Type"))
    }

    @Test fun `comment media follows the post audience`() {
        val post = publishMixed("private")
        assertEquals(Status.SEE_OTHER, upload("/web/posts/${post.id.value}/comments", file(MediaWebFixture.mp4(), "c.mp4"), who = "alice").status)
        val url = "/media/posts/${post.id.value}/comments/${m.comments.single().id.value}"
        assertEquals(Status.OK, get(url).status)
        assertEquals(Status.NOT_FOUND, get(url, "bob").status)
        assertEquals(Status.NOT_FOUND, get(url, null).status)
        assertEquals(Status.NOT_FOUND, get("/media/posts/${post.id.value}/comments/not-a-uuid").status)
    }

    @Test fun `limits are four per post and one per comment or reply and unsupported files are rejected`() {
        val gif = MediaWebFixture.gif()
        assertEquals(Status.BAD_REQUEST, upload("/web/posts", field("body", "x"), *Array(5) { file(gif, "$it.gif") }).status)
        assertEquals(Status.BAD_REQUEST, upload("/web/posts", file("not media".toByteArray(), "a.txt")).status)
        assertTrue(m.rows.isEmpty())
        val post = publishMixed()
        assertEquals(Status.BAD_REQUEST, upload("/web/posts/${post.id.value}/comments", file(gif, "1.gif"), file(gif, "2.gif")).status)
        assertEquals(Status.BAD_REQUEST, upload("/web/posts/${post.id.value}/comments", field("body", " ")).status)
        assertTrue(m.comments.isEmpty())
    }

    @Test fun `uploads need a session and csrf token before the body is read`() {
        val body = ByteArrayInputStream(file(MediaWebFixture.gif(), "a.gif"))
        val noCsrf = m.app(Request(Method.POST, "/web/posts").header("Cookie", "session=alice").header("content-type", "multipart/form-data; boundary=$boundary").body(body))
        assertEquals(Status.FORBIDDEN, noCsrf.status)
        assertEquals(Status.UNAUTHORIZED, m.app(Request(Method.POST, "/web/posts").header("content-type", "multipart/form-data; boundary=$boundary").body("x")).status)
        assertTrue(m.rows.isEmpty())
    }

    @Test fun `plain text comments and posts keep working without scripting`() {
        val post = publishMixed()
        val response = m.app(Request(Method.POST, "/web/posts/${post.id.value}/comments").header("Cookie", "session=alice")
            .header("content-type", "application/x-www-form-urlencoded").body("csrf=csrf&body=hello"))
        assertEquals(Status.SEE_OTHER, response.status)
    }

    @Test fun `hidden posts refuse comment and reply uploads before the body is read`() {
        val post = publishMixed("private")
        class Spy : java.io.InputStream() { var read = 0; override fun read(): Int { read++; return -1 } }
        fun send(path: String): Int {
            val spy = Spy()
            val status = m.app(Request(Method.POST, path).header("Cookie", "session=bob").header("X-CSRF-Token", "csrf")
                .header("content-type", "multipart/form-data; boundary=$boundary").body(spy)).status
            assertEquals(Status.NOT_FOUND, status); return spy.read
        }
        assertEquals(0, send("/web/posts/${post.id.value}/comments"))
        assertEquals(0, send("/web/posts/${post.id.value}/comments/${java.util.UUID.randomUUID()}/replies"))
    }

    @Test fun `ranged reads stream a huge original without loading it and respect visibility`() {
        val size = 100L * 1024 * 1024
        val opened = mutableListOf<Pair<Long, Long>>()
        val id = PostId.new()
        val repo = object : PostRepository by m.repository {
            override fun readable(id: PostId, viewer: UserId?) = viewer == m.f.alice
            override fun image(id: PostId, position: Int, viewer: UserId?): PostImage = error("original must not be loaded whole")
            override fun imageContent(id: PostId, position: Int, viewer: UserId?) =
                if (readable(id, viewer) && position == 0) MediaContent("video/mp4", size) { offset, length ->
                    opened += offset to length; ByteArrayInputStream(ByteArray(length.toInt()) { 7 })
                } else null
        }
        val app = m.f.access.protect(webRoutes(m.queries, PostService(repo), m.f.access, m.f.accounts))
        fun get(who: String?, range: String?) = app(Request(Method.GET, "/media/posts/${id.value}/0").let { if (who == null) it else it.header("Cookie", "session=$who") }
            .let { if (range == null) it else it.header("Range", range) })
        val partial = get("alice", "bytes=0-0")
        assertEquals(Status.PARTIAL_CONTENT, partial.status)
        assertEquals("bytes 0-0/$size", partial.header("Content-Range"))
        assertContentEquals(byteArrayOf(7), partial.body.stream.readAllBytes())
        assertEquals(listOf(0L to 1L), opened)
        assertEquals(Status.NOT_FOUND, get("bob", "bytes=0-0").status)
        assertEquals(Status.NOT_FOUND, get(null, null).status)
        assertEquals(1, opened.size)
    }
}
