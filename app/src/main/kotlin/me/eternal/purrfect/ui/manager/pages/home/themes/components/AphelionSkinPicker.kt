package me.eternal.purrfect.ui.manager.pages.home.themes.components

import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.eternal.purrfect.common.ui.theme.LocalPurrfectSkin
import me.eternal.purrfect.common.ui.theme.PurrfectPalette

/**
 * Premium Skin picker component.
 * Layout: Horizontally scrolling 120.dp pills.
 *
 * @param currentSkinId  The currently active skin ID.
 * @param onSkinSelected Callback when a new skin is selected.
 */
@Composable
fun AphelionSkinPicker(
    currentSkinId: String,
    onSkinSelected: (String) -> Unit
) {
    val skin = LocalPurrfectSkin.current
    val scrollState = rememberScrollState()

    val showCyberware = true

    val skins = remember {
        listOfNotNull(
            SkinOption(
                id = "LUMINA",
                name = "Lumina",
                description = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                    "Adaptive Day/Night aesthetics. Automatically shifts from ivory white to charcoal black."
                else
                    "Requires Android 12+",
                previewColors = listOf(Color(0xFFF8F3EC), Color(0xFF08080A), Color(0xFFF1D7D2)),
                available = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            ),
            SkinOption(
                id = "AETHER",
                name = "Aether",
                description = "Functional minimalism with high-contrast solid surfaces.",
                previewColors = listOf(Color(0xFFF8F3EC), Color(0xFF1A1B26), Color(0xFF8F63D8)),
                available = true
            ),
            SkinOption(
                id = "LUX",
                name = "Lux",
                description = "Warm Ivory on silk-smooth cards. Light theme.",
                previewColors = listOf(Color(0xFFF8F3EC), Color(0xFFF3EEE7), Color(0xFF8F63D8)),
                available = true
            ),
            SkinOption(
                id = "UMBRA",
                name = "Umbra",
                description = "Deep royal indigo with a velvety finish. Atmospheric and vibrantly dark. Dark theme.",
                previewColors = listOf(Color(0xFF14142B), Color(0xFF1F1F41), Color(0xFF7B61FF)),
                available = true
            ),
            SkinOption(
                id = "AMBER",
                name = "Amber",
                description = "A prestigious palette of warm cream and lustrous gold. Light theme.",
                previewColors = listOf(Color(0xFFFCF7E8), Color(0xFFF4E3BA), Color(0xFFD4AF37)),
                available = true
            ),
            SkinOption(
                id = "NOX",
                name = "Nox",
                description = "AMOLED Black. Dark Theme.",
                previewColors = listOf(Color(0xFF000000), Color(0xFF2D2D2D), Color(0xFFFFFFFF)),
                available = true
            ),
            if (showCyberware) SkinOption(
                id = "CYBER",
                name = "Cyber",
                description = "Full Cyberpunk aesthetic. Choose between Synthwave and Night City styles. Dark theme.",
                previewColors = listOf(Color(0xFF090713), Color(0xFF111111), Color(0xFF05D9E8)),
                available = true
            ) else null
        )
    }

    val selectedSkin = remember(currentSkinId) { skins.find { it.id == currentSkinId } }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Compact Horizontally scrollable row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(scrollState)
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            skins.forEach { option ->
                SkinCard(
                    option = option,
                    isSelected = currentSkinId == option.id,
                    onSelect = { if (option.available) onSkinSelected(option.id) },
                    modifier = Modifier.width(120.dp)
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
        }

        // Dynamic Description Popup (CENTER ALIGNED)
        AnimatedContent(
            targetState = selectedSkin,
            transitionSpec = {
                fadeIn(tween(200)) togetherWith fadeOut(tween(150))
            },
            label = "skin_description"
        ) { skinTarget ->
            if (skinTarget != null) {
                Text(
                    text = skinTarget.description,
                    fontSize = 12.sp,
                    color = skin.textSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp)
                )
            }
        }
    }
}

@Composable
private fun SkinCard(
    option: SkinOption,
    isSelected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier
) {
    val skin = LocalPurrfectSkin.current

    val borderBrush = if (isSelected) {
        if (skin.isDark) {
            Brush.linearGradient(listOf(skin.glowPrimary, skin.glowSecondary))
        } else {
            Brush.linearGradient(listOf(Color.Black.copy(alpha = 0.8f), Color.Black.copy(alpha = 0.8f)))
        }
    } else {
        Brush.linearGradient(listOf(skin.glassBorder, skin.glassBorder))
    }

    val bgColor by animateColorAsState(
        targetValue = if (isSelected) skin.glowPrimary.copy(alpha = 0.12f) else skin.glassSurface,
        animationSpec = tween(220),
        label = "skin_card_bg"
    )

    val unavailableAlpha = if (!option.available) 0.4f else 1f

    Surface(
        modifier = modifier
            .clickable(enabled = option.available) { onSelect() }
            .border(
                width = if (isSelected) 2.dp else 1.dp,
                brush = borderBrush,
                shape = RoundedCornerShape(18.dp)
            ),
        shape = RoundedCornerShape(18.dp),
        color = bgColor,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Color preview dots
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.alpha(unavailableAlpha)
            ) {
                option.previewColors.forEach { color ->
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(color)
                            .border(1.dp, skin.textPrimary.copy(alpha = 0.1f), CircleShape)
                    )
                }
            }

            Column(
                modifier = Modifier.alpha(unavailableAlpha),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Name and Indicator dynamically wrapped
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = option.name,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (skin.isDark) Color.White else Color(0xFF1A1A2E)
                    )
                    if (isSelected) {
                        Surface(
                            shape = CircleShape,
                            color = skin.glowPrimary.copy(alpha = 0.20f)
                        ) {
                            Text(
                                text = "Active",
                                fontSize = 8.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = skin.glowPrimary,
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                            )
                        }
                    }
                }
                
                if (!option.available) {
                    Text(
                        text = "Android 12+",
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFFFB347)
                    )
                }
            }
        }
    }
}

private data class SkinOption(
    val id: String,
    val name: String,
    val description: String,
    val previewColors: List<Color>,
    val available: Boolean
)
