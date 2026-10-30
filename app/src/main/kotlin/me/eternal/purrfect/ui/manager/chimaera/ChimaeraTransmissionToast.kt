package me.eternal.purrfect.ui.manager.chimaera

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * Internal terminal-style notification overlay.
 */
@Composable
fun ChimaeraTransmissionToast(
    message: String,
    displayDurationMs: Int = 3000,
    onDismiss: () -> Unit
) {
    var visible by remember { mutableStateOf(false) }
    var liveMessage by remember { mutableStateOf(message) }

    val progress by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessLow),
        label = "toast_progress"
    )

    LaunchedEffect(Unit) {
        visible = true
        
        // If this is a live signal, start the countdown ticker
        if (message.contains("CHIMAERA SIGNAL")) {
            val startTime = System.currentTimeMillis()
            while (System.currentTimeMillis() - startTime < displayDurationMs) {
                liveMessage = ChimaeraDiscovery.transmissionMessage
                delay(500)
            }
        } else {
            delay(displayDurationMs.toLong())
        }
        
        visible = false
        delay(400)
        onDismiss()
    }

    val cursorAlpha by rememberInfiniteTransition(label = "cursor")
        .animateFloat(
            initialValue = 1f,
            targetValue = 0f,
            animationSpec = infiniteRepeatable(
                animation = tween(500, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "cursor_blink"
        )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .graphicsLayer {
                translationY = -40f * (1f - progress)
                alpha = progress
                scaleX = 0.95f + (0.05f * progress)
                scaleY = 0.95f + (0.05f * progress)
            },
        contentAlignment = Alignment.TopCenter
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    color = Color(0xFF0A0A0F),
                    shape = RoundedCornerShape(12.dp)
                )
                .border(
                    width = 1.dp,
                    brush = Brush.linearGradient(
                        listOf(
                            Color(0xFF8C7BFF).copy(alpha = 0.6f),
                            Color(0xFF5FD8FF).copy(alpha = 0.4f)
                        )
                    ),
                    shape = RoundedCornerShape(12.dp)
                )
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "▶ SECURE CHANNEL 7",
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFF5FD8FF).copy(alpha = 0.6f),
                        fontWeight = FontWeight.Normal
                    )
                    Text(
                        text = "I.S.D. CHIMAERA",
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFF5FD8FF).copy(alpha = 0.6f),
                        fontWeight = FontWeight.Normal
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                liveMessage.lines().forEach { line ->
                    val isSignature = line.startsWith("—") || line.startsWith("  I.S.D.")
                    Text(
                        text = line,
                        fontSize = if (isSignature) 12.sp else 13.sp,
                        fontFamily = FontFamily.Monospace,
                        color = when {
                            isSignature -> Color(0xFF8C7BFF)
                            line.startsWith("CHIMAERA") -> Color.White
                            line.startsWith("ACCESS") || line.startsWith("WELCOME") -> Color(0xFF5FD8FF)
                            else -> Color(0xFFD0D0D0)
                        },
                        fontWeight = if (line.startsWith("CHIMAERA") || line.startsWith("ACCESS")) {
                            FontWeight.Bold
                        } else FontWeight.Normal,
                        lineHeight = 18.sp
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "█",
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFF8C7BFF).copy(alpha = cursorAlpha)
                )
            }
        }
    }
}
