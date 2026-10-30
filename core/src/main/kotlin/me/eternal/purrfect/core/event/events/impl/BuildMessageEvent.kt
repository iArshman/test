package me.eternal.purrfect.core.event.events.impl

import me.eternal.purrfect.core.event.Event
import me.eternal.purrfect.core.wrapper.impl.Message

class BuildMessageEvent(
    val message: Message
): Event()