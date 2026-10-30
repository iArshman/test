package me.eternal.purrfect.common.ui.theme

import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor

/**
 * Static definitions for all four Aphelion skins, featuring the Liquid Glass properties.
 * Refactored to implement the Catppuccin Designer System and Material 3 Expressive (Aether).
 */
object PurrfectSkins {

    private fun Color.darken(factor: Float): Color {
        return Color(
            red = red * (1 - factor),
            green = green * (1 - factor),
            blue = blue * (1 - factor),
            alpha = 1.0f
        )
    }

    // Helper to generate the Stepped Depth stack based on a Catppuccin flavor
    private fun steppedDepth(flavor: Catppuccin.Flavor, isDark: Boolean, id: String): PurrfectColorSet {
        return PurrfectColorSet(
            id = id,
            isDark = isDark,
            backgroundGradient = Brush.verticalGradient(listOf(flavor.base, flavor.mantle)),
            panelGradient = Brush.linearGradient(listOf(flavor.mantle, flavor.crust)),
            cardOverlay = SolidColor(flavor.surface0.copy(alpha = 0.85f)),
            cardOverlayColor = flavor.surface0,
            glassSurface = flavor.text.copy(alpha = 0.05f),
            glassBorder = flavor.text.copy(alpha = 0.12f),
            glassSpecular = Color.White.copy(alpha = if (isDark) 0.15f else 0.85f),
            blurTint = flavor.base.copy(alpha = 0.55f),
            refractiveColor = flavor.mantle,
            vibrancyFactor = 1.15f,
            refractionIntensity = 0.6f,
            laserBorder = flavor.mauve.copy(alpha = 0.2f),
            specularAlpha = if (isDark) 0.15f else 0.85f,
            glowPrimary = flavor.mauve,
            glowSecondary = flavor.lavender,
            textPrimary = flavor.text,
            textSecondary = flavor.subtext1,
            primaryButtonText = if (isDark) flavor.surface0 else flavor.text, // Cutout Effect: Dark text on primary glow in Dark mode, Black text in Light mode
            iconTint = flavor.text
        )
    }

    // —— UMBRA (Royal Velvet) ——————————————————————————————————————————————————————
    val umbra = PurrfectColorSet(
        id = "UMBRA",
        isDark = true,
        backgroundGradient = Brush.verticalGradient(listOf(Catppuccin.royalVelvet.base, Catppuccin.royalVelvet.base)),
        panelGradient = Brush.linearGradient(listOf(Catppuccin.royalVelvet.base, Catppuccin.royalVelvet.mantle)),
        cardOverlay = SolidColor(Catppuccin.royalVelvet.mantle.copy(alpha = 0.85f)),
        cardOverlayColor = Catppuccin.royalVelvet.mantle,
        glassSurface = Color.White.copy(alpha = 0.05f),
        glassBorder = Catppuccin.royalVelvet.lavender.copy(alpha = 0.15f),
        glassSpecular = Color.White.copy(alpha = 0.15f),
        blurTint = Catppuccin.royalVelvet.base.copy(alpha = 0.65f),
        refractiveColor = Catppuccin.royalVelvet.base,
        vibrancyFactor = 1.15f,
        refractionIntensity = 0.6f,
        laserBorder = Catppuccin.royalVelvet.mauve.copy(alpha = 0.25f),
        specularAlpha = 0.15f,
        glowPrimary = Catppuccin.royalVelvet.mauve, // Royal Blue/Purple
        glowSecondary = Catppuccin.royalVelvet.lavender, // Soft Lavender
        textPrimary = Catppuccin.royalVelvet.text,
        textSecondary = Catppuccin.royalVelvet.subtext1,
        primaryButtonText = Color.White,
        iconTint = Catppuccin.royalVelvet.text
    )

    // —— AMBER (Prestige Gold Light) ————————————————————————————————————————————————
    val amber = PurrfectColorSet(
        id = "AMBER",
        isDark = false,
        backgroundGradient = Brush.verticalGradient(listOf(Color(0xFFFCF7E8), Color(0xFFFCF7E8))),
        panelGradient = Brush.linearGradient(listOf(Color(0xFFFCF7E8), Color(0xFFF4E3BA))), 
        cardOverlay = SolidColor(Color(0xFFF4E3BA).copy(alpha = 0.90f)),
        cardOverlayColor = Color(0xFFF4E3BA),
        glassSurface = Color(0xFFFBF2D8).copy(alpha = 0.45f),
        glassBorder = Color(0xFFC69C38).copy(alpha = 0.25f),
        glassSpecular = Color.White.copy(alpha = 0.85f),
        blurTint = Color(0xFFFCF7E8).copy(alpha = 0.75f),
        refractiveColor = Color(0xFFFCF7E8),
        vibrancyFactor = 1.0f,
        refractionIntensity = 0.2f,
        laserBorder = Color(0xFF1A1610).copy(alpha = 0.15f),
        specularAlpha = 0.85f,
        glowPrimary = Color(0xFFD4AF37), // Primary Gold
        glowSecondary = Color(0xFFE2B773), // Warm Gold
        textPrimary = Color(0xFF000000), // Pure Black
        textSecondary = Color(0xFF3B3322), // Deep Bronze
        primaryButtonText = Color.Black, // Cutout Effect: Black text on gold glowing buttons
        iconTint = Color.Black
    )

