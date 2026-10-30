package me.eternal.purrfect.core.features.impl

import me.eternal.purrfect.core.features.Feature
import me.eternal.purrfect.core.util.hook.HookStage
import me.eternal.purrfect.core.util.hook.hookConstructor
import me.eternal.purrfect.core.util.hook.hook
import me.eternal.purrfect.core.wrapper.impl.media.opera.ParamMap
import me.eternal.purrfect.mapper.impl.OperaViewerParamsMapper
import java.util.concurrent.ConcurrentHashMap

class OperaViewerParamsOverride : Feature("OperaViewerParamsOverride") {
    var currentPlaybackRate = 1.0F

    data class OverrideKey(
        val name: String,
        val defaultValue: Any?
    )

    data class Override(
        val filter: (value: Any?) -> Boolean,
        val value: (key: OverrideKey, value: Any?) -> Any?
    )

    override fun init() {
        val overrideMap = mutableMapOf<String, Override>()

        fun overrideParam(key: String, filter: (value: Any?) -> Boolean, value: (overrideKey: OverrideKey, value: Any?) -> Any?) {
            overrideMap[key] = Override(filter, value)
        }

        currentPlaybackRate = context.config.global.defaultVideoPlaybackRate.getNullable()?.takeIf { it > 0 } ?: 1.0F

        val isSliderEnabled = context.config.global.videoPlaybackRateSlider.get()
        if (isSliderEnabled || currentPlaybackRate != 1.0F) {
            overrideParam(
                "video_playback_rate", 
                { isSliderEnabled || currentPlaybackRate != 1.0F }, 
                { _, _ -> currentPlaybackRate.toDouble() }
            )
        }

        if (context.config.messaging.loopMediaPlayback.get()) {
            //https://github.com/rodit/SnapMod/blob/master/app/src/main/java/xyz/rodit/snapmod/features/opera/SnapDurationModifier.kt
            overrideParam("auto_advance_mode", { true }, { _, value ->
                val advanceMode = value ?: return@overrideParam null
                advanceMode::class.java.enumConstants?.firstOrNull {
                    it.toString() == "NO_AUTO_ADVANCE"
                } ?: value
            })
            overrideParam("auto_advance_max_loop_number", { true }, { _, _ -> Int.MAX_VALUE })
            overrideParam("media_playback_mode", { true }, { _, value ->
                val playbackMode = value ?: return@overrideParam null
                playbackMode::class.java.enumConstants?.firstOrNull {
                    it.toString() == "LOOPING"
                } ?: return@overrideParam value
            })
        }

        onNextActivityCreate {
            context.mappings.useMapper(OperaViewerParamsMapper::class) {
                fun overrideParamResult(paramKey: Any, value: Any?): Any? {
                    val fields = paramKey::class.java.declaredFields
                    fields.forEach { runCatching { it.isAccessible = true } }
                    val key = OverrideKey(
                        name = fields.firstOrNull {
                            it.type == String::class.java
                        }?.get(paramKey)?.toString() ?: return value,
                        defaultValue = fields.firstOrNull {
                            it.type == Object::class.java
                        }?.get(paramKey)
                    )

                    overrideMap[key.name]?.let { override ->
                        if (override.filter(value)) {
                            runCatching {
                                return override.value(key, value)
                            }.onFailure {
                                context.log.error("Failed to override param $key", it)
                            }
                        }
                    }

                    return value
                }

                if (overrideMap.isEmpty()) return@useMapper

                val targetClass = classReference.get() ?: return@useMapper
                val getMethod = targetClass.methods.firstOrNull { 
                    it.returnType == Any::class.java && it.parameterTypes.size == 1 
                }
                
                if (getMethod != null) {
                    targetClass.hook(getMethod.name, HookStage.AFTER) { param ->
                        val originalValue = param.getResult()
                        val overriddenValue = overrideParamResult(param.arg(0), originalValue)
                        if (overriddenValue != originalValue) {
                            param.setResult(overriddenValue)
                        }
                    }
                }
                
                val putMethod = targetClass.methods.firstOrNull { 
                    it.parameterTypes.size == 2 && it.parameterTypes[0] != Int::class.javaPrimitiveType
                }
                
                if (putMethod != null) {
                    targetClass.hook(putMethod.name, HookStage.BEFORE) { param ->
                        val key = param.arg<Any>(0)
                        val value = param.argNullable<Any>(1)
                        val overriddenValue = overrideParamResult(key, value)
                        if (overriddenValue != value) {
                            param.setArg(1, overriddenValue)
                        }
                    }
                }
            }
        }
    }
}
