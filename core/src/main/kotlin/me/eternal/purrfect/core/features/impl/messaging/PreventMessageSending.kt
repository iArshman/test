package me.eternal.purrfect.core.features.impl.messaging

import me.eternal.purrfect.common.data.ContentType
import me.eternal.purrfect.common.data.NotificationType
import me.eternal.purrfect.common.util.protobuf.ProtoEditor
import me.eternal.purrfect.common.util.protobuf.ProtoReader
import me.eternal.purrfect.core.event.events.impl.NativeUnaryCallEvent
import me.eternal.purrfect.core.event.events.impl.UnaryCallEvent
import me.eternal.purrfect.core.event.events.impl.SendMessageWithContentEvent
import me.eternal.purrfect.core.features.Feature
import me.eternal.purrfect.core.util.hook.HookStage
import me.eternal.purrfect.core.util.hook.hook

class PreventMessageSending : Feature("Prevent message sending") {
    override fun init() {
        val preventMessageSending by context.config.messaging.preventMessageSending

        fun handleUpdateContentMessage(uri: String, buffer: ByteArray): ByteArray? {
            if (uri != "/messagingcoreservice.MessagingCoreService/UpdateContentMessage") return null
            return ProtoEditor(buffer).apply {
                edit(3) {
                    // replace replayed to read receipt
                    if (firstOrNull(13) != null) {
                        remove(13)
                        addBuffer(4, byteArrayOf())
                    }
                }
            }.toByteArray()
        }

        fun handleNativeUnaryCall(uri: String, buffer: ByteArray): ByteArray? {
            if (uri == "/messagingcoreservice.MessagingCoreService/UpdateContentMessage") {
                return handleUpdateContentMessage(uri, buffer)
            }
            return null
        }

        context.event.subscribe(NativeUnaryCallEvent::class) { event ->
            if (event.uri == "/messagingcoreservice.MessagingCoreService/CreateContentMessage") {
                val contentTypeId = ProtoReader(event.buffer).getVarInt(4, 2)?.toInt() ?: return@subscribe
                val associatedType = NotificationType.fromContentType(ContentType.fromId(contentTypeId)) ?: return@subscribe
                if (preventMessageSending.contains(associatedType.key) && associatedType.key != "snap_replay") {
                    context.log.verbose("Preventing native CreateContentMessage for $associatedType")
                    event.canceled = true
                }
            }

            if (preventMessageSending.contains("snap_replay")) {
                handleNativeUnaryCall(event.uri, event.buffer)?.let { event.buffer = it }
            }
        }

        context.event.subscribe(UnaryCallEvent::class) { event ->
            if (event.uri == "/messagingcoreservice.MessagingCoreService/CreateContentMessage") {
                val contentTypeId = ProtoReader(event.buffer).getVarInt(4, 2)?.toInt() ?: return@subscribe
                val associatedType = NotificationType.fromContentType(ContentType.fromId(contentTypeId)) ?: return@subscribe
                if (preventMessageSending.contains(associatedType.key) && associatedType.key != "snap_replay") {
                    context.log.verbose("Preventing CreateContentMessage for $associatedType")
                    event.canceled = true
                }
            }

            if (preventMessageSending.contains("snap_replay")) {
                handleNativeUnaryCall(event.uri, event.buffer)?.let { event.buffer = it }
            }
        }

        context.classCache.conversationManager.hook("updateMessage", HookStage.BEFORE) { param ->
            val messageUpdate = param.arg<Any>(2).toString()
            if (messageUpdate == "SCREENSHOT" && preventMessageSending.contains("chat_screenshot")) {
                param.setResult(null)
            }

            if (messageUpdate == "SCREEN_RECORD" && preventMessageSending.contains("chat_screen_record")) {
                param.setResult(null)
            }

            if (messageUpdate == "REPLAY" && preventMessageSending.contains("snap_replay")) {
                param.setResult(null)
            }
        }

        context.event.subscribe(SendMessageWithContentEvent::class) { event ->
            val contentType = event.messageContent.contentType
            val associatedType = NotificationType.fromContentType(contentType ?: return@subscribe) ?: return@subscribe

            if (preventMessageSending.contains(associatedType.key)) {
                context.log.verbose("Preventing message sending for $associatedType")
                event.canceled = true
            }
        }
    }
}
