package me.eternal.purrfect.core.action.impl

import android.app.AlertDialog
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabase.OpenParams
import android.net.Uri
import android.os.Environment
import androidx.documentfile.provider.DocumentFile
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.*
import me.eternal.purrfect.common.data.FileType
import me.eternal.purrfect.common.ui.createComposeAlertDialog
import me.eternal.purrfect.common.ui.theme.LocalPurrfectSkin
import me.eternal.purrfect.common.util.ktx.getLongOrNull
import me.eternal.purrfect.common.util.ktx.getStringOrNull
import me.eternal.purrfect.core.action.AbstractAction
import me.eternal.purrfect.core.ui.PurrfectOverlayTheme
import okhttp3.OkHttpClient
import java.io.File
import java.io.FileOutputStream
import java.nio.file.attribute.FileTime
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.math.absoluteValue

class ExportMemories : AbstractAction() {
    private val translation by lazy { context.translation.getCategory("memories") }
    
    private val rangeFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy")
    data class TimeRange(
        val start: Long?,
        val end: Long?,
    )

    data class MemoriesEntry(
        val storyTitle: String,
        val createTime: Long,
        val mediaKey: String?,
        val mediaIv: String?,
        val downloadUrl: String
    ) {
        val folderName: String
            get() = storyTitle.replace(Regex("[^a-zA-Z0-9\\s]"), "").trim().replace(Regex("\\s+"), "_")
    }

    private data class ExportTarget(
        val outputFile: File,
        val finalize: (File) -> String
    )

