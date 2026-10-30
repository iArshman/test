package me.eternal.purrfect.core.ui.menu.impl

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SlowMotionVideo
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.res.use
import me.eternal.purrfect.common.ui.createComposeView
import me.eternal.purrfect.core.event.events.impl.AddViewEvent
import me.eternal.purrfect.core.features.impl.OperaViewerParamsOverride
import me.eternal.purrfect.core.features.impl.downloader.MediaDownloader
import me.eternal.purrfect.core.ui.children
import me.eternal.purrfect.core.ui.menu.AbstractMenu
import me.eternal.purrfect.core.ui.triggerCloseTouchEvent
import me.eternal.purrfect.core.util.ktx.getIdentifier
import me.eternal.purrfect.core.util.ktx.vibrateLongPress
import me.eternal.purrfect.core.wrapper.impl.ScSize
import java.text.DateFormat
import java.util.Date

@SuppressLint("DiscouragedApi")
class OperaContextActionMenu : AbstractMenu() {
    private fun isViewGroupButtonMenuContainer(viewGroup: ViewGroup): Boolean {
        if (viewGroup !is LinearLayout) return false
        val children = viewGroup.children()
        return if (children.any { view: View? -> view !is LinearLayout })
            false
        else children.map { view: View -> view as LinearLayout }
            .any { linearLayout: LinearLayout ->
                linearLayout.children().any { viewChild: View ->
                    viewChild.javaClass.name.endsWith("SnapFontTextView")
                }
            }
    }

    @Composable
    private fun MetadataCapsule(
        title: String,
        value: String,
        modifier: Modifier = Modifier,
        skin: me.eternal.purrfect.common.ui.theme.PurrfectColorSet
    ) {
        Surface(
            modifier = modifier.height(52.dp),
            shape = RoundedCornerShape(12.dp),
            color = skin.cardOverlayColor.copy(alpha = 0.94f),
            border = BorderStroke(1.dp, skin.textPrimary.copy(alpha = 0.12f))
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = title,
                    fontSize = 7.sp,
                    fontWeight = FontWeight.Black,
                    color = skin.glowPrimary,
                    letterSpacing = 0.5.sp
                )
                Text(
                    text = value,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = skin.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }

    override fun onViewAdded(event: AddViewEvent) {
        val parentView = event.parent.parent as? ScrollView ?: return
        val view = event.view
        if (view !is LinearLayout) return
        if (!isViewGroupButtonMenuContainer(view as ViewGroup)) return

        val linearLayout = LinearLayout(view.context)
        linearLayout.orientation = LinearLayout.VERTICAL
        linearLayout.gravity = Gravity.CENTER
        linearLayout.layoutParams =
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        val translation = context.translation.getCategory("opera_context_menu")
        val mediaDownloader = context.feature(MediaDownloader::class)
        val paramMap = mediaDownloader.lastSeenMapParams

        linearLayout.addView(createComposeView(view.context) {
            me.eternal.purrfect.core.ui.PurrfectOverlayTheme(null) {
                val skin = me.eternal.purrfect.common.ui.theme.LocalPurrfectSkin.current

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Metadata Ribbon (4-Pillar Dashboard)
                    if (paramMap != null && context.config.userInterface.operaMediaQuickInfo.get()) {
                        val playableStorySnapRecord = paramMap["PLAYABLE_STORY_SNAP_RECORD"]?.toString()
                        val sentTimestamp = playableStorySnapRecord?.substringAfter("timestamp=")
                            ?.substringBefore(",")?.toLongOrNull()
                            ?: mediaDownloader.resolveCurrentSnapMessageContext()?.clientMessageId?.let { messageId ->
                                context.database.getConversationMessageFromId(messageId)?.creationTimestamp
                            }
                            ?: paramMap["SNAP_TIMESTAMP"]?.toString()?.toLongOrNull()

                        val mediaSize = paramMap["snap_size"]?.let { ScSize(it) }
                        val durationMs = paramMap["media_duration_ms"]?.toString()

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            MetadataCapsule(
                                title = "DATE",
                                value = sentTimestamp?.let { DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it)) } ?: "Unknown",
                                modifier = Modifier.weight(1f),
                                skin = skin
                            )
                            MetadataCapsule(
                                title = "TIME",
                                value = sentTimestamp?.let { DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it)) } ?: "Unknown",
                                modifier = Modifier.weight(1f),
                                skin = skin
                            )
                            MetadataCapsule(
                                title = "SIZE",
                                value = mediaSize?.let { "${it.first}x${it.second}" } ?: "Unknown",
                                modifier = Modifier.weight(1f),
                                skin = skin
                            )
                            MetadataCapsule(
                                title = "TIMER",
                                value = durationMs?.let { "${it}ms" } ?: "Static",
                                modifier = Modifier.weight(1f),
                                skin = skin
                            )
                        }
                    }

