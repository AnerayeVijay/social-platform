package com.example.social.api

import com.example.social.application.*
import com.example.social.domain.UserId
import com.example.social.http.AccessControl
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.http4k.core.*
import java.time.Instant
import kotlin.test.*

class AuthenticationRoutesTest {
    @Test fun `api login issues a bearer session logout revokes it and bad credentials fail`() {
        val account = Account(UserId.new(), "alice@example.com", "Alice", "hashed", true)
        val accounts = object : Accounts {
            override fun create(account: Account) = Unit
            override fun update(account: Account) = Unit
            override fun byEmail(email: String) = account.takeIf { it.email == email }
            override fun byId(id: UserId) = account.takeIf { it.id == id }
            override fun putToken(token: AccountToken) = Unit
            override fun consumeToken(digest: String, purpose: String, now: Instant): UserId? = null
        }
        val saved = mutableMapOf<String, Session>()
        val sessions = object : Sessions {
            override fun create(session: Session) { saved[session.digest] = session }
            override fun find(digest: String, now: Instant) = saved[digest]?.takeIf { it.expiresAt > now }
            override fun delete(digest: String) { saved.remove(digest) }
            override fun deleteForAccount(id: UserId) { saved.entries.removeIf { it.value.accountId == id } }
        }
        val service = AccountService(accounts, object : Passwords {
            override fun hash(password: String) = "hashed"
            override fun matches(password: String, hash: String) = password == "correct-password"
        }, { "token" }, { _, _, _ -> })
        val app = authenticationRoutes(service, sessions, AccessControl(sessions, false))
        fun login(password: String) = Request(Method.POST, "/api/auth/login").header("content-type", "application/json")
            .body("""{"email":"alice@example.com","password":"$password"}""")

        assertEquals(Status.UNAUTHORIZED, app(login("wrong-password")).status)
        assertEquals(Status.BAD_REQUEST, app(login("correct-password").header("Cookie", "session=anything")).status)
        val response = app(login("correct-password"))
        assertEquals(Status.OK, response.status)
        assertNull(response.header("Set-Cookie"))
        assertFalse(response.bodyString().contains("hashed"))
        val token = jacksonObjectMapper().readTree(response.bodyString()).path("token").asText()
        assertTrue(token.isNotBlank())
        assertEquals(account.id, saved[tokenDigest(token)]?.accountId)
        assertEquals(Status.OK, app(Request(Method.POST, "/api/auth/logout").header("Authorization", "Bearer $token")).status)
        assertTrue(saved.isEmpty())
        assertEquals(Status.UNAUTHORIZED, app(Request(Method.POST, "/api/auth/logout").header("Authorization", "Bearer $token")).status)
    }
}
