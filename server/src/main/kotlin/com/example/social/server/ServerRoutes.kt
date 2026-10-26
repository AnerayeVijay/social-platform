package com.example.social.server

import com.example.social.http.AccessControl
import com.example.social.web.googleUnavailablePage
import org.http4k.core.*

fun serverRoutes(content: HttpHandler, accounts: HttpHandler, google: HttpHandler?, access: AccessControl): HttpHandler {
    val protectedContent = access.protect(content)
    val accountPaths = setOf("/login", "/register", "/verify", "/forgot-password", "/reset-password", "/logout", "/api/auth/login", "/api/auth/logout")
    return { request ->
        when {
            request.uri.path.startsWith("/auth/google") -> google?.invoke(request) ?: googleUnavailablePage()
            request.uri.path in accountPaths -> accounts(request)
            request.uri.path == "/health" -> Response(Status.OK).header("content-type", "application/json").body("""{"status":"ok"}""")
            else -> protectedContent(request)
        }
    }
}
