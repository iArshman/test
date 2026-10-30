package me.eternal.purrfect.core.messaging

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import me.eternal.purrfect.bridge.snapclient.MessagingBridge
import me.eternal.purrfect.bridge.snapclient.SessionStartListener
import me.eternal.purrfect.bridge.snapclient.types.Message
import me.eternal.purrfect.common.data.MessageUpdate
import me.eternal.purrfect.core.ModContext
import me.eternal.purrfect.core.features.impl.downloader.decoder.MessageDecoder
import me.eternal.purrfect.core.features.impl.messaging.Messaging


fun me.eternal.purrfect.core.wrapper.impl.Message.toBridge(): Message {
    return Message().also { output ->
        output.conversationId = this.messageDescriptor!!.conversationId.toString()
        output.senderId = this.senderId.toString()
        output.clientMessageId = this.messageDescriptor!!.messageId!!
        output.serverMessageId = this.orderKey!!
        output.contentType = this.messageContent?.contentType?.id ?: -1
        output.content = this.messageContent?.content
        output.mediaReferences = MessageDecoder.getEncodedMediaReferences(this.messageContent!!)
    }
}


class CoreMessagingBridge(
    private val context: ModContext
) : MessagingBridge.Stub() {
    private val conversationManager get() = context.feature(Messaging::class).conversationManager
    private var sessionStartListener: SessionStartListener? = null

    fun triggerSessionStart() {
        sessionStartListener?.onConnected()
        sessionStartListener = null
    }

    override fun isSessionStarted() = conversationManager != null
    override fun registerSessionStartListener(listener: SessionStartListener) {
        sessionStartListener = listener
    }

    override fun getMyUserId() = context.database.myUserId

    override fun fetchMessage(conversationId: String, clientMessageId: String): Message? {
        return runBlocking {
            suspendCancellableCoroutine { continuation ->
                conversationManager?.fetchMessage(
                    conversationId,
                    clientMessageId.toLong(),
                    onSuccess = {
                        continuation.resumeWith(Result.success(it.toBridge()))
                    },
                    onError = { continuation.resumeWith(Result.success(null)) }
                ) ?: continuation.resumeWith(Result.success(null))
            }
        }
    }

    override fun fetchMessageByServerId(
        conversationId: String,
        serverMessageId: String
    ): Message? {
        return runBlocking {
            suspendCancellableCoroutine { continuation ->
                conversationManager?.fetchMessageByServerId(
                    conversationId,
                    serverMessageId.toLong(),
                    onSuccess = {
                        continuation.resumeWith(Result.success(it.toBridge()))
                    },
                    onError = { continuation.resumeWith(Result.success(null)) }
                ) ?: continuation.resumeWith(Result.success(null))
            }
        }
    }

    override fun fetchConversationWithMessagesPaginated(
        conversationId: String,
        limit: Int,
        beforeMessageId: Long
    ): List<Message>? {
        return runBlocking {
            suspendCancellableCoroutine { continuation ->
                conversationManager?.fetchConversationWithMessagesPaginated(
                    conversationId,
                    beforeMessageId,
                    limit,
                    onSuccess = { messages ->
                        continuation.resumeWith(Result.success(messages.map { it.toBridge() }))
                    },
                    onError = {
                        continuation.resumeWith(Result.success(null))
                    }
                ) ?: continuation.resumeWith(Result.success(null))
            }
        }
    }

    override fun updateMessage(
        conversationId: String,
        clientMessageId: Long,
        messageUpdate: String
    ): String? {
        return runBlocking {
            suspendCancellableCoroutine { continuation ->
                conversationManager?.updateMessage(
                    conversationId,
                    clientMessageId,
                    MessageUpdate.valueOf(messageUpdate),
                    onResult = {
                        continuation.resumeWith(Result.success(it))
                    }
                ) ?: continuation.resumeWith(Result.success("ConversationManager is null"))
            }
        }
    }

    override fun getOneToOneConversationId(userId: String) = context.database.getDMConversationId(userId)

    override fun getAutoOpenInterface(): me.eternal.purrfect.bridge.AutoOpenInterface? {
        return context.feature(me.eternal.purrfect.core.features.impl.experiments.AutoOpenSnaps::class).getInterface()
    }
}