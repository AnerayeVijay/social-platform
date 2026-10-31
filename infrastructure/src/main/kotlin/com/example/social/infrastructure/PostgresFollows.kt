package com.example.social.infrastructure

import com.example.social.application.*
import com.example.social.domain.FollowAccepted
import com.example.social.domain.FollowRemoved
import com.example.social.domain.FollowRequested
import com.example.social.domain.UserId
import com.example.social.notifications.NotificationKind
import com.example.social.sharedkernel.EventBus
import com.example.social.sharedkernel.publishCommitted
import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

class PostgresFollows(private val dataSource: DataSource, private val events: EventBus) : Follows {
    override fun state(follower: UserId, followed: UserId): FollowState = dataSource.connection.use { c ->
        c.prepareStatement("SELECT status FROM member_follows WHERE follower_id = ? AND followed_id = ?").use { s ->
            s.setObject(1, follower.value); s.setObject(2, followed.value)
            s.executeQuery().use { r -> if (r.next()) FollowState.valueOf(r.getString(1).uppercase()) else FollowState.NONE }
        }
    }
    override fun request(follower: UserId, followed: UserId, now: Instant) {
        val inserted = transaction { c ->
            c.prepareStatement("INSERT INTO member_follows(follower_id, followed_id, status, requested_at) VALUES (?, ?, 'pending', ?) ON CONFLICT DO NOTHING").use { s ->
                s.setObject(1, follower.value); s.setObject(2, followed.value); s.setTimestamp(3, Timestamp.from(now))
                s.executeUpdate() == 1
            }
        }
        if (inserted) events.publishCommitted(FollowRequested(follower, followed, now))
    }
    override fun accept(follower: UserId, followed: UserId, now: Instant) {
        val accepted = transaction { c ->
            val updated = c.prepareStatement("UPDATE member_follows SET status = 'accepted', accepted_at = ? WHERE follower_id = ? AND followed_id = ? AND status = 'pending'").use { s ->
                s.setTimestamp(1, Timestamp.from(now)); s.setObject(2, follower.value); s.setObject(3, followed.value)
                s.executeUpdate() == 1
            }
            if (updated) NotificationWriter.remove(c, NotificationKind.FOLLOW_REQUEST, followed, follower)
            updated
        }
        if (accepted) events.publishCommitted(FollowAccepted(follower, followed, now))
    }
    override fun remove(follower: UserId, followed: UserId, only: FollowState?) {
        val removed = transaction { c ->
            val removed = c.prepareStatement("DELETE FROM member_follows WHERE follower_id = ? AND followed_id = ?" +
                (if (only == null) "" else " AND status = ?") + " RETURNING status").use { s ->
                s.setObject(1, follower.value); s.setObject(2, followed.value)
                if (only != null) s.setString(3, only.name.lowercase())
                s.executeQuery().use { r -> if (r.next()) r.getString(1) else null }
            }
            if (removed == "pending") NotificationWriter.remove(c, NotificationKind.FOLLOW_REQUEST, followed, follower)
            removed
        }
        if (removed == "accepted") events.publishCommitted(FollowRemoved(follower, followed))
    }
    override fun list(member: UserId, kind: FollowList, limit: Int, after: UserId?): List<UserId> {
        val outgoing = kind == FollowList.FOLLOWING
        val owner = if (outgoing) "follower_id" else "followed_id"
        val other = if (outgoing) "followed_id" else "follower_id"
        return dataSource.connection.use { c ->
            c.prepareStatement("SELECT $other FROM member_follows WHERE $owner = ? AND status = ?" +
                (if (after == null) "" else " AND $other > ?") + " ORDER BY $other LIMIT ?").use { s ->
                s.setObject(1, member.value)
                s.setString(2, if (kind == FollowList.REQUESTS) "pending" else "accepted")
                var i = 3
                if (after != null) s.setObject(i++, after.value)
                s.setInt(i, limit)
                s.executeQuery().use { r -> buildList { while (r.next()) add(UserId(r.getObject(1, UUID::class.java))) } }
            }
        }
    }
    override fun suggestions(member: UserId, limit: Int): List<UserId> =
        dataSource.connection.use { c ->
            c.prepareStatement("SELECT a.id FROM accounts a WHERE a.verified AND a.id <> ? AND NOT EXISTS (SELECT 1 FROM member_follows f WHERE f.follower_id = ? AND f.followed_id = a.id) ORDER BY a.id LIMIT ?").use { s ->
                s.setObject(1, member.value)
                s.setObject(2, member.value)
                s.setInt(3, limit)
                s.executeQuery().use { r -> buildList { while (r.next()) add(UserId(r.getObject(1, UUID::class.java))) } }
            }
        }
    override fun pendingCount(member: UserId): Long = count(member, FollowList.REQUESTS)
    override fun count(member: UserId, kind: FollowList): Long = dataSource.connection.use { c ->
        val owner = if (kind == FollowList.FOLLOWING) "follower_id" else "followed_id"
        c.prepareStatement("SELECT count(*) FROM member_follows WHERE $owner = ? AND status = ?").use { s ->
            s.setObject(1, member.value)
            s.setString(2, if (kind == FollowList.REQUESTS) "pending" else "accepted")
            s.executeQuery().use { it.next(); it.getLong(1) }
        }
    }
    private fun <T> transaction(action: (Connection) -> T): T = dataSource.connection.use { c ->
        c.autoCommit = false
        try { action(c).also { c.commit() } } catch (error: Exception) { c.rollback(); throw error }
    }
}
