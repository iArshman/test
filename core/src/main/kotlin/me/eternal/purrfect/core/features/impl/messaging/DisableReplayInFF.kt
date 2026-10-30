package me.eternal.purrfect.core.features.impl.messaging

import me.eternal.purrfect.core.features.Feature
import me.eternal.purrfect.core.util.hook.HookStage
import me.eternal.purrfect.core.util.hook.hookConstructor
import me.eternal.purrfect.core.util.ktx.getObjectField
import me.eternal.purrfect.core.util.ktx.setEnumField

class DisableReplayInFF : Feature("DisableReplayInFF") {
    override fun init() {
        val state by context.config.messaging.disableReplayInFF

        onNextActivityCreate(defer = true) {
            findClass("com.snapchat.client.messaging.InteractionInfo")
                .hookConstructor(HookStage.AFTER, { state }) { param ->
                    val instance = param.thisObject<Any>()
                    if (instance.getObjectField("mLongPressActionState").toString() == "REQUEST_SNAP_REPLAY") {
                        instance.setEnumField("mLongPressActionState", "SHOW_CONVERSATION_ACTION_MENU")
                    }
                }
        }
    }
}