package me.eternal.purrfect.core.reddit

import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.IBinder
import android.os.SystemClock
import de.robv.android.xposed.XposedBridge
import me.eternal.purrfect.bridge.BridgeInterface
import me.eternal.purrfect.common.BuildConfig
import me.eternal.purrfect.common.Constants
import me.eternal.purrfect.core.logger.CoreLogger
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class RedditFeatureState(
    val blockPromotedPosts: Boolean = false,
    val blockCommentAds: Boolean = false,
    val unlockRedditPremium: Boolean = false,
    val openLinksInExternalBrowser: Boolean = false,
    val disableScreenshotPopup: Boolean = false,
    val hideAnswersButton: Boolean = false,
    val hideChatButton: Boolean = false,
    val hideCreateButton: Boolean = false,
    val hideDiscoverCommunitiesButton: Boolean = false,
    val hideGamesButton: Boolean = false,
    val hideRecentlyVisitedShelf: Boolean = false,
    val hideGamesOnRedditShelf: Boolean = false,
    val hideRedditProShelf: Boolean = false,
    val hideAboutShelf: Boolean = false,
    val hideResourcesShelf: Boolean = false,
    val hideRecommendedCommunities: Boolean = false,
    val hideTrendingTodayShelf: Boolean = false,
    val removeNsfwWarningDialog: Boolean = false,
    val removeNotificationSuggestionDialog: Boolean = false,
    val sanitizeSharingLinks: Boolean = false,
    val addScrollToTopButton: Boolean = false,
    val colorCodedCommentThreads: Boolean = false,
    val restoreDeletedContent: Boolean = false,
    val loadedFromXposedPrefs: Boolean = false,
    val source: String = "unavailable"
) {
    companion object {
        private const val TAG = RedditAdBlockHooks.TAG
        private const val PREFS_NAME = "reddit_features"
        private const val KEY_BLOCK_PROMOTED_POSTS = "block_promoted_posts"
        private const val KEY_BLOCK_COMMENT_ADS = "block_comment_ads"
        private const val KEY_UNLOCK_REDDIT_PREMIUM = "unlock_reddit_premium"
        private const val KEY_OPEN_LINKS_EXTERNAL = "open_links_in_external_browser"
        private const val KEY_DISABLE_SCREENSHOT_POPUP = "disable_screenshot_popup"
        private const val KEY_HIDE_ANSWERS_BUTTON = "hide_answers_button"
        private const val KEY_HIDE_CHAT_BUTTON = "hide_chat_button"
        private const val KEY_HIDE_CREATE_BUTTON = "hide_create_button"
        private const val KEY_HIDE_DISCOVER_COMMUNITIES_BUTTON = "hide_discover_communities_button"
        private const val KEY_HIDE_GAMES_BUTTON = "hide_games_button"
        private const val KEY_HIDE_RECENTLY_VISITED_SHELF = "hide_recently_visited_shelf"
        private const val KEY_HIDE_GAMES_ON_REDDIT_SHELF = "hide_games_on_reddit_shelf"
        private const val KEY_HIDE_REDDIT_PRO_SHELF = "hide_reddit_pro_shelf"
        private const val KEY_HIDE_ABOUT_SHELF = "hide_about_shelf"
        private const val KEY_HIDE_RESOURCES_SHELF = "hide_resources_shelf"
        private const val KEY_HIDE_RECOMMENDED_COMMUNITIES = "hide_recommended_communities"
        private const val KEY_HIDE_TRENDING_TODAY_SHELF = "hide_trending_today_shelf"
        private const val KEY_REMOVE_NSFW_WARNING_DIALOG = "remove_nsfw_warning_dialog"
        private const val KEY_REMOVE_NOTIFICATION_SUGGESTION_DIALOG = "remove_notification_suggestion_dialog"
        private const val KEY_SANITIZE_SHARING_LINKS = "sanitize_sharing_links"
        private const val KEY_ADD_SCROLL_TO_TOP_BUTTON = "add_scroll_to_top_button"
        private const val KEY_COLOR_CODED_COMMENT_THREADS = "color_coded_comment_threads"
        private const val KEY_RESTORE_DELETED_CONTENT = "restore_deleted_content"
        private const val PROVIDER_AUTHORITY = "me.eternal.purrfect.reddit.config"
        private const val PROVIDER_METHOD_GET_FEATURES = "getRedditFeatures"
        private const val REDDIT_FEATURES_FILE = "files/reddit_features.json"
        private const val REDDIT_EXTERNAL_FEATURES_FILE = "reddit_features.json"
        private val unavailableLogged = AtomicBoolean(false)

        fun load(androidContext: Context): RedditFeatureState {
            RedditFeatureStateStore.current.takeIf { it.source != "unavailable" }?.let { return it }
            return loadFromExternalJson() ?: loadFromRootJson() ?: loadFromBridge(androidContext) ?: loadFromProvider(androidContext) ?: loadFromXposedPrefs() ?: loadFromWorldReadablePrefs() ?: RedditFeatureState().also {
                if (unavailableLogged.compareAndSet(false, true)) {
                    log("Reddit feature state unavailable; waiting for broadcast config")
                }
            }
        }

        fun loadAsync(androidContext: Context) {
            Thread {
                repeat(8) { attempt ->
                    if (RedditFeatureStateStore.current.source != "unavailable") return@Thread
                    val state = load(androidContext)
                    if (state.source != "unavailable") {
                        RedditFeatureStateStore.update(state)
                        return@Thread
                    }
                    if (RedditFeatureStateStore.current.source == "unavailable") {
                        RedditFeatureStateStore.update(state)
                    }
                    SystemClock.sleep(1_500L + attempt * 500L)
                }
            }.apply {
                name = "PurrfectRedditConfig"
                isDaemon = true
                start()
            }
        }

        private fun loadFromProvider(androidContext: Context): RedditFeatureState? {
            return runCatching {
                val resolverContext = moduleContext(androidContext) ?: androidContext
                val result = resolverContext.contentResolver.call(
                    Uri.parse("content://$PROVIDER_AUTHORITY"),
                    PROVIDER_METHOD_GET_FEATURES,
                    null,
                    null
                )

                if (result == null) return null

                RedditFeatureState(
                    blockPromotedPosts = result.getBoolean(KEY_BLOCK_PROMOTED_POSTS, false),
                    blockCommentAds = result.getBoolean(KEY_BLOCK_COMMENT_ADS, false),
                    unlockRedditPremium = result.getBoolean(KEY_UNLOCK_REDDIT_PREMIUM, false),
                    openLinksInExternalBrowser = result.getBoolean(KEY_OPEN_LINKS_EXTERNAL, false),
                    restoreDeletedContent = result.getBoolean(KEY_RESTORE_DELETED_CONTENT, false),
                    source = "provider:${result.getString("source", "unknown")}"
                ).also { state ->
                    logLoadedState(state)
                }
            }.onFailure { throwable ->
                if (throwable !is IllegalArgumentException) {
                    log("Reddit config provider unavailable: ${throwable.message}")
                }
            }.getOrNull()
        }

        private fun loadFromBridge(androidContext: Context): RedditFeatureState? {
            val bridgeContext = moduleContext(androidContext) ?: androidContext
            var service: BridgeInterface? = null
            val latch = CountDownLatch(1)
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                    service = BridgeInterface.Stub.asInterface(binder)
                    latch.countDown()
                }

                override fun onServiceDisconnected(name: ComponentName) {
                    service = null
                }
            }

            return runCatching {
                runCatching {
                    bridgeContext.startActivity(
                        Intent()
                            .setClassName(Constants.MODULE_PACKAGE_NAME, "me.eternal.purrfect.bridge.ForceStartActivity")
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                    )
                }.onFailure { throwable ->
                    if (BuildConfig.DEBUG) {
                        log("Failed to nudge Purrfect bridge process for Reddit config: ${throwable.message}")
                    }
                }

                val intent = Intent()
                    .setClassName(Constants.MODULE_PACKAGE_NAME, "me.eternal.purrfect.bridge.BridgeService")
                    .setPackage(Constants.MODULE_PACKAGE_NAME)
                val bound = bridgeContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
                if (!bound) {
                    return null
                }

                try {
                    if (!latch.await(900L, TimeUnit.MILLISECONDS)) return null

                    val json = service?.redditFeaturesJson ?: run {
                        return null
                    }
                fromJson(json, "bridge").also {
                        logLoadedState(it)
                    }
                } finally {
                    runCatching { bridgeContext.unbindService(connection) }
                }
            }.onFailure { throwable ->
                if (BuildConfig.DEBUG) {
                    log("Failed to load Reddit feature state from bridge: ${throwable.message}")
                }
                runCatching { bridgeContext.unbindService(connection) }
            }.getOrNull()
        }

        private fun loadFromRootJson(): RedditFeatureState? {
            val candidates = listOf(
                "/data/user/0/${BuildConfig.APPLICATION_ID}/$REDDIT_FEATURES_FILE",
                "/data/data/${BuildConfig.APPLICATION_ID}/$REDDIT_FEATURES_FILE"
            ).distinct()

            candidates.forEach { path ->
                val state = runCatching {
                    val process = ProcessBuilder("su", "-c", "cat '$path'")
                        .redirectErrorStream(true)
                        .start()
                    val finished = process.waitFor(700L, TimeUnit.MILLISECONDS)
                    if (!finished) {
                        process.destroyForcibly()
                        return@runCatching null
                    }
                    val output = process.inputStream.bufferedReader().use { it.readText().trim() }
                    if (process.exitValue() != 0 || output.isBlank() || !output.startsWith("{")) {
                        return@runCatching null
                    }
                    fromJson(output, "root-json:$path").also {
                        logLoadedState(it)
                    }
                }.getOrNull()

                if (state != null) return state
            }

            return null
        }

        private fun loadFromExternalJson(): RedditFeatureState? {
            val candidates = listOf(
                File("/storage/emulated/0/Android/media/${BuildConfig.APPLICATION_ID}/$REDDIT_EXTERNAL_FEATURES_FILE"),
                File("/sdcard/Android/media/${BuildConfig.APPLICATION_ID}/$REDDIT_EXTERNAL_FEATURES_FILE")
            ).distinctBy { it.absolutePath }

            candidates.forEach { file ->
                val state = runCatching {
                    if (!file.exists()) return@runCatching null
                    val json = file.readText(Charsets.UTF_8).trim()
                    if (!json.startsWith("{")) {
                        return@runCatching null
                    }
                    fromJson(json, "external-json:${file.absolutePath}").also {
                        logLoadedState(it)
                    }
                }.getOrNull()

                if (state != null) return state
            }

            return null
        }

        private fun moduleContext(androidContext: Context): Context? {
            return runCatching {
                androidContext.createPackageContext(
                    Constants.MODULE_PACKAGE_NAME,
                    Context.CONTEXT_IGNORE_SECURITY
                )
            }.getOrNull()
        }

        private fun loadFromXposedPrefs(): RedditFeatureState? {
            return runCatching {
                val prefsClass = Class.forName("de.robv.android.xposed.XSharedPreferences")
                val prefs = prefsClass
                    .getConstructor(String::class.java, String::class.java)
                    .newInstance(BuildConfig.APPLICATION_ID, "reddit_features")

                runCatching { prefsClass.getMethod("makeWorldReadable").invoke(prefs) }
                runCatching { prefsClass.getMethod("reload").invoke(prefs) }

                val fileMethod = prefsClass.methods.firstOrNull { it.name == "getFile" && it.parameterTypes.isEmpty() }
                val file = fileMethod?.invoke(prefs)
                val exists = file?.javaClass?.getMethod("exists")?.invoke(file) as? Boolean
                if (exists == false) return null

                val getBoolean = prefsClass.getMethod("getBoolean", String::class.java, Boolean::class.javaPrimitiveType)
                RedditFeatureState(
                    blockPromotedPosts = getBoolean.invoke(prefs, KEY_BLOCK_PROMOTED_POSTS, false) as Boolean,
                    blockCommentAds = getBoolean.invoke(prefs, KEY_BLOCK_COMMENT_ADS, false) as Boolean,
                    unlockRedditPremium = getBoolean.invoke(prefs, KEY_UNLOCK_REDDIT_PREMIUM, false) as Boolean,
                    openLinksInExternalBrowser = getBoolean.invoke(prefs, KEY_OPEN_LINKS_EXTERNAL, false) as Boolean,
                    restoreDeletedContent = getBoolean.invoke(prefs, KEY_RESTORE_DELETED_CONTENT, false) as Boolean,
                    loadedFromXposedPrefs = true,
                    source = "XSharedPreferences"
                ).also { state ->
                    logLoadedState(state)
                }
            }.getOrNull()
        }

        private fun loadFromWorldReadablePrefs(): RedditFeatureState? {
            val candidates = listOf(
                File("/data/user/0/${BuildConfig.APPLICATION_ID}/shared_prefs/$PREFS_NAME.xml"),
                File("/data/data/${BuildConfig.APPLICATION_ID}/shared_prefs/$PREFS_NAME.xml")
            ).distinctBy { it.absolutePath }

            candidates.forEach { file ->
                val state = runCatching {
                    if (!file.exists()) return@runCatching null
                    val values = parseBooleanPrefs(file)
                    RedditFeatureState(
                        blockPromotedPosts = values[KEY_BLOCK_PROMOTED_POSTS] ?: false,
                        blockCommentAds = values[KEY_BLOCK_COMMENT_ADS] ?: false,
                        unlockRedditPremium = values[KEY_UNLOCK_REDDIT_PREMIUM] ?: false,
                        openLinksInExternalBrowser = values[KEY_OPEN_LINKS_EXTERNAL] ?: false,
                        restoreDeletedContent = values[KEY_RESTORE_DELETED_CONTENT] ?: false,
                        source = "world-readable:${file.absolutePath}"
                    ).also { logLoadedState(it) }
                }.getOrNull()

                if (state != null) return state
            }

            return null
        }

        private fun parseBooleanPrefs(file: File): Map<String, Boolean> {
            val result = mutableMapOf<String, Boolean>()
            file.inputStream().use { input ->
                val parser = XmlPullParserFactory.newInstance().newPullParser()
                parser.setInput(input, "utf-8")

                var event = parser.eventType
                while (event != XmlPullParser.END_DOCUMENT) {
                    if (event == XmlPullParser.START_TAG && parser.name == "boolean") {
                        val name = parser.getAttributeValue(null, "name")
                        val value = parser.getAttributeValue(null, "value")
                        if (name != null && value != null) {
                            result[name] = value.toBooleanStrictOrNull() ?: false
                        }
                    }
                    event = parser.next()
                }
            }
            return result
        }

        private fun logLoadedState(state: RedditFeatureState) {
            log(
                "Reddit feature state loaded from ${state.source}: " +
                    "blockPromotedPosts=${state.blockPromotedPosts}, blockCommentAds=${state.blockCommentAds}, " +
                    "unlockRedditPremium=${state.unlockRedditPremium}, " +
                    "openLinksInExternalBrowser=${state.openLinksInExternalBrowser}, " +
                    "disableScreenshotPopup=${state.disableScreenshotPopup}, " +
                    "hideCreateButton=${state.hideCreateButton}, " +
                    "hideTrendingTodayShelf=${state.hideTrendingTodayShelf}, " +
                    "addScrollToTopButton=${state.addScrollToTopButton}, " +
                    "layoutHooks=${state.hasAnyLayoutHook()}, dialogHooks=${state.hasAnyDialogHook()}, " +
                    "sanitizeSharingLinks=${state.sanitizeSharingLinks}"
            )
        }

        fun fromJson(json: String, source: String): RedditFeatureState {
            val obj = JSONObject(json)
            return RedditFeatureState(
                blockPromotedPosts = obj.optBoolean(KEY_BLOCK_PROMOTED_POSTS, false),
                blockCommentAds = obj.optBoolean(KEY_BLOCK_COMMENT_ADS, false),
                unlockRedditPremium = obj.optBoolean(KEY_UNLOCK_REDDIT_PREMIUM, false),
                openLinksInExternalBrowser = obj.optBoolean(KEY_OPEN_LINKS_EXTERNAL, false),
                disableScreenshotPopup = obj.optBoolean(KEY_DISABLE_SCREENSHOT_POPUP, false),
                hideAnswersButton = obj.optBoolean(KEY_HIDE_ANSWERS_BUTTON, false),
                hideChatButton = obj.optBoolean(KEY_HIDE_CHAT_BUTTON, false),
                hideCreateButton = obj.optBoolean(KEY_HIDE_CREATE_BUTTON, false),
                hideDiscoverCommunitiesButton = obj.optBoolean(KEY_HIDE_DISCOVER_COMMUNITIES_BUTTON, false),
                hideGamesButton = obj.optBoolean(KEY_HIDE_GAMES_BUTTON, false),
                hideRecentlyVisitedShelf = obj.optBoolean(KEY_HIDE_RECENTLY_VISITED_SHELF, false),
                hideGamesOnRedditShelf = obj.optBoolean(KEY_HIDE_GAMES_ON_REDDIT_SHELF, false),
                hideRedditProShelf = obj.optBoolean(KEY_HIDE_REDDIT_PRO_SHELF, false),
                hideAboutShelf = obj.optBoolean(KEY_HIDE_ABOUT_SHELF, false),
                hideResourcesShelf = obj.optBoolean(KEY_HIDE_RESOURCES_SHELF, false),
                hideRecommendedCommunities = obj.optBoolean(KEY_HIDE_RECOMMENDED_COMMUNITIES, false),
                hideTrendingTodayShelf = obj.optBoolean(KEY_HIDE_TRENDING_TODAY_SHELF, false),
                removeNsfwWarningDialog = obj.optBoolean(KEY_REMOVE_NSFW_WARNING_DIALOG, false),
                removeNotificationSuggestionDialog = obj.optBoolean(KEY_REMOVE_NOTIFICATION_SUGGESTION_DIALOG, false),
                sanitizeSharingLinks = obj.optBoolean(KEY_SANITIZE_SHARING_LINKS, false),
                addScrollToTopButton = obj.optBoolean(KEY_ADD_SCROLL_TO_TOP_BUTTON, false),
                colorCodedCommentThreads = obj.optBoolean(KEY_COLOR_CODED_COMMENT_THREADS, false),
                restoreDeletedContent = obj.optBoolean(KEY_RESTORE_DELETED_CONTENT, false),
                source = source
            )
        }

        private fun log(message: String) {
            XposedBridge.log("[$TAG] $message")
            CoreLogger.xposedLog(message, TAG)
            RedditAppLogWriter.info(null, TAG, message)
        }

    }
}

fun RedditFeatureState.hasAnyLayoutHook(): Boolean {
    return hideCreateButton || hideDiscoverCommunitiesButton ||
        hideRecentlyVisitedShelf || hideGamesOnRedditShelf || hideRedditProShelf ||
        hideAboutShelf || hideResourcesShelf ||
        hideTrendingTodayShelf || addScrollToTopButton
}

fun RedditFeatureState.hasAnyDrawerHook(): Boolean {
    return hideRecentlyVisitedShelf || hideGamesOnRedditShelf || hideRedditProShelf ||
        hideAboutShelf || hideResourcesShelf
}

fun RedditFeatureState.hasAnyDialogHook(): Boolean {
    return disableScreenshotPopup || removeNsfwWarningDialog || removeNotificationSuggestionDialog
}

object RedditFeatureStateStore {
    @Volatile
    var current: RedditFeatureState = RedditFeatureState()
        private set

    fun update(state: RedditFeatureState) {
        current = state
    }
}
