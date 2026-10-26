package com.example.social.http

import org.http4k.core.*
import java.time.Instant

class AuthRateLimit(private val maximum: Int = 30, private val seconds: Long = 60, private val now: () -> Instant = Instant::now) {
    private data class Window(val start: Instant, var count: Int)
    private val windows = mutableMapOf<String, Window>()
    @Synchronized private fun allowed(key: String): Boolean {
        val time = now()
        windows.entries.removeIf { it.value.start.plusSeconds(seconds) <= time }
        if (key !in windows && windows.size >= 10_000) return false
        val window = windows.getOrPut(key) { Window(time, 0) }
        return ++window.count <= maximum
    }
    fun protect(next: HttpHandler): HttpHandler = { request ->
        val key = request.source?.address ?: "unknown"
        if (!allowed(key)) Response(Status.TOO_MANY_REQUESTS).header("Retry-After", seconds.toString()).body("Too many attempts. Try again shortly.")
        else next(request)
    }
}
