package me.eternal.purrfect.ui.manager.theme

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Material 3 Expressive "Wavy/Slithering" Progress Indicator.
 * Implements the indeterminate slithering motion by animating sweep and rotation separately.
 */
@Composable
fun WavyCircularProgressIndicator(
    modifier: Modifier = Modifier,
    color: Color,
    trackColor: Color = color.copy(alpha = 0.2f),
    strokeWidth: Dp = 6.dp
) {
    val transition = rememberInfiniteTransition(label = "wavy_circular")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(animation = tween(1200, easing = LinearEasing)),
        label = "rotation"
    )
    val sweep by transition.animateFloat(
        initialValue = 15f,
        targetValue = 280f,
        animationSpec = infiniteRepeatable(
            animation = tween(700, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "sweep"
    )
    Canvas(modifier.size(40.dp)) {
        val diameter = size.minDimension
        val stroke = strokeWidth.toPx()
        val topLeft = Offset(stroke / 2, stroke / 2)
        val arcSize = Size(diameter - stroke, diameter - stroke)
        drawCircle(color = trackColor, style = Stroke(width = stroke, cap = StrokeCap.Round), radius = (diameter - stroke) / 2)
        drawArc(color = color, startAngle = rotation, sweepAngle = sweep, useCenter = false, topLeft = topLeft, size = arcSize, style = Stroke(width = stroke, cap = StrokeCap.Round))
    }
}

/**
 * Material 3 Expressive Linear Wavy Progress.
 */
@Composable
fun WavyProgress(
    progress: Float,
    modifier: Modifier = Modifier,
    color: Color,
    trackColor: Color = color.copy(alpha = 0.12f),
    height: Dp = 6.dp
) {
    Canvas(modifier.height(height)) {
        val w = size.width
        val h = size.height
        val stroke = h
        
        // Draw Track
        drawLine(color = trackColor, start = Offset(0f, h / 2), end = Offset(w, h / 2), strokeWidth = stroke, cap = StrokeCap.Round)
        
        // Draw Progress
        if (progress > 0.01f) {
            drawLine(color = color, start = Offset(0f, h / 2), end = Offset(w * progress, h / 2), strokeWidth = stroke, cap = StrokeCap.Round)
        }
    }
}
