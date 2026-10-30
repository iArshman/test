package me.eternal.purrfect.core.features.impl.messaging

import me.eternal.purrfect.common.data.MessagingRuleType
import me.eternal.purrfect.core.features.MessagingRuleFeature
import me.eternal.purrfect.core.util.hook.HookStage
import me.eternal.purrfect.core.util.hook.hook
import me.eternal.purrfect.core.wrapper.impl.SnapUUID

class HideTypingIndicator : MessagingRuleFeature("Hide Typing Indicator", MessagingRuleType.HIDE_TYPING_INDICATOR) {
    private val messaging: Messaging by lazy { context.feature(Messaging::class) }
    
    private fun shouldHideTypingIndicator(conversationId: String?): Boolean {
        return conversationId?.let { canUseRule(it) } ?: false
    }

    private fun currentConversationId(): String? {
        return messaging.openedConversationUUID?.toString()
    }

    override fun init() {
        context.classCache.conversationManager.hook("sendTypingNotification", HookStage.BEFORE, { param ->
            val conversationId = currentConversationId() ?: param.argNullable<Any>(0)?.let {
                runCatching { SnapUUID(it).toString() }.getOrNull()
            }
            shouldHideTypingIndicator(conversationId)
        }) {
            it.setResult(null)
        }
    }
}
