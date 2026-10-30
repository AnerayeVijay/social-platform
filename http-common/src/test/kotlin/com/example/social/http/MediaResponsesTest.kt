package com.example.social.http

import com.example.social.application.MediaContent
import com.example.social.domain.PostImage
import org.http4k.core.*
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import kotlin.test.*

class MediaResponsesTest {
    private val bytes = ByteArray(10) { it.toByte() }
    private val png = PostImage("image/png", bytes)
    private fun get(uri: String = "/m", range: String? = null) = Request(Method.GET, uri).let { r -> range?.let { r.header("Range", it) } ?: r }

    @Test fun `missing media is 404`() = assertEquals(Status.NOT_FOUND, originalMediaResponse(get(), null).status)

    @Test fun `full response has safe headers`() {
        val r = originalMediaResponse(get(), png)
        assertEquals(Status.OK, r.status)
        assertEquals("image/png", r.header("Content-Type"))
        assertEquals("nosniff", r.header("X-Content-Type-Options"))
        assertEquals("private, no-store", r.header("Cache-Control"))
        assertEquals("bytes", r.header("Accept-Ranges"))
        assertEquals("10", r.header("Content-Length"))
        assertEquals("inline; filename=\"attachment.png\"", r.header("Content-Disposition"))
        assertContentEquals(bytes, r.body.payload.array())
    }

    @Test fun `download is an attachment`() =
        assertEquals("attachment; filename=\"attachment.png\"", originalMediaResponse(get("/m?download=1"), png).header("Content-Disposition"))

    @Test fun `malicious mime is served as forced octet-stream download`() {
        for (mime in listOf("text/html", "image/svg+xml", "application/x-msdownload", "image/png\r\nX-Evil: 1", "image/png; charset=x")) {
            val r = originalMediaResponse(get(), PostImage(mime, bytes))
            assertEquals(if (mime.startsWith("image/png;")) "image/png" else "application/octet-stream", r.header("Content-Type"), mime)
            if (r.header("Content-Type") == "application/octet-stream")
                assertEquals("attachment; filename=\"attachment.bin\"", r.header("Content-Disposition"))
            assertNull(r.header("X-Evil"))
        }
    }

    @Test fun `single range`() {
        val r = originalMediaResponse(get(range = "bytes=2-4"), png)
        assertEquals(Status.PARTIAL_CONTENT, r.status)
        assertEquals("bytes 2-4/10", r.header("Content-Range"))
        assertEquals("3", r.header("Content-Length"))
        assertContentEquals(bytes.copyOfRange(2, 5), r.body.payload.array())
    }

    @Test fun `open ended suffix and clamped ranges`() {
        assertEquals("bytes 7-9/10", originalMediaResponse(get(range = "bytes=7-"), png).header("Content-Range"))
        assertEquals("bytes 6-9/10", originalMediaResponse(get(range = "bytes=-4"), png).header("Content-Range"))
        assertEquals("bytes 0-9/10", originalMediaResponse(get(range = "bytes=-99"), png).header("Content-Range"))
        assertEquals("bytes 8-9/10", originalMediaResponse(get(range = "bytes=8-99"), png).header("Content-Range"))
    }

    @Test fun `invalid or unsatisfiable ranges are 416`() {
        for (range in listOf("bytes=10-", "bytes=5-2", "bytes=-0", "bytes=a-b", "bytes=0-1,3-4", "bytes=", "bytes=-", "bytes=99999999999999999999-")) {
            val r = originalMediaResponse(get(range = range), png)
            assertEquals(Status.REQUESTED_RANGE_NOT_SATISFIABLE, r.status, range)
            assertEquals("bytes */10", r.header("Content-Range"), range)
        }
    }

    @Test fun `non bytes range unit is ignored`() = assertEquals(Status.OK, originalMediaResponse(get(range = "items=1-2"), png).status)

    private fun assertInline(mime: String, ext: String) {
        val r = originalMediaResponse(get(), PostImage(mime, bytes))
        assertEquals(mime, r.header("Content-Type"))
        assertEquals("inline; filename=\"attachment.$ext\"", r.header("Content-Disposition"))
    }

