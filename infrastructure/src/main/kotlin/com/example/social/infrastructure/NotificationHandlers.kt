package com.example.social.infrastructure

import com.example.social.domain.PostLiked
import com.example.social.sharedkernel.EventBus
import javax.sql.DataSource

class NotificationHandlers(private val dataSource: DataSource) {
    fun register(events: EventBus) {
        events.subscribe(PostLiked::class) { event ->
            dataSource.connection.use { c -> NotificationWriter.addPostLike(c, event.post, event.actor) }
        }
    }
}
