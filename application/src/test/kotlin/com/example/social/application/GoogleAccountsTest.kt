package com.example.social.application

import com.example.social.domain.UserId
import java.time.Instant
import kotlin.test.*

class GoogleAccountsTest {
    @Test fun `google email match requires explicit signed in linking`() {
        val existing = Account(UserId.new(), "alice@example.com", "Alice", "hash", true)
        val data = mutableMapOf(existing.id to existing)
        val accounts = object : Accounts {
            override fun create(account: Account) { data[account.id] = account }
            override fun update(account: Account) { data[account.id] = account }
            override fun byEmail(email: String) = data.values.find { it.email == email }
            override fun byId(id: UserId) = data[id]
            override fun putToken(token: AccountToken) = Unit
            override fun consumeToken(digest: String, purpose: String, now: Instant): UserId? = null
        }
        val linked = mutableMapOf<String, UserId>()
        val emails = mutableMapOf<String, String>()
        val identities = object : GoogleIdentities {
            override fun find(subject: String) = linked[subject]
            override fun link(subject: String, account: UserId) { linked[subject] = account }
            override fun link(subject: String, account: UserId, email: String) {
                linked[subject] = account
                emails[subject] = email
            }
            override fun emailsFor(account: UserId) = linked.filterValues { it == account }.keys.sorted().map { emails[it] }
        }
        val service = GoogleAccounts(accounts, identities)
        val identity = GoogleIdentity("google-id", "alice@example.com", "Alice", true)
        assertFailsWith<AccountLinkRequired> { service.signIn(identity) }
        service.link(existing.id, identity)
        assertEquals(existing, service.signIn(identity))
        assertFailsWith<AuthenticationFailed> { service.signIn(identity.copy(subject = "unverified", verified = false)) }
        assertEquals(listOf("alice@example.com"), identities.emailsFor(existing.id))
        val changed = identity.copy(email = "new-google@example.com")
        assertEquals(existing, service.signIn(changed))
        assertEquals(listOf("new-google@example.com"), identities.emailsFor(existing.id))
        service.link(existing.id, identity.copy(subject = "second", email = "second-google@example.com"))
        assertEquals(listOf("new-google@example.com", "second-google@example.com"), identities.emailsFor(existing.id))
        assertFailsWith<AuthenticationFailed> { service.link(existing.id, changed.copy(email = "bad@example.com", verified = false)) }
        assertFailsWith<AuthenticationFailed> { service.signIn(changed.copy(email = "bad@example.com", verified = false)) }
        assertEquals(listOf("new-google@example.com", "second-google@example.com"), identities.emailsFor(existing.id))
        val newcomer = service.signIn(identity.copy(subject = "new", email = "new@example.com"))
        assertEquals(listOf("new@example.com"), identities.emailsFor(newcomer.id))
        assertEquals("alice@example.com", accounts.byId(existing.id)?.email)
    }
}
