package me.eternal.purrfect.core.features.impl.messaging

import android.content.ComponentName
import android.content.Intent
import me.eternal.purrfect.common.Constants
import me.eternal.purrfect.common.ReceiversConfig
import me.eternal.purrfect.core.event.events.impl.ConversationUpdateEvent
import me.eternal.purrfect.core.event.events.impl.OnSnapInteractionEvent
import me.eternal.purrfect.core.features.Feature
import me.eternal.purrfect.core.features.impl.spying.StealthMode
import me.eternal.purrfect.core.util.EvictingMap
import me.eternal.purrfect.core.util.hook.HookStage
import me.eternal.purrfect.core.util.hook.hook
import me.eternal.purrfect.core.util.hook.hookConstructor
import me.eternal.purrfect.core.util.ktx.getObjectField
import me.eternal.purrfect.core.util.ktx.getObjectFieldOrNull
import me.eternal.purrfect.core.wrapper.impl.*
import me.eternal.purrfect.mapper.impl.CallbackMapper
import me.eternal.purrfect.mapper.impl.FriendsFeedEventDispatcherMapper
import me.eternal.purrfect.mapper.impl.PlatformPresenceActionWrapperMapper
import java.util.UUID
import java.util.concurrent.Future

class Messaging : Feature("Messaging") {
    var conversationManager: ConversationManager? = null
        private set
    var snapManager: Any? = null
        private set

    private var conversationManagerDelegate: Any? = null
    private var identityDelegate: Any? = null

    var openedConversationUUID: SnapUUID? = null
        private set
    var lastFocusedConversationId: String? = null
        private set
    var lastFocusedConversationType: Int = -1
        private set
    var lastFocusedMessageId: Long = -1
        private set

    private val feedCachedSnapMessages = EvictingMap<String, List<Long>>(100)
    private val conversationManagerReadyListeners = mutableListOf<() -> Unit>()
    private val openedSnaps = java.util.ArrayDeque<String>(200)

    fun onConversationManagerReady(listener: () -> Unit) {
        synchronized(conversationManagerReadyListeners) {
            conversationManager?.let { listener() } ?: conversationManagerReadyListeners.add(listener)
        }
    }

    fun resetLastFocusedConversation() {
        lastFocusedConversationId = null
        lastFocusedConversationType = -1
    }

    private fun currentConversationId(): String? = openedConversationUUID?.toString()

    private fun shouldHideBitmojiPresence(stealthMode: StealthMode): Boolean {
        return context.config.messaging.hideBitmojiPresence.get() ||
            currentConversationId()?.let { stealthMode.canUseChatStealth(it) } == true
    }

    private fun shouldSpoofViewingGalleryPresence(stealthMode: StealthMode): Boolean {
        return shouldHideBitmojiPresence(stealthMode) || context.config.messaging.spoofViewingGalleryPresence.get()
    }

    private fun shouldSpoofReplyCameraPresence(stealthMode: StealthMode): Boolean {
        return shouldHideBitmojiPresence(stealthMode) || context.config.messaging.spoofReplyCameraPresence.get()
    }

    private fun shouldHideTyping(stealthMode: StealthMode, hideTypingIndicator: HideTypingIndicator): Boolean {
        return context.config.messaging.hideTypingNotifications.get() ||
            currentConversationId()?.let { stealthMode.canUseChatStealth(it) || hideTypingIndicator.canUseRule(it) } == true
    }

    private fun shouldHidePeek(stealthMode: StealthMode): Boolean {
        return context.config.messaging.hidePeekAPeek.get() ||
            currentConversationId()?.let { stealthMode.canUseChatStealth(it) } == true
    }

    private fun clearField(instance: Any, typeNamePart: String, shouldClear: Boolean) {
        if (!shouldClear) return
        instance.javaClass.declaredFields.forEach { field ->
            if (field.type.name.contains(typeNamePart)) {
                field.isAccessible = true
                field.set(instance, null)
            }
        }
    }

