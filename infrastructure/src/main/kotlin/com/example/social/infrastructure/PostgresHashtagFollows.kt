package com.example.social.infrastructure

import com.example.social.application.HashtagFollows
import com.example.social.domain.Hashtag
import com.example.social.domain.UserId
import javax.sql.DataSource

class PostgresHashtagFollows(private val dataSource: DataSource) : HashtagFollows {
    override fun add(member: UserId, tag: Hashtag) {
        dataSource.connection.use { c ->
            c.prepareStatement("INSERT INTO hashtag_follows(member_id, name) VALUES (?, ?) ON CONFLICT DO NOTHING").use { s ->
                s.setObject(1, member.value); s.setString(2, tag.value)
                s.executeUpdate()
            }
        }
    }
    override fun remove(member: UserId, tag: Hashtag) {
        dataSource.connection.use { c ->
            c.prepareStatement("DELETE FROM hashtag_follows WHERE member_id = ? AND name = ?").use { s ->
                s.setObject(1, member.value); s.setString(2, tag.value)
                s.executeUpdate()
            }
        }
    }
    override fun list(member: UserId): List<Hashtag> =
        dataSource.connection.use { c ->
            c.prepareStatement("SELECT name FROM hashtag_follows WHERE member_id = ? ORDER BY name").use { s ->
                s.setObject(1, member.value)
                s.executeQuery().use { r -> buildList { while (r.next()) add(Hashtag(r.getString(1))) } }
            }
        }
}
