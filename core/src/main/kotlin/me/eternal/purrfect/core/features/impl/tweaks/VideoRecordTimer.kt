package me.eternal.purrfect.core.features.impl.tweaks

import android.media.AudioRecord
import android.media.MediaCodec
import me.eternal.purrfect.core.features.Feature
import me.eternal.purrfect.core.util.hook.HookStage
import me.eternal.purrfect.core.util.hook.hook

class VideoRecordTimer : Feature("Video Record Timer") {
    override fun init() {
        if (!context.config.camera.videoRecordTimer.get()) return

        val activeComponents = mutableSetOf<Int>()

        fun startRecording(componentHashCode: Int) {
            synchronized(activeComponents) {
                if (activeComponents.isEmpty()) {
                    context.inAppOverlay.videoRecordTimerState.isRecording = true
                    context.inAppOverlay.videoRecordTimerState.recordingStartTime = System.currentTimeMillis() - 1000
                }
                activeComponents.add(componentHashCode)
            }
        }

        fun stopRecording(componentHashCode: Int) {
            synchronized(activeComponents) {
                activeComponents.remove(componentHashCode)
                if (activeComponents.isEmpty()) {
                    context.inAppOverlay.videoRecordTimerState.isRecording = false
                }
            }
        }

        AudioRecord::class.java.hook("startRecording", HookStage.AFTER) {
            startRecording(it.thisObject<AudioRecord>().hashCode())
        }

        AudioRecord::class.java.hook("stop", HookStage.BEFORE) {
            stopRecording(it.thisObject<AudioRecord>().hashCode())
        }

        AudioRecord::class.java.hook("release", HookStage.BEFORE) {
            stopRecording(it.thisObject<AudioRecord>().hashCode())
        }

        MediaCodec::class.java.hook("start", HookStage.AFTER) {
            val codecName = runCatching { it.thisObject<MediaCodec>().name }.getOrNull() ?: ""
            if (codecName.contains("encoder", ignoreCase = true) && codecName.contains("video", ignoreCase = true)) {
                startRecording(it.thisObject<MediaCodec>().hashCode())
            }
        }

        MediaCodec::class.java.hook("stop", HookStage.BEFORE) {
            val codecName = runCatching { it.thisObject<MediaCodec>().name }.getOrNull() ?: ""
            if (codecName.contains("encoder", ignoreCase = true) && codecName.contains("video", ignoreCase = true)) {
                stopRecording(it.thisObject<MediaCodec>().hashCode())
            }
        }

        MediaCodec::class.java.hook("release", HookStage.BEFORE) {
            val codecName = runCatching { it.thisObject<MediaCodec>().name }.getOrNull() ?: ""
            if (codecName.contains("encoder", ignoreCase = true) && codecName.contains("video", ignoreCase = true)) {
                stopRecording(it.thisObject<MediaCodec>().hashCode())
            }
        }
    }
}
