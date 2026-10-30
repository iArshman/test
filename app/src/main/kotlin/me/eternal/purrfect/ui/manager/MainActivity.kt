@file:OptIn(androidx.compose.animation.ExperimentalAnimationApi::class)
package me.eternal.purrfect.ui.manager

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.foundation.background
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import me.eternal.purrfect.RemoteSideContext
import me.eternal.purrfect.SharedContextHolder
import me.eternal.purrfect.common.ui.AppMaterialTheme
import me.eternal.purrfect.common.ui.ThemeMode
import me.eternal.purrfect.common.ui.ThemePreferences
import me.eternal.purrfect.ui.manager.components.AestheticDialog
import me.eternal.purrfect.ui.manager.theme.PurrfectPalette
import me.eternal.purrfect.ui.manager.chimaera.ChimaeraDiscovery
import me.eternal.purrfect.ui.manager.chimaera.ChimaeraTransmissionToast
import me.eternal.purrfect.ui.manager.chimaera.ChimaeraCinematic
import me.eternal.purrfect.ui.manager.chimaera.ChimaeraGame
import me.eternal.purrfect.common.ui.theme.LocalPurrfectSkin
import me.eternal.purrfect.common.ui.theme.AphelionSkinProvider
import me.eternal.purrfect.ui.util.ActivityLauncherHelper
import me.eternal.purrfect.ui.manager.theme.aphelion.CircularRevealOverlay
import me.eternal.purrfect.ui.util.ThankYouDialog
import android.content.IntentFilter

class MainActivity : ComponentActivity() {
    private lateinit var navController: NavHostController
    private lateinit var managerContext: RemoteSideContext

    companion object {
        const val RESTART_ACTION = "me.eternal.purrfect.RESTART"
    }

