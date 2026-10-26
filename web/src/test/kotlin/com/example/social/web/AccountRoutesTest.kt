package com.example.social.web

import com.example.social.http.*

import com.example.social.application.*
import com.example.social.domain.UserId
import org.http4k.core.*
import java.time.Instant
import kotlin.test.*

class AccountRoutesTest {
    @Test fun `web login uses csrf and creates an httponly session for a verified account`() {
        val account = Account(UserId.new(), "alice@example.com", "Alice", "hashed", true)
        val accounts = object : Accounts {
            override fun create(account: Account) = Unit
            override fun update(account: Account) = Unit
            override fun byEmail(email: String) = account.takeIf { it.email == email }
            override fun byId(id: UserId) = account.takeIf { it.id == id }
            override fun putToken(token: AccountToken) = Unit
            override fun consumeToken(digest: String, purpose: String, now: Instant): UserId? = null
        }
        val saved = mutableListOf<Session>()
        val sessions = object : Sessions {
            override fun create(session: Session) { saved += session }
            override fun find(digest: String, now: Instant) = saved.find { it.digest == digest && it.expiresAt > now }
            override fun delete(digest: String) { saved.removeIf { it.digest == digest } }
            override fun deleteForAccount(id: UserId) { saved.removeIf { it.accountId == id } }
        }
        val service = AccountService(accounts, object : Passwords {
            override fun hash(password: String) = "hashed"
            override fun matches(password: String, hash: String) = password == "correct-password"
        }, { "token" }, { _, _, _ -> })
        val app = accountRoutes(service, accounts, sessions, AccessControl(sessions, false), false)
        val page = app(Request(Method.GET, "/login"))
        assertEquals(Status.OK, page.status)
        val csrfCookie = page.header("Set-Cookie")!!.substringBefore(';')
        val csrf = csrfCookie.substringAfter('=')
        fun login(token: String) = app(Request(Method.POST, "/login").header("content-type", "application/x-www-form-urlencoded")
            .header("Cookie", csrfCookie).body("email=alice%40example.com&password=correct-password&csrf=$token"))
        assertEquals(Status.FORBIDDEN, login("wrong").status)
        val response = login(csrf)
        assertEquals(Status.SEE_OTHER, response.status)
        assertTrue(response.header("Set-Cookie")!!.contains("HttpOnly"))
        assertEquals(account.id, saved.single().accountId)
    }
}
