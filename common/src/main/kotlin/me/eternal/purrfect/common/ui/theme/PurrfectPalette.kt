package me.eternal.purrfect.common.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * Centralized palette for the premium Purrfect look.
 * Avoids relying on MaterialTheme for branding consistency.
 */
val PurrfectPalette = PurrfectColorSet(
    id = "LEGACY",
    isDark = true,
    backgroundGradient = Brush.verticalGradient(
        listOf(
            Color(0xFF261F58),
            Color(0xFF302A6D),
            Color(0xFF241F52)
        )
    ),
    panelGradient = Brush.linearGradient(
        listOf(
            Color(0xFF5C4B99),
            Color(0xFF322B5E),
            Color(0xFF1B1836)
        )
    ),
    cardOverlay = Brush.linearGradient(
        listOf(
            Color(0xFF2A2452).copy(alpha = 0.95f),
            Color(0xFF1A143A).copy(alpha = 0.92f)
        )
    ),
    cardOverlayColor = Color(0xFF2A2452).copy(alpha = 0.95f),
    glassSurface = Color.White.copy(alpha = 0.08f),
    glassBorder = Color.White.copy(alpha = 0.12f),
    glassSpecular = Color.White.copy(alpha = 0.22f),
    blurTint = Color(0xFF1B152E).copy(alpha = 0.55f),
    refractiveColor = Color(0xFF241F52),
    vibrancyFactor = 1.15f,
    refractionIntensity = 0.6f,
    laserBorder = Color(0xFF8C7BFF).copy(alpha = 0.2f),
    specularAlpha = 0.15f,
    glowPrimary = Color(0xFF8C7BFF),
    glowSecondary = Color(0xFF5FD8FF),
    textPrimary = Color.White,
    textSecondary = Color(0xFFD9D3FF),
    primaryButtonText = Color.White,
    iconTint = Color.White
)