    private fun resolveExportTarget(fileName: String, mimeType: String): ExportTarget {
        val configuredFolder = context.config.downloader.saveFolder.get()?.trim().orEmpty()
        val defaultTarget = {
            val documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            val outputDir = documentsDir.takeIf { it.exists() || it.mkdirs() }
                ?: context.androidContext.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
                ?: context.androidContext.filesDir
            val outputFile = File(outputDir, fileName).also {
                if (it.exists()) it.delete()
            }
            ExportTarget(outputFile) { file -> file.absolutePath }
        }

        if (configuredFolder.isBlank()) {
            return defaultTarget()
        }

        val outputFolder = runCatching {
            DocumentFile.fromTreeUri(context.androidContext, Uri.parse(configuredFolder))
        }.getOrNull()

        if (outputFolder == null || !outputFolder.canWrite()) {
            return defaultTarget()
        }

        val tempFile = File(context.androidContext.cacheDir, fileName).also {
            if (it.exists()) it.delete()
        }
        return ExportTarget(tempFile) { file ->
            val outputFile = outputFolder.createFile(mimeType, fileName)
                ?: throw IllegalStateException("Failed to create export file")
            context.androidContext.contentResolver.openOutputStream(outputFile.uri)?.use { out ->
                file.inputStream().use { it.copyTo(out) }
            } ?: throw IllegalStateException("Failed to write export file")
            outputFile.uri.toString()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class, ExperimentalEncodingApi::class)
    private suspend fun exportMemories(
        scope: CoroutineScope = context.coroutineScope,
        database: SQLiteDatabase,
        timeRange: TimeRange?,
        includeMEO: Boolean,
        folders: Boolean,
        progress: (Int, Int) -> Unit
    ) {
        val downloadContext = Dispatchers.IO.limitedParallelism(10)
        val writeToZipContext = Dispatchers.IO.limitedParallelism(1)
        val outputTarget = resolveExportTarget(
            "memories_${System.currentTimeMillis()}.zip",
            "application/zip"
        )
        val outputZip = outputTarget.outputFile
        val okHttpClient = OkHttpClient.Builder().build()
        val outputZipFile = withContext(Dispatchers.IO) {
            ZipOutputStream(FileOutputStream(outputZip)).apply {
                setComment("Exported from Purrfect")
                setMethod(ZipOutputStream.DEFLATED)
            }
        }
        var totalCount = 0
        var currentCount = 0
        var failed = 0

        fun updateProgress() {
            progress((if (totalCount == 0) 0 else (currentCount.toFloat() / totalCount.toFloat() * 100f).toInt()), failed)
        }

        val jobs = mutableListOf<Job>()

        val meoMasterKeyPair = if (includeMEO) {
            runCatching {
                database.rawQuery("SELECT * FROM memories_meo_confidential", null).use { cursor ->
                    if (cursor.moveToNext()) {
                        cursor.getStringOrNull("master_key")!!.trim() to cursor.getStringOrNull("master_key_iv")!!.trim()
                    } else null
                }
            }.getOrNull()
        } else null

        database.rawQuery("SELECT memories_entry.title as story_title, memories_snap.create_time, " +
                "memories_snap.media_key, memories_snap.media_iv, memories_snap.encrypted_media_key, memories_snap.encrypted_media_iv, " +
                "memories_media.download_url FROM memories_snap " +
            "INNER JOIN memories_entry ON memories_snap.memories_entry_id = memories_entry._id " +
            "INNER JOIN memories_media ON memories_snap.media_id = memories_media._id " +
            "WHERE memories_snap.create_time >= ? AND memories_snap.create_time <= ? " +
            "ORDER BY memories_snap.create_time ASC", arrayOf(timeRange?.start?.toString() ?: "-1", timeRange?.end?.toString() ?: Long.MAX_VALUE.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val encryptedMediaKey = cursor.getStringOrNull("encrypted_media_key")?.trim()
                val encryptedMediaIv = cursor.getStringOrNull("encrypted_media_iv")?.trim()
                var mediaKey = cursor.getStringOrNull("media_key")?.trim()
                var mediaIv = cursor.getStringOrNull("media_iv")?.trim()

                if (!includeMEO && encryptedMediaKey != null && encryptedMediaIv != null) continue

                meoMasterKeyPair.takeIf { encryptedMediaKey != null && encryptedMediaIv != null }?.let { keyPair ->
                    val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                    runCatching {
                        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(Base64.decode(keyPair.first), "AES"), IvParameterSpec(Base64.decode(keyPair.second)))
                        mediaKey = Base64.encode(cipher.doFinal(Base64.decode(encryptedMediaKey ?: return@let)))
                        mediaIv = Base64.encode(cipher.doFinal(Base64.decode(encryptedMediaIv ?: return@let)))
                        context.log.verbose("decrypted meo $mediaKey/$mediaIv")
                    }.onFailure {
                        context.log.error("failed to decrypt meo", it)
                    }
                }

                if (mediaKey == null || mediaIv == null) {
                    context.log.error("missing media key or iv for ${cursor.getStringOrNull("download_url")}")
                    failed++
                    updateProgress()
                    continue
                }

                val entry = MemoriesEntry(
                    storyTitle = cursor.getStringOrNull("story_title") ?: "unknown",
                    createTime = cursor.getLongOrNull("create_time") ?: -1L,
                    mediaKey = mediaKey,
                    mediaIv = mediaIv,
                    downloadUrl = cursor.getStringOrNull("download_url") ?: continue
                )

                totalCount++

                scope.launch(downloadContext) {
                    var downloadedFile = File.createTempFile("memories", ".tmp", context.androidContext.cacheDir)

                    runCatching {
                        okHttpClient.newCall(
                            okhttp3.Request.Builder()
                                .url(entry.downloadUrl)
                                .build()
                        ).execute().use { response ->
                            val inputStream  = response.body.byteStream().let {
                                if (entry.mediaKey != null && entry.mediaIv != null) {
                                    val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                                    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(Base64.decode(entry.mediaKey), "AES"), IvParameterSpec(Base64.decode(entry.mediaIv)))
                                    CipherInputStream(it, cipher)
                                } else it
                            }

                            downloadedFile.outputStream().use { outputStream ->
                                inputStream.use { inputStream ->
                                    inputStream.copyTo(outputStream)
                                }
                            }

                            val fileType = FileType.fromFile(downloadedFile)

                            downloadedFile = File(
                                downloadedFile.parentFile,
                                "${entry.createTime}-${entry.downloadUrl.hashCode().absoluteValue.toString(16)}.${fileType.fileExtension}"
                            ).also {
                                downloadedFile.renameTo(it)
                            }

                            withContext(writeToZipContext) {
                                val zipEntry = ZipEntry("${if (folders) entry.folderName + "/" else ""}${downloadedFile.name}")
                                FileTime.fromMillis(entry.createTime).let {
                                    zipEntry.lastModifiedTime = it
                                    zipEntry.lastAccessTime = it
                                    zipEntry.creationTime = it
                                }
                                outputZipFile.apply {
                                    putNextEntry(zipEntry)
                                    downloadedFile.inputStream().use { it.copyTo(outputZipFile) }
                                    closeEntry()
                                    flush()
                                }
                                currentCount++
                                updateProgress()
                            }
                        }
                    }.onFailure {
                        context.log.error("failed to download ${entry.downloadUrl}", it)
                        failed++
                        updateProgress()
                    }
                    downloadedFile.delete()
                }.also { jobs.add(it) }
            }
        }

