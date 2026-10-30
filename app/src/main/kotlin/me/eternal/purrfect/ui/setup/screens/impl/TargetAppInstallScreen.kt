package me.eternal.purrfect.ui.setup.screens.impl

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.eternal.purrfect.common.TargetApp
import me.eternal.purrfect.common.bridge.wrapper.LocaleWrapper
import me.eternal.purrfect.setup.patch.AutoPatchServer
import me.eternal.purrfect.setup.patch.LSPatch
import me.eternal.purrfect.ui.manager.ManagerAssistantDialog
import me.eternal.purrfect.ui.manager.Routes
import me.eternal.purrfect.ui.manager.data.Updater
import me.eternal.purrfect.common.ui.theme.LocalPurrfectSkin
import me.eternal.purrfect.common.ui.util.G2RoundedRectangle
import me.eternal.purrfect.ui.manager.theme.WavyCircularProgressIndicator
import me.eternal.purrfect.ui.manager.theme.WavyProgress
import me.eternal.purrfect.ui.setup.SetupPreferences
import me.eternal.purrfect.ui.setup.screens.SetupScreen
import me.eternal.purrfect.ui.util.scaleOnPress
import okhttp3.OkHttpClient
import okhttp3.Request

enum class SetupInstallFlow {
    ROOT,
    PATCH,
    REPATCH
}

