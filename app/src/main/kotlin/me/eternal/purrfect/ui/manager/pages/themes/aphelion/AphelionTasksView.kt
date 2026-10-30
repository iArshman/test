package me.eternal.purrfect.ui.manager.pages.themes.aphelion

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
import kotlinx.coroutines.launch
import me.eternal.purrfect.common.ui.TopBarActionButton
import me.eternal.purrfect.common.ui.rememberAsyncMutableState
import me.eternal.purrfect.task.*
import me.eternal.purrfect.ui.manager.Routes
import me.eternal.purrfect.ui.manager.pages.TasksRootSection
import me.eternal.purrfect.common.ui.theme.LocalPurrfectSkin
import me.eternal.purrfect.common.ui.theme.PurrfectPalette
import me.eternal.purrfect.ui.util.OnLifecycleEvent
import me.eternal.purrfect.ui.util.coil.cacheKey
import me.eternal.purrfect.ui.util.scaleOnPress
import me.eternal.purrfect.ui.util.Motion
import me.eternal.purrfect.ui.manager.pages.TasksRootSection.TaskTab
import me.eternal.purrfect.ui.util.headerHeightTracker
import kotlinx.coroutines.delay

private object TasksSkinPalette {
    @Composable
    private fun isAphelion(): Boolean {
        val context = LocalContext.current
        return remember(context) { 
            me.eternal.purrfect.SharedContextHolder.remote(context).config.root.global.uiSettings.managerTheme.get() == "APHELION"
        }
    }

    val glowPrimary: Color @Composable get() = if (isAphelion()) LocalPurrfectSkin.current.glowPrimary else PurrfectPalette.glowPrimary
    val glowSecondary: Color @Composable get() = if (isAphelion()) LocalPurrfectSkin.current.glowSecondary else PurrfectPalette.glowSecondary
    val backgroundGradient: Brush @Composable get() = if (isAphelion()) LocalPurrfectSkin.current.backgroundGradient else PurrfectPalette.backgroundGradient
    val cardOverlay: Brush @Composable get() = if (isAphelion()) LocalPurrfectSkin.current.cardOverlay else PurrfectPalette.cardOverlay
    val textPrimary: Color @Composable get() = if (isAphelion()) LocalPurrfectSkin.current.textPrimary else PurrfectPalette.textPrimary
    val textSecondary: Color @Composable get() = if (isAphelion()) LocalPurrfectSkin.current.textSecondary else PurrfectPalette.textSecondary
    val cardOverlayColor: Color @Composable get() = if (isAphelion()) LocalPurrfectSkin.current.cardOverlayColor else PurrfectPalette.cardOverlayColor
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TasksRootSection.AphelionTasksScreen(nav: NavBackStackEntry) {
    val scrollState = rememberLazyListState()
    val haptic = LocalHapticFeedback.current
    var controlsHeight by remember { mutableStateOf(100.dp) }

    LaunchedEffect(scrollState.firstVisibleItemScrollOffset, scrollState.firstVisibleItemIndex) {
        val offset = if (scrollState.firstVisibleItemIndex > 0) Motion.HEADER_MORPH_THRESHOLD.toInt() else scrollState.firstVisibleItemScrollOffset
        routes.navigation?.globalScrollOffset = offset
    }

    val scope = rememberCoroutineScope()
    var showConfirmDialog by remember { mutableStateOf(false) }
    var alsoDeleteFiles by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        fetchActiveTasks(this)
    }

    DisposableEffect(Unit) {
        onDispose {
            taskSelection.clear()
        }
    }

