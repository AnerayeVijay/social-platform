package com.example.social.infrastructure

import com.example.social.application.*
import java.sql.Timestamp
import java.time.Instant
import java.time.OffsetDateTime
import javax.sql.DataSource

class PostgresOAuthAttempts(private val dataSource: DataSource) : OAuthAttempts {
    override fun save(attempt: OAuthAttempt) {
        dataSource.connection.use { c ->
            c.prepareStatement("INSERT INTO oauth_attempts(digest,nonce,verifier,link_session,expires_at) VALUES (?,?,?,?,?)").use {
                it.setString(1, attempt.digest); it.setString(2, attempt.nonce); it.setString(3, attempt.verifier)
                it.setString(4, attempt.linkSession); it.setTimestamp(5, Timestamp.from(attempt.expiresAt)); it.executeUpdate()
            }
        }
    }
    override fun consume(digest: String, now: Instant): OAuthAttempt? = dataSource.connection.use { c ->
        c.prepareStatement("DELETE FROM oauth_attempts WHERE digest=? AND expires_at>? RETURNING *").use {
            it.setString(1, digest); it.setTimestamp(2, Timestamp.from(now))
            it.executeQuery().use { rows ->
                if (rows.next()) OAuthAttempt(rows.getString("digest").trim(), rows.getString("nonce"), rows.getString("verifier"),
                    rows.getString("link_session")?.trim(), rows.getObject("expires_at", OffsetDateTime::class.java).toInstant()) else null
            }
        }
    }
}
