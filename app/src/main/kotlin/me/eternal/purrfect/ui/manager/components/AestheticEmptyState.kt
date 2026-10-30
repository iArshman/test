package me.eternal.purrfect.ui.manager.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import me.eternal.purrfect.common.ui.theme.LocalPurrfectSkin

internal object ComponentsSkinPalette {
    @Composable
    private fun isAphelion(): Boolean {
        val context = LocalContext.current
        return remember(context) { 
            me.eternal.purrfect.SharedContextHolder.remote(context).config.root.global.uiSettings.managerTheme.get() == "APHELION"
        }
    }

    val glowPrimary: Color @Composable get() = if (isAphelion()) LocalPurrfectSkin.current.glowPrimary else Color(0xFF8C7BFF)
    val glowSecondary: Color @Composable get() = if (isAphelion()) LocalPurrfectSkin.current.glowSecondary else Color(0xFF5FD8FF)
    val backgroundGradient: Brush @Composable get() = if (isAphelion()) LocalPurrfectSkin.current.backgroundGradient else Brush.verticalGradient(listOf(Color(0xFF261F58), Color(0xFF302A6D), Color(0xFF241F52)))
    val cardOverlay: Brush @Composable get() = if (isAphelion()) LocalPurrfectSkin.current.cardOverlay else SolidColor(Color(0xFF1B152E))
    val textPrimary: Color @Composable get() = if (isAphelion()) LocalPurrfectSkin.current.textPrimary else Color.White
    val textSecondary: Color @Composable get() = if (isAphelion()) LocalPurrfectSkin.current.textSecondary else Color(0xFFD9D3FF)
    val cardOverlayColor: Color @Composable get() = if (isAphelion()) LocalPurrfectSkin.current.cardOverlayColor else Color(0xFF1B152E)
    val refractiveColor: Color @Composable get() = if (isAphelion()) LocalPurrfectSkin.current.refractiveColor else Color(0xFF8C7BFF)
    val panelGradient: Brush @Composable get() = if (isAphelion()) LocalPurrfectSkin.current.cardOverlay else Brush.verticalGradient(listOf(Color(0xFF5C4B99), Color(0xFF322B5E), Color(0xFF1B1836)))
}

@Composable
fun AestheticEmptyState(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    actions: (@Composable () -> Unit)? = null
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Surface(
            shape = CircleShape,
            color = Color.White.copy(alpha = 0.08f),
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f))
        ) {
            Box(
                modifier = Modifier
                    .size(58.dp)
                    .background(
                        Brush.linearGradient(
                            listOf(
                                ComponentsSkinPalette.glowPrimary.copy(alpha = 0.32f),
                                ComponentsSkinPalette.glowSecondary.copy(alpha = 0.28f)
                            )
                        ),
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.material3.Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = Color.White
                )
            }
        }

        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
            color = Color.White,
            textAlign = TextAlign.Center
        )

        if (!subtitle.isNullOrBlank()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = ComponentsSkinPalette.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp)
            )
        }

        if (actions != null) {
            Spacer(modifier = Modifier.size(2.dp))
            actions()
        }
    }
}