        jobs.joinAll()
        withContext(Dispatchers.IO) {
            outputZipFile.close()
        }
        val exportedPath = runCatching { outputTarget.finalize(outputZip) }
            .getOrElse { error ->
                context.log.error("Failed to finalize memories export", error)
                context.longToast(context.translation["toast_export_memories_failed"])
                return
            }
        if (outputZip.parentFile == context.androidContext.cacheDir) {
            outputZip.delete()
        }
        context.longToast(
            context.translation.format("toast_exported_to_path", "path" to exportedPath)
        )
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun ExporterDialogContent(database: SQLiteDatabase, onDismiss: () -> Unit) {
        val skin = LocalPurrfectSkin.current
        var exportJob by remember { mutableStateOf(null as Job?) }
        var exportFinished by remember { mutableStateOf(false) }
        var exportProgress by remember { mutableStateOf(Pair(0, 0)) } // progress, failed

        var dateRangeFilter by remember { mutableStateOf(false) }
        var sortByFolder by remember { mutableStateOf(false) }
        var includeMEO by remember { mutableStateOf(false) }
        val dateRangePickerState = rememberDateRangePickerState(
            initialSelectedStartDateMillis = OffsetDateTime.now().minusDays(8).toInstant().toEpochMilli(),
            initialSelectedEndDateMillis = Instant.now().toEpochMilli(),
            initialDisplayMode = DisplayMode.Picker
        )

        val totalCount = remember(dateRangePickerState.selectedStartDateMillis, dateRangePickerState.selectedEndDateMillis, dateRangeFilter) {
            val timeRange = dateRangePickerState.takeIf { dateRangeFilter }?.let {
                TimeRange(it.selectedStartDateMillis, it.selectedEndDateMillis)
            }

            database.rawQuery("SELECT COUNT(*) FROM memories_snap WHERE create_time >= ? AND create_time <= ? ", arrayOf(timeRange?.start?.toString() ?: "-1", timeRange?.end?.toString() ?: Long.MAX_VALUE.toString())).use {
                it.moveToFirst()
                it.getInt(0)
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(skin.textPrimary.copy(alpha = 0.08f))
                        .border(1.dp, skin.textPrimary.copy(alpha = 0.12f), RoundedCornerShape(18.dp))
                        .padding(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Download,
                        tint = skin.glowPrimary,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(28.dp),
                        contentDescription = null
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = translation.get("export_title"),
                        color = skin.textPrimary,
                        fontSize = 21.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Text(
                        text = translation.get("total_memories").replace("{count}", totalCount.toString()),
                        color = skin.textSecondary,
                        fontSize = 14.sp
                    )
                }
                Text(
                    text = "ZIP",
                    color = skin.textPrimary,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(skin.textPrimary.copy(alpha = 0.12f))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }

            if (exportJob != null) {
                BasicText(
                    text = translation.get("exporting_memories").replace("{failed}", exportProgress.second.toString()),
                    modifier = Modifier.fillMaxWidth(),
                    style = TextStyle(color = skin.textPrimary, fontSize = 14.sp, textAlign = TextAlign.Start)
                )
                ProgressBar(progress = exportProgress.first / 100f, skin = skin)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    SecondaryButton(text = translation.get("quit"), modifier = Modifier.weight(1f), onClick = {
                        exportJob?.cancel()
                        exportJob = null
                        onDismiss()
                    }, skin = skin)
                    if (exportFinished) {
                        PrimaryButton(text = translation.get("done"), modifier = Modifier.weight(1f), onClick = {
                            exportJob = null
                            onDismiss()
                        }, skin = skin)
                    }
                }
            } else {
                var dateRangeDialog by remember { mutableStateOf(false) }

                Column(
                    modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    SectionLabel(translation.get("date_range"), skin)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .background(skin.textPrimary.copy(alpha = 0.04f))
                            .border(1.dp, skin.textPrimary.copy(alpha = 0.08f), RoundedCornerShape(18.dp))
                            .clickable { dateRangeDialog = true }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            val formattedRange = remember(
                                dateRangePickerState.selectedStartDateMillis,
                                dateRangePickerState.selectedEndDateMillis
                            ) {
                                formatRange(
                                    dateRangePickerState.selectedStartDateMillis,
                                    dateRangePickerState.selectedEndDateMillis
                                )
                            }
                            Text(
                                text = formattedRange,
                                color = skin.textPrimary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(skin.textPrimary.copy(alpha = 0.08f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Checkbox(
                                checked = dateRangeFilter,
                                onCheckedChange = { dateRangeFilter = it },
                                colors = CheckboxDefaults.colors(
                                    checkedColor = skin.glowPrimary,
                                    checkmarkColor = skin.cardOverlayColor,
                                    uncheckedColor = skin.textPrimary.copy(alpha = 0.4f)
                                )
                            )
                        }
                    }

                    if (dateRangeDialog) {
                        Dialog(
                            onDismissRequest = { dateRangeDialog = false },
                            properties = DialogProperties(usePlatformDefaultWidth = false)
                        ) {
                            Surface(
                                modifier = Modifier.fillMaxWidth(0.95f),
                                shape = RoundedCornerShape(26.dp),
                                color = skin.cardOverlayColor,
                                border = BorderStroke(1.dp, skin.textPrimary.copy(alpha = 0.15f))
                            ) {
                                Column(
                                    modifier = Modifier.background(skin.cardOverlay).padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    Text(translation.get("date_range"), color = skin.textPrimary, fontWeight = FontWeight.Bold)
                                    DateRangePicker(
                                        state = dateRangePickerState,
                                        showModeToggle = true,
                                        colors = DatePickerDefaults.colors(
                                            containerColor = Color.Transparent,
                                            titleContentColor = skin.textPrimary,
                                            headlineContentColor = skin.textPrimary,
                                            selectedDayContainerColor = skin.glowPrimary,
                                            selectedDayContentColor = skin.cardOverlayColor,
                                            todayContentColor = skin.glowSecondary,
                                            todayDateBorderColor = skin.glowSecondary
                                        )
                                    )
                                    PrimaryButton(text = translation.get("ok"), onClick = { dateRangeDialog = false }, skin = skin)
                                }
                            }
                        }
                    }

                    SectionLabel(translation.get("sort_by_folder"), skin)
                    NeonToggle(
                        checked = sortByFolder,
                        onCheckedChange = { sortByFolder = it },
                        label = translation.get("sort_by_folder"),
                        skin = skin
                    )

                    SectionLabel(translation.get("include_my_eyes_only"), skin)
                    NeonToggle(
                        checked = includeMEO,
                        onCheckedChange = { includeMEO = it },
                        label = translation.get("include_my_eyes_only"),
                        skin = skin
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        SecondaryButton(text = translation.get("cancel"), modifier = Modifier.weight(1f), onClick = onDismiss, skin = skin)
                        PrimaryButton(text = translation.get("export"), modifier = Modifier.weight(1f), onClick = {
                            context.coroutineScope.launch {
                                exportMemories(
                                    scope = this,
                                    database = database,
                                    timeRange = dateRangePickerState.takeIf { dateRangeFilter }?.let {
                                        TimeRange(it.selectedStartDateMillis, it.selectedEndDateMillis)
                                    },
                                    folders = sortByFolder,
                                    includeMEO = includeMEO,
                                ) { progress, failed ->
                                    exportProgress = Pair(progress, failed)
                                }
                            }.also { exportJob = it }.invokeOnCompletion {
                                exportFinished = true
                            }
                        }, skin = skin)
                    }
                }
            }
        }
    }

    @Composable
    private fun ProgressBar(progress: Float, skin: me.eternal.purrfect.common.ui.theme.PurrfectColorSet) {
        val clamped = progress.coerceIn(0f, 1f)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp)
                .clip(RoundedCornerShape(50))
                .background(skin.textPrimary.copy(alpha = 0.08f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction = clamped)
                    .fillMaxHeight()
                    .background(Brush.horizontalGradient(listOf(skin.glowPrimary, skin.glowSecondary)))
            )
        }
    }

    @Composable
    private fun SectionLabel(text: String, skin: me.eternal.purrfect.common.ui.theme.PurrfectColorSet) {
        BasicText(
            text = text,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            style = TextStyle(
                color = skin.textSecondary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Start
            )
        )
    }

    @Composable
    private fun NeonToggle(
        checked: Boolean,
        onCheckedChange: (Boolean) -> Unit,
        label: String,
        skin: me.eternal.purrfect.common.ui.theme.PurrfectColorSet
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(skin.textPrimary.copy(alpha = 0.04f))
                .border(1.dp, skin.textPrimary.copy(alpha = 0.08f), RoundedCornerShape(18.dp))
                .clickable { onCheckedChange(!checked) }
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = skin.textPrimary,
                    checkedTrackColor = skin.glowPrimary,
                    uncheckedThumbColor = skin.textPrimary.copy(alpha = 0.6f),
                    uncheckedTrackColor = skin.textPrimary.copy(alpha = 0.1f)
                )
            )
            Column {
                BasicText(
                    text = label,
                    style = TextStyle(color = skin.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                )
                BasicText(
                    text = if (checked) "Enabled" else "Disabled",
                    style = TextStyle(color = skin.textSecondary, fontSize = 11.sp)
                )
            }
        }
    }

    @Composable
    private fun PrimaryButton(
        text: String,
        modifier: Modifier = Modifier,
        onClick: () -> Unit,
        skin: me.eternal.purrfect.common.ui.theme.PurrfectColorSet
    ) {
        Box(
            modifier = modifier
                .height(48.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(Brush.horizontalGradient(listOf(skin.glowPrimary, skin.glowSecondary)))
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            BasicText(
                text = text,
                style = TextStyle(color = skin.primaryButtonText, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            )
        }
    }

    @Composable
    private fun SecondaryButton(
        text: String,
        modifier: Modifier = Modifier,
        onClick: () -> Unit,
        skin: me.eternal.purrfect.common.ui.theme.PurrfectColorSet
    ) {
        Box(
            modifier = modifier
                .height(48.dp)
                .clip(RoundedCornerShape(999.dp))
                .border(1.dp, skin.textPrimary.copy(alpha = 0.16f), RoundedCornerShape(999.dp))
                .background(skin.textPrimary.copy(alpha = 0.05f))
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            BasicText(
                text = text,
                style = TextStyle(color = skin.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            )
        }
    }

    private fun formatRange(start: Long?, end: Long?): String {
        fun fmt(millis: Long?): String = millis?.let {
            Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().format(rangeFormatter)
        } ?: "Not set"
        return "${fmt(start)} - ${fmt(end)}"
    }

    override fun run() {
        context.coroutineScope.launch(Dispatchers.Main) {
            val database = runCatching {
                SQLiteDatabase.openDatabase(
                    context.androidContext.getDatabasePath("memories.db"),
                    OpenParams.Builder().setOpenFlags(SQLiteDatabase.OPEN_READONLY).build()
                )
            }.getOrNull()

            if (database == null) {
                context.longToast(context.translation["toast_open_memories_db_failed"])
                return@launch
            }

            createComposeAlertDialog(context.mainActivity!!) { alertDialog ->
                me.eternal.purrfect.core.ui.PurrfectOverlayTheme(null) {
                    val skin = LocalPurrfectSkin.current
                    val isAether = skin.id == "AETHER"
                    val shape = if (isAether) me.eternal.purrfect.common.ui.util.G2RoundedRectangle(26.dp) else RoundedCornerShape(26.dp)

                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth(0.92f)
                                .heightIn(max = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * 0.85f).dp)
                                .clip(shape)
                                .border(1.dp, skin.textPrimary.copy(alpha = 0.12f), shape),
                            shape = shape,
                            color = skin.cardOverlayColor,
                            tonalElevation = 0.dp,
                            shadowElevation = 18.dp
                        ) {
                            ExporterDialogContent(database) {
                                database.close()
                                alertDialog.dismiss()
                            }
                        }
                    }
                }
            }.apply {
                setOnDismissListener { database.close() }
                setCanceledOnTouchOutside(false)
                show()
            }
        }
    }
}
