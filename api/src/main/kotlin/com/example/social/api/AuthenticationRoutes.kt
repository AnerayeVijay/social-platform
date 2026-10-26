package com.example.social.api

import com.example.social.application.*
import com.example.social.http.AccessControl
import com.example.social.infrastructure.secureToken
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.http4k.core.*
import org.http4k.routing.*
import java.time.Instant

fun authenticationRoutes(service: AccountService, sessions: Sessions, access: AccessControl): RoutingHttpHandler = routes(
    "/api/auth/login" bind Method.POST to { request ->
        if (request.header("Cookie") != null || request.header("content-type")?.substringBefore(';') != "application/json") Response(Status.BAD_REQUEST)
        else apiSafely {
            val json = jacksonObjectMapper().readTree(request.bodyString())
            val account = service.login(json.path("email").asText(), json.path("password").asText())
            val token = secureToken()
            val expires = Instant.now().plusSeconds(604800)
            sessions.create(Session(tokenDigest(token), account.id, secureToken(), expires))
            apiJson(mapOf("token" to token, "expiresAt" to expires.toString(), "user" to mapOf("id" to account.id.value.toString(), "displayName" to account.displayName)))
        }
    },
    "/api/auth/logout" bind Method.POST to access.protect { request ->
        access.session(request)?.let { sessions.delete(it.digest) }
        apiJson(mapOf("status" to "signed out"))
    }
)
