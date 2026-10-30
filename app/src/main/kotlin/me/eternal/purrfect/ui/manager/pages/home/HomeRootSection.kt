package me.eternal.purrfect.ui.manager.pages.home

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.State
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.navigation.NavBackStackEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.eternal.purrfect.R
import me.eternal.purrfect.action.EnumQuickActions
import me.eternal.purrfect.common.BuildConfig
import me.eternal.purrfect.common.Constants
import me.eternal.purrfect.common.action.EnumAction
import me.eternal.purrfect.common.ui.rememberAsyncMutableState
import me.eternal.purrfect.common.ui.rememberAsyncMutableStateList
import me.eternal.purrfect.common.util.ktx.openLink
import me.eternal.purrfect.storage.getQuickTiles
import me.eternal.purrfect.storage.setQuickTiles
import me.eternal.purrfect.common.ui.theme.LocalPurrfectSkin
import me.eternal.purrfect.ui.manager.theme.aetherGlass
import me.eternal.purrfect.ui.manager.Routes
import me.eternal.purrfect.ui.manager.ManagerTheme
import me.eternal.purrfect.ui.manager.ManagerAssistantEntry
import me.eternal.purrfect.ui.manager.ManagerAssistantTriggerStyle
import me.eternal.purrfect.ui.manager.theme.PurrfectPalette
import me.eternal.purrfect.ui.manager.data.UpdateDownloader
import me.eternal.purrfect.ui.manager.data.Updater
import me.eternal.purrfect.ui.manager.data.Updater.Channel
import me.eternal.purrfect.ui.manager.components.AestheticDialog
import me.eternal.purrfect.ui.setup.Requirements
import me.eternal.purrfect.ui.setup.SetupPreferences
import me.eternal.purrfect.ui.util.ActivityLauncherHelper
import me.eternal.purrfect.ui.util.AlertDialogs
import me.eternal.purrfect.ui.util.scaleOnPress
import okhttp3.OkHttpClient
import okhttp3.Request

class HomeRootSection : Routes.Route() {
    override val translation by lazy { context.translation.getCategory("manager.sections.home") }

    companion object {
        internal const val QUICK_TILES_INITIALIZED_PREF = "quick_tiles_initialized"
        val cardMargin = 10.dp
        val pageBackgroundGradient = Brush.verticalGradient(
            listOf(
                Color(0xFF261F58),
                Color(0xFF302A6D),
                Color(0xFF241F52)
            )
        )
    }

    internal val changelogClient by lazy { OkHttpClient() }
    internal val changelogStableUrls = listOf(
        "https://www.purrfectgit.com/r/particle-box/purrfect/file?branch=dev&path=changelogs-stable.txt",
    )
    internal val changelogPrereleaseUrls = listOf(
        "https://www.purrfectgit.com/r/particle-box/purrfect/file?branch=dev&path=changelogs-prerelease.txt",
    )
    internal val announcementsUrls = listOf(
        "https://www.purrfectgit.com/r/particle-box/purrfect/file?branch=dev&path=announcements.txt",
    )
    internal val changelogStableUrl = changelogStableUrls.first()
    internal val changelogPrereleaseUrl = changelogPrereleaseUrls.first()
    internal val announcementsUrl = announcementsUrls.first()
    internal val purrfectRepositoryUrl = "https://www.purrfectgit.com/r/particle-box/purrfect"
    internal val purrfectFallbackRepositoryUrl = "https://www.purrfectgit.com/r/particle-box/purrfect"

