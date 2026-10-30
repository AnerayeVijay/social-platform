package com.example.social.http

import com.example.social.application.ForYouCursor
import com.example.social.application.PostCursor
import com.example.social.domain.PostId
import java.time.Instant
import java.util.UUID
import kotlin.test.*

class PostCursorsTest {
    @Test fun `shared feed cursor preserves timestamp and identity and rejects malformed values`() {
        val timestamp = Instant.parse("2026-01-01T00:00:00.123456Z")
        val id = PostId(UUID.fromString("11111111-1111-4111-8111-111111111111"))
        val encoded = "2026-01-01T00:00:00.123456Z|11111111-1111-4111-8111-111111111111"
        assertEquals(encoded, encodePostCursor(PostCursor(timestamp, id)))
        assertEquals(PostCursor(timestamp, id), decodePostCursor(encoded))
        listOf("", "invalid|invalid", "$encoded|extra").forEach { value ->
            assertFails { decodePostCursor(value) }
        }
    }

    @Test fun `for you cursor keeps the followed group, timestamp and identity`() {
        val timestamp = Instant.parse("2026-01-01T00:00:00.123456Z")
        val id = PostId(UUID.fromString("11111111-1111-4111-8111-111111111111"))
        val encoded = "1|2026-01-01T00:00:00.123456Z|11111111-1111-4111-8111-111111111111"
        assertEquals(encoded, encodeForYouCursor(ForYouCursor(true, timestamp, id)))
        assertEquals(ForYouCursor(true, timestamp, id), decodeForYouCursor(encoded))
        assertEquals(ForYouCursor(false, timestamp, id), decodeForYouCursor(encoded.replaceFirst("1|", "0|")))
        listOf("", "2|2026-01-01T00:00:00Z|11111111-1111-4111-8111-111111111111", "1|invalid|invalid").forEach { value ->
            assertFails { decodeForYouCursor(value) }
        }
    }
}
