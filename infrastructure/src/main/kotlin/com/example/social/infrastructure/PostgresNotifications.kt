package com.example.social.infrastructure

import com.example.social.domain.CommentId
import com.example.social.domain.PostId
import com.example.social.domain.UserId
import com.example.social.notifications.*
import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

class PostgresNotifications(private val dataSource: DataSource) : NotificationStore {
    override fun list(recipient: UserId, limit: Int, before: Long?): List<Notification> = dataSource.connection.use { c ->
        select(c, recipient, "AND n.id < ? ORDER BY n.id DESC LIMIT ?") { s -> s.setLong(3, before ?: Long.MAX_VALUE); s.setInt(4, limit) }
    }
    override fun unreadCount(recipient: UserId): Long = dataSource.connection.use { c ->
        c.prepareStatement("SELECT count(*) FROM notifications n $VISIBLE_TO_RECIPIENT AND n.read_at IS NULL").use { s ->
            s.setObject(1, recipient.value); s.setObject(2, recipient.value)
            s.executeQuery().use { it.next(); it.getLong(1) }
        }
    }

    override fun markRead(recipient: UserId, id: Long, now: Instant): Notification? = dataSource.connection.use { c ->
        // COALESCE keeps the first read time, so repeating the action is a no-op that still returns the notice.
        c.prepareStatement("UPDATE notifications SET read_at = COALESCE(read_at, ?) WHERE id IN (SELECT n.id FROM notifications n $VISIBLE_TO_RECIPIENT AND n.id = ?)").use { s ->
            s.setTimestamp(1, Timestamp.from(now)); s.setObject(2, recipient.value); s.setObject(3, recipient.value); s.setLong(4, id)
            s.executeUpdate()
        }
        select(c, recipient, "AND n.id = ?") { s -> s.setLong(3, id) }.singleOrNull()
    }

    /** One statement: only notices committed and visible to the member when the action starts are marked. */
    override fun markAllRead(recipient: UserId, now: Instant) {
        dataSource.connection.use { c ->
            c.prepareStatement("UPDATE notifications SET read_at = ? WHERE read_at IS NULL AND id IN (SELECT n.id FROM notifications n $VISIBLE_TO_RECIPIENT)").use { s ->
                s.setTimestamp(1, Timestamp.from(now)); s.setObject(2, recipient.value); s.setObject(3, recipient.value)
                s.executeUpdate()
            }
        }
    }

    private fun select(c: Connection, recipient: UserId, tail: String, bind: (java.sql.PreparedStatement) -> Unit): List<Notification> =
        c.prepareStatement("SELECT n.id, n.kind, n.actor_id, a.display_name, n.read_at, n.created_at, n.post_id, n.comment_id FROM notifications n " +
            "JOIN accounts a ON a.id = n.actor_id $VISIBLE_TO_RECIPIENT $tail").use { s ->
            s.setObject(1, recipient.value); s.setObject(2, recipient.value); bind(s)
            s.executeQuery().use { r -> buildList { while (r.next()) add(Notification(
                r.getLong(1), NotificationKind.valueOf(r.getString(2).uppercase()), UserId(r.getObject(3, UUID::class.java)),
                r.getString(4), r.getTimestamp(5) != null, r.getTimestamp(6).toInstant(),
                r.getObject(7, UUID::class.java)?.let(::PostId), r.getObject(8, UUID::class.java)?.let(::CommentId)
            )) } }
        }

    private companion object {
        /** Binds recipient twice: post notices show only while the recipient can currently see the post. */
        val VISIBLE_TO_RECIPIENT = "LEFT JOIN posts p ON p.id = n.post_id WHERE n.recipient_id = ? AND (n.post_id IS NULL OR ${postVisibilitySql()})"
    }
}

