package me.eternal.purrfect.core.event.events.impl

import me.eternal.purrfect.core.event.events.AbstractHookEvent
import me.eternal.purrfect.core.wrapper.impl.SnapUUID

class OnSnapInteractionEvent(
    val interactionType: String,
    val conversationId: SnapUUID,
    val messageId: Long
) : AbstractHookEvent()