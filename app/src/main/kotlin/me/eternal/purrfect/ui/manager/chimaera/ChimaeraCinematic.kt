package me.eternal.purrfect.ui.manager.chimaera

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Full-screen cinematic that plays before the internal experience launches.
 */
@Composable
fun ChimaeraCinematic(
    isFirstUnlock: Boolean,
    onComplete: () -> Unit
) {
    var screenAlpha by remember { mutableFloatStateOf(0f) }
    var shipAlpha by remember { mutableFloatStateOf(0f) }
    var shipY by remember { mutableFloatStateOf(-0.3f) }
    var line1Alpha by remember { mutableFloatStateOf(0f) }
    var line2Alpha by remember { mutableFloatStateOf(0f) }
    var fadeOutAlpha by remember { mutableFloatStateOf(1f) }

    val textMeasurer = rememberTextMeasurer()

    LaunchedEffect(Unit) {
        animate(0f, 1f, animationSpec = tween(300)) { v, _ -> screenAlpha = v }

        launch {
            animate(-0.25f, 0.08f, animationSpec = tween(1200, easing = FastOutSlowInEasing)) { v, _ -> shipY = v }
        }
        animate(0f, 1f, animationSpec = tween(600)) { v, _ -> shipAlpha = v }
        delay(600)

        animate(0f, 1f, animationSpec = tween(400)) { v, _ -> line1Alpha = v }
        delay(200)

        animate(0f, 1f, animationSpec = tween(300)) { v, _ -> line2Alpha = v }
        delay(1200)

        animate(1f, 0f, animationSpec = tween(400)) { v, _ -> fadeOutAlpha = v }

        onComplete()
    }

    val subtitle = if (isFirstUnlock) "ADMIRAL THRAWN COMMANDS" else "WELCOME BACK, COMMANDER"

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = screenAlpha * fadeOutAlpha))
    ) {
        val w = size.width
        val h = size.height
        val centerX = w / 2f
        val shipCenterY = h * (0.35f + shipY)

        val combinedAlpha = shipAlpha * fadeOutAlpha
        if (combinedAlpha > 0f) {
            drawStarDestroyer(
                centerX = centerX,
                centerY = shipCenterY,
                scale = w * 0.65f,
                alpha = combinedAlpha
            )
        }

        if (line1Alpha > 0f) {
            val style1 = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 22.sp,
                color = Color.White.copy(alpha = line1Alpha * fadeOutAlpha)
            )
            val measured1 = textMeasurer.measure("I.S.D. CHIMAERA", style1)
            drawText(
                textLayoutResult = measured1,
                topLeft = Offset(
                    centerX - measured1.size.width / 2f,
                    h * 0.55f
                )
            )
        }

        if (line2Alpha > 0f) {
            val style2 = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Normal,
                fontSize = 13.sp,
                color = Color(0xFF8C7BFF).copy(alpha = line2Alpha * fadeOutAlpha)
            )
            val measured2 = textMeasurer.measure(subtitle, style2)
            drawText(
                textLayoutResult = measured2,
                topLeft = Offset(
                    centerX - measured2.size.width / 2f,
                    h * 0.62f
                )
            )
        }
    }
}

private fun DrawScope.drawStarDestroyer(
    centerX: Float,
    centerY: Float,
    scale: Float,
    alpha: Float
) {
    val hull = Path().apply {
        moveTo(centerX + scale * 0.50f, centerY)
        lineTo(centerX - scale * 0.50f, centerY - scale * 0.22f)
        lineTo(centerX - scale * 0.45f, centerY)
        lineTo(centerX - scale * 0.50f, centerY + scale * 0.22f)
        close()
    }

    val ridge = Path().apply {
        moveTo(centerX + scale * 0.20f, centerY - scale * 0.02f)
        lineTo(centerX - scale * 0.30f, centerY - scale * 0.14f)
        lineTo(centerX - scale * 0.38f, centerY - scale * 0.12f)
        lineTo(centerX - scale * 0.10f, centerY - scale * 0.02f)
        close()
    }

    val bridge = Path().apply {
        val bx = centerX - scale * 0.15f
        val by = centerY - scale * 0.16f
        moveTo(bx - scale * 0.03f, by)
        lineTo(bx + scale * 0.03f, by)
        lineTo(bx + scale * 0.02f, by - scale * 0.06f)
        lineTo(bx - scale * 0.02f, by - scale * 0.06f)
        close()
    }

    val engineY = centerY
    val engineSpacing = scale * 0.04f
    val engineX = centerX - scale * 0.44f
    val engineRadius = scale * 0.012f

    withTransform({}) {
        drawPath(hull, Color.White.copy(alpha = alpha * 0.15f))
        drawPath(hull, Color.White.copy(alpha = alpha * 0.7f), style = Stroke(width = 1.5f))
        drawPath(ridge, Color.White.copy(alpha = alpha * 0.25f))
        drawPath(ridge, Color.White.copy(alpha = alpha * 0.6f), style = Stroke(width = 1f))
        drawPath(bridge, Color.White.copy(alpha = alpha * 0.4f))
        drawPath(bridge, Color.White.copy(alpha = alpha * 0.8f), style = Stroke(width = 1f))

        for (i in -1..1) {
            drawCircle(
                color = Color(0xFF5FD8FF).copy(alpha = alpha * 0.8f),
                radius = engineRadius,
                center = Offset(engineX, engineY + i * engineSpacing)
            )
            drawCircle(
                color = Color(0xFF8C7BFF).copy(alpha = alpha * 0.4f),
                radius = engineRadius * 2.5f,
                center = Offset(engineX, engineY + i * engineSpacing)
            )
        }
    }
}
