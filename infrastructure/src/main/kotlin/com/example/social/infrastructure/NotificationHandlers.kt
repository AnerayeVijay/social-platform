package com.example.social.infrastructure

import com.example.social.domain.CommentAdded
import com.example.social.domain.PostLiked
import com.example.social.domain.PostPublished
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
        events.subscribe(CommentAdded::class) { event ->
            dataSource.connection.use { c -> NotificationWriter.addPostComment(c, event.comment) }
        }
        events.subscribe(PostPublished::class) { event ->
            dataSource.connection.use { c -> NotificationWriter.addNewPost(c, event.post) }
        }
    }
}

/** Call after the change has committed: a failing subscriber is logged and never undoes the committed change. */
internal fun EventBus.publishCommitted(event: DomainEvent) {
    publish(event).forEach { log.log(Level.WARNING, "Notice failed for $event", it) }
}
