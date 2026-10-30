package me.eternal.purrfect.core.features.impl.ui

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.eternal.purrfect.common.data.ContentType
import me.eternal.purrfect.common.ui.createComposeView
import me.eternal.purrfect.common.ui.rememberAsyncMutableState
import me.eternal.purrfect.common.util.protobuf.ProtoReader
import me.eternal.purrfect.core.event.events.impl.BindViewEvent
import me.eternal.purrfect.core.features.Feature
import me.eternal.purrfect.core.ui.AppleLogo
import me.eternal.purrfect.core.util.ktx.vibrateLongPress
import kotlin.random.Random

@Composable
private fun GradientIcon(
    imageVector: ImageVector,
    brush: Brush,
    size: androidx.compose.ui.unit.Dp = 14.dp
) {
    Image(
        imageVector = imageVector,
        contentDescription = null,
        colorFilter = ColorFilter.tint(Color.White),
        modifier = Modifier
            .size(size)
            .graphicsLayer(alpha = 0.99f)
            .drawWithContent {
                drawContent()
                drawRect(brush = brush, blendMode = BlendMode.SrcAtop)
            }
    )
}

@Composable
private fun IndicatorDivider() {
    Spacer(
        modifier = Modifier
            .width(1.dp)
            .height(10.dp)
            .background(Color.White.copy(alpha = 0.15f))
    )
}

@Composable
private fun GradientText(
    text: String,
    brush: Brush,
    fontWeight: FontWeight,
    fontSize: androidx.compose.ui.unit.TextUnit
) {
    Text(
        text = text,
        color = Color.White,
        fontWeight = fontWeight,
        fontSize = fontSize,
        modifier = Modifier
            .graphicsLayer(alpha = 0.99f)
            .drawWithContent {
                drawContent()
                drawRect(brush = brush, blendMode = BlendMode.SrcAtop)
            }
    )
}

