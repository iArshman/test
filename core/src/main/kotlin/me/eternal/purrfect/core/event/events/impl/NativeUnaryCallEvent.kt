package me.eternal.purrfect.core.event.events.impl

import me.eternal.purrfect.core.event.events.AbstractHookEvent

class NativeUnaryCallEvent(
    val uri: String,
    var buffer: ByteArray
) : AbstractHookEvent()