package com.example.social.infrastructure

import com.example.social.application.*
import com.example.social.domain.UserId
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

class PostgresAccounts(private val dataSource: DataSource) : Accounts {
    override fun searchMembers(name: String, excluding: UserId, limit: Int, after: UserId?): List<FollowMember> = dataSource.connection.use { connection ->
        val pattern = "%" + name.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        connection.prepareStatement("SELECT id, display_name FROM accounts WHERE verified=TRUE AND id<>? AND display_name ILIKE ? ESCAPE E'\\\\' AND (?::uuid IS NULL OR id>?) ORDER BY id LIMIT ?").use {
            it.setObject(1, excluding.value)
            it.setString(2, pattern)
            it.setObject(3, after?.value)
            it.setObject(4, after?.value)
            it.setInt(5, limit)
            it.executeQuery().use { rows ->
                buildList { while (rows.next()) add(FollowMember(UserId(rows.getObject("id", UUID::class.java)), rows.getString("display_name"))) }
            }
        }
    }

    override fun resetWithToken(digest: String, hash: String, now: Instant): UserId? = completeToken(digest, "reset", now, hash)
    override fun verifyWithToken(digest: String, now: Instant): UserId? = completeToken(digest, "verify", now, null)

    private fun completeToken(digest: String, purpose: String, now: Instant, hash: String?): UserId? = dataSource.connection.use { c ->
        c.autoCommit = false
        try {
            val id = c.prepareStatement("SELECT a.id FROM accounts a JOIN account_tokens t ON t.account_id=a.id WHERE t.digest=? AND t.purpose=? AND t.expires_at>? FOR UPDATE OF a").use {
                it.setString(1, digest); it.setString(2, purpose); it.setTimestamp(3, Timestamp.from(now))
                it.executeQuery().use { rows -> if (rows.next()) UserId(rows.getObject(1, UUID::class.java)) else null }
            }
            if (id == null) { c.rollback(); return null }
            val consumed = c.prepareStatement("DELETE FROM account_tokens WHERE digest=? AND purpose=? AND expires_at>?").use {
                it.setString(1, digest); it.setString(2, purpose); it.setTimestamp(3, Timestamp.from(now)); it.executeUpdate()
            }
            if (consumed != 1) { c.rollback(); return null }
            if (purpose == "reset") {
                c.prepareStatement("UPDATE accounts SET password_hash=? WHERE id=?").use { it.setString(1, hash); it.setObject(2, id.value); it.executeUpdate() }
                c.prepareStatement("DELETE FROM sessions WHERE account_id=?").use { it.setObject(1, id.value); it.executeUpdate() }
                c.prepareStatement("DELETE FROM account_tokens WHERE account_id=? AND purpose='reset'").use { it.setObject(1, id.value); it.executeUpdate() }
            } else c.prepareStatement("UPDATE accounts SET verified=TRUE WHERE id=?").use { it.setObject(1, id.value); it.executeUpdate() }
            c.commit()
            id
        } catch (error: Exception) { c.rollback(); throw error }
    }

    override fun create(account: Account) {
        dataSource.connection.use { connection ->
            connection.prepareStatement("INSERT INTO accounts(id,email,display_name,password_hash,verified,language) VALUES (?,?,?,?,?,?)").use {
                it.setObject(1, account.id.value)
                it.setString(2, account.email)
                it.setString(3, account.displayName)
                it.setString(4, account.passwordHash)
                it.setBoolean(5, account.verified)
                it.setString(6, account.language.code)
                it.executeUpdate()
            }
        }
    }
    override fun byEmail(email: String): Account? = find("email", email)
    override fun byId(id: UserId): Account? = find("id", id.value)
    private fun find(column: String, value: Any): Account? = dataSource.connection.use { connection ->
        connection.prepareStatement("SELECT * FROM accounts WHERE $column = ?").use {
            it.setObject(1, value)
            it.executeQuery().use { rows -> if (rows.next()) rows.account() else null }
        }
    }
    override fun update(account: Account) {
        dataSource.connection.use { connection ->
            connection.prepareStatement("UPDATE accounts SET display_name=?, password_hash=?, verified=?, language=? WHERE id=?").use {
                it.setString(1, account.displayName)
                it.setString(2, account.passwordHash)
                it.setBoolean(3, account.verified)
                it.setString(4, account.language.code)
                it.setObject(5, account.id.value)
                check(it.executeUpdate() == 1) { "Account no longer exists" }
            }
        }
    }
    override fun putToken(token: AccountToken) {
        dataSource.connection.use { connection ->
            connection.prepareStatement("INSERT INTO account_tokens(digest,account_id,purpose,expires_at) VALUES (?,?,?,?)").use {
                it.setString(1, token.digest)
                it.setObject(2, token.accountId.value)
                it.setString(3, token.purpose)
                it.setTimestamp(4, Timestamp.from(token.expiresAt))
                it.executeUpdate()
            }
        }
    }
    override fun consumeToken(digest: String, purpose: String, now: Instant): UserId? = dataSource.connection.use { connection ->
        connection.prepareStatement("DELETE FROM account_tokens WHERE digest=? AND purpose=? AND expires_at>? RETURNING account_id").use {
            it.setString(1, digest)
            it.setString(2, purpose)
            it.setTimestamp(3, Timestamp.from(now))
            it.executeQuery().use { rows -> if (rows.next()) UserId(rows.getObject(1, UUID::class.java)) else null }
        }
    }
    private fun ResultSet.account() = Account(UserId(getObject("id", UUID::class.java)), getString("email"),
        getString("display_name"), getString("password_hash"), getBoolean("verified"), Language.fromCode(getString("language")) ?: Language.ENGLISH)
}
