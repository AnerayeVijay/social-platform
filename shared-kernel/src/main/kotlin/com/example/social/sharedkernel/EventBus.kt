package com.example.social.sharedkernel

import kotlin.reflect.KClass

interface DomainEvent

class EventBus {
    private val handlers = mutableMapOf<KClass<*>, MutableList<(DomainEvent) -> Unit>>()

    fun <E : DomainEvent> subscribe(type: KClass<E>, handler: (E) -> Unit) {
        handlers.getOrPut(type) { mutableListOf() }.add { handler(type.java.cast(it)) }
    }

    fun publish(event: DomainEvent) {
        handlers[event::class].orEmpty().forEach { it(event) }
    }
}
