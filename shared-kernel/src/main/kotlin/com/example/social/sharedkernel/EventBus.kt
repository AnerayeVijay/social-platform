package com.example.social.sharedkernel

import kotlin.reflect.KClass

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
