package me.eternal.purrfect.core.features.impl.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import me.eternal.purrfect.common.ui.theme.LocalPurrfectSkin

@Composable
fun OperaStorySourceIndicatorDisplay(
    source: String,
    modifier: Modifier = Modifier
) {
    if (source.isEmpty()) return
    val skin = LocalPurrfectSkin.current

    val icon = if (source == "CAMERA") Icons.Outlined.CameraAlt else Icons.Outlined.PhotoLibrary
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = skin.textPrimary,
        modifier = modifier.size(11.dp)
    )
}
