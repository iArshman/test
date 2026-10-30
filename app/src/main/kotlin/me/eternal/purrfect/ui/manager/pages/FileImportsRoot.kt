package me.eternal.purrfect.ui.manager.pages

import android.net.Uri
import android.text.format.Formatter
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import androidx.navigation.NavBackStackEntry
import kotlinx.coroutines.launch
import me.eternal.purrfect.common.ui.AsyncUpdateDispatcher
import me.eternal.purrfect.common.ui.rememberAsyncMutableState
import me.eternal.purrfect.common.ui.rememberAsyncMutableStateList
import me.eternal.purrfect.ui.manager.Routes
import me.eternal.purrfect.ui.manager.components.FloatingTopBar
import me.eternal.purrfect.common.ui.theme.LocalPurrfectSkin
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.SolidColor
import me.eternal.purrfect.ui.util.ActivityLauncherHelper
import me.eternal.purrfect.ui.util.openFile
import java.text.DateFormat

private object FileImportSkinPalette {
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
    val panelGradient: Brush @Composable get() = if (isAphelion()) LocalPurrfectSkin.current.cardOverlay else Brush.verticalGradient(listOf(Color(0xFF5C4B99), Color(0xFF322B5E), Color(0xFF1B1836)))
}

class FileImportsRoot: Routes.Route() {
    private lateinit var activityLauncherHelper: ActivityLauncherHelper
    private val reloadDispatcher = AsyncUpdateDispatcher()

    override val init: () -> Unit = {
        activityLauncherHelper = ActivityLauncherHelper(context.activity!!)
    }

    @Composable
    private fun ImportFab(onClick: () -> Unit) {
        val shape = RoundedCornerShape(18.dp)
        val border = Brush.linearGradient(
            listOf(
                FileImportSkinPalette.glowPrimary.copy(alpha = 0.7f),
                FileImportSkinPalette.glowSecondary.copy(alpha = 0.6f)
            )
        )
        val fill = Brush.linearGradient(
            listOf(
                FileImportSkinPalette.glowPrimary.copy(alpha = 0.35f),
                FileImportSkinPalette.glowSecondary.copy(alpha = 0.28f)
            )
        )
        Row(
            modifier = Modifier
                .shadow(
                    elevation = 12.dp,
                    shape = shape,
                    ambientColor = FileImportSkinPalette.glowSecondary.copy(alpha = 0.2f),
                    spotColor = FileImportSkinPalette.glowPrimary.copy(alpha = 0.25f)
                )
                .clip(shape)
                .background(fill)
                .border(1.dp, border, shape)
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(Icons.Default.Upload, contentDescription = null, tint = FileImportSkinPalette.textPrimary)
            Text(
                text = translation["import_file_button"],
                color = FileImportSkinPalette.textPrimary,
                fontWeight = FontWeight.SemiBold
            )
        }
    }

    override val floatingActionButton: @Composable () -> Unit = {
        val coroutineScope = rememberCoroutineScope()
        ImportFab {
            context.coroutineScope.launch {
                activityLauncherHelper.openFile { filePath ->
                    val fileUri = Uri.parse(filePath)
                    runCatching {
                        DocumentFile.fromSingleUri(context.activity!!, fileUri)?.let { file ->
                            if (!file.exists()) {
                                context.shortToast(translation["file_not_found"])
                                return@openFile
                            }
                            context.fileHandleManager.importFile(file.name!!) {
                                context.androidContext.contentResolver.openInputStream(fileUri)?.use { inputStream ->
                                    inputStream.copyTo(this)
                                }
                            }
                        }
                    }.onFailure {
                        context.log.error("Failed to import file", it)
                        context.shortToast(translation.format("file_import_failed", "error" to it.message.toString()))
                    }.onSuccess {
                        context.shortToast(translation["file_imported"])
                        coroutineScope.launch {
                            reloadDispatcher.dispatch()
                        }
                    }
                }
            }
        }
    }

    override val content: @Composable (NavBackStackEntry) -> Unit = {
        val files = rememberAsyncMutableStateList(defaultValue = listOf(), updateDispatcher = reloadDispatcher) {
            context.fileHandleManager.getStoredFiles()
        }
        val density = LocalDensity.current
        val titleText = context.translation["manager.routes.file_imports"]
        var topBarHeight by remember { mutableStateOf(0.dp) }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(FileImportSkinPalette.backgroundGradient)
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(
                    top = topBarHeight + 8.dp,
                    bottom = routes.bottomPadding + 16.dp
                )
            ) {
                    item {
                        if (files.isEmpty()) {
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(20.dp),
                                color = FileImportSkinPalette.cardOverlayColor,
                                tonalElevation = 0.dp,
                                shadowElevation = 10.dp,
                                border = BorderStroke(1.dp, FileImportSkinPalette.textPrimary.copy(alpha = 0.1f))
                            ) {
                                Text(
                                    text = translation["no_files_hint"],
                                    modifier = Modifier
                                        .padding(horizontal = 18.dp, vertical = 16.dp)
                                        .fillMaxWidth(),
                                    textAlign = TextAlign.Center,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = FileImportSkinPalette.textPrimary
                                )
                            }
                        }
                    }
                    items(files, key = { it }) { file ->
                        val fileInfo by rememberAsyncMutableState(defaultValue = null) {
                            context.fileHandleManager.getFileInfo(file.name)
                        }
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(20.dp),
                            color = FileImportSkinPalette.cardOverlayColor,
                            tonalElevation = 0.dp,
                            shadowElevation = 10.dp,
                            border = BorderStroke(
                                1.dp,
                                Brush.linearGradient(
                                    listOf(
                                        FileImportSkinPalette.glowPrimary.copy(alpha = 0.4f),
                                        FileImportSkinPalette.glowSecondary.copy(alpha = 0.35f)
                                    )
                                )
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .padding(horizontal = 14.dp, vertical = 12.dp)
                                    .fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = FileImportSkinPalette.glowPrimary.copy(alpha = 0.16f)
                                ) {
                                    Icon(
                                        Icons.Default.AttachFile,
                                        contentDescription = null,
                                        modifier = Modifier.padding(10.dp),
                                        tint = FileImportSkinPalette.textPrimary
                                    )
                                }
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text = file.name,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 16.sp,
                                        color = FileImportSkinPalette.textPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    fileInfo?.let { (size, lastModified) ->
                                        Text(
                                            text = "${Formatter.formatFileSize(context.androidContext, size)} • ${
                                                DateFormat.getDateTimeInstance().format(lastModified)
                                            }",
                                            lineHeight = 15.sp,
                                            color = FileImportSkinPalette.textSecondary,
                                            fontSize = 12.sp
                                        )
                                    }
                                }

                                IconButton(onClick = {
                                    context.coroutineScope.launch {
                                        if (context.fileHandleManager.deleteFile(file.name)) {
                                            files.remove(file)
                                        } else {
                                            context.shortToast(translation["file_delete_failed"])
                                        }
                                    }
                                }) {
                                    Icon(
                                        Icons.Default.DeleteOutline,
                                        contentDescription = null,
                                        tint = FileImportSkinPalette.glowSecondary
                                    )
                                }
                            }
                        }
                    }
                }

            FloatingTopBar(
                title = titleText ?: translation["import_file_button"],
                subtitle = null,
                onBack = { routes.navController.popBackStack() },
                modifier = Modifier.onGloballyPositioned {
                    topBarHeight = with(density) { it.size.height.toDp() }
                }
            )
        }
    }
}