    private fun hookLegacyPresenceSession(
        stealthMode: StealthMode,
        hideTypingIndicator: HideTypingIndicator
    ): Boolean {
        val presenceSession = context.classCache.legacyPresenceSession ?: return false

        arrayOf("activate", "deactivate", "processTypingActivity").forEach { hook ->
            presenceSession.hook(hook, HookStage.BEFORE, {
                shouldHideBitmojiPresence(stealthMode)
            }) {
                it.setResult(null)
            }
        }

        presenceSession.hook("startPeeking", HookStage.BEFORE, {
            shouldHidePeek(stealthMode)
        }) { it.setResult(null) }

        presenceSession.hook("processTypingActivity", HookStage.BEFORE, {
            shouldHideTyping(stealthMode, hideTypingIndicator)
        }) {
            it.setResult(null)
        }

        context.log.verbose("Hooked legacy PresenceSession: ${presenceSession.name}", key)
        return true
    }

    private fun hookPlatformPresenceActionWrapper(
        stealthMode: StealthMode,
        hideTypingIndicator: HideTypingIndicator
    ): Boolean {
        var hooked = false

        context.mappings.useMapper(PlatformPresenceActionWrapperMapper::class) {
            classLoader = context.androidContext.classLoader

            fun isMissingTargets(): Boolean {
                return classReference.getAsClass() == null ||
                    listOf(
                        chatVisibleMethod.get(),
                        chatHiddenMethod.get(),
                        startPeekingMethod.get(),
                        typingMethod.get(),
                        usingReplyCameraMethod.get(),
                        viewingChatMediaMethod.get()
                    ).any { it.isNullOrBlank() }
            }

            if (isMissingTargets()) {
                runCatching { context.mappings.refresh() }.onFailure {
                    context.log.error("Failed to refresh mappings for PlatformPresenceActionWrapper", it)
                }
            }

            val wrapperClass = classReference.getAsClass() ?: return@useMapper

            fun requireMethod(methodName: String?, actionName: String): String {
                return methodName ?: throw RuntimeException(
                    "Failed to map PlatformPresenceActionWrapper method for $actionName"
                )
            }

            fun hookAction(
                methodName: String?,
                actionName: String,
                shouldBlock: () -> Boolean
            ) {
                wrapperClass.hook(
                    requireMethod(methodName, actionName),
                    HookStage.BEFORE,
                    { _ -> shouldBlock() }
                ) {
                    it.setResult(null)
                }
            }

            hookAction(chatVisibleMethod.get(), "PlatformChatVisibleAction") {
                shouldHideBitmojiPresence(stealthMode)
            }
            hookAction(chatHiddenMethod.get(), "PlatformChatHiddenAction") {
                shouldHideBitmojiPresence(stealthMode)
            }
            hookAction(startPeekingMethod.get(), "PlatformStartPeekingAction") {
                shouldHidePeek(stealthMode)
            }
            hookAction(typingMethod.get(), "PlatformTypingAction") {
                shouldHideTyping(stealthMode, hideTypingIndicator)
            }
            hookAction(usingReplyCameraMethod.get(), "PlatformUsingReplyCameraAction") {
                shouldSpoofReplyCameraPresence(stealthMode)
            }
            hookAction(viewingChatMediaMethod.get(), "PlatformViewingChatMediaAction") {
                shouldSpoofViewingGalleryPresence(stealthMode)
            }

            wrapperClass.hookConstructor(HookStage.AFTER) { param ->
                val instance = param.thisObject<Any>()
                clearField(instance, "PlatformChatVisibleAction", shouldHideBitmojiPresence(stealthMode))
                clearField(instance, "PlatformChatHiddenAction", shouldHideBitmojiPresence(stealthMode))
                clearField(instance, "PlatformViewingChatMediaAction", shouldSpoofViewingGalleryPresence(stealthMode))
                clearField(instance, "PlatformUsingReplyCameraAction", shouldSpoofReplyCameraPresence(stealthMode))
                clearField(instance, "PlatformTypingAction", shouldHideTyping(stealthMode, hideTypingIndicator))
                clearField(instance, "PlatformStartPeekingAction", shouldHidePeek(stealthMode))
            }

            context.log.verbose("Hooked PlatformPresenceActionWrapper: ${wrapperClass.name}", key)
            hooked = true
        }

        return hooked
    }

