package me.eternal.purrfect.ui.manager.pages.home.themes

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavBackStackEntry
import me.eternal.purrfect.ui.manager.components.FloatingTopBar
import me.eternal.purrfect.ui.manager.pages.home.HomeLogs
import me.eternal.purrfect.common.ui.theme.LocalPurrfectSkin
import me.eternal.purrfect.common.ui.theme.PurrfectPalette
import me.eternal.purrfect.ui.util.headerHeightTracker
import me.eternal.purrfect.ui.util.Motion
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun HomeLogs.AphelionLogsContent(nav: NavBackStackEntry) {
    val managerTheme = context.config.root.global.uiSettings.managerTheme.get()
    val activeSkin = LocalPurrfectSkin.current
    val skin = remember(managerTheme, activeSkin) { if (managerTheme == "APHELION") activeSkin else PurrfectPalette }
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
                context.log.newReader { line ->
                    if (shouldHideLog(line)) return@newReader
                    coroutineScope.launch(Dispatchers.Main) {
                        visibleLogs.add(line)
                    }
                }
            }
            readerResult.onFailure {
                context.longToast(translation["read_logs_failed_toast"] ?: "Failed to read logs")
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
    fun LogFilterDialog(onDismiss: () -> Unit) {
        androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
            me.eternal.purrfect.ui.util.AlertDialogs(translation).DefaultDialogCard {
                Column(
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = translation["filter_logs_title"] ?: "Log Filters",
                        fontSize = 21.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = skin.textPrimary,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
                    )
                    Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            color = skin.textPrimary.copy(alpha = 0.08f),
                            border = BorderStroke(1.dp, skin.textPrimary.copy(alpha = 0.05f))
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
                                                checkedColor = skin.glowPrimary,
                                                uncheckedColor = skin.textPrimary.copy(alpha = 0.3f),
                                                checkmarkColor = skin.textPrimary
                                            )
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = translation[category.translationKey] ?: category.name,
                                            color = skin.textPrimary,
                                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold)
                                        )
                                    }
                                }
                            }
                        }

                        Button(
                            onClick = onDismiss,
                            modifier = Modifier.fillMaxWidth().height(54.dp),
                            shape = RoundedCornerShape(18.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = skin.glowPrimary,
                                contentColor = skin.textPrimary
                            )
                        ) {
                            Text(text = translation["button.positive"] ?: "Close", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

    if (showFilterDialog) {
        LogFilterDialog { showFilterDialog = false }
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
            .background(skin.backgroundGradient)
    ) {
        var showDropDown by remember { mutableStateOf(false) }
        
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            shape = RoundedCornerShape(24.dp),
            color = skin.textPrimary.copy(alpha = 0.04f),
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            border = BorderStroke(1.dp, skin.textPrimary.copy(alpha = 0.08f))
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
                        color = skin.textPrimary
                    )
                }
                IconButton(onClick = { showFilterDialog = true }) {
                    Icon(Icons.Filled.FilterList, contentDescription = "Filter", tint = skin.glowSecondary)
                }
                IconButton(onClick = { refreshLogs() }) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh", tint = skin.textPrimary)
                }
                Box {
                    IconButton(onClick = { showDropDown = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = null, tint = skin.textPrimary)
                    }
                    DropdownMenu(
                        expanded = showDropDown,
                        onDismissRequest = { showDropDown = false },
                        offset = DpOffset(0.dp, 8.dp),
                        containerColor = skin.cardOverlayColor,
                        tonalElevation = 8.dp,
                        shadowElevation = 12.dp,
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        DropdownMenuItem(
                            onClick = {
                                clearLogsAndReload()
                                showDropDown = false
                            },
                            leadingIcon = { Icon(Icons.Filled.DeleteSweep, contentDescription = null, tint = skin.glowPrimary) },
                            text = { Text(translation["clear_logs_button"] ?: "Clear", color = skin.textPrimary) },
                            colors = MenuDefaults.itemColors(
                                textColor = skin.textPrimary,
                                leadingIconColor = skin.glowPrimary
                            )
                        )
                        DropdownMenuItem(
                            onClick = {
                                exportLogs()
                                showDropDown = false
                            },
                            leadingIcon = { Icon(Icons.Filled.Download, contentDescription = null, tint = skin.glowSecondary) },
                            text = { Text(translation["export_logs_button"] ?: "Export", color = skin.textPrimary) },
                            colors = MenuDefaults.itemColors(
                                textColor = skin.textPrimary,
                                leadingIconColor = skin.glowSecondary
                            )
                        )
                    }
                }
            }
        )
    }
}
