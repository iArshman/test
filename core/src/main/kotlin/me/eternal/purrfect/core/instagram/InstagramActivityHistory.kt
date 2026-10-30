package me.eternal.purrfect.core.instagram

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.util.LruCache
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import android.view.Window
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.MediaController
import android.widget.CompoundButton
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import android.webkit.CookieManager
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedHashMap
import java.util.Collections
import java.util.HashSet
import java.util.IdentityHashMap
import java.util.Locale
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.regex.Pattern
import kotlin.math.roundToInt

internal object InstagramActivityHistoryHooks {
    private val installed = AtomicBoolean(false)
    private val mediaInfoPath = Pattern.compile(".*/media/([^/]+)/info/.*")
    private val userInfoPath = Pattern.compile(".*/users/([^/]+)/info(?:_stream)?/.*")
    private val recentRecords = ConcurrentHashMap<String, Long>()
    private val visibleViews = Collections.synchronizedMap(WeakHashMap<View, Long>())
    private val profileImageUrls = Collections.synchronizedMap(WeakHashMap<View, String>())
    private val recentProfileThumbnails = ConcurrentHashMap<String, String>()
    private val recentProfileImageSamples = mutableListOf<TimedThumbnail>()
    private val recentMediaObjects = Collections.synchronizedMap(WeakHashMap<Any, Long>())
    private val resourceNameCache = Collections.synchronizedMap(WeakHashMap<View, String>())
    @Volatile private var recentProfileHeaderThumbnail: TimedThumbnail? = null
    private val mountedNativePages = Collections.synchronizedMap(WeakHashMap<Activity, Boolean>())
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newCachedThreadPool()
    private val recordExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "PurrfectInstaHistory").apply { isDaemon = true }
    }

    @Volatile private var appContext: Context? = null
    @Volatile private var suppressHistoryOpenUntilMs = 0L
    @Volatile private var mediaClass: Class<*>? = null
    @Volatile private var clipsUfiHooked = false
    @Volatile private var clipsSeenHooked = false
    @Volatile private var storySeenHooked = false
    @Volatile private var storyViewerHooked = false
    @Volatile private var idsResolved = false
    @Volatile private var feedLikeId = 0
    @Volatile private var feedSaveId = 0
    @Volatile private var reelLikeId = 0
    @Volatile private var clipsUfiLikeId = 0
    @Volatile private var clipsUfiId = 0
    @Volatile private var profileHeaderId = 0
    @Volatile private var rowProfileHeaderId = 0
    @Volatile private var profileHeaderUsernameId = 0
    @Volatile private var actionBarTitleId = 0
    @Volatile private var lastReelVisualRecordAt = 0L
    @Volatile private var lastReelVisualMediaId = ""
    @Volatile private var nativeRouteReady = false
    @Volatile private var callbackFailureLogs = 0
    @Volatile private var storyVideoVersionIntfClass: Class<*>? = null
    @Volatile private var storyVideoVersionGetUrl: Method? = null
    @Volatile private var scrollBusyUntilMs = 0L

    private const val TAG_REEL_MEDIA = 1521086594
    private const val CACHE_STORY_SEEN_MISSING = "ActivityHistory_story_seen_missing_v1"

    private data class TimedThumbnail(val url: String, val timeMs: Long)

    fun install(context: Context, classLoader: ClassLoader) {
        appContext = context.applicationContext ?: context
        ensureIds(context)
        runCatching { mediaClass = Class.forName("com.instagram.feed.media.Media", false, classLoader) }
        initStoryVideoVersionReflection(classLoader)
        if (!installed.compareAndSet(false, true)) return
        hookUriParse()
        hookVisibleMediaViews()
        hookModalLifecycle(classLoader)
        log("Installed activity history hooks")
    }

    fun installDexKitHooks(bridge: InstagramDexKitBridge, classLoader: ClassLoader) {
        runCatching { if (mediaClass == null) mediaClass = Class.forName("com.instagram.feed.media.Media", false, classLoader) }
        initStoryVideoVersionReflection(classLoader)
        hookClipsUfiComposer(bridge, classLoader)
        hookClipsSeenState(bridge, classLoader)
        hookStorySeenMedia(bridge, classLoader)
        hookStoryViewerMethods(classLoader)
    }

    fun suppressOpenLogging() {
        suppressHistoryOpenUntilMs = System.currentTimeMillis() + 8_000L
    }

    fun noteScrollBusyUntil(untilMs: Long) {
        if (untilMs > scrollBusyUntilMs) scrollBusyUntilMs = untilMs
    }

    private fun initStoryVideoVersionReflection(classLoader: ClassLoader) {
        if (storyVideoVersionIntfClass != null && storyVideoVersionGetUrl != null) return
        runCatching {
            val cls = Class.forName("com.instagram.model.mediasize.VideoVersionIntf", false, classLoader)
            storyVideoVersionIntfClass = cls
            storyVideoVersionGetUrl = cls.getMethod("getUrl")
        }
    }

    fun recordNetworkRequest(context: Context, uri: URI) {
        if (!InstagramFeatureStateStore.current.enableActivityHistory || isHistoryOpenLoggingSuppressed()) return
        if (!isNetworkHistoryCandidate(uri)) return
        val app = context.applicationContext ?: context
        appContext = app
        safeCallback("network request") {
            val path = uri.path.orEmpty()

            mediaInfoPath.matcher(path).takeIf { it.matches() }?.let { matcher ->
                val mediaId = matcher.group(1)
                if (!mediaId.isNullOrBlank()) {
                    record(app, InstagramActivityHistoryStore.TYPE_POST, "", "Post opened", "", mediaId, instagramPostUrl(mediaId), "", "network:media_info")
                }
                return
            }

            if (path.contains("/clips/item/") || path.contains("/clips/items/")) {
                val query = uri.query.orEmpty()
                val mediaId = firstQueryValue(query, "clips_media_id", "media_id", "clips_media_ids")
                if (!mediaId.isNullOrBlank()) {
                    record(app, InstagramActivityHistoryStore.TYPE_REEL, "", "Reel opened", "", mediaId, instagramReelUrl(mediaId), "", "network:clips_item")
                }
                return
            }

            if (path.contains("/clips/write_seen_state")) {
                val query = uri.query.orEmpty()
                val mediaId = firstQueryValue(query, "media_id", "clips_media_id", "reel_media_id", "clip_media_id")
                if (!mediaId.isNullOrBlank()) {
                    record(app, InstagramActivityHistoryStore.TYPE_REEL, "", "Reel watched", "", mediaId, instagramReelUrl(mediaId), "", "network:clips_seen")
                }
                return
            }

            if (path.contains("/media/seen/") ||
                path.contains("/users/web_profile_info/") ||
                userInfoPath.matcher(path).matches() ||
                path.contains("/feed/user/")
            ) {
                return
            }

            if (shouldRecordNetworkInstagramUri(uri, path)) {
                val full = uri.toString()
                recordInstagramUri(app, Uri.parse(full), "network:url")
            }
        }
    }

    fun recordVisibleView(view: View) {
        if (!InstagramFeatureStateStore.current.enableActivityHistory || !view.isAttachedToWindow) return
        if (SystemClock.uptimeMillis() <= scrollBusyUntilMs) return
        val context = view.context ?: return
        appContext = context.applicationContext ?: context
        ensureIds(context)
        val id = view.id
        val hasId = id != View.NO_ID && id != 0
        val matchesKnownId = hasId && (
            id == feedLikeId ||
                id == feedSaveId ||
                id == reelLikeId ||
                id == clipsUfiLikeId ||
                id == profileHeaderId ||
                id == rowProfileHeaderId
            )
        var idName = ""
        val needsFallbackName = hasId && (!matchesKnownId || clipsUfiLikeId == 0 || profileHeaderId == 0 || rowProfileHeaderId == 0)
        val matchesFallbackName = needsFallbackName && cachedResourceName(view).also { idName = it }.let { name ->
            name == "profile_header_container" ||
                name == "row_profile_header" ||
                (clipsUfiLikeId == 0 && name.contains("clips_ufi_like", ignoreCase = true))
        }
        val hasPotentialUrl = !matchesKnownId && !matchesFallbackName && hasPotentialInstagramUrl(view)
        if (!matchesKnownId && !matchesFallbackName && !hasPotentialUrl) return
        safeCallback("visible view") {
            val isFeedAnchor = (feedLikeId != 0 && id == feedLikeId) || (feedSaveId != 0 && id == feedSaveId)
            val isReelAnchor = (reelLikeId != 0 && id == reelLikeId) ||
                (clipsUfiLikeId != 0 && id == clipsUfiLikeId) ||
                (clipsUfiLikeId == 0 && idName.contains("clips_ufi_like", ignoreCase = true))
            val isProfileHeader = (profileHeaderId != 0 && id == profileHeaderId) ||
                (rowProfileHeaderId != 0 && id == rowProfileHeaderId) ||
                (idName == "profile_header_container") ||
                idName == "row_profile_header"
            val shouldExtractPotentialUrl = hasPotentialUrl && !isFeedAnchor && !isReelAnchor && !isProfileHeader

            if (!isFeedAnchor && !isReelAnchor && !isProfileHeader && !shouldExtractPotentialUrl) return

            val now = System.currentTimeMillis()
            val last = visibleViews[view] ?: 0L
            if (now - last < 2_000L) return
            visibleViews[view] = now

            if (isProfileHeader) tryRecordProfilePage(view)

            if (isFeedAnchor || isReelAnchor) {
                val tagged = if (isReelAnchor) runCatching { view.getTag(TAG_REEL_MEDIA) }.getOrNull() else null
                val taggedMedia = tagged?.takeIf { mediaClass?.isInstance(it) == true }
                if (taggedMedia != null) {
                    scheduleMediaRecord(context, taggedMedia, isReelAnchor, view, 0L)
                } else {
                    scheduleNearbyMediaRecord(context, view, isReelAnchor)
                }
            }
            if (shouldExtractPotentialUrl) {
                extractInstagramUrls(view).forEach { url ->
                    recordInstagramUri(context, Uri.parse(url), "view:url")
                }
            }
        }
    }

    private fun scheduleNearbyMediaRecord(context: Context, view: View, forceReel: Boolean) {
        val app = context.applicationContext ?: context
        mainHandler.postDelayed({
            if (!InstagramFeatureStateStore.current.enableActivityHistory || !view.isAttachedToWindow) return@postDelayed
            val media = findNearbyMedia(view) ?: return@postDelayed
            scheduleMediaRecord(app, media, forceReel, view, 0L)
        }, 700L)
    }

    private fun scheduleMediaRecord(context: Context, media: Any, forceReel: Boolean, anchor: View?, delayMs: Long) {
        val app = context.applicationContext ?: context
        val task = Runnable {
            if (!InstagramFeatureStateStore.current.enableActivityHistory) return@Runnable
            if (!markMediaObjectForRecord(media)) return@Runnable
            executor.execute {
                recordMediaObject(app, media, forceReel, null)
            }
        }
        if (delayMs > 0L) mainHandler.postDelayed(task, delayMs) else task.run()
    }

    private fun markMediaObjectForRecord(media: Any): Boolean {
        val now = System.currentTimeMillis()
        synchronized(recentMediaObjects) {
            val previous = recentMediaObjects[media] ?: 0L
            if (now - previous < 12_000L) return false
            recentMediaObjects[media] = now
            return true
        }
    }

    private fun hookUriParse() {
        runCatching {
            XposedBridge.hookAllMethods(
                Uri::class.java,
                "parse",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam<*>) {
                        safeCallback("Uri.parse") {
                            if (!InstagramFeatureStateStore.current.enableActivityHistory || isHistoryOpenLoggingSuppressed()) return
                            val raw = param.args.firstOrNull() as? String ?: return
                            if (!raw.contains("instagram.com") && !raw.startsWith("instagram://")) return
                            val uri = param.result as? Uri ?: return
                            val context = appContext ?: return
                            recordInstagramUri(context, uri, "uri_parse")
                        }
                    }
                }
            )
        }.onFailure { log("Uri.parse history hook failed: ${it.message}") }
    }

    private fun hookVisibleMediaViews() {
        runCatching {
            XposedBridge.hookAllMethods(
                View::class.java,
                "onAttachedToWindow",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam<*>) {
                        safeCallback("View.onAttachedToWindow") {
                            val view = param.thisObject as? View ?: return
                            recordVisibleView(view)
                        }
                    }
                }
            )
        }.onFailure { log("visible media hook failed: ${it.message}") }
    }

    private fun hookClipsUfiComposer(bridge: InstagramDexKitBridge, classLoader: ClassLoader) {
        if (clipsUfiHooked || mediaClass == null) return
        clipsUfiHooked = true
        val hook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam<*>) {
                safeCallback("clips UFI") {
                    if (!InstagramFeatureStateStore.current.enableActivityHistory) return
                    val context = appContext ?: return
                    val media = findFieldOfType(param.thisObject, mediaClass, 5) ?: firstMediaArg(param.args)
                    if (media != null) scheduleMediaRecord(context, media, true, null, 0L)
                }
            }
        }
        if (InstagramDexKitCache.isCacheValid()) {
            InstagramDexKitCache.loadMethod("ActivityHistory_clips_ufi", classLoader)?.let { cached ->
                XposedBridge.hookMethod(cached, hook)
                log("hooked clips UFI cached: ${cached.declaringClass.name}.${cached.name}")
                return
            }
        }
        val methods = bridge.findMethodsUsingStrings("clips_ufi_like_button_component")
        var hooked = 0
        methods.forEach { method ->
            if (Modifier.isStatic(method.modifiers)) return@forEach
            val signature = "${method.declaringClass.name}.${method.name}:clips_ufi"
            runCatching {
                method.isAccessible = true
                XposedBridge.hookMethod(method, hook)
                if (hooked == 0) InstagramDexKitCache.saveMethod("ActivityHistory_clips_ufi", method)
                hooked++
                log("hooked clips UFI: $signature")
            }.onFailure { log("clips UFI hook failed for $signature: ${it.message}") }
        }
        if (hooked == 0) log("clips UFI method not found")
    }

    private fun hookClipsSeenState(bridge: InstagramDexKitBridge, classLoader: ClassLoader) {
        if (clipsSeenHooked) return
        clipsSeenHooked = true
        val hook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam<*>) {
                safeCallback("clips seen") {
                    if (!InstagramFeatureStateStore.current.enableActivityHistory) return
                    val context = appContext ?: return
                    val mediaIds = extractStringSet(param.args.firstOrNull())
                    if (mediaIds.size != 1) return
                    mediaIds.forEach { mediaId ->
                        if (looksLikeMediaId(mediaId)) {
                            record(
                                context,
                                InstagramActivityHistoryStore.TYPE_REEL,
                                "",
                                "Reel watched",
                                "",
                                mediaId,
                                instagramReelUrl(mediaId),
                                "",
                                "dex:clips_seen_state"
                            )
                        }
                    }
                }
            }
        }
        if (InstagramDexKitCache.isCacheValid()) {
            InstagramDexKitCache.loadMethod("ActivityHistory_clips_seen", classLoader)?.let { cached ->
                XposedBridge.hookMethod(cached, hook)
                log("hooked clips seen cached: ${cached.declaringClass.name}.${cached.name}")
                return
            }
        }
        val methods = bridge.findMethodsUsingStrings("clips/write_seen_state/")
        var hooked = 0
        methods.forEach { method ->
            if (Modifier.isStatic(method.modifiers) || method.parameterTypes.size != 1) return@forEach
            val signature = "${method.declaringClass.name}.${method.name}:clips_seen"
            runCatching {
                method.isAccessible = true
                XposedBridge.hookMethod(method, hook)
                if (hooked == 0) InstagramDexKitCache.saveMethod("ActivityHistory_clips_seen", method)
                hooked++
                log("hooked clips seen: $signature")
            }.onFailure { log("clips seen hook failed for $signature: ${it.message}") }
        }
        if (hooked == 0) log("clips seen method not found")
    }

    private fun hookStorySeenMedia(bridge: InstagramDexKitBridge, classLoader: ClassLoader) {
        if (storySeenHooked) return
        storySeenHooked = true
        val hook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam<*>) {
                safeCallback("story seen") {
                    if (!InstagramFeatureStateStore.current.enableActivityHistory) return
                    val context = appContext ?: return
                    val info = findStoryMediaInfo(param.thisObject, param.args)
                    cacheStoryMedia(context, info, "dex:story_seen")
                }
            }
        }
        if (InstagramDexKitCache.isCacheValid()) {
            InstagramDexKitCache.loadMethod("ActivityHistory_story_seen", classLoader)?.let { cached ->
                XposedBridge.hookMethod(cached, hook)
                log("hooked story seen cached: ${cached.declaringClass.name}.${cached.name}")
                return
            }
            if (InstagramDexKitCache.loadString(CACHE_STORY_SEEN_MISSING) == "1") {
                log("story seen method not found cached")
                return
            }
        }
        val methods = bridge.findMethodsUsingStrings("media/seen/")
        var hooked = 0
        methods.forEach { method ->
            if (Modifier.isStatic(method.modifiers) || method.returnType != Void.TYPE || method.parameterTypes.isNotEmpty()) return@forEach
            val signature = "${method.declaringClass.name}.${method.name}:story_seen"
            runCatching {
                method.isAccessible = true
                XposedBridge.hookMethod(method, hook)
                if (hooked == 0) InstagramDexKitCache.saveMethod("ActivityHistory_story_seen", method)
                hooked++
                log("hooked story seen: $signature")
            }.onFailure { log("story seen hook failed for $signature: ${it.message}") }
        }
        if (hooked == 0) {
            InstagramDexKitCache.saveString(CACHE_STORY_SEEN_MISSING, "1")
            log("story seen method not found")
        }
    }

    fun rememberInstagramImageUrl(view: View, url: String) {
        if (!InstagramFeatureStateStore.current.enableActivityHistory || !looksLikeProfileImageUrl(url)) return
        profileImageUrls[view] = url
        rememberRecentProfileImageSample(url)
        if (isLikelyProfileHeaderAvatar(view)) {
            recentProfileHeaderThumbnail = TimedThumbnail(url, System.currentTimeMillis())
        }
    }

    fun cachedProfileThumbnail(username: String): String? {
        return recentProfileThumbnails[username.trim('@').lowercase(Locale.US)]?.takeIf { it.isNotBlank() }
    }

    private fun hookStoryViewerMethods(classLoader: ClassLoader) {
        if (storyViewerHooked) return
        storyViewerHooked = true
        runCatching {
            val fragmentClass = Class.forName("instagram.features.stories.fragment.ReelViewerFragment", false, classLoader)
            val reelItemClass = Class.forName("com.instagram.model.reels.ReelItem", false, classLoader)
            var hooked = 0
            fragmentClass.declaredMethods.forEach { method ->
                if (method.returnType != Void.TYPE || method.parameterTypes.none { it == reelItemClass }) return@forEach
                method.isAccessible = true
                XposedBridge.hookMethod(
                    method,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam<*>) {
                            safeCallback("story viewer") {
                                if (!InstagramFeatureStateStore.current.enableActivityHistory) return
                                val context = appContext ?: return
                                param.args.orEmpty().firstOrNull { reelItemClass.isInstance(it) }?.let { reelItem ->
                                    cacheStoryMedia(context, extractStoryMediaInfoForHistory(reelItem), "view:story_reel_item")
                                }
                            }
                        }
                    }
                )
                hooked++
            }
            log("hooked ReelViewerFragment story methods=$hooked")
        }.onFailure { log("story viewer hook failed: ${it.message}") }
    }

    private fun hookModalLifecycle(classLoader: ClassLoader) {
        runCatching {
            val modalClass = Class.forName("com.instagram.modal.ModalActivity", false, classLoader)
            modalClass.declaredMethods
                .filter { it.name == "A2H" && it.parameterTypes.size == 1 && it.parameterTypes[0] == android.os.Bundle::class.java }
                .forEach { method ->
                    method.isAccessible = true
                    XposedBridge.hookMethod(
                        method,
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam<*>) {
                                safeCallback("native route initializer") {
                                    val activity = param.thisObject as? Activity ?: return
                                    if (!isHistoryRoute(activity)) return
                                    param.result = null
                                    scheduleNativeHistoryMount(activity, "initializer")
                                }
                            }
                        }
                    )
                }
            XposedBridge.hookAllMethods(
                Activity::class.java,
                "onResume",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam<*>) {
                        safeCallback("native route resume") {
                            val activity = param.thisObject as? Activity ?: return
                            if (!isHistoryRoute(activity)) return
                            scheduleNativeHistoryMount(activity, "onResume")
                        }
                    }
                }
            )
            nativeRouteReady = true
        }.onFailure {
            nativeRouteReady = false
            log("Native history modal hook skipped: ${it.message}")
        }
    }

    fun launchNativePage(context: Context): Boolean {
        if (!nativeRouteReady) return false
        return runCatching {
            val intent = Intent().setClassName(context.packageName, "com.instagram.modal.ModalActivity")
                .putExtra("fragment_name", "instaeclipse_activity_history")
                .putExtra("fragment_arguments", Bundle())
            if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        }.getOrElse {
            false
        }
    }

    private fun scheduleNativeHistoryMount(activity: Activity, source: String) {
        if (activity.isFinishing) return
        mainHandler.post { mountNativeHistoryPage(activity, source) }
        activity.window?.decorView?.let { decor ->
            decor.post { mountNativeHistoryPage(activity, "$source:decor") }
            decor.postDelayed({ mountNativeHistoryPage(activity, "$source:retry") }, 120L)
        }
    }

    private fun isHistoryRoute(activity: Activity): Boolean {
        if (activity.javaClass.name != "com.instagram.modal.ModalActivity") return false
        val route = activity.intent?.getStringExtra("fragment_name")
        return route == "instaeclipse_activity_history" || route == "purrfect_insta_activity_history"
    }

    private fun mountNativeHistoryPage(activity: Activity, source: String) {
        safeCallback("mount native page") {
            if (activity.isFinishing || mountedNativePages[activity] == true) return
            val container = findNativeHistoryContainer(activity)
            if (container == null) {
                log("Native history mount pending: container missing via $source")
                return
            }
            container.removeAllViews()
            container.addView(
                InstagramActivityHistoryDialog.createContent(activity, onClose = { activity.finish() }),
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )
            mountedNativePages[activity] = true
            log("Mounted native history page into ${container.javaClass.name} via $source")
        }
    }

    private fun findNativeHistoryContainer(activity: Activity): ViewGroup? {
        runCatching {
            val containerId = activity.javaClass.getMethod("Bq4").invoke(activity) as? Int
            if (containerId != null) (activity.findViewById<View>(containerId) as? ViewGroup)?.let { return it }
        }
        listOf("layout_container_main", "layout_container", "layout_container_parent").forEach { idName ->
            val id = activity.resources.getIdentifier(idName, "id", activity.packageName)
            if (id != 0) (activity.findViewById<View>(id) as? ViewGroup)?.let { return it }
        }
        return null
    }

    private fun recordMediaObject(context: Context, media: Any, forceReel: Boolean, anchor: View?) {
        val mediaId = extractMediaId(media)
        val username = extractUsernameFromMediaObject(media).ifBlank {
            scanObjectForUsername(media, 0, Collections.newSetFromMap(IdentityHashMap()))
        }.orEmpty()
        val urls = extractAllUrlsFromMedia(media)
        val thumbnail = bestThumbnailUrl(urls)
        val caption = betterCaption(extractCaption(media), extractVisibleCaption(anchor, username))
        val type = if (forceReel) InstagramActivityHistoryStore.TYPE_REEL else InstagramActivityHistoryStore.TYPE_POST
        val title = caption.ifBlank {
            username.takeIf { it.isNotBlank() }?.let { "@$it" }
                ?: if (type == InstagramActivityHistoryStore.TYPE_REEL) "Reel watched" else "Post watched"
        }
        val url = if (type == InstagramActivityHistoryStore.TYPE_REEL) instagramReelUrl(mediaId) else instagramPostUrl(mediaId)
        record(context, type, username, title, caption, mediaId, url, thumbnail, if (forceReel) "view:reel" else "view:feed")
    }

    private fun extractAllUrlsFromMedia(media: Any?): List<String> {
        if (media == null) return emptyList()
        val urls = mutableListOf<String>()
        scanForCdnUrls(media, urls, 0, Collections.newSetFromMap(IdentityHashMap()))
        return urls.distinct()
    }

    private fun scanForCdnUrls(obj: Any?, out: MutableList<String>, depth: Int, visited: MutableSet<Any>) {
        if (obj == null || depth > 7 || out.size >= 60 || !visited.add(obj)) return
        when (obj) {
            is String -> {
                if (isCdnMediaUrl(obj) && !out.contains(obj)) out += obj
                return
            }
            is Uri -> {
                val value = obj.toString()
                if (isCdnMediaUrl(value) && !out.contains(value)) out += value
                return
            }
        }
        val className = obj.javaClass.name
        if (className.startsWith("android.") || className.startsWith("java.lang.") || className.startsWith("kotlin.")) return
        var cls: Class<*>? = obj.javaClass
        while (cls != null && cls != Any::class.java) {
            cls.declaredFields.forEach { field ->
                runCatching {
                    if (Modifier.isStatic(field.modifiers)) return@forEach
                    field.isAccessible = true
                    val value = field.get(obj) ?: return@forEach
                    when (value) {
                        is String -> if (isCdnMediaUrl(value) && !out.contains(value)) out += value
                        is Uri -> value.toString().takeIf { isCdnMediaUrl(it) && !out.contains(it) }?.let { out += it }
                        is Iterable<*> -> value.forEach { scanForCdnUrls(it, out, depth + 1, visited) }
                        is Array<*> -> value.forEach { scanForCdnUrls(it, out, depth + 1, visited) }
                        else -> if (shouldDescend(value.javaClass)) scanForCdnUrls(value, out, depth + 1, visited)
                    }
                }
            }
            cls = cls.superclass
        }
        if (depth <= 3 && shouldDescend(obj.javaClass)) {
            obj.javaClass.declaredMethods
                .filter { it.parameterTypes.isEmpty() && it.returnType == String::class.java }
                .take(40)
                .forEach { method ->
                    runCatching {
                        method.isAccessible = true
                        val value = method.invoke(obj) as? String
                        if (value != null && isCdnMediaUrl(value) && !out.contains(value)) out += value
                    }
                }
        }
    }

    private fun bestThumbnailUrl(urls: List<String>): String {
        if (urls.isEmpty()) return ""
        return urls.firstOrNull { !isVideoUrl(it) }.orEmpty()
    }

    private fun extractMediaId(media: Any?): String {
        if (media == null) return ""
        runCatching {
            val id = media.javaClass.methods.firstOrNull { it.name == "getId" && it.parameterTypes.isEmpty() }?.invoke(media)
            if (id is String && id.isNotBlank()) return id.substringBefore("_")
        }
        return scanForMediaId(media, 0, Collections.newSetFromMap(IdentityHashMap())).orEmpty()
    }

    private fun scanForMediaId(obj: Any?, depth: Int, visited: MutableSet<Any>): String? {
        if (obj == null || depth > 4 || !visited.add(obj)) return null
        if (obj is String && looksLikeMediaId(obj)) return obj.substringBefore("_")
        if (!shouldDescend(obj.javaClass)) return null
        var cls: Class<*>? = obj.javaClass
        while (cls != null && cls != Any::class.java) {
            cls.declaredFields.forEach { field ->
                runCatching {
                    if (Modifier.isStatic(field.modifiers)) return@forEach
                    field.isAccessible = true
                    val value = field.get(obj) ?: return@forEach
                    if (value is String && looksLikeMediaId(value)) return value.substringBefore("_")
                    if (shouldDescend(value.javaClass)) {
                        scanForMediaId(value, depth + 1, visited)?.let { return it }
                    }
                }
            }
            cls = cls.superclass
        }
        return null
    }

    private fun extractUsernameFromMediaObject(media: Any?): String {
        if (media == null) return ""
        runCatching {
            val direct = media.javaClass.methods.firstOrNull { it.name == "getUsername" && it.parameterTypes.isEmpty() }?.invoke(media)
            if (direct is String && isProfileSlug(direct)) return direct
        }
        return ""
    }

    private fun scanObjectForUsername(obj: Any?, depth: Int, visited: MutableSet<Any>): String? {
        if (obj == null || depth > 5 || !visited.add(obj)) return null
        if (obj is String) return obj.takeIf { isProfileSlug(it) }
        runCatching {
            val result = obj.javaClass.methods.firstOrNull { it.name == "getUsername" && it.parameterTypes.isEmpty() }?.invoke(obj)
            if (result is String && isProfileSlug(result)) return result
        }
        if (depth <= 2) {
            obj.javaClass.declaredMethods
                .filter { method ->
                    method.parameterTypes.isEmpty() &&
                        method.returnType == String::class.java &&
                        method.name.lowercase(Locale.US).contains("username")
                }
                .take(12)
                .forEach { method ->
                    runCatching {
                        method.isAccessible = true
                        val value = method.invoke(obj) as? String
                        if (value != null && isProfileSlug(value)) return value
                    }
                }
        }
        if (depth >= 5 || !shouldDescend(obj.javaClass)) return null
        var cls: Class<*>? = obj.javaClass
        while (cls != null && cls != Any::class.java) {
            cls.declaredFields.forEach { field ->
                runCatching {
                    if (Modifier.isStatic(field.modifiers) || field.type.isPrimitive || field.type.isArray) return@forEach
                    field.isAccessible = true
                    val value = field.get(obj) ?: return@forEach
                    if (value is String) {
                        val name = field.name.lowercase(Locale.US)
                        if (name.contains("username") && isProfileSlug(value)) return value
                        return@forEach
                    }
                    scanObjectForUsername(value, depth + 1, visited)?.let { return it }
                }
            }
            cls = cls.superclass
        }
        return null
    }

    private fun extractCaption(media: Any?): String {
        return cleanHistoryCaption(scanForCaption(media, 0, Collections.newSetFromMap(IdentityHashMap())).orEmpty())
    }

    private fun scanForCaption(obj: Any?, depth: Int, visited: MutableSet<Any>): String? {
        if (obj == null || depth > 4 || !visited.add(obj)) return null
        var best: String? = null
        if (obj is String && isCaptionLike(obj)) best = betterCaption(best, obj)
        if (!shouldDescend(obj.javaClass)) return best
        var cls: Class<*>? = obj.javaClass
        while (cls != null && cls != Any::class.java) {
            cls.declaredFields.forEach { field ->
                runCatching {
                    if (Modifier.isStatic(field.modifiers)) return@forEach
                    field.isAccessible = true
                    val value = field.get(obj) ?: return@forEach
                    if (value is String && isCaptionLike(value)) best = betterCaption(best, value)
                    if (depth < 4 && shouldDescend(value.javaClass)) {
                        best = betterCaption(best, scanForCaption(value, depth + 1, visited))
                    }
                }
            }
            cls = cls.superclass
        }
        return best
    }

    private fun extractVisibleCaption(anchor: View?, username: String): String {
        if (anchor == null) return ""
        var best = ""
        var parent: ViewParent? = anchor.parent
        var depth = 0
        while (depth++ < 7 && parent is View) {
            best = betterCaption(best, scanVisibleText(parent as View, username, 0, Collections.newSetFromMap(IdentityHashMap())))
            parent = (parent as View).parent
        }
        return best
    }

    private fun scanVisibleText(view: View?, username: String, depth: Int, visited: MutableSet<Any>): String {
        if (view == null || depth > 5 || !visited.add(view)) return ""
        var best = ""
        if (view is TextView) {
            val text = normalizeVisibleCaption(view.text?.toString().orEmpty(), username)
            if (isCaptionLike(text) && !isLikelyUiNoise(text)) best = betterCaption(best, text)
        }
        if (view is ViewGroup) {
            val count = view.childCount.coerceAtMost(80)
            for (i in 0 until count) {
                best = betterCaption(best, scanVisibleText(view.getChildAt(i), username, depth + 1, visited))
            }
        }
        return best
    }

    private fun normalizeVisibleCaption(value: String, username: String): String {
        val text = cleanHistoryCaption(value)
        if (text.isBlank() || username.isBlank()) return text
        return text.replaceFirst(Regex("^@?${Regex.escape(username.trim())}\\s+", RegexOption.IGNORE_CASE), "").trim()
    }

    private fun isLikelyUiNoise(value: String): Boolean {
        val lower = value.trim().lowercase(Locale.US)
        if (lower.isBlank()) return true
        if (lower.startsWith("liked by ")) return true
        if (lower.matches(Regex("\\d+[kKmM]?\\s+(likes|comments|shares|views)"))) return true
        if (lower == "view all comments" || (lower.startsWith("view all ") && lower.endsWith(" comments"))) return true
        return lower == "follow" || lower == "following" || lower == "message"
    }

    private fun betterCaption(current: String?, candidate: String?): String {
        if (!isCaptionLike(candidate)) return current?.let(::cleanHistoryCaption).orEmpty()
        if (current.isNullOrBlank()) return cleanHistoryCaption(candidate.orEmpty())
        val cleanCurrent = cleanHistoryCaption(current)
        val cleanCandidate = cleanHistoryCaption(candidate.orEmpty())
        val currentScore = captionScore(cleanCurrent)
        val candidateScore = captionScore(cleanCandidate)
        return if (candidateScore > currentScore || (candidateScore == currentScore && cleanCandidate.length > cleanCurrent.length)) {
            cleanCandidate
        } else {
            cleanCurrent
        }
    }

    private fun captionScore(value: String): Int {
        if (value.isBlank()) return 0
        val lower = value.lowercase(Locale.US)
        var score = value.length.coerceAtMost(900)
        if (lower.contains("http") || lower.contains("cdninstagram.com")) score -= 500
        if (lower.contains("liked by ") || lower.matches(Regex(".*\\b\\d+\\s+(likes|comments|shares)\\b.*"))) score -= 250
        if (value.contains(" ")) score += 100
        if (value.any { it == '.' || it == '?' || it == '!' }) score += 80
        return score
    }

    private fun cleanHistoryCaption(value: String): String {
        return value
            .replace("\\n", "\n")
            .replace(Regex("[\\u0000-\\u001F&&[^\\n\\t]]+"), " ")
            .replace(Regex("[ \\t]+"), " ")
            .trim()
    }

    private fun isCaptionLike(value: String?): Boolean {
        val s = value?.trim().orEmpty()
        if (s.length < 6 || s.length > 2200) return false
        val lower = s.lowercase(Locale.US)
        if (isBadCaptionText(lower)) return false
        if (lower.startsWith("http") || lower.contains("cdninstagram.com")) return false
        if (lower.contains("/api/v1/") || lower.contains("instagram.com")) return false
        if (s.matches(Regex("[0-9_\\-:.]+"))) return false
        return s.contains(" ") || s.length > 24
    }

    private fun isBadCaptionText(lower: String): Boolean {
        return lower.startsWith("<?xml") ||
            lower.startsWith("<!doctype") ||
            lower.startsWith("<html") ||
            lower.contains("<?xml version") ||
            lower.contains("encoding=\"utf-8\"") ||
            lower.contains("encoding='utf-8'") ||
            lower.contains("<head>") ||
            lower.contains("</html>")
    }

    private fun firstMediaArg(args: Array<Any?>?): Any? {
        val target = mediaClass ?: return null
        args.orEmpty().forEach { arg ->
            if (arg != null && target.isInstance(arg)) return arg
            findFieldOfType(arg, target, 3)?.let { return it }
        }
        return null
    }

    private fun extractStringSet(obj: Any?): Set<String> {
        val out = HashSet<String>()
        if (obj == null) return out
        var cls: Class<*>? = obj.javaClass
        while (cls != null && cls != Any::class.java) {
            cls.declaredFields.forEach { field ->
                runCatching {
                    if (Modifier.isStatic(field.modifiers)) return@forEach
                    field.isAccessible = true
                    when (val value = field.get(obj)) {
                        is Set<*> -> value.filterIsInstance<String>().filterTo(out) { looksLikeMediaId(it) }
                        is List<*> -> value.filterIsInstance<String>().filterTo(out) { looksLikeMediaId(it) }
                        is String -> if (looksLikeMediaId(value)) out += value
                    }
                }
            }
            cls = cls.superclass
        }
        return out
    }

    private fun findStoryMediaInfo(thisObject: Any?, args: Array<Any?>?): StoryMediaInfo? {
        extractStoryMediaInfoForHistory(thisObject)?.let { return it }
        args.orEmpty().forEach { arg -> extractStoryMediaInfoForHistory(arg)?.let { return it } }
        val carrier = findObjectWithTypeNameField(thisObject, "com.instagram.model.reels.ReelItem", 4, Collections.newSetFromMap(IdentityHashMap()))
        return extractStoryMediaInfoForHistory(carrier)
    }

    private fun findObjectWithTypeNameField(obj: Any?, typeName: String, depth: Int, visited: MutableSet<Any>): Any? {
        if (obj == null || depth < 0 || !visited.add(obj)) return null
        var cls: Class<*>? = obj.javaClass
        while (cls != null && cls != Any::class.java) {
            cls.declaredFields.forEach { field ->
                runCatching {
                    if (Modifier.isStatic(field.modifiers)) return@forEach
                    field.isAccessible = true
                    val value = field.get(obj) ?: return@forEach
                    if (field.type.name == typeName || value.javaClass.name == typeName) return obj
                    if (depth > 0 && shouldDescend(value.javaClass)) {
                        findObjectWithTypeNameField(value, typeName, depth - 1, visited)?.let { return it }
                    }
                }
            }
            cls = cls.superclass
        }
        return null
    }

    private data class StoryMediaInfo(
        val username: String,
        val mediaId: String,
        val url: String,
        val video: Boolean
    )

    private fun extractStoryMediaInfoForHistory(holder: Any?): StoryMediaInfo? {
        if (holder == null) return null
        val url = extractStoryUrl(holder) ?: return null
        val username = scanObjectForUsername(holder, 0, Collections.newSetFromMap(IdentityHashMap())).orEmpty()
        val mediaId = extractMediaId(holder).ifBlank { kotlin.math.abs(url.hashCode()).toString() }
        return StoryMediaInfo(username, mediaId, url, isVideoUrl(url))
    }

    private fun extractStoryUrl(holder: Any?): String? {
        if (holder == null) return null
        val reelItem = readFieldByTypeName(holder, "com.instagram.model.reels.ReelItem")
        val target = reelItem ?: holder
        findStoryVideoUrl(target, Collections.newSetFromMap(IdentityHashMap()), 0)?.let { return it }
        val candidates = mutableListOf<StoryImageCandidate>()
        collectStoryImageCandidates(target, candidates, Collections.newSetFromMap(IdentityHashMap()), 0)
        if (candidates.isNotEmpty()) return candidates.maxByOrNull { it.area }?.url
        val urls = extractAllUrlsFromMedia(target)
        return pickBestStoryUrl(urls)
    }

    private fun readFieldByTypeName(value: Any?, typeName: String): Any? {
        if (value == null) return null
        if (value.javaClass.name == typeName) return value
        var cls: Class<*>? = value.javaClass
        while (cls != null && cls != Any::class.java) {
            cls.declaredFields.forEach { field ->
                if (field.type.name == typeName) {
                    return runCatching {
                        field.isAccessible = true
                        field.get(value)
                    }.getOrNull()
                }
            }
            cls = cls.superclass
        }
        return null
    }

    private data class StoryImageCandidate(val url: String, val area: Int)

    private fun findStoryVideoUrl(value: Any?, visited: MutableSet<Any>, depth: Int): String? {
        if (value == null || depth > 5 || !visited.add(value)) return null
        val videoClass = storyVideoVersionIntfClass
        val getUrl = storyVideoVersionGetUrl
        if (videoClass == null || getUrl == null) return null
        if (videoClass.isInstance(value)) {
            val url = runCatching { getUrl.invoke(value) as? String }.getOrNull()
            if (url != null && isStoryCdnUrl(url) && isVideoUrl(url)) return url
        }
        if (!shouldDescend(value.javaClass)) return null
        var cls: Class<*>? = value.javaClass
        while (cls != null && cls != Any::class.java) {
            cls.declaredFields.forEach { field ->
                runCatching {
                    field.isAccessible = true
                    val nested = field.get(value) ?: return@forEach
                    if (nested is Iterable<*>) {
                        nested.forEach { item ->
                            if (item != null && videoClass.isInstance(item)) {
                                val url = runCatching { getUrl.invoke(item) as? String }.getOrNull()
                                if (url != null && isStoryCdnUrl(url) && isVideoUrl(url)) return url
                            }
                        }
                    } else if (shouldDescend(nested.javaClass)) {
                        findStoryVideoUrl(nested, visited, depth + 1)?.let { return it }
                    }
                }
            }
            cls = cls.superclass
        }
        return null
    }

    private fun collectStoryImageCandidates(value: Any?, out: MutableList<StoryImageCandidate>, visited: MutableSet<Any>, depth: Int) {
        if (value == null || depth > 7 || out.size >= 40 || !visited.add(value) || !shouldDescend(value.javaClass)) return
        var candidateUrl: String? = null
        val dims = mutableListOf<Int>()

        fun acceptDimension(raw: Long) {
            if (raw in 50L..20_000L) dims += raw.toInt()
        }

        var cls: Class<*>? = value.javaClass
        while (cls != null && cls != Any::class.java) {
            cls.declaredFields.forEach { field ->
                runCatching {
                    field.isAccessible = true
                    when (field.type) {
                        String::class.java -> {
                            val url = field.get(value) as? String
                            if (url != null && isStoryCdnUrl(url) && !isVideoUrl(url)) candidateUrl = url
                        }
                        java.lang.Integer.TYPE -> acceptDimension(field.getInt(value).toLong())
                        java.lang.Long.TYPE -> acceptDimension(field.getLong(value))
                    }
                }
            }
            cls = cls.superclass
        }

        cls = value.javaClass
        while (cls != null && cls != Any::class.java) {
            cls.declaredMethods
                .filter { it.parameterTypes.isEmpty() }
                .forEach { method ->
                    runCatching {
                        method.isAccessible = true
                        when (method.returnType) {
                            String::class.java -> {
                                val url = method.invoke(value) as? String
                                if (url != null && isStoryCdnUrl(url) && !isVideoUrl(url) && candidateUrl == null) candidateUrl = url
                            }
                            java.lang.Integer.TYPE -> acceptDimension((method.invoke(value) as? Int)?.toLong() ?: return@runCatching)
                            java.lang.Long.TYPE -> acceptDimension(method.invoke(value) as? Long ?: return@runCatching)
                        }
                    }
                }
            cls = cls.superclass
        }

        if (candidateUrl != null && dims.size >= 2) {
            val area = dims.sortedDescending().take(2).fold(1) { acc, dim -> acc * dim }
            out += StoryImageCandidate(candidateUrl.orEmpty(), area)
            return
        }

        cls = value.javaClass
        while (cls != null && cls != Any::class.java) {
            cls.declaredFields.forEach { field ->
                runCatching {
                    field.isAccessible = true
                    val nested = field.get(value) ?: return@forEach
                    when (nested) {
                        is String -> return@forEach
                        is Iterable<*> -> nested.forEach { collectStoryImageCandidates(it, out, visited, depth + 1) }
                        else -> if (shouldDescend(nested.javaClass)) collectStoryImageCandidates(nested, out, visited, depth + 1)
                    }
                }
            }
            cls = cls.superclass
        }
    }

    private fun pickBestStoryUrl(urls: List<String>): String? {
        urls.firstOrNull(::isVideoUrl)?.let { return it }
        return urls.maxByOrNull { parseUrlArea(it) } ?: urls.firstOrNull()
    }

    private fun parseUrlArea(url: String): Int {
        var maxArea = 0
        var index = 0
        while (index < url.length) {
            if (!url[index].isDigit()) {
                index++
                continue
            }
            val start = index
            while (index < url.length && url[index].isDigit()) index++
            if (index >= url.length || url[index] != 'x') continue
            val mid = index
            index++
            val heightStart = index
            if (heightStart >= url.length || !url[heightStart].isDigit()) continue
            while (index < url.length && url[index].isDigit()) index++
            val width = url.substring(start, mid).toIntOrNull() ?: continue
            val height = url.substring(heightStart, index).toIntOrNull() ?: continue
            maxArea = maxArea.coerceAtLeast(width * height)
        }
        return maxArea
    }

    private fun isStoryCdnUrl(url: String): Boolean {
        return (url.startsWith("http://") || url.startsWith("https://")) &&
            (url.contains("cdninstagram.com") || url.contains("fbcdn.net")) &&
            !looksLikeProfileImageUrl(url)
    }

    private fun cacheStoryMedia(context: Context?, info: StoryMediaInfo?, source: String) {
        if (context == null || info == null || info.url.isBlank() || isHistoryOpenLoggingSuppressed()) return
        val key = "story-cache:${info.mediaId.ifBlank { info.url }}"
        if (!shouldRecord(key, 60_000L)) return
        val app = context.applicationContext ?: context
        executor.submit {
            runCatching {
                val dir = File(app.filesDir, "instaeclipse_activity_history/stories")
                if (!dir.exists() && !dir.mkdirs()) {
                    log("could not create story cache dir")
                    return@submit
                }
                val isVideo = info.video || isVideoUrl(info.url)
                val mediaId = info.mediaId.ifBlank { kotlin.math.abs(info.url.hashCode()).toString() }
                val file = File(dir, "${safeFilePart(info.username)}_${safeFilePart(mediaId)}${if (isVideo) ".mp4" else ".jpg"}")
                if (!file.exists() || file.length() <= 0L) {
                    val tmp = File(dir, "${file.name}.tmp")
                    downloadToFile(info.url, tmp)
                    if (file.exists()) file.delete()
                    if (!tmp.renameTo(file)) {
                        tmp.delete()
                        log("could not finalize story cache file")
                        return@submit
                    }
                }
                val fileUri = Uri.fromFile(file).toString()
                val username = info.username
                InstagramActivityHistoryStore.record(
                    app,
                    InstagramActivityHistoryStore.Item(
                        type = InstagramActivityHistoryStore.TYPE_STORY,
                        username = username,
                        title = if (username.isBlank()) "Story" else "Story by @$username",
                        caption = "",
                        mediaId = mediaId,
                        url = fileUri,
                        thumbnailUrl = if (isVideo) "" else fileUri,
                        source = "$source:${if (isVideo) "video" else "image"}",
                        timestamp = System.currentTimeMillis()
                    )
                )
                log("cached story media: ${file.absolutePath}")
            }.onFailure { log("story cache failed: ${it.message}") }
        }
    }

    private fun downloadToFile(url: String, file: File) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 20_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36")
        }
        try {
            connection.inputStream.use { input ->
                FileOutputStream(file).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun findNearbyMedia(view: View): Any? {
        mediaFromView(view)?.let { return it }
        var parent: ViewParent? = view.parent
        repeat(5) {
            val group = parent as? ViewGroup ?: return@repeat
            findFieldOfType(group, mediaClass, 3)?.let { return it }
            listOf(feedSaveId, feedLikeId, reelLikeId, clipsUfiLikeId).forEach { id ->
                if (id != 0) mediaFromView(group.findViewById(id))?.let { media -> return media }
            }
            parent = group.parent
        }
        return null
    }

    private fun mediaFromView(view: View?): Any? {
        val target = mediaClass ?: return null
        if (view == null) return null
        runCatching { view.getTag(TAG_REEL_MEDIA) }.getOrNull()?.takeIf { target.isInstance(it) }?.let { return it }
        findFieldOfType(getOnClickListener(view), target, 4)?.let { return it }
        return findFieldOfType(view, target, 2)
    }

    private fun getOnClickListener(view: View?): Any? {
        if (view == null) return null
        return runCatching {
            val listenerInfoField = View::class.java.getDeclaredField("mListenerInfo")
            listenerInfoField.isAccessible = true
            val listenerInfo = listenerInfoField.get(view) ?: return@runCatching null
            val clickField = listenerInfo.javaClass.getDeclaredField("mOnClickListener")
            clickField.isAccessible = true
            clickField.get(listenerInfo)
        }.getOrNull()
    }

    private fun findFieldOfType(obj: Any?, target: Class<*>?, depth: Int): Any? {
        if (obj == null || target == null || depth < 0) return null
        if (target.isInstance(obj)) return obj
        var cls: Class<*>? = obj.javaClass
        while (cls != null && cls != Any::class.java) {
            cls.declaredFields.forEach { field ->
                runCatching {
                    if (Modifier.isStatic(field.modifiers)) return@forEach
                    field.isAccessible = true
                    val value = field.get(obj) ?: return@forEach
                    if (target.isInstance(value)) return value
                    if (depth > 0 && shouldDescend(value.javaClass)) {
                        findFieldOfType(value, target, depth - 1)?.let { return it }
                    }
                }
            }
            cls = cls.superclass
        }
        return null
    }

    private fun shouldDescend(cls: Class<*>?): Boolean {
        if (cls == null || cls.isPrimitive || cls.isArray || cls.isEnum) return false
        val name = cls.name
        return name.startsWith("com.instagram.") ||
            name.startsWith("com.facebook.") ||
            name.startsWith("X.") ||
            name.startsWith("p000X.")
    }

    private fun isCdnMediaUrl(url: String): Boolean {
        val lower = url.lowercase(Locale.US)
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false
        if (!lower.contains("cdninstagram.com") && !lower.contains("fbcdn.net") && !lower.contains("scontent")) return false
        if (lower.contains("/t51.") && lower.contains("-19/")) return false
        return true
    }

    private fun isVideoUrl(url: String): Boolean {
        val lower = url.lowercase(Locale.US)
        return lower.contains(".mp4") || lower.contains("t50.") || lower.contains("/o1/") || lower.contains("video")
    }

    private fun looksLikeProfileImageUrl(url: String?): Boolean {
        val lower = url?.lowercase(Locale.US) ?: return false
        return (lower.contains("/t51.") && lower.contains("-19/")) ||
            (lower.contains("t51.") && lower.contains("-19")) ||
            lower.contains("profile_pic") ||
            lower.contains("profilepic") ||
            lower.contains("profilepicture") ||
            lower.contains("profile_picture") ||
            lower.contains("profile_photo") ||
            lower.contains("profilephoto") ||
            lower.contains("avatar") ||
            lower.contains("s150x150") ||
            lower.contains("s320x320")
    }

    private fun looksLikeMediaId(value: String?): Boolean {
        val clean = value?.trim()?.substringBefore("_").orEmpty()
        return clean.matches(Regex("\\d{8,}"))
    }

    private fun isLikelyProfileHeaderAvatar(view: View): Boolean {
        val width = view.width
        val height = view.height
        if (width < 90 || height < 90 || width > 360 || height > 360) return false
        val location = IntArray(2)
        runCatching { view.getLocationOnScreen(location) }.getOrNull() ?: return false
        val centerY = location[1] + height / 2
        if (centerY !in 160..720) return false
        return location[0] < 420
    }

    private fun rememberRecentProfileImageSample(url: String) {
        val now = System.currentTimeMillis()
        synchronized(recentProfileImageSamples) {
            recentProfileImageSamples += TimedThumbnail(url, now)
            while (recentProfileImageSamples.size > 48) recentProfileImageSamples.removeAt(0)
            val cutoff = now - 30_000L
            recentProfileImageSamples.removeAll { it.timeMs < cutoff }
        }
    }

    @Synchronized
    private fun allowTimelineRecord(type: String, mediaId: String, source: String): Boolean {
        if (type != InstagramActivityHistoryStore.TYPE_REEL) return true
        if (!source.startsWith("view:reel") && !source.contains("clips_seen")) return true
        val now = System.currentTimeMillis()
        if (mediaId != lastReelVisualMediaId && now - lastReelVisualRecordAt < 1_200L) {
            return false
        }
        lastReelVisualMediaId = mediaId
        lastReelVisualRecordAt = now
        return true
    }

    private fun hasHistoryEnrichment(type: String, username: String, title: String, caption: String, thumbnailUrl: String): Boolean {
        if (caption.isNotBlank() || thumbnailUrl.isNotBlank()) return true
        if (username.isNotBlank() && (type == InstagramActivityHistoryStore.TYPE_POST || type == InstagramActivityHistoryStore.TYPE_REEL)) return true
        if (title.isBlank()) return false
        val lower = title.trim().lowercase(Locale.US)
        return lower != "post opened" &&
            lower != "post watched" &&
            lower != "reel opened" &&
            lower != "reel watched" &&
            !lower.startsWith("post ") &&
            !lower.startsWith("reel ")
    }

    private fun enrichMediaMetadataAsync(
        context: Context,
        type: String,
        username: String,
        mediaId: String,
        url: String,
        thumbnailUrl: String,
        source: String
    ) {
        val openUrl = InstagramActivityHistoryStore.canonicalMediaUrl(type, mediaId, url)
        if (openUrl.isBlank()) return
        val key = "media-meta:$type:$openUrl"
        if (!shouldRecord(key, 10 * 60_000L)) return
        val app = context.applicationContext ?: context
        executor.submit {
            runCatching {
                var html = fetchText("${openUrl}embed/", openUrl)
                if (html.isBlank()) html = fetchText(openUrl, openUrl)
                if (html.isBlank()) return@submit
                val normalized = normalizeEmbeddedJson(html)
                val caption = extractCaptionFromHtml(normalized)
                val resolvedUsername = firstUseful(username, extractUsernameFromHtml(normalized))
                val resolvedThumb = firstUseful(thumbnailUrl, extractThumbnailFromHtml(normalized))
                if (caption.isBlank() && resolvedUsername.isBlank() && resolvedThumb.isBlank()) return@submit
                val title = caption.ifBlank {
                    resolvedUsername.takeIf { it.isNotBlank() }?.let { "@$it" }
                        ?: if (type == InstagramActivityHistoryStore.TYPE_REEL) "Reel watched" else "Post watched"
                }
                InstagramActivityHistoryStore.record(
                    app,
                    InstagramActivityHistoryStore.Item(
                        type = type,
                        username = resolvedUsername,
                        title = title,
                        caption = caption,
                        mediaId = mediaId,
                        url = openUrl,
                        thumbnailUrl = resolvedThumb,
                        source = "$source:metadata",
                        timestamp = System.currentTimeMillis()
                    )
                )
            }.onFailure { log("metadata enrich failed: ${it.message}") }
        }
    }

    private fun fetchText(url: String, referer: String): String {
        var connection: HttpURLConnection? = null
        return runCatching {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 4_500
                readTimeout = 7_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36")
                setRequestProperty("Accept", "text/html,application/json;q=0.9,*/*;q=0.8")
                setRequestProperty("X-IG-App-ID", "936619743392459")
                setRequestProperty("X-Requested-With", "XMLHttpRequest")
                if (referer.isNotBlank()) setRequestProperty("Referer", referer)
            }
            val code = connection?.responseCode ?: return@runCatching ""
            if (code !in 200..299) return@runCatching ""
            val out = StringBuilder()
            connection?.inputStream.use { input ->
                val buffer = ByteArray(8192)
                while (input != null && out.length < 512_000) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    out.append(String(buffer, 0, read, StandardCharsets.UTF_8))
                }
            }
            out.toString()
        }.getOrDefault("").also { connection?.disconnect() }
    }

    private fun extractCaptionFromHtml(html: String): String {
        var caption = firstMatch(html, "\"edge_media_to_caption\"\\s*:\\s*\\{\\s*\"edges\"\\s*:\\s*\\[\\s*\\{\\s*\"node\"\\s*:\\s*\\{\\s*\"text\"\\s*:\\s*\"([^\"]*)\"")
        if (caption.isBlank()) caption = firstMatch(html, "\"caption\"\\s*:\\s*\"([^\"]{6,2200})\"")
        if (caption.isBlank()) caption = firstMatch(html, "property=[\"']og:description[\"'][^>]*content=[\"']([^\"']+)")
        if (caption.isBlank()) caption = firstMatch(html, "content=[\"']([^\"']+)[\"'][^>]*property=[\"']og:description[\"']")
        caption = cleanHistoryCaption(decodeJsonEscapes(caption))
        return if (isCaptionLike(caption)) caption else ""
    }

    private fun extractUsernameFromHtml(html: String): String {
        var username = firstMatch(html, "\"owner\"\\s*:\\s*\\{[^}]*\"username\"\\s*:\\s*\"([a-zA-Z0-9._]{1,30})\"")
        if (username.isBlank()) username = firstMatch(html, "\"username\"\\s*:\\s*\"([a-zA-Z0-9._]{1,30})\"")
        return username.takeIf { isProfileSlug(it) }.orEmpty()
    }

    private fun extractThumbnailFromHtml(html: String): String {
        var thumbnail = firstMatch(html, "\"thumbnail_url\"\\s*:\\s*\"([^\"]+)\"")
        if (thumbnail.isBlank()) thumbnail = firstMatch(html, "\"display_url\"\\s*:\\s*\"([^\"]+)\"")
        if (thumbnail.isBlank()) thumbnail = firstMatch(html, "property=[\"']og:image[\"'][^>]*content=[\"']([^\"']+)")
        if (thumbnail.isBlank()) thumbnail = firstMatch(html, "content=[\"']([^\"']+)[\"'][^>]*property=[\"']og:image[\"']")
        return cleanResolvedUrl(thumbnail)
    }

    private fun firstMatch(value: String, regex: String): String {
        if (value.isBlank()) return ""
        return runCatching {
            Pattern.compile(regex, Pattern.CASE_INSENSITIVE or Pattern.DOTALL).matcher(value).let { matcher ->
                if (matcher.find()) matcher.group(1).orEmpty() else ""
            }
        }.getOrDefault("")
    }

    private fun normalizeEmbeddedJson(value: String): String {
        if (value.isBlank()) return ""
        return value
            .replace("\\\"", "\"")
            .replace("\\\\/", "/")
            .replace("\\/", "/")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
    }

    private fun cleanResolvedUrl(value: String): String {
        if (value.isBlank()) return ""
        return decodeJsonEscapes(value.trim())
            .replace("\\\\/", "/")
            .replace("\\/", "/")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
    }

    private fun decodeJsonEscapes(value: String): String {
        if (value.isBlank()) return ""
        val text = value
            .replace("\\\\", "\\")
            .replace("\\/", "/")
            .replace("\\\"", "\"")
            .replace("\\n", "\n")
            .replace("\\r", "\n")
            .replace("\\t", " ")
        return runCatching {
            val matcher = Pattern.compile("\\\\[uU]([0-9a-fA-F]{4})").matcher(text)
            val out = StringBuffer()
            while (matcher.find()) {
                val decoded = String(Character.toChars(Integer.parseInt(matcher.group(1), 16)))
                matcher.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(decoded))
            }
            matcher.appendTail(out)
            out.toString()
        }.getOrDefault(text)
    }

    private fun firstUseful(existing: String, fresh: String): String = existing.takeIf { it.isNotBlank() } ?: fresh.trim()

    private fun safeFilePart(value: String): String {
        return value.ifBlank { "story" }.replace(Regex("[^a-zA-Z0-9._-]"), "_")
    }

    private fun recordInstagramUri(context: Context, uri: Uri, source: String) {
        if (isHistoryOpenLoggingSuppressed()) return
        val scheme = uri.scheme?.lowercase(Locale.US).orEmpty()
        val host = uri.host?.lowercase(Locale.US).orEmpty()
        if (scheme.startsWith("http") && !host.endsWith("instagram.com")) return
        val segments = uri.pathSegments ?: return
        if (segments.isEmpty()) return
        val first = segments[0].lowercase(Locale.US)
        val isCanonicalMediaOrStoryPath = first in setOf("p", "reel", "reels", "tv", "stories")
        if (scheme.isBlank() && !isCanonicalMediaOrStoryPath) return
        when {
            first in setOf("p", "reel", "reels", "tv") && segments.size >= 2 -> {
                val code = segments[1].trim('/')
                if (code.isBlank() || isMediaThumbnailPath(segments)) return
                val type = if (first == "reel" || first == "reels") InstagramActivityHistoryStore.TYPE_REEL else InstagramActivityHistoryStore.TYPE_POST
                val title = if (type == InstagramActivityHistoryStore.TYPE_REEL) "Reel $code" else "Post $code"
                val url = if (type == InstagramActivityHistoryStore.TYPE_REEL) instagramReelUrl(code) else instagramPostUrl(code)
                record(context, type, "", title, "", code, url, "", source)
            }
            first == "stories" && segments.size >= 3 -> {
                val username = segments[1].trim('@', '/')
                val storyId = segments[2].trim('/')
                if (username.isNotBlank() && storyId.isNotBlank()) {
                    record(
                        context,
                        InstagramActivityHistoryStore.TYPE_STORY,
                        username,
                        "Story by @$username",
                        "",
                        storyId,
                        "https://www.instagram.com/stories/$username/$storyId/",
                        "",
                        source
                    )
                }
            }
            isPublicWebProfileHost(host) && isProfileSlug(first) -> {
                record(
                    context,
                    InstagramActivityHistoryStore.TYPE_PAGE,
                    first,
                    "@$first",
                    "",
                    "",
                    instagramProfileUrl(first),
                    "",
                    source
                )
            }
        }
    }

    private fun isPublicWebProfileHost(host: String): Boolean {
        return host == "instagram.com" || host == "www.instagram.com"
    }

    private fun record(
        context: Context,
        type: String,
        username: String,
        title: String,
        caption: String,
        mediaId: String,
        url: String,
        thumbnailUrl: String,
        source: String
    ) {
        if (isHistoryOpenLoggingSuppressed()) return
        if ((type == InstagramActivityHistoryStore.TYPE_POST || type == InstagramActivityHistoryStore.TYPE_REEL) &&
            InstagramActivityHistoryStore.canonicalMediaUrl(type, mediaId, url).isBlank()
        ) {
            return
        }
        if (!allowTimelineRecord(type, mediaId, source)) return
        val identity = mediaId.ifBlank { username.ifBlank { url } }
        val keyPreview = listOf(type, identity).joinToString(":")
        val windowMs = if (type == InstagramActivityHistoryStore.TYPE_REEL) 25_000L else 90_000L
        val duplicateWindow = !shouldRecord(keyPreview, windowMs)
        if (duplicateWindow && !hasHistoryEnrichment(type, username, title, caption, thumbnailUrl)) return
        val app = context.applicationContext ?: context
        val item = InstagramActivityHistoryStore.Item(
            type = type,
            username = username,
            title = title,
            caption = caption,
            mediaId = mediaId,
            url = url,
            thumbnailUrl = thumbnailUrl,
            source = source,
            timestamp = System.currentTimeMillis()
        )
        recordExecutor.execute {
            InstagramActivityHistoryStore.record(app, item)
            if ((type == InstagramActivityHistoryStore.TYPE_POST || type == InstagramActivityHistoryStore.TYPE_REEL) && caption.isBlank()) {
                enrichMediaMetadataAsync(app, type, username, mediaId, url, thumbnailUrl, source)
            }
        }
        log("Recorded history item type=$type media=$mediaId user=$username source=$source")
    }

    private fun shouldRecord(key: String, windowMs: Long): Boolean {
        if (key.isBlank()) return false
        synchronized(recentRecords) {
            val now = System.currentTimeMillis()
            val previous = recentRecords[key] ?: 0L
            if (now - previous < windowMs) return false
            recentRecords[key] = now
            if (recentRecords.size > 600) recentRecords.clear()
            return true
        }
    }

    private fun tryRecordProfilePage(view: View) {
        val context = view.context ?: return
        val id = view.id
        val idName = resourceName(view)
        val profileHeader = (profileHeaderId != 0 && id == profileHeaderId) ||
            (rowProfileHeaderId != 0 && id == rowProfileHeaderId) ||
            idName == "profile_header_container" ||
            idName == "row_profile_header"
        if (!profileHeader) return
        val activity = activityFromContext(context)
        var username = profileUsernameFromActivity(activity)
        if (username.isBlank() && view is ViewGroup && profileHeaderUsernameId != 0) {
            val usernameView = view.findViewById<View>(profileHeaderUsernameId)
            if (usernameView is TextView) username = cleanText(usernameView.text)
        }
        if (!isProfileSlug(username)) return
        val thumbnailUrl = findProfileImageUrlInTree(view).ifBlank { recentProfileHeaderThumbnailForRecord() }
        if (thumbnailUrl.isNotBlank()) {
            recentProfileThumbnails[username.lowercase(Locale.US)] = thumbnailUrl
        }
        record(
            context,
            InstagramActivityHistoryStore.TYPE_PAGE,
            username,
            "@$username",
            "",
            "",
            instagramProfileUrl(username),
            thumbnailUrl,
            "view:profile_header"
        )
    }

    private fun findProfileImageUrlInTree(root: View): String {
        profileImageUrls[root]?.takeIf { looksLikeProfileImageUrl(it) }?.let { return it }
        if (root !is ViewGroup) return ""
        var visited = 0
        fun scan(view: View): String? {
            if (visited++ > 90) return null
            profileImageUrls[view]?.takeIf { looksLikeProfileImageUrl(it) }?.let { return it }
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) {
                    scan(view.getChildAt(i))?.let { return it }
                }
            }
            return null
        }
        return scan(root).orEmpty()
    }

    private fun recentProfileHeaderThumbnailForRecord(): String {
        synchronized(profileImageUrls) {
            profileImageUrls.entries.firstOrNull { (view, url) ->
                looksLikeProfileImageUrl(url) && isLikelyProfileHeaderAvatar(view)
            }?.value?.let { return it }
        }
        val now = System.currentTimeMillis()
        val candidate = recentProfileHeaderThumbnail
        if (candidate != null && now - candidate.timeMs <= 20_000L && looksLikeProfileImageUrl(candidate.url)) {
            return candidate.url
        }
        return bestRecentProfileImageSample(now)
    }

    private fun bestRecentProfileImageSample(now: Long): String {
        val samples = synchronized(recentProfileImageSamples) {
            recentProfileImageSamples.filter { now - it.timeMs <= 20_000L && looksLikeProfileImageUrl(it.url) }
        }
        var bestUrl = ""
        var bestCount = 0
        var bestLastSeen = 0L
        samples.groupBy { it.url }.forEach { (url, values) ->
            val count = values.size
            val lastSeen = values.maxOfOrNull { it.timeMs } ?: 0L
            if (count > bestCount || (count == bestCount && lastSeen > bestLastSeen)) {
                bestUrl = url
                bestCount = count
                bestLastSeen = lastSeen
            }
        }
        return bestUrl
    }

    private fun profileUsernameFromActivity(activity: Activity?): String {
        if (activity == null) return ""
        runCatching {
            if (actionBarTitleId != 0) {
                val title = activity.findViewById<View>(actionBarTitleId)
                if (title is TextView) {
                    val text = cleanText(title.text)
                    if (isProfileSlug(text)) return text
                }
            }
        }
        runCatching {
            val text = cleanText(activity.title)
            if (isProfileSlug(text)) return text
        }
        return ""
    }

    private fun activityFromContext(context: Context?): Activity? {
        var current = context
        while (current is ContextWrapper) {
            if (current is Activity) return current
            current = current.baseContext
        }
        return current as? Activity
    }

    private fun cleanText(value: CharSequence?): String = value?.toString()?.trim()?.replace("@", "").orEmpty()

    private fun hasPotentialInstagramUrl(view: View): Boolean {
        fun containsUrl(value: CharSequence?): Boolean {
            if (value.isNullOrBlank()) return false
            val text = value.toString()
            return text.contains("instagram.com", ignoreCase = true) ||
                text.contains("instagr.am", ignoreCase = true) ||
                text.contains("instagram://", ignoreCase = true)
        }
        return containsUrl(view.contentDescription) ||
            containsUrl(view.tag?.toString())
    }

    private fun isNetworkHistoryCandidate(uri: URI): Boolean {
        val path = uri.path.orEmpty()
        if (path.contains("/media/") && path.contains("/info/")) return true
        if (path.contains("/clips/item/") ||
            path.contains("/clips/items/") ||
            path.contains("/clips/write_seen_state")
        ) {
            return true
        }
        return shouldRecordNetworkInstagramUri(uri, path)
    }

    private fun shouldRecordNetworkInstagramUri(uri: URI, path: String = uri.path.orEmpty()): Boolean {
        val scheme = uri.scheme?.lowercase(Locale.US).orEmpty()
        if (scheme == "instagram") return true
        val host = uri.host?.lowercase(Locale.US).orEmpty()
        if (!host.endsWith("instagram.com") && !host.endsWith("instagr.am")) {
            val query = uri.rawQuery.orEmpty()
            return query.contains("instagram.com", ignoreCase = true) ||
                query.contains("instagr.am", ignoreCase = true) ||
                query.contains("instagram%3A%2F%2F", ignoreCase = true)
        }
        val first = path.trimStart('/').substringBefore('/').lowercase(Locale.US)
        if (first in setOf("p", "reel", "reels", "tv", "stories")) return true
        return isPublicWebProfileHost(host) && isProfileSlug(first)
    }

    private fun extractInstagramUrls(view: View): List<String> {
        val output = linkedSetOf<String>()
        fun scanText(value: String?) {
            if (value.isNullOrBlank()) return
            val matcher = Pattern.compile("https?://[^\\s<>\"']+").matcher(value)
            while (matcher.find()) {
                val url = matcher.group().trimEnd(',', '.', ')', ']')
                if (url.contains("instagram.com")) output += url
            }
        }
        scanText(view.contentDescription?.toString())
        scanText(view.tag?.toString())
        if (view is TextView) scanText(view.text?.toString())
        return output.toList()
    }

    private fun isHistoryOpenLoggingSuppressed(): Boolean = System.currentTimeMillis() < suppressHistoryOpenUntilMs

    private fun ensureIds(context: Context) {
        if (idsResolved) return
        idsResolved = true
        val resources = context.resources
        val packageName = context.packageName
        feedLikeId = resources.getIdentifier("row_feed_button_like", "id", packageName)
        feedSaveId = resources.getIdentifier("row_feed_button_save", "id", packageName)
        reelLikeId = resources.getIdentifier("like_button", "id", packageName)
        clipsUfiLikeId = resources.getIdentifier("clips_ufi_like_button", "id", packageName)
        clipsUfiId = resources.getIdentifier("clips_ufi_component", "id", packageName)
        profileHeaderId = resources.getIdentifier("profile_header_container", "id", packageName)
        rowProfileHeaderId = resources.getIdentifier("row_profile_header", "id", packageName)
        profileHeaderUsernameId = resources.getIdentifier("profile_header_username", "id", packageName)
        actionBarTitleId = resources.getIdentifier("action_bar_title", "id", packageName)
    }

    private fun resourceName(view: View): String {
        val id = view.id
        if (id == View.NO_ID || id <= 0x00FFFFFF) return ""
        return runCatching { view.resources.getResourceEntryName(id) }.getOrDefault("")
    }

    private fun cachedResourceName(view: View): String {
        resourceNameCache[view]?.let { return it }
        val value = resourceName(view)
        if (value.isNotBlank()) resourceNameCache[view] = value
        return value
    }

    private fun isMediaThumbnailPath(segments: List<String>): Boolean {
        val third = segments.getOrNull(2)?.lowercase(Locale.US).orEmpty()
        return third == "media" || third == "embed"
    }

    private fun firstQueryValue(query: String, vararg keys: String): String {
        if (query.isBlank()) return ""
        val wanted = keys.toSet()
        query.split("&").forEach { part ->
            val key = part.substringBefore("=")
            if (key in wanted) return decode(part.substringAfter("=", "")).split(",").firstOrNull().orEmpty()
        }
        return ""
    }

    private fun decode(value: String): String {
        return runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
    }

    private fun instagramPostUrl(mediaIdOrCode: String): String {
        val code = InstagramActivityHistoryStore.publicCodeFromIdOrUrl(mediaIdOrCode, "")
        return if (code.isBlank()) "" else "https://www.instagram.com/p/$code/"
    }

    private fun instagramReelUrl(mediaIdOrCode: String): String {
        val code = InstagramActivityHistoryStore.publicCodeFromIdOrUrl(mediaIdOrCode, "")
        return if (code.isBlank()) "" else "https://www.instagram.com/reel/$code/"
    }

    private fun instagramProfileUrl(username: String): String = "https://www.instagram.com/${username.trim('@')}/"

    private fun isProfileSlug(value: String): Boolean {
        val lower = value.lowercase(Locale.US)
        if (!lower.matches(Regex("[a-z0-9._]{1,30}"))) return false
        return lower !in setOf(
            "p", "reel", "reels", "tv", "stories", "explore", "direct", "accounts",
            "about", "developer", "legal", "privacy", "terms", "api", "graphql",
            "graphql_www", "www", "web", "ajax", "ads", "logging", "qp", "rmd", "v",
            "_n", "_u", "_uid"
        )
    }

    private inline fun safeCallback(source: String, block: () -> Unit) {
        try {
            block()
        } catch (throwable: Throwable) {
            if (callbackFailureLogs++ < 20) {
                log("$source failed safely: ${throwable.javaClass.simpleName}: ${throwable.message}")
            }
        }
    }

    private fun log(message: String) {
        InstagramAppLogWriter.info(appContext, "${InstagramFeatureState.TAG}|ActivityHistory", message)
        if (!message.startsWith("Recorded history item")) {
            XposedBridge.log("[${InstagramFeatureState.TAG}|ActivityHistory] $message")
        }
    }
}

