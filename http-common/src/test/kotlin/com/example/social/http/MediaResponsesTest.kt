package com.example.social.http

import com.example.social.domain.PostImage
import org.http4k.core.*
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
}