open class TargetAppInstallScreen(
    private val selectedAppsProvider: () -> Set<TargetApp>,
    private val flow: SetupInstallFlow,
    private val allowInstalledTarget: Boolean = false
) : SetupScreen() {
    private val autoPatchServer = AutoPatchServer()
    private val okHttpClient = OkHttpClient.Builder()
        .callTimeout(1, TimeUnit.HOURS)
        .connectTimeout(1, TimeUnit.HOURS)
        .readTimeout(1, TimeUnit.HOURS)
        .writeTimeout(1, TimeUnit.HOURS)
        .build()

    @Composable
    override fun Content() {
        val coroutineScope = rememberCoroutineScope()
        val translation = context.translation
        val stringPrefix = if (flow == SetupInstallFlow.ROOT) {
            "setup.root_install_app"
        } else {
            "setup.patch_app"
        }
        val installTargets = remember {
            setupInstallTargets(selectedAppsProvider())
        }
        var currentTargetIndex by rememberSaveable { mutableIntStateOf(0) }
        val currentTarget = installTargets[currentTargetIndex.coerceIn(0, installTargets.lastIndex)]
        val logs = remember { mutableStateListOf<String>() }
        @Suppress("DEPRECATION")
        val clipboard = LocalClipboardManager.current
        var progress by remember { mutableFloatStateOf(-1f) }
        var downloadedApkPath by rememberSaveable { mutableStateOf<String?>(null) }
        var downloadedReleaseTag by rememberSaveable { mutableStateOf<String?>(null) }
        var patchedApkPath by rememberSaveable { mutableStateOf<String?>(null) }
        val downloadedApk = remember(downloadedApkPath) { downloadedApkPath?.let(::File) }
        val patchedApk = remember(patchedApkPath) { patchedApkPath?.let(::File) }
        var isRunning by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        var operationStartedAt by rememberSaveable { mutableStateOf(0L) }
        var installVerified by rememberSaveable { mutableStateOf(false) }
        var installRequested by rememberSaveable { mutableStateOf(false) }
        var installWatcher by remember { mutableStateOf<Job?>(null) }
        var downloadFinished by rememberSaveable { mutableStateOf(false) }
        var completedTargetKeys by rememberSaveable { mutableStateOf("") }
        var pendingAutoStart by remember { mutableStateOf(false) }
        var showIssuesDialog by remember { mutableStateOf(false) }
        val assistantRoutes = remember { Routes(context) }
        val logPulse by rememberInfiniteTransition(label = "installLogPulse").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                tween(durationMillis = 2200, easing = FastOutSlowInEasing),
                RepeatMode.Reverse
            ),
            label = "installLogPulseValue"
        )

        fun completedTargets(): Set<String> {
            return completedTargetKeys.split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toSet()
        }

        fun allTargetsCompleted(): Boolean {
            val completed = completedTargets()
            return installTargets.all { it.targetApp.key in completed }
        }

        fun pushLog(message: String) {
            if (logs.size > 160) logs.removeAt(0)
            logs.add(message)
        }

        fun queueText(fromIndex: Int = currentTargetIndex + 1): String? {
            return installTargets
                .drop(fromIndex)
                .takeIf { it.isNotEmpty() }
                ?.joinToString(", ") { it.displayName }
        }

        fun pushQueueLog(fromIndex: Int = currentTargetIndex + 1) {
            queueText(fromIndex)?.let { queued ->
                pushLog(
                    translation.format(
                        "setup.install_queue.in_queue_status",
                        "app" to queued
                    )
                )
            }
        }

        fun isTargetInstalledAfter(target: SetupInstallTarget, timestamp: Long): Boolean {
            if (timestamp == 0L) return false
            return target.packageNames.any { packageName ->
                val info = runCatching {
                    context.androidContext.packageManager.getPackageInfo(packageName, 0)
                }.getOrNull()
                info?.lastUpdateTime?.let { it >= timestamp } == true
            }
        }

        fun isDefaultTargetPackageInstalled(target: SetupInstallTarget): Boolean {
            return runCatching {
                context.androidContext.packageManager.getPackageInfo(target.packageName, 0)
            }.isSuccess
        }

        fun resetTargetState() {
            progress = -1f
            downloadedApkPath = null
            downloadedReleaseTag = null
            patchedApkPath = null
            isRunning = false
            error = null
            operationStartedAt = 0L
            installVerified = false
            installRequested = false
            downloadFinished = false
        }

        fun completeCurrentTarget(target: SetupInstallTarget) {
            val completed = completedTargets()
            if (target.targetApp.key in completed) return

            SetupPreferences.addCompletedTarget(context.sharedPreferences, target.targetApp)
            if (installTargets.size == 1) {
                context.setActiveTargetApp(target.targetApp)
            }

            if (target.targetApp == TargetApp.REDDIT) {
                downloadedReleaseTag?.takeIf { it.isNotBlank() }?.let { releaseTag ->
                    context.sharedPreferences.edit()
                        .putString(Updater.REDDIT_INSTALLED_RELEASE_TAG_PREF, releaseTag)
                        .commit()
                    Updater.clearRedditUpdateCache()
                }
            }

            completedTargetKeys = (completed + target.targetApp.key).joinToString(",")
            listOfNotNull(downloadedApkPath, patchedApkPath)
                .map(::File)
                .forEach { runCatching { it.delete() } }

            val nextIndex = currentTargetIndex + 1
            if (nextIndex < installTargets.size) {
                val nextTarget = installTargets[nextIndex]
                pushLog(
                    translation.format(
                        "setup.install_queue.completed_next_status",
                        "current" to target.displayName,
                        "next" to nextTarget.displayName
                    )
                )
                currentTargetIndex = nextIndex
                resetTargetState()
                pendingAutoStart = true
            } else {
                pushLog(
                    translation.format(
                        "setup.install_queue.all_completed_status",
                        "apps" to installTargets.joinToString(", ") { it.displayName }
                    )
                )
                installVerified = true
                allowNext(true)
            }
        }

        fun startInstallWatcher(target: SetupInstallTarget = currentTarget) {
            if (operationStartedAt == 0L) return
            val watchedStartedAt = operationStartedAt
            installWatcher?.cancel()
            installWatcher = coroutineScope.launch {
                repeat(80) {
                    if (isTargetInstalledAfter(target, watchedStartedAt)) {
                        installVerified = true
                        pushLog(
                            translation.format(
                                "$stringPrefix.install_confirmed_log",
                                "app" to target.displayName
                            )
                        )
                        completeCurrentTarget(target)
                        return@launch
                    }
                    delay(1200)
                }
            }
        }

        suspend fun pushStatus(message: String) = withContext(Dispatchers.Main) { pushLog(message) }
        suspend fun setProgress(value: Float) = withContext(Dispatchers.Main) { progress = value }
        suspend fun setDownloadedReleaseTag(value: String?) = withContext(Dispatchers.Main) { downloadedReleaseTag = value }

        suspend fun downloadTargetFromAutoPatchServer(target: SetupInstallTarget): File? = withContext(Dispatchers.IO) {
            val latestApk = autoPatchServer.fetchLatestApk(target.targetApp) ?: return@withContext null
            if (target.targetApp == TargetApp.REDDIT) {
                setDownloadedReleaseTag(latestApk.tagName)
            }
            pushStatus(
                translation.format(
                    "$stringPrefix.download_recommended_status",
                    "app" to target.displayName,
                    "version" to latestApk.tagName
                )
            )

            okHttpClient.newCall(Request.Builder().url(latestApk.downloadUrl).build()).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val cacheDir = listOfNotNull(
                    context.activity?.externalCacheDir,
                    context.androidContext.externalCacheDir,
                    context.androidContext.cacheDir
                ).firstOrNull { it.exists() || it.mkdirs() } ?: return@withContext null
                val outputFile = File(cacheDir, latestApk.apkName)
                if (outputFile.parentFile?.exists() == false && outputFile.parentFile?.mkdirs() == false) {
                    return@withContext null
                }
                outputFile.outputStream().use { output ->
                    response.body?.byteStream()?.use { input ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var read: Int
                        var totalRead = 0L
                        val totalSize = response.body?.contentLength() ?: -1L
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            totalRead += read
                            if (totalSize > 0) setProgress(totalRead.toFloat() / totalSize.toFloat())
                        }
                    } ?: return@withContext null
                }
                setProgress(-1f)
                outputFile
            }
        }

        fun installApk(target: SetupInstallTarget, apk: File) {
            installRequested = true
            startInstallWatcher(target)
            val uri = FileProvider.getUriForFile(
                context.androidContext,
                "${context.androidContext.packageName}.fileprovider",
                apk
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.androidContext.startActivity(intent)
        }

        fun markAlreadyInstalled() {
            installRequested = false
            installVerified = true
            pushLog(
                translation.format(
                    "$stringPrefix.mark_installed_log",
                    "app" to currentTarget.displayName
                )
            )
            completeCurrentTarget(currentTarget)
        }

        fun startDownloadAndInstall(clearLogs: Boolean = true) {
            val target = currentTarget
            coroutineScope.launch(Dispatchers.Main) {
                isRunning = true
                allowNext(false)
                error = null
                progress = -1f
                downloadedApkPath = null
                downloadedReleaseTag = null
                patchedApkPath = null
                installVerified = false
                installRequested = false
                downloadFinished = false
                operationStartedAt = System.currentTimeMillis()
                if (clearLogs) logs.clear()
                pushLog(
                    translation.format(
                        "$stringPrefix.starting_log",
                        "app" to target.displayName
                    )
                )
                pushQueueLog()
                runCatching {
                    if (!allowInstalledTarget && flow != SetupInstallFlow.REPATCH && isDefaultTargetPackageInstalled(target)) {
                        pushStatus(
                            translation.format(
                                "$stringPrefix.uninstall_prompt_status",
                                "app" to target.displayName
                            )
                        )
                        throw IllegalStateException(
                            translation.format(
                                "$stringPrefix.uninstall_prompt_error",
                                "app" to target.displayName
                            )
                        )
                    }
                    val modulePath = if (flow != SetupInstallFlow.ROOT) {
                        context.androidContext.packageManager.getPackageInfo(
                            context.androidContext.packageName,
                            0
                        ).applicationInfo?.sourceDir
                            ?: throw IllegalStateException(translation["setup.patch_app.module_apk_not_found_error"])
                    } else {
                        null
                    }
                    pushStatus(
                        translation.format(
                            "$stringPrefix.fetching_apk_status",
                            "app" to target.displayName
                        )
                    )
                    val downloaded = downloadTargetFromAutoPatchServer(target)
                        ?: throw IllegalStateException(
                            translation.format(
                                "$stringPrefix.download_failed_error",
                                "app" to target.displayName
                            )
                        )
                    downloadedApkPath = downloaded.absolutePath
                    pushStatus(
                        translation.format(
                            "$stringPrefix.download_completed_status",
                            "app" to target.displayName,
                            "fileName" to downloaded.name
                        )
                    )
                    downloadFinished = true
                    if (flow == SetupInstallFlow.ROOT) {
                        pushStatus(
                            translation.format(
                                "$stringPrefix.launching_installer_status",
                                "app" to target.displayName
                            )
                        )
                        installApk(target, downloaded)
                    } else {
                        pushStatus(
                            translation.format(
                                "$stringPrefix.starting_patch_status",
                                "app" to target.displayName
                            )
                        )
                        val lsPatch = LSPatch(
                            context.androidContext,
                            mapOf(context.androidContext.packageName to File(modulePath!!)),
                            obfuscate = false,
                            printLog = { pushLog("[LSPatch] $it") }
                        )
                        val outputs = withContext(Dispatchers.IO) {
                            if (target.targetApp == TargetApp.REDDIT || target.targetApp == TargetApp.INSTAGRAM) {
                                mapOf("base.apk" to lsPatch.patchBaseApk(downloaded))
                            } else {
                                lsPatch.patchSplits(listOf(downloaded))
                            }
                        }
                        val patched = outputs["base.apk"] ?: outputs.values.firstOrNull()
                            ?: throw IllegalStateException(
                                translation.format(
                                    "$stringPrefix.patched_not_produced_error",
                                    "app" to target.displayName
                                )
                            )
                        patchedApkPath = patched.absolutePath
                        pushStatus(
                            translation.format(
                                "$stringPrefix.patched_ready_status",
                                "app" to target.displayName
                            )
                        )
                    }
                }.onFailure {
                    val message = it.message ?: it.toString()
                    error = message
                    it.stackTraceToString()
                        .lineSequence()
                        .filter { line -> line.isNotBlank() }
                        .forEach { line -> pushLog(line) }
                    pushStatus(
                        translation.format(
                            "$stringPrefix.failed_status",
                            "app" to target.displayName,
                            "message" to message
                        )
                    )
                }
                isRunning = false
                progress = -1f
            }
        }

        LaunchedEffect(Unit) {
            allowNext(false)
            if (logs.isEmpty()) {
                pushLog(
                    translation.format(
                        "$stringPrefix.ready_log",
                        "app" to currentTarget.displayName
                    )
                )
                pushQueueLog()
            }
        }

        LaunchedEffect(completedTargetKeys) {
            allowNext(allTargetsCompleted())
        }

        LaunchedEffect(installRequested, operationStartedAt, currentTargetIndex) {
            if (installRequested && operationStartedAt > 0) {
                startInstallWatcher()
            }
        }

        LaunchedEffect(operationStartedAt, currentTargetIndex) {
            if (operationStartedAt > 0 && isTargetInstalledAfter(currentTarget, operationStartedAt)) {
                installVerified = true
                completeCurrentTarget(currentTarget)
            }
        }

        LaunchedEffect(pendingAutoStart, currentTargetIndex) {
            if (pendingAutoStart) {
                pendingAutoStart = false
                delay(800)
                startDownloadAndInstall(clearLogs = false)
            }
        }

        val skin = LocalPurrfectSkin.current
        val accent = remember(skin) {
            Brush.linearGradient(
                listOf(
                    skin.glowSecondary,
                    skin.glowPrimary
                )
            )
        }
        val installableApk = if (flow == SetupInstallFlow.ROOT) downloadedApk else patchedApk
        val isDownloading = progress >= 0f
        val isPatching = flow != SetupInstallFlow.ROOT && isRunning && downloadFinished && !isDownloading

        if (showIssuesDialog) {
            ManagerAssistantDialog(
                context = context,
                routes = assistantRoutes,
                initialUserMessage = "I am facing an App not installed issue or Package appears to be invalid issue while installing ${currentTarget.displayName}. How do I fix it?",
                showImprovementLogging = false,
                onDismiss = { showIssuesDialog = false }
            )
        }

        SetupCard {
            StepTitle(
                title = translation.format("$stringPrefix.title", "app" to currentTarget.displayName),
                subtitle = if (installTargets.size > 1) {
                    translation.format(
                        "setup.install_queue.progress_title",
                        "current" to (currentTargetIndex + 1).toString(),
                        "total" to installTargets.size.toString(),
                        "next" to installTargets.getOrNull(currentTargetIndex + 1)
                            ?.let { "(Next: ${it.displayName})" }
                            .orEmpty()
                    )
                } else {
                    null
                },
                modifier = Modifier.align(Alignment.CenterHorizontally),
                textAlign = TextAlign.Center
            )
            if (flow != SetupInstallFlow.ROOT) {
                JingmatrixBadge(accent, translation)
            }
            val innerShape = if (skin.id == "AETHER") G2RoundedRectangle(24.dp) else RoundedCornerShape(24.dp)
            Surface(
                modifier = Modifier
                    .fillMaxWidth(),
                shape = innerShape,
                color = skin.textPrimary.copy(alpha = 0.03f),
                tonalElevation = 0.dp,
                border = BorderStroke(
                    1.dp,
                    Brush.linearGradient(
                        listOf(
                            skin.glowPrimary.copy(alpha = 0.4f),
                            skin.glowSecondary.copy(alpha = 0.35f)
                        )
                    )
                )
            ) {
                Column(
                    modifier = Modifier
                        .background(if (skin.id == "AETHER") skin.cardOverlayColor else skin.cardOverlayColor.copy(alpha = 0.28f))
                        .padding(horizontal = 18.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    AnimatedVisibility(visible = isRunning || progress >= 0f) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = when {
                                    isDownloading -> translation.format(
                                        "$stringPrefix.status_downloading",
                                        "app" to currentTarget.displayName,
                                        "percent" to (progress * 100).toInt().toString()
                                    )

                                    isPatching -> translation.format(
                                        "$stringPrefix.status_patching",
                                        "app" to currentTarget.displayName
                                    )

                                    flow == SetupInstallFlow.ROOT -> translation.format(
                                        "$stringPrefix.status_preparing",
                                        "app" to currentTarget.displayName
                                    )

                                    else -> translation.format(
                                        "$stringPrefix.status_initializing",
                                        "app" to currentTarget.displayName
                                    )
                                },
                                color = skin.textPrimary,
                                fontWeight = FontWeight.Medium
                            )
                            if (isDownloading) {
                                if (skin.id == "AETHER") {
                                    WavyProgress(
                                        progress = progress.coerceIn(0f, 1f),
                                        color = skin.glowPrimary,
                                        height = 8.dp,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                } else {
                                    LinearProgressIndicator(
                                        progress = { progress.coerceIn(0f, 1f) },
                                        color = skin.glowPrimary,
                                        trackColor = skin.textPrimary.copy(alpha = 0.12f),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(8.dp)
                                            .clip(RoundedCornerShape(12.dp))
                                    )
                                }
                            } else {
                                if (skin.id == "AETHER") {
                                    Box(
                                        modifier = Modifier.fillMaxWidth(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        WavyCircularProgressIndicator(
                                            color = skin.glowPrimary,
                                            strokeWidth = 6.dp
                                        )
                                    }
                                } else {
                                    LinearProgressIndicator(
                                        color = skin.glowPrimary,
                                        trackColor = skin.textPrimary.copy(alpha = 0.12f),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(8.dp)
                                            .clip(RoundedCornerShape(12.dp))
                                    )
                                }
                            }
                        }
                    }

                    LogsPanel(
                        logs = logs,
                        pulse = logPulse,
                        accent = accent,
                        translation = translation,
                        onCopy = {
                            clipboard.setText(AnnotatedString(logs.joinToString("\n")))
                            pushLog(translation["setup.install_queue.logs_copied"])
                        }
                    )

                    error?.let {
                        Text(
                            text = it,
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (allTargetsCompleted()) {
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = skin.textPrimary.copy(alpha = 0.06f),
                                border = BorderStroke(1.dp, skin.textPrimary.copy(alpha = 0.18f))
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 14.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.CheckCircle,
                                        contentDescription = null,
                                        tint = skin.textPrimary
                                    )
                                    Text(
                                        text = translation.format(
                                            "setup.install_queue.all_success",
                                            "apps" to installTargets.joinToString(", ") { it.displayName }
                                        ),
                                        color = skin.textPrimary,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        } else {
                            if (installableApk == null) {
                                GradientActionButton(
                                    label = translation.format(
                                        "$stringPrefix.start_button",
                                        "app" to currentTarget.displayName
                                    ),
                                    icon = Icons.Filled.Download,
                                    onClick = { startDownloadAndInstall() },
                                    enabled = !isRunning
                                )
                            }
                            if (installableApk != null) {
                                GradientActionButton(
                                    label = if (flow == SetupInstallFlow.REPATCH && currentTarget.targetApp == TargetApp.REDDIT) {
                                        "Update Reddit"
                                    } else {
                                        translation.format(
                                            "$stringPrefix.install_button",
                                            "app" to currentTarget.displayName
                                        )
                                    },
                                    icon = Icons.Filled.Verified,
                                    onClick = { installApk(currentTarget, installableApk) },
                                    enabled = true
                                )
                                if (flow != SetupInstallFlow.ROOT) {
                                    val issuesInteraction = remember { MutableInteractionSource() }
                                    Surface(
                                        shape = RoundedCornerShape(14.dp),
                                        color = skin.textPrimary.copy(alpha = 0.04f),
                                        border = BorderStroke(1.dp, skin.textPrimary.copy(alpha = 0.14f)),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .scaleOnPress(issuesInteraction)
                                            .clickable(
                                                interactionSource = issuesInteraction,
                                                indication = null
                                            ) { showIssuesDialog = true }
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.Info,
                                                contentDescription = null,
                                                tint = skin.textPrimary.copy(alpha = 0.9f)
                                            )
                                            Text(
                                                text = translation["setup.patch.issues_title"],
                                                color = skin.textPrimary,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }
                                    }
                                }
                                val manualInteraction = remember { MutableInteractionSource() }
                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = skin.textPrimary.copy(alpha = 0.04f),
                                    border = BorderStroke(1.dp, skin.textPrimary.copy(alpha = 0.14f)),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .scaleOnPress(manualInteraction)
                                        .clickable(
                                            interactionSource = manualInteraction,
                                            indication = null
                                        ) { markAlreadyInstalled() }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Info,
                                            contentDescription = null,
                                            tint = skin.textPrimary.copy(alpha = 0.9f)
                                        )
                                        Text(
                                            text = translation["setup.install_queue.already_installed_button"],
                                            color = skin.textPrimary,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun JingmatrixBadge(
    accent: Brush,
    translation: LocaleWrapper
) {
    val skin = LocalPurrfectSkin.current
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = skin.textPrimary.copy(alpha = 0.06f),
        border = BorderStroke(1.dp, skin.textPrimary.copy(alpha = 0.22f))
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(accent)
            ) {
                Icon(
                    imageVector = Icons.Filled.Info,
                    contentDescription = null,
                    tint = skin.textPrimary,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
            Text(
                text = translation["setup.patch.powered_by_label"],
                color = skin.textPrimary,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun GradientActionButton(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    val skin = LocalPurrfectSkin.current
    val interaction = remember { MutableInteractionSource() }
    val btnShape = if (skin.id == "AETHER") G2RoundedRectangle(16.dp) else RoundedCornerShape(16.dp)
    val gradient = Brush.horizontalGradient(listOf(skin.glowSecondary, skin.glowPrimary))
    val contentColor = if (enabled) {
        if (skin.glowPrimary.luminance() > 0.5f) Color.Black else Color.White
    } else skin.textPrimary

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(btnShape)
            .background(Color.Transparent)
            .scaleOnPress(interaction)
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            ),
        tonalElevation = 0.dp,
        color = Color.Transparent,
        border = BorderStroke(1.dp, skin.textPrimary.copy(alpha = 0.14f))
    ) {
        Box(
            modifier = Modifier
                .background(if (enabled) gradient else androidx.compose.ui.graphics.SolidColor(skin.textPrimary.copy(alpha = 0.08f)))
                .padding(vertical = 14.dp, horizontal = 16.dp)
                .clip(RoundedCornerShape(16.dp))
        ) {
            Row(
                modifier = Modifier.align(Alignment.CenterStart),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = contentColor
                )
                Text(
                    text = label,
                    color = contentColor,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp
                )
            }
        }
    }
}

@Composable
private fun LogsPanel(
    logs: List<String>,
    pulse: Float,
    accent: Brush,
    translation: LocaleWrapper,
    onCopy: () -> Unit
) {
    val skin = LocalPurrfectSkin.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    val animatedBrush = Brush.linearGradient(
        colors = listOf(
            skin.glowPrimary.copy(alpha = 0.18f + 0.1f * pulse),
            Color.Transparent,
            skin.glowSecondary.copy(alpha = 0.12f + 0.1f * (1 - pulse))
        ),
        start = Offset.Zero,
        end = Offset(400f * (0.6f + pulse), 260f * (0.4f + (1 - pulse)))
    )
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = if (skin.id == "AETHER") G2RoundedRectangle(18.dp) else RoundedCornerShape(18.dp),
        color = skin.textPrimary.copy(alpha = 0.03f),
        border = BorderStroke(1.dp, skin.textPrimary.copy(alpha = 0.12f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(animatedBrush)
                .background(if (skin.isDark) Color.Black.copy(alpha = 0.25f) else skin.textPrimary.copy(alpha = 0.25f))
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier
                        .clickable { expanded = !expanded },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = translation["setup.install_queue.logs_title"],
                        color = skin.textPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    Icon(
                        imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = null,
                        tint = skin.textPrimary
                    )
                }
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = skin.textPrimary.copy(alpha = 0.08f),
                    border = BorderStroke(1.dp, skin.textPrimary.copy(alpha = 0.16f)),
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onCopy() }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.ContentCopy,
                            contentDescription = null,
                            tint = skin.textPrimary,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = translation["setup.install_queue.copy_button"],
                            color = skin.textPrimary,
                            fontWeight = FontWeight.Medium,
                            fontSize = 12.sp
                        )
                    }
                }
            }
            AnimatedVisibility(
                visible = expanded,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    logs.forEach { line ->
                        Text(
                            text = translation.format(
                                "setup.install_queue.log_line_prefix",
                                "line" to line
                            ),
                            color = skin.textPrimary,
                            fontSize = 13.sp,
                            lineHeight = 16.sp
                        )
                    }
                }
            }
        }
    }
}
