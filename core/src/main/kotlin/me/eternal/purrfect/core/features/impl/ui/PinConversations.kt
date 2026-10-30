package me.eternal.purrfect.core.features.impl.ui

import me.eternal.purrfect.common.data.MessagingRuleType
import me.eternal.purrfect.common.data.RuleState
import me.eternal.purrfect.core.features.MessagingRuleFeature
import me.eternal.purrfect.core.util.hook.HookStage
import me.eternal.purrfect.core.util.hook.Hooker
import me.eternal.purrfect.core.util.hook.hook
import me.eternal.purrfect.core.util.hook.hookConstructor
import me.eternal.purrfect.core.util.ktx.getObjectFieldOrNull
import me.eternal.purrfect.core.util.ktx.setObjectField
import me.eternal.purrfect.core.wrapper.impl.SnapUUID
import me.eternal.purrfect.mapper.impl.CallbackMapper
import java.util.Collections

class PinConversations : MessagingRuleFeature("PinConversations", MessagingRuleType.PIN_CONVERSATION) {
    companion object {
        // Year 2030 Static Baseline to prevent jitter/jumbling during feed refreshes
        private const val STATIC_PIN_TIMESTAMP = 1893456000000L 
    }

    private val conversationIdCache = java.util.WeakHashMap<Any, String>()

    private fun getConversationId(entry: Any): String? {
        synchronized(conversationIdCache) {
            val cached = conversationIdCache[entry]
            if (cached != null) return cached.takeIf { it.isNotEmpty() }
            val conversationIdObject = entry.getObjectFieldOrNull("mConversationId") ?: return null
            val idStr = SnapUUID(conversationIdObject).toString()
            conversationIdCache[entry] = idStr
            return idStr
        }
    }

    private class SortingEntry(
        val original: Any,
        val pinnedTimestamp: Long,
        val interactionTimestamp: Long,
        val identity: String
    )

    private fun forcePinsInFeed(entries: ArrayList<Any>) {
        val sortingList = ArrayList<SortingEntry>(entries.size)

        entries.forEach { entry ->
            val conversationId = getConversationId(entry)
            var ts = entry.getObjectFieldOrNull("mPinnedTimestampMs") as? Long ?: 0L

            if (conversationId != null) {
                val isPinned = getState(conversationId)
                if (isPinned) {
                    if (ts != STATIC_PIN_TIMESTAMP) {
                        ts = STATIC_PIN_TIMESTAMP
                        entry.setObjectField("mPinnedTimestampMs", STATIC_PIN_TIMESTAMP)
                    }
                } else {
                    if (ts == STATIC_PIN_TIMESTAMP) {
                        ts = 0L
                        entry.setObjectField("mPinnedTimestampMs", 0L)
                    }
                }
            }

            val interaction = entry.getObjectFieldOrNull("mLastInteractionTimestamp") as? Long 
                ?: entry.getObjectFieldOrNull("mLastMessageTimestamp") as? Long ?: 0L

            sortingList.add(SortingEntry(entry, ts, interaction, entry.toString()))
        }

        // Stable sort
        sortingList.sortWith { a, b ->
            if (a.pinnedTimestamp == b.pinnedTimestamp) {
                if (a.interactionTimestamp == b.interactionTimestamp) {
                    a.identity.compareTo(b.identity)
                } else {
                    b.interactionTimestamp.compareTo(a.interactionTimestamp)
                }
            } else {
                b.pinnedTimestamp.compareTo(a.pinnedTimestamp)
            }
        }

        // Reorder entries
        entries.clear()
        sortingList.forEach { entries.add(it.original) }
    }

    override fun init() {
        if (!context.config.messaging.unlimitedConversationPinning.get()) return

        // Intercept native pinning requests and bypass server-side limits
        context.classCache.feedManager.hook("setPinnedConversationStatus", HookStage.BEFORE) { param ->
            val conversationUUID = SnapUUID(param.arg(0))
            val isPinned = param.arg<Any>(1).toString() == "PINNED"
            setState(conversationUUID.toString(), isPinned)

            // Callback forcing to suppress "Can't pin conversation" errors for both PIN and UNPIN
            val callback = param.arg<Any>(2)
            mutableSetOf<() -> Unit>().apply {
                addAll(Hooker.ephemeralHookObjectMethod(callback::class.java, callback, "onSuccess", HookStage.BEFORE) {
                    forEach { it() }
                })
                addAll(Hooker.ephemeralHookObjectMethod(callback::class.java, callback, "onError", HookStage.BEFORE) { methodParam ->
                    methodParam.setResult(null)
                    // Manually trigger success to bypass server-side limit rejections
                    callback::class.java.getDeclaredMethod("onSuccess").invoke(callback)
                })
            }
        }

        // Active feed sweep to ensure pinned conversations remain at the top
        context.mappings.useMapper(CallbackMapper::class) {
            val callbackMap = callbacks.getAsMap().orEmpty()
            callbackMap.entries.forEach { (_, className) ->
                val clazz = runCatching { findClass(className!!) }.getOrNull() ?: return@forEach
                clazz.methods.forEach { method ->
                    if (method.name.startsWith("on") && method.name.endsWith("Complete") && method.parameterTypes.any { it == ArrayList::class.java }) {
                        clazz.hook(method.name, HookStage.BEFORE) { param ->
                            (param.args().firstOrNull { it is ArrayList<*> } as? ArrayList<Any>)?.let { forcePinsInFeed(it) }
                        }
                    }
                }
            }
        }

        // Apply static pinning lead to newly created objects
        context.classCache.conversation.hookConstructor(HookStage.AFTER) { param ->
            val instance = param.thisObject<Any>()
            val conversationIdObject = instance.getObjectFieldOrNull("mConversationId") ?: return@hookConstructor
            runCatching {
                val conversationUUID = SnapUUID(conversationIdObject)
                if (getState(conversationUUID.toString())) {
                    instance.setObjectField("mPinnedTimestampMs", STATIC_PIN_TIMESTAMP)
                }
            }
        }

        context.classCache.feedEntry.hookConstructor(HookStage.AFTER) { param ->
            val instance = param.thisObject<Any>()
            val conversationIdObject = instance.getObjectFieldOrNull("mConversationId") ?: return@hookConstructor
            runCatching {
                val conversationUUID = SnapUUID(conversationIdObject)
                if (getState(conversationUUID.toString())) {
                    instance.setObjectField("mPinnedTimestampMs", STATIC_PIN_TIMESTAMP)
                }
            }
        }
    }

    override fun getRuleState() = RuleState.WHITELIST
}
