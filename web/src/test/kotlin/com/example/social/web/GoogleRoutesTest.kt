package com.example.social.web

import com.example.social.http.*

import com.example.social.application.*
import com.example.social.domain.UserId
import org.http4k.core.*
import java.time.Instant
import kotlin.test.*

class GoogleRoutesTest {
    @Test fun `google callback rejects mismatched state before exchanging a code`() {
        var exchanges = 0
        val pending = mutableMapOf<String, OAuthAttempt>()
        val attempts = object : OAuthAttempts {
            override fun save(attempt: OAuthAttempt) { pending[attempt.digest] = attempt }
            override fun consume(digest: String, now: Instant) = pending.remove(digest)?.takeIf { it.expiresAt > now }
        }
        val accounts = object : Accounts {
            override fun create(account: Account) = Unit
            override fun update(account: Account) = Unit
            override fun byEmail(email: String): Account? = null
            override fun byId(id: UserId): Account? = null
            override fun putToken(token: AccountToken) = Unit
            override fun consumeToken(digest: String, purpose: String, now: Instant): UserId? = null
        }
        val sessions = object : Sessions {
            override fun create(session: Session) = Unit
            override fun find(digest: String, now: Instant): Session? = null
            override fun delete(digest: String) = Unit
            override fun deleteForAccount(id: UserId) = Unit
        }
        val identities = object : GoogleIdentities {
            override fun find(subject: String): UserId? = null
            override fun link(subject: String, account: UserId) = Unit
        }
        val provider = object : GoogleProvider {
            override fun authorizationUrl(state: String, nonce: String, verifier: String) = "https://accounts.google.com/auth?state=$state"
            override fun exchange(code: String, nonce: String, verifier: String): GoogleIdentity { exchanges++; throw AuthenticationFailed() }
        }
        val app = googleRoutes(provider, attempts, GoogleAccounts(accounts, identities), sessions, AccessControl(sessions, false), false)
        val start = app(Request(Method.GET, "/auth/google"))
        assertEquals(Status.SEE_OTHER, start.status)
        val stateCookie = start.header("Set-Cookie")!!.substringBefore(';')
        assertEquals(Status.UNAUTHORIZED, app(Request(Method.GET, "/auth/google/callback?state=wrong&code=code").header("Cookie", stateCookie)).status)
        assertEquals(0, exchanges)
    }
}