    // —— NOX (Midnight Monochrome) —————————————————————————————————————————————————
    val nox = PurrfectColorSet(
        id = "NOX",
        isDark = true,
        backgroundGradient = Brush.verticalGradient(listOf(Color.Black, Color.Black)),
        panelGradient = Brush.linearGradient(listOf(Color.Black, Color(0xFF0A0A0A))),
        cardOverlay = SolidColor(Color(0xFF0A0A0A).copy(alpha = 0.90f)),
        cardOverlayColor = Color(0xFF0A0A0A),
        glassSurface = Color.White.copy(alpha = 0.03f),
        glassBorder = Color.White.copy(alpha = 0.15f),
        glassSpecular = Color.White.copy(alpha = 0.20f),
        blurTint = Color.Black.copy(alpha = 0.75f),
        refractiveColor = Color.Black,
        vibrancyFactor = 1.2f,
        refractionIntensity = 0.8f,
        laserBorder = Color.White.copy(alpha = 0.25f),
        specularAlpha = 0.18f,
        glowPrimary = Color.White,
        glowSecondary = Color(0xFFE8DEF8), // Starlight
        textPrimary = Color.White,
        textSecondary = Color(0xFFB8B1BD),
        primaryButtonText = Color.Black, // Cutout Effect
        iconTint = Color.White
    )

    // —— LUX (Warm Ivory) ———————————————————————————————————————————————————————
    val lux = steppedDepth(Catppuccin.warmLatte, false, "LUX")

    // —— LUMINA (Dynamic Adaptive) —————————————————————————————————————————————————————
    fun lumina(
        mode: String,
        accentName: String,
        isSystemDark: Boolean
    ): PurrfectColorSet {
        val isDark = when (mode) {
            "DARK" -> true
            "LIGHT" -> false
            else -> isSystemDark
        }
        
        val flavor = if (isDark) Catppuccin.softAmoled else Catppuccin.warmLatte
        
        // DEFAULT OVERRIDE: Use Blush (Rosewater) as default for Dark Mode instead of Lavender
        val effectiveAccentName = if (isDark && accentName == "MAUVE") "ROSEWATER" else accentName
        
        val accent = if (effectiveAccentName == "CYBER") {
            if (isDark) Color(0xFF00FFD1) else Color(0xFF0083B0)
        } else flavor.getAccent(effectiveAccentName)
        
        return steppedDepth(flavor, isDark, "LUMINA").copy(
            glowPrimary = accent,
            glowSecondary = if (effectiveAccentName == "CYBER") (if (isDark) Color(0xFF0083B0) else Color(0xFF00FFD1)) else flavor.lavender,
            laserBorder = accent.copy(alpha = 0.25f),
            primaryButtonText = if (effectiveAccentName.uppercase() == "BLACK") {
                Color.White
            } else {
                if (isDark) flavor.surface0 else flavor.text
            }
        )
    }

    // —— AETHER (Material 3 Expressive Solid) ———————————————————————————————————————
    fun aether(
        mode: String,
        accentName: String,
        isAmoled: Boolean,
        isSystemDark: Boolean
    ): PurrfectColorSet {
        val isDark = when (mode) {
            "DARK" -> true
            "LIGHT" -> false
            else -> isSystemDark
        }
        
        val flavor = if (isDark) Catppuccin.nordicDeep else Catppuccin.warmLatte
        val accent = if (accentName == "CYBER") {
            if (isDark) Color(0xFF00FFD1) else Color(0xFF0083B0)
        } else flavor.getAccent(accentName)

        val backgroundColor = if (isAmoled && isDark) {
            Color.Black
        } else {
            flavor.base
        }

        return PurrfectColorSet(
            id = "AETHER",
            isDark = isDark,
            backgroundGradient = SolidColor(backgroundColor),
            panelGradient = SolidColor(backgroundColor),
            cardOverlay = SolidColor(flavor.surface0),
            cardOverlayColor = flavor.surface0,
            glassSurface = Color.Transparent,
            glassBorder = accent,
            glassSpecular = Color.Transparent,
            blurTint = backgroundColor,
            refractiveColor = backgroundColor,
            vibrancyFactor = 1.0f,
            refractionIntensity = 0.0f,
            laserBorder = accent,
            glowPrimary = accent,
            glowSecondary = if (accentName == "CYBER") (if (isDark) Color(0xFF0083B0) else Color(0xFF00FFD1)) else flavor.lavender,
            textPrimary = flavor.text,
            textSecondary = flavor.subtext1,
            primaryButtonText = if (accentName.uppercase() == "BLACK") {
                Color.White
            } else {
                if (isDark) flavor.surface0 else flavor.text
            },
            iconTint = flavor.text
        )
    }

