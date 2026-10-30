package me.eternal.purrfect.ui.util

import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import me.eternal.purrfect.SharedContextHolder
import me.eternal.purrfect.common.ui.theme.LocalPurrfectSkin

@Composable
fun purrfectSwitchColors(): SwitchColors {
    val skin = LocalPurrfectSkin.current
    
    val trackAlpha = if (skin.isDark) 0.55f else 0.85f
    val borderAlpha = if (skin.isDark) 0.5f else 0.9f
    
    val secondary = skin.glowSecondary

    return SwitchDefaults.colors(
        checkedThumbColor = skin.textPrimary,
        checkedTrackColor = secondary.copy(alpha = trackAlpha),
        checkedBorderColor = skin.textPrimary.copy(alpha = borderAlpha),
        uncheckedThumbColor = skin.textPrimary.copy(alpha = 0.7f),
        uncheckedTrackColor = skin.textPrimary.copy(alpha = 0.25f),
        uncheckedBorderColor = skin.textPrimary.copy(alpha = 0.3f),
        disabledCheckedTrackColor = secondary.copy(alpha = 0.2f),
        disabledUncheckedTrackColor = skin.textPrimary.copy(alpha = 0.12f),
        disabledCheckedThumbColor = skin.textPrimary.copy(alpha = 0.5f),
        disabledUncheckedThumbColor = skin.textPrimary.copy(alpha = 0.35f)
    )
}
