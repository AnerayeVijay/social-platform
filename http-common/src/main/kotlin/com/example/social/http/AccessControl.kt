package com.example.social.http

import com.example.social.application.*
import org.http4k.core.*
import java.net.URLDecoder
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.time.Instant

class AccessControl(private val sessions: Sessions, private val membersOnly: Boolean, private val now: () -> Instant = Instant::now) {
    fun session(request: Request): Session? {
        val token = bearer(request) ?: cookie(request, "session") ?: return null
        return sessions.find(tokenDigest(token), now())
    }
    fun protect(next: HttpHandler): HttpHandler = { request ->
        val write = request.method !in setOf(Method.GET, Method.HEAD, Method.OPTIONS) && request.uri.path !in READ_ONLY_POST_PATHS
        val session = session(request)
        when {
            (write || membersOnly) && session == null ->
                if (request.header("Accept")?.contains("text/html") == true) Response(Status.SEE_OTHER).header("Location", "/login")
                else Response(Status.UNAUTHORIZED).body("Sign in to continue")
            write && bearer(request) == null && !constantEquals(session!!.csrf, request.header("X-CSRF-Token") ?: formFields(request)["csrf"].orEmpty()) ->
                Response(Status.FORBIDDEN).body("Invalid form token. Reload the page and try again.")
            else -> next(request)
        }
    }
    private fun bearer(request: Request) = request.header("Authorization")?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")?.takeIf { it.isNotBlank() }
}

private val READ_ONLY_POST_PATHS = setOf("/api/hashtags/search")

fun cookie(request: Request, name: String): String? = request.header("Cookie")?.split(';')
    ?.map { it.trim().split('=', limit = 2) }?.firstOrNull { it.size == 2 && it[0] == name }?.get(1)
fun constantEquals(first: String, second: String) = MessageDigest.isEqual(first.toByteArray(UTF_8), second.toByteArray(UTF_8))
fun formFields(request: Request): Map<String, String> {
    if (request.header("content-type")?.substringBefore(';')?.trim() != "application/x-www-form-urlencoded") return emptyMap()
    return request.bodyString().split('&').filter { it.isNotEmpty() }.associate {
        val parts = it.split('=', limit = 2)
        URLDecoder.decode(parts[0], UTF_8) to URLDecoder.decode(parts.getOrElse(1) { "" }, UTF_8)
    }
}
