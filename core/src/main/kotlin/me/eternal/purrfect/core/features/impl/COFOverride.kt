package me.eternal.purrfect.core.features.impl

import me.eternal.purrfect.core.features.Feature

import me.eternal.purrfect.core.util.dataBuilder
import me.eternal.purrfect.core.util.hook.HookStage
import me.eternal.purrfect.core.util.hook.hook
import me.eternal.purrfect.core.util.ktx.getObjectField
import me.eternal.purrfect.mapper.impl.COFObservableMapper
import java.lang.reflect.Method

class COFOverride : Feature("COF Override") {
    var hasActionMenuV2 = false

    override fun init() {
        val cofExperiments by context.config.experimental.cofExperiments

        context.mappings.useMapper(COFObservableMapper::class) {
            classReference.getAsClass()?.hook(getBooleanObservable.get() ?: return@useMapper, HookStage.AFTER) { param ->
                val configId = param.arg<String>(0)
                val result by lazy { param.getResult()?.getObjectField("b") }

                fun setBooleanResult(state: Boolean) {
                    param.setResult((param.method() as Method).returnType.dataBuilder {
                        set("a", 4)
                        set("b", state)
                    })
                }

                if (cofExperiments.contains(configId.lowercase())) {
                    setBooleanResult(true)
                }

                if ((configId == "ANDROID_ACTION_MENU_V2" || configId == "ANDROID_ACTION_MENU_ADJUST_MESSAGE_POSITION") && result == true) {
                    hasActionMenuV2 = true
                }
            }
        }
    }
}