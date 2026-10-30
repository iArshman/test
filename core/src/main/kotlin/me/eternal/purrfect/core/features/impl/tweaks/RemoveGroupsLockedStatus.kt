package me.eternal.purrfect.core.features.impl.tweaks

import me.eternal.purrfect.core.features.Feature
import me.eternal.purrfect.core.util.dataBuilder
import me.eternal.purrfect.core.util.hook.HookStage
import me.eternal.purrfect.core.util.hook.hookConstructor

class RemoveGroupsLockedStatus : Feature("Remove Groups Locked Status") {
    override fun init() {
        if (!context.config.messaging.removeGroupsLockedStatus.get()) return
        onNextActivityCreate(defer = true) {
            context.classCache.conversation.hookConstructor(HookStage.AFTER) { param ->
                param.thisObject<Any>().dataBuilder {
                    set("mLockedState", "UNLOCKED")
                }
            }
        }
    }
}