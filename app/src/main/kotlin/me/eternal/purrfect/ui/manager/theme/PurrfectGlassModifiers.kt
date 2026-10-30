package me.eternal.purrfect.ui.manager.theme

import android.os.Build
import android.graphics.RuntimeShader
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.eternal.purrfect.common.ui.theme.PurrfectColorSet
import me.eternal.purrfect.common.ui.theme.AetherShaders
import androidx.compose.runtime.*

fun Modifier.liquidBlur(
    radius: Dp = 25.dp,
    vibrancyFactor: Float = 1.0f
): Modifier = this 

/**
 * High-performance Aether interaction.
 * Scales down on press and bounces back using spring physics.
 */
fun Modifier.aetherClickable(
    onClick: () -> Unit
): Modifier = composed {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.96f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 400f),
        label = "aether_squish"
    )
    this.graphicsLayer(scaleX = scale, scaleY = scale)
        .clickable(
            interactionSource = interactionSource,
            indication = null,
            onClick = onClick
        )
}

fun Modifier.aetherGlass(
    skin: PurrfectColorSet,
    cornerRadius: Dp = 0.dp,
    topStart: Dp = cornerRadius,
    topEnd: Dp = cornerRadius,
    bottomStart: Dp = cornerRadius,
    bottomEnd: Dp = cornerRadius,
    focusFactor: Float = 1.0f
): Modifier {
    val shape = RoundedCornerShape(
        topStart = topStart,
        topEnd = topEnd,
        bottomStart = bottomStart,
        bottomEnd = bottomEnd
    )

    // Aether v2: 100% Solid Performance Mode
    // PERFORMANCE FIX: Switched from G2 math to system-native RoundedCornerShape to stop scroller crashes.
    if (skin.id == "AETHER") {
        return this.background(skin.cardOverlayColor, shape)
            .border(2.dp, skin.glassBorder, shape)
    }

    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        this.drawWithCache {
            val dispersionShader = RuntimeShader(AetherShaders.DISPERSION)
            val highlightShader = RuntimeShader(AetherShaders.HIGHLIGHT)
            
            val dispersionBrush = ShaderBrush(dispersionShader)
            val highlightBrush = ShaderBrush(highlightShader)

            val path = Path().apply {
                addRoundRect(
                    RoundRect(
                        rect = Rect(Offset.Zero, size),
                        topLeft = CornerRadius(topStart.toPx(), topStart.toPx()),
                        topRight = CornerRadius(topEnd.toPx(), topEnd.toPx()),
                        bottomLeft = CornerRadius(bottomStart.toPx(), bottomStart.toPx()),
                        bottomRight = CornerRadius(bottomEnd.toPx(), bottomEnd.toPx())
                    )
                )
            }

            onDrawWithContent {
                clipPath(path) {
                    val legibilityGradient = Brush.radialGradient(
                        colors = listOf(
                            skin.cardOverlayColor.copy(alpha = 0.92f * focusFactor),
                            skin.cardOverlayColor.copy(alpha = 0.35f * focusFactor)
                        ),
                        center = center,
                        radius = size.maxDimension * 0.7f
                    )
                    drawRect(legibilityGradient)
                    
                    if (focusFactor > 0.1f) {
                        drawRect(highlightBrush, alpha = skin.specularAlpha * focusFactor)
                    }
                    
                    if (focusFactor > 0.1f) {
                        dispersionShader.setFloatUniform("refractionAmount", 15f * focusFactor)
                        drawRect(dispersionBrush)
                    }
                    }

                    drawContent()
                    }
                    }
                    } else {
                    this.background(skin.cardOverlayColor.copy(alpha = 0.85f * focusFactor), shape)
                    }
                    }

                    /**
                    * Premium Refractive Scatter Layer.
                    * Uses SDF-clipped AGSL to simulate light-scattering gradients inside a solid slab.
                    */
                    fun Modifier.refractiveScatter(
                        skin: PurrfectColorSet,
                        cornerRadius: Dp = 28.dp,
                        scrollOffset: Float = 0f,
                        intensity: Float = 1.0f
                    ): Modifier = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        this.drawWithCache {
                            val shader = RuntimeShader(AetherShaders.SLAB_SCATTER)
                            val brush = ShaderBrush(shader)
                            val r = cornerRadius.toPx()

                            onDrawWithContent {
                                val path = Path().apply {
                                    addRoundRect(
                                        RoundRect(
                                            rect = Rect(Offset.Zero, size),
                                            cornerRadius = CornerRadius(r, r)
                                        )
                                    )
                                }
                                clipPath(path) {
                                    // Set uniforms for gradient material slab
                                    shader.setFloatUniform("size", size.width, size.height)
                                    shader.setFloatUniform("cornerRadii", r, r, r, r)
                                    shader.setFloatUniform("time", scrollOffset)
                                    shader.setFloatUniform("intensity", intensity)

                                    // Theme-correct Gradient Material Colors
                                    shader.setColorUniform("colorTop", skin.cardOverlayColor.copy(alpha = 0.98f).toArgb())
                                    shader.setColorUniform("colorBottom", skin.cardOverlayColor.copy(alpha = 0.94f).toArgb())

                                    // STEP 1: Draw the glass slab material background
                                    drawRect(brush)

                                    // STEP 2: Draw the content (icons, text, indicator) ON TOP
                                    this@onDrawWithContent.drawContent()
                                }
                            }
                        }
                    } else this

                    private fun Color.toArgb(): Int {
                    return (this.alpha * 255).toInt() shl 24 or
                    (this.red * 255).toInt() shl 16 or
                    (this.green * 255).toInt() shl 8 or
                    (this.blue * 255).toInt()
                    }