                    // Professional Step-Slider
                    if (context.config.global.videoPlaybackRateSlider.get()) {
                        val operaViewerParamsOverride = context.feature(OperaViewerParamsOverride::class)
                        var sliderValue by remember { mutableFloatStateOf(operaViewerParamsOverride.currentPlaybackRate) }

                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            color = skin.cardOverlayColor.copy(alpha = 0.94f),
                            border = BorderStroke(1.dp, skin.textPrimary.copy(alpha = 0.12f))
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(38.dp)
                                            .background(skin.glowPrimary.copy(alpha = 0.15f), CircleShape)
                                            .border(1.dp, skin.glowPrimary.copy(alpha = 0.25f), CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.SlowMotionVideo,
                                            contentDescription = null,
                                            tint = skin.glowPrimary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Playback Rate",
                                            color = skin.textPrimary,
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.ExtraBold
                                        )
                                        Text(
                                            text = "Speed: ${String.format("%.2fx", sliderValue)}",
                                            color = skin.textSecondary,
                                            fontSize = 12.sp
                                        )
                                    }
                                }

                                Slider(
                                    value = sliderValue,
                                    onValueChange = { newValue ->
                                        if (newValue != sliderValue) {
                                            view.context.vibrateLongPress()
                                            sliderValue = newValue
                                            operaViewerParamsOverride.currentPlaybackRate = newValue
                                        }
                                    },
                                    valueRange = 0.25f..4.0f,
                                    steps = 14,
                                    colors = SliderDefaults.colors(
                                        thumbColor = skin.glowPrimary,
                                        activeTrackColor = skin.glowPrimary,
                                        inactiveTrackColor = skin.textPrimary.copy(alpha = 0.12f)
                                    )
                                )
                            }
                        }
                    }
                }
            }
        })
        
        val creatorInfoFeature = context.feature(me.eternal.purrfect.core.features.impl.ui.SpotlightCreatorInfo::class)
        if (paramMap?.get("MEDIA_TYPE")?.toString() == "SPOTLIGHT" && context.config.global.spotlightCreatorInfo.get()) {
            linearLayout.addView(Button(view.context).apply {
                text = translation["spotlight_creator_info.title"] ?: "Creator Info"
                setOnClickListener {
                    creatorInfoFeature.showDialog()
                    parentView.triggerCloseTouchEvent()
                }
                this@OperaContextActionMenu.context.userInterface.applyActionButtonTheme(this)
            })
        }

        if (context.config.downloader.downloadContextMenu.get()) {
            linearLayout.addView(Button(view.context).apply {
                text = translation["download"]
                setOnClickListener {
                    mediaDownloader.downloadLastOperaMediaAsync(allowDuplicate = false)
                    parentView.triggerCloseTouchEvent()
                }
                setOnLongClickListener {
                    context.vibrateLongPress()
                    mediaDownloader.downloadLastOperaMediaAsync(allowDuplicate = true)
                    parentView.triggerCloseTouchEvent()
                    true
                }
                this@OperaContextActionMenu.context.userInterface.applyActionButtonTheme(this)
            })
        }

        if (context.isDeveloper) {
            linearLayout.addView(Button(view.context).apply {
                text = translation["show_debug_info"]
                setOnClickListener { mediaDownloader.showLastOperaDebugMediaInfo() }
                this@OperaContextActionMenu.context.userInterface.applyActionButtonTheme(this)
            })
        }

        (view as? ViewGroup)?.addView(linearLayout, 0)
    }
}
