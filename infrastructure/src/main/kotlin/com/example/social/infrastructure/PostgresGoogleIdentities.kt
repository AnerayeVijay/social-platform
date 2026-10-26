package com.example.social.infrastructure

import com.example.social.application.GoogleIdentities
import com.example.social.domain.UserId
import java.util.UUID
import javax.sql.DataSource

class PostgresGoogleIdentities(private val dataSource: DataSource) : GoogleIdentities {
    override fun find(subject: String): UserId? = dataSource.connection.use { c ->
        c.prepareStatement("SELECT account_id FROM google_identities WHERE subject=?").use {
            it.setString(1, subject)
            it.executeQuery().use { rows -> if (rows.next()) UserId(rows.getObject(1, UUID::class.java)) else null }
        }
    }
    override fun link(subject: String, account: UserId) {
        save(subject, account, null)
    }
    override fun link(subject: String, account: UserId, email: String) {
        save(subject, account, email)
    }
    override fun emailsFor(account: UserId): List<String?> = dataSource.connection.use { c ->
        c.prepareStatement("SELECT email FROM google_identities WHERE account_id=? ORDER BY subject").use {
            it.setObject(1, account.value)
            it.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString("email")) } }
        }
    }
    private fun save(subject: String, account: UserId, email: String?) {
        dataSource.connection.use { c ->
            c.prepareStatement("INSERT INTO google_identities(subject,account_id,email) VALUES (?,?,?) ON CONFLICT(subject) DO UPDATE SET email=COALESCE(EXCLUDED.email,google_identities.email) WHERE google_identities.account_id=EXCLUDED.account_id").use {
                it.setString(1, subject); it.setObject(2, account.value); it.setString(3, email)
                check(it.executeUpdate() == 1) { "Google identity is already linked" }
            }
        }
    }
}
