package me.eternal.purrfect.core.features.impl.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
private val TextIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "TextFields",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.White)) {
            moveTo(2.5f, 4f)
            lineTo(2.5f, 7f)
            lineTo(5.5f, 7f)
            lineTo(5.5f, 5.5f)
            lineTo(10f, 5.5f)
            lineTo(10f, 17f)
            lineTo(8f, 17f)
            lineTo(8f, 19f)
            lineTo(16f, 19f)
            lineTo(16f, 17f)
            lineTo(14f, 17f)
            lineTo(14f, 5.5f)
            lineTo(18.5f, 5.5f)
            lineTo(18.5f, 7f)
            lineTo(21.5f, 7f)
            lineTo(21.5f, 4f)
            close()
        }
    }.build()
}
@Composable
fun OperaStoryCaptionTextDisplay(
    captionText: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (captionText.isEmpty()) return

    Icon(
        imageVector = TextIcon,
        contentDescription = "Caption Text",
        tint = Color.White,
        modifier = modifier
            .size(11.dp)
            .clickable { onClick() }
    )
}
