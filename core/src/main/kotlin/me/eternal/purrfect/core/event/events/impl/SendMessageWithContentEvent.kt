package me.eternal.purrfect.core.event.events.impl

import me.eternal.purrfect.core.event.events.AbstractHookEvent
import me.eternal.purrfect.core.util.hook.HookStage
import me.eternal.purrfect.core.util.hook.Hooker
import me.eternal.purrfect.core.wrapper.impl.MessageContent
import me.eternal.purrfect.core.wrapper.impl.MessageDestinations

class SendMessageWithContentEvent(
    val destinations: MessageDestinations,
    val messageContent: MessageContent,
    private val callback: Any
) : AbstractHookEvent() {

    fun addCallbackResult(methodName: String, block: (args: Array<Any?>) -> Unit) {
        Hooker.ephemeralHookObjectMethod(
            callback::class.java,
            callback,
            methodName,
            HookStage.BEFORE
        ) { block(it.args()) }
    }
}