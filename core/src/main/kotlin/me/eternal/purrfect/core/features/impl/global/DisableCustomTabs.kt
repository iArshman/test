package me.eternal.purrfect.core.features.impl.global

import android.content.Intent
import me.eternal.purrfect.core.features.Feature
import me.eternal.purrfect.core.util.hook.HookStage
import me.eternal.purrfect.core.util.hook.hook

class DisableCustomTabs: Feature("Disable Custom Tabs") {
    override fun init() {
        if (!context.config.global.disableCustomTabs.get()) return
        onNextActivityCreate { activity ->
            activity.packageManager.javaClass.hook("resolveService", HookStage.BEFORE) { param ->
                val intent = param.arg<Intent>(0)
                if (intent.action == "android.support.customtabs.action.CustomTabsService") {
                    param.setResult(null)
                }
            }
        }
    }
}