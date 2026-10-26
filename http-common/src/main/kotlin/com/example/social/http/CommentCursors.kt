package com.example.social.http

import com.example.social.application.CommentCursor
import com.example.social.domain.CommentId
import java.time.Instant
import java.util.UUID

fun encodeCommentCursor(cursor: CommentCursor): String = "${cursor.createdAt}|${cursor.id.value}"
fun decodeCommentCursor(value: String): CommentCursor {
    val parts = value.split('|')
    require(parts.size == 2) { "Invalid comment cursor" }
    return CommentCursor(Instant.parse(parts[0]), CommentId(UUID.fromString(parts[1])))
}
