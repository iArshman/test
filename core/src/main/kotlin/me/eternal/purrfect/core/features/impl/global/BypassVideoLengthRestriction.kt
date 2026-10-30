package me.eternal.purrfect.core.features.impl.global

import android.os.Build
import android.os.FileObserver
import com.google.gson.JsonParser
import me.eternal.purrfect.core.event.events.impl.SendMessageWithContentEvent
import me.eternal.purrfect.core.features.Feature
import me.eternal.purrfect.core.util.dataBuilder
import me.eternal.purrfect.core.util.hook.HookStage
import me.eternal.purrfect.core.util.hook.hookConstructor
import me.eternal.purrfect.core.util.ktx.setObjectField
import me.eternal.purrfect.mapper.impl.DefaultMediaItemMapper
import java.io.File

class BypassVideoLengthRestriction :
    Feature("BypassVideoLengthRestriction") {
    private lateinit var fileObserver: FileObserver

    override fun init() {
        onNextActivityCreate(defer = true) {
            val mode = context.config.global.bypassVideoLengthRestriction.getNullable()

            if (mode == "single") {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val postedStorySnapFolder =
                        File(context.androidContext.filesDir, "file_manager/posted_story_snap")

                    fileObserver = (object : FileObserver(postedStorySnapFolder, MOVED_TO) {
                        override fun onEvent(event: Int, path: String?) {
                            if (event != MOVED_TO || path?.contains("posted_story_snap.") != true) return

                            runCatching {
                                val file = File(postedStorySnapFolder, path)
                                file.bufferedReader().use { bufferedReader ->
                                    bufferedReader.mark(1)
                                    if (bufferedReader.read() != 123) {
                                        context.log.verbose("Ignoring non-JSON file: $path")
                                        return@use
                                    }
                                    bufferedReader.reset()
                                    val fileContent = JsonParser.parseReader(bufferedReader).asJsonObject
                                    if ((fileContent["timerOrDuration"]?.also {
                                            fileObserver.stopWatching()
                                    }?.takeIf { !it.isJsonNull }?.asLong ?: 1) <= 0) {
                                        context.log.verbose("Deleting $path")
                                        file.delete()
                                    }
                                }

                            }.onFailure {
                                context.log.error("Failed to read story metadata file", it)
                            }
                        }
                    })

                    context.event.subscribe(SendMessageWithContentEvent::class) { event ->
                        if (event.destinations.stories!!.isEmpty()) return@subscribe
                        fileObserver.startWatching()
                    }
                }

                context.mappings.useMapper(DefaultMediaItemMapper::class) {
                    listOf(
                        defaultMediaItemClass to defaultMediaItemDurationMsField,
                        defaultMediaItemClass2 to defaultMediaItemDurationMsField2
                    ).forEach { (classRef, fieldRef) ->
                        classRef.getAsClass()?.hookConstructor(HookStage.AFTER) { param ->
                            param.thisObject<Any>().dataBuilder {
                                set(fieldRef.getAsString()!!, 10000L)
                            }
                        }
                    }
                }
            }

            arrayOf(
                "com.snap.composer.memories.MemoriesPickerVideoDurationConfig",
                "com.snap.modules.memories_v2.MemoriesTwoPickerVideoDurationConfig"
            ).forEach { className ->
                runCatching {
                    findClass(className).hookConstructor(HookStage.AFTER) { param ->
                        param.thisObject<Any>().apply {
                            setObjectField("_maxSingleItemDurationMs", null)
                            setObjectField("_maxTotalDurationMs", null)
                            setObjectField("_maxSnapLengthMs", null)
                        }
                    }
                }
            }

            if (mode == "split") {
                context.mappings.useMapper(DefaultMediaItemMapper::class) {
                    cameraRollMediaId.getAsClass()?.hookConstructor(HookStage.AFTER) { param ->
                        param.thisObject<Any>()
                            .setObjectField(durationMsField.get()!!, -1L)
                    }
                }
            }
        }
    }
}
