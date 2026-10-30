package me.eternal.purrfect.core.event.events.impl

import me.eternal.purrfect.core.event.events.AbstractHookEvent
import me.eternal.purrfect.core.wrapper.impl.Message

class ConversationUpdateEvent(
    val conversationId: String,
    val conversation: Any?,
    val messages: List<Message>
) : AbstractHookEvent()