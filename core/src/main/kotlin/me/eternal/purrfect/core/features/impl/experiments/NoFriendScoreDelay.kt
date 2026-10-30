package me.eternal.purrfect.core.features.impl.experiments

import me.eternal.purrfect.core.features.Feature
import me.eternal.purrfect.core.util.hook.HookStage
import me.eternal.purrfect.core.util.hook.hookConstructor
import me.eternal.purrfect.mapper.impl.ScoreUpdateMapper
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

class NoFriendScoreDelay : Feature("NoFriendScoreDelay") {
    override fun init() {
        if (!context.config.experimental.noFriendScoreDelay.get()) return

        onNextActivityCreate {
            context.mappings.useMapper(ScoreUpdateMapper::class) {
                classReference.get()?.hookConstructor(HookStage.BEFORE) { param ->
                    param.args().indexOfFirst {
                        val longValue = it.toString().toLongOrNull() ?: return@indexOfFirst false
                        longValue > 30.minutes.inWholeMilliseconds && longValue < 10.days.inWholeMilliseconds
                    }.takeIf { it != -1 }?.let { index ->
                        param.setArg(index, 0)
                    }
                }
            }
        }
    }
}