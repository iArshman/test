package me.eternal.purrfect.core.event.events.impl

import me.eternal.purrfect.core.event.events.AbstractHookEvent

class UnaryCallEvent(
    val uri: String,
    var buffer: ByteArray
): AbstractHookEvent() {
    val callbacks = mutableListOf<(UnaryCallEvent) -> Unit>()

    fun addResponseCallback(responseCallback: UnaryCallEvent.() -> Unit) {
        callbacks.add(responseCallback)
    }
}