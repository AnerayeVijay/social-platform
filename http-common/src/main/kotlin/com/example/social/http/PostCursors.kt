package com.example.social.http

import com.example.social.application.ForYouCursor
import com.example.social.application.PostCursor
import com.example.social.domain.PostId
import java.time.Instant

fun encodePostCursor(cursor: PostCursor): String = "${cursor.createdAt}|${cursor.id.value}"

fun decodePostCursor(value: String): PostCursor {
    val parts = value.split('|')
    require(parts.size == 2) { "Invalid cursor" }
    return PostCursor(Instant.parse(parts[0]), PostId.parse(parts[1]))
}

fun encodeForYouCursor(cursor: ForYouCursor): String = "${if (cursor.followed) 1 else 0}|${cursor.createdAt}|${cursor.id.value}"

fun decodeForYouCursor(value: String): ForYouCursor {
    val parts = value.split('|')
    require(parts.size == 3 && parts[0] in setOf("0", "1")) { "Invalid cursor" }
    return ForYouCursor(parts[0] == "1", Instant.parse(parts[1]), PostId.parse(parts[2]))
}
