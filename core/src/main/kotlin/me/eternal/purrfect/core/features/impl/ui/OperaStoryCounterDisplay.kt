package me.eternal.purrfect.core.features.impl.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import me.eternal.purrfect.common.ui.theme.LocalPurrfectSkin

@Composable
fun OperaStoryCounterDisplay(
    counterText: String,
    enableSnapJump: Boolean,
    totalCount: Int,
    onCounterClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (counterText.isEmpty()) return
    val skin = LocalPurrfectSkin.current

    Row(
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        modifier = modifier.then(
            if (enableSnapJump && totalCount > 1)
                Modifier.clickable { onCounterClick() }
            else Modifier
        )
    ) {
        Text(
            text = counterText,
            color = skin.textPrimary,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium
        )
    }
}
