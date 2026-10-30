package me.eternal.purrfect.ui.manager.pages

import android.content.Intent
import android.graphics.drawable.ColorDrawable
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavBackStackEntry
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.eternal.purrfect.download.DownloadProcessor
import me.eternal.purrfect.bridge.DownloadCallback
import me.eternal.purrfect.common.data.download.MediaDownloadSource
import me.eternal.purrfect.common.data.download.DownloadMetadata
import me.eternal.purrfect.common.ui.TopBarActionButton
import me.eternal.purrfect.common.ui.rememberAsyncMutableState
import me.eternal.purrfect.common.data.download.createNewFilePath
import me.eternal.purrfect.common.util.snap.RemoteMediaResolver
import me.eternal.purrfect.download.FFMpegProcessor
import me.eternal.purrfect.task.PendingTask
import me.eternal.purrfect.task.PendingTaskListener
import me.eternal.purrfect.task.Task
import me.eternal.purrfect.task.TaskStatus
import me.eternal.purrfect.task.TaskType
import me.eternal.purrfect.common.ui.theme.LocalPurrfectSkin
import me.eternal.purrfect.ui.manager.theme.aetherGlass
import me.eternal.purrfect.common.ui.util.G2RoundedRectangle
import me.eternal.purrfect.ui.manager.Routes
import me.eternal.purrfect.ui.manager.ManagerTheme
import me.eternal.purrfect.ui.util.*
import java.io.File

class TasksRootSection : Routes.Route() {
    enum class TaskTab {
        ACTIVE, SCHEDULED
    }

    internal var selectedTab by mutableStateOf(TaskTab.ACTIVE)
    internal var activeTasks by mutableStateOf(listOf<PendingTask>())
    internal var recentTasks = mutableStateListOf<Task>()
    internal val taskSelection = mutableStateListOf<Pair<Task, DocumentFile?>>()
    internal var lastFetchedTaskId: Long? by mutableStateOf(null)

    internal fun fetchActiveTasks(scope: CoroutineScope) {
        activeTasks = context.taskManager.getActiveTasks().values.toList()
    }

    internal fun fetchNewRecentTasks() {
        val tasks = context.taskManager.fetchStoredTasks(lastFetchedTaskId ?: Long.MAX_VALUE, limit = 20)
        if (tasks.isEmpty()) return
        
        lastFetchedTaskId = tasks.keys.last()
        val activeTaskHashes = activeTasks.map { it.task.hash }
        val existingHashes = recentTasks.map { it.hash }
        
        val newTasks = tasks.values.filter { it.hash !in activeTaskHashes && it.hash !in existingHashes }
        recentTasks.addAll(newTasks)
    }

    internal fun refreshRecentTasks() {
        val tasks = context.taskManager.fetchStoredTasks(Long.MAX_VALUE, limit = 20)
        val activeTaskHashes = activeTasks.map { it.task.hash }
        val newTasks = tasks.values.filter { it.hash !in activeTaskHashes }
        
        recentTasks.clear()
        recentTasks.addAll(newTasks)
        lastFetchedTaskId = tasks.keys.lastOrNull()
    }

    internal fun isRecentTasksInitialized() = true

    override val init: () -> Unit = {
        recentTasks = mutableStateListOf()
    }

    override val content: @Composable (NavBackStackEntry) -> Unit = { nav ->
        val themeId by produceState(
            initialValue = context.config.root.global.uiSettings.managerTheme.get()
        ) {
            while (true) {
                delay(300)
                value = context.config.root.global.uiSettings.managerTheme.get()
            }
        }
        val skin = LocalPurrfectSkin.current
        key(themeId, skin.id) {
            with(ManagerTheme.fromId(themeId).theme) {
                this@TasksRootSection.TasksScreen(nav)
            }
        }
    }

