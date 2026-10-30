package me.eternal.purrfect

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.app.CoreComponentFactory
import androidx.documentfile.provider.DocumentFile
import coil.ImageLoader
import coil.decode.VideoFrameDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import me.eternal.purrfect.bridge.BridgeService
import me.eternal.purrfect.common.BuildConfig
import me.eternal.purrfect.common.Constants
import me.eternal.purrfect.common.ReceiversConfig
import me.eternal.purrfect.common.TargetApp
import me.eternal.purrfect.common.action.EnumAction
import me.eternal.purrfect.common.bridge.wrapper.LocaleWrapper
import me.eternal.purrfect.common.bridge.wrapper.LoggerWrapper
import me.eternal.purrfect.common.bridge.wrapper.MappingsWrapper
import me.eternal.purrfect.common.config.ModConfig
import me.eternal.purrfect.common.config.impl.RootConfig
import me.eternal.purrfect.common.logger.fatalCrash
import me.eternal.purrfect.common.util.snap.SnapWidgetBroadcastReceiverHelper
import me.eternal.purrfect.common.util.constantLazyBridge
import me.eternal.purrfect.common.util.getPurgeTime
import me.eternal.purrfect.e2ee.E2EEImplementation
import me.eternal.purrfect.scripting.RemoteScriptManager
import me.eternal.purrfect.storage.AppDatabase
import me.eternal.purrfect.task.RemoteTaskInterface
import me.eternal.purrfect.task.TaskManager
import me.eternal.purrfect.ui.manager.MainActivity
import me.eternal.purrfect.ui.manager.data.InstallationSummary
import me.eternal.purrfect.ui.manager.data.ModInfo
import me.eternal.purrfect.ui.manager.data.PlatformInfo
import me.eternal.purrfect.ui.manager.data.SnapchatAppInfo
import me.eternal.purrfect.ui.overlay.RemoteOverlay
import me.eternal.purrfect.ui.setup.Requirements
import me.eternal.purrfect.ui.setup.SetupActivity
import me.eternal.purrfect.ui.setup.SetupPreferences
import me.eternal.purrfect.task.AnnouncementCheckWorker
import java.io.ByteArrayInputStream
import java.lang.ref.WeakReference
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import com.tonyodev.fetch2.Fetch
import com.tonyodev.fetch2.FetchConfiguration
import java.io.File
import java.util.concurrent.TimeUnit


