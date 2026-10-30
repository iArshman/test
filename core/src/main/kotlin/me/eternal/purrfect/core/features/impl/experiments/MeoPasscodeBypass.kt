package me.eternal.purrfect.core.features.impl.experiments

import me.eternal.purrfect.core.features.Feature
import me.eternal.purrfect.core.util.hook.HookStage
import me.eternal.purrfect.core.util.hook.hook
import me.eternal.purrfect.mapper.impl.BCryptClassMapper

class MeoPasscodeBypass : Feature("Meo Passcode Bypass") {
    override fun init() {
        if (!context.config.experimental.meoPasscodeBypass.get()) return

        onNextActivityCreate(defer = true) {
            context.mappings.useMapper(BCryptClassMapper::class) {
                classReference.get()?.hook(
                    hashMethod.get()!!,
                    HookStage.BEFORE,
                ) { param ->
                    //set the hash to the result of the method
                    param.setResult(param.arg(1))
                }
            }
        }
    }
}