    @Test fun `heic is served inline`() = assertInline("image/heic", "heic")
    @Test fun `avif is served inline`() = assertInline("image/avif", "avif")
    @Test fun `aac is served inline`() = assertInline("audio/aac", "aac")
    @Test fun `flac is served inline`() = assertInline("audio/flac", "flac")

    private class FakeSource(val length: Long) {
        val opened = mutableListOf<Pair<Long, Long>>()
        var closed = 0
        val content = MediaContent("video/mp4", length) { offset, len ->
            opened += offset to len
            object : FilterInputStream(ByteArrayInputStream(ByteArray(len.toInt()) { 7 })) { override fun close() { closed++; super.close() } }
        }
    }
    private val hundredMb = 100L * 1024 * 1024

    @Test fun `stream missing media is 404`() = assertEquals(Status.NOT_FOUND, streamOriginalMediaResponse(get(), null).status)

    @Test fun `one byte range of a 100MB source opens only that byte and only when the body is read`() {
        val source = FakeSource(hundredMb)
        val r = streamOriginalMediaResponse(get(range = "bytes=${hundredMb - 1}-"), source.content)
        assertEquals(Status.PARTIAL_CONTENT, r.status)
        assertEquals("bytes ${hundredMb - 1}-${hundredMb - 1}/$hundredMb", r.header("Content-Range"))
        assertEquals("1", r.header("Content-Length"))
        assertEquals(1L, r.body.length)
        assertTrue(source.opened.isEmpty(), "opened before body consumed")
        r.body.stream.use { assertContentEquals(byteArrayOf(7), it.readBytes()) }
        assertEquals(listOf(hundredMb - 1 to 1L), source.opened)
        assertEquals(1, source.closed)
    }

    @Test fun `full response streams the whole interval lazily`() {
        val source = FakeSource(10)
        val r = streamOriginalMediaResponse(get(), source.content)
        assertEquals(Status.OK, r.status)
        assertEquals("10", r.header("Content-Length"))
        assertTrue(source.opened.isEmpty())
        assertEquals(10, r.body.stream.use { it.readBytes().size })
        assertEquals(listOf(0L to 10L), source.opened)
    }

    @Test fun `closing an unread body never opens the source`() {
        val source = FakeSource(10)
        streamOriginalMediaResponse(get(range = "bytes=0-1"), source.content).close()
        assertTrue(source.opened.isEmpty())
        assertEquals(0, source.closed)
    }

    @Test fun `unsatisfiable range never opens the source`() {
        val source = FakeSource(10)
        val r = streamOriginalMediaResponse(get(range = "bytes=10-"), source.content)
        assertEquals(Status.REQUESTED_RANGE_NOT_SATISFIABLE, r.status)
        assertEquals("bytes */10", r.header("Content-Range"))
        r.body.stream.use { it.readBytes() }
        assertTrue(source.opened.isEmpty())
    }

    @Test fun `ranges beyond 2GiB use long offsets`() {
        val big = 5L * 1024 * 1024 * 1024
        val source = FakeSource(big)
        val r = streamOriginalMediaResponse(get(range = "bytes=${big - 2}-${big + 100}"), source.content)
        assertEquals("bytes ${big - 2}-${big - 1}/$big", r.header("Content-Range"))
        r.body.stream.use { it.readBytes() }
        assertEquals(listOf(big - 2 to 2L), source.opened)
    }

    @Test fun `zero length media is an empty 200 without opening and any range is 416`() {
        val source = FakeSource(0)
        val r = streamOriginalMediaResponse(get(), source.content)
        assertEquals(Status.OK, r.status)
        assertEquals("0", r.header("Content-Length"))
        assertEquals(0, r.body.stream.use { it.readBytes().size })
        assertTrue(source.opened.isEmpty())
        assertEquals(Status.REQUESTED_RANGE_NOT_SATISFIABLE, streamOriginalMediaResponse(get(range = "bytes=0-"), source.content).status)
    }

    @Test fun `overlong range numbers are rejected without parsing them`() {
        val r = streamOriginalMediaResponse(get(range = "bytes=" + "9".repeat(10_000) + "-"), FakeSource(10).content)
        assertEquals(Status.REQUESTED_RANGE_NOT_SATISFIABLE, r.status)
    }
}
