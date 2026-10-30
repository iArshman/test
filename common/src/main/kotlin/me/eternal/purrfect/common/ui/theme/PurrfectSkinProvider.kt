package me.eternal.purrfect.common.ui.theme

import android.os.Build
import android.content.Context
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import kotlinx.coroutines.delay

/**
 * CompositionLocal that provides the active [PurrfectColorSet] to all Aphelion composables.
 * Defaults to [PurrfectSkins.umbra] so nothing breaks if accessed outside the provider.
 */
val LocalPurrfectSkin = compositionLocalOf<PurrfectColorSet> { PurrfectSkins.umbra }

/**
 * Internal core provider.
 */
@Composable
private fun AphelionSkinProviderInternal(
    managerTheme: String,
    skinId: String,
    luminaMode: String,
    luminaAccent: String,
    aetherMode: String = "AUTO",
    aetherAccent: String = "MAUVE",
    aetherAmoled: Boolean = false,
    cyberwareStyle: String = "SYNTHWAVE",
    content: @Composable () -> Unit
) {
    val isSystemDark = (android.content.res.Resources.getSystem().configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
    
    val targetSkin = if (managerTheme == "APHELION") {
        PurrfectSkins.fromId(skinId, isSystemDark, luminaMode, luminaAccent, aetherMode, aetherAccent, aetherAmoled, cyberwareStyle)
    } else {
        PurrfectPalette
    }

    // Smooth Lerp Layer - Matches the bit-perfect 350ms duration from commit 6f8d636
    val glowPrimary by animateColorAsState(targetSkin.glowPrimary, tween(350), label = "gp")
    val glowSecondary by animateColorAsState(targetSkin.glowSecondary, tween(350), label = "gs")
    val textPrimary by animateColorAsState(targetSkin.textPrimary, tween(350), label = "tp")
    val textSecondary by animateColorAsState(targetSkin.textSecondary, tween(350), label = "ts")
    val cardOverlayColor by animateColorAsState(targetSkin.cardOverlayColor, tween(350), label = "coc")
    
    val currentSkin = remember(targetSkin, glowPrimary, glowSecondary, textPrimary, textSecondary, cardOverlayColor) {
        targetSkin.copy(
            glowPrimary = glowPrimary,
            glowSecondary = glowSecondary,
            textPrimary = textPrimary,
            textSecondary = textSecondary,
            cardOverlayColor = cardOverlayColor,
            backgroundGradient = targetSkin.backgroundGradient,
            panelGradient = targetSkin.panelGradient,
            cardOverlay = targetSkin.cardOverlay
        )
    }

    CompositionLocalProvider(
        LocalPurrfectSkin provides currentSkin,
        content = content
    )
}

/**
 * The primary public provider for Aphelion skins.
 * Automatically tracks SharedPreferences changes with bit-perfect 350ms polling.
 */
@Composable
fun AphelionSkinProvider(
    context: Context,
    content: @Composable () -> Unit
) {
    val prefs = remember(context) { context.getSharedPreferences("prefs", Context.MODE_PRIVATE) }
    
    val managerTheme by produceState(initialValue = prefs.getString("manager_theme", "APHELION") ?: "APHELION") {
        while (true) {
            delay(350)
            value = prefs.getString("manager_theme", "APHELION") ?: "APHELION"
        }
    }
    
    val skinId by produceState(initialValue = prefs.getString("aphelion_skin", "UMBRA") ?: "UMBRA") {
        while (true) {
            delay(350)
            value = prefs.getString("aphelion_skin", "UMBRA") ?: "UMBRA"
        }
    }
    
    val luminaMode by produceState(initialValue = prefs.getString("lumina_mode", "AUTO") ?: "AUTO") {
        while (true) {
            delay(350)
            value = prefs.getString("lumina_mode", "AUTO") ?: "AUTO"
        }
    }
    
    val luminaAccent by produceState(initialValue = prefs.getString("lumina_accent", "MAUVE") ?: "MAUVE") {
        while (true) {
            delay(350)
            value = prefs.getString("lumina_accent", "MAUVE") ?: "MAUVE"
        }
    }

    val aetherMode by produceState(initialValue = prefs.getString("aether_mode", "AUTO") ?: "AUTO") {
        while (true) {
            delay(350)
            value = prefs.getString("aether_mode", "AUTO") ?: "AUTO"
        }
    }
    
    val aetherAccent by produceState(initialValue = prefs.getString("aether_accent", "MAUVE") ?: "MAUVE") {
        while (true) {
            delay(350)
            value = prefs.getString("aether_accent", "MAUVE") ?: "MAUVE"
        }
    }
    
    val aetherAmoled by produceState(initialValue = prefs.getBoolean("aether_amoled", false)) {
        while (true) {
            delay(350)
            value = prefs.getBoolean("aether_amoled", false)
        }
    }
    
    val cyberwareStyle by produceState(initialValue = prefs.getString("cyberware_style", "SYNTHWAVE") ?: "SYNTHWAVE") {
        while (true) {
            delay(350)
            value = prefs.getString("cyberware_style", "SYNTHWAVE") ?: "SYNTHWAVE"
        }
    }

    AphelionSkinProviderInternal(
        managerTheme = managerTheme,
        skinId = skinId,
        luminaMode = luminaMode,
        luminaAccent = luminaAccent,
        aetherMode = aetherMode,
        aetherAccent = aetherAccent,
        aetherAmoled = aetherAmoled,
        cyberwareStyle = cyberwareStyle,
        content = content
    )
}

/**
 * Helper to extract a single color from a Brush if it's a SolidColor or a Gradient.
 */
fun resolveColorFromBrush(brush: Brush): Color? {
    if (brush is SolidColor) return brush.value
    return runCatching {
        val field = brush::class.java.getDeclaredField("colors").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val colors = field.get(brush) as? List<Color>
        colors?.firstOrNull() ?: run {
            val stopsField = brush::class.java.getDeclaredField("colorStops").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val stops = stopsField.get(brush) as? List<Pair<Float, Color>>
            stops?.maxByOrNull { it.first }?.second
        }
    }.getOrNull()
}