/** Writes notices on the caller's connection so they commit or roll back with the triggering change. */
internal object NotificationWriter {
    /** Writes only while the follow still has the expected status, so a withdrawn request leaves no notice behind. */
    fun addWhileFollow(c: Connection, kind: NotificationKind, recipient: UserId, actor: UserId, follower: UserId, followed: UserId, status: String, now: Instant) {
        require(recipient != actor) { "Members are not notified of their own actions" }
        c.prepareStatement("""
            INSERT INTO notifications(recipient_id, actor_id, kind, created_at)
            SELECT ?, ?, ?, ? WHERE EXISTS (SELECT 1 FROM member_follows WHERE follower_id = ? AND followed_id = ? AND status = ?)
            ON CONFLICT DO NOTHING
        """.trimIndent()).use { s ->
            s.setObject(1, recipient.value); s.setObject(2, actor.value)
            s.setString(3, kind.name.lowercase()); s.setTimestamp(4, Timestamp.from(now))
            s.setObject(5, follower.value); s.setObject(6, followed.value); s.setString(7, status)
            s.executeUpdate()
        }
    }
    fun remove(c: Connection, kind: NotificationKind, recipient: UserId, actor: UserId) {
        c.prepareStatement("DELETE FROM notifications WHERE recipient_id = ? AND actor_id = ? AND kind = ? AND post_id IS NULL AND comment_id IS NULL").use { s ->
            s.setObject(1, recipient.value); s.setObject(2, actor.value); s.setString(3, kind.name.lowercase())
            s.executeUpdate()
        }
    }
    /** Fans a new post out to the author's current accepted followers who may see it; no later backfill. */
    fun addNewPost(c: Connection, post: PostId) {
        c.prepareStatement("""
            INSERT INTO notifications(recipient_id, actor_id, kind, post_id, created_at, post_author_id, post_visibility)
            SELECT f.follower_id, p.author_id, 'new_post', p.id, p.created_at, p.author_id, p.visibility FROM posts p
            JOIN member_follows f ON f.followed_id = p.author_id AND f.status = 'accepted'
            WHERE p.id = ? AND p.visibility IN ('public', 'members')
            ON CONFLICT DO NOTHING
        """.trimIndent()).use { s -> s.setObject(1, post.value); s.executeUpdate() }
    }
    fun addPostLike(c: Connection, post: PostId, actor: UserId) {
        c.prepareStatement("""
            INSERT INTO notifications(recipient_id, actor_id, kind, post_id, created_at, post_author_id, post_visibility)
            SELECT p.author_id, actor.id, 'post_like', p.id, now(), p.author_id, p.visibility FROM posts p
            JOIN accounts author ON author.id = p.author_id JOIN accounts actor ON actor.id = ?
            WHERE p.id = ? AND p.author_id <> actor.id
              AND EXISTS (SELECT 1 FROM post_likes l WHERE l.post_id = p.id AND l.user_id = actor.id)
            ON CONFLICT DO NOTHING
        """.trimIndent()).use { s -> s.setObject(1, actor.value); s.setObject(2, post.value); s.executeUpdate() }
    }
    /** Withdraws the notice only while the like is really gone, so a re-like made meanwhile keeps its notice. */
    fun removePostLike(c: Connection, post: PostId, actor: UserId) {
        c.prepareStatement("DELETE FROM notifications WHERE kind = 'post_like' AND post_id = ? AND actor_id = ? AND NOT EXISTS (SELECT 1 FROM post_likes WHERE post_id = ? AND user_id = ?)").use { s ->
            s.setObject(1, post.value); s.setObject(2, actor.value); s.setObject(3, post.value); s.setObject(4, actor.value); s.executeUpdate()
        }
    }
    /** Notifies the post owner of a comment or reply on their post; skips own comments and account-less legacy ids. */
    fun addPostComment(c: Connection, comment: CommentId) {
        c.prepareStatement("""
            INSERT INTO notifications(recipient_id, actor_id, kind, post_id, comment_id, created_at, post_author_id, post_visibility)
            SELECT p.author_id, actor.id, 'comment', p.id, cm.id, now(), p.author_id, p.visibility FROM comments cm JOIN posts p ON p.id = cm.post_id
            JOIN accounts owner ON owner.id = p.author_id JOIN accounts actor ON actor.id = cm.author_id
            WHERE cm.id = ? AND p.author_id <> actor.id
            ON CONFLICT DO NOTHING
        """.trimIndent()).use { s -> s.setObject(1, comment.value); s.executeUpdate() }
    }
}
