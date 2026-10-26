package com.example.social.http

import org.http4k.core.*
import java.time.Instant
import kotlin.test.*

class RateLimitTest {
    @Test fun `authentication attempts are bounded and recover after window`() {
        var now = Instant.parse("2026-01-01T00:00:00Z")
        val limit = AuthRateLimit(2, 60, { now })
        val app = limit.protect { Response(Status.OK) }
        val request = Request(Method.POST, "/login")
        assertEquals(Status.OK, app(request).status)
        assertEquals(Status.OK, app(request).status)
        assertEquals(Status.TOO_MANY_REQUESTS, app(request).status)
        now = now.plusSeconds(61)
        assertEquals(Status.OK, app(request).status)
    }
}