class MessageIndicators : Feature("Message Indicators") {
    override fun init() {
        val messageIndicatorsConfig = context.config.userInterface.messageIndicators.getNullable() ?: return
        if (messageIndicatorsConfig.isEmpty()) return

        val messageInfoTag = Random.nextLong().toString()
        onNextActivityCreate {
            val appleLogo = AppleLogo

            context.event.subscribe(BindViewEvent::class) { event ->
                event.chatMessage { conversationId, _ ->
                    val view = event.view as? ViewGroup ?: return@subscribe
                    view.findViewWithTag<View>(messageInfoTag)?.let { view.removeView(it) }

                    val message = event.databaseMessage ?: return@chatMessage
                    if (message.contentType != ContentType.SNAP.id && message.contentType != ContentType.EXTERNAL_MEDIA.id) return@chatMessage
                    if (message.senderId == context.database.myUserId && messageIndicatorsConfig.contains("skip_own_indicators")) return@chatMessage
                    val reader = ProtoReader(message.messageContent ?: return@chatMessage)
                    val isGroupConversation = (context.database.getConversationParticipants(conversationId)?.size ?: 0) > 2
                    if (isGroupConversation && messageIndicatorsConfig.contains("disable_indicators_in_groups")) return@chatMessage

                    createComposeView(event.view.context) {
                        val lockBrush = Brush.linearGradient(listOf(Color(0xFF4CD471), Color(0xFF00B8D9)))
                        val locationBrush = Brush.linearGradient(listOf(Color(0xFFFF4B5C), Color(0xFFFFB74D)))
                        val androidBrush = Brush.linearGradient(listOf(Color(0xFF3DDC84), Color(0xFFA5E635)))
                        val appleBrush = Brush.linearGradient(listOf(Color(0xFFFFFFFF), Color(0xFF94A3B8)))
                        val webBrush = Brush.linearGradient(listOf(Color(0xFF4FC3F7), Color(0xFF00E5FF)))
                        val directorBrush = Brush.linearGradient(listOf(Color(0xFFFFB74D), Color(0xFFFF5E3A)))
                        val ovfBrush = Brush.linearGradient(listOf(Color(0xFFFF5CA8), Color(0xFFA64CFF)))
                        val memoriesBrush = Brush.linearGradient(listOf(Color(0xFFFFE066), Color(0xFFFFB300)))

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                                .padding(top = 8.dp, end = 10.dp),
                            contentAlignment = Alignment.TopEnd
                        ) {
                            val hasEncryption by rememberAsyncMutableState(defaultValue = false) {
                                if (reader.containsPath(4, 4, 1, 1)
                                    || reader.containsPath(4, 4, 1, 1, 1)
                                    || reader.getByteArray(4, 3, 3) != null
                                    || reader.containsPath(3, 99, 3)) {
                                    return@rememberAsyncMutableState true
                                }
                                if (reader.containsPath(4, 5, 1, 3, 1)) return@rememberAsyncMutableState true
                                reader.getVarInt(4, 5, 1, 3, 2, 9) in setOf(1L, 3L)
                            }
                            val sentFromIosDevice by rememberAsyncMutableState(defaultValue = false) {
                                if (reader.containsPath(4, 4, 3)) !reader.containsPath(4, 4, 3, 3, 17) else reader.getVarInt(4, 4, 11, 17, 7) != null
                            }
                            val sentFromWebApp by rememberAsyncMutableState(defaultValue = false) {
                                reader.getVarInt(4, 4, *(if (reader.containsPath(4, 4, 3)) intArrayOf(3, 3, 22, 1) else intArrayOf(11, 22, 1))) == 7L
                            }
                            val sentWithLocation by rememberAsyncMutableState(defaultValue = false) {
                                reader.getVarInt(4, 4, 11, 17, 5) != null
                            }
                            val sentUsingOvfEditor by rememberAsyncMutableState(defaultValue = false) {
                                (reader.getString(4, 4, 11, 12, 1) ?: reader.getString(4, 4, 11, 13, 4, 1, 2, 12, 20, 1)) == "c13129f7-fe4a-44c4-9b9d-e0b26fee8f82"
                            }
                            val sentUsingDirectorMode by rememberAsyncMutableState(defaultValue = false) {
                                reader.followPath(4, 4, 11, 28)?.let {
                                    (it.getVarInt(1) to it.getVarInt(2)) == (0L to 0L)
                                } == true || reader.getByteArray(4, 4, 11, 13, 4, 1, 2, 12, 27, 1) != null
                            }
                            val sentFromMemories by rememberAsyncMutableState(defaultValue = false) {
                                reader.getVarInt(4, 18) != null
                                    || reader.getString(4, 5, 1, 2)?.contains("/h/") == true
                            }

                            val visibleIndicators = mutableListOf<@Composable () -> Unit>()
                            
                            if (sentWithLocation && messageIndicatorsConfig.contains("location_indicator")) {
                                visibleIndicators.add { GradientIcon(Icons.Default.LocationOn, locationBrush) }
                            }
                            if (messageIndicatorsConfig.contains("platform_indicator")) {
                                val (platformIcon, platformBrush) = when {
                                    sentFromWebApp -> Icons.Default.Laptop to webBrush
                                    sentFromIosDevice -> appleLogo to appleBrush
                                    else -> Icons.Default.Android to androidBrush
                                }
                                visibleIndicators.add { GradientIcon(platformIcon, platformBrush) }
                            }
                            if (hasEncryption && messageIndicatorsConfig.contains("encryption_indicator")) {
                                visibleIndicators.add { GradientIcon(Icons.Default.Lock, lockBrush) }
                            }
                            if (sentUsingDirectorMode && messageIndicatorsConfig.contains("director_mode_indicator")) {
                                visibleIndicators.add { GradientIcon(Icons.Default.Edit, directorBrush) }
                            }
                            if (sentFromMemories && messageIndicatorsConfig.contains("memories_indicator")) {
                                visibleIndicators.add { GradientIcon(Icons.Default.HistoryEdu, memoriesBrush) }
                            }
                            if (sentUsingOvfEditor && messageIndicatorsConfig.contains("ovf_editor_indicator")) {
                                visibleIndicators.add { 
                                    GradientText(
                                        text = "OVF",
                                        brush = ovfBrush,
                                        fontWeight = FontWeight.ExtraBold,
                                        fontSize = 10.sp
                                    )
                                }
                            }

                            if (visibleIndicators.isNotEmpty()) {
                                Surface(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { 
                                            event.view.context.vibrateLongPress()
                                        },
                                    color = Color.Black.copy(alpha = 0.25f),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        visibleIndicators.forEachIndexed { index, indicator ->
                                            indicator()
                                            if (index < visibleIndicators.size - 1) {
                                                IndicatorDivider()
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }.apply {
                        tag = messageInfoTag
                        addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
                            layout(left, 0, right, 0)
                        }
                        setPadding(0, 0, 0, -(50 * event.view.resources.displayMetrics.density).toInt())
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        )
                        view.addView(this)
                    }
                }
            }
        }
    }
}
