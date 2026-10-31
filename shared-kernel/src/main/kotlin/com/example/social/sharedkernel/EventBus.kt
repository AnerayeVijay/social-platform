package com.example.social.sharedkernel

import java.util.logging.Level
import java.util.logging.Logger
import kotlin.reflect.KClass

private val log = Logger.getLogger(EventBus::class.java.name)

interface DomainEvent

class EventBus {
    private val handlers = mutableMapOf<KClass<*>, MutableList<(DomainEvent) -> Unit>>()

    fun <E : DomainEvent> subscribe(type: KClass<E>, handler: (E) -> Unit) {
        handlers.getOrPut(type) { mutableListOf() }.add { handler(type.java.cast(it)) }
    }

    fun publish(event: DomainEvent): List<Throwable> {
        return handlers[event::class].orEmpty().mapNotNull { handler ->
            runCatching { handler(event) }.exceptionOrNull()
        }
    }
}

/** Call after the change has committed: a failing subscriber is logged and never undoes the committed change. */
fun EventBus.publishCommitted(event: DomainEvent) {
    publish(event).forEach { log.log(Level.WARNING, "Subscriber failed for $event", it) }
}
