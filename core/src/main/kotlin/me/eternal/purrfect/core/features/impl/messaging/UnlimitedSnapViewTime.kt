package me.eternal.purrfect.core.features.impl.messaging

import me.eternal.purrfect.common.data.ContentType
import me.eternal.purrfect.common.data.MessageState
import me.eternal.purrfect.common.util.protobuf.ProtoEditor
import me.eternal.purrfect.common.util.protobuf.ProtoReader
import me.eternal.purrfect.core.event.events.impl.BuildMessageEvent
import me.eternal.purrfect.core.features.Feature

class UnlimitedSnapViewTime : Feature("UnlimitedSnapViewTime") {
    override fun init() {
        onNextActivityCreate {
            val state by context.config.messaging.unlimitedSnapViewTime

            context.event.subscribe(BuildMessageEvent::class, { state }, priority = 101) { event ->
                if (event.message.messageState != MessageState.COMMITTED) return@subscribe
                if (event.message.messageContent!!.contentType != ContentType.SNAP) return@subscribe

                val messageContent = event.message.messageContent

                val mediaAttributes = ProtoReader(messageContent!!.content!!).followPath(11, 5, 2) ?: return@subscribe
                if (mediaAttributes.contains(6)) return@subscribe
                messageContent.content = ProtoEditor(messageContent.content!!).apply {
                    edit(11, 5, 2) {
                        remove(8)
                        addBuffer(6, byteArrayOf())
                    }
                }.toByteArray()
            }
        }
    }
}
