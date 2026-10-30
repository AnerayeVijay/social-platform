package com.example.social.http

import org.http4k.core.*
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.*

class MediaUploadsTest {
    private val b = "XBOUNDARY"
    private fun field(name: String, value: String) = "--$b\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n"
    private fun file(name: String, filename: String, content: String, type: String = "image/png") =
        "--$b\r\nContent-Disposition: form-data; name=\"$name\"; filename=\"$filename\"\r\nContent-Type: $type\r\n\r\n$content\r\n"
    private fun req(vararg parts: String, type: String = "multipart/form-data; boundary=$b") =
        Request(Method.POST, "/").header("Content-Type", type).body(parts.joinToString("") + "--$b--\r\n")
    private val allowed = setOf("body")
    private fun read(r: Request, max: Int = 4, clock: Clock = Clock.systemUTC()) = readMediaForm(r, max, allowed, clock)

    @Test fun `detects multipart`() {
        assertTrue(isMediaMultipart(req()))
        assertFalse(isMediaMultipart(Request(Method.POST, "/").header("Content-Type", "application/x-www-form-urlencoded")))
    }

    @Test fun `reads fields and attachments plus legacy images`() {
        val form = read(req(field("body", "hi"), file("attachments", "a.png", "AAA"), file("images", "b.png", "BB")))
        assertEquals(mapOf("body" to "hi"), form.fields)
        assertEquals(listOf("a.png", "b.png"), form.attachments.map { it.filename })
        assertContentEquals("AAA".toByteArray(), form.attachments[0].bytes)
    }

    @Test fun `empty unselected file part is ignored`() =
        assertEquals(0, read(req(file("attachments", "", ""))).attachments.size)

    @Test fun `rejects unknown and duplicate fields and file names`() {
        assertFailsWith<IllegalArgumentException> { read(req(field("evil", "x"))) }
        assertFailsWith<IllegalArgumentException> { read(req(field("body", "x"), field("body", "y"))) }
        assertFailsWith<IllegalArgumentException> { read(req(file("other", "a.png", "A"))) }
    }

    @Test fun `rejects too many attachments`() {
        assertFailsWith<IllegalArgumentException> { read(req(file("attachments", "a", "A"), file("images", "b", "B")), max = 1) }
    }

    @Test fun `rejects malformed boundary and body`() {
        assertFailsWith<IllegalArgumentException> { read(req(type = "multipart/form-data")) }
        assertFailsWith<IllegalArgumentException> { read(req(type = "multipart/form-data; charset=x; boundary=$b")) }
        assertFailsWith<IllegalArgumentException> { read(Request(Method.POST, "/").header("Content-Type", "multipart/form-data; boundary=$b").body("garbage")) }
    }

    @Test fun `declared content length over budget is rejected without reading`() {
        val r = req().header("Content-Length", "${1L * 4 * 100 * 1024 * 1024 + 64 * 1024 + 1}")
        assertFailsWith<MediaUploadTooLarge> { read(r) }
    }

    @Test fun `actual stream over budget is rejected despite false content length`() {
        val limit = 1L * 100 * 1024 * 1024 + 64 * 1024
        val head = "--$b\r\nContent-Disposition: form-data; name=\"attachments\"; filename=\"a.mp4\"\r\nContent-Type: video/mp4\r\n\r\n".toByteArray()
        val stream = object : InputStream() {
            var sent = 0L
            override fun read(): Int = if (sent < head.size) head[sent++.toInt()].toInt() else { sent++; 1 }
            override fun read(buf: ByteArray, off: Int, len: Int): Int { if (sent > limit + 1_000_000) return -1; for (i in 0 until len) buf[off + i] = read().toByte(); return len }
        }
        val r = Request(Method.POST, "/").header("Content-Type", "multipart/form-data; boundary=$b").body(Body(stream))
        assertFailsWith<MediaUploadTooLarge> { read(r, max = 1) }
    }

    @Test fun `read deadline times out`() {
        var now = Instant.parse("2026-01-01T00:00:00Z")
        val clock = object : Clock() { override fun instant() = now; override fun getZone() = ZoneOffset.UTC; override fun withZone(z: java.time.ZoneId?) = this }
        val source = object : InputStream() {
            val data = ByteArrayInputStream(req(field("body", "x")).bodyString().toByteArray())
            override fun read(): Int { now = now.plusSeconds(61); return data.read() }
            override fun read(b: ByteArray, o: Int, l: Int): Int { now = now.plusSeconds(61); return data.read(b, o, l) }
        }
        val r = Request(Method.POST, "/").header("Content-Type", "multipart/form-data; boundary=$b").body(Body(source))
        assertFailsWith<MediaUploadTimedOut> { read(r, clock = clock) }
    }

    @Test fun `upload gate is fail fast and released`() {
        assertEquals("ok", withMediaUpload { assertFailsWith<MediaUploadBusy> { withMediaUpload { } }; "ok" })
        assertEquals("again", withMediaUpload { "again" })
        assertFailsWith<IllegalStateException> { withMediaUpload { error("x") } }
        assertEquals(1, withMediaUpload { 1 })
    }

    @Test fun `maxAttachments must be 1 to 4`() {
        for (bad in listOf(0, -1, 5)) assertFailsWith<IllegalArgumentException>("$bad") { read(req(), max = bad) }
        assertEquals(0, read(req(), max = 4).attachments.size)
    }

    @Test fun `part count is bounded by allowed fields plus attachments plus one empty file input`() {
        val empty = file("attachments", "", "")
        assertEquals(0, read(req(field("body", "x"), empty), max = 1).attachments.size)
        assertFailsWith<IllegalArgumentException> { read(req(field("body", "x"), empty, empty, empty), max = 1) }
    }

    @Test fun `gate is reentrant-safe and released when block fails`() {
        assertFailsWith<IllegalStateException> { withMediaUpload { error("x") } }
        withMediaUpload { assertFailsWith<MediaUploadBusy> { withMediaUpload { } } }
        assertFailsWith<MediaUploadBusy> { withMediaUpload { withMediaUpload { } } }
        assertEquals(2, withMediaUpload { 2 })
    }

    @Test fun `image size is bounded during reading rather than using the video allowance`() {
        val head = "--$b\r\nContent-Disposition: form-data; name=\"attachments\"; filename=\"large.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n".toByteArray()
        val maximumRead = 20 * 1024 * 1024 + 64 * 1024
        var sent = 0
        val source = object : InputStream() {
            override fun read(): Int {
                check(sent < maximumRead) { "Reader exceeded the image allowance" }
                return if (sent < head.size) head[sent++].toInt() and 255 else { sent++; 1 }
            }
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                check(sent + length <= maximumRead) { "Reader exceeded the image allowance" }
                if (sent < head.size) {
                    val n = minOf(length, head.size - sent)
                    head.copyInto(bytes, offset, sent, sent + n)
                    sent += n
                    return n
                }
                bytes.fill(1, offset, offset + length)
                sent += length
                return length
            }
        }
        val request = Request(Method.POST, "/").header("Content-Type", "multipart/form-data; boundary=$b").body(Body(source))
        assertFailsWith<MediaUploadTooLarge> { read(request, max = 1) }
        assertTrue(sent <= maximumRead)
    }
}
