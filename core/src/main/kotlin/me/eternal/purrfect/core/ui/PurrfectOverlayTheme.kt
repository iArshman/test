package me.eternal.purrfect.core.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.TextStyle
import me.eternal.purrfect.common.ui.theme.LocalPurrfectSkin
import me.eternal.purrfect.common.ui.theme.PurrfectColorSet
import me.eternal.purrfect.common.ui.theme.PurrfectSkins
import me.eternal.purrfect.core.ModContext
import me.eternal.purrfect.core.util.ktx.isDarkTheme

val LocalModContext = compositionLocalOf<ModContext?> { null }

@Composable
fun resolveActiveSkin(modContext: ModContext?): PurrfectColorSet {
    val context = androidx.compose.ui.platform.LocalContext.current
    val isDark = remember { context.isDarkTheme() }

    return remember {
        if (isDark) {
            PurrfectSkins.nox
        } else {
            PurrfectSkins.lux
        }
    }
}

/**
 * Legacy support object for Core UI.
 * Provides easy access to the currently active skin's colors.
 */
@Immutable
object PurrfectOverlayPalette {
    val glowPrimary: Color @Composable get() = LocalPurrfectSkin.current.glowPrimary
    val glowSecondary: Color @Composable get() = LocalPurrfectSkin.current.glowSecondary
    val textPrimary: Color @Composable get() = LocalPurrfectSkin.current.textPrimary
    val textSecondary: Color @Composable get() = LocalPurrfectSkin.current.textSecondary
    val cardOverlay: Brush @Composable get() = SolidColor(LocalPurrfectSkin.current.cardOverlayColor)
    val cardOverlayColor: Color @Composable get() = LocalPurrfectSkin.current.cardOverlayColor
}

@Composable
fun PurrfectOverlayTheme(
    modContext: ModContext? = null,
    content: @Composable () -> Unit
) {
    val skin = resolveActiveSkin(modContext)

    val scheme = if (skin.isDark) {
        darkColorScheme(
            primary = skin.glowPrimary,
            secondary = skin.glowSecondary,
            background = Color.Transparent,
            surface = skin.cardOverlayColor,
            onPrimary = skin.primaryButtonText,
            onSecondary = skin.primaryButtonText,
            onBackground = skin.textPrimary,
            onSurface = skin.textPrimary,
        )
    } else {
        lightColorScheme(
            primary = skin.glowPrimary,
            secondary = skin.glowSecondary,
            background = Color.Transparent,
            surface = skin.cardOverlayColor,
            onPrimary = skin.primaryButtonText,
            onSecondary = skin.primaryButtonText,
            onBackground = skin.textPrimary,
            onSurface = skin.textPrimary,
        )
    }

    MaterialTheme(
        colorScheme = scheme,
        shapes = MaterialTheme.shapes.copy(
            small = RoundedCornerShape(14.dp),
            medium = RoundedCornerShape(18.dp),
            large = RoundedCornerShape(22.dp),
        ),
        content = {
            CompositionLocalProvider(
                LocalModContext provides modContext,
                LocalPurrfectSkin provides skin,
                androidx.compose.material3.LocalContentColor provides skin.textPrimary,
                LocalTextStyle provides LocalTextStyle.current.merge(
                    TextStyle(color = skin.textPrimary)
                )
            ) {
                content()
            }
        }
    )
}

@Composable
fun PurrfectGlassCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    subtitle: String? = null,
    icon: ImageVector? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val skin = LocalPurrfectSkin.current
    val isAether = skin.id == "AETHER"
    val shape = if (isAether) me.eternal.purrfect.common.ui.util.G2RoundedRectangle(28.dp) else RoundedCornerShape(22.dp)

    Surface(
        modifier = modifier
            .shadow(
                elevation = 18.dp,
                shape = shape,
                spotColor = skin.glowPrimary.copy(alpha = 0.25f),
                ambientColor = skin.glowSecondary.copy(alpha = 0.18f),
            )
            .clip(shape)
            .border(
                BorderStroke(
                    1.dp,
                    if (isAether) SolidColor(skin.laserBorder.copy(alpha = 0.45f))
                    else Brush.linearGradient(
                        listOf(
                            skin.glowPrimary.copy(alpha = 0.55f),
                            skin.glowSecondary.copy(alpha = 0.35f),
                        )
                    )
                ),
                shape
            ),
        color = skin.cardOverlayColor,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Column {
                if (title != null || subtitle != null || icon != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (icon != null) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(skin.textPrimary.copy(alpha = 0.08f))
                                    .border(1.dp, skin.textPrimary.copy(alpha = 0.10f), RoundedCornerShape(14.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(icon, contentDescription = null, tint = skin.textPrimary, modifier = Modifier.size(20.dp))
                            }
                            Spacer(Modifier.width(12.dp))
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            if (title != null) {
                                Text(
                                    text = title,
                                    color = skin.textPrimary,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 18.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (!subtitle.isNullOrBlank()) {
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = subtitle,
                                    color = skin.textSecondary,
                                    fontSize = 12.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
                Box(content = content)
            }
        }
    }
}
