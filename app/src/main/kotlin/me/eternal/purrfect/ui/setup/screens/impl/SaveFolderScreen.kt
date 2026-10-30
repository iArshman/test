package me.eternal.purrfect.ui.setup.screens.impl

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import me.eternal.purrfect.common.ui.theme.LocalPurrfectSkin
import me.eternal.purrfect.common.ui.util.G2RoundedRectangle
import me.eternal.purrfect.ui.setup.screens.SetupScreen
import me.eternal.purrfect.ui.util.ActivityLauncherHelper
import me.eternal.purrfect.ui.util.chooseFolder
import me.eternal.purrfect.ui.util.scaleOnPress
import androidx.compose.foundation.interaction.MutableInteractionSource

class SaveFolderScreen : SetupScreen() {
    private lateinit var activityLauncherHelper: ActivityLauncherHelper

    override fun init() {
        activityLauncherHelper = ActivityLauncherHelper(context.activity!!)
    }

    @Composable
    override fun Content() {
        val skin = LocalPurrfectSkin.current
        var currentFolder by remember {
            mutableStateOf(context.config.root.downloader.saveFolder.get().orEmpty())
        }
        val readablePath = remember(currentFolder) {
            if (currentFolder.isBlank()) null
            else runCatching {
                val uri = android.net.Uri.parse(currentFolder)
                val path = uri.path ?: return@runCatching currentFolder
                if (path.contains("tree/")) {
                    path.substringAfter("tree/").replace("primary:", "Internal Storage/").replace(":", "/")
                } else currentFolder
            }.getOrElse { currentFolder }
        }
        var showNoPickerDialog by remember { mutableStateOf(false) }
        val contentColorAction = if (skin.glowPrimary.luminance() > 0.5f) Color.Black else Color.White
        SetupCard {
            StepTitle(
                title = context.translation["setup.dialogs.save_folder"],
                subtitle = null
            )
            DialogText(text = context.translation["setup.save_folder.description"])
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                color = skin.textPrimary.copy(alpha = 0.05f),
                border = BorderStroke(
                    1.dp,
                    Brush.linearGradient(
                        listOf(
                            skin.glowPrimary.copy(alpha = 0.5f),
                            skin.glowSecondary.copy(alpha = 0.35f)
                        )
                    )
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Surface(
                        modifier = Modifier.size(42.dp),
                        shape = if (skin.id == "AETHER") G2RoundedRectangle(14.dp) else RoundedCornerShape(14.dp),
                        color = skin.glowPrimary.copy(alpha = 0.18f)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Filled.FolderOpen,
                                contentDescription = null,
                                tint = skin.textPrimary,
                                modifier = Modifier.align(Alignment.Center)
                            )
                        }
                    }
                    Column(
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = context.translation["setup.save_folder.destination_label"],
                            fontSize = 13.sp,
                            color = skin.textSecondary
                        )
                        Text(
                            text = readablePath ?: context.translation["setup.save_folder.system_default_label"],
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = skin.textPrimary,
                            maxLines = 2
                        )
                    }
                }
            }
            val src = remember { MutableInteractionSource() }
            Button(
                onClick = {
                    activityLauncherHelper.chooseFolder(
                        onUnavailable = { showNoPickerDialog = true },
                        callback = callback@{ uriString ->
                        if (uriString.isBlank()) {
                            return@callback
                        }
                        currentFolder = uriString
                        context.config.root.downloader.saveFolder.set(uriString)
                        context.sharedPreferences.edit().putBoolean("downloader_use_default_save_folder", false).apply()
                        context.config.writeConfig()
                        goNext()
                        }
                    )
                },
                interactionSource = src,
                modifier = Modifier
                    .fillMaxWidth()
                    .scaleOnPress(src),
                colors = ButtonDefaults.buttonColors(
                    containerColor = skin.glowPrimary.copy(alpha = 0.32f),
                    contentColor = contentColorAction
                )
            ) {
                Text(text = context.translation["setup.dialogs.select_save_folder_button"])
            }
            val defaultSrc = remember { MutableInteractionSource() }
            OutlinedButton(
                onClick = {
                    currentFolder = ""
                    context.config.root.downloader.saveFolder.set("")
                    context.sharedPreferences.edit().putBoolean("downloader_use_default_save_folder", true).apply()
                    context.config.writeConfig()
                    goNext()
                },
                interactionSource = defaultSrc,
                modifier = Modifier
                    .fillMaxWidth()
                    .scaleOnPress(defaultSrc),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = skin.textPrimary
                ),
                border = BorderStroke(1.dp, skin.textPrimary.copy(alpha = 0.18f))
            ) {
                Text(text = context.translation["setup.save_folder.use_default_location_button"])
            }

            if (showNoPickerDialog) {
                Dialog(onDismissRequest = { showNoPickerDialog = false }) {
                    Surface(
                        shape = if (skin.id == "AETHER") G2RoundedRectangle(28.dp) else RoundedCornerShape(28.dp),
                        color = Color.Transparent,
                        tonalElevation = 0.dp,
                        shadowElevation = 18.dp,
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
                                .background(if (skin.id == "AETHER") skin.cardOverlayColor else skin.cardOverlayColor.copy(alpha = 0.28f))
                                .padding(horizontal = 20.dp, vertical = 18.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Surface(
                                modifier = Modifier.size(62.dp),
                                shape = if (skin.id == "AETHER") G2RoundedRectangle(20.dp) else RoundedCornerShape(20.dp),
                                color = skin.glowSecondary.copy(alpha = 0.18f)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Filled.FolderOpen,
                                        contentDescription = null,
                                        tint = skin.textPrimary,
                                        modifier = Modifier.size(30.dp)
                                    )
                                }
                            }
                            Text(
                                text = context.translation["setup.save_folder.no_picker_title"],
                                fontSize = 18.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = skin.textPrimary
                            )
                            Text(
                                text = context.translation["setup.save_folder.no_picker_message"],
                                fontSize = 14.sp,
                                lineHeight = 18.sp,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                color = skin.textSecondary
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedButton(
                                    onClick = { showNoPickerDialog = false },
                                    modifier = Modifier.weight(1f),
                                    shape = if (skin.id == "AETHER") G2RoundedRectangle(18.dp) else RoundedCornerShape(18.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = skin.textPrimary
                                    ),
                                    border = BorderStroke(1.dp, skin.textPrimary.copy(alpha = 0.14f))
                                ) {
                                    Text(context.translation["button.cancel"])
                                }
                                Button(
                                    onClick = {
                                        showNoPickerDialog = false
                                        currentFolder = ""
                                        context.config.root.downloader.saveFolder.set("")
                                        context.sharedPreferences.edit().putBoolean("downloader_use_default_save_folder", true).apply()
                                        context.config.writeConfig()
                                        goNext()
                                    },
                                    modifier = Modifier.weight(1f),
                                    shape = if (skin.id == "AETHER") G2RoundedRectangle(18.dp) else RoundedCornerShape(18.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = skin.glowPrimary.copy(alpha = 0.32f),
                                        contentColor = contentColorAction
                                    )
                                ) {
                                    Text(context.translation["setup.save_folder.use_default_button"])
                                }
                            }
                        }
                    }
                }
            }
            DialogText(
                text = context.translation["setup.save_folder.permission_hint"]
            )
        }
    }
}
