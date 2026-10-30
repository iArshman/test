package me.eternal.purrfect.ui.manager

import android.app.Activity
import android.os.Build
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.BrightnessLow
import androidx.compose.material.icons.filled.BrightnessHigh
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.*
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.horizontalScroll
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.navigation
import me.eternal.purrfect.RemoteSideContext
import me.eternal.purrfect.common.ui.theme.LocalPurrfectSkin
import me.eternal.purrfect.common.ui.theme.PurrfectPalette
import me.eternal.purrfect.common.ui.theme.PurrfectColorSet
import me.eternal.purrfect.ui.manager.theme.aphelion.ThemeRevealState
import me.eternal.purrfect.common.ui.util.G2RoundedRectangle
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.round
import androidx.compose.ui.graphics.SolidColor
import me.eternal.purrfect.ui.manager.theme.aetherGlass
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.interaction.MutableInteractionSource
import me.eternal.purrfect.ui.manager.components.AestheticDialog
import me.eternal.purrfect.ui.util.headerHeightTracker
import me.eternal.purrfect.ui.util.Motion

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalFoundationApi::class,
    ExperimentalLayoutApi::class,
    androidx.compose.animation.ExperimentalAnimationApi::class
)
class Navigation(
    internal val context: RemoteSideContext,
    private val navController: NavHostController,
    val routes: Routes = Routes(context).also { it.navController = navController }
) {
    private val translation by lazy { context.translation.getCategory("manager.navigation") }
    var openBottomBarCustomization by mutableStateOf(false)
    var globalScrollOffset by mutableIntStateOf(0)
    var pendingTransmission by mutableStateOf<String?>(null)
    var isFirstUnlock by mutableStateOf(false)
    var showCinematic by mutableStateOf(false)
    var showGame by mutableStateOf(false)
    val themeRevealState = ThemeRevealState()

    @Composable
    fun TopBar() {
        val navBackStackEntry by navController.currentBackStackEntryAsState()
        val currentRoute = remember(navBackStackEntry) { routes.getCurrentRoute(navBackStackEntry) }
        if (currentRoute?.routeInfo?.hasOwnTopBar == true) return

        val shrinkThreshold = Motion.HEADER_MORPH_THRESHOLD
        val isAphelion = context.config.root.global.uiSettings.managerTheme.get() == "APHELION"
        val skin = if (isAphelion) LocalPurrfectSkin.current else PurrfectPalette
        val focusFactor = if (isAphelion) (globalScrollOffset / shrinkThreshold).coerceIn(0f, 1f) else 0f

        val springFocusFactor by animateFloatAsState(
            targetValue = focusFactor,
            animationSpec = spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessLow),
            label = "aetherSpring"
        )
        val activeFocus = if (skin.id == "AETHER") springFocusFactor else focusFactor
        val headerHeight = lerp(64.dp, 48.dp, activeFocus)

        val canGoBack = remember(navBackStackEntry) {
            currentRoute?.let { !it.routeInfo.primary || it.routeInfo.childIds.contains(routes.currentDestination) } == true
        }
        val haptic = LocalHapticFeedback.current

        val topBarModifier = Modifier
            .height(headerHeight)
            .then(
                if (skin.id == "AETHER" || skin.id == "LUMINA") {
                    Modifier.background(skin.cardOverlayColor, RoundedCornerShape(bottomStart = 22.dp, bottomEnd = 22.dp))
                        .border(2.dp, skin.glassBorder, RoundedCornerShape(bottomStart = 22.dp, bottomEnd = 22.dp))
                } else {
                    Modifier.aetherGlass(skin = skin, bottomStart = 22.dp, bottomEnd = 22.dp, focusFactor = activeFocus)
                }
            )

        TopAppBar(
            modifier = topBarModifier,
            title = {
                currentRoute?.apply {
                    title?.invoke() ?: routeInfo.translatedKey?.value?.let {
                        Text(
                            text = it,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.graphicsLayer {
                                scaleX = 1f - (activeFocus * 0.05f)
                                scaleY = 1f - (activeFocus * 0.05f)
                            }
                        )
                    }
                }
            },
            navigationIcon = {
                val backButtonAnimation by animateFloatAsState(if (canGoBack) 1f else 0f, label = "backButton")
                Box(
                    modifier = Modifier
                        .graphicsLayer {
                            alpha = backButtonAnimation
                            scaleX = 1f - (activeFocus * 0.1f)
                            scaleY = 1f - (activeFocus * 0.1f)
                        }
                        .width(lerp(0.dp, 48.dp, backButtonAnimation))
                        .height(48.dp)
                ) {
                    IconButton(onClick = {
                        if (canGoBack) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            navController.popBackStack()
                        }
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color.Transparent,
                scrolledContainerColor = Color.Transparent,
                navigationIconContentColor = skin.textPrimary,
                titleContentColor = skin.textPrimary,
                actionIconContentColor = skin.textPrimary
            ),
            actions = {
                currentRoute?.topBarActions?.invoke(this)
                if (currentRoute?.routeInfo?.id == routes.settings.routeInfo.id) {
                    IconButton(onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        openBottomBarCustomization = true
                    }) {
                        Icon(Icons.Filled.Tune, contentDescription = null)
                    }
                }
            }
        )
    }

    @Composable
    fun FloatingBottomBar() {
        val haptic = LocalHapticFeedback.current
        val navBackStackEntry by navController.currentBackStackEntryAsState()
        val currentRoute = remember(navBackStackEntry) { routes.getCurrentRoute(navBackStackEntry) }
        val isLimitedTargetMode = context.isLimitedTargetMode
        val availableRoutes = remember(isLimitedTargetMode) {
            if (isLimitedTargetMode) {
                listOf(routes.home, routes.features)
            } else {
                listOf(routes.tasks, routes.features, routes.home, routes.social, routes.scripting, routes.friendTracker)
            }
        }
        val availableRouteMap = remember(availableRoutes) { availableRoutes.associateBy { it.routeInfo.id } }

        val shrinkThreshold = Motion.HEADER_MORPH_THRESHOLD
        val isAphelion = context.config.root.global.uiSettings.managerTheme.get() == "APHELION"
        val skin = if (isAphelion) LocalPurrfectSkin.current else PurrfectPalette
        val focusFactor = if (isAphelion) (globalScrollOffset / shrinkThreshold).coerceIn(0f, 1f) else 0f
        val barHeight = lerp(82.dp, 64.dp, focusFactor)
        val labelAlpha = (1f - (focusFactor * 2.5f)).coerceIn(0f, 1f)
        val iconTranslationY = (10 * focusFactor).dp

        val isMechanicalMode = skin.id == "AETHER" || skin.id == "LUMINA"

        val prefs = remember { context.sharedPreferences }
        val defaultOrder = remember(isLimitedTargetMode) {
            if (isLimitedTargetMode) listOf("home", "features") else listOf("tasks", "features", "home", "social", "scripts")
        }
        fun loadSelected(): List<String> {
            if (isLimitedTargetMode) return defaultOrder
            val raw = prefs.getString("manager_nav_tabs", null)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
            val cleaned = raw.filter { availableRouteMap.containsKey(it) }
            val list = (if (cleaned.isNotEmpty()) cleaned else defaultOrder).distinct()
            return list.take(5)
        }
        var defaultTabId by remember(isLimitedTargetMode) { mutableStateOf(if (isLimitedTargetMode) "home" else prefs.getString("manager_default_tab", "home") ?: "home") }
        fun saveDefault(id: String) {
            if (isLimitedTargetMode) return
            defaultTabId = id
            prefs.edit().putString("manager_default_tab", id).apply()
        }
        fun saveSelected(ids: List<String>) {
            if (isLimitedTargetMode) return
            if (defaultTabId !in ids) {
                val candidate = when {
                    "home" in ids -> "home"
                    ids.isNotEmpty() -> ids.first()
                    else -> defaultTabId
                }
                saveDefault(candidate)
            }
            prefs.edit().putString("manager_nav_tabs", ids.joinToString(",")).apply()
        }
        var selectedTabIds by remember(isLimitedTargetMode) { mutableStateOf(loadSelected()) }
        val selectedRoutes = remember(selectedTabIds) { selectedTabIds.mapNotNull { availableRouteMap[it] } }

        // GATED GEOMETRY: Restore 28.dp for LUX/NOX, bold 32.dp for Performance
        val barShape = remember(isMechanicalMode, isAphelion) {
            if (isMechanicalMode) G2RoundedRectangle(32.dp)
            else if (isAphelion) RoundedCornerShape(28.dp)
            else RoundedCornerShape(32.dp)
        }

        val barBorder = remember(skin) {
            Brush.linearGradient(
                listOf(
                    skin.glowPrimary.copy(alpha = 0.9f),
                    skin.glowSecondary.copy(alpha = 0.85f)
                )
            )
        }

        // SLAB GRADIENT: High-opacity vertical gradient for premium feel
        val slabGradient = remember(skin) {
            Brush.verticalGradient(
                listOf(
                    skin.cardOverlayColor.copy(alpha = 0.98f),
                    skin.cardOverlayColor.copy(alpha = 0.95f)
                )
            )
        }

        val barSheen = remember {
            Brush.verticalGradient(
                colors = listOf(
                    skin.textPrimary.copy(alpha = 0.14f),
                    Color.Transparent
                )
            )
        }

        Box(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
                .navigationBarsPadding(),
            contentAlignment = Alignment.BottomCenter
        ) {
            val baseItemWidth = 92.dp
            val containerPadding = 24.dp
            val targetBarWidth = if (selectedRoutes.size < 5) baseItemWidth * selectedRoutes.size.toFloat() + containerPadding else null
            val animatedBarWidth by animateDpAsState(targetValue = targetBarWidth ?: 0.dp, label = "barWidth")

            Surface(
                shape = barShape,
                color = Color.Transparent,
                contentColor = skin.textPrimary,
                border = BorderStroke(
                    if (skin.id == "AETHER") 2.dp else 1.dp,
                    if (skin.id == "AETHER") SolidColor(skin.glowPrimary) else barBorder
                ),
                modifier = Modifier
                    .then(if (targetBarWidth != null) Modifier.width(animatedBarWidth) else Modifier.fillMaxWidth())
                    .shadow(
                        elevation = if (skin.id == "AETHER") 0.dp else 28.dp,
                        shape = barShape,
                        spotColor = skin.glowPrimary.copy(alpha = 0.35f),
                        ambientColor = skin.glowSecondary.copy(alpha = 0.26f)
                    )
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(barHeight)
                        .clip(barShape)
                        .background(if (skin.id == "AETHER") skin.cardOverlayColor else Color.Transparent)
                ) {
                    // LAYER 1: Core Material Slab (All skins except Aether)
                    if (skin.id != "AETHER") {
                        Box(Modifier.matchParentSize().background(slabGradient))
                    }

                    // LAYER 2: Physical Rim Sheen (All skins except Aether)
                    if (skin.id != "AETHER") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(18.dp)
                                .align(Alignment.TopCenter)
                                .background(barSheen)
                                .graphicsLayer { alpha = 0.6f }
                        )
                    }

                    // LAYER 3: Refractive Mesh Glow (All skins except Aether)
                    if (skin.id != "AETHER") {
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .graphicsLayer { alpha = 0.25f }
                                .drawBehind {
                                    val radius = size.width * 0.42f
                                    drawCircle(
                                        brush = Brush.radialGradient(
                                            colors = listOf(
                                                skin.glowSecondary.copy(alpha = 0.2f),
                                                Color.Transparent
                                            ),
                                            center = center,
                                            radius = radius
                                        ),
                                        radius = radius,
                                        center = center
                                    )
                                }
                        )
                    }

                    Box(Modifier.fillMaxWidth().height(barHeight)) {
                        var barWidthPx by remember { mutableStateOf(0f) }
                        val itemCount = selectedRoutes.size.coerceAtLeast(1)
                        val density = androidx.compose.ui.platform.LocalDensity.current
                        val selectedIndex = remember(currentRoute, selectedRoutes) {
                            val index = selectedRoutes.indexOf(currentRoute)
                            if (index >= 0) index else null
                        }

                        selectedIndex?.let { 
                            val itemWidthPx =
                                remember(barWidthPx, itemCount) { if (itemCount > 0) barWidthPx / itemCount else 0f }
                            val offsetAnim = remember { Animatable(0f) }
                            var lastSelectedIndex by remember { mutableStateOf(selectedIndex) }
                            LaunchedEffect(itemWidthPx) {
                                if (itemWidthPx > 0f) {
                                    offsetAnim.snapTo(selectedIndex * itemWidthPx)
                                }
                            }
                            LaunchedEffect(selectedIndex, itemWidthPx) {
                                if (itemWidthPx <= 0f) return@LaunchedEffect
                                val dist = kotlin.math.abs(selectedIndex - lastSelectedIndex).coerceAtLeast(1)
                                val damping = when {
                                    dist >= 3 -> 0.65f
                                    dist == 2 -> 0.75f
                                    else -> 0.90f
                                }
                                val stiffness = if (isMechanicalMode) 450f else Spring.StiffnessMediumLow
                                offsetAnim.animateTo(
                                    targetValue = selectedIndex * itemWidthPx,
                                    animationSpec = spring(dampingRatio = damping, stiffness = stiffness)
                                )
                                lastSelectedIndex = selectedIndex
                            }
                            val horizontalInset = 2.dp
                            val indicatorWidth = (with(density) { itemWidthPx.toDp() } - horizontalInset * 2)
                                .coerceAtLeast(0.dp)
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .onGloballyPositioned { barWidthPx = it.size.width.toFloat() }
                            ) {
                                val motionProgress = remember { Animatable(1f) }
                                LaunchedEffect(selectedIndex) {
                                    motionProgress.snapTo(0f)
                                    val dist = kotlin.math.abs(selectedIndex - lastSelectedIndex).coerceAtLeast(1)
                                    val dur = when {
                                        dist >= 3 -> 440
                                        dist == 2 -> 380
                                        else -> 320
                                    }
                                    motionProgress.animateTo(
                                        1f,
                                        animationSpec = tween(durationMillis = dur, easing = FastOutSlowInEasing)
                                    )
                                }
                                val pulse = sin(PI * motionProgress.value).toFloat()
                                val distForScale = kotlin.math.abs(selectedIndex - lastSelectedIndex).coerceAtLeast(1)
                                val scaleXBase = 0.18f
                                val scaleXExtra = 0.06f
                                val scaleYBase = 0.06f
                                val scaleYExtra = 0.02f
                                val mult = (distForScale - 1).coerceAtLeast(0)
                                val scaleXAnim = 1f + (scaleXBase + scaleXExtra * mult) * pulse
                                val scaleYAnim = 1f - (scaleYBase + scaleYExtra * mult) * pulse
                                // Page Indicator (Always visible)
                                val offsetX = if (barWidthPx > 0f) with(density) { offsetAnim.value.toDp() } + horizontalInset else 0.dp

                                // DYNAMIC GEOMETRY: 18.dp Squircle (Expanded) -> 24.dp Pill (Shrunk)
                                val indicatorShape = if (isMechanicalMode) {
                                    G2RoundedRectangle(lerp(18.dp, 24.dp, focusFactor))
                                } else {
                                    RoundedCornerShape(18.dp)
                                }

                                Box(
                                    modifier = Modifier
                                        .fillMaxHeight()
                                        .width(indicatorWidth.coerceAtLeast(0.dp))
                                        .offset(x = offsetX)
                                        .padding(vertical = if (isMechanicalMode) 12.dp else lerp(10.dp, 8.dp, focusFactor), horizontal = 2.dp)
                                        .graphicsLayer { scaleX = scaleXAnim; scaleY = scaleYAnim }
                                        .clip(indicatorShape)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .matchParentSize()
                                            .background(
                                                if (isMechanicalMode) SolidColor(skin.glowPrimary)
                                                else Brush.linearGradient(
                                                    listOf(
                                                        skin.glowPrimary.copy(alpha = 0.85f),
                                                        skin.glowSecondary.copy(alpha = 0.75f)
                                                    )
                                                )
                                            )
                                    )
                                }                            }
                        }
                        NavigationBar(
                            containerColor = Color.Transparent,
                            tonalElevation = 0.dp,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 6.dp)
                        ) {
                            selectedRoutes.forEach { route ->
                                val isSelected = currentRoute == route
                                val selectionProgress by animateFloatAsState(if (isSelected) 1f else 0f, label = "${route.routeInfo.id}-selection")
                                
                                // SCATTERED GRADIENT: Illuminated text/icon visuals for Aphelion
                                val itemTint = if (isSelected) {
                                    skin.primaryButtonText
                                } else {
                                    skin.textPrimary
                                }

                                NavigationBarItem(
                                    alwaysShowLabel = true,
                                    icon = {
                                        Icon(
                                            imageVector = route.routeInfo.icon,
                                            contentDescription = null,
                                            modifier = Modifier
                                                .size(22.dp + 2.dp * selectionProgress)
                                                .graphicsLayer { 
                                                    alpha = if (isSelected) 1f else (0.65f + 0.35f * selectionProgress)
                                                    translationY = iconTranslationY.toPx()
                                                },
                                            tint = itemTint
                                        )
                                    },
                                    label = {
                                        val label = context.translation["manager.routes.${route.routeInfo.key.substringBefore("/")}"] ?: ""
                                        val isLong = label.length > 11
                                        Text(
                                            text = label,
                                            textAlign = TextAlign.Center,
                                            fontSize = 12.sp,
                                            fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.SemiBold,
                                            color = itemTint.copy(alpha = (0.6f + 0.4f * selectionProgress) * labelAlpha),
                                            maxLines = if (isLong) 2 else 1,
                                            overflow = if (isLong) TextOverflow.Ellipsis else TextOverflow.Clip,
                                            softWrap = isLong,
                                            modifier = (if (isLong) Modifier.widthIn(max = 90.dp).wrapContentWidth(Alignment.CenterHorizontally) else Modifier.wrapContentWidth(Alignment.CenterHorizontally))
                                                .graphicsLayer {
                                                    alpha = labelAlpha
                                                    translationY = (-10 * focusFactor).dp.toPx()
                                                }
                                        )
                                    },
                                    selected = isSelected,
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = Color.Transparent, // Managed by manual tint
                                        unselectedIconColor = skin.textPrimary.copy(alpha = 0.72f),
                                        selectedTextColor = Color.Transparent, // Managed by manual color
                                        unselectedTextColor = skin.textPrimary.copy(alpha = 0.72f),
                                        indicatorColor = Color.Transparent
                                    ),
                                    onClick = { 
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        route.navigateReset() 
                                    }
                                )
                            }
                        }
                    }
                }
            }

            if (openBottomBarCustomization) {
                val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                ModalBottomSheet(
                    onDismissRequest = { openBottomBarCustomization = false },
                    sheetState = sheetState,
                    containerColor = Color.Transparent,
                    dragHandle = {}
                ) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
                        color = Color.Transparent,
                        tonalElevation = 0.dp,
                        shadowElevation = 12.dp,
                        border = BorderStroke(1.dp, skin.textPrimary.copy(alpha = 0.1f))
                    ) {
                        Column(
                            modifier = Modifier
                                .background(skin.backgroundGradient)
                                .padding(vertical = 12.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 10.dp)
                                    .clip(RoundedCornerShape(22.dp))
                                    .background(
                                        brush = Brush.linearGradient(
                                            colors = listOf(
                                                skin.glowPrimary.copy(alpha = 0.28f),
                                                skin.glowSecondary.copy(alpha = 0.26f)
                                            )
                                        )
                                    )
                                    .padding(horizontal = 18.dp, vertical = 16.dp)
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        text = translation["customize_bottom_bar_title"] ?: "Customize Bottom Bar",
                                        style = MaterialTheme.typography.titleLarge,
                                        color = skin.textPrimary,
                                        textAlign = TextAlign.Center
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        text = translation["customize_bottom_bar_subtitle"] ?: "Select up to 5 tabs",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = skin.textSecondary,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                            Box(
                                modifier = Modifier
                                    .align(Alignment.CenterHorizontally)
                                    .padding(top = 6.dp, bottom = 2.dp)
                                    .width(40.dp)
                                    .height(5.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(skin.textPrimary.copy(alpha = 0.35f))
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = translation["shown_tabs_title"] ?: "Shown Tabs",
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.padding(horizontal = 16.dp),
                                color = skin.textPrimary
                            )
                            Spacer(Modifier.height(6.dp))
                            if (selectedTabIds.isEmpty()) {
                                Text(
                                    text = translation["no_tabs_selected_text"] ?: "No tabs selected",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = skin.textSecondary,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                                )
                            } else {
                                val hapticCustom = LocalHapticFeedback.current
                                var draggingId by remember { mutableStateOf<String?>(null) }
                                var dragDelta by remember { mutableStateOf(0f) }
                                var dragStartIndex by remember { mutableStateOf(-1) }
                                var rowHeight by remember { mutableStateOf(0) }
                                val listState = rememberLazyListState()
                                LazyColumn(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp),
                                    contentPadding = PaddingValues(bottom = 8.dp),
                                    state = listState
                                ) {
                                    itemsIndexed(selectedTabIds, key = { _, id -> id }) { index, id ->
                                        val route = availableRouteMap[id] ?: return@itemsIndexed
                                        val label = context.translation["manager.routes.${route.routeInfo.key.substringBefore("/")}"] ?: ""
                                        val isDragging = draggingId == id
                                        val rowShape = RoundedCornerShape(18.dp)
                                        Surface(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(bottom = 8.dp)
                                                .animateItem()
                                                .zIndex(if (isDragging) 1f else 0f)
                                                .graphicsLayer { if (isDragging) { scaleX = 1.02f; scaleY = 1.02f } }
                                                .onGloballyPositioned { if (rowHeight == 0) rowHeight = it.size.height }
                                                .pointerInput(id) {
                                                    detectDragGestures(
                                                        onDragStart = {
                                                            draggingId = id
                                                            dragStartIndex = selectedTabIds.indexOf(id)
                                                            dragDelta = 0f
                                                            hapticCustom.performHapticFeedback(HapticFeedbackType.LongPress)
                                                        },
                                                        onDrag = { _: PointerInputChange, dragAmount ->
                                                            dragDelta += dragAmount.y
                                                            if (rowHeight > 0 && dragStartIndex >= 0) {
                                                                val currentIndex = selectedTabIds.indexOf(id)
                                                                val deltaRows = round(dragDelta / rowHeight.toFloat()).toInt()
                                                                val targetIndex = (dragStartIndex + deltaRows).coerceIn(0, selectedTabIds.lastIndex)
                                                                if (targetIndex != currentIndex) {
                                                                    val list = selectedTabIds.toMutableList()
                                                                    list.removeAt(currentIndex)
                                                                    list.add(targetIndex, id)
                                                                    selectedTabIds = list
                                                                    saveSelected(selectedTabIds)
                                                                    hapticCustom.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                                }
                                                            }
                                                        },
                                                        onDragEnd = { draggingId = null; dragDelta = 0f; dragStartIndex = -1 },
                                                        onDragCancel = { draggingId = null; dragDelta = 0f; dragStartIndex = -1 }
                                                    )
                                                },
                                            shape = rowShape,
                                            color = Color.Transparent,
                                            border = BorderStroke(
                                                1.dp,
                                                if (isDragging) Brush.linearGradient(listOf(skin.glowPrimary, skin.glowSecondary))
                                                else SolidColor(skin.textPrimary.copy(alpha = 0.1f))
                                            ),
                                            tonalElevation = 0.dp,
                                            shadowElevation = 0.dp
                                        ) {
                                            Row(
                                                modifier = Modifier
                                                    .padding(12.dp)
                                                    .fillMaxWidth()
                                                    .clip(rowShape)
                                                    .background(
                                                        if (isDragging) Brush.linearGradient(
                                                            colors = listOf(
                                                                skin.glowPrimary.copy(alpha = 0.18f),
                                                                skin.glowSecondary.copy(alpha = 0.16f)
                                                            )
                                                        ) else SolidColor(skin.textPrimary.copy(alpha = 0.08f))
                                                    ),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(Icons.Filled.DragHandle, contentDescription = null, tint = skin.textPrimary.copy(alpha = 0.8f))
                                                Spacer(Modifier.width(8.dp))
                                                Icon(route.routeInfo.icon, contentDescription = null, tint = skin.textPrimary)
                                                Spacer(Modifier.width(12.dp))
                                                Text(text = label, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, color = skin.textPrimary)
                                                val defaultEligible = remember { setOf("tasks","features","home","social","scripts") }
                                                RadioButton(
                                                    selected = defaultTabId == id,
                                                    onClick = { if (id in defaultEligible) saveDefault(id) },
                                                    enabled = id in defaultEligible,
                                                    colors = RadioButtonDefaults.colors(
                                                        selectedColor = skin.glowPrimary,
                                                        unselectedColor = skin.textPrimary.copy(alpha = 0.7f),
                                                        disabledSelectedColor = skin.glowPrimary.copy(alpha = 0.4f),
                                                        disabledUnselectedColor = skin.textPrimary.copy(alpha = 0.35f)
                                                    )
                                                )
                                                IconButton(
                                                    onClick = {
                                                        if (selectedTabIds.size > 1 && id != defaultTabId && id != "home") {
                                                            selectedTabIds = selectedTabIds.toMutableList().also { it.removeAt(index) }
                                                            saveSelected(selectedTabIds)
                                                        }
                                                    },
                                                    enabled = id != defaultTabId && id != "home"
                                                ) { Icon(Icons.Filled.Close, contentDescription = null, tint = skin.textPrimary) }
                                            }
                                        }
                                    }
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            Text(text = translation["available_tabs_title"] ?: "Available Tabs", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 16.dp), color = skin.textPrimary)
                            Spacer(Modifier.height(6.dp))
                            FlowRow(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                              ) {
                                availableRoutes.forEach { route ->
                                    val id = route.routeInfo.id
                                    val already = selectedTabIds.contains(id)
                                    val label = context.translation["manager.routes.${route.routeInfo.key.substringBefore("/")}"] ?: ""
                                    AnimatedVisibility(
                                        visible = !already && selectedTabIds.size < 5,
                                        enter = scaleIn(tween(160), initialScale = 0.95f) + fadeIn(tween(180)) + slideInVertically(tween(180), initialOffsetY = { it / 3 }),
                                        exit = scaleOut(tween(120)) + fadeOut(tween(120)) + slideOutVertically(tween(120))
                                    ) {
                                        AssistChip(
                                            onClick = {
                                                if (!already && selectedTabIds.size < 5) {
                                                    selectedTabIds = selectedTabIds + id
                                                    saveSelected(selectedTabIds)
                                                }
                                            },
                                            label = { Text(text = label) },
                                            leadingIcon = { Icon(route.routeInfo.icon, contentDescription = null) },
                                            enabled = true,
                                            colors = AssistChipDefaults.assistChipColors(
                                                containerColor = skin.textPrimary.copy(alpha = 0.08f),
                                                labelColor = skin.textPrimary,
                                                leadingIconContentColor = skin.glowSecondary
                                            )
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.height(16.dp))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        selectedTabIds = defaultOrder
                                        saveSelected(selectedTabIds)
                                    },
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = skin.textPrimary),
                                    border = BorderStroke(1.dp, Brush.linearGradient(listOf(skin.glowPrimary, skin.glowSecondary)))
                                ) {
                                    Text(text = translation["reset_button"] ?: "Reset", style = MaterialTheme.typography.labelLarge)
                                }
                                Button(
                                    onClick = { openBottomBarCustomization = false },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = skin.textPrimary.copy(alpha = 0.08f),
                                        contentColor = skin.textPrimary
                                    )
                                ) {
                                    Text(text = translation["done_button"] ?: "Done", style = MaterialTheme.typography.labelLarge)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun Fab() {
        val navBackStackEntry by navController.currentBackStackEntryAsState()
        remember(navBackStackEntry) { routes.getCurrentRoute(navBackStackEntry) }?.floatingActionButton?.invoke()
    }
    @Composable fun NavContent(paddingValues: PaddingValues, startDestination: String) {
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier.padding(paddingValues)
        ) {
            routes.getRoutes().filter { it.parentRoute == null }.forEach { route ->
                val children = routes.getRoutes().filter { it.parentRoute == route }
                if (children.isEmpty()) {
                    val isSummaryScreen = route.routeInfo.id == Routes.CONFIG_IMPORT_CONFIRMATION_ROUTE ||
                            route.routeInfo.id == Routes.CONFIG_EXPORT_SUMMARY_ROUTE ||
                            route.routeInfo.id == Routes.FRIEND_TRACKER_CONFIG_EXPORT_ROUTE ||
                            route.routeInfo.id == Routes.FRIEND_TRACKER_CONFIG_IMPORT_ROUTE
                    val animatedRoutes = setOf("friend_tracker_catalog", "manage_friend_tracker_repos", "manage_script_repos", "manage_repos")
                    val isAnimatedRoute = animatedRoutes.contains(route.routeInfo.id)
                    composable(
                        route.routeInfo.id,
                        enterTransition = { if (isSummaryScreen) slideInHorizontally { it } else if (isAnimatedRoute) slideInHorizontally { it } else fadeIn(tween(100)) },   
                        exitTransition = { if (isSummaryScreen) slideOutHorizontally { -it } else if (isAnimatedRoute) slideOutHorizontally { -it } else fadeOut(tween(100)) },
                        popEnterTransition = { if (isSummaryScreen) slideInHorizontally { -it } else if (isAnimatedRoute) slideInHorizontally { -it } else fadeIn(tween(100)) },
                        popExitTransition = { if (isSummaryScreen) slideOutHorizontally { it } else if (isAnimatedRoute) slideOutHorizontally { it } else fadeOut(tween(100)) }
                    ) { route.content.invoke(it) }
                    route.customComposables.invoke(this)
                } else {
                    navigation("main_" + route.routeInfo.id, route.routeInfo.id) {
                        composable("main_" + route.routeInfo.id) { route.content.invoke(it) }
                        children.forEach { child ->
                            composable(child.routeInfo.id) { child.content.invoke(it) }
                        }
                        route.customComposables.invoke(this)
                    }
                }
            }
        }
    }

    @Composable fun FloatingActionButton() = Fab()
    @Composable fun Content(paddingValues: PaddingValues, startDestination: String) = NavContent(paddingValues, startDestination)
}
