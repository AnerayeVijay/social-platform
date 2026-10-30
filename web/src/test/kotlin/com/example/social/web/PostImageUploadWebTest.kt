package com.example.social.web

import com.example.social.application.*
import com.example.social.domain.*
import com.example.social.infrastructure.RasterMemberImages
import org.http4k.core.*
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Instant
import javax.imageio.ImageIO
import kotlin.test.*

/** Multipart post publishing and post media through the web HTTP seam with the real raster processor. */
class PostImageUploadWebTest {
    private val f = FollowWebFixture()
    private val rows = mutableMapOf<PostId, Post>()
    private val images = mutableMapOf<PostId, List<PostImage>>()
    private val repository = object : PostRepository {
        override fun readable(id: PostId, viewer: UserId?) = rows[id]?.let { it.authorId == viewer || it.visibility == PostVisibility.PUBLIC } == true
        override fun save(post: Post) = saveWithImages(post, emptyList())
        override fun saveWithImages(post: Post, images: List<PostImage>): Post {
            require(images.size == post.imageCount)
            rows[post.id] = post; this@PostImageUploadWebTest.images[post.id] = images; return post
        }
        override fun image(id: PostId, position: Int, viewer: UserId?) = if (readable(id, viewer)) images[id]?.getOrNull(position) else null
        override fun find(id: PostId) = rows[id]
        override fun page(limit: Int, before: Instant?) = rows.values.take(limit)
        override fun findByHashtag(tag: Hashtag, limit: Int) = emptyList<Post>()
        override fun update(id: PostId, change: (Post) -> Post) = rows[id]?.let(change)?.also { rows[id] = it }
    }
    private val queries = object : PostQueries {
        override fun feed(limit: Int, before: PostCursor?, viewer: UserId?, tag: Hashtag?, author: UserId?) = FeedPage(emptyList(), null)
        override fun comments(post: PostId, limit: Int, after: CommentCursor?) = CommentPage(emptyList(), null)
    }
    private val gate = ImageProcessingGate()
    private val clock = object : java.time.Clock() {
        var now: Instant = Instant.parse("2026-10-30T12:00:00Z")
        override fun instant() = now
        override fun getZone(): java.time.ZoneId = java.time.ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId) = this
    }
    private val app = f.access.protect(webRoutes(queries, PostService(repository, RasterMemberImages(), gate), f.access, f.accounts, uploadClock = clock))

    private val boundary = "PostBoundary123"
    private fun field(name: String, value: String) =
        "--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray()
    private fun file(bytes: ByteArray, name: String = "images", type: String = "image/png", filename: String = "photo.png") =
        "--$boundary\r\nContent-Disposition: form-data; name=\"$name\"; filename=\"$filename\"\r\nContent-Type: $type\r\n\r\n".toByteArray() + bytes + "\r\n".toByteArray()
    private fun end() = "--$boundary--\r\n".toByteArray()
    private fun multipart(vararg parts: ByteArray, who: String = "alice") = app(Request(Method.POST, "/web/posts")
        .header("Cookie", "session=$who").header("X-CSRF-Token", "csrf")
        .header("content-type", "multipart/form-data; boundary=$boundary")
        .body(ByteArrayInputStream(parts.fold(ByteArray(0)) { all, part -> all + part } + end())))
    private fun media(path: String, who: String? = "alice") =
        app(Request(Method.GET, path).let { if (who == null) it else it.header("Cookie", "session=$who") })

    private fun png(width: Int, height: Int) = ByteArrayOutputStream().also {
        ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_RGB).apply {
            createGraphics().run { color = Color.BLUE; fillRect(0, 0, width, height); dispose() }
        }, "png", it)
    }.toByteArray()

    @Test fun `text and image post is stored with normalized JPEG served privately`() {
        val response = multipart(field("body", "Look #sky"), field("visibility", "public"), field("font", "serif"), field("size", "large"), file(png(3000, 1500)))
        assertEquals(Status.SEE_OTHER, response.status, response.bodyString())
        val post = rows.values.single()
        assertEquals("Look #sky", post.body)
        assertEquals(PostVisibility.PUBLIC, post.visibility)
        assertEquals(PostFont.SERIF, post.font); assertEquals(PostSize.LARGE, post.size)
        assertEquals(1, post.imageCount)
        val served = media("/media/posts/${post.id.value}/0")
        assertEquals(Status.OK, served.status)
        assertEquals("image/jpeg", served.header("content-type"))
        assertEquals("nosniff", served.header("X-Content-Type-Options"))
        assertEquals("private, no-store", served.header("Cache-Control"))
        val decoded = ImageIO.read(served.body.stream)
        assertEquals(1920, decoded.width); assertEquals(960, decoded.height)
    }

    @Test fun `image-only posts are allowed while empty posts and a fifth image are rejected`() {
        val small = png(20, 10)
        assertEquals(Status.SEE_OTHER, multipart(file(small), file(small), file(small), file(small)).status)
        val post = rows.values.single()
        assertEquals("", post.body); assertEquals(4, post.imageCount); assertEquals(PostVisibility.MEMBERS, post.visibility)
        assertEquals(Status.OK, media("/media/posts/${post.id.value}/3").status)
        assertEquals(Status.BAD_REQUEST, multipart(field("body", "  ")).status)
        assertEquals(Status.BAD_REQUEST, multipart(field("body", "five"), file(small), file(small), file(small), file(small), file(small)).status)
        assertEquals(1, rows.size)
    }

    @Test fun `declared types are ignored and malformed uploads are rejected without storing`() {
        assertEquals(Status.SEE_OTHER, multipart(file(png(8, 8), type = "text/plain")).status)
        val gif = "GIF89a".toByteArray() + ByteArray(32)
        assertEquals(Status.BAD_REQUEST, multipart(file(gif, type = "image/png")).status)
        val noType = "--$boundary\r\nContent-Disposition: form-data; name=\"images\"; filename=\"a.png\"\r\n\r\n".toByteArray() + png(8, 8) + "\r\n".toByteArray()
        assertEquals(Status.BAD_REQUEST, multipart(noType).status)
        val unterminated = app(Request(Method.POST, "/web/posts").header("Cookie", "session=alice").header("X-CSRF-Token", "csrf")
            .header("content-type", "multipart/form-data; boundary=$boundary").body(ByteArrayInputStream(field("body", "cut"))))
        assertEquals(Status.BAD_REQUEST, unterminated.status)
        val garbage = app(Request(Method.POST, "/web/posts").header("Cookie", "session=alice").header("X-CSRF-Token", "csrf")
            .header("content-type", "multipart/form-data; boundary=$boundary").body("not multipart at all"))
        assertEquals(Status.BAD_REQUEST, garbage.status)
        assertEquals(1, rows.size)
    }

    @Test fun `unknown duplicate or misplaced parts are rejected`() {
        val small = png(8, 8)
        listOf(
            arrayOf(field("body", "a"), field("extra", "x")),
            arrayOf(field("body", "a"), field("body", "b")),
            arrayOf(field("body", "a"), field("visibility", "public"), field("visibility", "private")),
            arrayOf(field("body", "a"), field("csrf", "csrf")),
            arrayOf(field("body", "a"), field("images", "not a file")),
            arrayOf(field("body", "a"), file(small, name = "avatar")),
            arrayOf(field("body", "a"), field("visibility", "public"), field("font", "sans"), field("size", "small"), file(small), file(small), file(small), file(small), field("extra", "ninth")),
        ).forEach { parts -> assertEquals(Status.BAD_REQUEST, multipart(*parts).status, parts.joinToString { String(it).take(80) }) }
        assertTrue(rows.isEmpty())
    }

    @Test fun `per image limit is five MiB inclusive and oversized files are refused as too large`() {
        val limit = 5 * 1024 * 1024
        assertEquals(Status.BAD_REQUEST, multipart(file(ByteArray(limit))).status)
        assertEquals(Status.REQUEST_ENTITY_TOO_LARGE, multipart(file(ByteArray(limit + 1))).status)
        assertTrue(rows.isEmpty())
    }

    /** Endless body that records how much the server pulled. */
    private class Endless(prefix: ByteArray) : java.io.InputStream() {
        private val head = ByteArrayInputStream(prefix)
        var read = 0L
        override fun read(): Int { read++; return head.read().takeIf { it >= 0 } ?: 'a'.code }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = head.read(b, off, len).takeIf { it > 0 } ?: run { java.util.Arrays.fill(b, off, off + len, 'a'.code.toByte()); len }
            read += n; return n
        }
    }

    @Test fun `total body is capped by bytes actually read whatever Content-Length claims`() {
        val cap = 4L * 5 * 1024 * 1024 + 64 * 1024
        fun post(body: java.io.InputStream, length: Long?) = app(Request(Method.POST, "/web/posts").header("Cookie", "session=alice")
            .header("X-CSRF-Token", "csrf").header("content-type", "multipart/form-data; boundary=$boundary")
            .let { if (length == null) it else it.header("Content-Length", length.toString()) }.body(body))
        val untouched = Endless(ByteArray(0))
        assertEquals(Status.REQUEST_ENTITY_TOO_LARGE, post(untouched, cap + 1).status)
        assertEquals(0L, untouched.read)
        for (claimed in listOf(null, 100L)) {
            val endlessField = Endless(field("body", "").dropLast(2).toByteArray())
            assertEquals(Status.REQUEST_ENTITY_TOO_LARGE, post(endlessField, claimed).status)
            assertTrue(endlessField.read in cap..cap + 64 * 1024, "read ${endlessField.read}")
        }
        val sixImages = Endless(file(ByteArray(0)).dropLast(2).toByteArray())
        assertEquals(Status.REQUEST_ENTITY_TOO_LARGE, post(sixImages, null).status)
        assertTrue(rows.isEmpty())
    }

    @Test fun `boundary must be present and at most seventy characters`() {
        fun post(contentType: String, marker: String) = app(Request(Method.POST, "/web/posts").header("Cookie", "session=alice")
            .header("X-CSRF-Token", "csrf").header("content-type", contentType)
            .body("--$marker\r\nContent-Disposition: form-data; name=\"body\"\r\n\r\nhi\r\n--$marker--\r\n"))
        val longest = "b".repeat(70)
        assertEquals(Status.SEE_OTHER, post("multipart/form-data; boundary=$longest", longest).status)
        assertEquals(Status.BAD_REQUEST, post("multipart/form-data; boundary=${longest}b", "${longest}b").status)
        assertEquals(Status.BAD_REQUEST, post("multipart/form-data", "").status)
        assertEquals(Status.BAD_REQUEST, post("multipart/form-data; boundary=", "").status)
        assertEquals(1, rows.size)
    }

    @Test fun `session csrf header and image gate are checked before the body is read`() {
        fun post(cookie: String?, csrf: String?, body: java.io.InputStream) = app(Request(Method.POST, "/web/posts")
            .let { if (cookie == null) it else it.header("Cookie", cookie) }
            .let { if (csrf == null) it else it.header("X-CSRF-Token", csrf) }
            .header("content-type", "multipart/form-data; boundary=$boundary").body(body))
        val anonymous = Endless(field("csrf", "csrf"))
        assertEquals(Status.UNAUTHORIZED, post(null, null, anonymous).status)
        val bodyToken = Endless(field("csrf", "csrf"))
        assertEquals(Status.FORBIDDEN, post("session=alice", null, bodyToken).status)
        val busy = Endless(ByteArray(0))
        val response = gate.run { post("session=alice", "csrf", busy) }
        assertEquals(Status.TOO_MANY_REQUESTS, response.status)
        assertEquals("1", response.header("Retry-After"))
        assertEquals(listOf(0L, 0L, 0L), listOf(anonymous.read, bodyToken.read, busy.read))
        assertEquals(Status.BAD_REQUEST, multipart(file("junk".toByteArray())).status)
        assertEquals(Status.SEE_OTHER, multipart(file(png(4, 4))).status)
        assertEquals(Status.SEE_OTHER, multipart(field("body", "again")).status)
        assertEquals(2, rows.size)
    }

    @Test fun `profile image decoding and post uploads share one processing gate`() {
        val store = object : MemberCustomizations {
            override fun preferences(owner: UserId) = CustomizationPreferences()
            override fun saveTheme(owner: UserId, theme: ThemeMode) = Unit
            override fun saveBio(owner: UserId, bio: String) = Unit
            override fun image(owner: UserId, kind: MemberImageKind): MemberImage? = null
            override fun saveImage(owner: UserId, kind: MemberImageKind, image: MemberImage) = Unit
            override fun removeImage(owner: UserId, kind: MemberImageKind) = Unit
        }
        var duringProfileDecode: Status? = null
        val customization = CustomizationService(store, { bytes, _ ->
            duringProfileDecode = multipart(file(png(4, 4))).status; bytes }, gate)
        val settings = f.access.protect(settingsRoutes(customization, f.accounts, AccountService(f.accounts, object : Passwords {
            override fun hash(password: String) = password
            override fun matches(password: String, hash: String) = false
        }, { "token" }, { _, _, _ -> }), f.access))
        fun profileUpload() = settings(Request(Method.POST, "/settings/images/avatar").header("Cookie", "session=alice")
            .header("X-CSRF-Token", "csrf").header("content-type", "image/png").body(ByteArrayInputStream(png(4, 4)))).status
        assertEquals(Status.SEE_OTHER, profileUpload())
        assertEquals(Status.TOO_MANY_REQUESTS, duringProfileDecode)
        var duringPostDecode: Status? = null
        val posts = PostService(repository, { bytes, kind -> duringPostDecode = profileUpload(); RasterMemberImages().process(bytes, kind) }, gate)
        val postApp = f.access.protect(webRoutes(queries, posts, f.access, f.accounts))
        assertEquals(Status.SEE_OTHER, postApp(Request(Method.POST, "/web/posts").header("Cookie", "session=alice").header("X-CSRF-Token", "csrf")
            .header("content-type", "multipart/form-data; boundary=$boundary").body(ByteArrayInputStream(file(png(4, 4)) + end()))).status)
        assertEquals(Status.TOO_MANY_REQUESTS, duringPostDecode)
    }

    @Test fun `post media is not found when invalid missing or hidden from the viewer`() {
        assertEquals(Status.SEE_OTHER, multipart(file(png(4, 4))).status)
        val id = rows.values.single().id.value
        assertEquals(Status.OK, media("/media/posts/$id/0").status)
        listOf("/media/posts/$id/1", "/media/posts/$id/4", "/media/posts/$id/-1", "/media/posts/$id/x", "/media/posts/$id/99999999999",
            "/media/posts/not-a-uuid/0", "/media/posts/${java.util.UUID.randomUUID()}/0")
            .forEach { assertEquals(Status.NOT_FOUND, media(it).status, it) }
        assertEquals(Status.NOT_FOUND, media("/media/posts/$id/0", "bob").status)
        assertEquals(Status.NOT_FOUND, media("/media/posts/$id/0", null).status)
    }

    @Test fun `stored post images are re-encoded JPEGs without source metadata`() {
        val plain = png(16, 16)
        val chunk = java.nio.ByteBuffer.allocate(0).let {
            val data = "tEXt".toByteArray() + "Comment\u0000SECRET-POST-MARKER".toByteArray()
            val crc = java.util.zip.CRC32().apply { update(data) }.value.toInt()
            java.nio.ByteBuffer.allocate(4).putInt(data.size - 4).array() + data + java.nio.ByteBuffer.allocate(4).putInt(crc).array()
        }
        val marked = plain.copyOfRange(0, 33) + chunk + plain.copyOfRange(33, plain.size)
        assertNotNull(ImageIO.read(ByteArrayInputStream(marked)))
        assertEquals(Status.SEE_OTHER, multipart(file(marked)).status)
        val served = media("/media/posts/${rows.values.single().id.value}/0").body.stream.readAllBytes()
        assertEquals(listOf(0xFF.toByte(), 0xD8.toByte()), served.take(2))
        assertFalse(String(served, Charsets.ISO_8859_1).contains("SECRET-POST-MARKER"))
    }

    @Test fun `browser placeholder for an unselected file input is ignored but real files are not`() {
        val placeholder = file(ByteArray(0), type = "application/octet-stream", filename = "")
        assertEquals(Status.SEE_OTHER, multipart(field("body", "Just text"), placeholder).status)
        assertEquals(0, rows.values.single().imageCount)
        assertEquals(Status.BAD_REQUEST, multipart(placeholder).status)
        assertEquals(Status.BAD_REQUEST, multipart(field("body", "named but empty"), file(ByteArray(0))).status)
        assertEquals(Status.SEE_OTHER, multipart(field("body", "unnamed real"), file(png(4, 4), filename = "")).status)
        assertEquals(1, rows.values.single { it.body == "unnamed real" }.imageCount)
        assertEquals(2, rows.size)
    }

    @Test fun `an upload trickling past the deadline times out and frees the image gate`() {
        val head = ByteArrayInputStream(field("body", "").dropLast(2).toByteArray())
        var reads = 0
        val trickle = object : java.io.InputStream() {
            /** Each read delivers one byte one second later; the deadline must stop it long before the size cap. */
            override fun read(): Int { reads++; clock.now = clock.now.plusSeconds(1); return head.read().takeIf { it >= 0 } ?: 'a'.code }
            override fun read(b: ByteArray, off: Int, len: Int): Int { if (len == 0) return 0; b[off] = read().toByte(); return 1 }
        }
        val response = app(Request(Method.POST, "/web/posts").header("Cookie", "session=alice").header("X-CSRF-Token", "csrf")
            .header("content-type", "multipart/form-data; boundary=$boundary").body(trickle))
        assertEquals(Status.REQUEST_TIMEOUT, response.status)
        assertTrue(reads in 60..61, "reads $reads")
        assertEquals(Unit, gate.run { })
        assertEquals(Status.SEE_OTHER, multipart(file(png(4, 4))).status)
        assertEquals(1, rows.size)
    }
}
