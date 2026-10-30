package me.eternal.purrfect.core.features.impl.tweaks

import me.eternal.purrfect.core.features.Feature
import me.eternal.purrfect.core.util.dataBuilder
import me.eternal.purrfect.core.util.hook.HookStage
import me.eternal.purrfect.core.util.hook.hookConstructor

class DisableSnapModeRestrictions: Feature("Disable Snap Mode Restrictions") {
    override fun init() {
        if (!context.config.messaging.disableSnapModeRestrictions.get()) return

        findClass("com.snapchat.client.messaging.SnapModeInfo").hookConstructor(HookStage.AFTER) { param ->
            param.thisObject<Any>().dataBuilder {
                set("mSelfDestructSnapDurationMs", null)
            }
        }
    }
}