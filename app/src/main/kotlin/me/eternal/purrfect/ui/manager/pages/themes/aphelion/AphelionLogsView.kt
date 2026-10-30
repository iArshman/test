package me.eternal.purrfect.ui.manager.pages.themes.aphelion

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.navigation.NavBackStackEntry
import me.eternal.purrfect.ui.manager.components.FloatingTopBar
import me.eternal.purrfect.ui.manager.pages.home.HomeLogs
import me.eternal.purrfect.common.ui.theme.LocalPurrfectSkin
import me.eternal.purrfect.common.ui.theme.PurrfectPalette
import me.eternal.purrfect.ui.util.headerHeightTracker
import me.eternal.purrfect.ui.util.Motion
import me.eternal.purrfect.core.ui.PurrfectGlassCard
import me.eternal.purrfect.core.ui.PurrfectOverlayTheme
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private object LogsSkinPalette {
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

@Composable
fun HomeLogs.AphelionLogsScreen(nav: NavBackStackEntry) {
    val coroutineScope = rememberCoroutineScope()
    var controlsHeight by remember { mutableStateOf(100.dp) }
    val composeContext = LocalContext.current
    var logReader by remember { mutableStateOf<me.eternal.purrfect.LogReader?>(null) }
    val visibleLogs = remember { mutableStateListOf<me.eternal.purrfect.LogLine>() }
    var isRefreshing by remember { mutableStateOf(false) }
    var showFilterDialog by remember { mutableStateOf(false) }

    fun refreshLogs() {
        isRefreshing = true
        coroutineScope.launch(Dispatchers.IO) {
            val readerResult = runCatching {
                context.log.newReader { /* items are batch-added from reader logic below */ }
            }
            readerResult.onFailure {
                context.longToast(translation["read_logs_failed_toast"] ?: "Failed to read logs")
                withContext(Dispatchers.Main) { isRefreshing = false }
            }
            readerResult.getOrNull()?.let { reader ->
                logReader = reader
                val filteredLogs = (0 until reader.lineCount).mapNotNull { index ->
                    reader.getLogLine(index)?.takeUnless(::shouldHideLog)
                }
                withContext(Dispatchers.Main) {
                    visibleLogs.clear()
                    visibleLogs.addAll(filteredLogs)
                    if (visibleLogs.isNotEmpty()) {
                        logListState.scrollToItem((visibleLogs.size - 1).coerceAtLeast(0))
                    }
                    isRefreshing = false
                }
            }
        }
    }

    @Composable
    fun LogFilterDialog() {
        Dialog(onDismissRequest = { showFilterDialog = false }) {
            PurrfectOverlayTheme {
                PurrfectGlassCard(
                    title = translation["filter_logs_title"] ?: "Log Filters",
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            color = LogsSkinPalette.textPrimary.copy(alpha = 0.08f),
                            border = BorderStroke(1.dp, LogsSkinPalette.textPrimary.copy(alpha = 0.05f))
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                HomeLogs.LogCategory.entries.forEach { category ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                            .clickable {
                                                enabledCategories[category] = !(enabledCategories[category] ?: true)
                                                refreshLogs()
                                            }
                                            .padding(horizontal = 12.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Checkbox(
                                            checked = enabledCategories[category] == true,
                                            onCheckedChange = { checked ->
                                                enabledCategories[category] = checked
                                                refreshLogs()
                                            },
                                            colors = CheckboxDefaults.colors(
                                                checkedColor = LogsSkinPalette.glowPrimary,
                                                uncheckedColor = LogsSkinPalette.textPrimary.copy(alpha = 0.3f),
                                                checkmarkColor = LogsSkinPalette.textPrimary
                                            )
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = translation[category.translationKey] ?: category.name,
                                            color = LogsSkinPalette.textPrimary,
                                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold)
                                        )
                                    }
                                }
                            }
                        }

                        Button(
                            onClick = { showFilterDialog = false },
                            modifier = Modifier.fillMaxWidth().height(54.dp),
                            shape = RoundedCornerShape(18.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = LogsSkinPalette.glowPrimary)
                        ) {
                            Text(translation["filter_logs_done_button"] ?: "Apply Filters", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = LogsSkinPalette.textPrimary)
                        }
                    }
                }
            }
        }
    }

    if (showFilterDialog) {
        LogFilterDialog()
    }

    LaunchedEffect(externalRefreshTick.value) {
        if (externalRefreshTick.value > 0) {
            refreshLogs()
        }
    }

    LaunchedEffect(Unit) {
        refreshLogs()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(LogsSkinPalette.backgroundGradient)
    ) {
        var showDropDown by remember { mutableStateOf(false) }
        
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            shape = RoundedCornerShape(24.dp),
            color = LogsSkinPalette.textPrimary.copy(alpha = 0.04f),
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            border = BorderStroke(1.dp, LogsSkinPalette.textPrimary.copy(alpha = 0.08f))
        ) {
            if (visibleLogs.isEmpty() && logReader != null) {
                EmptyLogsState()
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize(),
                    state = logListState,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(
                        start = 8.dp,
                        end = 8.dp,
                        top = controlsHeight,
                        bottom = routes.bottomPadding + 12.dp
                    )
                ) {
                    items(visibleLogs, key = { it.hashCode() }) { line ->
                        LogEntryCard(line = line, composeContext = composeContext)
                    }
                }
            }
        }

        FloatingTopBar(
            title = context.translation["manager.routes.home_logs"] ?: "Logs",
            onBack = { routes.navController.popBackStack() },
            scrollOffset = if (logListState.firstVisibleItemIndex > 0) Motion.HEADER_MORPH_THRESHOLD.toInt() else logListState.firstVisibleItemScrollOffset,
            enableMorph = true,
            modifier = Modifier.headerHeightTracker { controlsHeight = it },
            actions = {
                if (isRefreshing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = LogsSkinPalette.textPrimary
                    )
                }
                IconButton(onClick = { showFilterDialog = true }) {
                    Icon(Icons.Filled.FilterList, contentDescription = "Filter Logs", tint = LogsSkinPalette.glowSecondary)
                }
                IconButton(onClick = { refreshLogs() }) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh", tint = LogsSkinPalette.textPrimary)
                }
                Box {
                    IconButton(onClick = { showDropDown = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = null, tint = LogsSkinPalette.textPrimary)
                    }
                    DropdownMenu(
                        expanded = showDropDown,
                        onDismissRequest = { showDropDown = false },
                        offset = DpOffset(0.dp, 8.dp),
                        containerColor = LogsSkinPalette.cardOverlayColor,
                        tonalElevation = 8.dp,
                        shadowElevation = 12.dp,
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        DropdownMenuItem(
                            onClick = {
                                clearLogsAndReload()
                                showDropDown = false
                            },
                            leadingIcon = { Icon(Icons.Filled.DeleteSweep, contentDescription = null, tint = LogsSkinPalette.glowPrimary) },
                            text = { Text(translation["clear_logs_button"] ?: "Clear", color = LogsSkinPalette.textPrimary) },
                            colors = MenuDefaults.itemColors(
                                textColor = LogsSkinPalette.textPrimary,
                                leadingIconColor = LogsSkinPalette.glowPrimary
                            )
                        )
                        DropdownMenuItem(
                            onClick = {
                                exportLogs()
                                showDropDown = false
                            },
                            leadingIcon = { Icon(Icons.Filled.Download, contentDescription = null, tint = LogsSkinPalette.glowSecondary) },
                            text = { Text(translation["export_logs_button"] ?: "Export", color = LogsSkinPalette.textPrimary) },
                            colors = MenuDefaults.itemColors(
                                textColor = LogsSkinPalette.textPrimary,
                                leadingIconColor = LogsSkinPalette.glowSecondary
                            )
                        )
                    }
                }
            }
        )
    }
}
