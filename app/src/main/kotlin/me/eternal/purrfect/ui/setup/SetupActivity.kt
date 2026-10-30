@file:OptIn(androidx.compose.animation.ExperimentalAnimationApi::class)

package me.eternal.purrfect.ui.setup

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import me.eternal.purrfect.RemoteSideContext
import me.eternal.purrfect.SharedContextHolder
import me.eternal.purrfect.common.TargetApp
import me.eternal.purrfect.common.ui.AppMaterialTheme
import me.eternal.purrfect.ui.manager.ManagerAssistantDialog
import me.eternal.purrfect.ui.manager.Routes
import me.eternal.purrfect.ui.manager.components.AestheticDialog
import me.eternal.purrfect.common.ui.theme.LocalPurrfectSkin
import me.eternal.purrfect.common.ui.theme.AphelionSkinProvider
import me.eternal.purrfect.common.ui.util.G2RoundedRectangle
import me.eternal.purrfect.ui.setup.screens.LocalSetupScrollState
import me.eternal.purrfect.ui.setup.screens.LocalSetupViewportHeight
import me.eternal.purrfect.ui.setup.screens.SetupScreen
import me.eternal.purrfect.ui.setup.screens.impl.InstallModeScreen
import me.eternal.purrfect.ui.setup.screens.impl.InstallMode
import me.eternal.purrfect.ui.setup.screens.impl.MappingsScreen
import me.eternal.purrfect.ui.setup.screens.impl.PermissionsScreen
import me.eternal.purrfect.ui.setup.screens.impl.PickLanguageScreen
import me.eternal.purrfect.ui.setup.screens.impl.PatchSnapchatScreen
import me.eternal.purrfect.ui.setup.screens.impl.RootInstallSnapchatScreen
import me.eternal.purrfect.ui.setup.screens.impl.SaveFolderScreen
import me.eternal.purrfect.ui.setup.screens.impl.IntroShowcaseScreen
import me.eternal.purrfect.ui.setup.screens.impl.ThemeSelectorScreen
import me.eternal.purrfect.ui.setup.screens.impl.parseSetupTargetApps
import me.eternal.purrfect.ui.setup.screens.impl.toSetupTargetPrefsValue
import me.eternal.purrfect.ui.util.ActivityLauncherHelper
import me.eternal.purrfect.ui.util.scaleOnPress
import kotlinx.coroutines.delay
import me.eternal.purrfect.common.Constants
import me.eternal.purrfect.common.bridge.InternalFileHandleType

private const val SETUP_SELECTED_APPS_PREF = SetupPreferences.PROGRESS_SELECTED_TARGET_APPS_PREF
private const val SNAPCHAT_BACKGROUND_RETRIGGER_ACTION =
    "me.eternal.purrfect.action.SNAPCHAT_BACKGROUND_RETRIGGER"

private data class SetupStepMeta(
    val route: String,
    val title: String,
    val subtitle: String,
    val icon: ImageVector
)

class SetupActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val setupContext = SharedContextHolder.remote(this).apply {
            activity = this@SetupActivity
        }
        val mappingCompletionMode = intent.getStringExtra(Constants.MAPPINGS_COMPLETION_MODE_EXTRA)
        fun endActivity() {
            setupContext.reload()
            sendBroadcast(Intent("me.eternal.purrfect.RESTART"))
            clearMappingsGenerationState(setupContext)
            handleMappingCompletion(mappingCompletionMode, setupContext)
            finish()
        }
        val requirements = intent.getIntExtra("requirements", Requirements.FIRST_RUN)
        val isRedditRepatchFlow = requirements and Requirements.REDDIT_REPATCH == Requirements.REDDIT_REPATCH
        val isSnapchatInstallFlow = requirements and Requirements.INSTALL_SNAPCHAT == Requirements.INSTALL_SNAPCHAT
        val isRedditInstallFlow = requirements and Requirements.INSTALL_REDDIT == Requirements.INSTALL_REDDIT
        val isRedditUpdateFlow = requirements and Requirements.UPDATE_REDDIT == Requirements.UPDATE_REDDIT
        val isInstagramInstallFlow = requirements and Requirements.INSTALL_INSTAGRAM == Requirements.INSTALL_INSTAGRAM
        val isTargetInstallFlow = isSnapchatInstallFlow || isRedditInstallFlow || isRedditUpdateFlow || isInstagramInstallFlow
        val targetInstallApps = when {
            isSnapchatInstallFlow -> setOf(TargetApp.SNAPCHAT)
            isRedditInstallFlow || isRedditUpdateFlow -> setOf(TargetApp.REDDIT)
            isInstagramInstallFlow -> setOf(TargetApp.INSTAGRAM)
            else -> emptySet()
        }
        val setupPrefs = setupContext.sharedPreferences
        val setupRoutes = Routes(setupContext).apply {
            activityLauncher = ActivityLauncherHelper(this@SetupActivity)
        }
        fun hasRequirement(requirement: Int) = requirements and requirement == requirement
        val wasInProgress = setupPrefs.getBoolean("setup_in_progress", false)
        val isFirstRunFlow = hasRequirement(Requirements.FIRST_RUN) || wasInProgress
        val persistedRoute = setupPrefs.getString("setup_current_route", null)
        val persistedSkipPatch = setupPrefs.getBoolean(SetupPreferences.PROGRESS_SKIP_PATCH_PREF, false)
        val persistedInstallMode = setupPrefs.getString(SetupPreferences.PROGRESS_INSTALL_MODE_PREF, null)
        val persistedSelectedApps = setupPrefs.getString(SETUP_SELECTED_APPS_PREF, null)
            ?: setupPrefs.getString(SetupPreferences.LEGACY_PROGRESS_SELECTED_TARGET_APPS_PREF, null)
        val storedInstallMode = SetupPreferences.lastInstallModeName(setupPrefs)
            ?.let { runCatching { InstallMode.valueOf(it) }.getOrNull() }
        val storedSkipAutoSetup = SetupPreferences.wasAutoSetupSkipped(setupPrefs)
        val skipPatchChoice = mutableStateOf(if (isTargetInstallFlow) storedSkipAutoSetup else persistedSkipPatch)
        val installModeChoice = mutableStateOf(
            runCatching { persistedInstallMode?.let { InstallMode.valueOf(it) } }.getOrNull()
                ?: if (isTargetInstallFlow && !storedSkipAutoSetup) storedInstallMode else null
        )
        val selectedAppsChoice = mutableStateOf(
            when {
                isRedditRepatchFlow -> setOf(TargetApp.REDDIT)
                isTargetInstallFlow -> targetInstallApps
                else -> parseSetupTargetApps(
                    persistedSelectedApps,
                    fallback = if (wasInProgress && persistedRoute != null) setOf(TargetApp.SNAPCHAT) else emptySet()
                )
            }
        )

        fun persistProgress(
            route: String,
            skipPatch: Boolean,
            installMode: InstallMode?,
            selectedApps: Set<TargetApp>,
            inProgress: Boolean = true
        ) {
            if (!isFirstRunFlow) return
            setupPrefs.edit()
                .putBoolean("setup_in_progress", inProgress)
                .putString("setup_current_route", route)
                .putBoolean(SetupPreferences.PROGRESS_SKIP_PATCH_PREF, skipPatch)
                .putString(SetupPreferences.PROGRESS_INSTALL_MODE_PREF, installMode?.name)
                .putString(SETUP_SELECTED_APPS_PREF, selectedApps.toSetupTargetPrefsValue())
                .commit()
        }

        fun clearProgress() {
            setupPrefs.edit()
                .remove("setup_in_progress")
                .remove("setup_current_route")
                .remove(SetupPreferences.PROGRESS_SKIP_PATCH_PREF)
                .remove(SetupPreferences.PROGRESS_INSTALL_MODE_PREF)
                .remove(SETUP_SELECTED_APPS_PREF)
                .remove(SetupPreferences.LEGACY_PROGRESS_SELECTED_TARGET_APPS_PREF)
                .commit()
        }

        val requiredScreens = mutableListOf<SetupScreen>().apply {
            if (isFirstRunFlow) {
                add(ThemeSelectorScreen().apply { route = "themeSelector" })
            }
            if (isFirstRunFlow || hasRequirement(Requirements.LANGUAGE)) {
                add(PickLanguageScreen().apply { route = "language" })
                if (isFirstRunFlow) {
                    add(IntroShowcaseScreen(
                        selectedAppsProvider = { selectedAppsChoice.value },
                        onSelectionChanged = { selectedAppsChoice.value = it }
                    ).apply { route = "introShowcase" })
                }
                if (isFirstRunFlow) {
                    add(InstallModeScreen(
                        onModeChosen = { mode ->
                            installModeChoice.value = mode
                            skipPatchChoice.value = false
                        },
                        onSkipAutoSetup = {
                            skipPatchChoice.value = true
                            installModeChoice.value = null
                        }
                    ).apply { route = "installMode" })
                }
            }
            if (isTargetInstallFlow && (installModeChoice.value == null || skipPatchChoice.value)) {
                add(InstallModeScreen(
                    onModeChosen = { mode ->
                        installModeChoice.value = mode
                        skipPatchChoice.value = false
                    },
                    onSkipAutoSetup = {
                        skipPatchChoice.value = true
                        installModeChoice.value = null
                    },
                    allowSkip = false
                ).apply { route = "installMode" })
            }
            if (isFirstRunFlow || isTargetInstallFlow) {
                add(RootInstallSnapchatScreen(
                    selectedAppsProvider = { selectedAppsChoice.value },
                    allowInstalledTarget = isRedditUpdateFlow
                ).apply { route = "rootInstallSnapchat" })
                add(PatchSnapchatScreen(
                    selectedAppsProvider = { selectedAppsChoice.value },
                    flow = if (isRedditUpdateFlow) {
                        me.eternal.purrfect.ui.setup.screens.impl.SetupInstallFlow.REPATCH
                    } else {
                        me.eternal.purrfect.ui.setup.screens.impl.SetupInstallFlow.PATCH
                    }
                ).apply { route = "patchSnapchat" })
            }
            if (isRedditRepatchFlow) {
                add(PatchSnapchatScreen(
                    selectedAppsProvider = { setOf(TargetApp.REDDIT) },
                    flow = me.eternal.purrfect.ui.setup.screens.impl.SetupInstallFlow.REPATCH
                ).apply { route = "repatchReddit" })
            }
            if (isFirstRunFlow || hasRequirement(Requirements.GRANT_PERMISSIONS) || isSnapchatInstallFlow) {
                add(PermissionsScreen(
                    selectedAppsProvider = { selectedAppsChoice.value }
                ).apply { route = "permissions" })
            }
            if (isFirstRunFlow || hasRequirement(Requirements.SAVE_FOLDER) || isSnapchatInstallFlow) {
                add(SaveFolderScreen().apply { route = "saveFolder" })
            }
            if (isFirstRunFlow || hasRequirement(Requirements.MAPPINGS) || isSnapchatInstallFlow) {
                add(MappingsScreen().apply { route = "mappings" })
            }
        }

        if (requiredScreens.isEmpty()) {
            endActivity()
            return
        }
        requiredScreens.forEach { screen ->
            screen.context = setupContext
            screen.isFirstRunFlow = isFirstRunFlow
            screen.init()
        }

            if (!isFirstRunFlow && !isTargetInstallFlow) {
                clearProgress()
                skipPatchChoice.value = false
                installModeChoice.value = null
                selectedAppsChoice.value = emptySet()
            }

        setContent {
            val context = LocalContext.current
            AphelionSkinProvider(context = context) {
                val skin = LocalPurrfectSkin.current
                val translation = setupContext.translation
            val navController = rememberNavController()
            var canGoNext by remember { mutableStateOf(false) }
            var lastRoute by rememberSaveable { mutableStateOf("") }
            var currentRoute by rememberSaveable {
                mutableStateOf(
                    persistedRoute?.takeIf { route -> requiredScreens.any { it.route == route } }
                        ?: requiredScreens.first().route
                )
            }
            var skipAction by remember { mutableStateOf<(() -> Unit)?>(null) }
            var skipLabel by remember { mutableStateOf<String?>(null) }

            val skipPatch by rememberSaveable { skipPatchChoice }
            val installMode by installModeChoice
            val selectedApps by selectedAppsChoice
            val shouldShowAbiWarning = remember {
                val deviceIsArm64 = Build.SUPPORTED_ABIS.any { it == "arm64-v8a" || it.startsWith("arm64") }
                val libDir = context.applicationInfo.nativeLibraryDir.orEmpty()
                val appIsArm64 = libDir.contains("arm64")
                deviceIsArm64 && !appIsArm64
            }
            if (shouldShowAbiWarning) {
                AestheticDialog(
                    onDismissRequest = {},
                    title = translation["setup.activity.wrong_apk_title"],
                    text = "",
                    icon = Icons.Filled.Warning,
                    confirmButtonText = translation["setup.activity.close_button"],
                    onConfirm = { (context as? Activity)?.finishAffinity() },
                    showCloseButton = false,
                    opaque = true,
                    customContent = {
                        Text(
                            text = translation["setup.activity.wrong_apk_message"],
                            color = skin.textSecondary,
                            lineHeight = 18.sp
                        )
                    }
                )
            }
            val visibleScreens = remember(skipPatch, installMode, selectedApps) {
                requiredScreens.filterNot { screen ->
                    if (!isRedditRepatchFlow && skipPatch && (screen is PatchSnapchatScreen || screen is RootInstallSnapchatScreen)) {
                        return@filterNot true
                    }
                    if (!isRedditRepatchFlow && installMode == null && (screen is PatchSnapchatScreen || screen is RootInstallSnapchatScreen)) {
                        return@filterNot true
                    }
                    if (!isRedditRepatchFlow && installMode == InstallMode.ROOT && screen is PatchSnapchatScreen) {
                        return@filterNot true
                    }
                    if (installMode == InstallMode.NON_ROOT && screen is RootInstallSnapchatScreen) {
                        return@filterNot true
                    }
                    if (selectedApps.isNotEmpty() && TargetApp.SNAPCHAT !in selectedApps && screen is MappingsScreen) {
                        return@filterNot true
                    }
                    if (selectedApps.isNotEmpty() &&
                        selectedApps.all { it == TargetApp.REDDIT || it == TargetApp.INSTAGRAM } &&
                        screen is SaveFolderScreen
                    ) {
                        return@filterNot true
                    }
                    false
                }
            }
            val stepMeta = remember(skipPatch, installMode, selectedApps) { visibleScreens.map { it.meta(setupContext) } }
            val currentStepIndex = visibleScreens.indexOfFirst { it.route == currentRoute }.let {
                if (it == -1) 0 else it
            }
            val animatedProgress by animateFloatAsState(
                targetValue = (currentStepIndex + 1f) / stepMeta.size.toFloat(),
                label = "SetupProgress"
            )
            LaunchedEffect(skipPatch, installMode, selectedApps) {
                val adjustedRoute = visibleScreens.firstOrNull { it.route == currentRoute }?.route
                    ?: run {
                        val currentIndex = requiredScreens.indexOfFirst { it.route == currentRoute }.coerceAtLeast(0)
                        val nextVisible = requiredScreens.drop(currentIndex + 1)
                            .firstOrNull { screen -> visibleScreens.any { it.route == screen.route } }
                        nextVisible?.route ?: visibleScreens.firstOrNull()?.route
                    }
                adjustedRoute?.let {
                    if (it != currentRoute) currentRoute = it
                }
            }

            LaunchedEffect(currentRoute, skipPatch, installMode, selectedApps) {
                persistProgress(currentRoute, skipPatch, installMode, selectedApps, true)
                if (navController.currentDestination?.route != currentRoute) {
                    navController.navigate(currentRoute) {
                        popUpTo(requiredScreens.first().route) { inclusive = false }
                        launchSingleTop = true
                    }
                }
                if (lastRoute != currentRoute) {
                    canGoNext = false
                    skipAction = null
                    skipLabel = null
                    lastRoute = currentRoute
                }
            }

            fun nextScreen() {
                if (!canGoNext) return
                canGoNext = false
                val currentScreen = visibleScreens.getOrNull(currentStepIndex)
                val proceed = {
                    currentScreen?.onLeave()
                    if (currentStepIndex < visibleScreens.lastIndex) {
                        val nextRoute = visibleScreens[currentStepIndex + 1].route
                        currentRoute = nextRoute
                    } else {
                        val preferredTarget = listOf(TargetApp.SNAPCHAT, TargetApp.INSTAGRAM, TargetApp.REDDIT, TargetApp.WHATSAPP)
                            .firstOrNull { it in selectedApps }
                        if (isFirstRunFlow || isTargetInstallFlow) {
                            SetupPreferences.saveSetupChoices(
                                setupPrefs,
                                selectedApps = selectedApps,
                                installModeName = installMode?.name,
                                skippedAutoSetup = skipPatch
                            )
                        }
                        if (isFirstRunFlow || (isTargetInstallFlow && !skipPatch)) {
                            SetupPreferences.addCompletedTargets(setupPrefs, selectedApps)
                        }
                        if (isFirstRunFlow || !skipPatch) {
                            preferredTarget?.let { setupContext.setActiveTargetApp(it) }
                        }
                        clearProgress()
                        endActivity()
                    }
                }
                currentScreen?.onNext { proceed() } ?: proceed()
            }

            AppMaterialTheme {
                val view = LocalView.current
                val navBarPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                var setupAiPrompt by rememberSaveable { mutableStateOf<String?>(null) }
                SideEffect {
                    val window = (view.context as Activity).window
                    WindowCompat.setDecorFitsSystemWindows(window, false)
                    @Suppress("DEPRECATION")
                    window.statusBarColor = Color.Transparent.toArgb()
                    @Suppress("DEPRECATION")
                    window.navigationBarColor = Color.Transparent.toArgb()
                    val insetsController = WindowInsetsControllerCompat(window, window.decorView)
                    insetsController.isAppearanceLightStatusBars = false
                    insetsController.isAppearanceLightNavigationBars = false
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Transparent)
                ) {
                    SetupAuroraBackground()
                    SetupTopBar(
                        onHome = {
                            val firstRoute = visibleScreens.firstOrNull()?.route ?: requiredScreens.first().route
                            if (currentRoute != firstRoute) {
                                canGoNext = false
                                skipAction = null
                                skipLabel = null
                                currentRoute = firstRoute
                            }
                        },
                        onAskAi = { setupAiPrompt = "hi" }
                    )
                    val bottomPadding = 80.dp + navBarPadding
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(
                                start = 16.dp,
                                end = 16.dp,
                                top = 98.dp,
                                bottom = bottomPadding
                            )
                            .statusBarsPadding(),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        SetupHeader(
                            currentStep = stepMeta[currentStepIndex],
                            currentIndex = currentStepIndex,
                            total = stepMeta.size
                        )
                        SetupProgressBar(animatedProgress)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .padding(horizontal = 4.dp)
                        ) {
                            NavHost(
                                navController = navController,
                                startDestination = requiredScreens.first().route,
                                enterTransition = { fadeIn() },
                                exitTransition = { fadeOut() },
                                popEnterTransition = { fadeIn() },
                                popExitTransition = { fadeOut() }
                            ) {
                                requiredScreens.forEach { screen ->
                                    val screenRoute = screen.route
                                    screen.allowNext = allowNext@{ canGoNextFlag ->
                                        if (screenRoute != currentRoute) return@allowNext
                                        canGoNext = canGoNextFlag
                                    }
                                    screen.goNext = goNext@{
                                        if (screenRoute != currentRoute) return@goNext
                                        canGoNext = true
                                        nextScreen()
                                    }
                                    screen.showSkip = { label, action ->
                                        if (screenRoute == currentRoute) {
                                            skipLabel = label
                                            skipAction = action
                                        }
                                    }
                                    composable(
                                        screen.route,
                                        enterTransition = { slideInHorizontally { it } },
                                        exitTransition = { slideOutHorizontally { -it } },
                                        popEnterTransition = { slideInHorizontally { -it } },
                                        popExitTransition = { slideOutHorizontally { it } }
                                    ) {
                                        BackHandler(true) {}
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .padding(horizontal = 10.dp, vertical = 4.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            val scrollState = remember(screenRoute) { ScrollState(0) }
                                            LaunchedEffect(screenRoute) {
                                                scrollState.scrollTo(0)
                                            }
                                            BoxWithConstraints(
                                                modifier = Modifier
                                                    .widthIn(max = 560.dp)
                                                    .fillMaxHeight()
                                            ) {
                                                CompositionLocalProvider(
                                                    LocalSetupScrollState provides scrollState,
                                                    LocalSetupViewportHeight provides maxHeight
                                                ) {
                                                    Box(
                                                        modifier = Modifier.fillMaxSize(),
                                                        contentAlignment = Alignment.Center
                                                    ) {
                                                        screen.Content()
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding()
                            .padding(bottom = 16.dp)
                            .padding(horizontal = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (skipAction != null) {
                            SkipButton(
                                label = skipLabel ?: "Skip",
                                onClick = { skipAction?.invoke() }
                            )
                        }
                        NextButton(
                            enabled = canGoNext,
                            isFinalStep = currentStepIndex >= stepMeta.lastIndex,
                            onClick = { nextScreen() }
                        )
                    }
                    setupAiPrompt?.let { prompt ->
                        ManagerAssistantDialog(
                            context = setupContext,
                            routes = setupRoutes,
                            initialUserMessage = prompt,
                            onDismiss = { setupAiPrompt = null }
                        )
                    }
                }
            }
        }
    }
    }

    private fun handleMappingCompletion(mode: String?, setupContext: RemoteSideContext) {
        when (mode) {
            Constants.MAPPINGS_COMPLETION_MODE_FOREGROUND -> reopenSnapchatAfterMappings(setupContext)
            Constants.MAPPINGS_COMPLETION_MODE_BACKGROUND -> retriggerSnapchatInBackground(setupContext)
        }
    }

    private fun clearMappingsGenerationState(setupContext: RemoteSideContext) {
        runCatching {
            val file = InternalFileHandleType.MAPPINGS_GENERATION_STATE.resolve(applicationContext)
            val existed = file.exists()
            val deleted = file.delete()
            setupContext.log.verbose(
                "Cleared mappings generation state after setup completion; existed=$existed deleted=$deleted"
            )
        }.onFailure {
            setupContext.log.warn("Failed to clear mappings generation state after setup completion: ${it.message}")
        }
    }

    private fun reopenSnapchatAfterMappings(setupContext: RemoteSideContext) {
        val launchIntent = packageManager.getLaunchIntentForPackage(Constants.SNAPCHAT_PACKAGE_NAME)
        if (launchIntent == null) {
            setupContext.log.warn("Mapping completion requested foreground Snapchat reopen, but launch intent was null")
            return
        }

        setupContext.log.warn("Mapping completion reopening Snapchat in foreground")
        startActivity(
            launchIntent.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
        )
    }

    private fun retriggerSnapchatInBackground(setupContext: RemoteSideContext) {
        setupContext.log.warn("Mapping completion retriggering Snapchat in background")
        if (sendSnapchatBackgroundRetriggerBroadcast(setupContext)) return
        if (startSnapchatBackgroundRetriggerService(setupContext)) return
        setupContext.log.warn(
            "No non-root Snapchat background retrigger target accepted after mappings; " +
                "leaving Snapchat closed instead of opening it in foreground"
        )
    }

    private fun sendSnapchatBackgroundRetriggerBroadcast(setupContext: RemoteSideContext): Boolean {
        val candidates = snapchatBackgroundRetriggerReceivers(setupContext)
        setupContext.log.verbose(
            "Snapchat background retrigger receiver candidates: " +
                candidates.joinToString { it.name }
        )
        for (receiver in candidates.take(4)) {
            val delivered = runCatching {
                val intent = Intent(SNAPCHAT_BACKGROUND_RETRIGGER_ACTION).apply {
                    component = ComponentName(Constants.SNAPCHAT_PACKAGE_NAME, receiver.name)
                    setPackage(Constants.SNAPCHAT_PACKAGE_NAME)
                    addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                }
                sendBroadcast(intent)
                true
            }.onFailure {
                setupContext.log.warn(
                    "Failed Snapchat background retrigger broadcast to ${receiver.name}: ${it.message}"
                )
            }.getOrDefault(false)
            if (delivered) {
                setupContext.log.warn(
                    "Sent Snapchat background retrigger broadcast to ${receiver.name}"
                )
                return true
            }
        }
        return false
    }

    private fun startSnapchatBackgroundRetriggerService(setupContext: RemoteSideContext): Boolean {
        val candidates = snapchatBackgroundRetriggerServices(setupContext)
        setupContext.log.verbose(
            "Snapchat background retrigger service candidates: " +
                candidates.joinToString { it.name }
        )
        for (service in candidates.take(2)) {
            val started = runCatching {
                val intent = Intent(SNAPCHAT_BACKGROUND_RETRIGGER_ACTION).apply {
                    component = ComponentName(Constants.SNAPCHAT_PACKAGE_NAME, service.name)
                    setPackage(Constants.SNAPCHAT_PACKAGE_NAME)
                }
                startService(intent)
                true
            }.onFailure {
                setupContext.log.warn(
                    "Failed Snapchat background retrigger service ${service.name}: ${it.message}"
                )
            }.getOrDefault(false)
            if (started) {
                setupContext.log.warn(
                    "Started Snapchat background retrigger service ${service.name}"
                )
                return true
            }
        }
        return false
    }

    private fun snapchatBackgroundRetriggerReceivers(setupContext: RemoteSideContext): List<ActivityInfo> {
        val receivers = snapPackageInfo(
            setupContext = setupContext,
            flags = PackageManager.GET_RECEIVERS
        )?.receivers.orEmpty()
        return receivers
            .asSequence()
            .filter { it.exported && it.permission.isNullOrBlank() && it.name.isSafeRetriggerComponentName() }
            .sortedWith(compareByDescending<ActivityInfo> { it.name.receiverRetriggerScore() }.thenBy { it.name })
            .toList()
    }

    private fun snapchatBackgroundRetriggerServices(setupContext: RemoteSideContext): List<ServiceInfo> {
        val services = snapPackageInfo(
            setupContext = setupContext,
            flags = PackageManager.GET_SERVICES
        )?.services.orEmpty()
        return services
            .asSequence()
            .filter { it.exported && it.permission.isNullOrBlank() && it.name.isSafeRetriggerComponentName() }
            .sortedWith(compareByDescending<ServiceInfo> { it.name.serviceRetriggerScore() }.thenBy { it.name })
            .toList()
    }

    private fun snapPackageInfo(setupContext: RemoteSideContext, flags: Int): PackageInfo? {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(
                    Constants.SNAPCHAT_PACKAGE_NAME,
                    PackageManager.PackageInfoFlags.of(flags.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(Constants.SNAPCHAT_PACKAGE_NAME, flags)
            }
        }.onFailure {
            setupContext.log.warn("Failed to inspect Snapchat package for background retrigger: ${it.message}")
        }.getOrNull()
    }

    private fun String.isSafeRetriggerComponentName(): Boolean {
        val lowered = lowercase()
        val blockedTokens = listOf(
            "otp",
            "token",
            "logout",
            "payment",
            "razorpay",
            "rzp",
            "firebase",
            "gms",
            "amazon",
            "adm",
            "sms",
            "authapi"
        )
        return blockedTokens.none { lowered.contains(it) }
    }

    private fun String.receiverRetriggerScore(): Int {
        val lowered = lowercase()
        return when {
            "widgetprovider" in lowered -> 100
            "notification" in lowered -> 60
            "telecom" in lowered || "mute" in lowered -> 30
            else -> 1
        }
    }

    private fun String.serviceRetriggerScore(): Int {
        val lowered = lowercase()
        return when {
            "syncservice" in lowered -> 80
            "authenticatorservice" in lowered -> 40
            else -> 1
        }
    }
}

@Composable
private fun SkipButton(
    label: String,
    onClick: () -> Unit
) {
    val skin = LocalPurrfectSkin.current
    val interactionSource = remember { MutableInteractionSource() }
    val gradient = Brush.horizontalGradient(
        listOf(
            skin.glowSecondary,
            skin.glowPrimary
        )
    )
    val contentColor = if (skin.glowPrimary.luminance() > 0.5f) Color.Black.copy(alpha = 0.85f) else Color.White
    val buttonShape = if (skin.id == "AETHER") G2RoundedRectangle(36.dp) else RoundedCornerShape(36.dp)
    
    Surface(
        modifier = Modifier
            .scaleOnPress(interactionSource)
            .clip(buttonShape)
            .border(
                width = 1.dp,
                color = skin.textPrimary.copy(alpha = 0.24f),
                shape = buttonShape
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null
            ) { onClick() },
        color = Color.Transparent
    ) {
        Box(
            modifier = Modifier
                .background(gradient)
                .padding(horizontal = 24.dp, vertical = 14.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                color = contentColor,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp
            )
        }
    }
}

private fun SetupScreen.meta(context: RemoteSideContext): SetupStepMeta {
    val translation = context.translation
    return when (this) {
        is ThemeSelectorScreen -> SetupStepMeta(
            route = route,
            title = "App Theme",
            subtitle = "Choose your Aphelion skin",
            icon = Icons.Filled.Palette
        )

        is PickLanguageScreen -> SetupStepMeta(
            route = route,
            title = translation["setup.dialogs.select_language"],
            subtitle = translation["setup.activity.language_subtitle"],
            icon = Icons.Filled.Language
        )
        is IntroShowcaseScreen -> SetupStepMeta(
            route = route,
            title = "Supported apps",
            subtitle = "Choose Snapchat, Reddit, Instagram, or any mix",
            icon = Icons.Filled.AutoAwesome
        )

        is InstallModeScreen -> SetupStepMeta(
            route = route,
            title = translation["setup.activity.install_mode_title"],
            subtitle = translation["setup.activity.install_mode_subtitle"],
            icon = Icons.Filled.VerifiedUser
        )

        is PermissionsScreen -> SetupStepMeta(
            route = route,
            title = translation["setup.permissions.dialog"],
            subtitle = translation["setup.activity.permissions_subtitle"],
            icon = Icons.Filled.VerifiedUser
        )

        is PatchSnapchatScreen -> SetupStepMeta(
            route = route,
            title = translation["setup.activity.patch_title"],
            subtitle = translation["setup.activity.patch_subtitle"],
            icon = Icons.Filled.Download
        )

        is RootInstallSnapchatScreen -> SetupStepMeta(
            route = route,
            title = translation["setup.activity.root_install_title"],
            subtitle = translation["setup.activity.root_install_subtitle"],
            icon = Icons.Filled.Download
        )

        is SaveFolderScreen -> SetupStepMeta(
            route = route,
            title = translation["setup.dialogs.save_folder"],
            subtitle = translation["setup.activity.save_folder_subtitle"],
            icon = Icons.Filled.Folder
        )

        is MappingsScreen -> SetupStepMeta(
            route = route,
            title = translation["setup.mappings.dialog"],
            subtitle = translation["setup.activity.mappings_subtitle"],
            icon = Icons.Filled.AutoAwesome
        )

        else -> SetupStepMeta(route, route, route, Icons.Filled.Check)
    }
}

@Composable
private fun SetupAuroraBackground() {
    val skin = LocalPurrfectSkin.current
    val infiniteTransition = rememberInfiniteTransition(label = "setupAurora")
    val driftX by infiniteTransition.animateFloat(
        initialValue = -90f,
        targetValue = 140f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 16000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "driftX"
    )
    val driftY by infiniteTransition.animateFloat(
        initialValue = 60f,
        targetValue = -120f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 14000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "driftY"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(skin.backgroundGradient)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val primaryGlow = skin.glowPrimary.copy(alpha = 0.36f)
            val secondaryGlow = skin.glowSecondary.copy(alpha = 0.28f)
            drawRect(
                brush = Brush.linearGradient(
                    colors = listOf(
                        skin.textPrimary.copy(alpha = 0.06f),
                        Color.Transparent
                    ),
                    start = Offset(x = size.width * 0.15f, y = 0f),
                    end = Offset(x = size.width * 0.75f, y = size.height * 0.6f)
                ),
                size = this.size
            )
            drawRect(
                brush = Brush.linearGradient(
                    colors = listOf(
                        skin.glowPrimary.copy(alpha = 0.12f),
                        Color.Transparent
                    ),
                    start = Offset(x = 0f, y = size.height * 0.72f),
                    end = Offset(x = size.width, y = size.height)
                ),
                size = this.size
            )
        }
    }
}

@Composable
private fun SetupTopBar(
    onHome: () -> Unit,
    onAskAi: () -> Unit
) {
    val skin = LocalPurrfectSkin.current
    val topPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val contentColor = if (skin.isDark) Color.White else Color.Black.copy(alpha = 0.85f)
    
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .padding(top = topPadding),
        shape = if (skin.id == "AETHER") G2RoundedRectangle(28.dp) else RoundedCornerShape(28.dp),
        color = skin.glassSurface,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            Brush.linearGradient(
                listOf(
                    skin.glowPrimary.copy(alpha = 0.6f),
                    skin.glowSecondary.copy(alpha = 0.45f)
                )
            )
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Purrfect",
                color = skin.textPrimary,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 18.sp,
                modifier = Modifier.weight(1f)
            )
            Surface(
                modifier = Modifier.size(40.dp),
                shape = CircleShape,
                color = skin.textPrimary.copy(alpha = 0.08f),
                border = androidx.compose.foundation.BorderStroke(1.dp, skin.textPrimary.copy(alpha = 0.14f))
            ) {
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable(onClick = onHome),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Home,
                        contentDescription = "Setup home",
                        tint = contentColor,
                        modifier = Modifier.size(21.dp)
                    )
                }
            }
            Surface(
                shape = RoundedCornerShape(40),
                color = skin.textPrimary.copy(alpha = 0.08f),
                border = androidx.compose.foundation.BorderStroke(1.dp, skin.textPrimary.copy(alpha = 0.14f))
            ) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(40))
                        .clickable(onClick = onAskAi)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.SmartToy,
                        contentDescription = null,
                        tint = contentColor
                    )
                    Text(
                        text = "Ask AI",
                        color = contentColor,
                        fontWeight = FontWeight.Medium,
                        fontSize = 13.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun SetupHeader(
    currentStep: SetupStepMeta,
    currentIndex: Int,
    total: Int
) {
    val skin = LocalPurrfectSkin.current
    val translation = SharedContextHolder.remote(LocalContext.current).translation
    val contentColor = if (skin.isDark) Color.White else Color.Black.copy(alpha = 0.85f)
    
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(30),
                color = skin.textPrimary.copy(alpha = 0.08f),
                border = androidx.compose.foundation.BorderStroke(1.dp, skin.textPrimary.copy(alpha = 0.14f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Flag,
                        contentDescription = null,
                        tint = skin.glowPrimary
                    )
                    Text(
                        text = translation.format(
                            "setup.activity.step_counter",
                            "current" to (currentIndex + 1).toString(),
                            "total" to total.toString()
                        ),
                        color = contentColor,
                        fontWeight = FontWeight.Medium,
                        fontSize = 13.sp
                    )
                }
            }
            Surface(
                shape = RoundedCornerShape(30),
                color = skin.glowPrimary.copy(alpha = 0.16f),
                border = androidx.compose.foundation.BorderStroke(1.dp, skin.glowPrimary.copy(alpha = 0.35f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = currentStep.icon,
                        contentDescription = null,
                        tint = skin.glowPrimary
                    )
                    Text(
                        text = currentStep.title,
                        color = contentColor,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

private enum class StepState { COMPLETE, ACTIVE, UPCOMING }

@Composable
private fun StepBadgesRow(steps: List<SetupStepMeta>, currentStep: Int) {
    val skin = LocalPurrfectSkin.current
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        steps.forEachIndexed { index, step ->
            val state = when {
                index < currentStep -> StepState.COMPLETE
                index == currentStep -> StepState.ACTIVE
                else -> StepState.UPCOMING
            }
            StepBadge(step, state)
            if (index < steps.lastIndex) {
                Box(
                    modifier = Modifier
                        .height(3.dp)
                        .width(28.dp)
                        .background(
                            Brush.horizontalGradient(
                                listOf(
                                    skin.textPrimary.copy(alpha = 0.05f),
                                    skin.glowPrimary.copy(alpha = 0.4f),
                                    skin.textPrimary.copy(alpha = 0.05f)
                                )
                            ),
                            shape = if (skin.id == "AETHER") G2RoundedRectangle(20.dp) else RoundedCornerShape(20.dp)
                        )
                )
            }
        }
    }
}

@Composable
private fun StepBadge(step: SetupStepMeta, state: StepState) {
    val skin = LocalPurrfectSkin.current
    val translation = SharedContextHolder.remote(LocalContext.current).translation
    val baseColor = when (state) {
        StepState.COMPLETE -> skin.glowSecondary
        StepState.ACTIVE -> skin.glowPrimary
        StepState.UPCOMING -> skin.textPrimary.copy(alpha = 0.35f)
    }
    val background = when (state) {
        StepState.UPCOMING -> skin.textPrimary.copy(alpha = 0.05f)
        StepState.COMPLETE -> skin.textPrimary.copy(alpha = 0.08f)
        StepState.ACTIVE -> skin.textPrimary.copy(alpha = 0.12f)
    }
    Surface(
        shape = if (skin.id == "AETHER") G2RoundedRectangle(18.dp) else RoundedCornerShape(18.dp),
        color = background,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            baseColor.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Surface(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape),
                color = baseColor.copy(alpha = 0.22f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = step.icon,
                        contentDescription = null,
                        tint = if (state == StepState.UPCOMING) skin.textPrimary.copy(alpha = 0.5f) else skin.textPrimary,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
            }
            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.widthIn(min = 0.dp, max = 160.dp)
            ) {
                Text(
                    text = step.title,
                    color = skin.textPrimary,
                    fontWeight = if (state == StepState.ACTIVE) FontWeight.Bold else FontWeight.SemiBold,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val hint = when (state) {
                    StepState.COMPLETE -> translation["setup.activity.step_complete"]
                    StepState.ACTIVE -> translation["setup.activity.step_active"]
                    StepState.UPCOMING -> translation["setup.activity.step_upcoming"]
                }
                Text(
                    text = hint,
                    color = skin.textSecondary.copy(alpha = 0.7f),
                    fontSize = 11.sp
                )
            }
        }
    }
}

@Composable
private fun SetupProgressBar(progress: Float) {
    val skin = LocalPurrfectSkin.current
    val gradient = Brush.horizontalGradient(
        listOf(
            skin.glowSecondary,
            skin.glowPrimary
        )
    )
    Surface(
        shape = if (skin.id == "AETHER") G2RoundedRectangle(18.dp) else RoundedCornerShape(18.dp),
        color = skin.textPrimary.copy(alpha = 0.08f),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            skin.textPrimary.copy(alpha = 0.12f)
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(progress.coerceIn(0f, 1f))
                    .background(gradient)
            )
        }
    }
}