    OnLifecycleEvent { _, event ->
        if (event == Lifecycle.Event.ON_RESUME) {
            fetchActiveTasks(scope)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(TasksSkinPalette.backgroundGradient)
    ) {
        val scrollOffset = routes.navigation?.globalScrollOffset ?: 0
        val focusFactor = (scrollOffset.toFloat() / Motion.HEADER_MORPH_THRESHOLD).coerceIn(0f, 1f)
        val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        
        val containerTopPadding = androidx.compose.ui.unit.lerp(statusBarHeight + 2.dp, 0.dp, focusFactor)
        val topCorners = androidx.compose.ui.unit.lerp(28.dp, 0.dp, focusFactor)

        val subtitle = if (activeTasks.isNotEmpty()) {
            translation.format(
                "summary_active",
                "active" to activeTasks.size.toString(),
                "recent" to recentTasks.size.toString()
            )
        } else {
            translation.format(
                "summary_idle",
                "recent" to recentTasks.size.toString()
            )
        }

        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp)
                .padding(top = containerTopPadding),
            shape = RoundedCornerShape(topStart = topCorners, topEnd = topCorners, bottomStart = 0.dp, bottomEnd = 0.dp),
            color = TasksSkinPalette.textPrimary.copy(alpha = 0.04f),
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            border = BorderStroke(1.dp, TasksSkinPalette.textPrimary.copy(alpha = 0.08f))
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(top = controlsHeight - 44.dp)) {
                Surface(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(14.dp),
                    color = TasksSkinPalette.textPrimary.copy(alpha = 0.05f),
                    border = BorderStroke(1.dp, TasksSkinPalette.textPrimary.copy(alpha = 0.08f))
                ) {
                    Row(
                        modifier = Modifier.padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        TaskTab.entries.forEach { tab ->
                            val isSelected = selectedTab == tab
                            val backgroundAlpha by animateFloatAsState(if (isSelected) 0.12f else 0f, label = "tabBg")
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(38.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(TasksSkinPalette.textPrimary.copy(alpha = backgroundAlpha))
                                    .clickable { 
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        selectedTab = tab 
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = if (tab == TaskTab.ACTIVE) (translation["tasks_tab_active"] ?: "Active") else (translation["tasks_tab_scheduled"] ?: "Scheduled"),
                                    color = if (isSelected) TasksSkinPalette.textPrimary else TasksSkinPalette.textPrimary.copy(alpha = 0.5f),
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }
                }

                val activeList = activeTasks.filter { it.task.type != TaskType.SCHEDULED_SEND }
                val recentList = recentTasks.filter { task -> 
                    task.type != TaskType.SCHEDULED_SEND && activeList.none { it.task.hash == task.hash } 
                }

                val scheduledActive = activeTasks.filter { it.task.type == TaskType.SCHEDULED_SEND }
                val scheduledRecent = recentTasks.filter { task -> 
                    task.type == TaskType.SCHEDULED_SEND && scheduledActive.none { it.task.hash == task.hash } 
                }

                LazyColumn(
                    state = scrollState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 10.dp,
                        end = 10.dp,
                        top = 8.dp,
                        bottom = routes.bottomPadding + 20.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    if (selectedTab == TaskTab.ACTIVE) {
                        item(key = "auto_open_card") {
                            var queueItems by remember { mutableStateOf(listOf<Any>()) }
                            var processedCount by remember { mutableIntStateOf(0) }
                            
                            LaunchedEffect(Unit) {
                                while (true) {
                                    runCatching {
                                        val autoOpen = context.bridgeService?.messagingBridge?.autoOpenInterface
                                        processedCount = autoOpen?.processedCount ?: 0
                                        val items = autoOpen?.queueItems ?: emptyList()
                                        queueItems = items.mapNotNull { 
                                            runCatching { context.gson.fromJson(it, Map::class.java) }.getOrNull()
                                        }
                                    }
                                    delay(2000)
                                }
                            }
                            
                            val queueSize = queueItems.size
                            
                            if (queueSize > 0 || processedCount > 0) {
                                var isExpanded by remember { mutableStateOf(false) }
                                Surface(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                                    shape = RoundedCornerShape(18.dp),
                                    color = TasksSkinPalette.textPrimary.copy(alpha = 0.06f),
                                    border = BorderStroke(1.dp, TasksSkinPalette.textPrimary.copy(alpha = 0.1f)),
                                    onClick = { 
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        isExpanded = !isExpanded 
                                    }
                                ) {
                                    Column(modifier = Modifier.padding(14.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(Icons.Default.AutoFixHigh, null, tint = TasksSkinPalette.glowSecondary, modifier = Modifier.size(20.dp))
                                                Spacer(Modifier.width(10.dp))
                                                Text(translation["auto_open_snaps.title"] ?: "Auto Open Snaps", fontWeight = FontWeight.Bold, color = TasksSkinPalette.textPrimary)
                                            }
                                            Icon(
                                                if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                                null,
                                                tint = TasksSkinPalette.textPrimary.copy(alpha = 0.5f)
                                            )
                                        }
                                        Row(modifier = Modifier.padding(top = 4.dp, start = 30.dp)) {
                                            Text(
                                                "${translation["auto_open_snaps.queue_size"] ?: "Queue"}: $queueSize \u00b7 ${translation["auto_open_snaps.processed_count"] ?: "Opened"}: $processedCount",
                                                fontSize = 12.sp,
                                                color = TasksSkinPalette.textPrimary.copy(alpha = 0.6f)
                                            )
                                        }
                                        
                                        AnimatedVisibility(
                                            visible = isExpanded,
                                            enter = expandVertically() + fadeIn(),
                                            exit = shrinkVertically() + fadeOut()
                                        ) {
                                            Column(modifier = Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                                queueItems.forEach { rawItem ->
                                                    val item = rawItem as? Map<String, String> ?: return@forEach
                                                    Row(
                                                        modifier = Modifier.fillMaxWidth().background(TasksSkinPalette.textPrimary.copy(alpha = 0.03f), RoundedCornerShape(8.dp)).padding(8.dp),
                                                        horizontalArrangement = Arrangement.SpaceBetween,
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Column {
                                                            Text(item["senderInfo"] ?: "", fontSize = 13.sp, color = TasksSkinPalette.textPrimary, fontWeight = FontWeight.Medium)
                                                            Text(item["contentType"] ?: "", fontSize = 11.sp, color = TasksSkinPalette.textPrimary.copy(alpha = 0.5f))
                                                        }
                                                        Text(item["conversationType"] ?: "", fontSize = 10.sp, color = TasksSkinPalette.glowSecondary.copy(alpha = 0.7f))
                                                    }
                                                }
                                                
                                                if (processedCount > 0 || queueSize > 0) {
                                                    OutlinedButton(
                                                        onClick = { 
                                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                            runCatching { context.bridgeService?.messagingBridge?.autoOpenInterface?.reset() }
                                                        },
                                                        modifier = Modifier.fillMaxWidth().height(36.dp),
                                                        shape = RoundedCornerShape(10.dp),
                                                        border = BorderStroke(1.dp, Color.Red.copy(alpha = 0.3f)),
                                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.Red.copy(alpha = 0.7f))
                                                    ) {
                                                        Text(translation["auto_open_snaps.action_reset"] ?: "Reset Statistics", fontSize = 12.sp)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        if (activeList.isEmpty() && recentList.isEmpty()) {
                            item(key = "active_empty") { AphelionTasksEmptyState(text = translation["tasks_no_active_tasks"] ?: "No active tasks") }
                        }

                        val groupedActiveTasks = activeList.distinctBy { it.task.hash }

                        items(groupedActiveTasks, key = { it.task.hash }) { pendingTask ->
                            val isAutoOpenTask = pendingTask.task.isAutoOpen
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

                            AphelionTaskCard(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .let { 
                                        if (isAutoOpenTask) {
                                            it.border(
                                                width = 1.5.dp,
                                                brush = Brush.linearGradient(
                                                    listOf(
                                                        TasksSkinPalette.glowPrimary.copy(alpha = pulseAlpha),
                                                        TasksSkinPalette.glowSecondary.copy(alpha = pulseAlpha)
                                                    )
                                                ),
                                                shape = RoundedCornerShape(22.dp)
                                            )
                                        } else it
                                    },
                                task = pendingTask.task,
                                pendingTask = pendingTask
                            )
                        }
                        
                        items(recentList.filter { task -> groupedActiveTasks.none { it.task.hash == task.hash } }, key = { it.hash }) { task ->
                            AphelionTaskCard(modifier = Modifier.fillMaxWidth(), task)
                        }
                    } else {
                        if (scheduledActive.isEmpty() && scheduledRecent.isEmpty()) {
                            item(key = "scheduled_empty") { AphelionTasksEmptyState(text = translation["tasks_no_scheduled_tasks"] ?: "No scheduled snaps") }
                        }

                        items(scheduledActive, key = { it.taskId }) { pendingTask ->
                            AphelionTaskCard(modifier = Modifier.fillMaxWidth(), pendingTask.task, pendingTask = pendingTask)
                        }
                        items(scheduledRecent, key = { it.hash }) { task ->
                            AphelionTaskCard(modifier = Modifier.fillMaxWidth(), task)
                        }
                    }

                    item {
                        Spacer(modifier = Modifier.height(40.dp))
                        LaunchedEffect(remember { derivedStateOf { scrollState.firstVisibleItemIndex } }) {
                            fetchNewRecentTasks()
                        }
                    }
                }
            }
        }

        me.eternal.purrfect.ui.manager.components.FloatingTopBar(
            title = context.translation["manager.routes.tasks"] ?: "Tasks",
            subtitle = subtitle,
            scrollOffset = routes.navigation?.globalScrollOffset ?: 0,
            enableMorph = true,
            modifier = Modifier.headerHeightTracker { controlsHeight = it },
            actions = {
                if (taskSelection.size > 1) {
                    val canMergeSelection by rememberAsyncMutableState(defaultValue = false, keys = arrayOf(taskSelection.size)) {
                        taskSelection.all { it.second?.type?.contains("video") == true }
                    }

                    if (canMergeSelection) {
                        Surface(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                mergeSelection(taskSelection.toList().also {
                                    taskSelection.clear()
                                }.map { it.first to it.second!! })
                            },
                            shape = RoundedCornerShape(18.dp),
                            color = TasksSkinPalette.glowPrimary.copy(alpha = 0.2f),
                            border = BorderStroke(1.dp, TasksSkinPalette.glowPrimary.copy(alpha = 0.4f))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(Icons.Filled.Merge, contentDescription = translation["tasks_merge_button"], tint = TasksSkinPalette.textPrimary, modifier = Modifier.size(16.dp))
                                Text(translation["tasks_merge_button"] ?: "Merge", color = TasksSkinPalette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                            }
                        }
                    }
                }

                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = TasksSkinPalette.textPrimary.copy(alpha = 0.08f),
                    border = BorderStroke(1.dp, TasksSkinPalette.textPrimary.copy(alpha = 0.16f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            Icons.Filled.PlaylistAddCheckCircle,
                            contentDescription = null,
                            tint = TasksSkinPalette.textPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = activeTasks.size.toString(),
                            color = TasksSkinPalette.textPrimary,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 12.sp
                        )
                    }
                }

                IconButton(onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)     
                    showConfirmDialog = true
                }) {
                    Icon(Icons.Filled.DeleteSweep, contentDescription = translation["tasks_clear_button_description"], tint = TasksSkinPalette.textPrimary)
                }
            }
        )
    }

    if (showConfirmDialog) {
        val isSelection = taskSelection.isNotEmpty()
        val titleText = if (isSelection) {
            translation.format("tasks_remove_selected_tasks_confirm", "count" to taskSelection.size.toString())
        } else {
            translation["tasks_remove_all_tasks_confirm"]
        }
        val messageText = if (isSelection) translation["tasks_remove_selected_tasks_title"] else translation["tasks_remove_all_tasks_title"]

        TaskDangerDialog(
            visible = showConfirmDialog,
            title = titleText,
            message = messageText,
            showDeleteFiles = isSelection,
            deleteFilesChecked = alsoDeleteFiles,
            onToggleDeleteFiles = { alsoDeleteFiles = it },
            onConfirm = {
                showConfirmDialog = false
                clearTasks(alsoDeleteFiles, scope)
            },
            onDismiss = { showConfirmDialog = false }
        )
    }
}

@Composable
internal fun TasksRootSection.AphelionTasksEmptyState(text: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 60.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Surface(
            shape = CircleShape,
            color = TasksSkinPalette.textPrimary.copy(alpha = 0.08f),
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            border = BorderStroke(1.dp, TasksSkinPalette.textPrimary.copy(alpha = 0.12f))
        ) {
            Box(
                modifier = Modifier
                    .size(58.dp)
                    .background(
                        Brush.linearGradient(
                            listOf(
                                TasksSkinPalette.glowPrimary.copy(alpha = 0.32f),
                                TasksSkinPalette.glowSecondary.copy(alpha = 0.28f)
                            )
                        ),
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = text,
                    tint = TasksSkinPalette.textPrimary
                )
            }
        }
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
            color = TasksSkinPalette.textPrimary
        )
    }
}

@Composable
internal fun TasksRootSection.AphelionTaskCard(modifier: Modifier, task: Task, pendingTask: PendingTask? = null) {
    var taskStatus by remember { mutableStateOf(task.status) }
    var taskProgressLabel by remember { mutableStateOf<String?>(null) }
    var taskProgress by remember { mutableIntStateOf(-1) }
    val isSelected by remember { derivedStateOf { taskSelection.any { it.first == task } } }

    var documentFileMimeType by remember { mutableStateOf("") }
    var isDocumentFileReadable by remember { mutableStateOf(true) }
    
    val docVal = task.extra?.toUri()
    val documentFile by rememberAsyncMutableState(
        defaultValue = null as DocumentFile?,
        keys = arrayOf(taskStatus.name)
    ) {
        if (docVal == null) null
        else DocumentFile.fromSingleUri(context.androidContext, docVal)?.apply {
            documentFileMimeType = type ?: ""
            isDocumentFileReadable = canRead()
        }
    }

    val listener = remember { PendingTaskListener(
        onStateChange = { taskStatus = it },
        onProgress = { label, progress ->
            taskProgressLabel = label
            taskProgress = progress
        }
    ) }

    LaunchedEffect(Unit) { pendingTask?.addListener(listener) }
    DisposableEffect(Unit) { onDispose { pendingTask?.removeListener(listener) } }

    val haptic = LocalHapticFeedback.current
    val isActive = pendingTask != null && !taskStatus.isFinalStage()

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
            context.shortToast(translation["failed_to_open_file"] ?: "Failed to open file")
        }
    }

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
                it.border(2.dp, TasksSkinPalette.glowSecondary, RoundedCornerShape(22.dp)).clip(RoundedCornerShape(22.dp))
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
            containerColor = TasksSkinPalette.textPrimary.copy(alpha = 0.08f),
            labelColor = TasksSkinPalette.textPrimary
        )
        taskStatus == TaskStatus.SUCCESS -> AssistChipDefaults.assistChipColors()
        taskStatus == TaskStatus.FAILURE -> AssistChipDefaults.assistChipColors(
            containerColor = Color(0xFFFF6B9B).copy(alpha = 0.18f),
            labelColor = TasksSkinPalette.textPrimary
        )
        taskStatus == TaskStatus.CANCELLED -> AssistChipDefaults.assistChipColors(
            containerColor = TasksSkinPalette.textPrimary.copy(alpha = 0.06f),
            labelColor = TasksSkinPalette.textSecondary
        )
        else -> AssistChipDefaults.assistChipColors()
    }
    val countdownText = if (isActive) {
        taskProgressLabel?.let { label ->
            Regex("""(\d+d\s+)?(\d+h\s+)?(\d+m\s+)?\d+s""").find(label)?.value?.trim() ?: label
        }
    } else null

    val cardShape = RoundedCornerShape(22.dp)
    Surface(
        modifier = cardModifier,
        shape = cardShape,
        color = Color.Transparent,
        border = BorderStroke(1.dp, if (isSelected) Brush.linearGradient(listOf(TasksSkinPalette.glowPrimary, TasksSkinPalette.glowSecondary)) else SolidColor(TasksSkinPalette.textPrimary.copy(alpha = 0.1f)))
    ) {
        Row(modifier = Modifier.background(TasksSkinPalette.cardOverlay, cardShape).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.padding(end = 15.dp).size(50.dp).clipToBounds(), contentAlignment = Alignment.Center) {
                var loadFailed by remember { mutableStateOf(false) }
                val doc = documentFile
                if (taskStatus.isFinalStage() && isDocumentFileReadable && !loadFailed && doc != null && (documentFileMimeType.contains("image") || documentFileMimeType.contains("video"))) {
                    val imageRequest = ImageRequest.Builder(context.androidContext)
                        .data(doc.uri)
                        .cacheKey(doc.uri.toString())
                        .placeholder(ColorDrawable(TasksSkinPalette.cardOverlayColor.toArgb()))
                        .build()
                    Image(
                        painter = rememberAsyncImagePainter(
                            model = imageRequest,
                            imageLoader = context.imageLoader,
                            onState = { state ->
                                if (state is coil.compose.AsyncImagePainter.State.Error) loadFailed = true
                            }
                        ),
                        contentDescription = null, 
                        contentScale = ContentScale.FillWidth, 
                        modifier = Modifier.size(50.dp).clip(MaterialTheme.shapes.medium)
                    )
                } else {
                    when {
                        !isDocumentFileReadable -> Icon(Icons.Filled.DeleteOutline, contentDescription = null, tint = TasksSkinPalette.textPrimary)
                        documentFileMimeType.contains("image") -> Icon(Icons.Filled.Photo, contentDescription = null, tint = TasksSkinPalette.textPrimary)
                        documentFileMimeType.contains("video") -> Icon(Icons.Filled.Videocam, contentDescription = null, tint = TasksSkinPalette.textPrimary)
                        documentFileMimeType.contains("audio") -> Icon(Icons.Filled.MusicNote, contentDescription = null, tint = TasksSkinPalette.textPrimary)
                        else -> Icon(Icons.Filled.FileCopy, contentDescription = null, tint = TasksSkinPalette.textPrimary)
                    }
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                if (task.type == TaskType.SCHEDULED_SEND) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(context.translation.getOrNull("scheduled_send_title") ?: "Scheduled Snaps", style = MaterialTheme.typography.labelMedium, color = TasksSkinPalette.textSecondary)
                        Text(task.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = TasksSkinPalette.textPrimary)
                        task.author?.takeIf { it != "null" }?.let { recipients ->
                            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(Icons.Filled.People, contentDescription = null, modifier = Modifier.size(16.dp).padding(top = 2.dp), tint = TasksSkinPalette.textSecondary)
                                recipients.split(", ").let { list ->
                                    Text(list.joinToString(", "), style = MaterialTheme.typography.bodyMedium, color = TasksSkinPalette.textPrimary, lineHeight = 20.sp)
                                }
                            }
                        }
                    }
                } else {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(task.title, style = MaterialTheme.typography.bodyMedium, color = TasksSkinPalette.textPrimary)
                        task.author?.takeIf { it != "null" }?.let {
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(it, style = MaterialTheme.typography.bodySmall, color = TasksSkinPalette.textSecondary)
                        }
                    }
                    Text(task.hash, style = MaterialTheme.typography.labelSmall, color = TasksSkinPalette.textSecondary)
                }
                
                Column(modifier = Modifier.padding(top = 5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    chipLabel?.let { label ->
                        val leadingIcon: (@Composable () -> Unit)? = if (isActive && task.type == TaskType.SCHEDULED_SEND) {
                            { Icon(Icons.Filled.Timer, contentDescription = null, modifier = Modifier.size(16.dp), tint = TasksSkinPalette.textPrimary) }
                        } else if (isActive) {
                            countdownText?.let { countdown -> { Text(countdown, style = MaterialTheme.typography.labelSmall, color = TasksSkinPalette.textPrimary) } }
                        } else chipIcon?.let { icon -> { Icon(icon, contentDescription = null, tint = TasksSkinPalette.textPrimary) } }
                        
                        val displayLabel = if (isActive && task.type == TaskType.SCHEDULED_SEND && countdownText != null) {
                            translation.getOrNull("schedule_sending_in")?.replace("{time}", countdownText) ?: "Sending in $countdownText"
                        } else label
                        
                        AssistChip(onClick = {}, enabled = false, leadingIcon = leadingIcon, label = { Text(displayLabel, color = TasksSkinPalette.textPrimary) }, colors = chipColors)
                    }
                    
                    if (!taskStatus.isFinalStage()) {
                        if (isActive) {
                            taskProgressLabel?.let {
                                val labelText = if (task.isAutoOpen) {
                                    val sessionTimeMins = (System.currentTimeMillis() - 0L) / 60000.0
                                    val speed = if (sessionTimeMins > 0.1) String.format("%.1f", 0 / sessionTimeMins) else "0.0"
                                    "$it • $speed snaps/min"
                                } else it
                                Text(labelText, style = MaterialTheme.typography.bodySmall, color = TasksSkinPalette.textPrimary)
                            }
                        } else {
                            taskProgressLabel?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = TasksSkinPalette.textPrimary) }
                        }
                        if (taskProgress != -1 && (taskProgressLabel == null || isActive)) {
                            LinearProgressIndicator(
                                progress = { taskProgress.toFloat() / 100f },
                                strokeCap = StrokeCap.Round, modifier = Modifier.fillMaxWidth(),
                                color = TasksSkinPalette.glowSecondary, trackColor = TasksSkinPalette.textPrimary.copy(alpha = 0.12f)
                            )
                        }
                        if (!isActive) {
                            task.extra?.takeIf { it.isNotEmpty() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = TasksSkinPalette.textSecondary) }
                        }
                    }
                }
            }
            
            Column {
                if (isActive) {
                    FilledIconButton(
                        onClick = {
                            runCatching { pendingTask?.cancel() }.onFailure { throwable ->
                                context.log.error("Failed to cancel task $pendingTask", throwable)
                            }
                        },
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFFFF6B9B).copy(alpha = 0.35f), contentColor = TasksSkinPalette.textPrimary)
                    ) { Icon(Icons.Filled.Close, contentDescription = "Cancel") }
                } else if (taskStatus == TaskStatus.SUCCESS) {
                    AnimatedVisibility(
                        visible = true, 
                        enter = fadeIn(tween(250)) + scaleIn(tween(300)), 
                        exit = fadeOut(tween(150)) + scaleOut(targetScale = 0.5f, animationSpec = tween(150))
                    ) {
                        Box(modifier = Modifier.size(40.dp).clip(CircleShape).background(TasksSkinPalette.glowPrimary.copy(alpha = 0.22f)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.Check, contentDescription = "Success", tint = TasksSkinPalette.textPrimary)
                        }
                    }
                } else {
                    when (taskStatus) {
                        TaskStatus.FAILURE -> Icon(Icons.Filled.Error, contentDescription = "Failure", tint = Color(0xFFFF6B9B))
                        TaskStatus.CANCELLED -> Icon(Icons.Filled.Cancel, contentDescription = "Cancelled", tint = Color(0xFFFF6B9B))
                        else -> {}
                    }
                }
            }
        }
    }
}
