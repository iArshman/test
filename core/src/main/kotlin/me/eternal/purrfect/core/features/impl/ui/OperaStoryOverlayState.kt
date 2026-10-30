package me.eternal.purrfect.core.features.impl.ui

import me.eternal.purrfect.core.ModContext
import me.eternal.purrfect.core.util.ktx.getObjectField
import me.eternal.purrfect.core.wrapper.impl.media.opera.Layer
import me.eternal.purrfect.core.wrapper.impl.media.opera.ParamMap
import me.eternal.purrfect.mapper.impl.OperaPageViewControllerMapper
import me.eternal.purrfect.core.util.hook.HookStage
import me.eternal.purrfect.core.util.hook.hook
import java.util.ArrayList
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
class OperaStoryOverlayState {
    val counterState = mutableStateOf("")
    val sourceState = mutableStateOf("")
    val currentIndexState = mutableIntStateOf(-1)
    val totalCountState = mutableIntStateOf(0)
    val snapSourceState = mutableStateOf<String?>(null)
    val playlistV2GroupState = mutableStateOf<String?>(null)
    val isInConversationState = mutableStateOf(false)
    val isInSpotlightContext = mutableStateOf(false)
    val captionTextState = mutableStateOf("")
    val mapStoryOverlayEligible = mutableStateOf(false)

    fun clearState() {
        counterState.value = ""
        sourceState.value = ""
        currentIndexState.intValue = -1
        totalCountState.intValue = 0
        snapSourceState.value = null
        playlistV2GroupState.value = null
        isInConversationState.value = false
        isInSpotlightContext.value = false
        captionTextState.value = ""
        mapStoryOverlayEligible.value = false
    }

    fun setupDisplayStateHook(
        context: ModContext,
        showCounter: Boolean,
        showSourceIndicator: Boolean,
        onSnapFullyDisplayed: ((Int) -> Unit)?,
        onClearState: (() -> Unit)? = null
    ) {
        context.mappings.useMapper(OperaPageViewControllerMapper::class) {
            arrayOf(onDisplayStateChange, onDisplayStateChangeGesture).forEach { methodName ->
                classReference.get()?.hook(
                    methodName.get() ?: return@forEach,
                    HookStage.AFTER
                ) { param ->
                    val viewState = (param.thisObject() as Any).getObjectField(viewStateField.get()!!).toString()

                    if (viewState != "FULLY_DISPLAYED") {
                        return@hook
                    }

                    val operaLayerList = (param.thisObject() as Any).getObjectField(layerListField.get()!!) as ArrayList<*>

                    if (operaLayerList.isEmpty()) {
                        context.runOnUiThread {
                            clearState()
                            onClearState?.invoke()
                        }
                        return@hook
                    }

                    val mediaParamMap: ParamMap = operaLayerList.map { Layer(it) }.first().paramMap
                    val snapSource = mediaParamMap["SNAP_SOURCE"]?.toString()

                    if (mediaParamMap.containsKey("MESSAGE_ID") || snapSource == "SINGLE_SNAP_STORY" || snapSource == "SPOTLIGHT" || snapSource == "PUBLIC_STORY") {
                        val publicSnapSource = if (snapSource == "PUBLIC_STORY" && showSourceIndicator) {
                            mediaOriginFromCapturedOnSnapCamera(mediaParamMap)
                        } else ""
                        context.runOnUiThread {
                            counterState.value = ""
                            sourceState.value = publicSnapSource
                            currentIndexState.intValue = -1
                            totalCountState.intValue = 0
                            snapSourceState.value = snapSource
                            playlistV2GroupState.value = null
                            mapStoryOverlayEligible.value = false
                            isInConversationState.value = mediaParamMap.containsKey("MESSAGE_ID")
                            captionTextState.value = ""
                            isInSpotlightContext.value = snapSource == "SINGLE_SNAP_STORY" || snapSource == "SPOTLIGHT"
                            onClearState?.invoke()
                        }
                        return@hook
                    }

                    val currentIndex = mediaParamMap["snap_index_in_story"]?.toString()?.toIntOrNull()
                        ?: mediaParamMap["SNAP_POSITION_IN_STORY"]?.toString()?.toIntOrNull()
                    val totalCount = mediaParamMap["snap_story_length"]?.toString()?.toIntOrNull()
                        ?: mediaParamMap["NUM_SNAPS_IN_STORY"]?.toString()?.toIntOrNull()

                    var mediaOrigin = ""
                    if (showSourceIndicator) {
                        val snapRecord = mediaParamMap["PLAYABLE_STORY_SNAP_RECORD"]?.toString() ?: ""
                        mediaOrigin = if (snapRecord.contains("mediaOrigins=")) {
                            if (snapRecord.contains("mediaOrigins=[CAMERA]")) "CAMERA" else "GALLERY"
                        } else ""
                        if (mediaOrigin.isEmpty()) {
                            val playlistGroup = mediaParamMap["PLAYLIST_V2_GROUP"]?.toString() ?: ""
                            val isPublicUserStory = playlistGroup.contains("PublicUserStory", ignoreCase = true) ||
                                snapSource == "PUBLIC_USER" ||
                                (mediaParamMap["DYNAMIC_SNAP_SOURCE"]?.toString() == "PUBLIC_USER")
                            if (isPublicUserStory) {
                                mediaOrigin = mediaOriginFromCapturedOnSnapCamera(mediaParamMap)
                            }
                        }
                    }

                    val playlist = mediaParamMap["PLAYLIST_V2_GROUP"]?.toString()
                    val mapStoryEligible = mediaParamMap.containsKey("MAP_STORY_ID") ||
                        playlist?.contains("MapFriendStory", ignoreCase = true) == true ||
                        playlist?.contains("MAP_USER_STORY", ignoreCase = true) == true ||
                        playlist?.contains("LocationStory", ignoreCase = true) == true

                    var captionText = ""
                    val snapRecord = mediaParamMap["PLAYABLE_STORY_SNAP_RECORD"]?.toString() ?: ""
                    if (snapRecord.contains("captionTextDisplay=")) {
                        val raw = snapRecord.substringAfter("captionTextDisplay=").substringBefore(",")
                        if (raw != "null" && raw.isNotBlank()) {
                            captionText = raw.trim()
                        }
                    }

                    context.runOnUiThread {
                        counterState.value = if (showCounter && currentIndex != null && totalCount != null && totalCount > 0) {
                            "${currentIndex + 1} / $totalCount"
                        } else ""
                        sourceState.value = mediaOrigin
                        currentIndexState.intValue = currentIndex ?: -1
                        totalCountState.intValue = totalCount ?: 0
                        snapSourceState.value = snapSource
                        playlistV2GroupState.value = playlist
                        isInConversationState.value = false
                        captionTextState.value = captionText
                        mapStoryOverlayEligible.value = mapStoryEligible
                        isInSpotlightContext.value = false

                        onSnapFullyDisplayed?.let { callback ->
                            if (currentIndex != null) callback(currentIndex)
                        }
                    }
                }
            }
        }
    }
}

private fun mediaOriginFromCapturedOnSnapCamera(mediaParamMap: ParamMap): String {
    if (!mediaParamMap.containsKey("IS_CAPTURED_ON_SNAP_CAMERA")) return ""
    return when (val v = mediaParamMap["IS_CAPTURED_ON_SNAP_CAMERA"]) {
        is Boolean -> if (v) "CAMERA" else "GALLERY"
        else -> {
            when (v.toString().lowercase()) {
                "true" -> "CAMERA"
                "false" -> "GALLERY"
                else -> ""
            }
        }
    }
}