@Composable
private fun SetupContentCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val skin = LocalPurrfectSkin.current
    val cardShape = if (skin.id == "AETHER") G2RoundedRectangle(34.dp) else RoundedCornerShape(34.dp)
    Surface(
        modifier = modifier,
        shape = cardShape,
        color = if (skin.id == "AETHER") skin.cardOverlayColor else skin.glassSurface,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            Brush.linearGradient(
                listOf(
                    skin.glowPrimary.copy(alpha = 0.45f),
                    skin.glowSecondary.copy(alpha = 0.35f)
                )
            )
        ),
        shadowElevation = 0.dp,
        tonalElevation = 0.dp
    ) {
        Box(
            modifier = Modifier
                .background(if (skin.id == "AETHER") skin.cardOverlayColor else skin.cardOverlayColor.copy(alpha = 0.28f))
                .padding(horizontal = 16.dp, vertical = 16.dp)
        ) {
            content()
        }
    }
}

@Composable
private fun NextButton(
    enabled: Boolean,
    isFinalStep: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val skin = LocalPurrfectSkin.current
    val translation = SharedContextHolder.remote(LocalContext.current).translation
    val alpha by animateFloatAsState(targetValue = if (enabled) 1f else 0.6f, label = "NextButtonAlpha")
    val gradient = Brush.horizontalGradient(
        listOf(
            skin.glowSecondary,
            skin.glowPrimary
        )
    )
    val interactionSource = remember { MutableInteractionSource() }
    val contentColor = if (enabled) {
        if (skin.glowPrimary.luminance() > 0.5f) Color.Black.copy(alpha = 0.85f) else Color.White
    } else {
        if (skin.isDark) Color.White else Color.Black.copy(alpha = 0.85f)
    }
    
    Surface(
        modifier = modifier
            .alpha(alpha)
            .scaleOnPress(interactionSource)
            .clip(RoundedCornerShape(36.dp))
            .border(
                width = 1.dp,
                color = skin.textPrimary.copy(alpha = 0.24f),
                shape = RoundedCornerShape(36.dp)
            )
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null
            ) { onClick() },
        color = Color.Transparent
    ) {
        Box(
            modifier = Modifier
                .background(if (enabled) gradient else SolidColor(skin.textPrimary.copy(alpha = 0.08f)))
                .padding(horizontal = 24.dp, vertical = 14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = if (isFinalStep) {
                        translation["setup.activity.finish_button"]
                    } else {
                        translation["setup.activity.continue_button"]
                    },
                    color = contentColor,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp
                )
                Icon(
                    imageVector = if (isFinalStep) Icons.Filled.Check else Icons.AutoMirrored.Filled.ArrowForwardIos,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}