    @Composable
    internal fun TasksRootSection.TasksScreen(nav: NavBackStackEntry) {
        val skin = LocalPurrfectSkin.current
        val isAether = skin.id == "AETHER"
        val listState = rememberLazyListState()
        var controlsHeight by remember { mutableStateOf(100.dp) }

        val computedScrollOffset by remember {
            derivedStateOf {
                if (listState.firstVisibleItemIndex > 0) Motion.HEADER_MORPH_THRESHOLD.toInt()
                else listState.firstVisibleItemScrollOffset
            }
        }

        LaunchedEffect(listState) {
            androidx.compose.runtime.snapshotFlow { computedScrollOffset }.collect { offset ->
                val isAphelion = context.config.root.global.uiSettings.managerTheme.get() == "APHELION"
                if (isAphelion) {
                    routes.navigation?.globalScrollOffset = offset
                } else {
                    routes.navigation?.globalScrollOffset = 0
                }
            }
        }

        DisposableEffect(Unit) {
            onDispose {
                routes.navigation?.globalScrollOffset = 0
            }
        }

        LaunchedEffect(Unit) {
            refreshRecentTasks()
            while (true) {
                fetchActiveTasks(this)
                delay(2000)
            }
        }

        val shouldFetchMore by remember {
            derivedStateOf {
                val layoutInfo = listState.layoutInfo
                val totalItemsNumber = layoutInfo.totalItemsCount
                val lastVisibleItemIndex = (layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) + 1
                lastVisibleItemIndex > (totalItemsNumber - 5)
            }
        }

        LaunchedEffect(shouldFetchMore) {
            if (shouldFetchMore) {
                fetchNewRecentTasks()
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxSize().background(skin.backgroundGradient))

            Column(modifier = Modifier.fillMaxSize()) {
                Spacer(Modifier.height(controlsHeight))

                val containerShape = if (isAether) G2RoundedRectangle(28.dp) else RoundedCornerShape(22.dp)
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .aetherGlass(skin, if (isAether) 28.dp else 22.dp),
                    shape = containerShape,
                    color = Color.Transparent,
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp,
                    border = BorderStroke(1.dp, if (isAether) skin.laserBorder.copy(alpha = 0.35f) else skin.textPrimary.copy(alpha = 0.08f))
                ) {
                    Box(modifier = Modifier.background(skin.cardOverlayColor.copy(alpha = if (isAether) 0.1f else 0.04f), containerShape)) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(
                                start = 12.dp,
                                end = 12.dp,
                                top = 16.dp,
                                bottom = routes.bottomPadding + 16.dp
                            ),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            item(key = "auto_open_card") {
                                var queueItems by remember { mutableStateOf(listOf<Any>()) }
                                var processedCount by remember { mutableIntStateOf(0) }
                                
                                LaunchedEffect(Unit) {
                                    while (true) {
                                        runCatching {
                                            val autoOpen = context.bridgeService?.messagingBridge?.getAutoOpenInterface()
                                            processedCount = autoOpen?.processedCount ?: 0
                                            val items = autoOpen?.queueItems ?: emptyList()
                                            queueItems = items.mapNotNull { 
                                                runCatching { context.gson.fromJson(it, Map::class.java) }.getOrNull()
                                            }
                                        }
                                        kotlinx.coroutines.delay(2000)
                                    }
                                }
                                
                                if (queueItems.isNotEmpty() || processedCount > 0) {
                                    Surface(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                        shape = MaterialTheme.shapes.large,
                                        color = skin.textPrimary.copy(alpha = 0.05f),
                                        border = BorderStroke(1.dp, skin.textPrimary.copy(alpha = 0.1f))
                                    ) {
                                        Column(modifier = Modifier.padding(16.dp)) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(translation["auto_open_snaps.title"] ?: "Auto Open Snaps", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = skin.textPrimary)
                                                IconButton(onClick = { 
                                                    runCatching { context.bridgeService?.messagingBridge?.getAutoOpenInterface()?.reset() }
                                                }) {
                                                    Icon(Icons.Default.Refresh, null, modifier = Modifier.size(20.dp), tint = skin.textPrimary)
                                                }
                                            }
                                            Text(
                                                "${translation["auto_open_snaps.queue_size"] ?: "Queue"}: ${queueItems.size} \u00b7 ${translation["auto_open_snaps.processed_count"] ?: "Opened"}: $processedCount",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = skin.textPrimary.copy(alpha = 0.6f)
                                            )
                                        }
                                    }
                                }
                            }

                            if (activeTasks.isEmpty() && recentTasks.isEmpty()) {
                                item {
                                    AphelionTasksEmptyState(translation["no_tasks"])
                                }
                            }

                            // CONSOLIDATED SESSION VIEW: Group Auto-Open tasks by their persistent session task.
                            // Non-AutoOpen tasks (Downloads, etc.) remain as individual cards.
                            val groupedActiveTasks = activeTasks.distinctBy { it.task.hash }

                            items(groupedActiveTasks, key = { it.task.hash }) { pendingTask ->
                                val isAutoOpen = pendingTask.task.isAutoOpen
                                val pulseAnimation = rememberInfiniteTransition(label = "pulse")
                                val pulseAlpha by pulseAnimation.animateFloat(
                                    initialValue = 0.15f,
                                    targetValue = 0.45f,
                                    animationSpec = infiniteRepeatable(
                                        animation = tween(1200, easing = LinearEasing),
                                        repeatMode = RepeatMode.Reverse
                                    ),
                                    label = "alpha"
                                )

                                TaskCard(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .let { 
                                            if (isAutoOpen) {
                                                it.border(
                                                    width = 1.5.dp,
                                                    brush = Brush.linearGradient(
                                                        listOf(
                                                            skin.glowPrimary.copy(alpha = pulseAlpha),
                                                            skin.glowSecondary.copy(alpha = pulseAlpha)
                                                        )
                                                    ),
                                                    shape = MaterialTheme.shapes.large
                                                )
                                            } else it
                                        },
                                    task = pendingTask.task,
                                    pendingTask = pendingTask
                                )
                            }

                            items(recentTasks.filter { task -> activeTasks.none { it.task.hash == task.hash } }, key = { it.hash }) { task ->
                                TaskCard(
                                    modifier = Modifier.fillMaxWidth(),
                                    task = task
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            Column(modifier = Modifier.headerHeightTracker { controlsHeight = it }) {
                me.eternal.purrfect.ui.manager.components.FloatingTopBar(
                    title = translation["manager.routes.tasks"],
                    subtitle = translation["tasks_tagline"],
                    scrollOffset = computedScrollOffset,
                    enableMorph = true,
                    actions = {
                        topBarActions()
                    }
                )
            }
        }
    }

    internal fun mergeSelection(files: List<Pair<Task, DocumentFile>>) {
        val taskHash = System.nanoTime().toString(36)
        val firstTask = files.first().first
        val pendingTask = context.taskManager.createPendingTask(Task(
            type = TaskType.DOWNLOAD,
            title = firstTask.title,
            author = firstTask.author,
            hash = taskHash
        )).apply {
            status = TaskStatus.RUNNING
        }

        context.coroutineScope.launch(Dispatchers.IO) {
            val filesToMerge = files.mapNotNull { (_, documentFile) ->
                val tempFile = File.createTempFile("merge", ".tmp")
                context.androidContext.contentResolver.openInputStream(documentFile.uri)?.use { input ->
                    tempFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                tempFile
            }

            val mergedFile = File.createTempFile("merged", ".mp4")
            runCatching {
                context.shortToast(translation.format("merge_files_toast", "count" to filesToMerge.size.toString()))
                FFMpegProcessor.newFFMpegProcessor(context, pendingTask).execute(
                    FFMpegProcessor.Request(FFMpegProcessor.Action.MERGE_MEDIA, filesToMerge.map { it.absolutePath }, mergedFile)
                )
                DownloadProcessor(context, object: DownloadCallback.Default() {
                    override fun onSuccess(outputPath: String) {
                        context.log.verbose("Merged files to $outputPath")
                    }
                }).saveMediaToGallery(pendingTask, mergedFile, DownloadMetadata(
                    mediaIdentifier = taskHash,
                    outputPath = createNewFilePath(
                        context.config.root,
                        taskHash,
                        downloadSource = MediaDownloadSource.MERGED,
                        mediaAuthor = firstTask.author,
                        creationTimestamp = System.currentTimeMillis()
                    ),
                    mediaAuthor = firstTask.author,
                    downloadSource = MediaDownloadSource.MERGED.translate(context.translation),
                    iconUrl = null
                ))
            }.onFailure {
                context.log.error("Failed to merge files", it)
                pendingTask.fail(it.message ?: "Failed to merge files")
            }.onSuccess {
                pendingTask.success()
            }
            filesToMerge.forEach { it.delete() }
            mergedFile.delete()
        }.also {
            pendingTask.addListener(PendingTaskListener(onCancel = { it.cancel() }))
        }
    }

    internal fun clearTasks(alsoDeleteFiles: Boolean, scope: CoroutineScope) {
        if (taskSelection.isNotEmpty()) {
            taskSelection.forEach { (task, documentFile) ->
                scope.launch(Dispatchers.IO) {
                    context.taskManager.removeTask(task)
                    if (alsoDeleteFiles) documentFile?.delete()
                }
                recentTasks.remove(task)
            }
            activeTasks = activeTasks.filter { task -> !taskSelection.map { it.first }.contains(task.task) }
            taskSelection.clear()
        } else {
            scope.launch(Dispatchers.IO) { context.taskManager.clearAllTasks() }
            recentTasks.clear()
            activeTasks.forEach {
                runCatching { it.cancel() }.onFailure { throwable ->
                    context.log.error("Failed to cancel task $it", throwable)
                }
            }
            activeTasks = listOf()
        }
    }

    @Composable
    internal fun TaskDangerDialog(
        visible: Boolean,
        title: String,
        message: String,
        showDeleteFiles: Boolean,
        deleteFilesChecked: Boolean,
        onToggleDeleteFiles: (Boolean) -> Unit,
        onConfirm: () -> Unit,
        onDismiss: () -> Unit
    ) {
        if (!visible) return

        val skin = LocalPurrfectSkin.current
        val dialogShape = RoundedCornerShape(24.dp)
        val haptic = LocalHapticFeedback.current
        val borderGradient = remember {
            Brush.linearGradient(
                listOf(
                    skin.glowPrimary.copy(alpha = 0.65f),
                    skin.glowSecondary.copy(alpha = 0.55f)
                )
            )
        }

        Dialog(onDismissRequest = onDismiss) {
            Surface(
                shape = dialogShape,
                color = skin.textPrimary.copy(alpha = 0.06f),
                tonalElevation = 0.dp,
                shadowElevation = 20.dp,
                border = BorderStroke(1.dp, borderGradient)
            ) {
                Box(
                    modifier = Modifier
                        .background(skin.cardOverlay, dialogShape)
                ) {
                    Column(
                        modifier = Modifier
                            .padding(horizontal = 20.dp, vertical = 18.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Warning,
                                contentDescription = null,
                                tint = Color(0xFFFF6B9B),
                                modifier = Modifier.size(28.dp)
                            )
                            Text(
                                text = title,
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold),
                                color = skin.textPrimary
                            )
                        }

                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = skin.textSecondary
                        )

                        if (showDeleteFiles) {
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                color = skin.textPrimary.copy(alpha = 0.05f),
                                tonalElevation = 0.dp,
                                shadowElevation = 0.dp,
                                border = BorderStroke(1.dp, skin.textPrimary.copy(alpha = 0.08f))
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { 
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            onToggleDeleteFiles(!deleteFilesChecked) 
                                        }
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Checkbox(
                                        checked = deleteFilesChecked,
                                        onCheckedChange = { 
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            onToggleDeleteFiles(it) 
                                        },
                                        colors = CheckboxDefaults.colors(
                                            checkedColor = skin.glowPrimary,
                                            uncheckedColor = skin.textPrimary,
                                            checkmarkColor = skin.cardOverlayColor
                                        )
                                    )
                                    Column {
                                        Text(
                                            text = context.translation["delete_files_option"] ?: "Delete Files",
                                            color = skin.textPrimary,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Text(
                                            text = context.translation["delete_files_option_hint"] ?: "Also remove downloaded files",
                                            color = skin.textSecondary,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)
                        ) {
                            Button(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onDismiss()
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = skin.textPrimary.copy(alpha = 0.08f),
                                    contentColor = skin.textPrimary
                                )
                            ) {
                                Text(context.translation["button.negative"] ?: "Cancel")
                            }
                            Button(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onConfirm()
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = skin.glowPrimary.copy(alpha = 0.34f),
                                    contentColor = skin.textPrimary
                                )
                            ) {
                                Text(context.translation["button.positive"] ?: "Delete")
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    internal fun TasksEmptyState(text: String) {
        val skin = LocalPurrfectSkin.current
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 60.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = skin.textPrimary.copy(alpha = 0.08f),
                tonalElevation = 0.dp,
                shadowElevation = 0.dp,
                border = BorderStroke(1.dp, skin.textPrimary.copy(alpha = 0.12f))
            ) {
                Box(
                    modifier = Modifier
                        .size(58.dp)
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    skin.glowPrimary.copy(alpha = 0.32f),
                                    skin.glowSecondary.copy(alpha = 0.28f)
                                )
                            ),
                            CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = text,
                        tint = skin.textPrimary
                    )
                }
            }
            Text(
                text = text,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
                color = skin.textPrimary
            )
        }
    }

    @Composable
    internal fun AphelionTasksEmptyState(text: String) {
        TasksEmptyState(text)
    }

    override val topBarActions: @Composable (RowScope.() -> Unit) = {
        var showConfirmDialog by remember { mutableStateOf(false) }
        val coroutineScope = rememberCoroutineScope()
        val haptic = LocalHapticFeedback.current
        val skin = LocalPurrfectSkin.current

        if (taskSelection.size > 1) {
            val canMergeSelection by rememberAsyncMutableState(defaultValue = false, keys = arrayOf(taskSelection.size)) {
                taskSelection.all { it.second?.type?.contains("video") == true }
            }

            if (canMergeSelection) {
                TopBarActionButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        mergeSelection(taskSelection.toList().also {
                            taskSelection.clear()
                        }.map { it.first to it.second!! })
                    },
                    icon = Icons.Filled.Merge,
                    text = translation["merge_button"]
                )
            }
        }

