package me.eternal.purrfect.core.features.impl.tweaks

import android.media.AudioManager
import me.eternal.purrfect.core.features.Feature
import me.eternal.purrfect.core.util.hook.HookStage
import me.eternal.purrfect.core.util.hook.hook

class HideActiveMusic: Feature("Hide Active Music") {
    override fun init() {
        if (!context.config.global.hideActiveMusic.get()) return
        onNextActivityCreate {
            AudioManager::class.java.hook("isMusicActive", HookStage.BEFORE) {
                it.setResult(false)
            }
        }
    }
}