package com.example.social.infrastructure

import com.example.social.application.*
import com.example.social.domain.UserId
import java.sql.Timestamp
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import javax.sql.DataSource

class PostgresSessions(private val dataSource: DataSource) : Sessions {
    override fun create(session: Session) {
        dataSource.connection.use { c ->
            c.prepareStatement("INSERT INTO sessions(digest,account_id,csrf,expires_at) VALUES (?,?,?,?)").use {
                it.setString(1, session.digest); it.setObject(2, session.accountId.value)
                it.setString(3, session.csrf); it.setTimestamp(4, Timestamp.from(session.expiresAt)); it.executeUpdate()
            }
        }
    }
    override fun find(digest: String, now: Instant): Session? = dataSource.connection.use { c ->
        c.prepareStatement("SELECT * FROM sessions WHERE digest=? AND expires_at>?").use {
            it.setString(1, digest); it.setTimestamp(2, Timestamp.from(now))
            it.executeQuery().use { rows ->
                if (rows.next()) Session(rows.getString("digest").trim(), UserId(rows.getObject("account_id", UUID::class.java)),
                    rows.getString("csrf"), rows.getObject("expires_at", OffsetDateTime::class.java).toInstant()) else null
            }
        }
    }
    override fun delete(digest: String) { deleteWhere("digest", digest) }
    override fun deleteForAccount(id: UserId) { deleteWhere("account_id", id.value) }
    private fun deleteWhere(column: String, value: Any) {
        dataSource.connection.use { c ->
            c.prepareStatement("DELETE FROM sessions WHERE $column=?").use { it.setObject(1, value); it.executeUpdate() }
        }
    }
}
