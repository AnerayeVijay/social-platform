package com.example.social.web

import com.example.social.http.*

import com.example.social.application.*
import com.example.social.infrastructure.secureToken
import org.http4k.core.*
import org.http4k.routing.*
import java.time.Instant

fun googleUnavailablePage(): Response = htmlPage("Google sign-in unavailable", "<h1>Google sign-in is not configured</h1><p>Please use email and password.</p><a href=\"/login\">Sign in</a>", Status.SERVICE_UNAVAILABLE)

fun googleRoutes(provider: GoogleProvider, attempts: OAuthAttempts, accounts: GoogleAccounts, sessions: Sessions, access: AccessControl, secureCookies: Boolean): RoutingHttpHandler {
    fun start(request: Request, link: Boolean): Response {
        val session = access.session(request)
        if (link && session == null) return Response(Status.UNAUTHORIZED)
        val state = secureToken()
        val nonce = secureToken()
        val verifier = secureToken()
        attempts.save(OAuthAttempt(tokenDigest(state), nonce, verifier, if (link) session!!.digest else null, Instant.now().plusSeconds(600)))
        return Response(Status.SEE_OTHER).header("Location", provider.authorizationUrl(state, nonce, verifier))
            .header("Set-Cookie", "oauth_state=$state; Path=/auth/google; HttpOnly; SameSite=Lax; Max-Age=600" + if (secureCookies) "; Secure" else "")
            .header("Cache-Control", "no-store")
    }
    return routes(
        "/auth/google" bind Method.GET to { start(it, false) },
        "/auth/google/link" bind Method.POST to access.protect { start(it, true) },
        "/auth/google/callback" bind Method.GET to { request ->
            try {
                val state = request.query("state") ?: throw AuthenticationFailed()
                if (!constantEquals(state, cookie(request, "oauth_state").orEmpty())) throw AuthenticationFailed()
                val attempt = attempts.consume(tokenDigest(state), Instant.now()) ?: throw AuthenticationFailed()
                val code = request.query("code") ?: throw AuthenticationFailed()
                val identity = provider.exchange(code, attempt.nonce, attempt.verifier)
                val linkSession = attempt.linkSession
                if (linkSession != null) {
                    val session = access.session(request) ?: throw AuthenticationFailed()
                    if (!constantEquals(session.digest, linkSession)) throw AuthenticationFailed()
                    accounts.link(session.accountId, identity)
                    Response(Status.SEE_OTHER).header("Location", "/profiles/${session.accountId.value}")
                } else {
                    val account = accounts.signIn(identity)
                    val token = secureToken()
                    sessions.create(Session(tokenDigest(token), account.id, secureToken(), Instant.now().plusSeconds(604800)))
                    Response(Status.SEE_OTHER).header("Location", "/").header("Set-Cookie", "session=$token; Path=/; HttpOnly; SameSite=Lax; Max-Age=604800" + if (secureCookies) "; Secure" else "")
                }
            } catch (_: AuthenticationFailed) { htmlPage("Google sign-in failed", "<h1>Google sign-in failed</h1><p>The request is invalid or expired.</p><a href=\"/login\">Try again</a>", Status.UNAUTHORIZED) }
            catch (_: AccountLinkRequired) { htmlPage("Link your account", "<h1>Sign in to your existing account</h1><p>Then link Google from your profile.</p><a href=\"/login\">Sign in</a>", Status.CONFLICT) }
        }
    )
}
