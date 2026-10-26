package com.example.social.infrastructure

import com.example.social.application.*
import com.example.social.domain.UserId
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

class PostgresFollows(private val dataSource: DataSource) : Follows {
    override fun state(follower: UserId, followed: UserId): FollowState = dataSource.connection.use { c ->
        c.prepareStatement("SELECT status FROM member_follows WHERE follower_id = ? AND followed_id = ?").use { s ->
            s.setObject(1, follower.value); s.setObject(2, followed.value)
            s.executeQuery().use { r -> if (r.next()) FollowState.valueOf(r.getString(1).uppercase()) else FollowState.NONE }
        }
    }
    override fun request(follower: UserId, followed: UserId, now: Instant) {
        write("INSERT INTO member_follows(follower_id, followed_id, status, requested_at) VALUES (?, ?, 'pending', ?) ON CONFLICT DO NOTHING", follower, followed, now)
    }
    override fun accept(follower: UserId, followed: UserId, now: Instant) {
        write("UPDATE member_follows SET status = 'accepted', accepted_at = ? WHERE follower_id = ? AND followed_id = ? AND status = 'pending'", follower, followed, now, timeFirst = true)
    }
    override fun remove(follower: UserId, followed: UserId, only: FollowState?) {
        dataSource.connection.use { c ->
            c.prepareStatement("DELETE FROM member_follows WHERE follower_id = ? AND followed_id = ?" + if (only == null) "" else " AND status = ?").use { s ->
                s.setObject(1, follower.value); s.setObject(2, followed.value)
                if (only != null) s.setString(3, only.name.lowercase())
                s.executeUpdate()
            }
        }
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
    override fun pendingCount(member: UserId): Long = count(member, FollowList.REQUESTS)
    override fun count(member: UserId, kind: FollowList): Long = dataSource.connection.use { c ->
        val owner = if (kind == FollowList.FOLLOWING) "follower_id" else "followed_id"
        c.prepareStatement("SELECT count(*) FROM member_follows WHERE $owner = ? AND status = ?").use { s ->
            s.setObject(1, member.value)
            s.setString(2, if (kind == FollowList.REQUESTS) "pending" else "accepted")
            s.executeQuery().use { it.next(); it.getLong(1) }
        }
    }
    private fun write(sql: String, follower: UserId, followed: UserId, now: Instant, timeFirst: Boolean = false) {
        dataSource.connection.use { c -> c.prepareStatement(sql).use { s ->
            s.setObject(if (timeFirst) 2 else 1, follower.value)
            s.setObject(if (timeFirst) 3 else 2, followed.value)
            s.setTimestamp(if (timeFirst) 1 else 3, Timestamp.from(now))
            s.executeUpdate()
        } }
    }
}