    // —— CYBER (Synthwave & Night City Tactical) —————————————————————————————————
    fun cyber(style: String): PurrfectColorSet {
        val isSynthwave = style.trim() == "SYNTHWAVE"
        
        // Synthwave Colors
        val synthMidnight = Color(0xFF090713)
        val synthDark = Color(0xFF141124)
        val synthElevated = Color(0xFF221C38)
        val synthCyan = Color(0xFF05D9E8)
        val synthMagenta = Color(0xFF9D00FF)
        val synthHolo = Color(0xFF00FFD1)
        val synthWireframe = Color(0xFF332B4D)
        val synthText = Color(0xFFF0F4F8)
        val synthSubtext = Color(0xFF7A85A3)

        // Night City Colors
        val ncVoid = Color(0xFF050505)
        val ncMatte = Color(0xFF111111)
        val ncPolymer = Color(0xFF1D1D1D)
        val ncYellow = Color(0xFFFCEE0A)
        val ncMagenta = Color(0xFFFF0055)
        val ncTeal = Color(0xFF00F0FF)
        val ncWire = Color(0xFF2B2B2B)
        val ncText = Color(0xFFF5F5F5)
        val ncAsh = Color(0xFF888888)

        val backgroundStart = if (isSynthwave) synthMidnight else ncVoid
        val panelEnd = if (isSynthwave) synthDark else ncMatte
        val cardColor = if (isSynthwave) synthElevated else ncPolymer
        val primaryGlow = if (isSynthwave) synthCyan else ncYellow
        val secondaryGlow = if (isSynthwave) synthMagenta else ncMagenta
        val laserBorder = if (isSynthwave) synthHolo else ncTeal
        val glassBorder = if (isSynthwave) synthWireframe else ncWire
        val textPrimary = if (isSynthwave) synthText else ncText
        val textSecondary = if (isSynthwave) synthSubtext else ncAsh
        val buttonText = if (isSynthwave) synthMidnight else ncVoid
        val glassTint = if (isSynthwave) synthHolo.copy(alpha = 0.05f) else ncYellow.copy(alpha = 0.03f)

        return PurrfectColorSet(
            id = "CYBER",
            isDark = true, // Cyber is always dark
            backgroundGradient = Brush.verticalGradient(listOf(backgroundStart, Color.Black)),
            panelGradient = Brush.linearGradient(listOf(backgroundStart, panelEnd)),
            cardOverlay = SolidColor(cardColor),
            cardOverlayColor = cardColor,
            glassSurface = glassTint,
            glassBorder = glassBorder,
            glassSpecular = Color.White.copy(alpha = 0.15f),
            blurTint = backgroundStart.copy(alpha = 0.65f),
            refractiveColor = backgroundStart,
            vibrancyFactor = 1.35f,
            refractionIntensity = 0.8f,
            laserBorder = laserBorder.copy(alpha = 0.35f),
            specularAlpha = 0.15f,
            glowPrimary = primaryGlow,
            glowSecondary = secondaryGlow,
            textPrimary = textPrimary,
            textSecondary = textSecondary,
            primaryButtonText = buttonText, // Cutout Effect
            iconTint = textPrimary
        )
    }

    /**
     * Returns the correct [PurrfectColorSet] for the given skin ID.
     * Modified to pass through user Lumina and Aether preferences.
     */
    fun fromId(
        id: String,
        isSystemDark: Boolean = true,
        luminaMode: String = "AUTO",
        luminaAccent: String = "LAVENDER",
        aetherMode: String = "DARK",
        aetherAccent: String = "PINK",
        aetherAmoled: Boolean = false,
        cyberwareStyle: String = "SYNTHWAVE"
    ): PurrfectColorSet = when (id) {
        "AMBER"     -> amber
        "NOX"       -> nox
        "LUX"       -> lux
        "LUMINA"    -> lumina(luminaMode, luminaAccent, isSystemDark)
        "AETHER"    -> aether(aetherMode, aetherAccent, aetherAmoled, isSystemDark)
        "CYBER"     -> cyber(cyberwareStyle)
        else        -> umbra
    }
}
