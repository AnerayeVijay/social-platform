package com.example.social.application

import com.example.social.domain.UserId
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale

data class Account(val id: UserId, val email: String, val displayName: String, val passwordHash: String?, val verified: Boolean, val language: Language = Language.ENGLISH) {
    init {
        require(displayName.isNotBlank() && displayName.length <= 80) { "Display name must contain 1–80 characters" }
    }
}
data class AccountToken(val digest: String, val accountId: UserId, val purpose: String, val expiresAt: Instant)
interface Accounts {
    fun searchMembers(name: String, excluding: UserId, limit: Int, after: UserId?): List<FollowMember> =
        throw UnsupportedOperationException("Member search is not configured")

    fun resetWithToken(digest: String, hash: String, now: Instant): UserId? {
        val id = consumeToken(digest, "reset", now) ?: return null
        val account = byId(id) ?: return null
        update(account.copy(passwordHash = hash))
        return id
    }
    fun verifyWithToken(digest: String, now: Instant): UserId? {
        val id = consumeToken(digest, "verify", now) ?: return null
        val account = byId(id) ?: return null
        update(account.copy(verified = true))
        return id
    }
    fun create(account: Account)
    fun byEmail(email: String): Account?
    fun byId(id: UserId): Account?
    fun update(account: Account)
    fun putToken(token: AccountToken)
    fun consumeToken(digest: String, purpose: String, now: Instant): UserId?
}
interface Passwords {
    fun hash(password: String): String
    fun matches(password: String, hash: String): Boolean
}
class AuthenticationFailed : RuntimeException("Invalid credentials, unverified account, or expired link")

fun tokenDigest(token: String): String = MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }

class AccountService(
    private val accounts: Accounts,
    private val passwords: Passwords,
    private val newToken: () -> String,
    private val sendLink: (email: String, purpose: String, token: String) -> Unit,
    private val now: () -> Instant = Instant::now,
    private val revokeSessions: (UserId) -> Unit = {}
) {
    fun register(email: String, password: String, displayName: String) {
        val normalized = normalizeEmail(email)
        validatePassword(password)
        if (accounts.byEmail(normalized) != null) return
        val account = Account(UserId.new(), normalized, displayName.trim(), passwords.hash(password), false)
        accounts.create(account)
        issue(account, "verify", 86_400)
    }

    fun verify(token: String) {
        accounts.verifyWithToken(tokenDigest(token), now()) ?: throw AuthenticationFailed()
    }

    fun login(email: String, password: String): Account {
        val account = accounts.byEmail(normalizeEmail(email)) ?: throw AuthenticationFailed()
        if (!account.verified || account.passwordHash == null || !passwords.matches(password, account.passwordHash)) throw AuthenticationFailed()
        return account
    }

    fun requestReset(email: String) {
        accounts.byEmail(normalizeEmail(email))?.takeIf { it.verified && it.passwordHash != null }
            ?.let { issue(it, "reset", 3_600) }
    }

    fun rename(id: UserId, displayName: String) {
        val account = accounts.byId(id) ?: throw AuthenticationFailed()
        accounts.update(account.copy(displayName = displayName.trim()))
    }

    fun resetPassword(token: String, password: String) {
        validatePassword(password)
        val hash = passwords.hash(password)
        val id = accounts.resetWithToken(tokenDigest(token), hash, now()) ?: throw AuthenticationFailed()
        revokeSessions(id)
    }

    private fun issue(account: Account, purpose: String, seconds: Long) {
        val token = newToken()
        accounts.putToken(AccountToken(tokenDigest(token), account.id, purpose, now().plusSeconds(seconds)))
        sendLink(account.email, purpose, token)
    }

    private fun normalizeEmail(email: String): String {
        val value = email.trim().lowercase(Locale.ROOT)
        require(value.length <= 254 && Regex("[^\\s@]+@[^\\s@]+\\.[^\\s@]+").matches(value)) { "Enter a valid email address" }
        return value
    }

    private fun validatePassword(password: String) {
        require(password.length in 12..128) { "Password must contain 12–128 characters" }
    }
}
