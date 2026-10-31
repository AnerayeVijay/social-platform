package com.example.social.infrastructure

import com.example.social.domain.CommentAdded
import com.example.social.domain.FollowAccepted
import com.example.social.domain.FollowRequested
import com.example.social.domain.FollowRemoved
import com.example.social.domain.PostDeleted
import com.example.social.domain.PostLiked
import com.example.social.domain.PostPublished
import com.example.social.domain.PostUnliked
import com.example.social.domain.PostVisibilityChanged
import com.example.social.notifications.NotificationKind
import com.example.social.sharedkernel.DomainEvent
import com.example.social.sharedkernel.EventBus
import java.util.logging.Level
import java.util.logging.Logger
import javax.sql.DataSource

private val log = Logger.getLogger(NotificationHandlers::class.java.name)

class NotificationHandlers(private val dataSource: DataSource) {
    fun register(events: EventBus) {
        events.subscribe(PostLiked::class) { event ->
            dataSource.connection.use { c -> NotificationWriter.addPostLike(c, event.post, event.actor) }
        }
        events.subscribe(PostUnliked::class) { event ->
            dataSource.connection.use { c -> NotificationWriter.removePostLike(c, event.post, event.actor) }
        }
        events.subscribe(PostVisibilityChanged::class) { event ->
            dataSource.connection.use { c -> NotificationWriter.updatePostVisibility(c, event.post, event.visibility.name.lowercase()) }
        }
        events.subscribe(PostDeleted::class) { event ->
            dataSource.connection.use { c -> NotificationWriter.removePostNotices(c, event.post) }
        }
        events.subscribe(FollowRemoved::class) { event ->
            dataSource.connection.use { c -> NotificationWriter.removeMembersOnlyNotices(c, event.follower, event.followed) }
        }
        events.subscribe(CommentAdded::class) { event ->
            dataSource.connection.use { c -> NotificationWriter.addPostComment(c, event.comment) }
        }
        events.subscribe(PostPublished::class) { event ->
            dataSource.connection.use { c -> NotificationWriter.addNewPost(c, event.post) }
        }
        events.subscribe(FollowRequested::class) { event ->
            dataSource.connection.use { c ->
                NotificationWriter.addWhileFollow(c, NotificationKind.FOLLOW_REQUEST, event.followed, event.follower, event.follower, event.followed, "pending", event.at)
            }
        }
        events.subscribe(FollowAccepted::class) { event ->
            dataSource.connection.use { c ->
                NotificationWriter.addWhileFollow(c, NotificationKind.FOLLOW_ACCEPTED, event.follower, event.followed, event.follower, event.followed, "accepted", event.at)
            }
        }
    }
}

/** Call after the change has committed: a failing subscriber is logged and never undoes the committed change. */
internal fun EventBus.publishCommitted(event: DomainEvent) {
    publish(event).forEach { log.log(Level.WARNING, "Notice failed for $event", it) }
}
