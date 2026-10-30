package me.eternal.purrfect.core.features.impl.global

import android.content.ContextWrapper
import me.eternal.purrfect.core.features.Feature
import me.eternal.purrfect.core.util.hook.HookStage
import me.eternal.purrfect.core.util.hook.hook

class DisableTelecomFramework: Feature("Disable Telecom Framework") {
    override fun init() {
        if (!context.config.global.disableTelecomFramework.get() && !context.config.messaging.blockCalls.get()) return

        ContextWrapper::class.java.hook("getSystemService", HookStage.BEFORE) { param ->
            if (param.arg<Any>(0).toString() == "telecom") param.setResult(null)
        }
    }
}
