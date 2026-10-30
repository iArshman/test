package me.eternal.purrfect.ui.manager.pages.scripting

import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.documentfile.provider.DocumentFile
import androidx.navigation.NavBackStackEntry
import kotlinx.coroutines.*
import me.eternal.purrfect.common.scripting.type.ModuleInfo
import me.eternal.purrfect.common.scripting.ui.EnumScriptInterface
import me.eternal.purrfect.common.scripting.ui.InterfaceManager
import me.eternal.purrfect.common.scripting.ui.ScriptInterface
import me.eternal.purrfect.common.ui.AsyncUpdateDispatcher
import me.eternal.purrfect.common.ui.rememberAsyncMutableState
import me.eternal.purrfect.common.ui.rememberAsyncUpdateDispatcher
import me.eternal.purrfect.common.util.ktx.getUrlFromClipboard
import me.eternal.purrfect.common.util.ktx.openLink
import me.eternal.purrfect.storage.isScriptEnabled
import me.eternal.purrfect.storage.setScriptEnabled
import me.eternal.purrfect.ui.manager.Routes
import me.eternal.purrfect.ui.manager.ManagerTheme
import me.eternal.purrfect.ui.manager.components.AestheticDialog
import me.eternal.purrfect.ui.manager.components.AestheticEmptyState
import me.eternal.purrfect.common.ui.theme.LocalPurrfectSkin
import me.eternal.purrfect.ui.util.ActivityLauncherHelper
import me.eternal.purrfect.ui.util.chooseFolder
import me.eternal.purrfect.ui.util.purrfectSwitchColors
import me.eternal.purrfect.ui.util.pullrefresh.PullRefreshIndicator
import me.eternal.purrfect.ui.util.pullrefresh.pullRefresh
import me.eternal.purrfect.ui.util.pullrefresh.rememberPullRefreshState
import androidx.compose.ui.platform.LocalContext

internal object ScriptingSkinPalette {
    @Composable
    internal fun isAphelion(): Boolean {
        val context = LocalContext.current
        return remember(context) { 
            me.eternal.purrfect.SharedContextHolder.remote(context).config.root.global.uiSettings.managerTheme.get() == "APHELION"
        }
    }

    val glowPrimary: Color @Composable get() = LocalPurrfectSkin.current.glowPrimary
    val glowSecondary: Color @Composable get() = LocalPurrfectSkin.current.glowSecondary
    val backgroundGradient: Brush @Composable get() = LocalPurrfectSkin.current.backgroundGradient
    val cardOverlay: Brush @Composable get() = LocalPurrfectSkin.current.cardOverlay
    val textPrimary: Color @Composable get() = LocalPurrfectSkin.current.textPrimary
    val textSecondary: Color @Composable get() = LocalPurrfectSkin.current.textSecondary
    val cardOverlayColor: Color @Composable get() = LocalPurrfectSkin.current.cardOverlayColor
}

class ScriptingRootSection : Routes.Route() {
    override val translation by lazy { context.translation.getCategory("manager.scripting") }
    internal lateinit var activityLauncherHelper: ActivityLauncherHelper
    internal val reloadDispatcher = AsyncUpdateDispatcher(updateOnFirstComposition = false)
    internal var selectedTab by mutableStateOf(0)

    override val init: () -> Unit = {
        activityLauncherHelper = ActivityLauncherHelper(context.activity!!)
    }

    internal suspend fun isScriptInstalledByUrl(scriptUrl: String): Boolean {
        return try {
            val installedScripts = context.scriptManager.getSyncedModules()
            installedScripts.any { module ->
                module.updateUrl?.equals(scriptUrl, ignoreCase = true) == true
            }
        } catch (e: Exception) {
            false
        }
    }

    internal fun downloadScript(scriptUrl: String, onComplete: () -> Unit) {
        context.coroutineScope.launch {
            if (isScriptInstalledByUrl(scriptUrl)) {
                context.shortToast(translation["script_already_installed"])
                return@launch
            }

            runCatching {
                context.shortToast(translation["downloading_script"])
                val moduleInfo = context.scriptManager.importFromUrl(scriptUrl)
                context.shortToast(translation.format("script_downloaded", "name" to moduleInfo.name))
                reloadDispatcher.dispatch()
                onComplete()
            }.onFailure {
                context.log.error("Failed to download script", it)
                context.shortToast(translation["download_script_failed"])
            }
        }
    }