class RemoteSideContext(
    val androidContext: Context
) {
    val fetch: Fetch by lazy {
        val fetchConfiguration = FetchConfiguration.Builder(androidContext)
            .setDownloadConcurrentLimit(3)
            .build()
        Fetch.getInstance(fetchConfiguration)
    }
    val coroutineScope = CoroutineScope(Dispatchers.IO)

    private var _activity: WeakReference<ComponentActivity>? = null
    var bridgeService: BridgeService? = null

    var activity: ComponentActivity?
        get() = _activity?.get()
        set(value) { _activity?.clear(); _activity = WeakReference(value) }

    val sharedPreferences: SharedPreferences get() = androidContext.getSharedPreferences("prefs", 0)
    private var targetAppOverride: TargetApp? = null
    val activeTargetApp: TargetApp
        get() {
            targetAppOverride?.let { return it }
            val savedTarget = TargetApp.fromKeyOrNull(sharedPreferences.getString(TargetApp.PREF_KEY, null))
            val setupTargets = SetupPreferences.completedTargetApps(sharedPreferences) +
                SetupPreferences.selectedTargetApps(sharedPreferences)
            val inferredInstalledTarget = inferInstalledTargetApp()
            if (savedTarget != null && (savedTarget in setupTargets || inferredInstalledTarget == null || savedTarget == inferredInstalledTarget)) {
                return savedTarget
            }
            if (setupTargets.isNotEmpty()) {
                return SetupPreferences.preferredTargetApp(sharedPreferences)
            }
            return inferredInstalledTarget ?: savedTarget ?: TargetApp.SNAPCHAT
        }
    val isRedditMode: Boolean
        get() = activeTargetApp == TargetApp.REDDIT
    val isWhatsAppMode: Boolean
        get() = activeTargetApp == TargetApp.WHATSAPP
    val isInstagramMode: Boolean
        get() = activeTargetApp == TargetApp.INSTAGRAM
    val isLimitedTargetMode: Boolean
        get() = activeTargetApp != TargetApp.SNAPCHAT
    val fileHandleManager = RemoteFileHandleManager(this)
    val database = AppDatabase(this)
    val trackerDataManager = me.eternal.purrfect.storage.TrackerDataManagerImpl(database)
    val config = ModConfig(androidContext, constantLazyBridge { fileHandleManager })
    val translation = LocaleWrapper(androidContext, constantLazyBridge { fileHandleManager })
    val mappings = MappingsWrapper(constantLazyBridge { fileHandleManager })
    val taskManager = TaskManager(this)
    val taskInterface = RemoteTaskInterface(this)
    val streaksReminder = StreaksReminder(this)
    val log = LogManager(this)
    val scriptManager = RemoteScriptManager(this)
    val remoteOverlay = RemoteOverlay(this)
    val e2eeImplementation = E2EEImplementation(this)
    val messageLogger by lazy { LoggerWrapper(androidContext) }
    val tracker = RemoteTracker(this)
    val accountStorage = RemoteAccountStorage(this)
    val locationManager = RemoteLocationManager(this)

    init {
        val prefs = androidContext.getSharedPreferences("prefs", 0)
        if (!prefs.contains("debug_test_mode")) {
            prefs.edit().putBoolean("debug_test_mode", true).apply()
        }
    }

    //used to load bitmoji selfies and download previews
    val imageLoader by lazy {
        ImageLoader.Builder(androidContext)
            .dispatcher(Dispatchers.IO)
            .memoryCache {
                MemoryCache.Builder(androidContext)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(androidContext.cacheDir.resolve("coil-disk-cache"))
                    .maxSizeBytes(1024 * 1024 * 100) // 100MB
                    .build()
            }
            .components { add(VideoFrameDecoder.Factory()) }.build()
    }

    val gson: Gson by lazy { GsonBuilder().setPrettyPrinting().create() }

    fun reload() {
        runCatching {
            runBlocking(Dispatchers.IO) {
                log.init()
                log.verbose("Loading RemoteSideContext")
                config.load()
                config.root.reddit.migrateLegacyFlags()
                mirrorRedditFeaturePrefs()
                mirrorWhatsAppFeaturePrefs()
                mirrorInstagramFeaturePrefs()
                ensureAutoUpdateCheckOnUpgrade()
                launch {
                    mappings.apply {
                        init(androidContext)
                    }
                }
                translation.apply {
                    userLocale = config.locale
                    load()
                }
                scheduleAnnouncementCheck()
                database.init()
                streaksReminder.init()
                scriptManager.init()
                launch {
                    taskManager.init()
                    config.root.messaging.messageLogger.takeIf {
                        it.globalState == true
                    }?.autoPurge?.let { getPurgeTime(it.getNullable()) }?.let {
                        messageLogger.purgeAll(it)
                    }

                    config.root.friendTracker.takeIf {
                        it.globalState == true
                    }?.autoPurge?.let { getPurgeTime(it.getNullable()) }?.let {
                        messageLogger.purgeTrackerLogs(it)
                    }
                }
            }
        }.onFailure {
            log.error("Failed to load RemoteSideContext", it)
            androidContext.fatalCrash(it)
        }

        scriptManager.runtime.eachModule {
            callFunction("module.onPurrfectLoad", androidContext)
        }
    }

    val installationSummary by lazy {
        InstallationSummary(
            snapchatInfo = mappings.getSnapchatPackageInfo()?.let {
                val packageName = requireNotNull(it.packageName) { "Package name cannot be null" }
                SnapchatAppInfo(
                    packageName = packageName,
                    version = it.versionName ?: "unknown",
                    versionCode = it.longVersionCode,
                    isLSPatched = it.applicationInfo?.appComponentFactory != CoreComponentFactory::class.java.name,
                    isSplitApk = it.splitNames?.isNotEmpty() ?: false
                )
            },
            modInfo = ModInfo(
                loaderPackageName = MainActivity::class.java.`package`?.name,
                buildPackageName = androidContext.packageName,
                buildVersion = BuildConfig.VERSION_NAME,
                buildVersionCode = BuildConfig.VERSION_CODE.toLong(),
                buildIssuer = androidContext.packageManager.getPackageInfo(androidContext.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                    ?.signingInfo?.apkContentsSigners?.firstOrNull()?.let {
                        val certFactory = CertificateFactory.getInstance("X509")
                        val cert = certFactory.generateCertificate(ByteArrayInputStream(it.toByteArray())) as X509Certificate
                        cert.issuerDN.toString()
                    } ?: throw Exception("Failed to get certificate info"),
                gitHash = BuildConfig.GIT_HASH,
                isDebugBuild = BuildConfig.DEBUG,
                mappingVersion = mappings.getGeneratedBuildNumber(),
                mappingsOutdated = mappings.isMappingsOutdated()
            ),
            platformInfo = PlatformInfo(
                device = Build.DEVICE,
                androidVersion = Build.VERSION.RELEASE,
                systemAbi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"
            )
        )
    }

    fun longToast(message: Any) {
        androidContext.mainExecutor.execute {
            Toast.makeText(androidContext, message.toString(), Toast.LENGTH_LONG).show()
        }
        log.debug(message.toString())
    }

    fun shortToast(message: Any) {
        androidContext.mainExecutor.execute {
            Toast.makeText(androidContext, message.toString(), Toast.LENGTH_SHORT).show()
        }
        log.debug(message.toString())
    }

    fun hasMessagingBridge() = bridgeService != null && bridgeService?.messagingBridge != null && bridgeService?.messagingBridge?.asBinder()?.pingBinder() == true

    fun checkForRequirements(overrideRequirements: Int? = null): Boolean {
        var requirements = overrideRequirements ?: 0
        if (sharedPreferences.getBoolean("setup_in_progress", false)) {
            requirements = requirements or Requirements.FIRST_RUN
        }
        if (!config.wasPresent) {
            requirements = requirements or Requirements.FIRST_RUN
        }

        val shouldCheckSnapchatSetup = activeTargetApp == TargetApp.SNAPCHAT ||
                SetupPreferences.hasCompletedTarget(sharedPreferences, TargetApp.SNAPCHAT)
        if (shouldCheckSnapchatSetup) {
            config.root.downloader.saveFolder.get().let {
                val allowDefaultSaveFolder = sharedPreferences.getBoolean("downloader_use_default_save_folder", false)
                if (it.isEmpty()) {
                    if (!allowDefaultSaveFolder) {
                        requirements = requirements or Requirements.SAVE_FOLDER
                    }
                } else if (run {
                        val documentFile = runCatching { DocumentFile.fromTreeUri(androidContext, Uri.parse(it)) }.getOrNull()
                        documentFile == null || !documentFile.exists() || !documentFile.canWrite()
                    }) {
                    requirements = requirements or Requirements.SAVE_FOLDER
                }
            }

            if (!sharedPreferences.getBoolean("debug_disable_mapper", false) && mappings.getSnapchatPackageInfo() != null && mappings.isMappingsOutdated()) {
                requirements = requirements or Requirements.MAPPINGS
            }
        }

        if (requirements == 0) return false

        val currentContext = activity ?: androidContext

        Intent(currentContext, SetupActivity::class.java).apply {
            putExtra("requirements", requirements)
            if (currentContext !is Activity) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            currentContext.startActivity(this)
            return true
        }
    }

    fun launchActionIntent(action: EnumAction) {
        val intent = androidContext.packageManager.getLaunchIntentForPackage(
            Constants.SNAPCHAT_PACKAGE_NAME
        )
        if (intent == null) {
            shortToast(translation["toast_snapchat_not_installed"])
            return
        }
        intent.putExtra(EnumAction.ACTION_PARAMETER, action.key)
        androidContext.startActivity(intent)
    }

    fun openTargetPackage(packageName: String, appLabel: String) {
        val intent = androidContext.packageManager
            .getLaunchIntentForPackage(packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (intent == null) {
            shortToast("$appLabel is not installed")
            return
        }
        androidContext.startActivity(intent)
    }

    fun forceStopTargetPackage(packageName: String, appLabel: String) {
        val action = when (packageName) {
            Constants.REDDIT_PACKAGE_NAME -> Constants.REDDIT_FORCE_STOP_ACTION
            Constants.WHATSAPP_PACKAGE_NAME -> Constants.WHATSAPP_FORCE_STOP_ACTION
            in Constants.INSTAGRAM_PACKAGE_NAMES -> Constants.INSTAGRAM_FORCE_STOP_ACTION
            else -> null
        }
        if (action == null) {
            shortToast("Force stop is not available for $appLabel")
            return
        }
        runCatching {
            androidContext.sendBroadcast(
                Intent(action)
                    .setPackage(packageName)
            )
        }.onSuccess {
            shortToast("Close signal sent to $appLabel")
        }.onFailure {
            log.warn("Failed to send close signal to $appLabel: ${it.message}")
            shortToast("Failed to signal $appLabel")
        }
    }

    fun setActiveTargetApp(targetApp: TargetApp) {
        sharedPreferences.edit().putString(TargetApp.PREF_KEY, targetApp.key).commit()
    }

    fun packageNameForTargetApp(targetApp: TargetApp): String {
        return when (targetApp) {
            TargetApp.SNAPCHAT -> Constants.SNAPCHAT_PACKAGE_NAME
            TargetApp.REDDIT -> Constants.REDDIT_PACKAGE_NAME
            TargetApp.WHATSAPP -> Constants.WHATSAPP_PACKAGE_NAME
            TargetApp.INSTAGRAM -> installedInstagramPackageName()
        }
    }

    private fun installedInstagramPackageName(): String {
        return Constants.INSTAGRAM_PACKAGE_NAMES.firstOrNull { packageName ->
            runCatching {
                @Suppress("DEPRECATION")
                androidContext.packageManager.getPackageInfo(packageName, 0)
                true
            }.getOrDefault(false)
        } ?: Constants.INSTAGRAM_PACKAGE_NAME
    }

    private fun inferInstalledTargetApp(): TargetApp? {
        val installedTargets = TargetApp.entries.filter { targetApp ->
            runCatching {
                androidContext.packageManager.getPackageInfo(
                    packageNameForTargetApp(targetApp),
                    0
                )
            }.isSuccess
        }
        return installedTargets.singleOrNull()
    }

    fun setTargetAppOverride(targetApp: TargetApp?) {
        targetAppOverride = targetApp
    }

    fun mirrorRedditFeaturePrefs() {
        runCatching {
            val redditJson = getRedditFeaturesJson()
            File(androidContext.filesDir, REDDIT_FEATURE_CONFIG_FILE).apply {
                parentFile?.mkdirs()
                writeText(redditJson, Charsets.UTF_8)
            }
            redditFeatureExternalFiles().forEach { file ->
                runCatching {
                    file.parentFile?.mkdirs()
                    file.writeText(redditJson, Charsets.UTF_8)
                    file.parentFile?.setReadable(true, false)
                    file.setReadable(true, false)
                }.onFailure {
                    log.warn("Failed to mirror Reddit feature config to ${file.absolutePath}: ${it.message}")
                }
            }

            androidContext.getSharedPreferences(REDDIT_FEATURE_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean("block_promoted_posts", config.root.reddit.blockPromotedPostsEnabled())
                .putBoolean("block_comment_ads", config.root.reddit.blockCommentAdsEnabled())
                .putBoolean("unlock_reddit_premium", config.root.reddit.unlockRedditPremiumEnabled())
                .putBoolean("open_links_in_external_browser", config.root.reddit.openLinksInExternalBrowserEnabled())
                .putBoolean("disable_screenshot_popup", config.root.reddit.disableScreenshotPopupEnabled())
                .putBoolean("hide_answers_button", false)
                .putBoolean("hide_chat_button", false)
                .putBoolean("hide_create_button", config.root.reddit.hideCreateButtonEnabled())
                .putBoolean("hide_discover_communities_button", false)
                .putBoolean("hide_games_button", false)
                .putBoolean("hide_recently_visited_shelf", config.root.reddit.hideRecentlyVisitedShelfEnabled())
                .putBoolean("hide_games_on_reddit_shelf", config.root.reddit.hideGamesOnRedditShelfEnabled())
                .putBoolean("hide_reddit_pro_shelf", config.root.reddit.hideRedditProShelfEnabled())
                .putBoolean("hide_about_shelf", config.root.reddit.hideAboutShelfEnabled())
                .putBoolean("hide_resources_shelf", config.root.reddit.hideResourcesShelfEnabled())
                .putBoolean("hide_recommended_communities", false)
                .putBoolean("hide_trending_today_shelf", config.root.reddit.hideTrendingTodayShelfEnabled())
                .putBoolean("remove_nsfw_warning_dialog", config.root.reddit.removeNsfwWarningDialogEnabled())
                .putBoolean("remove_notification_suggestion_dialog", config.root.reddit.removeNotificationSuggestionDialogEnabled())
                .putBoolean("sanitize_sharing_links", config.root.reddit.sanitizeSharingLinksEnabled())
                .putBoolean("add_scroll_to_top_button", config.root.reddit.addScrollToTopButtonEnabled())
                .putBoolean("color_coded_comment_threads", false)
                .putBoolean("restore_deleted_content", false)
                .commit()

            val prefsFile = File(androidContext.applicationInfo.dataDir, "shared_prefs/$REDDIT_FEATURE_PREFS.xml")
            File(androidContext.applicationInfo.dataDir).setExecutable(true, false)
            File(androidContext.applicationInfo.dataDir).setReadable(true, false)
            prefsFile.parentFile?.setExecutable(true, false)
            prefsFile.parentFile?.setReadable(true, false)
            prefsFile.setReadable(true, false)
            broadcastRedditFeaturePrefs(redditJson)
            log.verbose("Mirrored Reddit feature config JSON")
        }.onFailure {
            log.error("Failed to mirror Reddit feature prefs", it)
        }
    }

    private fun broadcastRedditFeaturePrefs(json: String) {
        runCatching {
            androidContext.sendBroadcast(
                Intent(Constants.REDDIT_CONFIG_UPDATE_ACTION)
                    .setPackage(Constants.REDDIT_PACKAGE_NAME)
                    .putExtra(Constants.REDDIT_CONFIG_JSON_EXTRA, json)
            )
        }.onFailure {
            log.warn("Failed to broadcast Reddit feature config: ${it.message}")
        }
    }

    fun mirrorWhatsAppFeaturePrefs() {
        runCatching {
            val whatsAppFeatures = getWhatsAppFeaturesMap()
            val whatsAppJson = Gson().toJson(whatsAppFeatures)
            File(androidContext.filesDir, WHATSAPP_FEATURE_CONFIG_FILE).apply {
                parentFile?.mkdirs()
                writeText(whatsAppJson, Charsets.UTF_8)
                setReadable(true, false)
            }
            whatsAppFeatureExternalFiles().forEach { file ->
                runCatching {
                    file.parentFile?.mkdirs()
                    file.writeText(whatsAppJson, Charsets.UTF_8)
                    file.parentFile?.setReadable(true, false)
                    file.setReadable(true, false)
                }.onFailure {
                    log.warn("Failed to mirror WhatsApp feature config to ${file.absolutePath}: ${it.message}")
                }
            }

            androidContext.getSharedPreferences(WHATSAPP_FEATURE_PREFS, Context.MODE_PRIVATE)
                .edit()
                .apply {
                    whatsAppFeatures.forEach { (key, value) ->
                        when (value) {
                            is Boolean -> putBoolean(key, value)
                            is String -> putString(key, value)
                        }
                    }
                }
                .commit()

            val prefsFile = File(androidContext.applicationInfo.dataDir, "shared_prefs/$WHATSAPP_FEATURE_PREFS.xml")
            val configFile = File(androidContext.filesDir, "config.json")
            File(androidContext.applicationInfo.dataDir).setExecutable(true, false)
            File(androidContext.applicationInfo.dataDir).setReadable(true, false)
            androidContext.filesDir.setExecutable(true, false)
            androidContext.filesDir.setReadable(true, false)
            configFile.takeIf { it.exists() }?.setReadable(true, false)
            prefsFile.parentFile?.setExecutable(true, false)
            prefsFile.parentFile?.setReadable(true, false)
            prefsFile.setReadable(true, false)
            broadcastWhatsAppFeaturePrefs(whatsAppJson)
            log.verbose("Mirrored WhatsApp feature config JSON")
        }.onFailure {
            log.error("Failed to mirror WhatsApp feature prefs", it)
        }
    }

    fun mirrorInstagramFeaturePrefs() {
        runCatching {
            val instagramFeatures = getInstagramFeaturesMap()
            val instagramJson = Gson().toJson(instagramFeatures)
            File(androidContext.filesDir, INSTAGRAM_FEATURE_CONFIG_FILE).apply {
                parentFile?.mkdirs()
                writeText(instagramJson, Charsets.UTF_8)
                setReadable(true, false)
            }
            instagramFeatureExternalFiles().forEach { file ->
                runCatching {
                    file.parentFile?.mkdirs()
                    file.writeText(instagramJson, Charsets.UTF_8)
                    file.parentFile?.setReadable(true, false)
                    file.setReadable(true, false)
                }.onFailure {
                    log.warn("Failed to mirror Instagram feature config to ${file.absolutePath}: ${it.message}")
                }
            }

            androidContext.getSharedPreferences(INSTAGRAM_FEATURE_PREFS, Context.MODE_PRIVATE)
                .edit()
                .apply {
                    instagramFeatures.forEach { (key, value) ->
                        when (value) {
                            is Boolean -> putBoolean(key, value)
                            is String -> putString(key, value)
                        }
                    }
                    putBoolean("keepUnsentMessagesInitialized", true)
                    putBoolean("quickToggleUnsendInitialized", true)
                }
                .commit()

            val prefsFile = File(androidContext.applicationInfo.dataDir, "shared_prefs/$INSTAGRAM_FEATURE_PREFS.xml")
            val configFile = File(androidContext.filesDir, "config.json")
            File(androidContext.applicationInfo.dataDir).setExecutable(true, false)
            File(androidContext.applicationInfo.dataDir).setReadable(true, false)
            androidContext.filesDir.setExecutable(true, false)
            androidContext.filesDir.setReadable(true, false)
            configFile.takeIf { it.exists() }?.setReadable(true, false)
            prefsFile.parentFile?.setExecutable(true, false)
            prefsFile.parentFile?.setReadable(true, false)
            prefsFile.setReadable(true, false)
            broadcastInstagramFeaturePrefs(instagramJson)
            log.verbose("Mirrored Instagram feature config JSON")
        }.onFailure {
            log.error("Failed to mirror Instagram feature prefs", it)
        }
    }

    private fun broadcastInstagramFeaturePrefs(json: String) {
        Constants.INSTAGRAM_PACKAGE_NAMES.forEach { packageName ->
            runCatching {
                androidContext.sendBroadcast(
                    Intent(Constants.INSTAGRAM_CONFIG_UPDATE_ACTION)
                        .setPackage(packageName)
                        .putExtra(Constants.INSTAGRAM_CONFIG_JSON_EXTRA, json)
                )
            }.onFailure {
                log.warn("Failed to broadcast Instagram feature config to $packageName: ${it.message}")
            }
        }
    }

    private fun broadcastWhatsAppFeaturePrefs(json: String) {
        runCatching {
            androidContext.sendBroadcast(
                Intent(Constants.WHATSAPP_CONFIG_UPDATE_ACTION)
                    .setPackage(Constants.WHATSAPP_PACKAGE_NAME)
                    .putExtra(Constants.WHATSAPP_CONFIG_JSON_EXTRA, json)
            )
        }.onFailure {
            log.warn("Failed to broadcast WhatsApp feature config: ${it.message}")
        }
    }

    fun getRedditFeaturesJson(): String {
        return Gson().toJson(
            mapOf(
                "block_promoted_posts" to config.root.reddit.blockPromotedPostsEnabled(),
                "block_comment_ads" to config.root.reddit.blockCommentAdsEnabled(),
                "unlock_reddit_premium" to config.root.reddit.unlockRedditPremiumEnabled(),
                "open_links_in_external_browser" to config.root.reddit.openLinksInExternalBrowserEnabled(),
                "disable_screenshot_popup" to config.root.reddit.disableScreenshotPopupEnabled(),
                "hide_answers_button" to false,
                "hide_chat_button" to false,
                "hide_create_button" to config.root.reddit.hideCreateButtonEnabled(),
                "hide_discover_communities_button" to false,
                "hide_games_button" to false,
                "hide_recently_visited_shelf" to config.root.reddit.hideRecentlyVisitedShelfEnabled(),
                "hide_games_on_reddit_shelf" to config.root.reddit.hideGamesOnRedditShelfEnabled(),
                "hide_reddit_pro_shelf" to config.root.reddit.hideRedditProShelfEnabled(),
                "hide_about_shelf" to config.root.reddit.hideAboutShelfEnabled(),
                "hide_resources_shelf" to config.root.reddit.hideResourcesShelfEnabled(),
                "hide_recommended_communities" to false,
                "hide_trending_today_shelf" to config.root.reddit.hideTrendingTodayShelfEnabled(),
                "remove_nsfw_warning_dialog" to config.root.reddit.removeNsfwWarningDialogEnabled(),
                "remove_notification_suggestion_dialog" to config.root.reddit.removeNotificationSuggestionDialogEnabled(),
                "sanitize_sharing_links" to config.root.reddit.sanitizeSharingLinksEnabled(),
                "add_scroll_to_top_button" to config.root.reddit.addScrollToTopButtonEnabled(),
                "color_coded_comment_threads" to false,
                "restore_deleted_content" to false
            )
        )
    }

    fun getWhatsAppFeaturesJson(): String {
        return Gson().toJson(getWhatsAppFeaturesMap())
    }

    fun getInstagramFeaturesJson(): String {
        return Gson().toJson(getInstagramFeaturesMap())
    }

    private fun getWhatsAppFeaturesMap(): Map<String, Any> {
        val whatsApp = config.root.whatsapp
        return mapOf(
            "hide_channels" to whatsApp.hideChannelsEnabled(),
            "hide_channel_recommendations" to whatsApp.hideChannelRecommendationsEnabled(),
            "hide_communities_tab" to whatsApp.hideCommunitiesTabEnabled(),
            "hide_typing_indicators" to whatsApp.hideTypingIndicatorsEnabled(),
            "hide_recording_audio" to whatsApp.hideRecordingAudioEnabled(),
            "hide_delivered" to whatsApp.hideDeliveredEnabled(),
            "hide_audio_seen" to whatsApp.hideAudioSeenEnabled(),
            "hide_status_view" to whatsApp.hideStatusViewEnabled(),
            "hide_start_chatting" to whatsApp.hideStartChattingEnabled(),
            "unlimited_view_once" to whatsApp.unlimitedViewOnceEnabled(),
            "hide_blue_ticks" to whatsApp.hideBlueTicksEnabled(),
            "show_deleted_messages" to whatsApp.showDeletedMessagesEnabled(),
            "hide_ui_elements" to whatsApp.hideUiElementsEnabled(),
            "capture_ui_elements" to whatsApp.captureUiElementsEnabled(),
            "liquid_class" to whatsApp.liquidClassEnabled(),
            "hidden_ui_element_ids" to whatsApp.hiddenUiElementIds(),
            "hidden_ui_element_selectors" to whatsApp.hiddenUiElementSelectors()
        )
    }

    private fun getInstagramFeaturesMap(): Map<String, Any> {
        return config.root.instagram.featureMap()
    }

    fun resetActiveTargetConfig() {
        val defaults = RootConfig().apply { lateInit(androidContext) }
        when (activeTargetApp) {
            TargetApp.REDDIT -> config.root.reddit.fromJson(defaults.reddit.toJson())
            TargetApp.WHATSAPP -> config.root.whatsapp.fromJson(defaults.whatsapp.toJson())
            TargetApp.INSTAGRAM -> config.root.instagram.fromJson(defaults.instagram.toJson())
            TargetApp.SNAPCHAT -> {
                val redditConfig = config.root.reddit.toJson()
                val whatsAppConfig = config.root.whatsapp.toJson()
                val instagramConfig = config.root.instagram.toJson()
                config.reset()
                config.root.reddit.fromJson(redditConfig)
                config.root.whatsapp.fromJson(whatsAppConfig)
                config.root.instagram.fromJson(instagramConfig)
            }
        }
        config.root.reddit.migrateLegacyFlags()
        config.writeConfig()
        mirrorRedditFeaturePrefs()
        mirrorWhatsAppFeaturePrefs()
        mirrorInstagramFeaturePrefs()
    }

    private fun redditFeatureExternalFiles(): List<File> {
        return listOf(
            File("/storage/emulated/0/Android/media/${BuildConfig.APPLICATION_ID}/$REDDIT_FEATURE_CONFIG_FILE"),
            File("/sdcard/Android/media/${BuildConfig.APPLICATION_ID}/$REDDIT_FEATURE_CONFIG_FILE")
        ).distinctBy { it.absolutePath }
    }



    private fun whatsAppFeatureExternalFiles(): List<File> {
        return listOf(
            File("/storage/emulated/0/Android/media/${BuildConfig.APPLICATION_ID}/$WHATSAPP_FEATURE_CONFIG_FILE"),
            File("/sdcard/Android/media/${BuildConfig.APPLICATION_ID}/$WHATSAPP_FEATURE_CONFIG_FILE")
        ).distinctBy { it.absolutePath }
    }

    private fun instagramFeatureExternalFiles(): List<File> {
        return listOf(
            File("/storage/emulated/0/Android/media/${BuildConfig.APPLICATION_ID}/$INSTAGRAM_FEATURE_CONFIG_FILE"),
            File("/sdcard/Android/media/${BuildConfig.APPLICATION_ID}/$INSTAGRAM_FEATURE_CONFIG_FILE")
        ).distinctBy { it.absolutePath }
    }

    fun requestSocialSnapshotRefresh(
        openSnapchatFirst: Boolean = true,
        snapchatWarmupDelayMs: Long = 1200L,
        returnDelayMs: Long = 1200L
    ) {
        fun sendSocialSnapshotBroadcast() {
            runCatching {
                androidContext.sendBroadcast(
                    SnapWidgetBroadcastReceiverHelper.create(ReceiversConfig.BRIDGE_SYNC_ACTION) {}
                )
            }.onFailure {
                log.error("Failed to request latest social snapshot", it)
            }
        }

        if (!openSnapchatFirst) {
            sendSocialSnapshotBroadcast()
            return
        }

        val snapchatIntent = androidContext.packageManager
            .getLaunchIntentForPackage(Constants.SNAPCHAT_PACKAGE_NAME)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

        if (snapchatIntent == null) {
            shortToast(translation["toast_snapchat_not_installed"])
            sendSocialSnapshotBroadcast()
            return
        }

        val returnIntent = Intent(androidContext, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            )
        }

        val mainHandler = Handler(Looper.getMainLooper())
        runCatching {
            androidContext.startActivity(snapchatIntent)
            mainHandler.postDelayed(
                {
                    runCatching {
                        androidContext.startActivity(returnIntent)
                    }.onFailure {
                        log.error("Failed to return to Purrfect after Snapchat handoff", it)
                    }
                    mainHandler.postDelayed(
                        { sendSocialSnapshotBroadcast() },
                        returnDelayMs
                    )
                },
                snapchatWarmupDelayMs
            )
        }.onFailure {
            log.error("Failed to launch Snapchat for social snapshot refresh", it)
            sendSocialSnapshotBroadcast()
        }
    }

    private fun scheduleAnnouncementCheck() {
        val workManager = WorkManager.getInstance(androidContext)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val inputData = Data.Builder()
            .putString("announcements_url", "https://www.purrfectgit.com/r/particle-box/purrfect/file?branch=dev&path=announcements.txt")
            .putString("announcements_fallback_url", "https://www.purrfectgit.com/r/particle-box/purrfect/file?branch=dev&path=announcements.txt")
            .putString("channel_name", "Announcements")
            .putString("channel_description", "Notifications for Purrfect announcements")
            .putString("notification_title", "New announcement available")
            .putString("notification_text", "Tap to open and read.")
            .build()
        val workRequest = PeriodicWorkRequestBuilder<AnnouncementCheckWorker>(1, TimeUnit.DAYS)
            .setConstraints(constraints)
            .setInputData(inputData)
            .build()
        workManager.enqueueUniquePeriodicWork(
            "purrfect_announcement_check",
            ExistingPeriodicWorkPolicy.REPLACE,
            workRequest
        )
    }

    private fun ensureAutoUpdateCheckOnUpgrade() {
        val currentVersion = BuildConfig.VERSION_CODE.toLong()
        val lastVersion = sharedPreferences.getLong("last_build_version_code", -1L)
        val reenabledOnce = sharedPreferences.getBoolean("auto_update_reenabled_once", false)
        if (lastVersion == currentVersion) return
        if (!reenabledOnce) {
            config.root.global.updateSettings.autoUpdateCheck.set(true)
            config.writeConfig()
            sharedPreferences.edit()
                .putBoolean("auto_update_reenabled_once", true)
                .apply()
        }
        sharedPreferences.edit().putLong("last_build_version_code", currentVersion).apply()
    }

    fun syncSkinSettings() {
        runCatching {
            val uiSettings = config.root.global.uiSettings
            sharedPreferences.edit()
                .putString("manager_theme", uiSettings.managerTheme.get())
                .putString("aphelion_skin", uiSettings.aphelionSkin.get())
                .putString("lumina_mode", uiSettings.luminaMode.get())
                .putString("lumina_accent", uiSettings.luminaAccent.get())
                .putString("aether_mode", uiSettings.aetherMode.get())
                .putString("aether_accent", uiSettings.aetherAccent.get())
                .putBoolean("aether_amoled", uiSettings.aetherAmoled.get())
                .putString("cyberware_style", uiSettings.cyberwareStyle.get())
                .apply()
        }
    }

    companion object {
        const val REDDIT_FEATURE_PREFS = "reddit_features"
        const val REDDIT_FEATURE_CONFIG_FILE = "reddit_features.json"
        const val WHATSAPP_FEATURE_PREFS = "whatsapp_features"
        const val WHATSAPP_FEATURE_CONFIG_FILE = "whatsapp_features.json"
        const val INSTAGRAM_FEATURE_PREFS = "instagram_features"
        const val INSTAGRAM_FEATURE_CONFIG_FILE = "instagram_features.json"
    }
}