internal object InstagramActivityHistoryDialog {
    private val BG = Color.BLACK
    private val SURFACE = Color.rgb(18, 18, 18)
    private val SURFACE_2 = Color.rgb(30, 30, 30)
    private val TEXT = Color.rgb(245, 245, 245)
    private val MUTED = Color.rgb(168, 168, 168)
    private val DIVIDER = Color.rgb(38, 38, 38)
    private val ACCENT = Color.rgb(0, 149, 246)
    private val imageExecutor = Executors.newFixedThreadPool(8)
    private val thumbnailLocks = ConcurrentHashMap<String, Any>()
    private val resolvedThumbnails = ConcurrentHashMap<String, String>()
    private val resolvedPageNames = ConcurrentHashMap<String, String>()
    private val resolvedPageThumbnails = ConcurrentHashMap<String, String>()
    private val bitmapCache = object : LruCache<String, Bitmap>(24 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = maxOf(1, value.byteCount / 1024)
    }
    @Volatile private var lastCacheTrimMs = 0L

    fun show(context: Context) {
        val activity = activityFromContext(context)
        if (activity != null && InstagramActivityHistoryHooks.launchNativePage(activity)) return
        val dialogContext = activity ?: run {
            Toast.makeText(context, "Activity history is not ready", Toast.LENGTH_SHORT).show()
            return
        }
        runCatching {
            val dialog = Dialog(dialogContext)
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
            dialog.setContentView(createContent(dialogContext, onClose = { dialog.dismiss() }, preferNativeUi = false))
            dialog.show()
            dialog.window?.let { window ->
                window.setBackgroundDrawable(ColorDrawable(BG))
                window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            }
        }.onFailure {
            XposedBridge.log("[${InstagramFeatureState.TAG}|ActivityHistory] dialog show failed: ${it.javaClass.simpleName}: ${it.message}")
            Toast.makeText(context, "Activity history is not ready", Toast.LENGTH_SHORT).show()
        }
    }