    internal suspend fun fetchTextWithFallback(urls: List<String>): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        var lastError: Throwable? = null
        urls.forEach { url ->
            runCatching {
                changelogClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    if (!response.isSuccessful) throw IllegalStateException("Failed to fetch $url (${response.code})")
                    response.body?.string() ?: throw IllegalStateException("Empty body from $url")
                }
            }.onSuccess { return@withContext it }
                .onFailure { lastError = it }
        }
        throw lastError ?: IllegalStateException("No fallback URLs configured")
    }

    internal fun openPurrfectRepository(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            val url = if (isUrlReachable(purrfectRepositoryUrl)) {
                purrfectRepositoryUrl
            } else {
                purrfectFallbackRepositoryUrl
            }
            withContext(Dispatchers.Main) {
                context.androidContext.openLink(url, context.translation["toast_open_link_failed"])
            }
        }
    }

    internal fun installedInstagramPackages(): List<String> {
        val packageManager = context.androidContext.packageManager
        return Constants.INSTAGRAM_PACKAGE_NAMES.filter { packageName ->
            runCatching {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0)
                true
            }.getOrDefault(false)
        }
    }

    internal fun primaryInstagramPackage(): String {
        return installedInstagramPackages().firstOrNull() ?: Constants.INSTAGRAM_PACKAGE_NAME
    }

    private fun isUrlReachable(url: String): Boolean {
        return runCatching {
            changelogClient.newCall(Request.Builder().url(url).head().build()).execute().use { response ->
                response.code in 200..399
            }
        }.getOrDefault(false)
    }

    internal fun launchRedditUpdateSetup() {
        val installMode = SetupPreferences.lastInstallModeName(context.sharedPreferences)
        val skippedAutoSetup = SetupPreferences.wasAutoSetupSkipped(context.sharedPreferences)
        val requirement = if (!skippedAutoSetup && installMode == "NON_ROOT") {
            Requirements.REDDIT_REPATCH
        } else {
            Requirements.UPDATE_REDDIT
        }
        val currentContext = context.activity ?: context.androidContext
        Intent(currentContext, me.eternal.purrfect.ui.setup.SetupActivity::class.java).apply {
            putExtra("requirements", requirement)
            if (currentContext !is Activity) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            currentContext.startActivity(this)
        }
    }

    internal val heroGradientColors = listOf(
        Color(0xFF5C4B99),
        Color(0xFF322B5E),
        Color(0xFF1B1836)
    )
    internal val quickActionsGradientColors = listOf(
        Color(0xFF241C3E),
        Color(0xFF151127)
    )
    private lateinit var activityLauncherHelper: ActivityLauncherHelper
    data class QaCard(val id: String, val name: String, val icon: ImageVector, val action: (Routes) -> Unit)
    internal val cardEntries by lazy {
        val list = mutableListOf<QaCard>()
        EnumQuickActions.entries.forEach { q ->
            val name = context.translation["actions.${q.key}.name"]
            list.add(QaCard(id = "quick.${q.key}", name = name, icon = q.icon, action = q.action))
        }
        EnumAction.entries.forEach { a ->
            val name = context.translation["actions.${a.key}.name"]
            list.add(QaCard(id = "action.${a.key}", name = name, icon = a.icon, action = { context.launchActionIntent(a) }))
        }
        list
    }
    internal val cards by lazy {
        EnumQuickActions.entries.map {
            (context.translation["actions.${it.key}.name"] to it.icon) to it.action
        }.associate {
            it.first to it.second
        }.toMutableMap().apply {
            EnumAction.entries.forEach { action ->
                this[context.translation["actions.${action.key}.name"] to action.icon] = {
                    context.launchActionIntent(action)
                }
            }
        }
    }
    internal val redditCards by lazy {
        mutableMapOf<Pair<String, ImageVector>, Routes.() -> Unit>(
            ("Force Stop Reddit" to Icons.Default.StopCircle) to {
                context.forceStopTargetPackage(Constants.REDDIT_PACKAGE_NAME, "Reddit")
            },
            ("Open Reddit" to Icons.Default.OpenInNew) to {
                context.openTargetPackage(Constants.REDDIT_PACKAGE_NAME, "Reddit")
            }
        )
    }
    internal val whatsAppCards by lazy {
        mutableMapOf<Pair<String, ImageVector>, Routes.() -> Unit>(
            ("Force Stop WhatsApp" to Icons.Default.StopCircle) to {
                context.forceStopTargetPackage(Constants.WHATSAPP_PACKAGE_NAME, "WhatsApp")
            },
            ("Open WhatsApp" to Icons.Default.OpenInNew) to {
                context.openTargetPackage(Constants.WHATSAPP_PACKAGE_NAME, "WhatsApp")
            }
        )
    }
    internal val instagramCards by lazy {
        mutableMapOf<Pair<String, ImageVector>, Routes.() -> Unit>(
            ("Force Stop Instagram" to Icons.Default.StopCircle) to {
                context.forceStopTargetPackage(primaryInstagramPackage(), "Instagram")
            },
            ("Open Instagram" to Icons.Default.OpenInNew) to {
                context.openTargetPackage(primaryInstagramPackage(), "Instagram")
            }
        )
    }

    @Composable
    internal fun rememberPreferenceBool(key: String, default: Boolean = false): State<Boolean> {
        val prefs = remember { context.sharedPreferences }
        val state = remember { mutableStateOf(prefs.getBoolean(key, default)) }
        DisposableEffect(prefs, key) {
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, changedKey ->
                if (changedKey == key) {
                    state.value = prefs.getBoolean(key, default)
                }
            }
            prefs.registerOnSharedPreferenceChangeListener(listener)
            onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
        }
        return state
    }

    @Composable
    fun ExternalLinkIcon(
        modifier: Modifier = Modifier,
        size: Dp = 44.dp,
        imageVector: ImageVector,
        onClick: (() -> Unit)? = null,
        tint: Color? = null,
        containerColor: Color? = null,
        haptic: HapticFeedback? = null,
    ) {
        val skin = LocalPurrfectSkin.current
        val interactionSource = remember { MutableInteractionSource() }
        val clickModifier = if (onClick != null) {
            Modifier.clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current
            ) {
                haptic?.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            }
        } else {
            Modifier
        }
        Box(
            modifier = modifier
                .size(size)
                .clip(RoundedCornerShape(50))
                .background(containerColor ?: skin.glowPrimary.copy(alpha = 0.08f))
                .scaleOnPress(interactionSource)
                .then(clickModifier)
        ) {
            Icon(
                imageVector = imageVector,
                contentDescription = null,
                tint = tint ?: skin.textPrimary,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(size * 0.55f)
            )
        }
    }

    @Composable
    internal fun HeroBadge(text: String) {
        val skin = LocalPurrfectSkin.current
        Text(
            text = text,
            color = skin.textPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(skin.textPrimary.copy(alpha = 0.15f))
                .padding(horizontal = 14.dp, vertical = 6.dp)
        )
    }

    @Composable
    private fun TopBarActionChip(
        icon: ImageVector,
        label: String? = null,
        contentDescription: String? = label,
        modifier: Modifier = Modifier,
        onClick: () -> Unit,
    ) {
        val skin = LocalPurrfectSkin.current
        Surface(
            modifier = modifier.height(36.dp),
            shape = RoundedCornerShape(40),
            color = skin.textPrimary.copy(alpha = 0.06f),
            border = BorderStroke(1.dp, skin.textPrimary.copy(alpha = 0.12f))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(40))
                    .clickable(onClick = onClick)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(icon, contentDescription = contentDescription, tint = skin.textPrimary, modifier = Modifier.size(20.dp))
                label?.let {
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = it,
                        color = skin.textPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }

    @Composable
    private fun InfoCard(content: @Composable ColumnScope.() -> Unit) {
        val skin = LocalPurrfectSkin.current
        OutlinedCard(
            modifier = Modifier
                .padding(start = cardMargin, end = cardMargin)
                .fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = skin.cardOverlayColor,
                contentColor = skin.textPrimary
            ),
            border = BorderStroke(1.dp, skin.glassBorder)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(all = 10.dp),
                content = content
            )
        }
    }

    @Composable
    private fun RowScope.HomeActionChips() {
        val isRedditMode = remember { context.activeTargetApp == me.eternal.purrfect.common.TargetApp.REDDIT }

        ManagerAssistantEntry(
            context = context,
            routes = routes,
            style = ManagerAssistantTriggerStyle.DEFAULT
        )
        if (!isRedditMode) {
            TopBarActionChip(
                icon = Icons.Filled.BugReport,
                label = context.translation["manager.routes.home_logs"],
                modifier = Modifier
            ) { routes.homeLogs.navigate() }
        }
        TopBarActionChip(
            icon = Icons.Filled.Info,
            label = translation["manager.routes.home_about"],
            modifier = Modifier
        ) { routes.about.navigate() }
    }


    @Composable
    private fun AuroraBackground() {
        val skin = LocalPurrfectSkin.current
        val infiniteTransition = rememberInfiniteTransition(label = "aurora")
        val driftX by infiniteTransition.animateFloat(
            initialValue = -120f,
            targetValue = 220f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 16000, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "driftX"
        )
        val driftY by infiniteTransition.animateFloat(
            initialValue = 80f,
            targetValue = -140f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 14000, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "driftY"
        )
        val shimmer by infiniteTransition.animateFloat(
            initialValue = -120f,
            targetValue = 160f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 11000, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "shimmer"
        )

        val primaryGlow = skin.glowPrimary.copy(alpha = 0.28f)
        val tertiaryGlow = skin.glowSecondary.copy(alpha = 0.22f)
        val trailGradient = listOf(
            skin.glowPrimary.copy(alpha = 0.18f),
            skin.glowSecondary.copy(alpha = 0.16f),
            skin.glowPrimary.copy(alpha = 0.14f)
        )

        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(primaryGlow, Color.Transparent),
                    center = Offset(
                        x = size.width * 0.25f + driftX,
                        y = size.height * 0.18f + driftY * 0.4f
                    ),
                    radius = size.minDimension * 0.9f
                ),
                alpha = 0.85f
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(tertiaryGlow, Color.Transparent),
                    center = Offset(
                        x = size.width * 0.78f - driftX * 0.45f,
                        y = size.height * 0.72f
                    ),
                    radius = size.minDimension * 0.95f
                ),
                alpha = 0.9f
            )
            drawRect(
                brush = Brush.linearGradient(
                    colors = trailGradient,
                    start = Offset(x = 0f, y = size.height * 0.15f + shimmer),
                    end = Offset(x = size.width, y = size.height * 0.9f + shimmer)
                ),
                size = this.size,
                alpha = 0.24f
            )
        }
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun HeroSection(
        versionName: String,
        latestUpdate: Updater.LatestRelease?,
        downloadState: UpdateDownloader.DownloadState,
        downloadProgress: Float,
        onUpdateAction: () -> Unit,
        channelLabel: String,
        isPurrAuraActive: Boolean,
        onWebsiteClick: () -> Unit,
        onTelegramClick: () -> Unit,
        onGithubClick: () -> Unit,
        onManageClick: () -> Unit,
        avenirNext: FontFamily
    ) {
        val skin = LocalPurrfectSkin.current
        val heroShape = RoundedCornerShape(36.dp)
        val gitHashShort = remember { (context.installationSummary.modInfo?.gitHash ?: BuildConfig.GIT_HASH).take(7) }
        val activeTarget = context.activeTargetApp
        val targetSuffix = remember(activeTarget) {
            when (activeTarget) {
                me.eternal.purrfect.common.TargetApp.SNAPCHAT -> "Snap"
                me.eternal.purrfect.common.TargetApp.REDDIT -> "Reddit"
                me.eternal.purrfect.common.TargetApp.WHATSAPP -> "WA"
                me.eternal.purrfect.common.TargetApp.INSTAGRAM -> "Insta"
            }
        }
        val targetAppName = remember(activeTarget) {
            when (activeTarget) {
                me.eternal.purrfect.common.TargetApp.SNAPCHAT -> "Snapchat"
                me.eternal.purrfect.common.TargetApp.REDDIT -> "Reddit"
                me.eternal.purrfect.common.TargetApp.WHATSAPP -> "WhatsApp"
                me.eternal.purrfect.common.TargetApp.INSTAGRAM -> "Instagram"
            }
        }
        Box(
            modifier = Modifier
                .padding(horizontal = cardMargin, vertical = 6.dp)
                .clip(heroShape)
                .background(skin.cardOverlayColor.copy(alpha = 0.45f))
                .border(1.dp, skin.glassBorder.copy(alpha = 0.4f), heroShape)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 22.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Purrfect$targetSuffix",
                        color = skin.textPrimary,
                        fontSize = 34.sp,
                        fontWeight = FontWeight.ExtraBold,
                        fontFamily = avenirNext
                    )
                    Text(
                        text = (translation["hero_tagline"] ?: "").replace("Snapchat", targetAppName),
                        color = skin.textPrimary.copy(alpha = 0.9f),
                        fontSize = 15.sp,
                        lineHeight = 20.sp,
                        textAlign = TextAlign.Center
                    )
                }
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            HeroBadge(translation.format("hero_version_label", "version" to versionName, "channel" to channelLabel))
            gitHashShort.takeIf { it.isNotBlank() && it.lowercase() != "unknown" }?.let {
                HeroBadge(translation.format("hero_build_label", "build" to it))
            }
        }

                if (latestUpdate != null) {
                    Surface(
                        modifier = Modifier.fillMaxWidth().aetherGlass(skin, 20.dp),
                        shape = RoundedCornerShape(20.dp),
                        color = Color.Transparent,
                        border = BorderStroke(1.dp, skin.glassBorder.copy(alpha = 0.3f)),
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
                                    text = translation["update_title"],
                                    color = skin.textPrimary,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 14.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = translation.format(
                                        "update_content",
                                        "version" to (latestUpdate.versionName)
                                    ),
                                    color = skin.textPrimary.copy(alpha = 0.82f),
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            AnimatedContent(
                                targetState = downloadState,
                                label = "UpdateDownloadHero"
                            ) { state ->
                                when (state) {
                                    UpdateDownloader.DownloadState.IDLE,
                                    UpdateDownloader.DownloadState.FAILED -> {
                                        Button(
                                        onClick = onUpdateAction,
                                        shape = RoundedCornerShape(50),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = skin.textPrimary,
                                            contentColor = skin.cardOverlayColor
                                        ),
                                        border = BorderStroke(1.dp, skin.glassBorder.copy(alpha = 0.12f)),
                                        contentPadding = PaddingValues(12.dp)
                                        ) {                                            Icon(
                                                imageVector = Icons.Default.Download,
                                                contentDescription = translation["download_icon_description"],
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }

                                    UpdateDownloader.DownloadState.DOWNLOADING -> {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                                            modifier = Modifier.padding(end = 6.dp)
                                        ) {
                                            CircularProgressIndicator(
                                                progress = { downloadProgress },
                                                modifier = Modifier.size(28.dp),
                                                strokeWidth = 3.dp,
                                                color = skin.textPrimary
                                            )
                                            Text(
                                                text = "${(downloadProgress * 100).toInt()}%",
                                                color = skin.textPrimary,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }
                                    }

                                    UpdateDownloader.DownloadState.COMPLETED -> {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = translation["completed_icon_description"],
                                                tint = skin.glowPrimary
                                            )
                                            Text(
                                                text = translation["update_ready_label"],
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
                    color = skin.cardOverlayColor.copy(alpha = 0.3f),
                    shape = RoundedCornerShape(24.dp),
                    border = BorderStroke(1.dp, skin.glassBorder.copy(alpha = 0.25f)),
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if (context.activeTargetApp == me.eternal.purrfect.common.TargetApp.SNAPCHAT) {
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = skin.glowPrimary.copy(alpha = 0.12f),
                                border = BorderStroke(1.dp, skin.glassBorder.copy(alpha = 0.2f)),
                                tonalElevation = 0.dp,
                                shadowElevation = 0.dp
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(14.dp)
                                            .clip(RoundedCornerShape(50))
                                            .background(if (isPurrAuraActive) skin.glowPrimary else Color(0xFF8C8CA3))
                                    )
                                    Text(
                                        text = if (isPurrAuraActive) translation["purr_aura_active_label"] else translation["purr_aura_inactive_label"],
                                        color = skin.textPrimary,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp
                                    )
                                }
                            }
                        }

                        OutlinedButton(
                            onClick = onManageClick,
                            border = BorderStroke(1.dp, skin.glassBorder.copy(alpha = 0.4f)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = skin.textPrimary),
                            modifier = Alignment.CenterHorizontally.let { Modifier.align(it) }
                        ) {
                            Icon(Icons.Filled.Settings, contentDescription = null, tint = skin.textPrimary)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(translation["open_settings_button"])
                        }
                    }
                }

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(26.dp),
                    color = skin.cardOverlayColor.copy(alpha = 0.25f),
                    border = BorderStroke(1.dp, skin.glassBorder.copy(alpha = 0.2f)),
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            modifier = Modifier.weight(1f),
                            onClick = onWebsiteClick,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = skin.textPrimary,
                                contentColor = skin.cardOverlayColor
                            )
                        ) {
                            Icon(Icons.Filled.Language, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = "Site", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            onClick = onGithubClick,
                            border = BorderStroke(1.dp, skin.glassBorder.copy(alpha = 0.45f)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = skin.textPrimary)
                        ) {
                            Icon(
                                imageVector = ImageVector.vectorResource(id = R.drawable.ic_git),
                                contentDescription = null,
                                tint = skin.textPrimary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(text = translation["github_button"] ?: "PurrfectGit", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        ExternalLinkIcon(
                            imageVector = ImageVector.vectorResource(id = R.drawable.ic_telegram),
                            onClick = onTelegramClick,
                            tint = skin.textPrimary,
                            containerColor = skin.cardOverlayColor.copy(alpha = 0.35f)
                        )
                    }
                }
            }
        }
    }
    internal fun resolveTileKey(name: String): String {
        val entry = cardEntries.firstOrNull { it.name == name }
        return entry?.id ?: name
    }
    private fun getTileSpan(name: String): Pair<Int, Int> {
        val prefs = context.sharedPreferences
        val key = resolveTileKey(name)
        val raw = prefs.getString("quick_tile_size_$key", null) ?: "1x1"
        val parts = raw.split('x')
        val w = parts.getOrNull(0)?.toIntOrNull()?.coerceIn(1, 3) ?: 1
        val h = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(1, 3) ?: 1
        return w to h
    }
    private fun setTileSpan(name: String, w: Int, h: Int) {
        val prefs = context.sharedPreferences
        val key = resolveTileKey(name)
        prefs.edit().putString("quick_tile_size_$key", "${w.coerceIn(1,3)}x${h.coerceIn(1,3)}").apply()
    }
    internal fun clearTileSpan(name: String) {
        val prefs = context.sharedPreferences
        val key = resolveTileKey(name)
        prefs.edit().remove("quick_tile_size_$key").apply()
    }

    internal fun clearTileOffset(name: String) {
        val prefs = context.sharedPreferences
        val key = resolveTileKey(name)
        prefs.edit().remove("quick_tile_offset_$key").apply()
    }

    override val title: @Composable (() -> Unit)? = {}
    override val init: () -> Unit = {
        activityLauncherHelper = ActivityLauncherHelper(context.activity!!)
    }
    override val topBarActions: @Composable (RowScope.() -> Unit) = {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.wrapContentWidth(),
        ) {
            HomeActionChips()
        }
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
                this@HomeRootSection.HomeScreen(nav)
            }
        }
    }

    internal fun extractChangelogForVersion(raw: String, version: String): String {
    val lines = raw.lines()
    val headerRegex = Regex("^\\s*#+\\s*v?${Regex.escape(version)}\\b", RegexOption.IGNORE_CASE)
    val collected = mutableListOf<String>()
    var collecting = false
    for (line in lines) {
        if (!collecting) {
            if (headerRegex.containsMatchIn(line)) {
                collecting = true
            }
            continue
        }
        if (line.trimStart().startsWith("#")) break
        collected.add(line)
    }
    return collected.joinToString("\n").trim()
}
}
