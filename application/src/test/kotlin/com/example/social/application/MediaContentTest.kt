package com.example.social.application

import com.example.social.domain.PostImage
import java.io.ByteArrayInputStream
import kotlin.test.*

class MediaContentTest {
    private val bytes = ByteArray(10) { it.toByte() }

    @Test fun `from post image exposes type length and requested slice`() {
        val content = MediaContent.from(PostImage("image/png", bytes))
        assertEquals("image/png", content.mediaType)
        assertEquals(10L, content.length)
        assertContentEquals(bytes.copyOfRange(2, 5), content.open(2, 3).readBytes())
        assertContentEquals(ByteArray(0), content.open(10, 0).readBytes())
    }

    @Test fun `source is opened only for the requested interval`() {
        val opened = mutableListOf<Pair<Long, Long>>()
        val content = MediaContent("video/mp4", 100L * 1024 * 1024) { o, l -> opened += o to l; ByteArrayInputStream(ByteArray(l.toInt())) }
        assertTrue(opened.isEmpty())
        assertEquals(1, content.open(100L * 1024 * 1024 - 1, 1).readBytes().size)
        assertEquals(listOf(100L * 1024 * 1024 - 1 to 1L), opened)
    }

    @Test fun `out of bounds intervals are rejected without opening`() {
        val content = MediaContent("video/mp4", 10) { _, _ -> fail("opened") }
        for ((o, l) in listOf(-1L to 1L, 0L to -1L, 0L to 11L, 10L to 1L, 11L to 0L, 5L to Long.MAX_VALUE, Long.MAX_VALUE to 1L))
            assertFailsWith<IllegalArgumentException>("$o,$l") { content.open(o, l) }
        assertFailsWith<IllegalArgumentException> { MediaContent("video/mp4", -1) { _, _ -> fail() } }
    }
}