    override fun init() {
        val stealthMode = context.feature(StealthMode::class)
        val hideTypingIndicator = context.feature(HideTypingIndicator::class)

        // ANCHOR: Persistent Conversation Focus Hook
        context.mappings.useMapper(FriendsFeedEventDispatcherMapper::class) {
            classReference.getAsClass()?.hook("onItemLongPress", HookStage.BEFORE) { param ->
                val viewItemContainer = param.arg<Any>(0)
                val viewItem = viewItemContainer.getObjectField(viewModelField.get()!!).toString()
                val conversationId = viewItem.substringAfter("conversationId: ").substring(0, 36).also {
                    if (it.startsWith("null")) return@hook
                }
                lastFocusedConversationId = conversationId
                lastFocusedConversationType = context.database.getConversationType(conversationId) ?: 0
                context.log.verbose("Captured Focus: $conversationId")
            }
        }

        context.classCache.conversationManager.hookConstructor(HookStage.BEFORE) { param ->
            synchronized(conversationManagerReadyListeners) {
                conversationManager = ConversationManager(context, param.thisObject())
                context.messagingBridge.triggerSessionStart()
                context.mainActivity?.takeIf { it.intent.getBooleanExtra(ReceiversConfig.MESSAGING_PREVIEW_EXTRA, false) }?.run {
                    startActivity(Intent().apply {
                        setComponent(ComponentName(Constants.MODULE_PACKAGE_NAME, "me.eternal.purrfect.ui.manager.MainActivity"))
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                }
                conversationManagerReadyListeners.removeIf { it(); true }
            }
        }

        context.classCache.snapManager.hookConstructor(HookStage.BEFORE) { param ->
            snapManager = param.thisObject()
        }

        context.mappings.useMapper(CallbackMapper::class) {
            callbacks.getClass("ConversationManagerDelegate")?.apply {
                hookConstructor(HookStage.AFTER) { param ->
                    conversationManagerDelegate = param.thisObject()
                }
                hook("onConversationUpdated", HookStage.BEFORE) { param ->
                    context.event.post(ConversationUpdateEvent(
                        conversationId = SnapUUID(param.arg(0)).toString(),
                        conversation = param.argNullable(1),
                        messages = param.arg<ArrayList<*>>(2).map { Message(it) },
                    ).apply { adapter = param }) {
                        param.setArg(
                            2,
                            messages.map { it.instanceNonNull() }.toCollection(ArrayList())
                        )
                    }
                }
            }
            callbacks.getClass("IdentityDelegate")?.apply {
                hookConstructor(HookStage.AFTER) {
                    identityDelegate = it.thisObject()
                }
            }
        }

        defer {
            val legacyPresenceHooked = hookLegacyPresenceSession(stealthMode, hideTypingIndicator)
            val platformPresenceHooked = hookPlatformPresenceActionWrapper(stealthMode, hideTypingIndicator)
            if (!legacyPresenceHooked && !platformPresenceHooked) {
                throw RuntimeException("Failed to hook Snapchat presence actions")
            }

            context.classCache.conversationManager.hook("sendTypingNotification", HookStage.BEFORE, {
                shouldHideTyping(stealthMode, hideTypingIndicator)
            }) {
                it.setResult(null)
            }

            //get last opened snap for media downloader
            context.event.subscribe(OnSnapInteractionEvent::class) { event ->
                openedConversationUUID = event.conversationId
                lastFocusedMessageId = event.messageId
            }

            context.classCache.conversationManager.hook("fetchMessage", HookStage.BEFORE) { param ->
                val conversationId = SnapUUID(param.arg(0)).toString()
                if (openedConversationUUID?.toString() == conversationId) {
                    lastFocusedMessageId = param.arg(1)
                }
            }

            // Extraction loop: Direct access to avoid creating expensive Message wrappers (JNI memory fix)
            context.classCache.feedEntry.hookConstructor(HookStage.AFTER) { param ->
                val instance = param.thisObject<Any>()
                val interactionInfo = instance.getObjectFieldOrNull("mInteractionInfo") ?: return@hookConstructor
                val nativeMessages = (interactionInfo.getObjectFieldOrNull("mMessages") as? List<*>) ?: return@hookConstructor
                val conversationIdObject = instance.getObjectFieldOrNull("mConversationId") ?: return@hookConstructor
                val conversationId = SnapUUID(conversationIdObject).toString()
                
                val myUserId = context.database.myUserId
                val myUserIdBytes = runCatching { 
                    val uuid = UUID.fromString(myUserId)
                    val bb = java.nio.ByteBuffer.wrap(ByteArray(16))
                    bb.putLong(uuid.mostSignificantBits)
                    bb.putLong(uuid.leastSignificantBits)
                    bb.array()
                }.getOrNull()
                
                val unreadIds = mutableListOf<Pair<Long, Long>>() // orderKey to messageId
                for (nativeMsg in nativeMessages) {
                    if (nativeMsg == null) continue
                    val metadata = nativeMsg.getObjectFieldOrNull("mMetadata") ?: continue
                    val openedBy = metadata.getObjectFieldOrNull("mOpenedBy") as? List<*> ?: continue
                    
                    var openedByMe = false
                    if (myUserIdBytes != null) {
                        for (nativeUuid in openedBy) {
                            val bytes = nativeUuid?.getObjectFieldOrNull("mId") as? ByteArray
                            if (bytes != null && bytes.contentEquals(myUserIdBytes)) {
                                openedByMe = true
                                break
                            }
                        }
                    }

                    if (!openedByMe) {
                        val orderKey = nativeMsg.getObjectFieldOrNull("mOrderKey") as? Long ?: 0L
                        val descriptor = nativeMsg.getObjectFieldOrNull("mDescriptor") ?: continue
                        val messageId = descriptor.getObjectFieldOrNull("mMessageId") as? Long ?: continue
                        unreadIds.add(orderKey to messageId)
                    }
                }

                feedCachedSnapMessages[conversationId] = unreadIds.sortedBy { it.first }.map { it.second }
            }
        }

        onNextActivityCreate {
            context.classCache.conversationManager.apply {
                hook("enterConversation", HookStage.BEFORE) { param ->
                    openedConversationUUID = SnapUUID(param.arg(0))
                    if (context.config.messaging.bypassMessageRetentionPolicy.get()) {
                        val callback = param.argNullable<Any>(2) ?: return@hook
                        callback::class.java.methods.firstOrNull { it.name == "onSuccess" }?.invoke(callback)
                        param.setResult(null)
                    }
                }

                hook("exitConversation", HookStage.BEFORE) {
                    openedConversationUUID = null
                }
            }
        }
    }

    fun getFeedCachedMessageIds(conversationId: String) = feedCachedSnapMessages[conversationId]

    fun clearConversationFromFeed(conversationId: String, onError : (String) -> Unit = {}, onSuccess : () -> Unit = {}) {
        conversationManager?.clearConversation(conversationId, onError = { onError(it) }, onSuccess = {
            runCatching {
                conversationManagerDelegate!!.let {
                    it::class.java.methods.first { method ->
                        method.name == "onConversationRemoved"
                    }.invoke(conversationManagerDelegate, conversationId.toSnapUUID().instanceNonNull())
                }
                onSuccess()
            }.onFailure {
                context.log.error("Failed to invoke onConversationRemoved: $it")
                onError(it.message ?: "Unknown error")
            }
        })
    }

    fun localUpdateMessage(conversationId: String, message: Message, forceUpdate: Boolean = false) {
        if (forceUpdate) {
            message.messageMetadata?.screenRecordedBy = ArrayList<SnapUUID>(message.messageMetadata?.screenRecordedBy ?: emptyList()).apply {
                add(SnapUUID(UUID.randomUUID().toString()))
            }
        }
        conversationManagerDelegate?.let {
            it::class.java.methods.first { method ->
                method.name == "onConversationUpdated"
            }.invoke(conversationManagerDelegate, conversationId.toSnapUUID().instanceNonNull(), null, mutableListOf(message.instanceNonNull()), mutableListOf<Any>())
        }
    }

    fun fetchSnapchatterInfos(userIds: List<String>): List<Snapchatter> {
        val identity = identityDelegate ?: return emptyList()
        val snapUUIDs = userIds.map {
            it.toSnapUUID().instanceNonNull()
        }

        val future = identity::class.java.methods.first {
            it.name == "fetchSnapchatterInfos"
        }.let { method ->
            if (method.parameterCount == 2) method.invoke(identity, snapUUIDs, false)
            else method.invoke(identity, snapUUIDs)
        } as Future<*>

        return (future.get() as? List<*>)?.map { Snapchatter(it) } ?: return emptyList()
    }
}