    private val restartReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: Intent) {
            if (intent.action == RESTART_ACTION) {
                recreate()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (::navController.isInitialized.not()) return
        handleAnnouncementIntent(intent)
        handleUpdateIntent(intent)

        intent.getStringExtra("route")?.let { route ->
            navController.popBackStack()
            navController.navigate(route) {
                popUpTo(navController.graph.findStartDestination().id) {
                    inclusive = true
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (::managerContext.isInitialized) {
            managerContext.checkForRequirements()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        super.onCreate(savedInstanceState)

        registerReceiver(restartReceiver, IntentFilter(RESTART_ACTION), RECEIVER_EXPORTED)
        managerContext = SharedContextHolder.remote(this).apply {
            activity = this@MainActivity
            syncSkinSettings()
            checkForRequirements()
        }
        handleAnnouncementIntent(intent)
        handleUpdateIntent(intent)
        val routes = Routes(managerContext)
        routes.activityLauncher = ActivityLauncherHelper(this)
        routes.getRoutes().forEach { it.init() }

        setContent {
            val context = LocalContext.current
            val themeMode by ThemePreferences.getThemeModeFlow(context).collectAsState(initial = ThemeMode.SYSTEM)
            navController = rememberNavController()
            val navigation = remember {
                Navigation(managerContext, navController, routes.also {
                    it.navController = navController
                })
            }
            routes.navigation = navigation
            val startDestination = remember {
                intent.getStringExtra("route") ?: run {
                    val def = managerContext.sharedPreferences.getString("manager_default_tab", "home") ?: "home"
                    val allowed = setOf("tasks", "features", "home", "social", "scripts")
                    if (def in allowed) def else "home"
                }
            }

            AppMaterialTheme(themeMode = themeMode) {
                Box(Modifier.fillMaxSize()) {
                    AphelionSkinProvider(managerContext.androidContext) {
                        val managerTheme = managerContext.config.root.global.uiSettings.managerTheme.get()
                        val activeSkin = LocalPurrfectSkin.current
                        val skin = remember(managerTheme, activeSkin) { 
                            if (managerTheme == "APHELION") activeSkin else me.eternal.purrfect.common.ui.theme.PurrfectPalette 
                        }
                        val isAphelion = managerTheme == "APHELION"

                        CompositionLocalProvider(
                            LocalPurrfectSkin provides skin,
                            LocalContentColor provides skin.textPrimary
                        ) {
                            val shouldShowAbiWarning = remember {
                                val deviceIsArm64 = Build.SUPPORTED_ABIS.any { it == "arm64-v8a" || it.startsWith("arm64") }
                                val libDir = context.applicationInfo.nativeLibraryDir.orEmpty()
                                val appIsArm64 = libDir.contains("arm64")
                                deviceIsArm64 && !appIsArm64
                            }
                            if (shouldShowAbiWarning) {
                                AestheticDialog(
                                    onDismissRequest = {},
                                    title = managerContext.translation["setup.activity.wrong_apk_title"],
                                    text = "",
                                    icon = Icons.Filled.Warning,
                                    confirmButtonText = managerContext.translation["setup.activity.close_button"],
                                    onConfirm = { (context as? Activity)?.finishAffinity() },
                                    showCloseButton = false,
                                    opaque = true,
                                    customContent = {
                                        Text(
                                            text = managerContext.translation["setup.activity.wrong_apk_message"],
                                            color = skin.textPrimary.copy(alpha = 0.7f),
                                            lineHeight = 18.sp
                                        )
                                    }
                                )
                            }
                            val background = MaterialTheme.colorScheme.background
                            val isLight = background.luminance() > 0.5f
                            val view = LocalView.current
                            @Suppress("DEPRECATION")
                            SideEffect {
                                val window = (view.context as Activity).window
                                WindowCompat.setDecorFitsSystemWindows(window, false)
                                window.statusBarColor = Color.Transparent.toArgb()
                                window.navigationBarColor = Color.Transparent.toArgb()
                                val insetsController = WindowInsetsControllerCompat(window, window.decorView)
                                insetsController.isAppearanceLightStatusBars = isLight
                                insetsController.isAppearanceLightNavigationBars = isLight
                            }
                            val bottomPadding = 80.dp + 16.dp +
                                WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                            routes.bottomPadding = bottomPadding
                            val navBackStackEntry by navController.currentBackStackEntryAsState()
                            val currentRoute = navBackStackEntry?.destination?.route
                            val fullscreenRoutes = remember {
                                listOf(
                                    Routes.CONFIG_IMPORT_CONFIRMATION_ROUTE,
                                    Routes.CONFIG_EXPORT_SUMMARY_ROUTE,
                                    Routes.FRIEND_TRACKER_CONFIG_EXPORT_ROUTE,
                                    Routes.FRIEND_TRACKER_CONFIG_IMPORT_ROUTE
                                )
                            }
                            val isFullscreen = currentRoute in fullscreenRoutes || navigation.showGame || navigation.showCinematic
                            ThankYouDialog()

                            LaunchedEffect(Unit) {
                                ChimaeraDiscovery.load(managerContext.sharedPreferences)
                            }

                            Scaffold(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(if (isAphelion) skin.backgroundGradient else me.eternal.purrfect.common.ui.theme.PurrfectPalette.backgroundGradient),
                                containerColor = Color.Transparent,
                                topBar = {
                                    if (!isFullscreen) {
                                        navigation.TopBar()
                                    }
                                },
                                floatingActionButton = {
                                    if (!isFullscreen) {
                                        Box(Modifier.padding(bottom = bottomPadding)) {
                                            navigation.Fab()
                                        }
                                    }
                                },
                                // Disable automatic padding so content can draw behind the bottom bar
                                contentWindowInsets = WindowInsets(0, 0, 0, 0)
                            ) { innerPadding ->
                                Box(Modifier.fillMaxSize()) {
                                    val contentPadding = if (!isFullscreen) {
                                        PaddingValues(
                                            top = innerPadding.calculateTopPadding(),
                                            start = innerPadding.calculateStartPadding(LayoutDirection.Ltr),
                                            end = innerPadding.calculateEndPadding(LayoutDirection.Ltr),
                                            bottom = innerPadding.calculateBottomPadding()
                                        )
                                    } else {
                                        PaddingValues(0.dp)
                                    }
                                    val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
                                    navigation.NavContent(contentPadding, startDestination)

                                    // Transmission Overlay
                                    navigation.pendingTransmission?.let { message ->
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .padding(top = statusBarHeight + 12.dp),
                                            contentAlignment = Alignment.TopCenter
                                        ) {
                                            ChimaeraTransmissionToast(
                                                message = message,
                                                onDismiss = {
                                                    navigation.pendingTransmission = null
                                                    if (ChimaeraDiscovery.unlocked) {
                                                        navigation.showCinematic = true
                                                    }
                                                }
                                            )
                                        }
                                    }

                                    // Cinematic Overlay
                                    if (navigation.showCinematic) {
                                        ChimaeraCinematic(
                                            isFirstUnlock = navigation.isFirstUnlock,
                                            onComplete = {
                                                navigation.showCinematic = false
                                                navigation.showGame = true
                                            }
                                        )
                                    }

                                    // Game Overlay
                                    if (navigation.showGame) {
                                        ChimaeraGame(
                                            prefs = managerContext.sharedPreferences,
                                            onExit = { navigation.showGame = false }
                                        )
                                    }

                                    // Top Fade
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.TopCenter)
                                            .fillMaxWidth()
                                            .height(statusBarHeight + 24.dp)
                                            .background(
                                                Brush.verticalGradient(
                                                    listOf(
                                                        if (isAphelion && skin.id != "LEGACY") skin.textPrimary.copy(alpha = 0.05f) else Color(0xFF261F58),
                                                        Color.Transparent
                                                    )
                                                )
                                            )
                                    )

                                    if (!isFullscreen) {
                                        // Bottom Fade
                                        Box(
                                            modifier = Modifier
                                                .align(Alignment.BottomCenter)
                                                .fillMaxWidth()
                                                .height(routes.bottomPadding)
                                                .background(
                                                    Brush.verticalGradient(
                                                        listOf(
                                                            Color.Transparent,
                                                            if (isAphelion && skin.id != "LEGACY") skin.textPrimary.copy(alpha = 0.08f) else Color(0xFF241F52)
                                                        )
                                                    )
                                                )
                                        )
                                        Box(modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter)) {
                                            navigation.FloatingBottomBar()
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Theme Reveal Overlay (Android 13+ only for stability)
                    // High Z-Order: Placed at the bottom of the root Box to cover everything
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        navigation.themeRevealState.pendingReveal?.let { revealRequest ->
                            CircularRevealOverlay(
                                context = managerContext,
                                request = revealRequest,
                                onComplete = { navigation.themeRevealState.clearReveal() }
                            )
                        }
                    } else {
                        // Instantly clear reveal state on older versions
                        navigation.themeRevealState.pendingReveal?.let {
                            navigation.themeRevealState.clearReveal()
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(restartReceiver)
    }

    private fun handleAnnouncementIntent(intent: Intent) {
        if (intent.getBooleanExtra("show_announcements", false)) {
            applicationContext.getSharedPreferences("prefs", 0).edit()
                .putBoolean("show_announcements_on_launch", true)
                .apply()
        }
    }

    private fun handleUpdateIntent(intent: Intent) {
        if (intent.getBooleanExtra("show_changelog", false)) {
            val version = intent.getStringExtra("changelog_version")
            applicationContext.getSharedPreferences("prefs", 0).edit()
                .putBoolean("show_changelog_on_launch", true)
                .putString("changelog_version_on_launch", version)
                .apply()
        }
    }
}
