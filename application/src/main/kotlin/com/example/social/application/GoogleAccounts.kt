package com.example.social.application

import com.example.social.domain.UserId
import java.util.Locale

data class GoogleIdentity(val subject: String, val email: String, val displayName: String, val verified: Boolean)
interface GoogleIdentities {
    fun find(subject: String): UserId?
    fun link(subject: String, account: UserId)
    fun link(subject: String, account: UserId, email: String) = link(subject, account)
    fun emailsFor(account: UserId): List<String?> = throw UnsupportedOperationException("Google connections are not configured")
}
class AccountLinkRequired : RuntimeException("Sign in to your existing account and link Google from your profile")

class GoogleAccounts(private val accounts: Accounts, private val identities: GoogleIdentities) {
    fun signIn(identity: GoogleIdentity): Account {
        if (!identity.verified || identity.subject.isBlank()) throw AuthenticationFailed()
        identities.find(identity.subject)?.let {
            val account = accounts.byId(it) ?: throw AuthenticationFailed()
            identities.link(identity.subject, account.id, identity.email)
            return account
        }
        val email = identity.email.trim().lowercase(Locale.ROOT)
        if (accounts.byEmail(email) != null) throw AccountLinkRequired()
        val account = Account(UserId.new(), email, identity.displayName.trim().take(80).ifBlank { "Community member" }, null, true)
        accounts.create(account)
        identities.link(identity.subject, account.id, identity.email)
        return account
    }
    fun link(accountId: UserId, identity: GoogleIdentity) {
        if (!identity.verified || identity.subject.isBlank() || accounts.byId(accountId)?.verified != true) throw AuthenticationFailed()
        val owner = identities.find(identity.subject)
        if (owner != null && owner != accountId) throw AuthenticationFailed()
        identities.link(identity.subject, accountId, identity.email)
    }
}
