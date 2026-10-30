package me.eternal.purrfect.ui.manager.theme

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import me.eternal.purrfect.common.ui.theme.LocalPurrfectSkin

@Composable
fun PurrfectGlassContainer(
    modifier: Modifier = Modifier,
    shape: Shape? = null,
    containerColor: Color? = null,
    content: @Composable BoxScope.() -> Unit
) {
    val skin = LocalPurrfectSkin.current
    val finalModifier = modifier
        .let { if (shape != null) it.clip(shape) else it }

    Box(
        modifier = finalModifier.fillMaxSize()
    ) {
        content()
    }
}
