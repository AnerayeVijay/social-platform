package com.example.social.application

import com.example.social.domain.PostImage
import java.io.ByteArrayInputStream
import java.io.InputStream

/** Stored original media whose bytes are opened per requested interval, so ranged reads never load the whole file. */
class MediaContent(val mediaType: String, val length: Long, private val openRange: (Long, Long) -> InputStream) {
    init { require(length >= 0) { "Negative media length" } }

    fun open(offset: Long, length: Long): InputStream {
        // Compare as "offset <= this.length - length" so huge values cannot overflow past the check.
        require(offset >= 0 && length >= 0 && length <= this.length && offset <= this.length - length) { "Range outside media" }
        return openRange(offset, length)
    }

    companion object {
        fun from(media: PostImage): MediaContent {
            val bytes = media.bytes
            return MediaContent(media.mediaType, bytes.size.toLong()) { offset, length ->
                ByteArrayInputStream(bytes, offset.toInt(), length.toInt())
            }
        }
    }
}