    fun createContent(context: Context, onClose: (() -> Unit)? = null, preferNativeUi: Boolean = true): View {
        val state = State(context, onClose ?: {}, preferNativeUi = preferNativeUi)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
        }
        root.addView(createHeader(state))
        root.addView(createSearchBox(state))
        root.addView(createTabs(state))
        if (!InstagramFeatureStateStore.current.enableActivityHistory) {
            root.addView(TextView(context).apply {
                text = "Logging is off. Enable Activity History Logging in Misc Features to start collecting new items."
                setTextColor(MUTED)
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(dp(context, 18), dp(context, 10), dp(context, 18), dp(context, 10))
                setBackgroundColor(SURFACE)
            })
        }
        state.recyclerView = RecyclerView(context).apply {
            clipToPadding = false
            setPadding(
                if (state.preferNativeUi) 0 else dp(context, 10),
                if (state.preferNativeUi) 0 else dp(context, 12),
                if (state.preferNativeUi) 0 else dp(context, 10),
                dp(context, 24)
            )
            layoutManager = GridLayoutManager(context, 3).apply {
                spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                    override fun getSpanSize(position: Int): Int = state.adapter?.spanSize(position) ?: 1
                }
            }
        }
        state.adapter = Adapter(state)
        state.recyclerView?.adapter = state.adapter
        root.addView(state.recyclerView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        render(state)
        return root
    }

    private fun createHeader(state: State): View {
        if (state.preferNativeUi) createNativeHeader(state)?.let { return it }
        val context = state.context
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(context, 8), dp(context, 8), dp(context, 8), dp(context, 4))
            minimumHeight = dp(context, 58)
            setBackgroundColor(BG)
            addView(actionLabel(context, "\u2039", 36).apply {
                setOnClickListener { if (state.selectionMode) exitSelectionMode(state) else state.closeAction() }
            }, LinearLayout.LayoutParams(dp(context, 48), dp(context, 48)))
            state.titleView = TextView(context).apply {
                text = "Activity History"
                setTextColor(TEXT)
                textSize = 24f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
            }
            addView(state.titleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            state.clearButton = actionLabel(context, "\uD83D\uDDD1", 24).apply {
                setOnClickListener { handleTrashClick(state) }
                setOnLongClickListener {
                    confirmClear(state)
                    true
                }
            }
            addView(state.clearButton, LinearLayout.LayoutParams(dp(context, 48), dp(context, 48)))
            addView(actionLabel(context, "\u2315", 32).apply {
                setOnClickListener {
                    state.searchVisible = !state.searchVisible
                    searchContainer(state)?.visibility = if (state.searchVisible) View.VISIBLE else View.GONE
                    if (state.searchVisible) {
                        state.searchBox?.requestFocus()
                    } else {
                        state.query = ""
                        state.searchBox?.setText("")
                        render(state)
                    }
                }
            }, LinearLayout.LayoutParams(dp(context, 48), dp(context, 48)))
            updateSelectionHeader(state)
        }
    }

    private fun createNativeHeader(state: State): View? {
        return runCatching {
            val context = state.context
            val actionBarClass = Class.forName("com.instagram.igds.components.actionbar.IgdsActionBar", true, context.classLoader)
            val actionBar = actionBarClass.getConstructor(Context::class.java).newInstance(context) as View
            state.titleView = actionBarClass.getField("A06").get(actionBar) as? TextView
            state.titleView?.text = "Activity History"

            (actionBarClass.getField("A09").get(actionBar) as? ImageView)?.apply {
                drawableId(context, "instagram_arrow_back_24").takeIf { it != 0 }?.let(::setImageResource)
                visibility = View.VISIBLE
                setOnClickListener { if (state.selectionMode) exitSelectionMode(state) else state.closeAction() }
            }

            val endActions = actionBarClass.getField("A03").get(actionBar) as? LinearLayout
            val clear = nativeActionIcon(context, "instagram_delete_outline_24").apply {
                setOnClickListener { handleTrashClick(state) }
                setOnLongClickListener {
                    confirmClear(state)
                    true
                }
            }
            state.clearButton = clear
            endActions?.addView(clear, nativeActionParams(context))

            endActions?.addView(nativeActionIcon(context, "instagram_search_outline_24").apply {
                setOnClickListener {
                    state.searchVisible = !state.searchVisible
                    searchContainer(state)?.visibility = if (state.searchVisible) View.VISIBLE else View.GONE
                    if (state.searchVisible) {
                        state.searchBox?.requestFocus()
                    } else {
                        state.query = ""
                        state.searchBox?.setText("")
                        render(state)
                    }
                }
            }, nativeActionParams(context))
            updateSelectionHeader(state)
            actionBar
        }.onFailure {
            XposedBridge.log("[${InstagramFeatureState.TAG}|ActivityHistory] native action bar unavailable: ${it.javaClass.simpleName}")
        }.getOrNull()
    }

    private fun createSearchBox(state: State): View {
        if (state.preferNativeUi) createNativeSearchBox(state)?.let { return it }
        val context = state.context
        return EditText(context).apply {
            state.searchBox = this
            state.searchContainer = this
            visibility = View.GONE
            setSingleLine(true)
            hint = "Search username, caption, media id..."
            setHintTextColor(Color.rgb(115, 115, 115))
            setTextColor(TEXT)
            textSize = 16f
            setPadding(dp(context, 18), 0, dp(context, 18), 0)
            background = rounded(SURFACE_2, dp(context, 18), 0, 0)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 46)).apply {
                setMargins(dp(context, 16), dp(context, 4), dp(context, 16), dp(context, 8))
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    state.query = s?.toString().orEmpty()
                    render(state)
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
    }

    private fun createNativeSearchBox(state: State): View? {
        return runCatching {
            val context = state.context
            val searchClass = Class.forName("com.instagram.igds.components.search.IgdsInlineSearchBox", true, context.classLoader)
            val searchView = searchClass.getConstructor(Context::class.java).newInstance(context) as View
            runCatching { searchClass.getMethod("setHint", String::class.java).invoke(searchView, "Search username, caption, media id...") }
            val editText = searchClass.getField("A0E").get(searchView) as EditText
            state.searchBox = editText
            state.searchContainer = searchView
            searchView.visibility = View.GONE
            searchView.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(context, 12), dp(context, 6), dp(context, 12), dp(context, 8))
            }
            editText.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    state.query = s?.toString().orEmpty()
                    render(state)
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
            searchView
        }.onFailure {
            XposedBridge.log("[${InstagramFeatureState.TAG}|ActivityHistory] native search unavailable: ${it.javaClass.simpleName}")
        }.getOrNull()
    }

    private fun createTabs(state: State): View {
        if (state.preferNativeUi) createNativeTabs(state)?.let { return it }
        val context = state.context
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(BG)
            setPadding(0, dp(context, 4), 0, 0)
            addTab(state, this, "Post", InstagramActivityHistoryStore.TYPE_POST)
            addTab(state, this, "Story", InstagramActivityHistoryStore.TYPE_STORY)
            addTab(state, this, "Reel", InstagramActivityHistoryStore.TYPE_REEL)
            addTab(state, this, "Page", InstagramActivityHistoryStore.TYPE_PAGE)
        }
    }

    private fun addTab(state: State, parent: LinearLayout, label: String, type: String) {
        val tab = TextView(state.context).apply {
            text = label
            gravity = Gravity.CENTER
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(state.context, 14), 0, dp(state.context, 10))
            setOnClickListener {
                state.selectionMode = false
                state.selectedKeys.clear()
                state.type = type
                updateSelectionHeader(state)
                render(state)
                refreshTabs(state)
            }
        }
        state.tabs += TabRef(type, tab)
        parent.addView(tab, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        refreshTab(state, type, tab)
    }

    private fun createNativeTabs(state: State): View? {
        return runCatching {
            val context = state.context
            val tabLayoutClass = Class.forName("com.instagram.igds.components.segmentedtabs.IgSegmentedTabLayout", true, context.classLoader)
            val tabSpecClass = Class.forName("p000X.C53709Kw1", true, context.classLoader)
            val tabSpecCtor = tabSpecClass.getConstructor(
                android.graphics.drawable.Drawable::class.java,
                CharSequence::class.java,
                CharSequence::class.java,
                java.lang.Integer.TYPE,
                java.lang.Boolean.TYPE
            )
            val addTab = tabLayoutClass.getMethod("A02", View.OnClickListener::class.java, tabSpecClass)
            val tabs = tabLayoutClass.getConstructor(Context::class.java).newInstance(context) as View
            state.nativeTabs = tabs
            addNativeTab(state, addTab, tabSpecCtor, tabs, "Post", InstagramActivityHistoryStore.TYPE_POST, 0)
            addNativeTab(state, addTab, tabSpecCtor, tabs, "Story", InstagramActivityHistoryStore.TYPE_STORY, 1)
            addNativeTab(state, addTab, tabSpecCtor, tabs, "Reel", InstagramActivityHistoryStore.TYPE_REEL, 2)
            addNativeTab(state, addTab, tabSpecCtor, tabs, "Page", InstagramActivityHistoryStore.TYPE_PAGE, 3)
            tabs.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            refreshTabs(state)
            tabs
        }.onFailure {
            XposedBridge.log("[${InstagramFeatureState.TAG}|ActivityHistory] native tabs unavailable: ${it.javaClass.simpleName}")
        }.getOrNull()
    }

    private fun addNativeTab(
        state: State,
        addTab: Method,
        tabSpecCtor: java.lang.reflect.Constructor<*>,
        tabs: View,
        label: String,
        type: String,
        index: Int
    ) {
        val tabSpec = tabSpecCtor.newInstance(null, label, null, -1, true)
        val listener = View.OnClickListener {
            state.selectionMode = false
            state.selectedKeys.clear()
            state.type = type
            updateSelectionHeader(state)
            render(state)
            selectNativeTab(state, index)
        }
        addTab.invoke(tabs, listener, tabSpec)
    }

    private fun render(state: State) {
        refreshTabs(state)
        val items = queryItemsForDialog(state)
        val rows = mutableListOf<Row>()
        when {
            items.isEmpty() -> rows += Row.Empty(if (state.query.isBlank()) "No history yet." else "No matches.")
            state.type == InstagramActivityHistoryStore.TYPE_STORY -> rows += storyRows(items)
            else -> items.take(160).forEach { rows += Row.Card(it) }
        }
        state.adapter?.submit(rows)
    }

    private fun storyRows(items: List<InstagramActivityHistoryStore.Item>): List<Row> {
        val grouped = LinkedHashMap<String, Pair<String, MutableList<InstagramActivityHistoryStore.Item>>>()
        items.forEach { item ->
            val label = storyGroupLabel(item)
            val key = storyGroupKey(label, item)
            grouped.getOrPut(key) { label to mutableListOf() }.second += item
        }
        val rows = mutableListOf<Row>()
        var added = 0
        for ((_, entry) in grouped) {
            val (label, group) = entry
            rows += Row.Header(label, group.size)
            for (item in group) {
                if (added >= 160) return rows
                rows += Row.Card(item)
                added++
            }
        }
        return rows
    }

    private fun storyGroupLabel(item: InstagramActivityHistoryStore.Item): String {
        val username = resolvedStoryUsername(item)
        if (username.isNotBlank()) return "@$username"
        val title = cleanDisplayText(item.title)
            .takeIf { it.isNotBlank() && !it.equals("Story", true) && !it.startsWith("Story by ", true) && !isBadHistoryText(it) }
        if (title != null) return title.take(48)
        val source = cleanDisplayText(item.source.substringBefore(":"))
            .takeIf { it.isNotBlank() && !it.equals("story", true) && !it.startsWith("dex", true) && !it.startsWith("view", true) }
        if (source != null) return source.take(48)
        return "Unknown story ${formatTime(item.timestamp).substringAfter(' ')}"
    }

    private fun storyGroupKey(label: String, item: InstagramActivityHistoryStore.Item): String {
        if (label.startsWith("Unknown story", true)) return "unknown:${item.uniqueKey()}"
        val normalized = normalizeSearch(label).ifBlank { "unknown" }
        return if (normalized.startsWith("@")) normalized else "$normalized:${item.timestamp / 86_400_000L}"
    }

    private fun resolvedStoryUsername(item: InstagramActivityHistoryStore.Item): String {
        item.username.trim('@').takeIf { isStoryUsernameLike(it) }?.let { return it }
        listOf(item.url, item.thumbnailUrl).forEach { value ->
            val fromUrl = usernameFromStoryUrl(value)
            if (fromUrl.isNotBlank()) return fromUrl
        }
        val fromTitle = Regex("(?i)story\\s+by\\s+@?([A-Za-z0-9._]{1,30})").find(item.title)?.groupValues?.getOrNull(1).orEmpty()
        if (isStoryUsernameLike(fromTitle)) return fromTitle
        return ""
    }

    private fun usernameFromStoryUrl(value: String): String {
        if (value.isBlank()) return ""
        return runCatching {
            val uri = Uri.parse(value)
            val scheme = uri.scheme.orEmpty().lowercase(Locale.US)
            val host = uri.host.orEmpty().lowercase(Locale.US)
            if (scheme !in setOf("http", "https") || host !in setOf("instagram.com", "www.instagram.com")) return@runCatching ""
            val segments = uri.pathSegments
            val storyIndex = segments.indexOfFirst { it.equals("stories", true) }
            val username = if (storyIndex >= 0) segments.getOrNull(storyIndex + 1).orEmpty() else ""
            username.trim('@').takeIf { isStoryUsernameLike(it) }.orEmpty()
        }.getOrDefault("")
    }

    private fun isStoryUsernameLike(value: String): Boolean {
        val lower = value.lowercase(Locale.US)
        return lower.matches(Regex("[a-z0-9._]{1,30}")) && lower !in setOf("stories", "story", "media")
    }

    private fun queryItemsForDialog(state: State): List<InstagramActivityHistoryStore.Item> {
        val items = InstagramActivityHistoryStore.read(state.context).filter { it.type == state.type }
        val query = normalizeSearch(state.query)
        if (query.isBlank()) return items
        val tokens = query.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return items
        val direct = mutableListOf<ScoredItem>()
        val fuzzy = mutableListOf<ScoredItem>()
        items.forEachIndexed { index, item ->
            val storyLabel = if (item.type == InstagramActivityHistoryStore.TYPE_STORY) storyGroupLabel(item) else ""
            val haystack = normalizeSearch(
                listOf(primaryText(item), secondaryText(item), storyLabel, item.username, item.mediaId, item.title, item.caption, item.typeLabel()).joinToString(" ")
            )
            val compact = haystack.replace(" ", "")
            if (tokens.all { haystack.contains(it) }) {
                direct += ScoredItem(item, 1000 - index)
            } else if (tokens.all { compact.contains(it) }) {
                fuzzy += ScoredItem(item, 200 - index)
            }
        }
        return (direct + fuzzy).sortedByDescending { it.score }.map { it.item }
    }

    private fun createItemView(state: State, item: InstagramActivityHistoryStore.Item): View {
        if (state.preferNativeUi &&
            item.type != InstagramActivityHistoryStore.TYPE_PAGE &&
            item.type != InstagramActivityHistoryStore.TYPE_FOLLOWER
        ) {
            val native = when (item.type) {
                InstagramActivityHistoryStore.TYPE_REEL -> createNativeReelTile(state, item)
                InstagramActivityHistoryStore.TYPE_STORY -> createNativePostTile(state, item)
                else -> createNativePostTile(state, item)
            }
            if (native != null) return native
        }
        return if (item.type == InstagramActivityHistoryStore.TYPE_PAGE || item.type == InstagramActivityHistoryStore.TYPE_FOLLOWER) {
            createPageRow(state, item)
        } else {
            createGridCard(state, item)
        }
    }

    private fun createNativePostTile(state: State, item: InstagramActivityHistoryStore.Item): View? {
        val tile = inflateHostLayout(state.context, "media_grid_item_layout") ?: return null
        val image = imageFrom(tile, "media_image_button") ?: return null
        loadThumbnailAsync(image, item)
        return interactiveTileWrapper(state, item, tile, square = true)
    }

    private fun createNativeReelTile(state: State, item: InstagramActivityHistoryStore.Item): View? {
        val tile = inflateHostLayout(state.context, "layout_clips_grid_item") ?: return null
        val image = imageFrom(tile, "preview_clip_thumbnail") ?: return null
        loadThumbnailAsync(image, item)
        textFrom(tile, "primary_label_text")?.text = item.username.takeIf { it.isNotBlank() }?.let { "@$it" }.orEmpty()
        viewFrom(tile, "clip_just_watched_overlay")?.visibility = View.GONE
        return interactiveTileWrapper(state, item, tile, square = false)
    }

    private fun createNativePageRow(state: State, item: InstagramActivityHistoryStore.Item): View? {
        val row = createNativeListCell(
            state.context,
            primaryText(item),
            secondaryText(item),
            checkbox = state.selectionMode,
            checked = state.selectedKeys.contains(item.uniqueKey())
        ) ?: return null
        val icon = listCellIcon(row) ?: return null
        icon.apply {
            visibility = View.VISIBLE
            clearColorFilter()
            scaleType = ImageView.ScaleType.CENTER_CROP
            loadThumbnailAsync(this, item)
        }
        listCellTitle(row)?.let { loadPageNameAsync(it, item) }
        wireActions(row, state, item)
        return row
    }

    private fun createNativeListCell(context: Context, title: String, subtitle: String, checkbox: Boolean, checked: Boolean): View? {
        return runCatching {
            val cellClass = Class.forName("com.instagram.igds.components.textcell.IgdsListCell", true, context.classLoader)
            val cell = cellClass.getConstructor(Context::class.java).newInstance(context) as View
            cellClass.getMethod("A0L", CharSequence::class.java).invoke(cell, title)
            cellClass.getMethod("A0K", CharSequence::class.java).invoke(cell, subtitle)
            runCatching { cellClass.getMethod("setTitleMaxLines", java.lang.Integer.TYPE).invoke(cell, 2) }
            runCatching { cellClass.getMethod("setSubtitleMaxLine", java.lang.Integer.TYPE).invoke(cell, 2) }
            setNativeListCellType(cell, if (checkbox) "A03" else "A04")
            if (checkbox) runCatching { cellClass.getMethod("setChecked", java.lang.Boolean.TYPE).invoke(cell, checked) }
            cell
        }.onFailure {
            XposedBridge.log("[${InstagramFeatureState.TAG}|ActivityHistory] native list cell unavailable: ${it.javaClass.simpleName}")
        }.getOrNull()
    }

    private fun interactiveTileWrapper(state: State, item: InstagramActivityHistoryStore.Item, content: View, square: Boolean): View {
        val context = state.context
        val wrapper = FrameLayout(context).apply {
            isClickable = true
            isFocusable = true
            addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            if (square) minimumHeight = gridTileWidth(context)
        }
        if (state.selectionMode) {
            createNativeCheckbox(context, state.selectedKeys.contains(item.uniqueKey()))?.let { checkbox ->
                wrapper.addView(checkbox, FrameLayout.LayoutParams(dp(context, 32), dp(context, 32), Gravity.TOP or Gravity.END).apply {
                    setMargins(0, dp(context, 6), dp(context, 6), 0)
                })
            }
        }
        wireActions(wrapper, state, item)
        return wrapper
    }

    private fun createNativeCheckbox(context: Context, checked: Boolean): View? {
        return runCatching {
            val checkboxClass = Class.forName("com.instagram.igds.components.checkbox.IgdsCheckBox", true, context.classLoader)
            val checkbox = checkboxClass.getConstructor(Context::class.java).newInstance(context) as View
            if (checkbox is CompoundButton) checkbox.isChecked = checked else checkboxClass.getMethod("setChecked", java.lang.Boolean.TYPE).invoke(checkbox, checked)
            checkbox.isClickable = false
            checkbox.isFocusable = false
            checkbox
        }.getOrNull()
    }

    private fun inflateHostLayout(context: Context, layoutName: String): View? {
        val layoutId = context.resources.getIdentifier(layoutName, "layout", context.packageName)
        if (layoutId == 0) return null
        return runCatching { LayoutInflater.from(context).inflate(layoutId, null, false) }
            .onFailure { XposedBridge.log("[${InstagramFeatureState.TAG}|ActivityHistory] native layout inflate failed for $layoutName: ${it.javaClass.simpleName}") }
            .getOrNull()
    }

    private fun viewFrom(root: View?, idName: String): View? {
        root ?: return null
        val id = root.resources.getIdentifier(idName, "id", root.context.packageName)
        return if (id == 0) null else root.findViewById(id)
    }

    private fun imageFrom(root: View?, idName: String): ImageView? = viewFrom(root, idName) as? ImageView

    private fun textFrom(root: View?, idName: String): TextView? = viewFrom(root, idName) as? TextView

    private fun listCellIcon(cell: View): ImageView? {
        return runCatching { cell.javaClass.getField("iconView").get(cell) as? ImageView }.getOrNull()
    }

    private fun listCellTitle(cell: View): TextView? {
        return runCatching { cell.javaClass.getField("A07").get(cell) as? TextView }.getOrNull()
    }

    private fun setNativeListCellType(cell: View, enumName: String) {
        runCatching {
            val enumClass = Class.forName("p000X.EnumC44270HJb", true, cell.context.classLoader)
            val value = enumClass.getField(enumName).get(null)
            cell.javaClass.getMethod("setTextCellType", enumClass).invoke(cell, value)
        }
    }

    private fun createStoryGroupHeader(state: State, label: String, count: Int): View {
        if (state.preferNativeUi) {
            val header = createNativeListCell(state.context, label, "$count ${if (count == 1) "story" else "stories"}", false, false)
            if (header != null) {
                setNativeListCellType(header, "A0B")
                header.isClickable = false
                header.isFocusable = false
                return header
            }
        }
        return label(state.context, "$label - $count ${if (count == 1) "story" else "stories"}", 15, TEXT, true).apply {
            setPadding(dp(state.context, 8), dp(state.context, 18), dp(state.context, 8), dp(state.context, 6))
        }
    }

    private fun createEmptyView(state: State, message: String): View {
        if (state.preferNativeUi) {
            val empty = createNativeEmptyView(state.context, message)
            if (empty != null) return empty
        }
        return label(state.context, message, 15, MUTED, false).apply {
            gravity = Gravity.CENTER
            minimumHeight = dp(state.context, 240)
        }
    }

    private fun createNativeEmptyView(context: Context, message: String): View? {
        return runCatching {
            val emptyClass = Class.forName("com.instagram.igds.components.emptystate.IgdsEmptyState", true, context.classLoader)
            val empty = emptyClass.getConstructor(Context::class.java).newInstance(context) as View
            emptyClass.getMethod("setHeadline", CharSequence::class.java).invoke(empty, message)
            empty.minimumHeight = dp(context, 240)
            empty
        }.onFailure {
            XposedBridge.log("[${InstagramFeatureState.TAG}|ActivityHistory] native empty state unavailable: ${it.javaClass.simpleName}")
        }.getOrNull()
    }

    private fun createGridCard(state: State, item: InstagramActivityHistoryStore.Item): View {
        val context = state.context
        val selected = state.selectionMode && state.selectedKeys.contains(item.uniqueKey())
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(context, 10))
            background = rounded(if (selected) Color.rgb(10, 31, 48) else SURFACE, dp(context, 4), dp(context, if (selected) 2 else 1), if (selected) ACCENT else DIVIDER)
        }
        wireActions(card, state, item)
        val media = FrameLayout(context).apply { setBackgroundColor(SURFACE_2) }
        val image = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(SURFACE_2)
        }
        media.addView(image, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        loadThumbnailAsync(image, item)
        if (selected) {
            media.addView(selectionBadge(context), FrameLayout.LayoutParams(dp(context, 32), dp(context, 28), Gravity.TOP or Gravity.END).apply {
                setMargins(0, dp(context, 8), dp(context, 8), 0)
            })
        }
        card.addView(media, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, gridTileWidth(context)))
        val primary = label(context, primaryText(item), 14, TEXT, true).apply {
            setPadding(dp(context, 10), dp(context, 9), dp(context, 10), 0)
            maxLines = 2
        }
        card.addView(primary)
        loadPageNameAsync(primary, item)
        card.addView(label(context, secondaryText(item), 13, MUTED, false).apply {
            setPadding(dp(context, 10), dp(context, 4), dp(context, 10), 0)
            maxLines = 2
        })
        card.addView(label(context, formatTime(item.timestamp), 12, MUTED, false).apply {
            setPadding(dp(context, 10), dp(context, 5), dp(context, 10), 0)
        })
        return card
    }

    private fun createPageRow(state: State, item: InstagramActivityHistoryStore.Item): View {
        val context = state.context
        val selected = state.selectionMode && state.selectedKeys.contains(item.uniqueKey())
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10))
            background = rounded(if (selected) Color.rgb(10, 31, 48) else BG, dp(context, 4), dp(context, if (selected) 2 else 1), if (selected) ACCENT else DIVIDER)
        }
        wireActions(row, state, item)
        val image = ProfileThumbnailView(context)
        row.addView(image, LinearLayout.LayoutParams(dp(context, 56), dp(context, 56)))
        loadThumbnailAsync(image, item)
        val labels = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 12), 0, 0, 0)
        }
        val primary = label(context, primaryText(item), 16, TEXT, true).apply { maxLines = 2 }
        labels.addView(primary)
        loadPageNameAsync(primary, item)
        labels.addView(label(context, secondaryText(item), 13, MUTED, false).apply { maxLines = 2 })
        labels.addView(label(context, formatTime(item.timestamp), 12, MUTED, false))
        row.addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    private fun wireActions(view: View, state: State, item: InstagramActivityHistoryStore.Item) {
        view.isClickable = true
        view.isFocusable = true
        view.setOnClickListener {
            if (state.selectionMode) toggleSelection(state, item) else openItem(state.context, item)
        }
        view.setOnLongClickListener {
            if (!state.selectionMode) state.selectionMode = true
            toggleSelection(state, item)
            true
        }
        if (view is CompoundButton) view.setOnCheckedChangeListener(null)
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) wireActions(view.getChildAt(i), state, item)
        }
    }

    private fun handleTrashClick(state: State) {
        if (!state.selectionMode) {
            enterSelectionMode(state)
            Toast.makeText(state.context, "Tap items to select. Long-press trash to clear all.", Toast.LENGTH_SHORT).show()
            return
        }
        if (state.selectedKeys.isEmpty()) {
            exitSelectionMode(state)
            return
        }
        val count = state.selectedKeys.size
        AlertDialog.Builder(state.context)
            .setTitle("Delete selected history?")
            .setMessage("Delete $count selected ${if (count == 1) "item" else "items"}?")
            .setPositiveButton("Delete") { _, _ ->
                InstagramActivityHistoryStore.delete(state.context, state.selectedKeys.toSet())
                state.selectedKeys.clear()
                state.selectionMode = false
                updateSelectionHeader(state)
                render(state)
                Toast.makeText(state.context, "Deleted $count ${if (count == 1) "item" else "items"}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmClear(state: State) {
        AlertDialog.Builder(state.context)
            .setTitle("Clear activity history?")
            .setMessage("This deletes the locally saved history list.")
            .setPositiveButton("Clear") { _, _ ->
                InstagramActivityHistoryStore.clear(state.context)
                state.selectedKeys.clear()
                state.selectionMode = false
                updateSelectionHeader(state)
                render(state)
                Toast.makeText(state.context, "Activity history cleared", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun enterSelectionMode(state: State) {
        state.selectionMode = true
        updateSelectionHeader(state)
        render(state)
    }

    private fun exitSelectionMode(state: State) {
        state.selectionMode = false
        state.selectedKeys.clear()
        updateSelectionHeader(state)
        render(state)
    }

    private fun toggleSelection(state: State, item: InstagramActivityHistoryStore.Item) {
        val key = item.uniqueKey()
        if (key.isBlank()) return
        if (!state.selectedKeys.add(key)) state.selectedKeys.remove(key)
        updateSelectionHeader(state)
        render(state)
    }

    private fun updateSelectionHeader(state: State) {
        state.titleView?.text = if (state.selectionMode) "${state.selectedKeys.size} selected" else "Activity History"
        state.clearButton?.alpha = if (state.selectionMode && state.selectedKeys.isEmpty()) 0.55f else 1f
        when (val clear = state.clearButton) {
            is TextView -> clear.setTextColor(if (state.selectionMode) ACCENT else TEXT)
            is ImageView -> clear.setColorFilter(if (state.selectionMode) ACCENT else TEXT)
        }
    }

    private fun openItem(context: Context, item: InstagramActivityHistoryStore.Item) {
        val url = InstagramActivityHistoryStore.buildOpenUrl(item)
        if (url.isBlank()) {
            Toast.makeText(context, "No link available", Toast.LENGTH_SHORT).show()
            return
        }
        if (item.type == InstagramActivityHistoryStore.TYPE_STORY && url.startsWith("file://")) {
            showSavedStoryPreview(context, url)
            return
        }
        InstagramActivityHistoryHooks.suppressOpenLogging()
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                setPackage(context.packageName)
                putExtra("me.eternal.purrfect.internal_intent", true)
                if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }.onFailure {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                putExtra("me.eternal.purrfect.internal_intent", true)
                if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
    }

    private fun showSavedStoryPreview(context: Context, url: String) {
        val uri = Uri.parse(url)
        val lower = url.lowercase(Locale.US)
        runCatching {
            val dialog = Dialog(activityFromContext(context) ?: context)
            dialog.setTitle("Saved story")
            if (lower.endsWith(".mp4")) {
                dialog.setContentView(VideoView(context).apply {
                    setVideoURI(uri)
                    setMediaController(MediaController(context).also { it.setAnchorView(this) })
                    setOnPreparedListener { start() }
                })
            } else {
                dialog.setContentView(ImageView(context).apply {
                    setImageURI(uri)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setBackgroundColor(Color.BLACK)
                })
            }
            dialog.show()
            dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }.onFailure {
            Toast.makeText(context, "Could not open saved story", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadThumbnailAsync(image: ImageView, item: InstagramActivityHistoryStore.Item) {
        val urls = InstagramActivityHistoryStore.buildThumbnailUrls(item)
        val tag = "${item.uniqueKey()}:${item.timestamp}:$urls"
        image.tag = tag
        val cachedBitmap = cachedBitmapFor(item, urls)
        cachedBitmap?.let {
            image.clearColorFilter()
            image.setImageBitmap(it)
            if (item.type != InstagramActivityHistoryStore.TYPE_PAGE) return
        }
        if (cachedBitmap == null) image.setImageDrawable(null)
        imageExecutor.submit {
            val cacheKey = item.uniqueKey()
            var bitmap: Bitmap? = null
            val lock = thumbnailLocks.computeIfAbsent(cacheKey) { Any() }
            try {
                synchronized(lock) {
                    bitmap = cachedBitmapFor(item, urls)
                    val resolvedUrl = resolvedThumbnails[cacheKey].orEmpty()
                    if (bitmap == null) resolvedUrl.takeIf { it.isNotBlank() }?.let { bitmap = decodeThumbnail(image.context, it) }
                    if (bitmap == null) bitmap = urls.firstNotNullOfOrNull { decodeThumbnail(image.context, it) }
                    if (bitmap == null || (item.type == InstagramActivityHistoryStore.TYPE_PAGE && resolvedUrl.isBlank())) {
                        val resolved = resolveRemoteThumbnailUrl(image.context, item)
                        if (resolved.isNotBlank() && resolved != item.thumbnailUrl) {
                            resolvedThumbnails[cacheKey] = resolved
                            persistResolvedThumbnail(image.context, item, resolved)
                            decodeThumbnail(image.context, resolved)?.let { bitmap = it }
                        }
                    }
                }
            } finally {
                thumbnailLocks.remove(cacheKey)
            }
            Handler(Looper.getMainLooper()).post {
                if (image.tag != tag) return@post
                val finalBitmap = bitmap
                if (finalBitmap != null) {
                    image.clearColorFilter()
                    image.setImageBitmap(finalBitmap)
                } else {
                    image.setImageResource(android.R.drawable.ic_menu_report_image)
                    image.setColorFilter(MUTED)
                }
            }
        }
    }

    private fun cachedBitmapFor(item: InstagramActivityHistoryStore.Item, urls: List<String>): Bitmap? {
        getCachedBitmap(resolvedThumbnails[item.uniqueKey()])?.let { return it }
        urls.forEach { getCachedBitmap(it)?.let { bitmap -> return bitmap } }
        return null
    }

    private fun decodeThumbnail(context: Context, url: String): Bitmap? {
        if (url.isBlank()) return null
        getCachedBitmap(url)?.let { return it }
        return runCatching {
            if (url.startsWith("file://")) {
                val path = Uri.parse(url).path.orEmpty()
                var bitmap = BitmapFactory.decodeFile(path)
                if (bitmap == null && isVideoPath(path)) bitmap = decodeVideoFrame(path)
                if (bitmap != null) putCachedBitmap(url, bitmap)
                return@runCatching bitmap
            }
            decodeDiskCachedThumbnail(context, url)?.let {
                putCachedBitmap(url, it)
                return@runCatching it
            }
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 4500
            connection.readTimeout = 4500
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36")
            connection.setRequestProperty("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
            connection.setRequestProperty("Referer", "https://www.instagram.com/")
            try {
                if (connection.responseCode !in 200..299) return@runCatching null
                val bytes = connection.inputStream.use { readBytes(it, 4_000_000) }
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bitmap != null) {
                    putCachedBitmap(url, bitmap)
                    saveDiskCachedThumbnail(context, url, bytes)
                }
                bitmap
            } finally {
                connection.disconnect()
            }
        }.getOrElse {
            XposedBridge.log("[${InstagramFeatureState.TAG}|ActivityHistory] thumbnail exception ${it.javaClass.simpleName}: ${shortUrl(url)}")
            null
        }
    }

    private fun decodeVideoFrame(path: String): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path)
            retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun getCachedBitmap(url: String?): Bitmap? {
        if (url.isNullOrBlank()) return null
        synchronized(bitmapCache) { return bitmapCache.get(url) }
    }

    private fun putCachedBitmap(url: String, bitmap: Bitmap?) {
        if (url.isBlank() || bitmap == null) return
        synchronized(bitmapCache) { bitmapCache.put(url, bitmap) }
    }

    private fun decodeDiskCachedThumbnail(context: Context, url: String): Bitmap? {
        val file = diskThumbnailFile(context, url) ?: return null
        if (!file.isFile || file.length() <= 0L) return null
        return runCatching {
            BitmapFactory.decodeFile(file.absolutePath).also { if (it == null) file.delete() }
        }.getOrNull()
    }

    private fun saveDiskCachedThumbnail(context: Context, url: String, bytes: ByteArray) {
        val file = diskThumbnailFile(context, url) ?: return
        runCatching {
            file.parentFile?.mkdirs()
            val temp = File(file.absolutePath + ".tmp")
            FileOutputStream(temp).use { it.write(bytes) }
            if (!temp.renameTo(file)) {
                FileOutputStream(file).use { it.write(bytes) }
                temp.delete()
            }
            trimDiskCache(context)
        }
    }

    private fun diskThumbnailFile(context: Context, url: String): File? {
        if (url.isBlank() || url.startsWith("file://")) return null
        return runCatching { File(File(context.cacheDir, "instaeclipse_activity_thumbnails"), "${sha256(url)}.img") }.getOrNull()
    }

    private fun trimDiskCache(context: Context) {
        val now = System.currentTimeMillis()
        if (now - lastCacheTrimMs < 60_000L) return
        lastCacheTrimMs = now
        runCatching {
            val files = File(context.cacheDir, "instaeclipse_activity_thumbnails").listFiles() ?: return
            if (files.size <= 450) return
            files.sortByDescending { it.lastModified() }
            var bytes = 0L
            files.forEachIndexed { index, file ->
                bytes += maxOf(0L, file.length())
                if (index >= 450 || bytes > 96L * 1024L * 1024L) file.delete()
            }
        }
    }

    private fun resolveRemoteThumbnailUrl(context: Context, item: InstagramActivityHistoryStore.Item): String {
        return when (item.type) {
            InstagramActivityHistoryStore.TYPE_PAGE -> resolveProfilePictureUrl(context, item.username)
            InstagramActivityHistoryStore.TYPE_POST, InstagramActivityHistoryStore.TYPE_REEL -> resolveInstagramHtmlThumbnail(InstagramActivityHistoryStore.buildOpenUrl(item))
            else -> ""
        }
    }

    private fun resolveProfilePictureUrl(context: Context, username: String): String {
        if (username.isBlank()) return ""
        val key = username.lowercase(Locale.US)
        resolvedPageThumbnails[key]?.takeIf { it.isNotBlank() }?.let { return it }
        InstagramActivityHistoryHooks.cachedProfileThumbnail(username)?.let {
            resolvedPageThumbnails[key] = it
            return it
        }
        val authenticated = InstagramFollowerListLogger.resolveProfileForActivityHistory(context, username)
        if (authenticated.thumbnailUrl.isNotBlank()) {
            resolvedPageThumbnails[key] = authenticated.thumbnailUrl
            if (isUsefulPageName(authenticated.displayName, username)) resolvedPageNames[key] = authenticated.displayName
            return authenticated.thumbnailUrl
        }
        val profile = resolveEmbedProfile(context, username)
        if (profile.thumbnailUrl.isNotBlank()) return profile.thumbnailUrl
        return runCatching {
            listOf(
                "https://www.instagram.com/api/v1/users/web_profile_info/?username=${Uri.encode(username)}" to "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36",
                "https://i.instagram.com/api/v1/users/web_profile_info/?username=${Uri.encode(username)}" to "Instagram 219.0.0.12.117 Android"
            ).firstNotNullOfOrNull { (url, userAgent) ->
                val json = fetchText(url, userAgent)
                cleanResolvedUrl(firstMatch(json, "\"profile_pic_url_hd\"\\s*:\\s*\"(https:[^\"]+)\"")
                    .ifBlank { firstMatch(json, "\"profile_pic_url\"\\s*:\\s*\"(https:[^\"]+)\"") })
                    .takeIf { it.isNotBlank() }
            }.orEmpty().also { if (it.isNotBlank()) resolvedPageThumbnails[key] = it }
        }.getOrDefault("")
    }

    private fun resolveEmbedProfile(context: Context, username: String): EmbedProfile {
        val key = username.lowercase(Locale.US)
        val cachedName = resolvedPageNames[key]
        val cachedThumb = resolvedPageThumbnails[key]
        if (cachedName != null && !cachedThumb.isNullOrBlank()) return EmbedProfile(cachedThumb, cachedName)
        val authenticated = InstagramFollowerListLogger.resolveProfileForActivityHistory(context, username)
        if (authenticated.thumbnailUrl.isNotBlank()) resolvedPageThumbnails[key] = authenticated.thumbnailUrl
        if (isUsefulPageName(authenticated.displayName, username)) resolvedPageNames[key] = authenticated.displayName
        if (authenticated.thumbnailUrl.isNotBlank() || isUsefulPageName(authenticated.displayName, username)) {
            return EmbedProfile(authenticated.thumbnailUrl.ifBlank { cachedThumb.orEmpty() }, authenticated.displayName.ifBlank { cachedName.orEmpty() })
        }
        return runCatching {
            val html = normalizeJsonEscapes(fetchText("https://www.instagram.com/$username/embed/", "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36"))
            val thumb = cleanResolvedUrl(firstMatch(html, "\"profile_pic_url_hd\"\\s*:\\s*\"(https:[^\"]+)\"")
                .ifBlank { firstMatch(html, "\"profile_pic_url\"\\s*:\\s*\"(https:[^\"]+)\"") }
                .ifBlank { firstMatch(html, "property=[\"']og:image[\"'][^>]*content=[\"']([^\"']+)") })
            val display = stripInstagramProfileTitle(cleanDisplayText(firstMatch(html, "\"full_name\"\\s*:\\s*\"([^\"]*)\"")
                .ifBlank { firstMatch(html, "property=[\"']og:title[\"'][^>]*content=[\"']([^\"']+)") }), username)
            if (thumb.isNotBlank()) resolvedPageThumbnails[key] = thumb
            if (isUsefulPageName(display, username)) resolvedPageNames[key] = display
            EmbedProfile(thumb.ifBlank { cachedThumb.orEmpty() }, display.ifBlank { cachedName.orEmpty() })
        }.getOrDefault(EmbedProfile("", ""))
    }

    private fun resolveInstagramHtmlThumbnail(pageUrl: String): String {
        if (pageUrl.isBlank()) return ""
        return runCatching {
            val html = normalizeJsonEscapes(fetchText(pageUrl, "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36"))
            cleanResolvedUrl(firstMatch(html, "\"thumbnail_url\"\\s*:\\s*\"([^\"]+)\"")
                .ifBlank { firstMatch(html, "\"display_url\"\\s*:\\s*\"([^\"]+)\"") }
                .ifBlank { firstMatch(html, "property=[\"']og:image[\"'][^>]*content=[\"']([^\"']+)") }
                .ifBlank { firstMatch(html, "content=[\"']([^\"']+)[\"'][^>]*property=[\"']og:image[\"']") })
        }.getOrDefault("")
    }

    private fun fetchText(url: String, userAgent: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        val host = runCatching { URL(url).host.lowercase(Locale.US) }.getOrDefault("")
        val webRequest = host.contains("instagram.com") && !host.startsWith("i.")
        connection.connectTimeout = 4500
        connection.readTimeout = 4500
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", userAgent)
        connection.setRequestProperty("Accept", "text/html,application/json,*/*")
        connection.setRequestProperty("X-IG-App-ID", if (webRequest) "936619743392459" else "567067343352427")
        connection.setRequestProperty("X-ASBD-ID", "198387")
        connection.setRequestProperty("X-IG-WWW-Claim", "0")
        connection.setRequestProperty("X-Requested-With", "XMLHttpRequest")
        connection.setRequestProperty("Accept-Language", Locale.getDefault().toLanguageTag())
        instagramCookieHeader().takeIf { it.isNotBlank() }?.let { cookie ->
            connection.setRequestProperty("Cookie", cookie)
            Regex("""(?:^|;\s*)csrftoken=([^;]+)""").find(cookie)?.groupValues?.getOrNull(1)?.let {
                connection.setRequestProperty("X-CSRFToken", it)
            }
        }
        connection.setRequestProperty("Referer", "https://www.instagram.com/")
        if (webRequest) connection.setRequestProperty("Origin", "https://www.instagram.com")
        return try {
            if (connection.responseCode !in 200..299) return ""
            connection.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun instagramCookieHeader(): String {
        return runCatching { CookieManager.getInstance().getCookie("https://www.instagram.com/") }
            .getOrNull()
            .orEmpty()
    }

    private fun loadPageNameAsync(target: TextView, item: InstagramActivityHistoryStore.Item) {
        if (item.type != InstagramActivityHistoryStore.TYPE_PAGE || item.username.isBlank()) return
        if (isUsefulPageName(target.text?.toString().orEmpty(), item.username)) return
        val tag = "page-name:${item.username.lowercase(Locale.US)}:${item.timestamp}"
        target.tag = tag
        imageExecutor.submit {
            val profile = resolveEmbedProfile(target.context, item.username)
            val display = profile.displayName
            if (!isUsefulPageName(display, item.username)) return@submit
            InstagramActivityHistoryStore.record(target.context, item.copy(type = InstagramActivityHistoryStore.TYPE_PAGE, title = display))
            Handler(Looper.getMainLooper()).post {
                if (target.tag == tag) target.text = display
            }
        }
    }

    private fun persistResolvedThumbnail(context: Context, item: InstagramActivityHistoryStore.Item, thumbnailUrl: String) {
        if (thumbnailUrl.isBlank() || thumbnailUrl == item.thumbnailUrl) return
        InstagramActivityHistoryStore.record(context, item.copy(thumbnailUrl = thumbnailUrl))
    }

    private fun primaryText(item: InstagramActivityHistoryStore.Item): String {
        if (item.type == InstagramActivityHistoryStore.TYPE_PAGE) {
            resolvedPageNames[item.username.lowercase(Locale.US)]?.takeIf { isUsefulPageName(it, item.username) }?.let { return it }
            stripInstagramProfileTitle(item.title, item.username).takeIf { isUsefulPageName(it, item.username) }?.let { return it }
            stripInstagramProfileTitle(item.caption, item.username).takeIf { isUsefulPageName(it, item.username) }?.let { return it }
            return item.username.takeIf { it.isNotBlank() }?.let { "@$it" } ?: "Page"
        }
        if (item.type == InstagramActivityHistoryStore.TYPE_FOLLOWER) {
            return item.title.takeIf { it.isNotBlank() && it != "Follower" } ?: item.username.takeIf { it.isNotBlank() }?.let { "@$it" } ?: "Follower"
        }
        return item.username.takeIf { it.isNotBlank() }?.let { "@$it" } ?: item.typeLabel()
    }

    private fun secondaryText(item: InstagramActivityHistoryStore.Item): String {
        if (item.type == InstagramActivityHistoryStore.TYPE_PAGE || item.type == InstagramActivityHistoryStore.TYPE_FOLLOWER) {
            if (item.username.isNotBlank()) return "@${item.username}"
            if (item.url.isNotBlank()) return item.url
            return item.source
        }
        if ((item.type == InstagramActivityHistoryStore.TYPE_POST || item.type == InstagramActivityHistoryStore.TYPE_REEL) &&
            item.caption.isNotBlank() && !isBadHistoryText(item.caption)
        ) return cleanDisplayText(item.caption)
        if (item.title.isNotBlank() && !isBadHistoryText(item.title)) return cleanDisplayText(item.title)
        if (item.caption.isNotBlank() && !isBadHistoryText(item.caption)) return cleanDisplayText(item.caption)
        return item.source
    }

    private fun isBadHistoryText(value: String): Boolean {
        val lower = cleanDisplayText(value).lowercase(Locale.US)
        return lower.startsWith("<?xml") || lower.startsWith("<!doctype") || lower.startsWith("<html") ||
            lower.contains("<?xml version") || lower.contains("encoding=\"utf-8\"") || lower.contains("encoding='utf-8'") ||
            lower.contains("<head>") || lower.contains("</html>")
    }

    private fun stripInstagramProfileTitle(value: String, username: String): String {
        var clean = cleanDisplayText(value)
        clean = clean.replace(Regex("\\s*\\(@$username\\)\\s*.*$", RegexOption.IGNORE_CASE), "")
        clean = clean.replace(Regex("\\s*\\|\\s*Instagram.*$", RegexOption.IGNORE_CASE), "")
        clean = clean.replace(Regex("\\s*on Instagram.*$", RegexOption.IGNORE_CASE), "")
        return clean.trim()
    }

    private fun isUsefulPageName(value: String, username: String): Boolean {
        if (value.isBlank()) return false
        val clean = cleanDisplayText(value)
        val lower = clean.lowercase(Locale.US)
        if (lower.startsWith("login") && lower.contains("instagram")) return false
        val cleanUsername = username.trim()
        if (cleanUsername.isBlank()) return true
        if (!cleanUsername.equals("instagram", true) && clean.equals("instagram", true)) return false
        return !clean.equals(cleanUsername, true) && !clean.equals("@$cleanUsername", true) && !clean.equals("Page", true)
    }

    private fun cleanDisplayText(value: String): String {
        return value.replace("\\n", "\n")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .trim()
    }

    private fun normalizeSearch(value: String): String {
        val decomposed = Normalizer.normalize(cleanDisplayText(value), Normalizer.Form.NFD)
        return decomposed.replace(Regex("\\p{M}+"), "")
            .lowercase(Locale.US)
            .replace(Regex("[^a-z0-9_@. -]+"), " ")
            .trim()
    }

    private fun normalizeJsonEscapes(value: String): String {
        return value.replace("\\/", "/").replace("\\u0026", "&").replace("\\u003d", "=").replace("\\u0025", "%")
    }

    private fun cleanResolvedUrl(value: String): String {
        if (value.isBlank()) return ""
        var clean = cleanDisplayText(value).replace("\\/", "/")
        repeat(2) {
            clean = runCatching { URLDecoder.decode(clean, StandardCharsets.UTF_8.name()) }.getOrDefault(clean)
        }
        return clean.substringBefore("&amp;").trim()
    }

    private fun firstMatch(value: String, regex: String): String {
        return runCatching { Regex(regex, setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(value)?.groupValues?.getOrNull(1).orEmpty() }.getOrDefault("")
    }

    private fun refreshTabs(state: State) {
        if (state.nativeTabs != null) {
            selectNativeTab(state, nativeTabIndex(state.type))
            return
        }
        state.tabs.forEach { refreshTab(state, it.type, it.view) }
    }

    private fun refreshTab(state: State, type: String, tab: TextView) {
        val active = state.type == type
        tab.setTextColor(if (active) TEXT else MUTED)
        tab.typeface = Typeface.defaultFromStyle(if (active) Typeface.BOLD else Typeface.NORMAL)
        tab.setBackgroundColor(BG)
        val underline = ColorDrawable(if (active) TEXT else Color.TRANSPARENT).apply {
            setBounds(0, 0, dp(state.context, 38), dp(state.context, 2))
        }
        tab.compoundDrawablePadding = dp(state.context, 7)
        tab.setCompoundDrawables(null, null, null, underline)
    }

    private fun label(context: Context, value: String, sp: Int, color: Int, bold: Boolean): TextView {
        return TextView(context).apply {
            text = value
            setTextColor(color)
            textSize = sp.toFloat()
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }
    }

    private fun actionLabel(context: Context, value: String, sp: Int): TextView {
        return label(context, value, sp, TEXT, true).apply {
            gravity = Gravity.CENTER
            isClickable = true
        }
    }

    private fun selectionBadge(context: Context): TextView {
        return label(context, "\u2713", 16, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            background = rounded(ACCENT, dp(context, 14), dp(context, 2), Color.WHITE)
        }
    }

    private fun rounded(color: Int, radius: Int, strokeWidth: Int, strokeColor: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius.toFloat()
            if (strokeWidth > 0) setStroke(strokeWidth, strokeColor)
        }
    }

    private fun gridTileWidth(context: Context): Int = maxOf(dp(context, 96), context.resources.displayMetrics.widthPixels / 3)

    private fun formatTime(timestamp: Long): String = SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.US).format(Date(timestamp))

    private fun searchContainer(state: State): View? = state.searchContainer ?: state.searchBox

    private fun nativeActionParams(context: Context): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(dp(context, 48), dp(context, 48))

    private fun nativeActionIcon(context: Context, drawableName: String): ImageView {
        return ImageView(context).apply {
            drawableId(context, drawableName).takeIf { it != 0 }?.let(::setImageResource)
            scaleType = ImageView.ScaleType.CENTER
            setColorFilter(TEXT)
            isClickable = true
            isFocusable = true
        }
    }

    private fun drawableId(context: Context, name: String): Int {
        if (name.isBlank()) return 0
        return context.resources.getIdentifier(name, "drawable", context.packageName)
    }

    private fun nativeTabIndex(type: String): Int {
        return when (type) {
            InstagramActivityHistoryStore.TYPE_STORY -> 1
            InstagramActivityHistoryStore.TYPE_REEL -> 2
            InstagramActivityHistoryStore.TYPE_PAGE -> 3
            InstagramActivityHistoryStore.TYPE_FOLLOWER -> 4
            else -> 0
        }
    }

    private fun selectNativeTab(state: State, index: Int) {
        runCatching { state.nativeTabs?.javaClass?.getMethod("A01", java.lang.Integer.TYPE)?.invoke(state.nativeTabs, index, false) }
    }

    private fun activityFromContext(context: Context?): Activity? {
        var current = context
        while (current is ContextWrapper) {
            if (current is Activity) return current
            current = current.baseContext
        }
        return current as? Activity
    }

    private fun dp(context: Context, value: Int): Int = (value * context.resources.displayMetrics.density).roundToInt()

    private fun readBytes(input: InputStream, maxBytes: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            total += read
            if (total > maxBytes) throw java.io.IOException("thumbnail too large")
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun isVideoPath(path: String): Boolean {
        val lower = path.lowercase(Locale.US)
        return lower.endsWith(".mp4") || lower.endsWith(".mov") || lower.endsWith(".m4v") || lower.endsWith(".webm")
    }

    private fun sha256(value: String): String {
        return MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private fun shortUrl(url: String): String = if (url.length <= 120) url else url.take(117) + "..."

    private class ProfileThumbnailView(context: Context) : ImageView(context) {
        private val clipPath = Path()
        private val rect = RectF()
        private val placeholderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = SURFACE_2
        }

        init {
            scaleType = ScaleType.CENTER_CROP
            setBackgroundColor(Color.TRANSPARENT)
        }

        override fun onDraw(canvas: Canvas) {
            val save = canvas.save()
            rect.set(0f, 0f, width.toFloat(), height.toFloat())
            clipPath.reset()
            clipPath.addRoundRect(rect, width / 2f, height / 2f, Path.Direction.CW)
            canvas.clipPath(clipPath)
            canvas.drawRoundRect(rect, width / 2f, height / 2f, placeholderPaint)
            super.onDraw(canvas)
            canvas.restoreToCount(save)
        }
    }

    private data class State(
        val context: Context,
        val closeAction: () -> Unit,
        val preferNativeUi: Boolean,
        var type: String = InstagramActivityHistoryStore.TYPE_POST,
        var query: String = "",
        var searchVisible: Boolean = false,
        var selectionMode: Boolean = false,
        val selectedKeys: MutableSet<String> = HashSet(),
        val tabs: MutableList<TabRef> = mutableListOf(),
        var recyclerView: RecyclerView? = null,
        var adapter: Adapter? = null,
        var titleView: TextView? = null,
        var clearButton: View? = null,
        var searchBox: EditText? = null,
        var searchContainer: View? = null,
        var nativeTabs: View? = null
    )

    private data class TabRef(val type: String, val view: TextView)
    private data class ScoredItem(val item: InstagramActivityHistoryStore.Item, val score: Int)
    private data class EmbedProfile(val thumbnailUrl: String, val displayName: String)

    private sealed class Row {
        data class Card(val item: InstagramActivityHistoryStore.Item) : Row()
        data class Header(val label: String, val count: Int) : Row()
        data class Empty(val message: String) : Row()
    }

    private class Adapter(private val state: State) : RecyclerView.Adapter<Holder>() {
        private val rows = mutableListOf<Row>()

        fun submit(next: List<Row>) {
            rows.clear()
            rows.addAll(next)
            notifyDataSetChanged()
        }

        fun spanSize(position: Int): Int {
            return when (val row = rows.getOrNull(position)) {
                is Row.Card -> if (row.item.type == InstagramActivityHistoryStore.TYPE_PAGE || row.item.type == InstagramActivityHistoryStore.TYPE_FOLLOWER) 3 else 1
                else -> 3
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            return Holder(FrameLayout(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            })
        }

        override fun getItemCount(): Int = rows.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val row = rows[position]
            holder.container.removeAllViews()
            val view = when (row) {
                is Row.Card -> createItemView(state, row.item)
                is Row.Header -> createStoryGroupHeader(state, row.label, row.count)
                is Row.Empty -> createEmptyView(state, row.message)
            }
            holder.container.addView(view, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                if (row is Row.Empty) dp(state.context, 240) else ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            (holder.container.layoutParams as? ViewGroup.MarginLayoutParams)?.let { params ->
                when {
                    row is Row.Card && row.item.type != InstagramActivityHistoryStore.TYPE_PAGE && row.item.type != InstagramActivityHistoryStore.TYPE_FOLLOWER -> {
                        val margin = if (state.preferNativeUi) dp(state.context, 1) else dp(state.context, 6)
                        params.setMargins(margin, margin, margin, margin)
                    }
                    row is Row.Header -> params.setMargins(0, dp(state.context, 8), 0, 0)
                    else -> params.setMargins(0, 0, 0, 0)
                }
                holder.container.layoutParams = params
            }
        }
    }

    private class Holder(val container: FrameLayout) : RecyclerView.ViewHolder(container)
}

internal object InstagramActivityHistoryStore {
    const val TYPE_POST = "post"
    const val TYPE_STORY = "story"
    const val TYPE_REEL = "reel"
    const val TYPE_PAGE = "page"
    const val TYPE_FOLLOWER = "follower"

    private const val PREF_NAME = "purrfect_instagram_prefs"
    private const val LEGACY_PREF_NAME = "instaeclipse_prefs"
    private const val KEY_HISTORY = "activityHistoryJson"
    private const val MAX_ITEMS = 500
    private const val SHORTCODE_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    private val writeExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "PurrfectInstaHistoryStore").apply { isDaemon = true }
    }
    private val pendingRecordKeys = ConcurrentHashMap.newKeySet<String>()

    fun record(context: Context, rawItem: Item) {
        val item = rawItem.normalized()
        if (item.type.isBlank()) return
        if (!item.isDisplayable()) return
        val key = item.uniqueKey()
        if (key.isBlank()) return
        val app = context.applicationContext ?: context
        if (!pendingRecordKeys.add(key)) return
        writeExecutor.execute {
            try {
                synchronized(this) {
                    recordLocked(app, item, key)
                }
            } finally {
                pendingRecordKeys.remove(key)
            }
        }
    }

    private fun recordLocked(context: Context, item: Item, key: String) {
        val old = readRawArray(context)
        val next = JSONArray()
        var foundExisting = false
        for (i in 0 until old.length()) {
            val existingJson = old.optJSONObject(i)
            val existing = Item.fromJson(existingJson)
            when {
                existing == null -> if (existingJson != null && next.length() < MAX_ITEMS) next.put(existingJson)
                existing.uniqueKey() == key -> {
                    foundExisting = true
                    next.put(existing.mergeMissingFrom(item).toJson())
                }
                next.length() < MAX_ITEMS -> next.put(existingJson)
            }
        }
        val stored = if (foundExisting) {
            next
        } else {
            JSONArray().apply {
                put(item.toJson())
                for (i in 0 until next.length()) {
                    if (length() >= MAX_ITEMS) break
                    put(next.optJSONObject(i))
                }
            }
        }
        writeRawArray(context, stored)
    }

    @Synchronized
    fun read(context: Context): List<Item> {
        val out = mutableListOf<Item>()
        val raw = readRawArray(context)
        for (i in 0 until raw.length()) {
            Item.fromJson(raw.optJSONObject(i))?.takeIf { it.isDisplayable() }?.let { out += it }
        }
        return out
    }

    @Synchronized
    fun query(context: Context, type: String, query: String): List<Item> {
        val normalizedType = normalize(type)
        val normalizedQuery = normalize(query)
        return read(context).filter { item ->
            (normalizedType.isBlank() || normalizedType == item.type) &&
                (normalizedQuery.isBlank() || item.matches(normalizedQuery))
        }
    }

    @Synchronized
    fun clear(context: Context) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit().remove(KEY_HISTORY).apply()
        context.getSharedPreferences(LEGACY_PREF_NAME, Context.MODE_PRIVATE).edit().remove(KEY_HISTORY).apply()
    }

    @Synchronized
    fun delete(context: Context, uniqueKeys: Set<String>) {
        if (uniqueKeys.isEmpty()) return
        val old = readRawArray(context)
        val next = JSONArray()
        for (i in 0 until old.length()) {
            val existing = old.optJSONObject(i) ?: continue
            val item = Item.fromJson(existing)
            if (item != null && item.uniqueKey() in uniqueKeys) continue
            next.put(existing)
        }
        writeRawArray(context, next)
    }

    fun buildOpenUrl(item: Item): String {
        if (item.type == TYPE_POST || item.type == TYPE_REEL) {
            val mediaUrl = canonicalMediaUrl(item.type, item.mediaId, item.url)
            if (mediaUrl.isNotBlank()) return mediaUrl
        }
        if (item.type == TYPE_STORY) {
            if (item.url.isNotBlank() && !isApiOrCdnUrl(item.url)) return item.url
            if (isUsernameLike(item.username) && item.mediaId.isNotBlank()) {
                return "https://www.instagram.com/stories/${item.username}/${cleanStoryId(item.mediaId)}/"
            }
            return ""
        }
        if (item.type == TYPE_PAGE) {
            if (item.url.isNotBlank() && !isApiOrCdnUrl(item.url)) return item.url
            if (isUsernameLike(item.username)) return "https://www.instagram.com/${item.username}/"
            return ""
        }
        if (item.type == TYPE_FOLLOWER) {
            if (isUsernameLike(item.username)) return "https://www.instagram.com/${item.username}/"
            if (item.url.isNotBlank() && !isApiOrCdnUrl(item.url)) return item.url
            return ""
        }
        return item.url.takeUnless { isApiOrCdnUrl(it) }.orEmpty()
    }

    fun buildThumbnailUrl(item: Item): String = buildThumbnailUrls(item).firstOrNull().orEmpty()

    fun buildThumbnailUrls(item: Item): List<String> {
        val urls = mutableListOf<String>()
        if (item.type != TYPE_PAGE || !isUnusablePageThumbnail(item.thumbnailUrl)) {
            addUniqueUrl(urls, item.thumbnailUrl)
        }
        addUniqueUrl(urls, buildCanonicalThumbnailUrl(item))
        if (item.type == TYPE_REEL) {
            val code = publicCodeFromIdOrUrl(item.mediaId, item.url)
            if (code.isNotBlank()) addUniqueUrl(urls, "https://www.instagram.com/p/$code/media/?size=m")
        }
        return urls
    }

    fun buildCanonicalThumbnailUrl(item: Item): String {
        if (item.type == TYPE_STORY) {
            if (item.url.startsWith("file://")) return item.url
            return ""
        }
        if (item.type == TYPE_POST || item.type == TYPE_REEL) {
            val mediaUrl = canonicalMediaUrl(item.type, item.mediaId, item.url)
            if (mediaUrl.isNotBlank()) return "${mediaUrl}media/?size=m"
        }
        return ""
    }

    fun canonicalMediaUrl(type: String, mediaIdOrCode: String, fallbackUrl: String): String {
        val code = publicCodeFromIdOrUrl(mediaIdOrCode, fallbackUrl)
        if (code.isBlank()) return ""
        return if (type == TYPE_REEL) "https://www.instagram.com/reel/$code/" else "https://www.instagram.com/p/$code/"
    }

    fun publicCodeFromIdOrUrl(mediaIdOrCode: String, fallbackUrl: String): String {
        val direct = cleanMediaCode(mediaIdOrCode)
        if (isShortcode(direct)) return direct
        if (isNumeric(direct)) return shortcodeFromNumericMediaId(direct)
        return codeFromInstagramUrl(fallbackUrl)
    }

    private fun readRawArray(context: Context): JSONArray {
        val raw = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getString(KEY_HISTORY, null)
            ?: context.getSharedPreferences(LEGACY_PREF_NAME, Context.MODE_PRIVATE).getString(KEY_HISTORY, null)
            ?: return JSONArray()
        return runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
    }

    private fun writeRawArray(context: Context, array: JSONArray) {
        val value = array.toString()
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_HISTORY, value)
            .apply()
    }

    private fun addUniqueUrl(urls: MutableList<String>, url: String) {
        if (url.isNotBlank() && url !in urls) urls += url
    }

    private fun isOldLocalPageThumbnail(url: String): Boolean {
        val lower = url.lowercase(Locale.US)
        return lower.startsWith("file://") &&
            lower.contains("instaeclipse_activity_history") &&
            lower.contains("/pages/")
    }

    private fun isUnusablePageThumbnail(url: String): Boolean {
        val lower = url.lowercase(Locale.US)
        if (lower.isBlank()) return true
        if (isOldLocalPageThumbnail(lower)) return true
        return lower.contains("rsrc.php") ||
            lower.contains("default_profile") ||
            lower.contains("profile_default") ||
            lower.contains("anonymous_user") ||
            lower.contains("blank_profile") ||
            lower.contains("placeholder")
    }

    private fun cleanMediaCode(value: String): String {
        var clean = value.trim()
        val underscore = clean.indexOf('_')
        if (underscore > 0 && clean.substring(0, underscore).all { it.isDigit() }) {
            clean = clean.substring(0, underscore)
        }
        return clean
    }

    private fun shortcodeFromNumericMediaId(mediaId: String): String {
        return runCatching {
            var value = BigInteger(mediaId)
            if (value.signum() <= 0) return@runCatching ""
            val base = BigInteger.valueOf(64)
            val out = StringBuilder()
            while (value.signum() > 0) {
                val divRem = value.divideAndRemainder(base)
                out.append(SHORTCODE_ALPHABET[divRem[1].toInt()])
                value = divRem[0]
            }
            out.reverse().toString()
        }.getOrDefault("")
    }

    private fun codeFromInstagramUrl(url: String): String {
        if (url.isBlank() || isApiOrCdnUrl(url)) return ""
        return runCatching {
            val segments = Uri.parse(url).pathSegments
            if (segments.size < 2) return@runCatching ""
            val first = segments[0]
            if (first !in setOf("p", "reel", "reels", "tv")) return@runCatching ""
            val code = cleanMediaCode(segments[1])
            if (isShortcode(code)) code else ""
        }.getOrDefault("")
    }

    private fun cleanStoryId(value: String): String {
        val clean = cleanMediaCode(value)
        return clean.ifBlank { value }
    }

    private fun isUsernameLike(value: String): Boolean = value.matches(Regex("[A-Za-z0-9._]{1,30}"))

    private fun isShortcode(value: String): Boolean = value.isNotBlank() &&
        !isNumeric(value) &&
        value.length >= 3 &&
        value.matches(Regex("[A-Za-z0-9_-]+"))

    private fun isNumeric(value: String): Boolean = value.isNotBlank() && value.all { it.isDigit() }

    private fun isApiOrCdnUrl(url: String): Boolean {
        val lower = url.lowercase(Locale.US)
        return lower.contains("/api/v1/") ||
            lower.contains("i.instagram.com") ||
            lower.contains("cdninstagram.com") ||
            lower.contains("fbcdn.net")
    }

    private fun stableUrlKey(url: String): String {
        if (url.isBlank()) return ""
        if (url.startsWith("file://")) {
            return runCatching {
                val path = Uri.parse(url).path.orEmpty()
                path.substringAfterLast('/', path)
            }.getOrDefault(url)
        }
        return url
    }

    private fun normalize(value: String): String = value.trim().lowercase(Locale.US)

    data class Item(
        val type: String,
        val username: String,
        val title: String,
        val caption: String,
        val mediaId: String,
        val url: String,
        val thumbnailUrl: String,
        val source: String,
        val timestamp: Long
    ) {
        fun normalized(): Item {
            return copy(
                type = normalize(type),
                username = username.trim(),
                title = title.trim(),
                caption = caption.trim(),
                mediaId = mediaId.trim(),
                url = url.trim(),
                thumbnailUrl = thumbnailUrl.trim(),
                source = source.trim(),
                timestamp = if (timestamp <= 0L) System.currentTimeMillis() else timestamp
            )
        }

        fun isDisplayable(): Boolean {
            return when (type) {
                TYPE_POST, TYPE_REEL -> canonicalMediaUrl(type, mediaId, url).isNotBlank()
                TYPE_STORY -> mediaId.isNotBlank() || url.isNotBlank() || thumbnailUrl.isNotBlank() || username.isNotBlank()
                TYPE_PAGE, TYPE_FOLLOWER -> isUsernameLike(username) || (url.isNotBlank() && !isApiOrCdnUrl(url))
                else -> type.isNotBlank()
            }
        }

        fun uniqueKey(): String {
            return when {
                type == TYPE_POST || type == TYPE_REEL -> {
                    val code = publicCodeFromIdOrUrl(mediaId, url)
                    if (code.isNotBlank()) "$type:media:$code" else ""
                }
                type == TYPE_STORY && mediaId.isNotBlank() -> "$type:media:${cleanStoryId(mediaId)}"
                type == TYPE_STORY && username.isNotBlank() && title.isNotBlank() -> "$type:text:${username.lowercase(Locale.US)}:${normalize(title)}"
                type == TYPE_STORY && url.isNotBlank() -> "$type:url:${stableUrlKey(url)}"
                type == TYPE_PAGE && username.isNotBlank() -> "$type:user:${username.lowercase(Locale.US)}"
                type == TYPE_FOLLOWER && username.isNotBlank() -> "$type:user:${username.lowercase(Locale.US)}"
                mediaId.isNotBlank() -> "$type:media:$mediaId"
                url.isNotBlank() -> "$type:url:${stableUrlKey(url)}"
                username.isNotBlank() && title.isNotBlank() -> "$type:text:${username.lowercase(Locale.US)}:${normalize(title)}"
                source.isNotBlank() -> "$type:source:$source"
                else -> ""
            }
        }

        fun matches(query: String): Boolean {
            if (query.isBlank()) return true
            val haystack = normalize("$username $title $caption $mediaId ${typeLabel()}")
            return normalize(query).split(Regex("\\s+")).all { token -> token.isBlank() || haystack.contains(token) }
        }

        fun typeLabel(): String {
            return when (type) {
                TYPE_STORY -> "Story"
                TYPE_REEL -> "Reel"
                TYPE_PAGE -> "Page"
                TYPE_FOLLOWER -> "Follower"
                else -> "Post"
            }
        }

        fun mergeMissingFrom(fresh: Item): Item {
            val cleanFresh = fresh.normalized()
            return copy(
                type = firstUseful(type, cleanFresh.type),
                username = firstUseful(username, cleanFresh.username),
                title = betterExistingTitle(type, username, title, cleanFresh.title),
                caption = betterCaption(caption, cleanFresh.caption),
                mediaId = firstUseful(mediaId, cleanFresh.mediaId),
                url = firstUseful(url, cleanFresh.url),
                thumbnailUrl = betterThumbnail(type, thumbnailUrl, cleanFresh.thumbnailUrl),
                source = firstUseful(source, cleanFresh.source),
                timestamp = timestamp
            )
        }

        fun toJson(): JSONObject {
            return JSONObject()
                .put("type", type)
                .put("username", username)
                .put("title", title)
                .put("caption", caption)
                .put("mediaId", mediaId)
                .put("url", url)
                .put("thumbnailUrl", thumbnailUrl)
                .put("source", source)
                .put("timestamp", timestamp)
        }

        companion object {
            fun fromJson(json: JSONObject?): Item? {
                json ?: return null
                return Item(
                    type = json.optString("type", ""),
                    username = json.optString("username", ""),
                    title = json.optString("title", ""),
                    caption = json.optString("caption", ""),
                    mediaId = json.optString("mediaId", ""),
                    url = json.optString("url", ""),
                    thumbnailUrl = json.optString("thumbnailUrl", ""),
                    source = json.optString("source", ""),
                    timestamp = json.optLong("timestamp", 0L)
                ).normalized()
            }

            private fun firstUseful(existing: String, fresh: String): String = if (existing.isBlank()) fresh.trim() else existing.trim()

            private fun betterThumbnail(type: String, existing: String, fresh: String): String {
                val cleanExisting = existing.trim()
                val cleanFresh = fresh.trim()
                if (cleanFresh.isBlank()) return cleanExisting
                if (type == TYPE_PAGE && isUnusablePageThumbnail(cleanFresh)) return cleanExisting
                if (cleanExisting.isBlank()) return cleanFresh
                if (type == TYPE_PAGE && isUnusablePageThumbnail(cleanExisting)) return cleanFresh
                val existingRemote = isApiOrCdnUrl(cleanExisting)
                val freshRemote = isApiOrCdnUrl(cleanFresh)
                if ((type == TYPE_PAGE || type == TYPE_POST || type == TYPE_REEL) && existingRemote && freshRemote && cleanExisting != cleanFresh) {
                    return cleanFresh
                }
                return cleanExisting
            }

            private fun betterExistingTitle(type: String, username: String, existing: String, fresh: String): String {
                if (isGenericTitle(type, username, existing) && fresh.isNotBlank() && !isGenericTitle(type, username, fresh)) {
                    return fresh.trim()
                }
                return existing.ifBlank { fresh }.trim()
            }

            private fun betterCaption(existing: String, fresh: String): String {
                if (fresh.isBlank()) return existing.trim()
                if (existing.isBlank() || isBadText(existing)) return fresh.trim()
                return existing.trim()
            }

            private fun isGenericTitle(type: String, username: String, value: String): Boolean {
                if (value.isBlank()) return true
                val lower = value.trim().lowercase(Locale.US)
                if (isBadText(lower)) return true
                if (type == TYPE_PAGE) {
                    val cleanUsername = username.trim().lowercase(Locale.US)
                    return lower == "page" ||
                        (lower.startsWith("login") && lower.contains("instagram")) ||
                        (cleanUsername != "instagram" && lower == "instagram") ||
                        (cleanUsername.isNotBlank() && (lower == cleanUsername || lower == "@$cleanUsername"))
                }
                val cleanUsername = username.trim().lowercase(Locale.US)
                if (cleanUsername.isNotBlank() && (lower == cleanUsername || lower == "@$cleanUsername")) return true
                return lower == "post opened" ||
                    lower == "post watched" ||
                    lower == "reel opened" ||
                    lower == "reel watched" ||
                    lower.startsWith("post ") ||
                    lower.startsWith("reel ")
            }

            private fun isBadText(value: String): Boolean {
                val lower = value.trim().lowercase(Locale.US)
                return lower.startsWith("<?xml") ||
                    lower.startsWith("<!doctype") ||
                    lower.startsWith("<html") ||
                    lower.contains("<?xml version") ||
                    lower.contains("encoding=\"utf-8\"") ||
                    lower.contains("encoding='utf-8'") ||
                    lower.contains("<head>") ||
                    lower.contains("</html>")
            }
        }
    }
}
