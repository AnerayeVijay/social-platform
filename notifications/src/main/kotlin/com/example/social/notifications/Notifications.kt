package com.example.social.notifications

import com.example.social.domain.CommentId
import com.example.social.domain.PostId
import com.example.social.domain.UserId
import java.time.Instant

enum class NotificationKind { FOLLOW_REQUEST, FOLLOW_ACCEPTED, NEW_POST, POST_LIKE, COMMENT }

data class Notification(
    val id: Long,
    val kind: NotificationKind,
    val actor: UserId,
    val actorName: String,
    val read: Boolean,
    val createdAt: Instant,
    val post: PostId? = null,
    val comment: CommentId? = null,
)

data class NotificationPage(val notifications: List<Notification>, val next: Long?)

interface NotificationStore {
    fun list(recipient: UserId, limit: Int, before: Long? = null): List<Notification>
    fun unreadCount(recipient: UserId): Long
    fun markRead(recipient: UserId, id: Long, now: Instant): Notification?
    fun markAllRead(recipient: UserId, now: Instant)
}

class Notifications(private val store: NotificationStore, private val now: () -> Instant = Instant::now) {
    fun list(recipient: UserId, limit: Int = 20): List<Notification> {
        require(limit in 1..100) { "limit must be between 1 and 100" }
        return store.list(recipient, limit)
    }
    fun page(recipient: UserId, limit: Int = 20, before: Long? = null): NotificationPage {
        require(limit in 1..100) { "limit must be between 1 and 100" }
        require(before == null || before > 0) { "before must be a positive notification id" }
        val rows = store.list(recipient, limit + 1, before)
        val shown = rows.take(limit)
        return NotificationPage(shown, if (rows.size > limit) shown.last().id else null)
    }
    fun unreadCount(recipient: UserId): Long = store.unreadCount(recipient)
    fun markRead(recipient: UserId, id: Long): Notification? = store.markRead(recipient, id, now())
    fun markAllRead(recipient: UserId) = store.markAllRead(recipient, now())
}
