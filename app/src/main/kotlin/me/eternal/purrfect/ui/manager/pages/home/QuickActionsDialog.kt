package me.eternal.purrfect.ui.manager.pages.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.eternal.purrfect.common.bridge.wrapper.LocaleWrapper
import me.eternal.purrfect.common.ui.theme.LocalPurrfectSkin
import me.eternal.purrfect.common.ui.theme.PurrfectPalette
import me.eternal.purrfect.ui.manager.Routes
import me.eternal.purrfect.SharedContextHolder

internal object QuickActionsSkinPalette {
    @Composable
    fun isAphelion(): Boolean {
        val context = LocalContext.current
        return remember(context) { 
            SharedContextHolder.remote(context).config.root.global.uiSettings.managerTheme.get() == "APHELION"
        }
    }

    val glowPrimary: Color @Composable get() = if (isAphelion()) LocalPurrfectSkin.current.glowPrimary else PurrfectPalette.glowPrimary
    val glowSecondary: Color @Composable get() = if (isAphelion()) LocalPurrfectSkin.current.glowSecondary else PurrfectPalette.glowSecondary
    val cardOverlayColor: Color @Composable get() = if (isAphelion()) LocalPurrfectSkin.current.cardOverlayColor else PurrfectPalette.cardOverlayColor
    val textPrimary: Color @Composable get() = if (isAphelion()) LocalPurrfectSkin.current.textPrimary else PurrfectPalette.textPrimary
}

@Composable
fun QuickActionsDialog(
    quickActions: Map<Pair<String, ImageVector>, Routes.() -> Unit>,
    selectedQuickActions: List<String>,
    onDismiss: () -> Unit,
    onSave: (List<String>) -> Unit,
    translation: LocaleWrapper
) {
    val isAphelion = QuickActionsSkinPalette.isAphelion()
    val skin = if (isAphelion) LocalPurrfectSkin.current else PurrfectPalette
    val selected = remember { mutableStateListOf<String>().apply { addAll(selectedQuickActions) } }

    me.eternal.purrfect.ui.util.Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 20.dp),
            shape = RoundedCornerShape(28.dp),
            color = skin.cardOverlayColor,
            tonalElevation = 12.dp,
            shadowElevation = 12.dp,
            border = BorderStroke(
                1.dp,
                Brush.linearGradient(
                    listOf(
                        skin.glowPrimary.copy(alpha = 0.55f),
                        skin.glowSecondary.copy(alpha = 0.45f)
                    )
                )
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = translation["manager.dialogs.quick_actions_dialog.title"] ?: "Select Quick Actions",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = skin.textPrimary,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(16.dp))

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 600.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(quickActions.keys.toList()) { action ->
                        val isSelected = selected.contains(action.first)
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (isSelected) {
                                        selected.remove(action.first)
                                    } else {
                                        selected.add(action.first)
                                    }
                                },
                            shape = RoundedCornerShape(16.dp),
                            color = if (isSelected) skin.textPrimary.copy(alpha = 0.12f) else skin.textPrimary.copy(alpha = 0.05f),
                            border = BorderStroke(
                                1.dp,
                                if (isSelected) Brush.linearGradient(listOf(skin.glowPrimary, skin.glowSecondary))
                                else SolidColor(skin.textPrimary.copy(alpha = 0.1f))
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(
                                    imageVector = action.second,
                                    contentDescription = null,
                                    tint = if (isSelected) skin.glowPrimary else skin.textPrimary,
                                    modifier = Modifier.size(24.dp)
                                )
                                Text(
                                    text = action.first,
                                    fontSize = 15.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = skin.textPrimary,
                                    modifier = Modifier.weight(1f)
                                )
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        tint = skin.glowSecondary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        onClick = onDismiss,
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, skin.textPrimary.copy(alpha = 0.2f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = skin.textPrimary)
                    ) {
                        Text(text = translation["button.cancel"] ?: "Cancel")
                    }
                    Button(
                        modifier = Modifier.weight(1f),
                        onClick = { onSave(selected.toList()) },
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = skin.textPrimary,
                            contentColor = skin.cardOverlayColor
                        )
                    ) {
                        Text(text = translation["button.save"] ?: "Save")
                    }
                }
            }
        }
    }
}
