package com.example.social.http

import com.example.social.application.PostCursor
import com.example.social.domain.PostId
import java.time.Instant

fun encodePostCursor(cursor: PostCursor): String = "${cursor.createdAt}|${cursor.id.value}"

fun decodePostCursor(value: String): PostCursor {
    val parts = value.split('|')
    require(parts.size == 2) { "Invalid cursor" }
    return PostCursor(Instant.parse(parts[0]), PostId.parse(parts[1]))
}
