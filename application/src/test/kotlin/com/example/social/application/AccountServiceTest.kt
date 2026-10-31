package com.example.social.application

import com.example.social.domain.AccountRenamed
import com.example.social.domain.UserId
import com.example.social.sharedkernel.DomainEvent
import com.example.social.sharedkernel.EventBus
import java.time.Instant
import kotlin.test.*

class AccountServiceTest {
    @Test fun `profile update validates display name without exposing or changing identity`() {
        val store = MemoryAccounts()
        val account = Account(UserId.new(), "alice@example.com", "Alice", "hash", true)
        store.create(account)
        val service = AccountService(store, object : Passwords {
            override fun hash(password: String) = password
            override fun matches(password: String, hash: String) = password == hash
        }, { "token" }, { _, _, _ -> })
        service.rename(account.id, "  Alicia  ")
        assertEquals(account.copy(displayName = "Alicia"), store.byId(account.id))
        assertFailsWith<IllegalArgumentException> { service.rename(account.id, " ") }
    }

    @Test fun `renaming publishes the new display name for the account`() {
        val store = MemoryAccounts()
        val account = Account(UserId.new(), "alice@example.com", "Alice", "hash", true)
        store.create(account)
        val events = EventBus()
        val seen = mutableListOf<DomainEvent>()
        events.subscribe(AccountRenamed::class) { seen += it }
        val service = AccountService(store, object : Passwords {
            override fun hash(password: String) = password
            override fun matches(password: String, hash: String) = password == hash
        }, { "token" }, { _, _, _ -> }, events = events)

        service.rename(account.id, "  Alicia  ")

        assertEquals(listOf<DomainEvent>(AccountRenamed(account.id, "Alicia")), seen)
    }

    @Test fun `password reset expires and cannot be reused`() {
        val store = MemoryAccounts()
        val sent = mutableListOf<String>()
        val revoked = mutableListOf<UserId>()
        var now = Instant.parse("2026-01-01T00:00:00Z")
        val passwords = object : Passwords {
            override fun hash(password: String) = "hash:$password"
            override fun matches(password: String, hash: String) = hash == "hash:$password"
        }
        store.create(Account(UserId.new(), "alice@example.com", "Alice", passwords.hash("old-password-123"), true))
        val service = AccountService(store, passwords, { "token-${sent.size}" }, { _, _, token -> sent += token }, { now }, { revoked += it })
        service.requestReset("alice@example.com")
        now = now.plusSeconds(3_601)
        assertFailsWith<AuthenticationFailed> { service.resetPassword(sent.last(), "new-password-123") }
        service.requestReset("alice@example.com")
        service.resetPassword(sent.last(), "new-password-123")
        assertFailsWith<AuthenticationFailed> { service.resetPassword(sent.last(), "another-password") }
        assertFailsWith<AuthenticationFailed> { service.login("alice@example.com", "old-password-123") }
        assertEquals("Alice", service.login("alice@example.com", "new-password-123").displayName)
        assertEquals(listOf(store.accounts.values.single().id), revoked)
    }

    @Test fun `registration requires verification and verification tokens are single use`() {
        val store = MemoryAccounts()
        val delivered = mutableListOf<Pair<String, String>>()
        val service = AccountService(store, object : Passwords {
            override fun hash(password: String) = "hash:$password"
            override fun matches(password: String, hash: String) = hash == "hash:$password"
        }, { "token-${delivered.size}" }, { _, purpose, token -> delivered += purpose to token },
            { Instant.parse("2026-01-01T00:00:00Z") })
        service.register(" Alice@Example.com ", "a-long-password", "Alice")
        assertEquals("alice@example.com", store.accounts.values.single().email)
        assertFalse(store.accounts.values.single().verified)
        assertFailsWith<AuthenticationFailed> { service.login("alice@example.com", "a-long-password") }
        service.verify(delivered.single().second)
        assertFailsWith<AuthenticationFailed> { service.verify(delivered.single().second) }
        assertEquals("Alice", service.login("alice@example.com", "a-long-password").displayName)
    }

    private class MemoryAccounts : Accounts {
        val accounts = mutableMapOf<UserId, Account>()
        private val tokens = mutableMapOf<String, AccountToken>()
        override fun create(account: Account) { accounts[account.id] = account }
        override fun byEmail(email: String) = accounts.values.find { it.email == email }
        override fun byId(id: UserId) = accounts[id]
        override fun update(account: Account) { accounts[account.id] = account }
        override fun putToken(token: AccountToken) { tokens[token.digest] = token }
        override fun consumeToken(digest: String, purpose: String, now: Instant): UserId? =
            tokens[digest]?.takeIf { it.purpose == purpose && it.expiresAt > now }
                ?.also { tokens.remove(digest) }?.accountId
    }
}
