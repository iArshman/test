package me.eternal.purrfect.core.features.impl.global

import me.eternal.purrfect.core.features.Feature
import me.eternal.purrfect.core.util.hook.HookStage
import me.eternal.purrfect.core.util.hook.hook
import me.eternal.purrfect.mapper.impl.MemoriesPresenterMapper

class DisableMemoriesSnapFeed : Feature("Disable Memories Snap Feed") {
    override fun init() {
        if (!context.config.global.disableMemoriesSnapFeed.get()) return
        onNextActivityCreate {
            context.mappings.useMapper(MemoriesPresenterMapper::class) {
                classReference.get()?.apply {
                    val getNameMethod = getMethod("getName") ?: return@apply

                    hook(onNavigationEventMethod.get()!!, HookStage.BEFORE) { param ->
                        val instance = param.thisObject<Any>()

                        if (getNameMethod.invoke(instance) == "MemoriesAsyncPresenterFragmentSubscriber") {
                            param.setResult(null)
                        }
                    }
                }
            }
        }
    }
}