        IconButton(onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            showConfirmDialog = true
        }) {
            Icon(Icons.Filled.Delete, contentDescription = translation["clear_button_description"], tint = skin.textPrimary)
        }

        if (showConfirmDialog) {
            var alsoDeleteFiles by remember { mutableStateOf(false) }
            val isSelection = taskSelection.isNotEmpty()
            val titleText = if (isSelection) {
                translation.format("remove_selected_tasks_confirm", "count" to taskSelection.size.toString())
            } else {
                translation["remove_all_tasks_confirm"] ?: "Remove all tasks?"
            }
            val messageText = if (isSelection) (translation["remove_selected_tasks_title"] ?: "Clear selected tasks?") else (translation["remove_all_tasks_title"] ?: "Clear all task history?")

            TaskDangerDialog(
                visible = showConfirmDialog,
                title = titleText,
                message = messageText,
                showDeleteFiles = isSelection,
                deleteFilesChecked = alsoDeleteFiles,
                onToggleDeleteFiles = { alsoDeleteFiles = it },
                onConfirm = {
                    showConfirmDialog = false
                    clearTasks(alsoDeleteFiles, coroutineScope)
                },
                onDismiss = { showConfirmDialog = false }
            )
        }
    }

    @Composable
    internal fun TaskCard(modifier: Modifier, task: Task, pendingTask: PendingTask? = null) {
        val skin = LocalPurrfectSkin.current
        var taskStatus by remember { mutableStateOf(task.status) }
        var taskProgressLabel by remember { mutableStateOf<String?>(null) }
        var taskProgress by remember { mutableIntStateOf(-1) }
        val isSelected by remember { derivedStateOf { taskSelection.any { it.first == task } } }
        val haptic = LocalHapticFeedback.current

        var documentFileMimeType by remember { mutableStateOf("") }
        var isDocumentFileReadable by remember { mutableStateOf(true) }
        
        val docVal = task.extra?.toUri()
        val documentFile by rememberAsyncMutableState(defaultValue = null as DocumentFile?, keys = arrayOf(taskStatus.name)) {
            if (docVal == null) null
            else DocumentFile.fromSingleUri(context.androidContext, docVal)?.apply {
                documentFileMimeType = type ?: ""
                isDocumentFileReadable = canRead()
            }
        }


        val listener = remember { PendingTaskListener(
            onStateChange = {
                taskStatus = it
            },
            onProgress = { label, progress ->
                taskProgressLabel = label
                taskProgress = progress
            }
        ) }

        LaunchedEffect(Unit) {
            pendingTask?.addListener(listener)
        }

        DisposableEffect(Unit) {
            onDispose {
                pendingTask?.removeListener(listener)
            }
        }

        fun toggleSelection() {
            if (isSelected) {
                taskSelection.removeIf { it.first == task }
                return
            }
            taskSelection.add(task to documentFile)
        }

        fun openFile() {
            if (!isDocumentFileReadable || documentFile == null) return
            runCatching {
                context.androidContext.startActivity(Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(documentFile!!.uri, documentFile!!.type)
                    flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
                })
            }.onFailure {
                context.log.error("Failed to open file ${documentFile?.uri}", it)
                context.shortToast(translation["failed_to_open_file"])
            }
        }

        val isActive = pendingTask != null && !taskStatus.isFinalStage()
        val cardModifier = modifier
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        if (taskSelection.isNotEmpty()) {
                            toggleSelection()
                            return@detectTapGestures
                        }
                        openFile()
                    },
                    onLongPress = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        if (taskSelection.isNotEmpty()) {
                            openFile()
                            return@detectTapGestures
                        }
                        toggleSelection()
                    }
                )
            }
            .let {
                if (isSelected) {
                    it
                        .border(2.dp, skin.glowSecondary, MaterialTheme.shapes.large)
                        .clip(MaterialTheme.shapes.large)
                } else it
            }

        val chipLabel = when {
            isActive -> translation.getOrNull("task_sending") ?: "Sending"
            taskStatus == TaskStatus.SUCCESS -> null
            taskStatus == TaskStatus.FAILURE -> translation.getOrNull("task_failed") ?: "Failed"
            taskStatus == TaskStatus.CANCELLED -> translation.getOrNull("task_cancelled") ?: "Cancelled"
            else -> taskStatus.name.lowercase().replaceFirstChar { it.titlecase() }
        }
        val chipIcon = when {
            isActive -> null
            taskStatus == TaskStatus.SUCCESS -> Icons.Filled.Check
            taskStatus == TaskStatus.FAILURE -> Icons.Filled.WarningAmber
            taskStatus == TaskStatus.CANCELLED -> Icons.Filled.Cancel
            else -> Icons.Filled.Info
        }
        val chipColors = when {
            isActive -> AssistChipDefaults.assistChipColors(
                containerColor = skin.textPrimary.copy(alpha = 0.08f),
                labelColor = skin.textPrimary
            )
            taskStatus == TaskStatus.SUCCESS -> AssistChipDefaults.assistChipColors()
            taskStatus == TaskStatus.FAILURE -> AssistChipDefaults.assistChipColors(
                containerColor = Color(0xFFFF6B9B).copy(alpha = 0.18f),
                labelColor = skin.textPrimary
            )
            taskStatus == TaskStatus.CANCELLED -> AssistChipDefaults.assistChipColors(
                containerColor = skin.textPrimary.copy(alpha = 0.06f),
                labelColor = skin.textSecondary
            )
            else -> AssistChipDefaults.assistChipColors()
        }

        Surface(
            modifier = cardModifier,
            shape = MaterialTheme.shapes.large,
            color = skin.textPrimary.copy(alpha = 0.06f),
            border = BorderStroke(1.dp, skin.textPrimary.copy(alpha = 0.1f)),
            tonalElevation = 0.dp,
            shadowElevation = 0.dp
        ) {
            Box(
                modifier = Modifier
                    .background(skin.cardOverlay)
                    .padding(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = skin.textPrimary.copy(alpha = 0.08f),
                        tonalElevation = 0.dp,
                        modifier = Modifier.size(56.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.linearGradient(
                                        listOf(
                                            skin.glowPrimary.copy(alpha = 0.22f),
                                            skin.glowSecondary.copy(alpha = 0.18f)
                                        )
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            documentFile?.let { doc ->
                                if (documentFileMimeType.contains("image")) {
                                    Image(
                                        painter = rememberAsyncImagePainter(
                                            ImageRequest.Builder(LocalContext.current)
                                                .data(doc.uri)
                                                .size(120)
                                                .crossfade(true)
                                                .build()
                                        ),
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(18.dp))
                                    )
                                } else {
                                    Icon(
                                        imageVector = when {
                                            !isDocumentFileReadable -> Icons.Filled.DeleteOutline
                                            documentFileMimeType.contains("video") -> Icons.Filled.Videocam
                                            documentFileMimeType.contains("audio") -> Icons.Filled.MusicNote
                                            else -> Icons.Filled.FileCopy
                                        },
                                        contentDescription = null,
                                        tint = skin.textPrimary,
                                        modifier = Modifier.size(28.dp)
                                    )
                                }
                            } ?: run {
                                Icon(
                                    imageVector = when (task.type) {
                                        TaskType.DOWNLOAD -> Icons.Filled.Download
                                        TaskType.CHAT_ACTION -> Icons.Filled.ChatBubble
                                        TaskType.SCHEDULED_SEND -> Icons.Filled.Schedule
                                    },
                                    contentDescription = null,
                                    tint = skin.textPrimary,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                        }
                    }

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = task.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = skin.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        task.author?.takeIf { it != "null" }?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = skin.textSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        if (isActive) {
                            taskProgressLabel?.let {
                                val labelText = if (task.isAutoOpen) {
                                    // Live Metrics: Show Snaps/min and session total
                                    val sessionTimeMins = (System.currentTimeMillis() - 0L) / 60000.0 // Placeholder for session start
                                    val speed = if (sessionTimeMins > 0.1) String.format("%.1f", 0 / sessionTimeMins) else "0.0"
                                    "$it • $speed snaps/min"
                                } else it
                                Text(labelText, style = MaterialTheme.typography.labelSmall, color = skin.textPrimary)
                            }
                            if (taskProgress != -1) {
                                LinearProgressIndicator(
                                    progress = { taskProgress.toFloat() / 100f },
                                    strokeCap = StrokeCap.Round,
                                    modifier = Modifier.fillMaxWidth().height(6.dp),
                                    color = skin.glowSecondary,
                                    trackColor = skin.textPrimary.copy(alpha = 0.12f)
                                )
                            }
                        } else {
                            chipLabel?.let { label ->
                                AssistChip(
                                    onClick = {},
                                    enabled = false,
                                    leadingIcon = chipIcon?.let { { Icon(it, null, modifier = Modifier.size(14.dp)) } },
                                    label = { Text(label, fontSize = 11.sp) },
                                    colors = chipColors,
                                    shape = RoundedCornerShape(10.dp)
                                )
                            }
                        }
                    }

                    if (isActive) {
                        IconButton(onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            pendingTask?.cancel()
                        }) {
                            Icon(Icons.Filled.Close, null, tint = Color(0xFFFF6B9B))
                        }
                    } else if (taskStatus == TaskStatus.SUCCESS) {
                        Icon(Icons.Filled.Check, null, tint = skin.glowSecondary)
                    }
                }
            }
        }
    }

    @Composable
    internal fun TaskTabSwitcher(
        selectedTab: TaskTab,
        onTabSelected: (TaskTab) -> Unit,
        activeCount: Int,
        scheduledCount: Int
    ) {
        val skin = LocalPurrfectSkin.current
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            TaskTab.entries.forEach { tab ->
                val selected = selectedTab == tab
                val count = if (tab == TaskTab.ACTIVE) activeCount else scheduledCount
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = if (selected) skin.textPrimary.copy(alpha = 0.12f) else skin.textPrimary.copy(alpha = 0.06f),
                    border = if (selected) BorderStroke(1.dp, Brush.linearGradient(listOf(skin.glowPrimary, skin.glowSecondary))) else BorderStroke(
                        1.dp,
                        skin.textPrimary.copy(alpha = 0.12f)
                    ),
                    modifier = Modifier.weight(1f)
                ) {
                    Row(
                        modifier = Modifier
                            .clickable { onTabSelected(tab) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (tab == TaskTab.ACTIVE) Icons.Filled.Timer else Icons.Filled.Schedule,
                            contentDescription = null,
                            tint = skin.textPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = if (tab == TaskTab.ACTIVE) (context.translation["tasks_tab_active"] ?: "Active") else (context.translation["tasks_tab_scheduled"] ?: "Scheduled"),
                            color = skin.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }
    }

    @Composable
    internal fun TasksHeader(
        selectedTab: TaskTab,
        onTabSelected: (TaskTab) -> Unit,
        activeCount: Int,
        scheduledCount: Int,
        runningCount: Int,
        subtitle: String,
        onClear: () -> Unit,
        onMerge: () -> Unit,
        canMerge: Boolean
    ) {
        val skin = LocalPurrfectSkin.current
        val haptic = LocalHapticFeedback.current
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp)
                .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()),
            shape = RoundedCornerShape(26.dp),
            color = skin.textPrimary.copy(alpha = 0.07f),
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
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = context.translation["manager.routes.tasks"] ?: "Tasks",
                            color = skin.textPrimary,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 18.sp
                        )
                        Text(
                            text = subtitle,
                            color = skin.textSecondary,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    
                    Row(
                        modifier = Modifier.wrapContentWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (canMerge) {
                            Surface(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onMerge()
                                },
                                shape = RoundedCornerShape(18.dp),
                                color = skin.glowPrimary.copy(alpha = 0.2f),
                                border = BorderStroke(1.dp, skin.glowPrimary.copy(alpha = 0.4f))
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(Icons.Filled.Merge, contentDescription = context.translation["tasks_merge_button"], tint = skin.textPrimary, modifier = Modifier.size(16.dp))
                                    Text(context.translation["tasks_merge_button"] ?: "Merge", color = skin.textPrimary, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                }
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(18.dp),
                            color = skin.textPrimary.copy(alpha = 0.08f),
                            border = BorderStroke(1.dp, skin.textPrimary.copy(alpha = 0.16f))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.PlaylistAddCheckCircle,
                                    contentDescription = null,
                                    tint = skin.textPrimary
                                )
                                Text(
                                    text = (context.translation["tasks_running_count"] ?: "{count} running")
                                        .replace("{count}", runningCount.toString()),
                                    color = skin.textPrimary,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 12.sp
                                )
                            }
                        }

                        IconButton(onClick = onClear) {
                            Icon(
                                imageVector = Icons.Filled.DeleteSweep,
                                contentDescription = context.translation["tasks_clear_button_description"],
                                tint = skin.textPrimary
                            )
                        }
                    }
                }
                
                TaskTabSwitcher(
                    selectedTab = selectedTab,
                    onTabSelected = onTabSelected,
                    activeCount = activeCount,
                    scheduledCount = scheduledCount
                )
            }
        }
    }
}
