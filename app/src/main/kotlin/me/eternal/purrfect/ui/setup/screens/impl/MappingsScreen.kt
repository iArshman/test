package me.eternal.purrfect.ui.setup.screens.impl

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.MaterialTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.eternal.purrfect.common.ui.theme.LocalPurrfectSkin
import me.eternal.purrfect.common.ui.util.G2RoundedRectangle
import me.eternal.purrfect.ui.manager.components.AestheticDialog
import me.eternal.purrfect.ui.manager.theme.WavyCircularProgressIndicator
import me.eternal.purrfect.ui.setup.screens.SetupScreen
import me.eternal.purrfect.ui.util.AlertDialogs
import me.eternal.purrfect.ui.util.Motion

class MappingsScreen : SetupScreen() {
    @Composable
    override fun Content() {
        val skin = LocalPurrfectSkin.current
        val coroutineScope = rememberCoroutineScope()
        val translation = context.translation
        var infoText by remember { mutableStateOf(null as String?) }
        var isGenerating by remember { mutableStateOf(false) }
        var showNotice by remember { mutableStateOf(false) }
        var timeout by remember { mutableIntStateOf(15) }
        fun finishMappings() = goNext()

        LaunchedEffect(showNotice) {
            if (showNotice) {
                timeout = 15
                while (timeout > 0) {
                    delay(1000)
                    timeout--
                }
            }
        }

        if (showNotice) {
            val confirmLabel = if (timeout > 0) {
                context.translation.format("setup.mappings.confirm_understand_timeout", "seconds" to timeout.toString())
            } else {
                context.translation["setup.mappings.confirm_understand"]
            }
            AestheticDialog(
                onDismissRequest = { 
                    if (timeout == 0) {
                        showNotice = false
                        finishMappings()
                    }
                },
                title = context.translation["setup.mappings.notice_title"],
                text = "",
                icon = Icons.Filled.Info,
                confirmButtonText = confirmLabel,
                onConfirm = { 
                    if (timeout == 0) {
                        showNotice = false
                        finishMappings()
                    }
                },
                confirmEnabled = timeout == 0,
                showCloseButton = false,
                customContent = {
                    val bodyStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = skin.textSecondary,
                        lineHeight = 18.sp
                    )
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = skin.cardOverlayColor,
                        tonalElevation = 0.dp,
                        shadowElevation = 0.dp,
                        border = BorderStroke(
                            1.dp,
                            Brush.linearGradient(
                                listOf(
                                    skin.glowPrimary.copy(alpha = 0.55f),
                                    skin.glowSecondary.copy(alpha = 0.35f)
                                )
                            )
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 360.dp)
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = context.translation["setup.mappings.notice_intro"],
                                style = bodyStyle,
                                textAlign = TextAlign.Start,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                text = context.translation["setup.mappings.notice_step_1"],
                                fontWeight = FontWeight.SemiBold,
                                color = skin.textPrimary,
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Start
                            )
                            Text(
                                text = context.translation["setup.mappings.notice_step_2"],
                                fontWeight = FontWeight.SemiBold,
                                color = skin.textPrimary,
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Start
                            )
                            Text(
                                text = context.translation["setup.mappings.notice_step_3"],
                                fontWeight = FontWeight.SemiBold,
                                color = skin.textPrimary,
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Start
                            )
                        }
                    }
                }
            )
        }

        if (infoText != null) {
            fun dismiss() {
                val isFailure = infoText?.contains(context.translation["setup.mappings.generate_failure"]) == true
                infoText = null
                if (isFailure) {
                    isGenerating = false
                } else {
                    if (isFirstRunFlow) {
                        showNotice = true
                    } else {
                        finishMappings()
                    }
                }
            }

            var visible by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { visible = true }
            Dialog(onDismissRequest = { dismiss() }) {
                AnimatedVisibility(
                    visible = visible,
                    enter = fadeIn(animationSpec = Motion.tweenFloatSpec(180)) + scaleIn(animationSpec = Motion.tweenFloatSpec(200)),
                    exit = fadeOut(animationSpec = Motion.tweenFloatSpec(150)) + scaleOut(animationSpec = Motion.tweenFloatSpec(180))
                ) {
                    remember { AlertDialogs(context.translation) }.InfoDialog(title = infoText!!) {
                        dismiss()
                    }
                }
            }
        }

        LaunchedEffect(Unit) {
            coroutineScope.launch(Dispatchers.IO) {
                if (isGenerating) return@launch
                isGenerating = true
                runCatching {
                    context.mappings.init(context.androidContext)
                    if (context.mappings.getSnapchatPackageInfo() == null) {
                        throw Exception(context.translation["setup.mappings.generate_failure_no_snapchat"])
                    }
                    val warnings = context.mappings.refresh()

                    if (warnings.isNotEmpty()) {
                        isGenerating = false
                        infoText = translation.format(
                            "setup.mappings.warnings_info",
                            "count" to warnings.size.toString(),
                            "warnings" to warnings.joinToString("\n")
                        ).also {
                            context.log.warn(it)
                        }
                        return@launch
                    }

                    withContext(Dispatchers.Main) {
                        if (isFirstRunFlow) {
                            showNotice = true
                        } else {
                            finishMappings()
                        }
                    }
                }.onFailure {
                    isGenerating = false
                    infoText = context.translation["setup.mappings.generate_failure"] + "\n\n" + it.message
                    context.log.error("Failed to generate mappings", it)
                }
            }
        }

        SetupCard {
            StepTitle(
                title = context.translation["setup.mappings.dialog"],
                subtitle = null
            )
            DialogText(
                text = translation["setup.mappings.progress_hint"]
            )
            Spacer(modifier = Modifier.height(12.dp))
            Surface(
                shape = if (skin.id == "AETHER") G2RoundedRectangle(20.dp) else RoundedCornerShape(20.dp),
                color = if (skin.id == "AETHER") skin.cardOverlayColor else skin.textPrimary.copy(alpha = 0.05f),
                border = BorderStroke(
                    1.dp,
                    Brush.linearGradient(
                        listOf(
                            skin.glowPrimary.copy(alpha = 0.48f),
                            skin.glowSecondary.copy(alpha = 0.36f)
                        )
                    )
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (skin.id == "AETHER") {
                        WavyCircularProgressIndicator(
                            color = skin.glowPrimary,
                            strokeWidth = 6.dp
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(10.dp),
                            color = skin.glowPrimary,
                            trackColor = skin.textPrimary.copy(alpha = 0.12f)
                        )
                    }
                }
            }
        }
    }
}
