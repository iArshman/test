package me.eternal.purrfect.ui.manager.pages.themes.aphelion

import android.content.SharedPreferences
import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.navigation.NavBackStackEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.eternal.purrfect.R
import me.eternal.purrfect.common.BuildConfig
import me.eternal.purrfect.common.TargetApp
import me.eternal.purrfect.common.ui.rememberAsyncMutableState
import me.eternal.purrfect.common.ui.rememberAsyncMutableStateList
import me.eternal.purrfect.common.util.ktx.openLink
import me.eternal.purrfect.storage.getQuickTiles
import me.eternal.purrfect.storage.setQuickTiles
import me.eternal.purrfect.ui.manager.components.AestheticDialog
import me.eternal.purrfect.ui.manager.data.UpdateDownloader
import me.eternal.purrfect.ui.manager.data.Updater
import me.eternal.purrfect.ui.manager.data.Updater.Channel
import me.eternal.purrfect.ui.manager.pages.home.HomeRootSection
import me.eternal.purrfect.ui.manager.pages.home.QuickActionsDialog
import me.eternal.purrfect.common.ui.theme.LocalPurrfectSkin
import me.eternal.purrfect.ui.util.Motion
import me.eternal.purrfect.ui.util.PurrfectMarqueeText
import me.eternal.purrfect.ui.util.headerHeightTracker
import me.eternal.purrfect.ui.util.scaleOnPress
import okhttp3.OkHttpClient
import okhttp3.Request

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun HomeRootSection.AphelionHomeView(
    nav: NavBackStackEntry,
    routes: me.eternal.purrfect.ui.manager.Routes
) {
    val skin = LocalPurrfectSkin.current
    val haptic = LocalHapticFeedback.current
    val scrollState = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()
    var controlsHeight by remember { mutableStateOf(100.dp) }

    // State logic restoration
    val avenirNext = remember { FontFamily(Font(R.font.avenir_next_medium, FontWeight.Medium)) }
    val latestUpdate by rememberAsyncMutableState<me.eternal.purrfect.ui.manager.data.Updater.LatestRelease?>(defaultValue = null) {
        Updater.getLatestRelease(Channel.STABLE)
    }
    val downloadState by UpdateDownloader.downloadState.collectAsState()
    val downloadProgress by UpdateDownloader.downloadProgress.collectAsState()
    val isPurrAuraActive by rememberPreferenceBool("debug_test_mode", true)
    
    // Quick Actions Logic
    val activeTarget = context.activeTargetApp
    val isRedditMode = remember(activeTarget) { activeTarget == TargetApp.REDDIT }
    val isSnapchatMode = remember(activeTarget) { activeTarget == TargetApp.SNAPCHAT }
    val targetAppName = remember(activeTarget) {
        when (activeTarget) {
            TargetApp.SNAPCHAT -> "Snapchat"
            TargetApp.REDDIT -> "Reddit"
            TargetApp.WHATSAPP -> "WhatsApp"
            TargetApp.INSTAGRAM -> "Instagram"
        }
    }
    val activeCards = remember(activeTarget) {
        when (activeTarget) {
            TargetApp.REDDIT -> redditCards
            TargetApp.WHATSAPP -> whatsAppCards
            TargetApp.INSTAGRAM -> instagramCards
            TargetApp.SNAPCHAT -> cards
        }
    }
    val allQuickTileNames = remember(activeCards) { activeCards.keys.map { it.first } }

    val selectedTiles = rememberAsyncMutableStateList<String>(defaultValue = emptyList()) {
        context.database.getQuickTiles()
    }

    val channelLabel = "STABLE"

    var showQuickActionsMenu by rememberSaveable { mutableStateOf(false) }
    var showAnnouncementsDialog by rememberSaveable { mutableStateOf(false) }
    var announcementsText by rememberSaveable { mutableStateOf<String?>(null) }
    var showFullChangelogDialog by rememberSaveable { mutableStateOf(false) }
    var fullChangelogText by rememberSaveable { mutableStateOf<String?>(null) }

    val onShowAnnouncements = {
        announcementsText = null
        showAnnouncementsDialog = true
        coroutineScope.launch {
            announcementsText = fetchTextWithFallback(announcementsUrls)
        }
    }

    val onShowFullChangelog = {
        fullChangelogText = null
        showFullChangelogDialog = true
        coroutineScope.launch {
            fullChangelogText = fetchTextWithFallback(changelogStableUrls)
        }
    }

    val onShowQuickActionsMenu = { showQuickActionsMenu = true }
    fun onUpdateAction() {
        if (downloadState == UpdateDownloader.DownloadState.IDLE || downloadState == UpdateDownloader.DownloadState.FAILED) {
            latestUpdate?.let { update ->
                val deviceAbi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64"
                val downloadUrl = if (deviceAbi.contains("64")) {
                    update.assetDownloads["arm64"] ?: update.releaseUrl
                } else {
                    update.assetDownloads["armv7"] ?: update.assetDownloads["arm64"] ?: update.releaseUrl
                }
                
                UpdateDownloader.downloadAndInstall(
                    remoteContext = context,
                    downloadUrl = downloadUrl,
                    fileName = "Purrfect${if (context.activeTargetApp == me.eternal.purrfect.common.TargetApp.REDDIT) "Reddit" else "Snap"}-${update.versionName}.apk",
                    scope = coroutineScope
                )
            }
        }
    }

    if (showAnnouncementsDialog) {
        AestheticDialog(
            onDismissRequest = { showAnnouncementsDialog = false },
            title = translation["announcements_dialog_title"] ?: "Announcements",
            text = announcementsText ?: translation["announcements_dialog_loading"] ?: "Loading...",
            icon = Icons.Default.Campaign,
            confirmButtonText = translation["announcements_dialog_close_button"] ?: "Close",
            onConfirm = { showAnnouncementsDialog = false },
            showCloseButton = false
        )
    }

    if (showFullChangelogDialog) {
        AestheticDialog(
            onDismissRequest = { showFullChangelogDialog = false },
            title = translation["changelog_dialog_title"] ?: "Changelog",
            text = fullChangelogText ?: translation["changelog_dialog_loading"] ?: "Loading...",
            icon = Icons.Default.History,
            confirmButtonText = translation["announcements_dialog_close_button"] ?: "Close",
            onConfirm = { showFullChangelogDialog = false },
            showCloseButton = false
        )
    }

    if (showQuickActionsMenu) {
        QuickActionsDialog(
            quickActions = activeCards,
            selectedQuickActions = selectedTiles,
            onDismiss = { showQuickActionsMenu = false },
            onSave = { updatedTiles ->
                selectedTiles.clear()
                selectedTiles.addAll(updatedTiles)
                context.database.setQuickTiles(updatedTiles)
                showQuickActionsMenu = false
            },
            translation = translation
        )
    }

    @Composable
    fun LivingPurrAura(isActive: Boolean, haptic: HapticFeedback) {
        val infiniteTransition = rememberInfiniteTransition(label = "aura")
        val pulseScale by infiniteTransition.animateFloat(
            initialValue = 0.88f, targetValue = 1.12f,
            animationSpec = infiniteRepeatable(tween(1600, easing = EaseInOutSine), RepeatMode.Reverse),
            label = "pulse"
        )
        val glow1 by infiniteTransition.animateFloat(
            initialValue = 0f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(3200, easing = LinearEasing), RepeatMode.Restart),
            label = "g1"
        )
        val glow2 by infiniteTransition.animateFloat(
            initialValue = 0f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(3200, delayMillis = 1100, easing = LinearEasing), RepeatMode.Restart),
            label = "g2"
        )
        val glow3 by infiniteTransition.animateFloat(
            initialValue = 0f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(3200, delayMillis = 2200, easing = LinearEasing), RepeatMode.Restart),
            label = "g3"
        )
        val coreColor by animateColorAsState(
            targetValue = if (isActive) skin.glowPrimary else (if (skin.isDark) Color(0xFF8C8CA3) else Color(0xFFC0C0C0)),
            animationSpec = tween(800), label = "coreColor"
        )
        val secondaryColor by animateColorAsState(
            targetValue = if (isActive) skin.glowSecondary else (if (skin.isDark) Color(0xFF6B6B7A) else Color(0xFFAAAAAA)),
            animationSpec = tween(800), label = "secondaryColor"
        )
        Canvas(modifier = Modifier.size(44.dp)) {
            val center = Offset(size.width / 2, size.height / 2)
            val baseRadius = 6.dp.toPx()
            fun drawAuroraGlow(progress: Float, alphaMultiplier: Float) {
                if (!isActive || progress <= 0f) return
                val auroraRadius = baseRadius * (1.2f + 4.5f * progress)
                drawCircle(
                    brush = Brush.radialGradient(
                        0.0f to coreColor.copy(alpha = 0.25f * (1f - progress) * alphaMultiplier),
                        0.6f to secondaryColor.copy(alpha = 0.12f * (1f - progress) * alphaMultiplier),
                        1.0f to Color.Transparent,
                        center = center, radius = auroraRadius
                    ),
                    radius = auroraRadius, center = center
                )
            }
            drawAuroraGlow(glow1, 0.8f)
            drawAuroraGlow(glow2, 0.5f)
            drawAuroraGlow(glow3, 0.3f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(coreColor, secondaryColor),
                    center = center, radius = baseRadius * pulseScale
                ),
                radius = baseRadius * pulseScale, center = center
            )
            drawCircle(
                color = (if (skin.isDark) skin.textPrimary else Color.Black).copy(alpha = 0.5f),
                radius = (baseRadius * pulseScale) * 0.25f,
                center = Offset(center.x - (baseRadius * pulseScale) * 0.3f, center.y - (baseRadius * pulseScale) * 0.3f)
            )
        }
    }

    @Composable
    fun AphelionTopBarActionChip(
        icon: ImageVector,
        label: String? = null,
        contentDescription: String? = label,
        shrinkFactor: Float = 1f,
        haptic: HapticFeedback,
        onClick: () -> Unit,
    ) {
        val chipShape = RoundedCornerShape(40)
        val backgroundColor = if (skin.id == "AETHER") skin.cardOverlayColor else (if (skin.isDark) LocalPurrfectSkin.current.textPrimary else Color.Black).copy(alpha = 0.06f)
        Surface(
            modifier = Modifier.height(36.dp).widthIn(min = 36.dp),
            shape = chipShape,
            color = backgroundColor,
            border = BorderStroke(
                1.dp,
                if (skin.id == "AETHER") SolidColor(skin.glowPrimary) else Brush.linearGradient(
                    listOf(
                        skin.glowPrimary.copy(alpha = 0.55f),
                        skin.glowSecondary.copy(alpha = 0.35f)
                    )
                )
            )
        ) {
            Row(
                modifier = Modifier
                    .wrapContentWidth()
                    .clip(chipShape)
                    .clickable { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onClick() }
                    .padding(vertical = 6.dp, horizontal = lerp(10.dp, 12.dp, shrinkFactor)),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = icon, contentDescription = contentDescription, tint = skin.textPrimary,
                    modifier = Modifier.size(20.dp).graphicsLayer {
                        val s = 0.82f + (0.18f * shrinkFactor)
                        scaleX = s; scaleY = s
                    }
                )
                if (label != null) {
                    val labelAlpha = (shrinkFactor - 0.1f).coerceIn(0f, 1f)
                    Spacer(modifier = Modifier.width((8 * shrinkFactor).dp))
                    Text(
                        text = label,
                        color = skin.textPrimary.copy(alpha = labelAlpha),
                        fontSize = 12.sp, fontWeight = FontWeight.Medium,
                        maxLines = 1, overflow = TextOverflow.Clip,
                        modifier = Modifier
                            .graphicsLayer { alpha = labelAlpha; translationX = (-4 * (1f - shrinkFactor)).dp.toPx() }
                            .widthIn(max = (75 * shrinkFactor).dp)
                    )
                }
            }
        }
    }

    @Composable
    fun RowScope.AphelionHomeActionChips(scrollState: ScrollState, haptic: HapticFeedback) {
        val focusFactor = (scrollState.value.toFloat() / Motion.HEADER_MORPH_THRESHOLD).coerceIn(0f, 1f)
        val shrinkFactor = (1f - focusFactor).coerceIn(0f, 1f)
        val isRedditMode = remember { context.activeTargetApp == me.eternal.purrfect.common.TargetApp.REDDIT }
        me.eternal.purrfect.ui.manager.ManagerAssistantEntry(
            context = context,
            routes = routes,
            style = me.eternal.purrfect.ui.manager.ManagerAssistantTriggerStyle.APHELION,
            shrinkFactor = shrinkFactor,
            modifier = Modifier.width(lerp(36.dp, 66.dp, shrinkFactor))
        )
        AphelionTopBarActionChip(
            icon = Icons.Filled.BugReport, label = context.translation["manager.routes.home_logs"],
            shrinkFactor = shrinkFactor, haptic = haptic
        ) { routes.homeLogs.navigate() }
        AphelionTopBarActionChip(
            icon = Icons.Filled.Settings, label = context.translation["manager.routes.home_settings"],
            shrinkFactor = shrinkFactor, haptic = haptic
        ) { routes.settings.navigate() }
    }

    @Composable
    fun HeroBadge(text: String, onClick: () -> Unit = {}) {
        val backgroundColor = if (skin.id == "AETHER") skin.cardOverlayColor else (if (skin.isDark) LocalPurrfectSkin.current.textPrimary else Color.Black).copy(alpha = 0.15f)
        Text(
            text = text,
            color = skin.textPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .clickable { onClick() }
                .background(backgroundColor)
                .then(if (skin.id == "AETHER") Modifier.border(1.dp, skin.textPrimary.copy(alpha = 0.15f), RoundedCornerShape(50)) else Modifier)
                .padding(horizontal = 14.dp, vertical = 6.dp)
        )
    }

    @Composable
    fun ExternalLinkIcon(
        imageVector: ImageVector,
        onClick: () -> Unit,
        tint: Color,
        containerColor: Color,
        haptic: HapticFeedback
    ) {
        val interactionSource = remember { MutableInteractionSource() }
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(50))
                .background(containerColor)
                .scaleOnPress(interactionSource)
                .clickable(interactionSource = interactionSource, indication = null) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onClick()
                }
        ) {
            Icon(
                imageVector = imageVector,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.align(Alignment.Center).size(24.dp)
            )
        }
    }

    @Composable
    fun AphelionHeroSection(
        versionName: String,
        latestUpdate: me.eternal.purrfect.ui.manager.data.Updater.LatestRelease?,
        downloadState: UpdateDownloader.DownloadState,
        downloadProgress: Float,
        onUpdateAction: () -> Unit,
        channelLabel: String,
        isPurrAuraActive: Boolean,
        onAboutClick: () -> Unit,
        avenirNext: FontFamily,
        scrollOffset: () -> Int,
        haptic: HapticFeedback
    ) {
        val heroShape = if (skin.id == "AETHER") me.eternal.purrfect.common.ui.util.G2RoundedRectangle(36.dp) else RoundedCornerShape(36.dp)
        val gitHashShort = remember { (context.installationSummary.modInfo?.gitHash ?: BuildConfig.GIT_HASH).take(7) }
        
        Box(
            modifier = Modifier
                .padding(horizontal = HomeRootSection.cardMargin, vertical = 6.dp)
                .clip(heroShape)
                .background(skin.panelGradient)
                .border(if (skin.id == "AETHER") 2.dp else 1.dp, if (skin.id == "AETHER") skin.glowPrimary else (if (skin.isDark) LocalPurrfectSkin.current.textPrimary else Color.Black).copy(alpha = 0.1f), heroShape)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 22.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    val titleTapSource = remember { MutableInteractionSource() }
                    val titleTapCount = remember { mutableIntStateOf(0) }
                    val lastTitleTapTime = remember { mutableLongStateOf(0L) }
                    
                    Text(
                        text = buildAnnotatedString {
                            append("Purrfect")
                            val targetAccent = when (context.activeTargetApp) {
                                me.eternal.purrfect.common.TargetApp.SNAPCHAT -> Color(0xFFFFE100)
                                me.eternal.purrfect.common.TargetApp.REDDIT -> Color(0xFFFF4500)
                                me.eternal.purrfect.common.TargetApp.WHATSAPP -> Color(0xFF25D366)
                                me.eternal.purrfect.common.TargetApp.INSTAGRAM -> Color(0xFFE4405F)
                            }
                            val targetSuffix = when (context.activeTargetApp) {
                                me.eternal.purrfect.common.TargetApp.SNAPCHAT -> "Snap"
                                me.eternal.purrfect.common.TargetApp.REDDIT -> "Reddit"
                                me.eternal.purrfect.common.TargetApp.WHATSAPP -> "WA"
                                me.eternal.purrfect.common.TargetApp.INSTAGRAM -> "Insta"
                            }
                            withStyle(SpanStyle(color = targetAccent)) {
                                append(targetSuffix)
                            }
                        },
                        color = skin.textPrimary,
                        fontSize = 34.sp,
                        fontWeight = FontWeight.ExtraBold,
                        fontFamily = avenirNext,
                        modifier = Modifier.clickable(
                            interactionSource = titleTapSource,
                            indication = null,
                            onClick = {
                                val now = SystemClock.elapsedRealtime()
                                if (now - lastTitleTapTime.longValue > 1500L) {
                                    titleTapCount.intValue = 0
                                }
                                titleTapCount.intValue += 1
                                lastTitleTapTime.longValue = now
                                if (titleTapCount.intValue >= 5) {
                                    titleTapCount.intValue = 0
                                    val discovery = me.eternal.purrfect.ui.manager.chimaera.ChimaeraDiscovery
                                    discovery.load(context.sharedPreferences)
                                    
                                    if (discovery.isWindowActive) {
                                        discovery.completeStage2(context.sharedPreferences)
                                        routes.navigation?.isFirstUnlock = true
                                        routes.navigation?.pendingTransmission = discovery.unlockMessage
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    } else if (discovery.unlocked) {
                                        routes.navigation?.isFirstUnlock = false
                                        routes.navigation?.showCinematic = true
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    }
                                }
                            }
                        )
                    )
                    Text(
                        text = (translation["hero_tagline"] ?: "").replace("Snapchat", targetAppName),
                        color = skin.textPrimary.copy(alpha = 0.9f),
                        fontSize = 15.sp,
                        lineHeight = 20.sp,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Switch Apps in Settings",
                        color = skin.textPrimary.copy(alpha = 0.5f),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    var versionTapCount by remember { mutableIntStateOf(0) }
                    var lastVersionTapTime by remember { mutableLongStateOf(0L) }

                    HeroBadge(
                        text = translation.format("hero_version_label", "version" to versionName, "channel" to channelLabel),
                        onClick = {
                            val now = System.currentTimeMillis()
                            if (now - lastVersionTapTime > 500) {
                                versionTapCount = 1
                            } else {
                                versionTapCount++
                            }
                            lastVersionTapTime = now
                            if (versionTapCount >= 5) {
                                versionTapCount = 0
                                val discovery = me.eternal.purrfect.ui.manager.chimaera.ChimaeraDiscovery
                                discovery.load(context.sharedPreferences) // Ensure fresh state
                                if (discovery.unlocked) {
                                    routes.navigation?.pendingTransmission = discovery.welcomeBackMessage
                                } else {
                                    discovery.triggerStage1(context.sharedPreferences)
                                    routes.navigation?.pendingTransmission = discovery.transmissionMessage
                                }
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            }
                        }
                    )
                    gitHashShort.takeIf { it.isNotBlank() && it.lowercase() != "unknown" }?.let {
                        HeroBadge(translation.format("hero_build_label", "build" to it))
                    }
                }

                if (latestUpdate != null) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        color = skin.cardOverlayColor,
                        border = BorderStroke(if (skin.id == "AETHER") 2.dp else 1.dp, if (skin.id == "AETHER") skin.glowPrimary.copy(alpha = 0.4f) else (if (skin.isDark) LocalPurrfectSkin.current.textPrimary else Color.Black).copy(alpha = 0.14f)),
                        tonalElevation = 0.dp,
                        shadowElevation = 0.dp
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = translation["update_title"] ?: "Update Available",
                                    color = skin.textPrimary,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 14.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = translation.format(
                                        "update_content",
                                        "version" to latestUpdate!!.versionName
                                    ),
                                    color = skin.textPrimary.copy(alpha = 0.82f),
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            androidx.compose.animation.AnimatedContent(
                                targetState = downloadState,
                                label = "UpdateDownloadHero"
                            ) { currentDownloadState ->
                                when (currentDownloadState) {
                                    me.eternal.purrfect.ui.manager.data.UpdateDownloader.DownloadState.IDLE,
                                    me.eternal.purrfect.ui.manager.data.UpdateDownloader.DownloadState.FAILED -> {
                                        Button(
                                            onClick = { onUpdateAction() },
                                            shape = RoundedCornerShape(50),
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = skin.textPrimary,
                                                contentColor = skin.cardOverlayColor 
                                            ),
                                            border = BorderStroke(1.dp, skin.textPrimary.copy(alpha = 0.12f)),
                                            contentPadding = PaddingValues(12.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Download,
                                                contentDescription = translation["download_icon_description"],
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }

                                    me.eternal.purrfect.ui.manager.data.UpdateDownloader.DownloadState.DOWNLOADING -> {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                                            modifier = Modifier.padding(end = 6.dp)
                                        ) {
                                            if (skin.id == "AETHER") {
                                                me.eternal.purrfect.ui.manager.theme.WavyCircularProgressIndicator(
                                                    modifier = Modifier.size(28.dp),
                                                    color = skin.textPrimary,
                                                    strokeWidth = 4.dp
                                                )
                                            } else {
                                                CircularProgressIndicator(
                                                    progress = { downloadProgress },
                                                    modifier = Modifier.size(28.dp),
                                                    strokeWidth = 3.dp,
                                                    color = skin.textPrimary
                                                )
                                            }
                                            Text(
                                                text = "${(downloadProgress * 100).toInt()}%",
                                                color = skin.textPrimary,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }
                                    }

                                    me.eternal.purrfect.ui.manager.data.UpdateDownloader.DownloadState.COMPLETED -> {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = translation["completed_icon_description"],
                                                tint = Color(0xFFA3F0C2)
                                            )
                                            Text(
                                                text = translation["update_ready_label"] ?: "Ready to Install",
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

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = skin.cardOverlayColor.copy(alpha = 0.45f),
                    shape = RoundedCornerShape(24.dp),
                    border = BorderStroke(1.dp, if (skin.id == "AETHER") skin.textPrimary.copy(alpha = 0.15f) else (if (skin.isDark) LocalPurrfectSkin.current.textPrimary else Color.Black).copy(alpha = 0.10f))
                ) {
                    val unifiedButtonWidth = 180.dp
                    Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        if (isSnapchatMode) {
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = skin.cardOverlayColor.copy(alpha = 0.6f),
                                border = BorderStroke(1.dp, if (skin.id == "AETHER") skin.glowPrimary.copy(alpha = 0.35f) else (if (skin.isDark) LocalPurrfectSkin.current.textPrimary else Color.Black).copy(alpha = 0.10f)),
                                modifier = Modifier.width(unifiedButtonWidth).height(46.dp)
                            ) {
                                Row(modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                                    Box(modifier = Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                                        LivingPurrAura(isActive = isPurrAuraActive, haptic = haptic)
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = if (isPurrAuraActive) translation["purr_aura_active_label"] ?: "" else translation["purr_aura_inactive_label"] ?: "",
                                        color = skin.textPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp
                                    )
                                }
                            }
                        }
                        OutlinedButton(
                            onClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onAboutClick() },
                            border = BorderStroke(1.dp, if (skin.id == "AETHER") skin.glowSecondary.copy(alpha = 0.45f) else (if (skin.isDark) LocalPurrfectSkin.current.textPrimary else Color.Black).copy(alpha = 0.35f)),
                            colors = ButtonDefaults.outlinedButtonColors(containerColor = skin.cardOverlayColor.copy(alpha = 0.2f), contentColor = skin.textPrimary),
                            modifier = Modifier.width(unifiedButtonWidth).height(46.dp)
                        ) {
                            Icon(Icons.Filled.Info, contentDescription = null, tint = skin.textPrimary, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = translation.getOrNull("about_meet_team_button") ?: "About Us", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }
                }

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(26.dp),
                    color = skin.cardOverlayColor.copy(alpha = 0.45f),
                    border = BorderStroke(1.dp, if (skin.id == "AETHER") skin.textPrimary.copy(alpha = 0.15f) else (if (skin.isDark) LocalPurrfectSkin.current.textPrimary else Color.Black).copy(alpha = 0.10f))
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        val androidContext = context.androidContext
                        Button(
                            modifier = Modifier.weight(1f).height(44.dp),
                            onClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); androidContext.openLink("https://thepurrfectproject.com", context.translation["toast_open_link_failed"]) },
                            colors = ButtonDefaults.buttonColors(containerColor = skin.textPrimary, contentColor = skin.cardOverlayColor),
                            contentPadding = PaddingValues(horizontal = 12.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                                Icon(Icons.Filled.Language, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                PurrfectMarqueeText(text = "Site", style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold), color = skin.cardOverlayColor)
                            }
                        }
                        OutlinedButton(
                            modifier = Modifier.weight(1f).height(44.dp),
                            onClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); androidContext.openLink("https://www.purrfectgit.com/r/particle-box/purrfect", context.translation["toast_open_link_failed"]) },
                            border = BorderStroke(1.dp, if (skin.id == "AETHER") skin.glowPrimary.copy(alpha = 0.45f) else (if (skin.isDark) LocalPurrfectSkin.current.textPrimary else Color.Black).copy(alpha = 0.35f)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = skin.textPrimary),
                            contentPadding = PaddingValues(horizontal = 12.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                                Icon(imageVector = ImageVector.vectorResource(id = R.drawable.ic_git), contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                PurrfectMarqueeText(text = translation["github_button"] ?: "PurrfectGit", style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold), color = skin.textPrimary)
                            }
                        }
                        ExternalLinkIcon(
                            imageVector = ImageVector.vectorResource(id = R.drawable.ic_telegram),
                            onClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); androidContext.openLink("https://t.me/purrfect_tg", context.translation["toast_open_link_failed"]) },
                            tint = skin.textPrimary, containerColor = skin.cardOverlayColor.copy(alpha = 0.6f),
                            haptic = haptic
                        )
                    }
                }
            }
        }
    }

    LaunchedEffect(scrollState.value) { routes.navigation?.globalScrollOffset = scrollState.value }

    val borderPath = remember { Path() }
    val uPath = remember { Path() }
    val quickActionsGradientColors = listOf(skin.glowPrimary.copy(alpha = 0.15f), skin.glowSecondary.copy(alpha = 0.05f))

    Box(modifier = Modifier.fillMaxSize().background(skin.backgroundGradient)) {

        val focusFactor by remember(scrollState.value) {
            derivedStateOf { (scrollState.value.toFloat() / Motion.HEADER_MORPH_THRESHOLD).coerceIn(0f, 1f) }
        }
        val stickyBrandingAlpha by remember(scrollState.value) {
            derivedStateOf { ((scrollState.value.toFloat() - 50f) / 100f).coerceIn(0f, 1f) }
        }
        val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val headerHeight = lerp(54.dp, 56.dp, focusFactor)
        val containerTopPadding = lerp(statusBarHeight + 2.dp, 0.dp, focusFactor)
        val internalTopPadding = lerp(0.dp, statusBarHeight, focusFactor)
        val topCorners = lerp(26.dp, 0.dp, focusFactor)
        val bottomCorners = lerp(26.dp, 28.dp, focusFactor)

        Box(modifier = Modifier.fillMaxWidth().zIndex(10f)) {
            val refractiveColor = skin.refractiveColor
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = containerTopPadding)
                    .height(internalTopPadding + headerHeight + 32.dp)
                    .background(
                        Brush.verticalGradient(
                            0.0f to refractiveColor.copy(alpha = 0.95f * focusFactor),
                            0.6f to refractiveColor.copy(alpha = 0.85f * focusFactor),
                            1.0f to Color.Transparent
                        )
                    )
            )

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = containerTopPadding)
                    .headerHeightTracker { controlsHeight = it }
                    .drawBehind {
                        val strokeWidth = 1.dp.toPx()
                        val brush = Brush.linearGradient(
                            listOf(
                                skin.glowPrimary.copy(alpha = focusFactor * 0.6f),
                                skin.glowSecondary.copy(alpha = focusFactor * 0.4f)
                            )
                        )
                        val tr = topCorners.toPx()
                        val br = bottomCorners.toPx()
                        if (focusFactor > 0.9f) {
                            uPath.reset()
                            uPath.apply {
                                moveTo(0f, 0f)
                                lineTo(0f, size.height - br)
                                arcTo(androidx.compose.ui.geometry.Rect(0f, size.height - 2 * br, 2 * br, size.height), 180f, -90f, false)
                                lineTo(size.width - br, size.height)
                                arcTo(androidx.compose.ui.geometry.Rect(size.width - 2 * br, size.height - 2 * br, size.width, size.height), 90f, -90f, false)
                                lineTo(size.width, 0f)
                            }
                            drawPath(uPath, brush, style = Stroke(strokeWidth))
                        } else if (focusFactor > 0.01f) {
                            borderPath.reset()
                            borderPath.apply {
                                moveTo(tr, 0f)
                                lineTo(size.width - tr, 0f)
                                arcTo(androidx.compose.ui.geometry.Rect(size.width - 2 * tr, 0f, size.width, 2 * tr), 270f, 90f, false)
                                lineTo(size.width, size.height - br)
                                arcTo(androidx.compose.ui.geometry.Rect(size.width - 2 * br, size.height - 2 * br, size.width, size.height), 0f, 90f, false)
                                lineTo(br, size.height)
                                arcTo(androidx.compose.ui.geometry.Rect(0f, size.height - 2 * br, 2 * br, size.height), 90f, 90f, false)
                                lineTo(0f, tr)
                                arcTo(androidx.compose.ui.geometry.Rect(0f, 0f, 2 * tr, 2 * tr), 180f, 90f, false)
                            }
                            drawPath(borderPath, brush, style = Stroke(strokeWidth))
                        }
                    },
                shape = RoundedCornerShape(topStart = topCorners, topEnd = topCorners, bottomStart = bottomCorners, bottomEnd = bottomCorners),
                color = skin.cardOverlayColor.copy(alpha = focusFactor * 0.95f)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = internalTopPadding)
                        .padding(horizontal = lerp(14.dp, 28.dp, focusFactor))
                        .height(headerHeight)
                ) {
                    val rowShrinkFactor = (1f - focusFactor).coerceIn(0f, 1f)
                    
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // LEADING BRANDING: Fades in and expands width to push icons
                        Box(modifier = Modifier.width(lerp(0.dp, 72.dp, focusFactor))) {
                            Text(
                                text = "Purrfect",
                                color = skin.textPrimary.copy(alpha = stickyBrandingAlpha),
                                fontSize = 18.sp, fontWeight = FontWeight.Bold, fontFamily = avenirNext,
                                maxLines = 1,
                                modifier = Modifier.graphicsLayer { 
                                    alpha = stickyBrandingAlpha
                                    translationX = (-12 * (1f - stickyBrandingAlpha)).dp.toPx() 
                                }
                            )
                        }

                        // DYNAMIC SPACER: Only active during scroll
                        if (focusFactor > 0.1f) {
                            Spacer(modifier = Modifier.weight(1f))
                        }

                        // UNIFIED ACTION CLUSTER: Spans wide when static, clumps when scrolling
                        Row(
                            modifier = Modifier.then(if (focusFactor <= 0.1f) Modifier.weight(1f) else Modifier.wrapContentWidth()),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = if (focusFactor <= 0.1f) Arrangement.SpaceBetween else Arrangement.spacedBy(8.dp)
                        ) {
                            AphelionTopBarActionChip(
                                icon = Icons.Filled.Notifications, label = null,
                                shrinkFactor = rowShrinkFactor,
                                contentDescription = translation["announcements_button_description"],
                                haptic = haptic
                            ) { onShowAnnouncements() }
                            
                            AphelionTopBarActionChip(
                                icon = Icons.Filled.Description, label = null,
                                shrinkFactor = rowShrinkFactor,
                                contentDescription = translation.getOrNull("changelog_button_description") ?: "Open full changelog",
                                haptic = haptic
                            ) { onShowFullChangelog() }

                            me.eternal.purrfect.ui.manager.ManagerAssistantEntry(
                                context = context,
                                routes = routes,
                                style = me.eternal.purrfect.ui.manager.ManagerAssistantTriggerStyle.APHELION,
                                shrinkFactor = rowShrinkFactor,
                                modifier = Modifier.width(lerp(36.dp, 66.dp, rowShrinkFactor))
                            )

                            AphelionTopBarActionChip(
                                icon = Icons.Filled.BugReport, label = context.translation["manager.routes.home_logs"],
                                shrinkFactor = rowShrinkFactor, haptic = haptic
                            ) { routes.homeLogs.navigate() }

                            AphelionTopBarActionChip(
                                icon = Icons.Filled.Settings, label = context.translation["manager.routes.home_settings"],
                                shrinkFactor = rowShrinkFactor, haptic = haptic
                            ) { routes.settings.navigate() }
                        }
                    }
                }
            }
        }

        Column(modifier = Modifier.fillMaxSize().verticalScroll(scrollState).padding(bottom = routes.bottomPadding + 4.dp)) {
            Spacer(Modifier.height(controlsHeight + containerTopPadding))

            AphelionHeroSection(
                versionName = BuildConfig.VERSION_NAME,
                latestUpdate = latestUpdate,
                downloadState = downloadState,
                downloadProgress = downloadProgress,
                onUpdateAction = { onUpdateAction() },
                channelLabel = channelLabel,
                isPurrAuraActive = isPurrAuraActive,
                onAboutClick = { routes.about.navigate() },
                avenirNext = avenirNext,
                scrollOffset = { scrollState.value },
                haptic = haptic
            )

            Spacer(Modifier.height(12.dp))

            AnimatedContent<Boolean>(targetState = selectedTiles.isNotEmpty(), label = "QuickActions") { hasQuickActions ->
                Surface(
                    modifier = Modifier.padding(horizontal = HomeRootSection.cardMargin, vertical = 10.dp),
                    shape = RoundedCornerShape(34.dp),
                    color = Color.Transparent,
                    border = BorderStroke(1.dp, (if (skin.isDark) LocalPurrfectSkin.current.textPrimary else Color.Black).copy(alpha = 0.05f))
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().background(Brush.linearGradient(quickActionsGradientColors)).padding(horizontal = 24.dp, vertical = 28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if (!hasQuickActions) {
                            Text(translation["quick_actions_title"] ?: "Quick Actions", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = skin.textPrimary.copy(alpha = 0.85f))
                            Spacer(Modifier.height(24.dp))
                            Icon(Icons.Outlined.Widgets, contentDescription = null, modifier = Modifier.size(72.dp), tint = skin.textPrimary)
                            Spacer(Modifier.height(20.dp))
                            Button(
                                onClick = { onShowQuickActionsMenu() },
                                colors = ButtonDefaults.buttonColors(containerColor = skin.textPrimary, contentColor = skin.cardOverlayColor)
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(translation["quick_actions_add_tile_button"] ?: "Add Tile")
                            }
                        } else {
                            Column(modifier = Modifier.fillMaxWidth().padding(bottom = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(translation["quick_actions_title"] ?: "Quick Actions", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = skin.textPrimary)
                                Text(translation.format("quick_actions_count_label", "count" to selectedTiles.size.toString()), fontSize = 13.sp, color = skin.textPrimary.copy(alpha = 0.75f))
                                Spacer(Modifier.height(12.dp))
                                OutlinedButton(
                                    onClick = { onShowQuickActionsMenu() },
                                    border = BorderStroke(1.dp, (if (skin.isDark) LocalPurrfectSkin.current.textPrimary else Color.Black).copy(alpha = 0.3f)),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = skin.textPrimary)
                                ) {
                                    Icon(imageVector = ImageVector.vectorResource(id = R.drawable.ic_manage), contentDescription = null, modifier = Modifier.size(18.dp))      
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(translation["quick_actions_manage_button"] ?: "Manage")
                                }
                            }
                            var gridIsVisible by remember { mutableStateOf(false) }
                            var animationPhase by remember { mutableIntStateOf(1) }
                            LaunchedEffect(gridIsVisible) {
                                if (gridIsVisible) {
                                    delay(600); animationPhase = 2
                                    delay(1200); animationPhase = 3
                                }
                            }

                            BoxWithConstraints(
                                modifier = Modifier.fillMaxWidth().onGloballyPositioned { coords ->
                                    val windowHeight = context.androidContext.resources.displayMetrics.heightPixels
                                    val posY = coords.localToWindow(Offset.Zero).y
                                    if (posY > 0 && posY < windowHeight * 0.95f) gridIsVisible = true
                                }
                            ) {
                                val columns = (maxWidth / 110.dp).toInt().coerceIn(2, 4)
                                FlowRow(
                                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                                    horizontalArrangement = Arrangement.SpaceEvenly,
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                    maxItemsInEachRow = columns
                                ) {
                                    for (tileName in selectedTiles) {
                                    val cardEntry = activeCards.entries.find { it.key.first == tileName }
                                        if (cardEntry != null) {
                                            val interactionSource = remember { MutableInteractionSource() }
                                            val animatedIconSize by animateDpAsState(
                                                targetValue = if (animationPhase >= 2) 28.dp else 44.dp,
                                                animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow),
                                                label = "iconShrink"
                                            )
                                            Surface(
                                                modifier = Modifier.width(100.dp).aspectRatio(1.05f).scaleOnPress(interactionSource)
                                                    .clickable { haptic.performHapticFeedback(HapticFeedbackType.LongPress); cardEntry.value(routes) },
                                                shape = RoundedCornerShape(18.dp),
                                                color = (if (skin.isDark) LocalPurrfectSkin.current.textPrimary else Color.Black).copy(alpha = 0.06f),
                                                border = BorderStroke(1.dp, (if (skin.isDark) LocalPurrfectSkin.current.textPrimary else Color.Black).copy(alpha = 0.16f))
                                            ) {
                                                Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(skin.glowPrimary.copy(alpha = 0.3f), skin.glowSecondary.copy(alpha = 0.22f)))).clipToBounds()) {
                                                    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                                        Icon(cardEntry.key.second, contentDescription = null, tint = skin.textPrimary, modifier = Modifier.size(animatedIconSize))
                                                        Spacer(Modifier.height(8.dp))
                                                        PurrfectMarqueeText(
                                                            text = cardEntry.key.first,
                                                            style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center),
                                                            color = skin.textPrimary,
                                                            modifier = Modifier.fillMaxWidth()
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
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}
