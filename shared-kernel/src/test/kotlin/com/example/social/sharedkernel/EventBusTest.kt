package com.example.social.sharedkernel

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class EventBusTest {
    private data class PostLiked(val by: AccountId) : DomainEvent
    private data class CommentAdded(val by: AccountId) : DomainEvent

    @Test fun `subscribers receive only the event type they subscribed to`() {
        val bus = EventBus()
        val received = mutableListOf<DomainEvent>()
        bus.subscribe(PostLiked::class) { received += it }
        val liked = PostLiked(AccountId(UUID.randomUUID()))

        bus.publish(liked)
        bus.publish(CommentAdded(AccountId(UUID.randomUUID())))

        assertEquals(listOf<DomainEvent>(liked), received)
    }

    @Test fun `a failing subscriber does not stop the others and its failure is returned`() {
        val bus = EventBus()
        val failure = IllegalStateException("notice store down")
        val received = mutableListOf<DomainEvent>()
        bus.subscribe(PostLiked::class) { throw failure }
        bus.subscribe(PostLiked::class) { received += it }
        val liked = PostLiked(AccountId(UUID.randomUUID()))

        val failures = bus.publish(liked)

        assertEquals(listOf<DomainEvent>(liked), received)
        assertEquals(listOf<Throwable>(failure), failures)
    }
}
