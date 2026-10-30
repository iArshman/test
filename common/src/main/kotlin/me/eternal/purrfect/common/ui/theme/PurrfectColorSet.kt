package me.eternal.purrfect.common.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * Defines the complete visual "Aura" of an Aphelion skin.
 * This includes base colors, glass properties, and the new Liquid Glass tokens.
 */
data class PurrfectColorSet(
    val id: String,
    val isDark: Boolean,

    // Backgrounds & Panels
    val backgroundGradient: Brush,
    val panelGradient: Brush,
    val cardOverlay: Brush,
    val cardOverlayColor: Color,

    // Glassmorphism Base Properties
    val glassSurface: Color,
    val glassBorder: Color,
    val glassSpecular: Color,
    val blurTint: Color,
    val refractiveColor: Color,

    // Liquid Glass Advanced Properties
    val vibrancyFactor: Float = 1.0f,
    val refractionIntensity: Float = 1.0f,
    val laserBorder: Color = Color.Transparent,
    val specularAlpha: Float = 0.1f,

    // Brand & Text
    val glowPrimary: Color,
    val glowSecondary: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val primaryButtonText: Color,
    val iconTint: Color
)