    @Composable
    internal fun ImportRemoteScript(
        dismiss: () -> Unit
    ) {
        var url by remember { mutableStateOf("") }
        val focusRequester = remember { FocusRequester() }
        var isLoading by remember { mutableStateOf(false) }

        LaunchedEffect(Unit) {
            context.androidContext.getUrlFromClipboard()?.let { url = it }
        }

        AestheticDialog(
            onDismissRequest = dismiss,
            title = translation["import_script_from_url_title"] ?: "Import script from URL",
            text = translation["import_script_warning"] ?: "Are you sure you want to import this script?",
            icon = Icons.Default.Link,
            confirmButtonText = translation["import_button"] ?: "Import",
            dismissButtonText = translation["button.cancel"],
            onDismiss = dismiss,
            loading = isLoading,
            confirmEnabled = url.isNotBlank(),
            showCloseButton = false,
            onConfirm = {
                isLoading = true
                context.coroutineScope.launch {
                    runCatching {
                        if (isScriptInstalledByUrl(url)) {
                            context.shortToast(translation["script_already_installed"] ?: "Script already installed")
                            withContext(Dispatchers.Main) {
                                dismiss()
                            }
                            return@launch
                        }

                        val moduleInfo = context.scriptManager.importFromUrl(url)
                        context.shortToast(translation.format("script_imported", "name" to moduleInfo.name))
                        reloadDispatcher.dispatch()
                        withContext(Dispatchers.Main) {
                            dismiss()
                        }
                        return@launch
                    }.onFailure {
                        context.log.error("Failed to import script", it)
                        context.shortToast(translation.format("import_failed", "message" to (it.message ?: context.translation["common.unknown"])))
                    }
                    isLoading = false
                }
            },
            customContent = {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(text = translation["enter_url_label"] ?: "Enter URL") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .onGloballyPositioned { focusRequester.requestFocus() },
                    singleLine = true,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = ScriptingSkinPalette.textPrimary.copy(alpha = 0.06f),
                        unfocusedContainerColor = ScriptingSkinPalette.textPrimary.copy(alpha = 0.05f),
                        focusedIndicatorColor = ScriptingSkinPalette.glowSecondary.copy(alpha = 0.5f),
                        unfocusedIndicatorColor = ScriptingSkinPalette.textPrimary.copy(alpha = 0.12f),
                        cursorColor = ScriptingSkinPalette.glowSecondary,
                        focusedTextColor = ScriptingSkinPalette.textPrimary,
                        unfocusedTextColor = ScriptingSkinPalette.textPrimary,
                        focusedLabelColor = Color.White,
                        unfocusedLabelColor = ScriptingSkinPalette.textSecondary
                    )
                )
            }
        )
    }

    @Composable
    internal fun ModuleActions(
        script: ModuleInfo,
        canUpdate: Boolean,
        dismiss: () -> Unit
    ) {
        val coroutineScope = rememberCoroutineScope()
        AestheticDialog(
            onDismissRequest = dismiss,
            title = script.displayName ?: script.name,
            text = script.description ?: "",
            icon = Icons.Default.Extension,
            confirmButtonText = "",
            onConfirm = {},
            opaque = true,
            showCloseButton = true,
            customContent = {
                val actions = remember(script, canUpdate) {
                    mutableMapOf<Pair<String, ImageVector>, suspend () -> Unit>().apply {
                        if (canUpdate) {
                            put(translation["update_script_button"] to Icons.Default.Update) {
                                script.updateUrl?.let { downloadScript(it, dismiss) }
                            }
                        }
                        put(translation["open_in_external_editor"] to Icons.Default.OpenInNew) {
                            context.scriptManager.getModulePath(script.name)?.let { path ->
                                context.scriptManager.getScriptsFolder()?.findFile(path)?.uri?.let { uri ->
                                    val intent = Intent(Intent.ACTION_VIEW).apply {
                                        setDataAndType(uri, "text/javascript")
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.androidContext.startActivity(Intent.createChooser(intent, translation["choose_editor_title"]))
                                }
                            }
                        }
                        put(translation["delete_script_button"] to Icons.Default.DeleteForever) {
                           context.scriptManager.apply {
                                runCatching {
                                    val modulePath = getModulePath(script.name)!!
                                    unloadScript(modulePath)
                                    getScriptsFolder()?.findFile(modulePath)?.delete()
                                    reloadDispatcher.dispatch()
                                    context.shortToast(translation.format("deleted_script", "name" to script.name))
                                    dismiss()
                                }.onFailure {
                                    context.log.error("Failed to delete module", it)
                                    context.shortToast(translation["delete_module_failed"])
                                }
                            }
                        }
                    }.toMap()
                }
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    item {
                        Text(
                            text = translation["actions_title"],
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(16.dp).fillMaxWidth(),
                            textAlign = TextAlign.Center,
                            color = ScriptingSkinPalette.textPrimary
                        )
                    }
                    items(actions.size) { index ->
                        val action = actions.entries.elementAt(index)
                        ListItem(
                            modifier = Modifier
                                .clickable { context.coroutineScope.launch { action.value(); dismiss() } }
                                .fillMaxWidth(),
                            leadingContent = {
                                Icon(action.key.second, action.key.first, tint = ScriptingSkinPalette.textPrimary)
                            },
                            headlineContent = { Text(action.key.first, color = ScriptingSkinPalette.textPrimary) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )
                    }
                }
            }
        )
    }

    @Composable
    internal fun ModuleItem(script: ModuleInfo) {
        var enabled by rememberAsyncMutableState(defaultValue = false, keys = arrayOf(script)) {
            context.database.isScriptEnabled(script.name)
        }
        var openSettings by remember(script) { mutableStateOf(false) }
        var openActions by remember { mutableStateOf(false) }

        val dispatcher = rememberAsyncUpdateDispatcher()
        val reloadCallback = remember { suspend { dispatcher.dispatch() } }
        val latestUpdate by rememberAsyncMutableState(defaultValue = null, updateDispatcher = dispatcher, keys = arrayOf(script)) {
            context.scriptManager.checkForUpdate(script)
        }

        LaunchedEffect(Unit) {
            reloadDispatcher.addCallback(reloadCallback)
        }
        DisposableEffect(Unit) {
            onDispose { reloadDispatcher.removeCallback(reloadCallback) }
        }

        val cardShape = RoundedCornerShape(20.dp)
        val glowPrimary = ScriptingSkinPalette.glowPrimary
        val glowSecondary = ScriptingSkinPalette.glowSecondary
        val textPrimary = ScriptingSkinPalette.textPrimary
        val borderBrush = remember(glowPrimary, glowSecondary, enabled, textPrimary) {
            if (enabled) Brush.linearGradient(listOf(glowPrimary, glowSecondary))
            else SolidColor(textPrimary.copy(alpha = 0.08f))
        }
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            shape = cardShape,
            color = Color.Transparent,
            tonalElevation = 0.dp,
            shadowElevation = 8.dp,
            border = BorderStroke(1.dp, borderBrush)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = enabled) { if (enabled) openSettings = !openSettings }
                    .background(ScriptingSkinPalette.cardOverlay, cardShape)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                Brush.radialGradient(
                                    listOf(
                                        glowPrimary.copy(alpha = 0.35f),
                                        Color.Transparent
                                    )
                                )
                            )
                            .border(1.dp, ScriptingSkinPalette.textPrimary.copy(alpha = 0.16f), RoundedCornerShape(14.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Extension,
                            contentDescription = null,
                            tint = ScriptingSkinPalette.textPrimary
                        )
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = script.displayName ?: script.name,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = ScriptingSkinPalette.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = script.description ?: translation["no_description"],
                            fontSize = 13.sp,
                            color = ScriptingSkinPalette.textSecondary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            latestUpdate?.let {
                                AssistChip(
                                    onClick = { },
                                    enabled = false,
                                    leadingIcon = { Icon(Icons.Default.Star, contentDescription = null, tint = glowSecondary) },
                                    label = { Text(translation.format("update_available", "version" to it.version)) },
                                    colors = AssistChipDefaults.assistChipColors(
                                        containerColor = ScriptingSkinPalette.textPrimary.copy(alpha = 0.08f),
                                        labelColor = Color.White,
                                        disabledLabelColor = Color.White
                                    )
                                )
                            }
                            if (openSettings && enabled) {
                                AssistChip(
                                    onClick = {},
                                    enabled = false,
                                    label = { Text(translation["actions_button"]) },
                                    leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null) },
                                    colors = AssistChipDefaults.assistChipColors(
                                        containerColor = ScriptingSkinPalette.textPrimary.copy(alpha = 0.06f),
                                        labelColor = Color.White,
                                        disabledLabelColor = Color.White
                                    )
                                )
                            }
                        }
                    }
                    IconButton(onClick = { openActions = !openActions }) {
                        Icon(Icons.Default.Build, translation["actions_button"], tint = ScriptingSkinPalette.textPrimary)
                    }
                    Switch(
                        checked = enabled,
                        onCheckedChange = { isChecked ->
                            openSettings = false
                            context.coroutineScope.launch(Dispatchers.IO) {
                                runCatching {
                                    val modulePath = context.scriptManager.getModulePath(script.name)!!
                                    context.scriptManager.unloadScript(modulePath)
                                    if (isChecked) {
                                        context.scriptManager.loadScript(modulePath)
                                        context.scriptManager.runtime.getModuleByName(script.name)
                                            ?.callFunction("module.onPurrfectLoad")
                                        context.shortToast(translation.format("loaded_script", "name" to script.name))
                                    } else {
                                        context.shortToast(translation.format("unloaded_script", "name" to script.name))
                                    }
                                    context.database.setScriptEnabled(script.name, isChecked)
                                    withContext(Dispatchers.Main) { enabled = isChecked }
                                }.onFailure { throwable ->
                                    withContext(Dispatchers.Main) { enabled = !isChecked }
                                    context.log.error("Failed to ${if (isChecked) "enable" else "disable"} script", throwable)
                                    context.shortToast(translation.format(if (isChecked) "enable_script_failed" else "disable_script_failed"))
                                }
                            }
                        },
                        colors = purrfectSwitchColors()
                    )
                }
                if (openSettings) {
                    HorizontalDivider(color = ScriptingSkinPalette.textPrimary.copy(alpha = 0.08f))
                    ScriptSettings(script)
                }
            }
        }
        if (openActions) {
            ModuleActions(script = script, canUpdate = latestUpdate != null) { openActions = false }
        }
    }

    override val floatingActionButton: @Composable () -> Unit = {}

    @Composable
    internal fun SelectFolderButton(onClick: () -> Unit) {
        val label = translation["select_folder_button"]
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            val glowPrimary = ScriptingSkinPalette.glowPrimary
            val glowSecondary = ScriptingSkinPalette.glowSecondary
            val borderBrush = remember(glowPrimary, glowSecondary) {
                Brush.linearGradient(
                    listOf(
                        glowPrimary.copy(alpha = 0.7f),
                        glowSecondary.copy(alpha = 0.65f)
                    )
                )
            }
            Surface(
                modifier = Modifier.size(78.dp),
                shape = CircleShape,
                color = ScriptingSkinPalette.cardOverlayColor.copy(alpha = 0.9f),
                tonalElevation = 0.dp,
                shadowElevation = 14.dp,
                border = BorderStroke(1.5.dp, borderBrush)
            ) {
                Box(
                    modifier = Modifier
                        .padding(6.dp)
                        .size(66.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    glowPrimary.copy(alpha = 0.42f),
                                    glowSecondary.copy(alpha = 0.34f)
                                )
                            )
                        )
                        .border(1.dp, ScriptingSkinPalette.textPrimary.copy(alpha = 0.14f), CircleShape)
                        .clickable(onClick = onClick),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = label,
                        tint = ScriptingSkinPalette.textPrimary,
                        modifier = Modifier.size(34.dp)
                    )
                }
            }
        }
    }

    @Composable
    internal fun ScriptSettings(script: ModuleInfo) {
        val settingsInterface = remember {
            val module =
                context.scriptManager.runtime.getModuleByName(script.name) ?: return@remember null
            (module.getBinding(InterfaceManager::class))?.buildInterface(EnumScriptInterface.SETTINGS)
        }
        if (settingsInterface == null) {
            Text(
                text = translation["no_settings_for_module"],
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(8.dp),
                color = ScriptingSkinPalette.textPrimary.copy(alpha = 0.7f)
            )
        } else {
            ScriptInterface(interfaceBuilder = settingsInterface)
        }
    }

    @Composable
    internal fun ScriptingScreen(nav: NavBackStackEntry) {
        val scriptingFolder = rememberAsyncMutableState(defaultValue = null, updateDispatcher = reloadDispatcher) {
            context.scriptManager.getScriptsFolder()
        }
        val titles = listOf(translation["scripts_tab"], translation["catalog_tab"])
        var showImportDialog by remember { mutableStateOf(false) }
        val coroutineScope = rememberCoroutineScope()

        if (showImportDialog) {
            ImportRemoteScript(dismiss = { showImportDialog = false })
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(ScriptingSkinPalette.backgroundGradient)
        ) {
            ScriptingHeader(
                titles = titles,
                selectedTab = selectedTab,
                onTabSelected = { selectedTab = it },
                onImport = { showImportDialog = true },
                onOpenFolder = {
                    activityLauncherHelper.chooseFolder {
                        context.config.root.scripting.moduleFolder.set(it)
                        context.config.writeConfig()
                        coroutineScope.launch { reloadDispatcher.dispatch() }
                    }
                },
                onManageRepos = { routes.navController.navigate("manage_repos") },
                onDocs = {
                    context.androidContext.openLink(
                        "https://www.purrfectgit.com/r/particle-box/purrfect/wiki/Scripting",
                        context.translation["toast_open_link_failed"]
                    )
                },
                folderSelected = scriptingFolder.value != null
            )

            Spacer(modifier = Modifier.height(8.dp))

            when (selectedTab) {
                0 -> InstalledTabContent(scriptingFolder.value)
                1 -> CatalogTabContent(scriptingFolder.value)
            }
        }
    }

    override val content: @Composable (NavBackStackEntry) -> Unit = { nav ->
        val themeId by produceState(initialValue = context.config.root.global.uiSettings.managerTheme.get()) {
            while (true) {
                delay(300)
                value = context.config.root.global.uiSettings.managerTheme.get()
            }
        }

        key(themeId) {
            with(ManagerTheme.fromId(themeId).theme) {
                this@ScriptingRootSection.ScriptingScreen(nav)
            }
        }
    }

    @Composable
    internal fun InstalledTabContent(
        scriptingFolder: DocumentFile?
    ) {
        val scriptModules by rememberAsyncMutableState(
            defaultValue = emptyList(),
            updateDispatcher = reloadDispatcher
        ) { context.scriptManager.sync(); context.scriptManager.getSyncedModules() }
        val coroutineScope = rememberCoroutineScope()
        var refreshing by remember { mutableStateOf(false) }

        LaunchedEffect(Unit) {
            refreshing = true
            withContext(Dispatchers.IO) {
                reloadDispatcher.dispatch()
                refreshing = false
            }
        }
        val pullRefreshState = rememberPullRefreshState(refreshing, onRefresh = {
            refreshing = true
            coroutineScope.launch(Dispatchers.IO) {
                reloadDispatcher.dispatch()
                refreshing = false
            }
        })

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = ScriptingSkinPalette.textPrimary.copy(alpha = 0.04f),
                tonalElevation = 0.dp,
                shadowElevation = 0.dp,
                border = BorderStroke(1.dp, ScriptingSkinPalette.textPrimary.copy(alpha = 0.08f))
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .pullRefresh(pullRefreshState),
                        contentPadding = PaddingValues(bottom = routes.bottomPadding + 28.dp, start = 8.dp, end = 8.dp, top = 12.dp),
                        horizontalAlignment = Alignment.Start,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        item {
                            if (scriptingFolder == null && !refreshing) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 260.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    val glowPrimary = ScriptingSkinPalette.glowPrimary
                                    val glowSecondary = ScriptingSkinPalette.glowSecondary
                                    val borderBrush = remember(glowPrimary, glowSecondary) {
                                        Brush.linearGradient(
                                            listOf(
                                                glowPrimary.copy(alpha = 0.6f),
                                                glowSecondary.copy(alpha = 0.45f)
                                            )
                                        )
                                    }
                                    Surface(
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(24.dp),
                                        color = ScriptingSkinPalette.textPrimary.copy(alpha = 0.05f),
                                        border = BorderStroke(
                                            1.dp,
                                            borderBrush
                                        )
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 20.dp, vertical = 22.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.spacedBy(14.dp)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(58.dp)
                                                    .clip(RoundedCornerShape(18.dp))
                                                    .background(ScriptingSkinPalette.textPrimary.copy(alpha = 0.08f)),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.FolderOpen,
                                                    contentDescription = null,
                                                    tint = glowSecondary,
                                                    modifier = Modifier.size(28.dp)
                                                )
                                            }
                                            Text(
                                                text = translation["no_scripts_folder_selected_title"],
                                                style = MaterialTheme.typography.headlineSmall,
                                                fontWeight = FontWeight.ExtraBold,
                                                textAlign = TextAlign.Center,
                                                color = ScriptingSkinPalette.textPrimary
                                            )
                                            Text(
                                                text = translation["select_scripts_folder_toast"],
                                                style = MaterialTheme.typography.bodyMedium,
                                                textAlign = TextAlign.Center,
                                                color = ScriptingSkinPalette.textSecondary,
                                                lineHeight = 18.sp
                                            )
                                            SelectFolderButton(
                                                onClick = {
                                                    activityLauncherHelper.chooseFolder {
                                                        context.config.root.scripting.moduleFolder.set(it)
                                                        context.config.writeConfig()
                                                        coroutineScope.launch { reloadDispatcher.dispatch() }
                                                    }
                                                }
                                            )
                                        }
                                    }
                                }
                            } else if (scriptModules.isEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(220.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    AestheticEmptyState(
                                        icon = Icons.Default.DataObject,
                                        title = translation["no_scripts_found_title"],
                                        subtitle = translation["use_catalog_to_add_scripts"],
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 22.dp)
                                    )
                                }
                            }
                        }
                        items(scriptModules.size, key = { scriptModules[it].name.hashCode() }) { index ->
                            ModuleItem(scriptModules[index])
                        }
                    }
                    PullRefreshIndicator(
                        refreshing = refreshing,
                        state = pullRefreshState,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 8.dp)
                    )
                }
            }
        }
        var scriptingWarning by remember {
            mutableStateOf(context.sharedPreferences.run {
                getBoolean("scripting_warning", true).also {
                    edit().putBoolean("scripting_warning", false).apply()
                }
            })
        }
        if (scriptingWarning) {
            var timeout by remember { mutableIntStateOf(10) }
            LaunchedEffect(Unit) {
                while (timeout > 0) {
                    delay(1000)
                    timeout--
                }
            }
        val glowPrimary = ScriptingSkinPalette.glowPrimary
        val glowSecondary = ScriptingSkinPalette.glowSecondary
        val borderBrush = remember(glowPrimary, glowSecondary) {
            Brush.linearGradient(
                listOf(
                    glowPrimary.copy(alpha = 0.55f),
                    glowSecondary.copy(alpha = 0.35f)
                )
            )
        }
        AestheticDialog(
            onDismissRequest = { if (timeout == 0) scriptingWarning = false },
            title = context.translation["manager.dialogs.scripting_warning.title"] ?: "Scripting Warning",
            text = context.translation["manager.dialogs.scripting_warning.content"] ?: "Be careful with third-party scripts.",
            icon = Icons.Default.Warning,
            confirmButtonText = translation["button.ok"],
            onConfirm = { if (timeout == 0) scriptingWarning = false },
            loading = timeout > 0,
            showCloseButton = false,
            customContent = {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = ScriptingSkinPalette.cardOverlayColor,
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp,
                    border = BorderStroke(
                        1.dp,
                        borderBrush
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .background(
                                    Brush.radialGradient(
                                        listOf(
                                            glowPrimary.copy(alpha = 0.25f),
                                            glowSecondary.copy(alpha = 0.18f)
                                        )
                                    ),
                                    shape = RoundedCornerShape(18.dp)
                                ),
                            contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = timeout.toString(),
                                    color = ScriptingSkinPalette.textPrimary,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 20.sp
                                )
                            }
                    }
                }
            }
        )
        }
    }

    @Composable
    internal fun CatalogTabContent(
        scriptingFolder: DocumentFile?
    ) {
        val coroutineScope = rememberCoroutineScope()
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = ScriptingSkinPalette.textPrimary.copy(alpha = 0.04f),
                tonalElevation = 0.dp,
                shadowElevation = 0.dp,
                border = BorderStroke(1.dp, ScriptingSkinPalette.textPrimary.copy(alpha = 0.08f))
            ) {
                if (scriptingFolder == null) {
                    AestheticEmptyState(
                        icon = Icons.Default.FolderOpen,
                        title = translation["no_scripts_folder_selected_title"],
                        subtitle = translation["select_scripts_folder_toast"],
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 22.dp)
                    )
                } else {
                    ScriptCatalog(this@ScriptingRootSection)
                }
            }
        }
    }

    @Composable
    internal fun ScriptingHeader(
        titles: List<String>,
        selectedTab: Int,
        onTabSelected: (Int) -> Unit,
        onImport: () -> Unit,
        onOpenFolder: () -> Unit,
        onManageRepos: () -> Unit,
        onDocs: () -> Unit,
        folderSelected: Boolean
    ) {
        val glowPrimary = ScriptingSkinPalette.glowPrimary
        val glowSecondary = ScriptingSkinPalette.glowSecondary
        val borderBrush = remember(glowPrimary, glowSecondary) {
            Brush.linearGradient(
                listOf(
                    glowPrimary.copy(alpha = 0.55f),
                    glowSecondary.copy(alpha = 0.35f)
                )
            )
        }
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp)
                .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()),
            shape = RoundedCornerShape(26.dp),
            color = ScriptingSkinPalette.textPrimary.copy(alpha = 0.07f),
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            border = BorderStroke(1.dp, borderBrush)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = translation["manager.routes.scripts"],
                            color = ScriptingSkinPalette.textPrimary,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 18.sp
                        )
                    }
                    Row(
                        modifier = Modifier.wrapContentWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onDocs) {
                            Icon(Icons.Default.CollectionsBookmark, contentDescription = translation["documentation_button"], tint = ScriptingSkinPalette.textPrimary)
                        }
                        IconButton(onClick = onManageRepos) {
                            Icon(Icons.Default.Public, contentDescription = translation["manage_repos_button"], tint = ScriptingSkinPalette.textPrimary)
                        }
                        IconButton(onClick = onOpenFolder) {
                            Icon(Icons.Default.FolderOpen, contentDescription = translation["open_scripts_folder_button"], tint = ScriptingSkinPalette.textPrimary)
                        }
                        IconButton(onClick = onImport, enabled = folderSelected) {
                            Icon(Icons.Default.Link, contentDescription = translation["import_from_url_button"], tint = if (folderSelected) Color.White else ScriptingSkinPalette.textPrimary.copy(alpha = 0.4f))
                        }
                    }
                }
                ScriptingTabSwitcher(
                    titles = titles,
                    selectedTab = selectedTab,
                    onTabSelected = onTabSelected
                )
            }
        }
    }

    @Composable
    internal fun ScriptingTabSwitcher(
        titles: List<String>,
        selectedTab: Int,
        onTabSelected: (Int) -> Unit
    ) {
        val glowPrimary = ScriptingSkinPalette.glowPrimary
        val glowSecondary = ScriptingSkinPalette.glowSecondary
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            titles.forEachIndexed { index, title ->
                val isSelected = selectedTab == index
                val indicatorBrush = remember(glowPrimary, glowSecondary) {
                    Brush.linearGradient(listOf(glowPrimary, glowSecondary))
                }
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = if (isSelected) ScriptingSkinPalette.textPrimary.copy(alpha = 0.12f) else ScriptingSkinPalette.textPrimary.copy(alpha = 0.06f),
                    border = if (isSelected) BorderStroke(
                        1.dp,
                        indicatorBrush
                    ) else BorderStroke(1.dp, ScriptingSkinPalette.textPrimary.copy(alpha = 0.12f)),
                    modifier = Modifier.weight(1f)
                ) {
                    Row(
                        modifier = Modifier
                            .clickable { onTabSelected(index) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (index == 0) Icons.Default.Extension else Icons.Default.Public,
                            contentDescription = null,
                            tint = ScriptingSkinPalette.textPrimary
                        )
                        Text(
                            text = title,
                            color = ScriptingSkinPalette.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }

    override val topBarActions: @Composable() (RowScope.() -> Unit) = {}
}
