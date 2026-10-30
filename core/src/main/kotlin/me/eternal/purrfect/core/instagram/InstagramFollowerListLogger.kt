package me.eternal.purrfect.core.instagram

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
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
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.webkit.CookieManager
import android.widget.FrameLayout
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import de.robv.android.xposed.XposedBridge
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.IdentityHashMap
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

internal object InstagramFollowerListLogger {
    private const val MAX_FETCHED_FOLLOWERS = 1000
    private const val MAX_FETCHED_FOLLOWING = 500
    private const val MAX_ANALYTICS_MEDIA = 72
    private const val MAX_ANALYTICS_ENGAGEMENT_MEDIA = 16
    private const val MAX_ANALYTICS_USERS_PER_MEDIA = 80
    private const val MAX_REQUEST_AUTH_PROBES = 18
    private const val ANDROID_USER_AGENT = "Instagram 326.0.0.34.120 Android"
    private const val WEB_USER_AGENT = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36"
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var cachedAuth = AuthSnapshot(null, null, emptyMap(), null)
    @Volatile private var cachedAuthUpdatedAtMs = 0L
    @Volatile private var authProbeCount = 0
    @Volatile private var nativeAuthProbeLastAtMs = 0L
    @Volatile private var requestAuthProbeLastAtMs = 0L
    @Volatile private var accountSwitchLogLastAtMs = 0L
    private val imageCache = ConcurrentHashMap<String, Bitmap>()
    private val activityProfileCache = ConcurrentHashMap<String, ActivityProfile>()
    private val activityProfileMissCache = ConcurrentHashMap<String, Long>()
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "PurrfectInstaFollowers").apply { isDaemon = true }
    }

    fun rememberPotentialAuth(source: Any?, url: String? = null) {
        if (source == null) return
        val current = cachedAuth
        val protectsNativeProfile = shouldSkipRequestAuthProbe(url)
        if (protectsNativeProfile && !shouldRunNativeAuthHintProbe()) return
        if (!protectsNativeProfile && !shouldRunRequestAuthProbe(current)) return
        if (!protectsNativeProfile) {
            if (current.hasCompleteContext && authProbeCount >= MAX_REQUEST_AUTH_PROBES) return
            authProbeCount++
            if (authProbeCount > MAX_REQUEST_AUTH_PROBES && current.hasCompleteContext) return
        }
        val found = AuthSnapshot.fromObject(
            source,
            maxObjects = if (protectsNativeProfile) 8 else 32,
            logResult = false
        )
        if (!found.hasUsableAuth) return
        val updated = if (current.userId != null && found.userId != null && current.userId != found.userId) {
            logAccountSwitch(current.userId, found.userId)
            found
        } else {
            current.merge(found)
        }
        cachedAuth = updated
        cachedAuthUpdatedAtMs = System.currentTimeMillis()
    }

    fun currentInstagramAuthHeaders(context: Context): Map<String, String> {
        val app = context.applicationContext ?: context
        val auth = cachedAuth.takeIf { it.hasUsableAuth } ?: AuthSnapshot.from(app, null)
        if (auth.hasUsableAuth) {
            cachedAuth = cachedAuth.merge(auth)
            cachedAuthUpdatedAtMs = System.currentTimeMillis()
        }
        return auth.requestHeaders()
    }

    fun fetchMediaInfoVideoUrl(context: Context, mediaId: String): String? {
        if (!isMediaInfoId(mediaId)) return null
        val auth = cachedAuth.takeIf { it.hasUsableAuth } ?: AuthSnapshot.from(context, null)
        if (!auth.hasUsableAuth) {
            log("Media info video lookup has no usable auth")
            return null
        }
        cachedAuth = cachedAuth.merge(auth)
        cachedAuthUpdatedAtMs = System.currentTimeMillis()
        val encoded = Uri.encode(mediaId)
        val urls = listOf(
            "https://i.instagram.com/api/v1/media/$encoded/info/",
            "https://i.instagram.com/api/v1/clips/media/$encoded/info/"
        )
        urls.forEach { url ->
            runCatching {
                val json = requestJson(url, auth)
                firstVideoUrlFromMediaInfo(json)
            }.onSuccess { videoUrl ->
                if (videoUrl != null) return videoUrl
            }.onFailure {
                log("Media info video lookup failed path=${URL(url).path} id=${mediaId.take(12)}: ${it.message ?: it.javaClass.simpleName}")
            }
        }
        return null
    }

    fun fetchMediaInfoDownloadUrls(context: Context, mediaId: String): List<String> {
        if (!isMediaInfoId(mediaId)) return emptyList()
        val auth = cachedAuth.takeIf { it.hasUsableAuth } ?: AuthSnapshot.from(context, null)
        if (!auth.hasUsableAuth) {
            log("Media info download lookup has no usable auth")
            return emptyList()
        }
        cachedAuth = cachedAuth.merge(auth)
        cachedAuthUpdatedAtMs = System.currentTimeMillis()
        val encoded = Uri.encode(mediaId)
        val urls = listOf(
            "https://i.instagram.com/api/v1/media/$encoded/info/",
            "https://i.instagram.com/api/v1/clips/media/$encoded/info/"
        )
        urls.forEach { url ->
            runCatching {
                val json = requestJson(url, auth)
                mediaDownloadUrlsFromInfo(json)
            }.onSuccess { mediaUrls ->
                if (mediaUrls.isNotEmpty()) {
                    log(
                        "Media info download lookup hit path=${URL(url).path} id=${mediaId.take(12)} " +
                            "count=${mediaUrls.size} types=${mediaUrls.joinToString(",") { if (looksLikeVideoUrl(it)) "video" else "image" }}"
                    )
                    return mediaUrls
                }
            }.onFailure {
                log("Media info download lookup failed path=${URL(url).path} id=${mediaId.take(12)}: ${it.message ?: it.javaClass.simpleName}")
            }
        }
        log("Media info download lookup empty id=${mediaId.take(12)}")
        return emptyList()
    }

    fun resolveProfileForActivityHistory(context: Context, username: String): ActivityProfile {
        if (!isUsername(username)) return ActivityProfile("", "")
        val key = username.lowercase(Locale.US)
        activityProfileCache[key]?.let { return it }
        activityProfileMissCache[key]?.let { lastMiss ->
            if (System.currentTimeMillis() - lastMiss < 10 * 60_000L) return ActivityProfile("", "")
        }
        val app = context.applicationContext ?: context
        val auth = cachedAuth.takeIf { it.hasUsableAuth } ?: AuthSnapshot.from(app, null)
        if (!auth.hasUsableAuth) {
            return ActivityProfile("", "")
        }
        cachedAuth = cachedAuth.merge(auth)
        cachedAuthUpdatedAtMs = System.currentTimeMillis()
        val encoded = Uri.encode(username)
        listOf(
            "https://i.instagram.com/api/v1/users/$encoded/usernameinfo/",
            "https://www.instagram.com/api/v1/users/web_profile_info/?username=$encoded",
            "https://i.instagram.com/api/v1/users/web_profile_info/?username=$encoded"
        ).forEach { url ->
            val profile = runCatching {
                val json = requestJson(url, auth)
                activityProfileFromJson(json)
            }.getOrNull()
            if (profile != null && (profile.thumbnailUrl.isNotBlank() || profile.displayName.isNotBlank())) {
                activityProfileCache[key] = profile
                return profile
            }
        }
        activityProfileMissCache[key] = System.currentTimeMillis()
        return ActivityProfile("", "")
    }

    private fun activityProfileFromJson(json: JSONObject): ActivityProfile {
        val user = json.optJSONObject("data")?.optJSONObject("user") ?: json.optJSONObject("user") ?: json
        val hd = user.optJSONObject("hd_profile_pic_url_info")?.optString("url").orEmpty()
        return ActivityProfile(
            thumbnailUrl = user.optString("profile_pic_url_hd")
                .ifBlank { hd }
                .ifBlank { user.optString("profile_pic_url") }
                .trim(),
            displayName = user.optString("full_name").trim()
        )
    }

    private fun isMediaInfoId(value: String): Boolean {
        return value.length in 8..120 && value.matches(Regex("\\d+(?:_\\d+)?"))
    }

    private fun shouldRunNativeAuthHintProbe(): Boolean {
        val now = System.currentTimeMillis()
        if (now - nativeAuthProbeLastAtMs < 1_500L) return false
        nativeAuthProbeLastAtMs = now
        return true
    }

    private fun shouldRunRequestAuthProbe(current: AuthSnapshot): Boolean {
        val now = System.currentTimeMillis()
        if (current.hasCompleteContext && now - requestAuthProbeLastAtMs < 5_000L) return false
        if (!current.hasCompleteContext && now - requestAuthProbeLastAtMs < 900L) return false
        requestAuthProbeLastAtMs = now
        return true
    }

    private fun logAccountSwitch(from: String?, to: String?) {
        val now = System.currentTimeMillis()
        if (now - accountSwitchLogLastAtMs < 30_000L) return
        accountSwitchLogLastAtMs = now
        log("Detected Instagram account switch $from -> $to")
    }

    private fun shouldSkipRequestAuthProbe(url: String?): Boolean {
        val value = url?.lowercase(Locale.US) ?: return false
        return value.contains("/friendships/") ||
            value.contains("/followers") ||
            value.contains("/following") ||
            value.contains("/feed/user/") ||
            value.contains("/users/") ||
            value.contains("profile")
    }

    fun show(activity: Activity, userSession: Any?) {
        val auth = currentAuth(activity, userSession)
        val target = resolveTargetUser(userSession, auth)
        if (target == null) {
            Toast.makeText(activity, "Instagram session is not ready yet", Toast.LENGTH_SHORT).show()
            return
        }

        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(loadingView(activity))
        dialog.show()
        dialog.window?.let { window ->
            window.setBackgroundDrawable(ColorDrawable(Color.BLACK))
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        }

        executor.execute {
            val result = runCatching {
                val followerFetch = fetchFollowers(target.userId, auth, target.expectedFollowerCount)
                val followers = followerFetch.followers
                val tracker = updateTracker(activity, target.userId, followers, followerFetch.isComplete)
                val following = runCatching { fetchFollowing(target.userId, auth) }
                    .onFailure { log("Following fetch failed: ${it.message ?: it.javaClass.simpleName}") }
                    .getOrDefault(emptyList())
                val analytics = fetchAccountAnalytics(activity, target.userId, auth, followers, following)
                FollowerPageData(
                    followers = followers,
                    tracker = tracker,
                    following = buildFollowingSummary(followers, following),
                    followerRows = buildFollowerRows(followers, following),
                    analytics = analytics,
                    partialFollowerMessage = followerFetch.partialMessage
                )
            }
            mainHandler.post {
                if (!dialog.isShowing || activity.isFinishing) return@post
                result
                    .onSuccess { page ->
                        page.followers.forEach { follower ->
                            InstagramActivityHistoryStore.record(activity, follower.toHistoryItem())
                        }
                        dialog.setContentView(contentView(activity, page, auth) { dialog.dismiss() })
                    }
                    .onFailure { throwable ->
                        dialog.dismiss()
                        log("Analytics fetch failed: ${throwable.javaClass.simpleName}: ${throwable.message}")
                        AlertDialog.Builder(activity)
                            .setTitle("Analytics")
                            .setMessage("Could not fetch analytics right now: ${throwable.message ?: "unknown error"}")
                            .setPositiveButton("OK", null)
                            .show()
                    }
            }
        }
    }

    private fun currentAuth(activity: Activity, userSession: Any?): AuthSnapshot {
        val requestAuth = cachedAuth
        val sessionAuth = AuthSnapshot.from(activity, userSession)
        val requestAuthAgeMs = System.currentTimeMillis() - cachedAuthUpdatedAtMs
        val auth = when {
            requestAuth.userId != null && sessionAuth.userId != null && requestAuth.userId != sessionAuth.userId -> {
                if (requestAuthAgeMs in 0..30_000L) {
                    log("Using fresh request account ${requestAuth.userId}; session/prefs still report ${sessionAuth.userId}")
                    requestAuth
                } else {
                    log("Using session account ${sessionAuth.userId}; cached request account ${requestAuth.userId} is stale")
                    sessionAuth
                }
            }
            requestAuth.requestUserId != null -> sessionAuth.merge(requestAuth)
            else -> requestAuth.merge(sessionAuth)
        }
        if (auth.hasUsableAuth) {
            cachedAuth = auth
            cachedAuthUpdatedAtMs = System.currentTimeMillis()
        }
        return auth
    }

    private fun fetchFollowers(userId: String, auth: AuthSnapshot, expectedCountHint: Int?): FollowerFetchResult {
        val expectedCount = expectedCountHint ?: fetchFollowerCount(userId, auth)
        val rankToken = "${userId}_${UUID.randomUUID()}"
        var best = emptyList<Follower>()
        var bestVariant = ""
        var lastFailure: Throwable? = null
        followerRequestVariants(userId, rankToken).forEach { variant ->
            val result = runCatching { fetchFollowersFromVariant(variant, auth) }
            result
                .onSuccess { followers ->
                    if (followers.size > best.size) {
                        best = followers
                        bestVariant = variant.name
                    }
                    if (expectedCount == null || followers.size >= expectedCount) {
                        log("Fetched ${followers.size} followers for user=$userId variant=${variant.name} expected=${expectedCount ?: "unknown"}")
                        return FollowerFetchResult(
                            followers = followers,
                            expectedCount = expectedCount,
                            isComplete = true,
                            source = variant.name,
                            partialMessage = null
                        )
                    }
                    if (followers.size >= MAX_FETCHED_FOLLOWERS) {
                        log("Follower variant ${variant.name} reached cap ${followers.size}/${expectedCount}; using capped partial list")
                        return FollowerFetchResult(
                            followers = followers,
                            expectedCount = expectedCount,
                            isComplete = false,
                            source = variant.name,
                            partialMessage = "Showing ${followers.size} of ${expectedCount} followers Instagram returned. Follower history was not updated from this partial list."
                        )
                    }
                    log("Follower variant ${variant.name} returned ${followers.size}/${expectedCount}; trying fallback")
                }
                .onFailure { throwable ->
                    lastFailure = throwable
                    log("Follower variant ${variant.name} failed: ${throwable.message ?: throwable.javaClass.simpleName}")
                }
        }

        if (best.isNotEmpty() && expectedCount == null) {
            log("Fetched ${best.size} followers for user=$userId expected=unknown")
            return FollowerFetchResult(
                followers = best,
                expectedCount = null,
                isComplete = true,
                source = bestVariant.ifBlank { "fallback" },
                partialMessage = null
            )
        }
        if (best.isNotEmpty()) {
            log("Using partial follower list for user=$userId fetched=${best.size} expected=${expectedCount ?: "unknown"} source=${bestVariant.ifBlank { "fallback" }}")
            return FollowerFetchResult(
                followers = best,
                expectedCount = expectedCount,
                isComplete = expectedCount == null || best.size >= expectedCount,
                source = bestVariant.ifBlank { "fallback" },
                partialMessage = expectedCount?.let {
                    "Showing ${best.size} of $it followers Instagram returned. Follower history was not updated from this partial list."
                }
            )
        }
        throw lastFailure ?: IllegalStateException("No followers returned")
    }

    private fun fetchFollowersFromVariant(variant: RequestVariant, auth: AuthSnapshot): List<Follower> {
        val followers = linkedMapOf<String, Follower>()
        var maxId: String? = null
        var page = 0
        val seenCursors = HashSet<String>()
        do {
            val json = requestJson(variant.url(maxId), auth)
            val users = json.optJSONArray("users") ?: break
            for (i in 0 until users.length()) {
                val user = users.optJSONObject(i) ?: continue
                val username = user.optString("username").trim()
                if (!isUsername(username)) continue
                followers[username.lowercase(Locale.US)] = Follower(
                    username = username,
                    displayName = user.optString("full_name").trim(),
                    userId = user.optString("pk_id", user.optString("pk")).trim(),
                    thumbnailUrl = user.optString("profile_pic_url").trim()
                )
                if (followers.size >= MAX_FETCHED_FOLLOWERS) break
            }
            maxId = nextPaginationCursor(json)?.takeIf { seenCursors.add(it) }
            page++
        } while (maxId != null && followers.size < MAX_FETCHED_FOLLOWERS && page < 12)

        if (followers.isEmpty()) error("No followers returned")
        return followers.values.toList()
    }

    private fun fetchFollowing(userId: String, auth: AuthSnapshot): List<Follower> {
        val rankToken = "${userId}_${UUID.randomUUID()}"
        var best = emptyList<Follower>()
        var lastFailure: Throwable? = null
        followingRequestVariants(userId, rankToken).forEach { variant ->
            val result = runCatching { fetchUsersFromVariant(variant, auth, MAX_FETCHED_FOLLOWING) }
            result
                .onSuccess { users ->
                    if (users.size > best.size) best = users
                    if (users.isNotEmpty()) {
                        log("Fetched ${users.size} following for user=$userId variant=${variant.name}")
                        return users
                    }
                }
                .onFailure { throwable ->
                    lastFailure = throwable
                    log("Following variant ${variant.name} failed: ${throwable.message ?: throwable.javaClass.simpleName}")
                }
        }
        if (best.isNotEmpty()) return best
        throw lastFailure ?: IllegalStateException("No following returned")
    }

    private fun fetchUsersFromVariant(variant: RequestVariant, auth: AuthSnapshot, limit: Int): List<Follower> {
        val usersByName = linkedMapOf<String, Follower>()
        var maxId: String? = null
        var page = 0
        val seenCursors = HashSet<String>()
        do {
            val json = requestJson(variant.url(maxId), auth)
            val users = json.optJSONArray("users") ?: break
            for (i in 0 until users.length()) {
                val user = users.optJSONObject(i) ?: continue
                val username = user.optString("username").trim()
                if (!isUsername(username)) continue
                usersByName[username.lowercase(Locale.US)] = Follower(
                    username = username,
                    displayName = user.optString("full_name").trim(),
                    userId = user.optString("pk_id", user.optString("pk")).trim(),
                    thumbnailUrl = user.optString("profile_pic_url").trim()
                )
                if (usersByName.size >= limit) break
            }
            maxId = nextPaginationCursor(json)?.takeIf { seenCursors.add(it) }
            page++
        } while (maxId != null && usersByName.size < limit && page < 12)
        if (usersByName.isEmpty()) error("No users returned")
        return usersByName.values.toList()
    }

    private fun nextPaginationCursor(json: JSONObject): String? {
        sequenceOf(
            json.optString("next_max_id").trim(),
            json.optString("next_cursor").trim(),
            json.optJSONObject("page_info")?.optString("end_cursor")?.trim().orEmpty()
        ).forEach { cursor ->
            if (cursor.isNotBlank() && cursor != "null") return cursor
        }
        return null
    }

    private fun fetchFollowerCount(userId: String, auth: AuthSnapshot): Int? {
        val urls = listOf(
            "https://i.instagram.com/api/v1/users/$userId/info/?entry_point=follower_list",
            "https://i.instagram.com/api/v1/users/$userId/info/"
        )
        urls.forEach { url ->
            runCatching {
                val json = requestJson(url, auth)
                val user = json.optJSONObject("user") ?: json
                user.optInt("follower_count", -1).takeIf { it >= 0 }
                    ?: user.optInt("followers_count", -1).takeIf { it >= 0 }
                    ?: user.optJSONObject("edge_followed_by")?.optInt("count", -1)?.takeIf { it >= 0 }
            }.getOrNull()?.let {
                log("Follower count check user=$userId expected=$it")
                return it
            }
        }
        log("Follower count check unavailable for user=$userId")
        return null
    }

    private fun resolveTargetUser(session: Any?, auth: AuthSnapshot): TargetUser? {
        val userId = auth.userId ?: currentUserId(session) ?: return null
        log("Resolved follower target user=$userId source=${if (auth.requestUserId != null) "active_request" else "session"}")
        return TargetUser(userId = userId, expectedFollowerCount = null)
    }

    private fun fetchAccountAnalytics(
        context: Context,
        userId: String,
        auth: AuthSnapshot,
        followers: List<Follower>,
        following: List<Follower>
    ): AccountAnalytics {
        val followerNames = followers.map { it.key }.toHashSet()
        val followingNames = following.map { it.key }.toHashSet()
        val media = runCatching { fetchRecentMedia(userId, auth) }
            .onFailure { log("Recent media fetch failed: ${it.message ?: it.javaClass.simpleName}") }
            .getOrDefault(emptyList())
        val selfUsername = fetchSelfUsername(userId, auth).orEmpty()
        val taggedMedia = runCatching { fetchTaggedMedia(userId, selfUsername, auth, "${userId}_${UUID.randomUUID()}") }
            .onFailure { log("Tagged media fetch failed: ${it.message ?: it.javaClass.simpleName}") }
            .getOrDefault(emptyList())
        val stats = linkedMapOf<String, EngagementUser>()
        val likerSnapshot = linkedMapOf<String, Map<String, Follower>>()
        val commenterSnapshot = linkedMapOf<String, Map<String, Follower>>()
        media.take(MAX_ANALYTICS_ENGAGEMENT_MEDIA).forEach { item ->
            val likers = runCatching { fetchMediaLikers(item.mediaId, auth) }
                .onFailure { log("Likers fetch failed media=${item.mediaId}: ${it.message ?: it.javaClass.simpleName}") }
                .getOrDefault(emptyList())
            val commenters = runCatching { fetchMediaCommenters(item.mediaId, auth) }
                .onFailure { log("Commenters fetch failed media=${item.mediaId}: ${it.message ?: it.javaClass.simpleName}") }
                .getOrDefault(emptyList())
            likerSnapshot[item.mediaId] = likers.associateBy { it.key }
            commenterSnapshot[item.mediaId] = commenters.associateBy { it.key }
            likers.forEach { user ->
                val stat = stats.getOrPut(user.key) { EngagementUser(user, 0, 0, followingNames.contains(user.key), followerNames.contains(user.key)) }
                stat.likeCount++
            }
            commenters.forEach { user ->
                val stat = stats.getOrPut(user.key) { EngagementUser(user, 0, 0, followingNames.contains(user.key), followerNames.contains(user.key)) }
                stat.commentCount++
            }
        }
        val deleted = updateEngagementSnapshots(context, userId, likerSnapshot, commenterSnapshot, stats)
        val followerEngagement = followers.map { follower ->
            stats[follower.key] ?: EngagementUser(follower, 0, 0, followingNames.contains(follower.key), true)
        }
        val history = InstagramActivityHistoryStore.read(context)
        return AccountAnalytics(
            media = media,
            engagementUsers = stats.values.sortedByDescending { it.total }.toList(),
            followerEngagement = followerEngagement,
            deletedLikes = deleted.first,
            deletedComments = deleted.second,
            timelineItems = history.filter { it.type == InstagramActivityHistoryStore.TYPE_POST || it.type == InstagramActivityHistoryStore.TYPE_REEL },
            taggedMedia = taggedMedia
        )
    }

    private fun fetchRecentMedia(userId: String, auth: AuthSnapshot): List<MediaMetric> {
        val out = mutableListOf<MediaMetric>()
        var maxId: String? = null
        var page = 0
        do {
            val url = "https://i.instagram.com/api/v1/feed/user/$userId/?count=24" + (maxId?.let { "&max_id=${Uri.encode(it)}" } ?: "")
            val json = requestJson(url, auth)
            parseMediaArray(json.optJSONArray("items") ?: JSONArray(), out, auth)
            maxId = json.optString("next_max_id").trim().takeIf { it.isNotBlank() }
            page++
        } while (maxId != null && out.size < MAX_ANALYTICS_MEDIA && page < 4)
        log("Fetched ${out.size} recent media item(s) for analytics")
        return out.distinctBy { it.mediaId }.take(MAX_ANALYTICS_MEDIA)
    }

    private fun fetchSelfUsername(userId: String, auth: AuthSnapshot): String? {
        return runCatching {
            val json = requestJson("https://i.instagram.com/api/v1/users/$userId/info/", auth)
            (json.optJSONObject("user") ?: json).optString("username").trim().takeIf { isUsername(it) }
        }.getOrNull()
    }

    private fun fetchTaggedMedia(userId: String, selfUsername: String, auth: AuthSnapshot, rankToken: String?): List<MediaMetric> {
        val out = mutableListOf<MediaMetric>()
        var maxId: String? = null
        var page = 0
        do {
            val extras = buildString {
                maxId?.let { append("&max_id=${Uri.encode(it)}") }
                rankToken?.let { append("&rank_token=${Uri.encode(it)}") }
                append("&ranked_content=true")
            }
            val urls = listOf(
                "https://i.instagram.com/api/v1/usertags/$userId/feed/?count=24$extras",
                "https://www.instagram.com/api/v1/usertags/$userId/feed/?count=24$extras"
            )
            val json = urls.firstNotNullOfOrNull { url ->
                runCatching { requestJson(url, auth) }
                    .onSuccess { log("Tagged endpoint ok path=${URL(url).path} items=${it.optJSONArray("items")?.length() ?: -1} keys=${jsonKeys(it).take(12)}") }
                    .onFailure { log("Tagged endpoint failed path=${URL(url).path}: ${it.message ?: it.javaClass.simpleName}") }
                    .getOrNull()
            } ?: error("Tagged media unavailable")
            parseMediaArray(json.optJSONArray("items") ?: JSONArray(), out, auth)
            maxId = json.optString("next_max_id").trim().takeIf { it.isNotBlank() }
            page++
        } while (maxId != null && out.size < MAX_ANALYTICS_MEDIA && page < 4)
        val mentionMedia = fetchMentionMediaFromActivityInbox(selfUsername, auth)
        val webTagged = fetchWebTaggedMedia(selfUsername, auth)
        log("Tagged analytics user=$userId username=${selfUsername.ifBlank { "unknown" }} usertags=${out.size} mentions=${mentionMedia.size} web=${webTagged.size}")
        return (out + mentionMedia + webTagged).distinctBy { it.mediaId }.take(MAX_ANALYTICS_MEDIA)
    }

    private fun fetchWebTaggedMedia(selfUsername: String, auth: AuthSnapshot): List<MediaMetric> {
        if (selfUsername.isBlank()) return emptyList()
        val json = runCatching {
            requestJson("https://www.instagram.com/api/v1/users/web_profile_info/?username=${Uri.encode(selfUsername)}", auth)
        }.onFailure {
            log("Web profile tagged fetch failed: ${it.message ?: it.javaClass.simpleName}")
        }.getOrNull() ?: return emptyList()
        val user = json.optJSONObject("data")?.optJSONObject("user") ?: json.optJSONObject("user") ?: json
        val edges = user.optJSONObject("edge_user_to_photos_of_you")?.optJSONArray("edges") ?: JSONArray()
        val out = mutableListOf<MediaMetric>()
        for (i in 0 until edges.length()) {
            val node = edges.optJSONObject(i)?.optJSONObject("node") ?: continue
            parseWebMediaMetric(node, i + 1, auth)?.let { out += it }
        }
        log("Web tagged profile username=$selfUsername edges=${edges.length()} parsed=${out.size}")
        return out
    }

    private fun fetchMentionMediaFromActivityInbox(selfUsername: String, auth: AuthSnapshot): List<MediaMetric> {
        if (selfUsername.isBlank()) return emptyList()
        val json = runCatching { requestJson("https://i.instagram.com/api/v1/news/inbox/?mark_as_seen=false", auth) }
            .onFailure { log("Activity inbox fetch failed: ${it.message ?: it.javaClass.simpleName}") }
            .getOrNull() ?: return emptyList()
        val out = mutableListOf<MediaMetric>()
        scanForMentionMedia(json, selfUsername.lowercase(Locale.US), out, auth)
        log("Activity inbox mention scan keys=${jsonKeys(json).take(12)} found=${out.size}")
        return out
    }

    private fun scanForMentionMedia(value: Any?, username: String, out: MutableList<MediaMetric>, auth: AuthSnapshot, depth: Int = 0) {
        if (value == null || depth > 8 || out.size >= MAX_ANALYTICS_MEDIA) return
        when (value) {
            is JSONObject -> {
                val text = listOf("text", "message", "caption", "title").joinToString(" ") { value.optString(it) }.lowercase(Locale.US)
                val mediaObject = value.optJSONObject("media") ?: value.optJSONObject("media_item") ?: value.optJSONObject("item")
                if (text.contains("@$username") && mediaObject != null) {
                    parseMediaMetric(mediaObject, out.size + 1, auth)?.let { out += it }
                }
                val keys = value.keys()
                while (keys.hasNext()) scanForMentionMedia(value.opt(keys.next()), username, out, auth, depth + 1)
            }
            is JSONArray -> for (i in 0 until value.length()) scanForMentionMedia(value.opt(i), username, out, auth, depth + 1)
        }
    }

    private fun parseMediaArray(items: JSONArray, out: MutableList<MediaMetric>, auth: AuthSnapshot) {
        for (i in 0 until items.length()) {
            parseMediaMetric(items.optJSONObject(i) ?: continue, out.size + 1, auth)?.let { out += it }
        }
    }

    private fun parseMediaMetric(item: JSONObject, position: Int, auth: AuthSnapshot): MediaMetric? {
        val mediaId = item.optString("id", item.optString("pk")).trim()
        if (mediaId.isBlank()) return null
        val code = item.optString("code").trim()
        val caption = item.optJSONObject("caption")?.optString("text").orEmpty()
        val base = MediaMetric(
            mediaId = mediaId,
            code = code,
            title = caption.lineSequence().firstOrNull()?.take(80).orEmpty().ifBlank { "Post $position" },
            thumbnailUrl = firstMediaThumbnail(item),
            likeCount = intFromMedia(item, "like_count").coerceAtLeast(0),
            commentCount = intFromMedia(item, "comment_count").coerceAtLeast(0),
            viewCount = viewCountFromMedia(item).coerceAtLeast(0),
            timestampMs = item.optLong("taken_at", 0L).let { if (it > 0L) it * 1000L else System.currentTimeMillis() }
        )
        if (position <= 3) {
            log("Media debug id=${mediaId.take(16)} code=$code media_type=${item.optString("media_type")} product=${item.optString("product_type")} counts=${countFieldSummary(item)}")
        }
        return if (base.viewCount > 0) base else enrichMediaMetric(base, auth)
    }

    private fun parseWebMediaMetric(item: JSONObject, position: Int, auth: AuthSnapshot): MediaMetric? {
        val mediaId = item.optString("id").trim()
        if (mediaId.isBlank()) return null
        val code = item.optString("shortcode", item.optString("code")).trim()
        val caption = item.optJSONObject("edge_media_to_caption")
            ?.optJSONArray("edges")
            ?.optJSONObject(0)
            ?.optJSONObject("node")
            ?.optString("text")
            .orEmpty()
        val base = MediaMetric(
            mediaId = mediaId,
            code = code,
            title = caption.lineSequence().firstOrNull()?.take(80).orEmpty().ifBlank { "Post $position" },
            thumbnailUrl = item.optString("thumbnail_src").ifBlank { item.optString("display_url") },
            likeCount = item.optJSONObject("edge_liked_by")?.optInt("count", 0)?.coerceAtLeast(0) ?: 0,
            commentCount = item.optJSONObject("edge_media_to_comment")?.optInt("count", 0)?.coerceAtLeast(0) ?: 0,
            viewCount = viewCountFromMedia(item).coerceAtLeast(0),
            timestampMs = item.optLong("taken_at_timestamp", 0L).let { if (it > 0L) it * 1000L else System.currentTimeMillis() }
        )
        return if (base.viewCount > 0) base else enrichMediaMetric(base, auth)
    }

    private fun enrichMediaMetric(base: MediaMetric, auth: AuthSnapshot): MediaMetric {
        return runCatching {
            val json = requestJson("https://i.instagram.com/api/v1/media/${base.mediaId}/info/", auth)
            val item = json.optJSONArray("items")?.optJSONObject(0) ?: json.optJSONObject("item") ?: json
            log("Media info debug id=${base.mediaId.take(16)} keys=${jsonKeys(item).take(18)} counts=${countFieldSummary(item)}")
            base.copy(
                likeCount = maxOf(base.likeCount, intFromMedia(item, "like_count")),
                commentCount = maxOf(base.commentCount, intFromMedia(item, "comment_count")),
                viewCount = maxOf(base.viewCount, viewCountFromMedia(item)),
                thumbnailUrl = base.thumbnailUrl.ifBlank { firstMediaThumbnail(item) }
            )
        }.getOrDefault(base)
    }

    private fun intFromMedia(item: JSONObject, key: String): Int {
        return item.optInt(key, 0).takeIf { it > 0 } ?: deepInt(item, key)
    }

    private fun viewCountFromMedia(item: JSONObject): Int {
        return listOf("view_count", "video_view_count", "play_count", "ig_play_count", "fb_play_count", "clips_play_count").maxOf { intFromMedia(item, it) }
    }

    private fun deepInt(value: Any?, key: String, depth: Int = 0): Int {
        if (depth > 5 || value == null) return 0
        return when (value) {
            is JSONObject -> {
                var best = value.optInt(key, 0).coerceAtLeast(0)
                val keys = value.keys()
                while (keys.hasNext()) {
                    best = maxOf(best, deepInt(value.opt(keys.next()), key, depth + 1))
                }
                best
            }
            is JSONArray -> {
                var best = 0
                for (i in 0 until value.length()) best = maxOf(best, deepInt(value.opt(i), key, depth + 1))
                best
            }
            else -> 0
        }
    }

    private fun countFieldSummary(json: JSONObject): String {
        val names = listOf("like_count", "comment_count", "view_count", "video_view_count", "play_count", "ig_play_count", "fb_play_count", "clips_play_count", "reshare_count")
        return names.mapNotNull { name -> deepInt(json, name).takeIf { it > 0 }?.let { "$name=$it" } }.joinToString(",").ifBlank { "none" }
    }

    private fun jsonKeys(json: JSONObject): List<String> {
        return buildList {
            val keys = json.keys()
            while (keys.hasNext()) add(keys.next())
        }
    }

    private fun firstMediaThumbnail(item: JSONObject): String {
        val candidates = item.optJSONObject("image_versions2")?.optJSONArray("candidates")
        if (candidates != null && candidates.length() > 0) {
            candidates.optJSONObject(0)?.optString("url")?.takeIf { it.isNotBlank() }?.let { return it }
        }
        val carousel = item.optJSONArray("carousel_media")
        if (carousel != null && carousel.length() > 0) return firstMediaThumbnail(carousel.optJSONObject(0) ?: JSONObject())
        return ""
    }

    private fun firstVideoUrlFromMediaInfo(value: Any?, depth: Int = 0): String? {
        if (value == null || depth > 8) return null
        return when (value) {
            is JSONObject -> {
                value.optJSONArray("video_versions")?.let { versions ->
                    for (index in 0 until versions.length()) {
                        versions.optJSONObject(index)
                            ?.optString("url")
                            ?.takeIf(::looksLikeVideoUrl)
                            ?.let { return it }
                    }
                }
                value.optJSONArray("items")?.let { items ->
                    for (index in 0 until items.length()) firstVideoUrlFromMediaInfo(items.opt(index), depth + 1)?.let { return it }
                }
                value.optJSONArray("carousel_media")?.let { carousel ->
                    for (index in 0 until carousel.length()) firstVideoUrlFromMediaInfo(carousel.opt(index), depth + 1)?.let { return it }
                }
                val keys = value.keys()
                while (keys.hasNext()) firstVideoUrlFromMediaInfo(value.opt(keys.next()), depth + 1)?.let { return it }
                null
            }
            is JSONArray -> {
                for (index in 0 until value.length()) firstVideoUrlFromMediaInfo(value.opt(index), depth + 1)?.let { return it }
                null
            }
            is String -> value.takeIf(::looksLikeVideoUrl)
            else -> null
        }
    }

    private fun mediaDownloadUrlsFromInfo(json: JSONObject): List<String> {
        val items = json.optJSONArray("items")
        if (items != null && items.length() > 0) {
            val out = LinkedHashSet<String>()
            for (index in 0 until items.length()) {
                out += mediaDownloadUrlsFromItem(items.optJSONObject(index) ?: continue)
            }
            return out.toList().take(20)
        }
        return mediaDownloadUrlsFromItem(json).distinct().take(20)
    }

    private fun mediaDownloadUrlsFromItem(item: JSONObject): List<String> {
        val carousel = item.optJSONArray("carousel_media")
        if (carousel != null && carousel.length() > 0) {
            val out = LinkedHashSet<String>()
            for (index in 0 until carousel.length()) {
                bestMediaInfoItemUrl(carousel.optJSONObject(index) ?: continue)?.let(out::add)
            }
            return out.toList()
        }
        val out = LinkedHashSet<String>()
        bestMediaInfoVideoUrl(item)?.let(out::add)
        bestMediaInfoImageUrl(item)?.let(out::add)
        return out.toList()
    }

    private fun bestMediaInfoItemUrl(item: JSONObject): String? {
        return bestMediaInfoVideoUrl(item) ?: bestMediaInfoImageUrl(item)
    }

    private fun bestMediaInfoVideoUrl(item: JSONObject): String? {
        val versions = item.optJSONArray("video_versions") ?: return null
        var bestUrl = ""
        var bestScore = -1
        for (index in 0 until versions.length()) {
            val candidate = versions.optJSONObject(index) ?: continue
            val url = candidate.optString("url").takeIf(::looksLikeVideoUrl) ?: continue
            val area = candidate.optInt("width", 0).coerceAtLeast(1) * candidate.optInt("height", 0).coerceAtLeast(1)
            val bitrate = candidate.optInt("bandwidth", 0).coerceAtLeast(candidate.optInt("bitrate", 0))
            val score = area + bitrate / 8
            if (score > bestScore) {
                bestScore = score
                bestUrl = url
            }
        }
        return bestUrl.takeIf { it.isNotBlank() }
    }

    private fun bestMediaInfoImageUrl(item: JSONObject): String? {
        val candidates = item.optJSONObject("image_versions2")?.optJSONArray("candidates") ?: return null
        var bestUrl = ""
        var bestArea = -1
        for (index in 0 until candidates.length()) {
            val candidate = candidates.optJSONObject(index) ?: continue
            val url = candidate.optString("url").takeIf(::looksLikeMediaInfoImageUrl) ?: continue
            val area = candidate.optInt("width", 0).coerceAtLeast(1) * candidate.optInt("height", 0).coerceAtLeast(1)
            if (area > bestArea) {
                bestArea = area
                bestUrl = url
            }
        }
        return bestUrl.takeIf { it.isNotBlank() }
    }

    private fun looksLikeVideoUrl(url: String): Boolean {
        val lower = url.lowercase(Locale.US)
        return lower.startsWith("http") &&
            (lower.contains(".mp4") || lower.contains("/m86/") || lower.contains("%2fm86%2f") || lower.contains("t50."))
    }

    private fun looksLikeMediaInfoImageUrl(url: String): Boolean {
        val lower = url.lowercase(Locale.US)
        if (!lower.startsWith("http")) return false
        if (looksLikeVideoUrl(url)) return false
        return lower.contains("fbcdn") ||
            lower.contains("cdninstagram") ||
            lower.contains(".jpg") ||
            lower.contains(".jpeg") ||
            lower.contains(".webp") ||
            lower.contains(".png")
    }

    private fun fetchMediaLikers(mediaId: String, auth: AuthSnapshot): List<Follower> {
        val json = requestJson("https://i.instagram.com/api/v1/media/$mediaId/likers/", auth)
        return parseUsers(json.optJSONArray("users") ?: JSONArray(), MAX_ANALYTICS_USERS_PER_MEDIA)
    }

    private fun fetchMediaCommenters(mediaId: String, auth: AuthSnapshot): List<Follower> {
        val json = requestJson("https://i.instagram.com/api/v1/media/$mediaId/comments/?can_support_threading=true&permalink_enabled=false", auth)
        val comments = json.optJSONArray("comments") ?: JSONArray()
        val out = linkedMapOf<String, Follower>()
        for (i in 0 until comments.length()) {
            val user = comments.optJSONObject(i)?.optJSONObject("user") ?: continue
            parseUser(user)?.let { out[it.key] = it }
            if (out.size >= MAX_ANALYTICS_USERS_PER_MEDIA) break
        }
        return out.values.toList()
    }

    private fun parseUsers(users: JSONArray, limit: Int): List<Follower> {
        val out = linkedMapOf<String, Follower>()
        for (i in 0 until users.length()) {
            val follower = parseUser(users.optJSONObject(i) ?: continue) ?: continue
            out[follower.key] = follower
            if (out.size >= limit) break
        }
        return out.values.toList()
    }

    private fun parseUser(user: JSONObject): Follower? {
        val username = user.optString("username").trim()
        if (!isUsername(username)) return null
        return Follower(
            username = username,
            displayName = user.optString("full_name").trim(),
            userId = user.optString("pk_id", user.optString("pk")).trim(),
            thumbnailUrl = user.optString("profile_pic_url").trim()
        )
    }

    private fun followerToSnapshot(follower: Follower): JSONObject {
        return JSONObject()
            .put("username", follower.username)
            .put("displayName", follower.displayName)
            .put("userId", follower.userId)
            .put("thumbnailUrl", follower.thumbnailUrl)
    }

    private fun followerFromSnapshot(json: JSONObject): Follower? {
        val username = json.optString("username").trim()
        if (!isUsername(username)) return null
        return Follower(
            username = username,
            displayName = json.optString("displayName").trim(),
            userId = json.optString("userId").trim(),
            thumbnailUrl = json.optString("thumbnailUrl").trim()
        )
    }

    private fun updateEngagementSnapshots(
        context: Context,
        accountId: String,
        likers: Map<String, Map<String, Follower>>,
        commenters: Map<String, Map<String, Follower>>,
        currentStats: Map<String, EngagementUser>
    ): Pair<List<EngagementUser>, List<EngagementUser>> {
        val prefs = context.getSharedPreferences("purrfect_instagram_engagement_tracker", Context.MODE_PRIVATE)
        fun decode(kind: String): Map<String, Map<String, Follower>> = runCatching {
            val json = JSONObject(prefs.getString("${accountId}_$kind", "{}").orEmpty())
            json.keys().asSequence().associateWith { mediaId ->
                val array = json.optJSONArray(mediaId) ?: JSONArray()
                buildMap {
                    for (i in 0 until array.length()) {
                        val raw = array.opt(i)
                        val follower = when (raw) {
                            is JSONObject -> followerFromSnapshot(raw)
                            is String -> raw.takeIf { isUsername(it) }?.let { Follower(it, "", "", "") }
                            else -> null
                        }
                        follower?.let { put(it.key, it) }
                    }
                }
            }
        }.getOrDefault(emptyMap())
        fun encode(value: Map<String, Map<String, Follower>>): String {
            val json = JSONObject()
            value.forEach { (mediaId, users) -> json.put(mediaId, JSONArray(users.values.map(::followerToSnapshot))) }
            return json.toString()
        }
        fun decodeDeleted(kind: String): List<Follower> = runCatching {
            val array = JSONArray(prefs.getString("${accountId}_deleted_$kind", "[]").orEmpty())
            buildList {
                for (i in 0 until array.length()) {
                    followerFromSnapshot(array.optJSONObject(i) ?: continue)?.let { add(it) }
                }
            }.distinctBy { it.key }
        }.getOrDefault(emptyList())
        fun encodeDeleted(value: List<Follower>): String {
            return JSONArray(value.distinctBy { it.key }.map(::followerToSnapshot)).toString()
        }
        fun removed(previous: Map<String, Map<String, Follower>>, current: Map<String, Map<String, Follower>>): List<EngagementUser> {
            return previous.filterKeys { it in current.keys }.flatMap { (mediaId, oldUsers) ->
                val currentUsers = current[mediaId].orEmpty()
                oldUsers.filterKeys { it !in currentUsers.keys }.values
            }.distinctBy { it.key }.map { follower ->
                currentStats[follower.key]?.copy(likeCount = 0, commentCount = 0)
                    ?: EngagementUser(follower, 0, 0, followedByYou = false, followsYou = false)
            }
        }
        val oldLikers = decode("likers")
        val oldCommenters = decode("commenters")
        val removedLikes = removed(oldLikers, likers)
        val removedComments = removed(oldCommenters, commenters)
        val deletedLikes = (removedLikes.map { it.follower } + decodeDeleted("likers")).distinctBy { it.key }
        val deletedComments = (removedComments.map { it.follower } + decodeDeleted("commenters")).distinctBy { it.key }
        log("Deleted engagement snapshot account=$accountId oldLikes=${oldLikers.values.sumOf { it.size }} currentLikes=${likers.values.sumOf { it.size }} removedLikes=${removedLikes.size} savedLikes=${deletedLikes.size} oldComments=${oldCommenters.values.sumOf { it.size }} currentComments=${commenters.values.sumOf { it.size }} removedComments=${removedComments.size} savedComments=${deletedComments.size}")
        prefs.edit()
            .putString("${accountId}_likers", encode(likers))
            .putString("${accountId}_commenters", encode(commenters))
            .putString("${accountId}_deleted_likers", encodeDeleted(deletedLikes))
            .putString("${accountId}_deleted_commenters", encodeDeleted(deletedComments))
            .apply()
        fun toUsers(users: List<Follower>) = users.map { follower ->
            currentStats[follower.key]?.copy(likeCount = 0, commentCount = 0)
                ?: EngagementUser(follower, 0, 0, followedByYou = false, followsYou = false)
        }
        return toUsers(deletedLikes) to toUsers(deletedComments)
    }

    private fun followerRequestVariants(userId: String, rankToken: String): List<RequestVariant> {
        fun native(name: String, count: Int, params: Map<String, String> = emptyMap()) = RequestVariant(
            name = name,
            baseUrl = "https://i.instagram.com/api/v1/friendships/$userId/followers/",
            count = count,
            params = params
        )
        fun web(name: String, count: Int, params: Map<String, String> = emptyMap()) = RequestVariant(
            name = name,
            baseUrl = "https://www.instagram.com/api/v1/friendships/$userId/followers/",
            count = count,
            params = params
        )
        val listParams = mapOf(
            "rank_token" to rankToken,
            "search_surface" to "follow_list_page",
            "query" to ""
        )
        return listOf(
            native("native-follow-list", 12, listParams),
            native("native-follow-list-large", 50, listParams),
            native("native-ranked", 100, mapOf("rank_token" to rankToken)),
            native("native-plain", 100),
            web("web-follow-list", 12, listParams),
            web("web-ranked", 50, mapOf("rank_token" to rankToken))
        )
    }

    private fun followingRequestVariants(userId: String, rankToken: String): List<RequestVariant> {
        fun native(name: String, count: Int, params: Map<String, String> = emptyMap()) = RequestVariant(
            name = name,
            baseUrl = "https://i.instagram.com/api/v1/friendships/$userId/following/",
            count = count,
            params = params
        )
        fun web(name: String, count: Int, params: Map<String, String> = emptyMap()) = RequestVariant(
            name = name,
            baseUrl = "https://www.instagram.com/api/v1/friendships/$userId/following/",
            count = count,
            params = params
        )
        val listParams = mapOf(
            "rank_token" to rankToken,
            "search_surface" to "follow_list_page",
            "query" to ""
        )
        return listOf(
            native("native-following-list", 50, listParams),
            native("native-following-ranked", 100, mapOf("rank_token" to rankToken)),
            native("native-following-plain", 100),
            web("web-following-list", 50, listParams),
            web("web-following-ranked", 50, mapOf("rank_token" to rankToken))
        )
    }

    private fun requestJson(url: String, auth: AuthSnapshot, method: String = "GET", body: String? = null): JSONObject {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000
            readTimeout = 18_000
            instanceFollowRedirects = true
            requestMethod = method
            val webRequest = URL(url).host.contains("instagram.com") && !URL(url).host.startsWith("i.")
            setRequestProperty("User-Agent", if (webRequest) WEB_USER_AGENT else ANDROID_USER_AGENT)
            setRequestProperty("X-IG-App-ID", if (webRequest) "936619743392459" else "567067343352427")
            setRequestProperty("X-ASBD-ID", "198387")
            setRequestProperty("X-IG-WWW-Claim", "0")
            setRequestProperty("X-Requested-With", "XMLHttpRequest")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Accept-Language", Locale.getDefault().toLanguageTag())
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            }
            if (webRequest) {
                setRequestProperty("Referer", "https://www.instagram.com/")
                setRequestProperty("Origin", "https://www.instagram.com")
            }
            auth.authorization?.let { setRequestProperty("Authorization", it) }
            auth.csrfToken?.let { setRequestProperty("X-CSRFToken", it) }
            auth.cookieHeader.takeIf { it.isNotBlank() }?.let { setRequestProperty("Cookie", it) }
        }
        return try {
            body?.let { payload ->
                connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) error("HTTP $code${auth.debugSummary()}")
            JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun updateTracker(context: Context, accountId: String, followers: List<Follower>, completeFetch: Boolean): TrackerSummary {
        val prefs = context.getSharedPreferences("purrfect_instagram_follower_tracker", Context.MODE_PRIVATE)
        val key = "account_$accountId"
        val previous = prefs.getString(key, null)?.let(::decodeFollowerSnapshot).orEmpty()
        val current = followers.associateBy { it.username.lowercase(Locale.US) }
        val currentEvents = prefs.getString("${key}_events", null)?.let(::decodeTrackerEvents).orEmpty()
        if (!completeFetch) {
            log("Follower tracker account=$accountId partial current=${current.size} previous=${previous.size}; keeping stored snapshot/events")
            return TrackerSummary(
                accountId = accountId,
                baseline = previous.isEmpty(),
                currentCount = if (previous.isNotEmpty()) previous.size else current.size,
                events = currentEvents,
                currentAdded = emptyList(),
                currentRemoved = emptyList()
            )
        }
        val added = current.keys.minus(previous.keys).mapNotNull { current[it] }.sortedBy { it.username.lowercase(Locale.US) }
        val removed = previous.keys.minus(current.keys).mapNotNull { previous[it] }.sortedBy { it.username.lowercase(Locale.US) }
        val hadPrevious = previous.isNotEmpty()
        val now = System.currentTimeMillis()
        val nextEvents = if (hadPrevious && (added.isNotEmpty() || removed.isNotEmpty())) {
            (currentEvents + TrackerEvent(now, added, removed)).takeLast(240)
        } else {
            currentEvents
        }
        prefs.edit()
            .putString(key, encodeFollowerSnapshot(current.values))
            .putString("${key}_events", encodeTrackerEvents(nextEvents))
            .putLong("${key}_checked_at", now)
            .apply()
        log("Follower tracker account=$accountId current=${current.size} added=${if (hadPrevious) added.size else 0} removed=${if (hadPrevious) removed.size else 0} baseline=${!hadPrevious}")
        return TrackerSummary(
            accountId = accountId,
            baseline = !hadPrevious,
            currentCount = current.size,
            events = nextEvents,
            currentAdded = if (hadPrevious) added else emptyList(),
            currentRemoved = if (hadPrevious) removed else emptyList()
        )
    }

    private fun periodEvents(events: List<TrackerEvent>, period: AnalyticsPeriod): List<TrackerEvent> {
        val cutoff = period.cutoffMs(System.currentTimeMillis())
        return events.filter { it.timestampMs >= cutoff }
    }

    private fun addedForPeriod(tracker: TrackerSummary, period: AnalyticsPeriod): List<Follower> {
        val events = if (period == AnalyticsPeriod.LAST_CHECK) tracker.events.takeLast(1) else periodEvents(tracker.events, period)
        return events.flatMap { it.added }.dedupeFollowers()
    }

    private fun removedForPeriod(tracker: TrackerSummary, period: AnalyticsPeriod): List<Follower> {
        val events = if (period == AnalyticsPeriod.LAST_CHECK) tracker.events.takeLast(1) else periodEvents(tracker.events, period)
        return events.flatMap { it.removed }.dedupeFollowers()
    }

    private fun List<Follower>.dedupeFollowers(): List<Follower> {
        val out = linkedMapOf<String, Follower>()
        forEach { follower -> out[follower.username.lowercase(Locale.US)] = follower }
        return out.values.sortedBy { it.username.lowercase(Locale.US) }
    }

    private fun encodeFollowerSnapshot(followers: Collection<Follower>): String {
        val array = JSONArray()
        followers.sortedBy { it.username.lowercase(Locale.US) }.forEach { follower ->
            array.put(JSONObject().apply {
                put("username", follower.username)
                put("displayName", follower.displayName)
                put("userId", follower.userId)
                put("thumbnailUrl", follower.thumbnailUrl)
            })
        }
        return array.toString()
    }

    private fun encodeTrackerEvents(events: List<TrackerEvent>): String {
        val array = JSONArray()
        events.forEach { event ->
            array.put(JSONObject().apply {
                put("timestampMs", event.timestampMs)
                put("added", JSONArray(encodeFollowerSnapshot(event.added)))
                put("removed", JSONArray(encodeFollowerSnapshot(event.removed)))
            })
        }
        return array.toString()
    }

    private fun decodeTrackerEvents(value: String): List<TrackerEvent> {
        return runCatching {
            val array = JSONArray(value)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val timestamp = item.optLong("timestampMs", 0L).takeIf { it > 0L } ?: continue
                    val added = decodeFollowerSnapshot(item.optJSONArray("added")?.toString().orEmpty()).values.toList()
                    val removed = decodeFollowerSnapshot(item.optJSONArray("removed")?.toString().orEmpty()).values.toList()
                    add(TrackerEvent(timestamp, added, removed))
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun decodeFollowerSnapshot(value: String): Map<String, Follower> {
        return runCatching {
            val array = JSONArray(value)
            val out = linkedMapOf<String, Follower>()
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val username = item.optString("username").trim()
                if (!isUsername(username)) continue
                out[username.lowercase(Locale.US)] = Follower(
                    username = username,
                    displayName = item.optString("displayName").trim(),
                    userId = item.optString("userId").trim(),
                    thumbnailUrl = item.optString("thumbnailUrl").trim()
                )
            }
            out
        }.getOrDefault(emptyMap())
    }

    private fun contentView(context: Context, page: FollowerPageData, auth: AuthSnapshot, onClose: () -> Unit): View {
        var selectedPeriod = AnalyticsPeriod.LAST_CHECK
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            lateinit var root: LinearLayout
            val followerRows = page.followerRows.map { it.copy() }.toMutableList()
            val followingRows = page.following?.rows
                ?.map { FollowerRelation(it.follower, followedByYou = true, followsYou = it.followsYou) }
                ?.toMutableList()
                ?: mutableListOf()
            fun showMain() {
                root.removeAllViews()
                root.addView(header(context, "Analytics", "", onClose))
                val body = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, 0, 0, dp(context, 24))
                }
                fun renderBody() {
                    body.removeAllViews()
                    body.addView(periodSelector(context, selectedPeriod) { period ->
                        selectedPeriod = period
                        renderBody()
                    })
                    page.partialFollowerMessage?.let { message ->
                        body.addView(label(context, message, 12, Color.rgb(255, 214, 10), false).apply {
                            setPadding(dp(context, 16), dp(context, 6), dp(context, 16), dp(context, 4))
                        })
                    }
                    body.addView(trackerView(context, page.tracker, selectedPeriod) { title, users, followsYou ->
                        val rows = users.map { follower ->
                            FollowerRelation(
                                follower = follower,
                                followedByYou = followerRows.firstOrNull { it.follower.username.equals(follower.username, true) }?.followedByYou ?: false,
                                followsYou = followsYou
                            )
                        }.toMutableList()
                        showRelationPage(root, context, title, rows, auth, removeOnUnfollow = false, onBack = ::showMain)
                    })
                    body.addView(chartsView(context, page.tracker))
                    page.following?.let { body.addView(followingSummaryView(context, followingRows) { showRelationPage(root, context, "Your following", followingRows, auth, removeOnUnfollow = true, onBack = ::showMain) }) }
                    body.addView(listPreviewButton(context, "Followers", "${followerRows.size} accounts", "Open follower list") {
                        showRelationPage(root, context, "Followers", followerRows, auth, removeOnUnfollow = false, onBack = ::showMain)
                    })
                }
                renderBody()
                root.addView(ScrollView(context).apply {
                    addView(body)
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            }
            root = this
            showMain()
        }
    }

    private fun showRelationPage(
        root: LinearLayout,
        context: Context,
        title: String,
        rows: MutableList<FollowerRelation>,
        auth: AuthSnapshot,
        removeOnUnfollow: Boolean,
        onBack: () -> Unit
    ) {
        root.removeAllViews()
        var countLabel: TextView? = null
        var adapterRef: RelationAdapter? = null
        val filterMode = if (removeOnUnfollow) RelationFilterMode.FOLLOWS_YOU else RelationFilterMode.FOLLOWED_BY_YOU
        val filterState = RelationFilterState()
        val filterButton = pillButton(context, "Filters", Color.rgb(44, 44, 44), Color.WHITE) {
            showRelationFilterDialog(context, filterMode, filterState) {
                adapterRef?.setFilters(filterState.includePositive, filterState.includeNegative)
                countLabel?.text = "${adapterRef?.filteredCount() ?: rows.size} accounts"
            }
        }
        root.addView(header(context, title, "${rows.size} accounts", onBack, { countLabel = it }, filterButton))
        val bulkBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(context, 12), dp(context, 4), dp(context, 12), dp(context, 8))
        }
        root.addView(RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            setBackgroundColor(Color.BLACK)
            adapterRef = RelationAdapter(rows, auth, removeOnUnfollow, filterMode, root) { selectedCount, adapter ->
                countLabel?.text = "${adapter.filteredCount()} accounts"
                bulkBar.removeAllViews()
                bulkBar.addView(pillButton(context, if (selectedCount == adapter.filteredCount() && adapter.filteredCount() > 0) "Clear" else "Select all", Color.rgb(44, 44, 44), Color.WHITE) {
                    if (selectedCount == adapter.filteredCount() && adapter.filteredCount() > 0) adapter.clearSelection() else adapter.selectAll()
                }, LinearLayout.LayoutParams(0, dp(context, 36), 1f).apply { setMargins(0, 0, dp(context, 6), 0) })
                bulkBar.addView(pillButton(context, "Follow $selectedCount", Color.rgb(52, 199, 89), Color.WHITE) {
                    adapter.applyBulk(follow = true)
                }, LinearLayout.LayoutParams(0, dp(context, 36), 1f).apply { setMargins(dp(context, 3), 0, dp(context, 3), 0) })
                bulkBar.addView(pillButton(context, "Unfollow $selectedCount", Color.rgb(80, 80, 80), Color.WHITE) {
                    adapter.applyBulk(follow = false)
                }, LinearLayout.LayoutParams(0, dp(context, 36), 1f).apply { setMargins(dp(context, 6), 0, 0, 0) })
            }
            adapter = adapterRef
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(bulkBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun engagementView(
        context: Context,
        root: LinearLayout,
        auth: AuthSnapshot,
        followerRows: MutableList<FollowerRelation>,
        followingRows: MutableList<FollowerRelation>,
        analytics: AccountAnalytics,
        onBack: () -> Unit
    ): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 16), dp(context, 8), dp(context, 16), dp(context, 16))
            addView(analyticsCard(context, "Your best followers", listOf(
                AnalyticsAction("Most likes to you") { showEngagementUsersPage(root, context, "Most likes to you", analytics.engagementUsers.sortedByDescending { it.likeCount }.filter { it.likeCount > 0 }, onBack) },
                AnalyticsAction("Most comments to you") { showEngagementUsersPage(root, context, "Most comments to you", analytics.engagementUsers.sortedByDescending { it.commentCount }.filter { it.commentCount > 0 }, onBack) },
                AnalyticsAction("Most likes and comments to you") { showEngagementUsersPage(root, context, "Most likes and comments to you", analytics.engagementUsers.sortedByDescending { it.total }.filter { it.total > 0 }, onBack) }
            )))
            addView(analyticsCard(context, "Your ghost followers", listOf(
                AnalyticsAction("Least likes to you") { showEngagementUsersPage(root, context, "Least likes to you", analytics.followerEngagement.sortedBy { it.likeCount }, onBack) },
                AnalyticsAction("Least comments to you") { showEngagementUsersPage(root, context, "Least comments to you", analytics.followerEngagement.sortedBy { it.commentCount }, onBack) },
                AnalyticsAction("Least likes and comments to you") { showEngagementUsersPage(root, context, "Least likes and comments to you", analytics.followerEngagement.sortedBy { it.total }, onBack) },
                AnalyticsAction("No likes and comments to you") { showEngagementUsersPage(root, context, "No likes and comments to you", analytics.followerEngagement.filter { it.total == 0 }, onBack) }
            )))
            addView(analyticsCard(context, "Interactions", listOf(
                AnalyticsAction("You like but don't follow") { showEngagementUsersPage(root, context, "You like but don't follow", analytics.engagementUsers.filter { !it.followedByYou }.sortedByDescending { it.total }, onBack) },
                AnalyticsAction("Those who like or comment but do not follow") { showEngagementUsersPage(root, context, "Those who like or comment but do not follow", analytics.engagementUsers.filter { !it.followsYou }.sortedByDescending { it.total }, onBack) },
                AnalyticsAction("Those who tagged you") { showMediaMetricPage(root, context, "Those who tagged you", sortRecent(analytics.taggedMedia), "tagged", onBack) }
            )))
            addView(analyticsCard(context, "Delete", listOf(
                AnalyticsAction("Users who deleted their like") { showEngagementUsersPage(root, context, "Users who deleted their like", analytics.deletedLikes, onBack) },
                AnalyticsAction("Users who deleted their comment") { showEngagementUsersPage(root, context, "Users who deleted their comment", analytics.deletedComments, onBack) }
            )))
        }
    }

    private fun mediasView(
        context: Context,
        root: LinearLayout,
        analytics: AccountAnalytics,
        onBack: () -> Unit
    ): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 16), dp(context, 8), dp(context, 16), dp(context, 16))
            addView(analyticsCard(context, "Your best posts", listOf(
                AnalyticsAction("Most liked posts") { showMediaMetricPage(root, context, "Most liked posts", sortMediaDesc(analytics.media, "likes"), "likes", onBack) },
                AnalyticsAction("Most commented posts") { showMediaMetricPage(root, context, "Most commented posts", sortMediaDesc(analytics.media, "comments"), "comments", onBack) },
                AnalyticsAction("Most viewed posts") { showMediaMetricPage(root, context, "Most viewed posts", sortMediaDesc(analytics.media, "views"), "views", onBack) },
                AnalyticsAction("Most liked and commented posts") { showMediaMetricPage(root, context, "Most liked and commented posts", sortMediaDesc(analytics.media, "engagements"), "engagements", onBack) }
            )))
            addView(analyticsCard(context, "Your worst posts", listOf(
                AnalyticsAction("Least liked posts") { showMediaMetricPage(root, context, "Least liked posts", sortMediaAsc(analytics.media, "likes"), "likes", onBack) },
                AnalyticsAction("Least commented posts") { showMediaMetricPage(root, context, "Least commented posts", sortMediaAsc(analytics.media, "comments"), "comments", onBack) },
                AnalyticsAction("Least viewed posts") { showMediaMetricPage(root, context, "Least viewed posts", sortMediaAsc(analytics.media, "views"), "views", onBack) },
                AnalyticsAction("Least liked and commented posts") { showMediaMetricPage(root, context, "Least liked and commented posts", sortMediaAsc(analytics.media, "engagements"), "engagements", onBack) }
            )))
        }
    }

    private fun analyticsCard(context: Context, title: String, actions: List<AnalyticsAction>): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.rgb(28, 36, 54))
                cornerRadius = dp(context, 8).toFloat()
            }
            addView(label(context, title, 20, Color.WHITE, true).apply {
                setPadding(dp(context, 14), dp(context, 14), dp(context, 14), dp(context, 12))
            })
            actions.forEach { action -> addView(analyticsRow(context, action)) }
        }.also {
            it.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 0, dp(context, 14))
            }
        }
    }

    private fun analyticsRow(context: Context, action: AnalyticsAction): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(context, 14), 0, dp(context, 14), 0)
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                setStroke(dp(context, 1), Color.rgb(38, 48, 68))
            }
            addView(label(context, action.title, 16, Color.WHITE, false), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(label(context, ">", 28, Color.rgb(80, 170, 255), false).apply {
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(dp(context, 34), dp(context, 56)))
            isClickable = true
            setOnClickListener { action.open() }
        }
    }

    private fun mediaMetricValue(media: MediaMetric, metric: String): Int {
        return when (metric) {
            "likes" -> media.likeCount
            "comments" -> media.commentCount
            "views" -> media.viewCount
            "engagements" -> media.likeCount + media.commentCount
            else -> 0
        }
    }

    private fun sortMediaDesc(media: List<MediaMetric>, metric: String): List<MediaMetric> {
        return media.sortedWith(compareByDescending<MediaMetric> { mediaMetricValue(it, metric) }.thenByDescending { it.timestampMs })
    }

    private fun sortMediaAsc(media: List<MediaMetric>, metric: String): List<MediaMetric> {
        return media.sortedWith(compareBy<MediaMetric> { mediaMetricValue(it, metric) }.thenByDescending { it.timestampMs })
    }

    private fun sortRecent(media: List<MediaMetric>): List<MediaMetric> {
        return media.sortedByDescending { it.timestampMs }
    }

    private fun showEngagementUsersPage(root: LinearLayout, context: Context, title: String, users: List<EngagementUser>, onBack: () -> Unit) {
        root.removeAllViews()
        root.addView(header(context, title, "${users.size} accounts", onBack))
        if (users.isEmpty()) {
            showEmptyBody(root, context, "No data yet", "Open Analytics again after recent posts have likes or comments.")
            return
        }
        root.addView(ScrollView(context).apply {
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(context, 8), dp(context, 6), dp(context, 8), dp(context, 16))
                users.forEach { addView(engagementUserRow(context, it)) }
            })
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    private fun engagementUserRow(context: Context, user: EngagementUser): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10))
            background = GradientDrawable().apply {
                setColor(Color.BLACK)
                setStroke(dp(context, 1), Color.rgb(38, 38, 38))
                cornerRadius = dp(context, 4).toFloat()
            }
            addView(AvatarImageView(context).apply { loadProfileImage(this, user.follower.thumbnailUrl) }, LinearLayout.LayoutParams(dp(context, 52), dp(context, 52)))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(context, 12), 0, 0, 0)
                addView(label(context, user.follower.displayName.ifBlank { "@${user.follower.username}" }, 15, Color.WHITE, true))
                addView(label(context, "@${user.follower.username}", 12, Color.rgb(168, 168, 168), false))
                addView(label(context, "${user.likeCount} likes  ${user.commentCount} comments", 12, Color.rgb(80, 170, 255), false))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            setOnClickListener {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.instagram.com/${user.follower.username}/")).apply { setPackage(context.packageName) })
            }
        }.also {
            it.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 0, dp(context, 6))
            }
        }
    }

    private fun showMediaMetricPage(root: LinearLayout, context: Context, title: String, media: List<MediaMetric>, metric: String, onBack: () -> Unit) {
        root.removeAllViews()
        root.addView(header(context, title, "${media.size} posts", onBack))
        if (media.isEmpty()) {
            showEmptyBody(root, context, "No media yet", "Analytics could not fetch recent posts for this account.")
            return
        }
        root.addView(ScrollView(context).apply {
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(context, 8), dp(context, 6), dp(context, 8), dp(context, 16))
                media.forEach { addView(mediaMetricRow(context, it, metric)) }
            })
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    private fun mediaMetricRow(context: Context, media: MediaMetric, metric: String): View {
        val metricValue = mediaMetricValue(media, metric)
        val metricText = if (metric == "tagged") "tagged post" else "$metricValue $metric"
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10))
            background = GradientDrawable().apply {
                setColor(Color.BLACK)
                setStroke(dp(context, 1), Color.rgb(38, 38, 38))
                cornerRadius = dp(context, 4).toFloat()
            }
            addView(ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(Color.rgb(30, 30, 30))
                loadProfileImage(this, media.thumbnailUrl)
            }, LinearLayout.LayoutParams(dp(context, 64), dp(context, 64)))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(context, 12), 0, 0, 0)
                addView(label(context, media.title, 14, Color.WHITE, true))
                addView(label(context, metricText, 12, Color.rgb(80, 170, 255), false))
                addView(label(context, "${media.likeCount} likes  ${media.commentCount} comments  ${media.viewCount} views", 11, Color.rgb(168, 168, 168), false))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            setOnClickListener {
                val code = media.code.ifBlank { InstagramActivityHistoryStore.publicCodeFromIdOrUrl(media.mediaId, "") }
                if (code.isNotBlank()) context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.instagram.com/p/$code/")).apply { setPackage(context.packageName) })
            }
        }.also {
            it.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 0, dp(context, 6))
            }
        }
    }

    private fun showEmptyAnalyticsPage(root: LinearLayout, context: Context, title: String, message: String, onBack: () -> Unit) {
        root.removeAllViews()
        root.addView(header(context, title, "0 items", onBack))
        showEmptyBody(root, context, "No data yet", message)
    }

    private fun showEmptyBody(root: LinearLayout, context: Context, title: String, message: String) {
        root.addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(context, 24), dp(context, 48), dp(context, 24), dp(context, 48))
            addView(label(context, title, 22, Color.WHITE, true).apply { gravity = Gravity.CENTER })
            addView(label(context, message, 14, Color.rgb(168, 168, 168), false).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(context, 10), 0, 0)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    private fun showHistoryPage(root: LinearLayout, context: Context, title: String, items: List<InstagramActivityHistoryStore.Item>, onBack: () -> Unit) {
        root.removeAllViews()
        root.addView(header(context, title, "${items.size} items", onBack))
        if (items.isEmpty()) {
            root.addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(context, 24), dp(context, 48), dp(context, 24), dp(context, 48))
                addView(label(context, "No data yet", 22, Color.WHITE, true).apply { gravity = Gravity.CENTER })
                addView(label(context, "Open posts, reels, or stories while Activity History is enabled and they will appear here.", 14, Color.rgb(168, 168, 168), false).apply {
                    gravity = Gravity.CENTER
                    setPadding(0, dp(context, 10), 0, 0)
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            return
        }
        root.addView(ScrollView(context).apply {
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(context, 8), dp(context, 6), dp(context, 8), dp(context, 16))
                items.forEach { addView(historyRow(context, it)) }
            })
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    private fun historyRow(context: Context, item: InstagramActivityHistoryStore.Item): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10))
            background = GradientDrawable().apply {
                setColor(Color.BLACK)
                setStroke(dp(context, 1), Color.rgb(38, 38, 38))
                cornerRadius = dp(context, 4).toFloat()
            }
            addView(ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(Color.rgb(30, 30, 30))
                loadProfileImage(this, InstagramActivityHistoryStore.buildThumbnailUrl(item))
            }, LinearLayout.LayoutParams(dp(context, 58), dp(context, 58)))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(context, 12), 0, 0, 0)
                val primary = item.title.ifBlank { item.username.ifBlank { item.typeLabel() } }
                addView(label(context, primary, 15, Color.WHITE, true))
                val secondary = item.caption.ifBlank { item.username.takeIf { it.isNotBlank() }?.let { "@$it" }.orEmpty() }
                addView(label(context, secondary.ifBlank { item.typeLabel() }, 12, Color.rgb(168, 168, 168), false))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            setOnClickListener {
                val url = InstagramActivityHistoryStore.buildOpenUrl(item)
                if (url.isBlank()) {
                    Toast.makeText(context, "Could not open this item", Toast.LENGTH_SHORT).show()
                } else {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply { setPackage(context.packageName) })
                }
            }
        }.also {
            it.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 0, dp(context, 6))
            }
        }
    }

    private fun listPreviewButton(context: Context, title: String, subtitle: String, action: String, onClick: () -> Unit): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(context, 16), dp(context, 12), dp(context, 16), dp(context, 12))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(sectionTitle(context, title))
                addView(label(context, subtitle, 13, Color.rgb(168, 168, 168), false))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(pillButton(context, action, Color.rgb(52, 199, 89), Color.WHITE) { onClick() })
        }
    }

    private fun showRelationFilterDialog(
        context: Context,
        mode: RelationFilterMode,
        state: RelationFilterState,
        onApply: () -> Unit
    ) {
        val positiveLabel = when (mode) {
            RelationFilterMode.FOLLOWED_BY_YOU -> "I follow this account"
            RelationFilterMode.FOLLOWS_YOU -> "They follow me"
        }
        val negativeLabel = when (mode) {
            RelationFilterMode.FOLLOWED_BY_YOU -> "I do not follow this account"
            RelationFilterMode.FOLLOWS_YOU -> "They do not follow me"
        }
        val positive = CheckBox(context).apply {
            text = positiveLabel
            textSize = 15f
            setTextColor(Color.WHITE)
            isChecked = state.includePositive
            setPadding(dp(context, 8), dp(context, 8), dp(context, 8), dp(context, 8))
        }
        val negative = CheckBox(context).apply {
            text = negativeLabel
            textSize = 15f
            setTextColor(Color.WHITE)
            isChecked = state.includeNegative
            setPadding(dp(context, 8), dp(context, 8), dp(context, 8), dp(context, 8))
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 18), dp(context, 8), dp(context, 18), 0)
            addView(positive)
            addView(negative)
        }
        AlertDialog.Builder(context)
            .setTitle("Filters")
            .setView(content)
            .setPositiveButton("Apply") { _, _ ->
                state.includePositive = positive.isChecked
                state.includeNegative = negative.isChecked
                onApply()
            }
            .setNeutralButton("Reset") { _, _ ->
                state.includePositive = true
                state.includeNegative = true
                onApply()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun periodSelector(context: Context, selected: AnalyticsPeriod, onSelect: (AnalyticsPeriod) -> Unit): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(context, 12), dp(context, 6), dp(context, 12), dp(context, 6))
            AnalyticsPeriod.values().forEach { period ->
                addView(TextView(context).apply {
                    text = period.label
                    textSize = 12f
                    gravity = Gravity.CENTER
                    setTextColor(if (period == selected) Color.BLACK else Color.WHITE)
                    background = GradientDrawable().apply {
                        cornerRadius = dp(context, 14).toFloat()
                        setColor(if (period == selected) Color.WHITE else Color.rgb(22, 22, 22))
                        setStroke(dp(context, 1), Color.rgb(58, 58, 58))
                    }
                    setPadding(dp(context, 9), 0, dp(context, 9), 0)
                    setOnClickListener { onSelect(period) }
                }, LinearLayout.LayoutParams(0, dp(context, 32), 1f).apply {
                    setMargins(dp(context, 3), 0, dp(context, 3), 0)
                })
            }
        }
    }

    private fun trackerView(
        context: Context,
        tracker: TrackerSummary,
        period: AnalyticsPeriod,
        onOpenSet: (String, List<Follower>, Boolean) -> Unit
    ): View {
        val added = addedForPeriod(tracker, period)
        val removed = removedForPeriod(tracker, period)
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 16), dp(context, 8), dp(context, 16), dp(context, 14))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                addView(trackerCircle(context, "+${added.size}", "New", Color.rgb(52, 199, 89)) {
                    onOpenSet("New Followers", added, true)
                })
                addView(trackerCircle(context, "-${removed.size}", "Lost", Color.rgb(255, 69, 58)) {
                    onOpenSet("Lost Followers", removed, false)
                })
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            val status = when {
                tracker.baseline -> "Baseline saved for this account"
                added.isEmpty() && removed.isEmpty() -> period.emptyText
                else -> null
            }
            status?.let {
                addView(label(context, it, 12, Color.rgb(168, 168, 168), false).apply {
                    gravity = Gravity.CENTER
                    setPadding(0, dp(context, 8), 0, 0)
                })
            }
        }
    }

    private fun trackerCircle(context: Context, count: String, title: String, color: Int, onClick: () -> Unit): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.rgb(10, 10, 10))
                setStroke(dp(context, 2), color)
            }
            addView(label(context, count, 22, color, true).apply {
                gravity = Gravity.CENTER
            })
            addView(label(context, title, 11, Color.rgb(168, 168, 168), false).apply {
                gravity = Gravity.CENTER
            })
            isClickable = true
            setOnClickListener { onClick() }
        }.also {
            it.layoutParams = LinearLayout.LayoutParams(dp(context, 86), dp(context, 86)).apply {
                setMargins(dp(context, 10), 0, dp(context, 10), 0)
            }
        }
    }

    private fun chartsView(context: Context, tracker: TrackerSummary): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 16), dp(context, 8), dp(context, 16), dp(context, 8))
            addView(sectionTitle(context, "Follower growth"))
            addView(TrendChartView(context, tracker.growthPoints(), Color.rgb(116, 226, 239), "Followers"))
            addView(sectionTitle(context, "New follower trend"))
            addView(TrendChartView(context, tracker.eventPoints { it.added.size }, Color.rgb(52, 199, 89), "New"))
            addView(sectionTitle(context, "Lost follower trend"))
            addView(TrendChartView(context, tracker.eventPoints { it.removed.size }, Color.rgb(255, 69, 58), "Lost"))
        }
    }

    private fun TrackerSummary.growthPoints(): List<ChartPoint> {
        var count = currentCount - events.sumOf { it.added.size } + events.sumOf { it.removed.size }
        val sorted = events.sortedBy { it.timestampMs }
        val points = mutableListOf(ChartPoint(sorted.firstOrNull()?.timestampMs ?: System.currentTimeMillis(), count.coerceAtLeast(0)))
        events.sortedBy { it.timestampMs }.forEach { event ->
            count += event.added.size - event.removed.size
            points += ChartPoint(event.timestampMs, count.coerceAtLeast(0))
        }
        if (points.size == 1) points += ChartPoint(System.currentTimeMillis(), currentCount)
        return points
    }

    private fun TrackerSummary.eventPoints(value: (TrackerEvent) -> Int): List<ChartPoint> {
        val points = events.sortedBy { it.timestampMs }.map { ChartPoint(it.timestampMs, value(it).coerceAtLeast(0)) }
        return points.ifEmpty {
            val now = System.currentTimeMillis()
            listOf(ChartPoint(now - 86_400_000L, 0), ChartPoint(now, 0))
        }
    }

    private fun buildFollowerRows(followers: List<Follower>, following: List<Follower>): List<FollowerRelation> {
        val followingNames = following.map { it.username.lowercase(Locale.US) }.toHashSet()
        return followers.sortedBy { it.username.lowercase(Locale.US) }.map {
            FollowerRelation(it, followedByYou = followingNames.contains(it.username.lowercase(Locale.US)), followsYou = true)
        }
    }

    private fun buildFollowingSummary(followers: List<Follower>, following: List<Follower>): FollowingSummary? {
        if (following.isEmpty()) return null
        val followerNames = followers.map { it.username.lowercase(Locale.US) }.toHashSet()
        val rows = following.sortedBy { it.username.lowercase(Locale.US) }.map { followed ->
            FollowingStatus(followed, followsYou = followerNames.contains(followed.username.lowercase(Locale.US)))
        }
        return FollowingSummary(rows)
    }

    private fun followingSummaryView(context: Context, rows: List<FollowerRelation>, onOpen: () -> Unit): View {
        val followsBack = rows.count { it.followsYou }
        val notFollowing = rows.size - followsBack
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 16), dp(context, 10), dp(context, 16), dp(context, 10))
            addView(sectionTitle(context, "Your following"))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(label(context, "${rows.size} total following", 16, Color.WHITE, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(pillButton(context, "Reveal full list", Color.rgb(80, 170, 255), Color.WHITE) { onOpen() })
            })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                val total = rows.size.coerceAtLeast(1)
                addView(barSegment(context, notFollowing.toString(), Color.rgb(80, 170, 255)), LinearLayout.LayoutParams(0, dp(context, 44), notFollowing.toFloat() / total).apply {
                    setMargins(0, 0, dp(context, 3), 0)
                })
                addView(barSegment(context, followsBack.toString(), Color.rgb(52, 199, 89)), LinearLayout.LayoutParams(0, dp(context, 44), followsBack.toFloat() / total).apply {
                    setMargins(dp(context, 3), 0, 0, 0)
                })
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 44)).apply {
                setMargins(0, dp(context, 10), 0, dp(context, 8))
            })
            addView(label(context, "Blue: does not follow you   Green: follows you", 12, Color.rgb(168, 168, 168), false))
        }
    }

    private fun barSegment(context: Context, value: String, color: Int): TextView {
        return label(context, value, 15, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = dp(context, 10).toFloat()
                setColor(color)
            }
        }
    }

    private fun pillButton(context: Context, textValue: String, color: Int, textColor: Int, onClick: () -> Unit): TextView {
        return label(context, textValue, 12, textColor, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(context, 12), 0, dp(context, 12), 0)
            background = GradientDrawable().apply {
                cornerRadius = dp(context, 16).toFloat()
                setColor(color)
            }
            isClickable = true
            setOnClickListener { onClick() }
            minHeight = dp(context, 32)
        }
    }

    private fun followingStatusRow(context: Context, status: FollowingStatus): View {
        val color = if (status.followsYou) Color.rgb(52, 199, 89) else Color.rgb(80, 170, 255)
        val text = if (status.followsYou) "Follows you" else "Does not follow you"
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(context, 8), 0, 0)
            addView(View(context).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color)
                }
            }, LinearLayout.LayoutParams(dp(context, 10), dp(context, 10)).apply {
                setMargins(0, 0, dp(context, 10), 0)
            })
            addView(label(context, "@${status.follower.username}", 13, Color.WHITE, false), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(label(context, text, 12, color, false))
        }
    }

    private fun followerRow(context: Context, follower: Follower): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(context, 16), dp(context, 10), dp(context, 16), dp(context, 10))
            background = GradientDrawable().apply {
                setColor(Color.BLACK)
                setStroke(dp(context, 1), Color.rgb(38, 38, 38))
                cornerRadius = dp(context, 4).toFloat()
            }
            setOnClickListener {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.instagram.com/${follower.username}/")).apply {
                    setPackage(context.packageName)
                })
            }
            addView(AvatarImageView(context).apply {
                loadProfileImage(this, follower.thumbnailUrl)
            }, LinearLayout.LayoutParams(dp(context, 48), dp(context, 48)))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(context, 14), 0, 0, 0)
                addView(label(context, follower.displayName.ifBlank { "@${follower.username}" }, 16, Color.WHITE, true))
                addView(label(context, "@${follower.username}", 13, Color.rgb(168, 168, 168), false))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }.also {
            it.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(context, 8), dp(context, 4), dp(context, 8), dp(context, 4))
            }
        }
    }

    private fun sectionTitle(context: Context, text: String): TextView {
        return label(context, text, 18, Color.WHITE, true).apply {
            setPadding(0, dp(context, 10), 0, dp(context, 6))
        }
    }

    private fun showFollowerSetDialog(context: Context, title: String, users: List<Follower>, auth: AuthSnapshot) {
        if (users.isEmpty()) {
            Toast.makeText(context, "No accounts in this category", Toast.LENGTH_SHORT).show()
            return
        }
        val names = users.map { "@${it.username}${it.displayName.takeIf { name -> name.isNotBlank() }?.let { name -> " - $name" }.orEmpty()}" }.toTypedArray()
        AlertDialog.Builder(context)
            .setTitle(title)
            .setItems(names, null)
            .setPositiveButton("Follow all") { _, _ -> batchFriendshipAction(context, users, auth, follow = true) }
            .setNegativeButton("Unfollow all") { _, _ -> batchFriendshipAction(context, users, auth, follow = false) }
            .setNeutralButton("Close", null)
            .show()
    }

    private fun batchFriendshipAction(context: Context, users: List<Follower>, auth: AuthSnapshot, follow: Boolean) {
        Toast.makeText(context, if (follow) "Following..." else "Unfollowing...", Toast.LENGTH_SHORT).show()
        executor.execute {
            val failures = users.count { user ->
                runCatching { requestFriendshipAction(user.userId, auth, follow) }.isFailure
            }
            mainHandler.post {
                val ok = users.size - failures
                Toast.makeText(context, "$ok done${if (failures > 0) ", $failures failed" else ""}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun requestFriendshipAction(userId: String, auth: AuthSnapshot, follow: Boolean) {
        if (userId.isBlank()) error("Missing user id")
        val action = if (follow) "create" else "destroy"
        requestJson(
            "https://i.instagram.com/api/v1/friendships/$action/$userId/",
            auth,
            method = "POST",
            body = "_uuid=${Uri.encode(UUID.randomUUID().toString())}"
        )
    }

    private fun loadProfileImage(target: ImageView, url: String) {
        target.setBackgroundColor(Color.TRANSPARENT)
        target.setImageDrawable(null)
        if (url.isBlank()) return
        imageCache[url]?.let {
            target.setImageBitmap(it)
            return
        }
        val expectedUrl = url
        target.tag = expectedUrl
        executor.execute {
            val bitmap = runCatching {
                val connection = URL(expectedUrl).openConnection() as HttpURLConnection
                connection.connectTimeout = 8_000
                connection.readTimeout = 8_000
                connection.setRequestProperty("User-Agent", ANDROID_USER_AGENT)
                connection.inputStream.use { BitmapFactory.decodeStream(it) }
            }.getOrNull()
            if (bitmap != null) imageCache[expectedUrl] = bitmap
            mainHandler.post {
                if (bitmap != null && target.tag == expectedUrl) target.setImageBitmap(bitmap)
            }
        }
    }

    private fun loadingView(context: Context): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.BLACK)
            addView(label(context, "Fetching analytics...", 18, Color.WHITE, true).apply {
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun header(
        context: Context,
        title: String,
        subtitle: String,
        onClose: () -> Unit,
        onSubtitle: ((TextView) -> Unit)? = null,
        actionView: View? = null
    ): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(context, 8), dp(context, 16), dp(context, 12), dp(context, 10))
            addView(label(context, "<", 30, Color.WHITE, false).apply {
                gravity = Gravity.CENTER
                setOnClickListener { onClose() }
            }, LinearLayout.LayoutParams(dp(context, 52), dp(context, 52)))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(label(context, title, 24, Color.WHITE, true))
                if (subtitle.isNotBlank()) {
                    addView(label(context, subtitle, 13, Color.rgb(168, 168, 168), false).also { onSubtitle?.invoke(it) })
                }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            actionView?.let {
                addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(context, 36)).apply {
                    setMargins(dp(context, 8), 0, 0, 0)
                })
            }
        }
    }

    private class Adapter(private val followers: List<Follower>) : RecyclerView.Adapter<Holder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            return Holder(FrameLayout(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            })
        }

        override fun getItemCount(): Int = followers.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val context = holder.container.context
            val follower = followers[position]
            holder.container.removeAllViews()
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(context, 16), dp(context, 10), dp(context, 16), dp(context, 10))
                background = GradientDrawable().apply {
                    setColor(Color.BLACK)
                    setStroke(dp(context, 1), Color.rgb(38, 38, 38))
                    cornerRadius = dp(context, 4).toFloat()
                }
                setOnClickListener {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.instagram.com/${follower.username}/")).apply {
                        setPackage(context.packageName)
                    })
                }
            }
            row.addView(AvatarImageView(context).apply {
                loadProfileImage(this, follower.thumbnailUrl)
            }, LinearLayout.LayoutParams(dp(context, 48), dp(context, 48)))
            row.addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(context, 14), 0, 0, 0)
                addView(label(context, follower.displayName.ifBlank { "@${follower.username}" }, 16, Color.WHITE, true))
                addView(label(context, "@${follower.username}", 13, Color.rgb(168, 168, 168), false))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            holder.container.addView(row, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(context, 8), dp(context, 4), dp(context, 8), dp(context, 4))
            })
        }
    }

    private class Holder(val container: FrameLayout) : RecyclerView.ViewHolder(container)

    private class RelationAdapter(
        private val rows: MutableList<FollowerRelation>,
        private val auth: AuthSnapshot,
        private val removeOnUnfollow: Boolean,
        private val filterMode: RelationFilterMode,
        private val hostRoot: LinearLayout,
        private val onSelectionChanged: (Int, RelationAdapter) -> Unit
    ) : RecyclerView.Adapter<Holder>() {
        private val selected = linkedSetOf<String>()
        private var includePositive = true
        private var includeNegative = true

        init {
            mainHandler.post { onSelectionChanged(0, this) }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            return Holder(FrameLayout(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            })
        }

        override fun getItemCount(): Int = visibleRows().size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val context = holder.container.context
            val rowData = visibleRows()[position]
            val follower = rowData.follower
            holder.container.removeAllViews()
            val actionText = if (rowData.followedByYou) "Unfollow" else "Follow"
            val selectedNow = selected.contains(follower.key)
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(context, 16), dp(context, 10), dp(context, 16), dp(context, 10))
                background = GradientDrawable().apply {
                    setColor(if (selectedNow) Color.rgb(12, 28, 18) else Color.BLACK)
                    setStroke(dp(context, 1), if (selectedNow) Color.rgb(52, 199, 89) else Color.rgb(38, 38, 38))
                    cornerRadius = dp(context, 4).toFloat()
                }
                setOnClickListener {
                    toggleSelection(follower.key)
                }
                setOnLongClickListener {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.instagram.com/${follower.username}/")).apply {
                        setPackage(context.packageName)
                    })
                    true
                }
            }
            row.addView(label(context, if (selectedNow) "✓" else "", 18, Color.WHITE, true).apply {
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (selectedNow) Color.rgb(52, 199, 89) else Color.TRANSPARENT)
                    setStroke(dp(context, 1), Color.rgb(86, 86, 86))
                }
            }, LinearLayout.LayoutParams(dp(context, 30), dp(context, 30)).apply {
                setMargins(0, 0, dp(context, 10), 0)
            })
            row.addView(AvatarImageView(context).apply {
                loadProfileImage(this, follower.thumbnailUrl)
            }, LinearLayout.LayoutParams(dp(context, 52), dp(context, 52)))
            row.addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(context, 14), 0, dp(context, 10), 0)
                addView(label(context, follower.displayName.ifBlank { "@${follower.username}" }, 15, Color.WHITE, true))
                addView(label(context, "@${follower.username}", 12, Color.rgb(168, 168, 168), false))
                val status = when {
                    removeOnUnfollow && rowData.followsYou -> "Follows you"
                    removeOnUnfollow -> "Does not follow you"
                    rowData.followedByYou -> "You follow this account"
                    else -> "You do not follow this account"
                }
                addView(label(context, status, 11, if (rowData.followsYou) Color.rgb(52, 199, 89) else Color.rgb(80, 170, 255), false))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(pillButton(context, actionText, if (rowData.followedByYou) Color.rgb(44, 44, 44) else Color.rgb(52, 199, 89), Color.WHITE) {
                applySingle(position, follow = !rowData.followedByYou)
            }, LinearLayout.LayoutParams(dp(context, 104), dp(context, 36)))
            holder.container.addView(row, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(context, 8), dp(context, 4), dp(context, 8), dp(context, 4))
            })
        }

        fun setFilters(nextIncludePositive: Boolean, nextIncludeNegative: Boolean) {
            includePositive = nextIncludePositive
            includeNegative = nextIncludeNegative
            selected.removeAll { key -> visibleRows().none { it.follower.key == key } }
            notifyDataSetChanged()
            onSelectionChanged(selected.size, this)
        }

        fun filteredCount(): Int = visibleRows().size

        fun selectAll() {
            selected.clear()
            visibleRows().forEach { selected += it.follower.key }
            notifyDataSetChanged()
            onSelectionChanged(selected.size, this)
        }

        fun clearSelection() {
            selected.clear()
            notifyDataSetChanged()
            onSelectionChanged(0, this)
        }

        fun applyBulk(follow: Boolean) {
            val chosen = visibleRows().filter { selected.contains(it.follower.key) }
            if (chosen.isEmpty()) {
                Toast.makeText(hostRoot.context, "Select accounts first", Toast.LENGTH_SHORT).show()
                return
            }
            applyRows(chosen, follow)
        }

        private fun toggleSelection(key: String) {
            if (!selected.add(key)) selected.remove(key)
            notifyDataSetChanged()
            onSelectionChanged(selected.size, this)
        }

        private fun applySingle(position: Int, follow: Boolean) {
            visibleRows().getOrNull(position)?.let { applyRows(listOf(it), follow) }
        }

        private fun applyRows(targets: List<FollowerRelation>, follow: Boolean) {
            val context = hostRoot.context
            val before = targets.map { it.copy() }
            val action = if (follow) "Follow" else "Unfollow"
            Toast.makeText(context, "$action ${targets.size} account${if (targets.size == 1) "" else "s"}...", Toast.LENGTH_SHORT).show()
            executor.execute {
                val succeeded = targets.filter { relation ->
                    runCatching { requestFriendshipAction(relation.follower.userId, auth, follow) }.isSuccess
                }
                mainHandler.post {
                    if (succeeded.isEmpty()) {
                        Toast.makeText(context, "$action failed", Toast.LENGTH_SHORT).show()
                        return@post
                    }
                    applyLocalState(succeeded, follow)
                    clearSelection()
                    showUndoPrompt(context, hostRoot, "${action}ed ${succeeded.size}") {
                        undoRows(before.filter { old -> succeeded.any { it.follower.key == old.follower.key } })
                    }
                }
            }
        }

        private fun applyLocalState(targets: List<FollowerRelation>, follow: Boolean) {
            targets.forEach { target ->
                val index = rows.indexOfFirst { it.follower.key == target.follower.key }
                if (index < 0) return@forEach
                if (!follow && removeOnUnfollow) {
                    rows.removeAt(index)
                } else {
                    rows[index].followedByYou = follow
                }
            }
            notifyDataSetChanged()
        }

        private fun undoRows(previous: List<FollowerRelation>) {
            if (previous.isEmpty()) return
            val context = hostRoot.context
            executor.execute {
                previous.forEach { old ->
                    runCatching { requestFriendshipAction(old.follower.userId, auth, follow = old.followedByYou) }
                }
                mainHandler.post {
                    previous.forEach { old ->
                        val index = rows.indexOfFirst { it.follower.key == old.follower.key }
                        if (index >= 0) {
                            rows[index].followedByYou = old.followedByYou
                        } else {
                            rows.add(old.copy())
                            rows.sortBy { it.follower.username.lowercase(Locale.US) }
                        }
                    }
                    notifyDataSetChanged()
                    onSelectionChanged(selected.size, this@RelationAdapter)
                    Toast.makeText(context, "Undo complete", Toast.LENGTH_SHORT).show()
                }
            }
        }

        private fun visibleRows(): List<FollowerRelation> {
            return rows.filter { relation ->
                val positive = when (filterMode) {
                    RelationFilterMode.FOLLOWED_BY_YOU -> relation.followedByYou
                    RelationFilterMode.FOLLOWS_YOU -> relation.followsYou
                }
                (positive && includePositive) || (!positive && includeNegative)
            }
        }
    }

    private fun pillButton(context: Context, textValue: String, color: Int, textColor: Int, onClick: () -> Unit, params: ViewGroup.LayoutParams): TextView {
        return pillButton(context, textValue, color, textColor, onClick).apply {
            layoutParams = params
        }
    }

    private fun showUndoPrompt(context: Context, hostRoot: LinearLayout, message: String, undo: () -> Unit) {
        val tagValue = "purrfect_follower_undo"
        for (i in hostRoot.childCount - 1 downTo 0) {
            if (hostRoot.getChildAt(i).tag == tagValue) hostRoot.removeViewAt(i)
        }
        val prompt = LinearLayout(context).apply {
            tag = tagValue
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(context, 14), dp(context, 7), dp(context, 10), dp(context, 7))
            background = GradientDrawable().apply {
                setColor(Color.rgb(22, 22, 22))
                setStroke(dp(context, 1), Color.rgb(58, 58, 58))
                cornerRadius = dp(context, 22).toFloat()
            }
        }
        prompt.addView(label(context, message, 13, Color.WHITE, true), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, 0, dp(context, 12), 0)
        })
        prompt.addView(pillButton(context, "Undo", Color.WHITE, Color.BLACK) {
            if (prompt.parent === hostRoot) hostRoot.removeView(prompt)
            undo()
        })
        val insertAt = (hostRoot.childCount - 1).coerceAtLeast(0)
        hostRoot.addView(prompt, insertAt, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setMargins(dp(context, 16), dp(context, 8), dp(context, 16), dp(context, 14))
        })
        mainHandler.postDelayed({
            if (prompt.parent === hostRoot) hostRoot.removeView(prompt)
        }, 5_000L)
    }

    private class TrendChartView(
        context: Context,
        private val points: List<ChartPoint>,
        private val accent: Int,
        private val yLabel: String
    ) : View(context) {
        private val dateFormat = SimpleDateFormat("MMM d", Locale.US)
        private var selectedIndex: Int? = null
        private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(58, 58, 58)
            strokeWidth = dp(context, 1).toFloat()
        }
        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accent
            strokeWidth = dp(context, 2).toFloat()
            style = Paint.Style.STROKE
        }
        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accent
            style = Paint.Style.FILL
        }
        private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(34, 34, 34)
            strokeWidth = dp(context, 1).toFloat()
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(170, 170, 170)
            textSize = dp(context, 10).toFloat()
        }
        private val selectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            strokeWidth = dp(context, 1).toFloat()
        }

        init {
            isClickable = true
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 190)).apply {
                setMargins(0, 0, 0, dp(context, 14))
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val chartPoints = points.ifEmpty { listOf(ChartPoint(System.currentTimeMillis(), 0)) }
            val left = dp(context, 46).toFloat()
            val right = width - dp(context, 14).toFloat()
            val stepX = if (chartPoints.size <= 1) 0f else (right - left) / (chartPoints.size - 1)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    selectedIndex = if (stepX <= 0f) 0 else ((event.x - left) / stepX).roundToInt().coerceIn(0, chartPoints.lastIndex)
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    parent?.requestDisallowInterceptTouchEvent(false)
                    performClick()
                    return true
                }
            }
            return true
        }

        override fun performClick(): Boolean {
            super.performClick()
            return true
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val left = dp(context, 46).toFloat()
            val right = width - dp(context, 14).toFloat()
            val top = dp(context, 18).toFloat()
            val bottom = height - dp(context, 42).toFloat()
            val chartPoints = points.ifEmpty { listOf(ChartPoint(System.currentTimeMillis(), 0)) }
            val max = chartPoints.maxOf { it.value }.coerceAtLeast(1)
            canvas.drawText(yLabel, left, dp(context, 11).toFloat(), textPaint)
            val tickCount = max.coerceIn(1, 4)
            for (i in 0..tickCount) {
                val y = bottom - ((bottom - top) * i / tickCount.toFloat())
                val value = (max * i / tickCount.toFloat()).roundToInt()
                canvas.drawLine(left, y, right, y, gridPaint)
                canvas.drawText(value.toString(), dp(context, 6).toFloat(), y + dp(context, 4), textPaint)
            }
            canvas.drawLine(left, top, left, bottom, axisPaint)
            canvas.drawLine(left, bottom, right, bottom, axisPaint)
            val stepX = if (chartPoints.size <= 1) 0f else (right - left) / (chartPoints.size - 1)
            var prevX: Float? = null
            var prevY: Float? = null
            chartPoints.forEachIndexed { index, point ->
                val x = if (chartPoints.size <= 1) left else left + stepX * index
                val y = bottom - ((bottom - top) * point.value.coerceAtLeast(0) / max.toFloat())
                val px = prevX
                val py = prevY
                if (px != null && py != null) canvas.drawLine(px, py, x, y, linePaint)
                canvas.drawCircle(x, y, dp(context, if (selectedIndex == index) 6 else 4).toFloat(), dotPaint)
                prevX = x
                prevY = y
            }
            canvas.drawText(dateFormat.format(Date(chartPoints.first().timestampMs)), left, height - dp(context, 18).toFloat(), textPaint)
            val lastLabel = dateFormat.format(Date(chartPoints.last().timestampMs))
            canvas.drawText(lastLabel, right - textPaint.measureText(lastLabel), height - dp(context, 18).toFloat(), textPaint)
            selectedIndex?.let { index ->
                val point = chartPoints[index]
                val x = if (chartPoints.size <= 1) left else left + stepX * index
                val y = bottom - ((bottom - top) * point.value.coerceAtLeast(0) / max.toFloat())
                canvas.drawLine(x, top, x, bottom, selectedPaint)
                val bubble = "${point.value} on ${dateFormat.format(Date(point.timestampMs))}"
                val bubbleWidth = textPaint.measureText(bubble) + dp(context, 16)
                val bx = (x - bubbleWidth / 2f).coerceIn(left, right - bubbleWidth)
                val by = (y - dp(context, 18)).coerceAtLeast(top + dp(context, 4))
                val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(22, 22, 22) }
                canvas.drawRoundRect(bx, by - dp(context, 18), bx + bubbleWidth, by + dp(context, 6), dp(context, 8).toFloat(), dp(context, 8).toFloat(), bg)
                canvas.drawText(bubble, bx + dp(context, 8), by, textPaint)
            }
        }
    }

    private class AvatarImageView(context: Context) : ImageView(context) {
        private val clipPath = Path()
        private val rect = RectF()
        private val placeholderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(30, 30, 30)
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
            canvas.drawCircle(width / 2f, height / 2f, minOf(width, height) / 2f, placeholderPaint)
            super.onDraw(canvas)
            canvas.restoreToCount(save)
        }
    }

    private fun currentUserId(session: Any?): String? {
        session ?: return null
        return runCatching {
            val field = session.javaClass.getDeclaredField("userId")
            field.isAccessible = true
            field.get(session) as? String
        }.getOrNull()
            ?.takeIf { it.all(Char::isDigit) }
    }

    private fun Follower.toHistoryItem(): InstagramActivityHistoryStore.Item {
        return InstagramActivityHistoryStore.Item(
            type = InstagramActivityHistoryStore.TYPE_FOLLOWER,
            username = username,
            title = displayName.ifBlank { "@$username" },
            caption = "Follower",
            mediaId = userId,
            url = "https://www.instagram.com/$username/",
            thumbnailUrl = thumbnailUrl,
            source = "fetch:follower_list",
            timestamp = System.currentTimeMillis()
        )
    }

    private fun isUsername(value: String): Boolean {
        return value.matches(Regex("[A-Za-z0-9._]{1,30}"))
    }

    private fun label(context: Context, textValue: String, size: Int, color: Int, bold: Boolean): TextView {
        return TextView(context).apply {
            text = textValue
            textSize = size.toFloat()
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }
    }

    private fun dp(context: Context, value: Int): Int = (value * context.resources.displayMetrics.density).roundToInt()

    private fun log(message: String) {
        Log.i("PurrfectInstaAnalytics", message.take(3900))
        InstagramAppLogWriter.info(null, "${InstagramFeatureState.TAG}|FollowerList", message)
        XposedBridge.log("[${InstagramFeatureState.TAG}|FollowerList] $message")
    }

    private data class Follower(
        val username: String,
        val displayName: String,
        val userId: String,
        val thumbnailUrl: String
    ) {
        val key: String
            get() = username.lowercase(Locale.US)
    }

    private data class RequestVariant(
        val name: String,
        val baseUrl: String,
        val count: Int,
        val params: Map<String, String>
    ) {
        fun url(maxId: String?): String {
            val allParams = linkedMapOf("count" to count.toString())
            allParams.putAll(params)
            maxId?.let { allParams["max_id"] = it }
            return baseUrl + "?" + allParams.entries.joinToString("&") { (key, value) ->
                "${Uri.encode(key)}=${Uri.encode(value)}"
            }
        }
    }

    private data class TargetUser(
        val userId: String,
        val expectedFollowerCount: Int?
    )

    private data class FollowerPageData(
        val followers: List<Follower>,
        val tracker: TrackerSummary,
        val following: FollowingSummary?,
        val followerRows: List<FollowerRelation>,
        val analytics: AccountAnalytics,
        val partialFollowerMessage: String?
    )

    private data class FollowerFetchResult(
        val followers: List<Follower>,
        val expectedCount: Int?,
        val isComplete: Boolean,
        val source: String,
        val partialMessage: String?
    )

    private data class AccountAnalytics(
        val media: List<MediaMetric>,
        val engagementUsers: List<EngagementUser>,
        val followerEngagement: List<EngagementUser>,
        val deletedLikes: List<EngagementUser>,
        val deletedComments: List<EngagementUser>,
        val timelineItems: List<InstagramActivityHistoryStore.Item>,
        val taggedMedia: List<MediaMetric>
    )

    private data class MediaMetric(
        val mediaId: String,
        val code: String,
        val title: String,
        val thumbnailUrl: String,
        val likeCount: Int,
        val commentCount: Int,
        val viewCount: Int,
        val timestampMs: Long
    )

    private data class EngagementUser(
        val follower: Follower,
        var likeCount: Int,
        var commentCount: Int,
        val followedByYou: Boolean,
        val followsYou: Boolean
    ) {
        val total: Int
            get() = likeCount + commentCount
    }

    private data class TrackerSummary(
        val accountId: String,
        val baseline: Boolean,
        val currentCount: Int,
        val events: List<TrackerEvent>,
        val currentAdded: List<Follower>,
        val currentRemoved: List<Follower>
    )

    private data class TrackerEvent(
        val timestampMs: Long,
        val added: List<Follower>,
        val removed: List<Follower>
    )

    private data class FollowingSummary(
        val rows: List<FollowingStatus>
    )

    private data class FollowingStatus(
        val follower: Follower,
        val followsYou: Boolean
    )

    data class ActivityProfile(
        val thumbnailUrl: String,
        val displayName: String
    )

    private data class FollowerRelation(
        val follower: Follower,
        var followedByYou: Boolean,
        val followsYou: Boolean
    )

    private enum class RelationFilterMode {
        FOLLOWED_BY_YOU,
        FOLLOWS_YOU
    }

    private data class RelationFilterState(
        var includePositive: Boolean = true,
        var includeNegative: Boolean = true
    )

    private data class AnalyticsAction(
        val title: String,
        val open: () -> Unit
    )

    private data class ChartPoint(
        val timestampMs: Long,
        val value: Int
    )

    private enum class AnalyticsPeriod(val label: String, private val durationMs: Long?) {
        LAST_CHECK("Check", null),
        WEEK("Week", 7L * 24L * 60L * 60L * 1000L),
        MONTH("Month", 31L * 24L * 60L * 60L * 1000L),
        YEAR("Year", 366L * 24L * 60L * 60L * 1000L),
        ALL_TIME("All", Long.MAX_VALUE);

        val emptyText: String
            get() = when (this) {
                LAST_CHECK -> "No changes since last check"
                WEEK -> "No changes this week"
                MONTH -> "No changes this month"
                YEAR -> "No changes this year"
                ALL_TIME -> "No changes all time"
            }

        fun cutoffMs(now: Long): Long {
            if (this == ALL_TIME) return Long.MIN_VALUE
            val duration = durationMs ?: return Long.MIN_VALUE
            return now - duration
        }
    }

    private data class AuthSnapshot(
        val authorization: String?,
        val csrfToken: String?,
        val cookies: Map<String, String>,
        val requestUserId: String?
    ) {
        val userId: String? = requestUserId ?: cookies["ds_user_id"]?.takeIf { it.all(Char::isDigit) }
        val cookieHeader: String = cookies.entries.joinToString("; ") { (key, value) -> "$key=$value" }
        val hasUsableAuth: Boolean = authorization != null || cookies["sessionid"] != null
        val hasCompleteContext: Boolean = hasUsableAuth && userId != null

        fun debugSummary(): String {
            val keys = cookies.keys.sorted().joinToString(",").ifBlank { "none" }
            return " (auth=${authorization != null}, user=${requestUserId != null}, cookies=$keys)"
        }

        fun requestHeaders(): Map<String, String> {
            val headers = linkedMapOf<String, String>()
            authorization?.let { headers["Authorization"] = it }
            csrfToken?.let { headers["X-CSRFToken"] = it }
            requestUserId?.let { userId ->
                headers["IG-INTENDED-USER-ID"] = userId
                headers["IG-U-DS-USER-ID"] = userId
                headers["X-IG-User-ID"] = userId
            }
            cookieHeader.takeIf { it.isNotBlank() }?.let { headers["Cookie"] = it }
            return headers
        }

        fun merge(other: AuthSnapshot): AuthSnapshot {
            val mergedCookies = linkedMapOf<String, String>()
            mergedCookies.putAll(cookies)
            mergedCookies.putAll(other.cookies)
            return AuthSnapshot(
                authorization = other.authorization ?: authorization,
                csrfToken = other.csrfToken ?: csrfToken,
                cookies = mergedCookies,
                requestUserId = other.requestUserId ?: requestUserId
            )
        }

        companion object {
            private val cookieNames = listOf("sessionid", "ds_user_id", "csrftoken", "mid", "ig_did", "rur")

            fun from(context: Context, session: Any?): AuthSnapshot {
                val cookies = linkedMapOf<String, String>()
                val webCookie = runCatching {
                    CookieManager.getInstance().getCookie("https://www.instagram.com/")
                }.getOrNull().orEmpty()
                var requestUserId: String? = null
                absorbCookieHeader(webCookie, cookies)

                var authorization: String? = null
                inspectObject(
                    session,
                    cookies,
                    { token -> authorization = authorization ?: token },
                    { id -> requestUserId = requestUserId ?: id },
                    maxObjects = 240,
                    logResult = true,
                    allowMethods = true
                )
                runCatching {
                    val prefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
                    prefsDir.listFiles { file ->
                        file.isFile && file.extension.equals("xml", true) && file.length() in 1..5_000_000
                    }?.forEach { file ->
                        val text = runCatching { file.readText() }.getOrNull() ?: return@forEach
                        authorization = authorization ?: extractAuthorization(text)
                        requestUserId = requestUserId ?: extractRequestUserId(text)
                        absorbCookieHeader(text, cookies)
                        cookieNames.forEach { key ->
                            if (cookies[key].isNullOrBlank()) extractLooseValue(text, key)?.let { cookies[key] = it }
                        }
                    }
                }.onFailure { log("Unable to inspect Instagram auth prefs: ${it.javaClass.simpleName}") }

                return AuthSnapshot(authorization, cookies["csrftoken"], cookies.filterValues { it.isNotBlank() }, requestUserId)
            }

            fun fromObject(source: Any?, maxObjects: Int, logResult: Boolean): AuthSnapshot {
                val cookies = linkedMapOf<String, String>()
                var authorization: String? = null
                var requestUserId: String? = null
                inspectObject(
                    source,
                    cookies,
                    { token -> authorization = authorization ?: token },
                    { id -> requestUserId = requestUserId ?: id },
                    maxObjects,
                    logResult,
                    allowMethods = false
                )
                return AuthSnapshot(authorization, cookies["csrftoken"], cookies.filterValues { it.isNotBlank() }, requestUserId)
            }

            private fun inspectObject(
                root: Any?,
                cookies: MutableMap<String, String>,
                setAuthorization: (String) -> Unit,
                setRequestUserId: (String) -> Unit,
                maxObjects: Int,
                logResult: Boolean,
                allowMethods: Boolean
            ) {
                if (root == null) return
                val seen = IdentityHashMap<Any, Boolean>()
                val queue = ArrayDeque<Pair<Any, Int>>()
                queue.add(root to 0)
                var inspected = 0
                while (queue.isNotEmpty() && inspected < maxObjects) {
                    val (target, depth) = queue.removeFirst()
                    if (seen.put(target, true) != null) continue
                    inspected++
                    scanText(target.toString(), cookies, setAuthorization, setRequestUserId)
                    val cls = target.javaClass
                    runCatching {
                        cls.declaredFields.forEach { field ->
                            if (java.lang.reflect.Modifier.isStatic(field.modifiers)) return@forEach
                            field.isAccessible = true
                            val value = field.get(target) ?: return@forEach
                            when (value) {
                                is String -> scanText(value, cookies, setAuthorization, setRequestUserId)
                                is Map<*, *> -> scanMap(value, cookies, setAuthorization, setRequestUserId)
                                is Iterable<*> -> value.forEach { scanText(it?.toString().orEmpty(), cookies, setAuthorization, setRequestUserId) }
                                else -> if (depth < 2 && shouldInspect(value.javaClass)) queue.add(value to depth + 1)
                            }
                        }
                    }
                    if (allowMethods) {
                        runCatching {
                            cls.methods.forEach { method ->
                                if (method.parameterTypes.isNotEmpty() || method.returnType != String::class.java) return@forEach
                                if (method.name in setOf("toString", "getClass")) return@forEach
                                val value = runCatching { method.invoke(target) as? String }.getOrNull() ?: return@forEach
                                scanText(value, cookies, setAuthorization, setRequestUserId)
                            }
                        }
                        runCatching {
                            cls.methods.forEach { method ->
                                if (method.parameterTypes.isNotEmpty()) return@forEach
                                val name = method.name.lowercase(Locale.US)
                                if (name != "headers" && name != "getheaders" && name != "getallheaders") return@forEach
                                val value = runCatching { method.invoke(target) }.getOrNull() ?: return@forEach
                                scanText(value.toString(), cookies, setAuthorization, setRequestUserId)
                                if (depth < 2 && shouldInspect(value.javaClass)) queue.add(value to depth + 1)
                            }
                        }
                    }
                }
                if (logResult) log("Inspected Instagram session auth graph objects=$inspected")
            }

            private fun shouldInspect(cls: Class<*>): Boolean {
                if (cls.isPrimitive || cls.isArray || cls.isEnum) return false
                val name = cls.name
                return name.startsWith("com.instagram.") ||
                    name.startsWith("X.") ||
                    name.startsWith("p000X.") ||
                    name.startsWith("okhttp3.") ||
                    name.startsWith("com.facebook.tigon") ||
                    name.contains("Header", ignoreCase = true)
            }

            private fun scanMap(
                map: Map<*, *>,
                cookies: MutableMap<String, String>,
                setAuthorization: (String) -> Unit,
                setRequestUserId: (String) -> Unit
            ) {
                map.forEach { (key, value) ->
                    scanText("${key.orEmptyText()}: ${value.orEmptyText()}", cookies, setAuthorization, setRequestUserId)
                }
            }

            private fun scanText(
                text: String,
                cookies: MutableMap<String, String>,
                setAuthorization: (String) -> Unit,
                setRequestUserId: (String) -> Unit
            ) {
                extractAuthorization(text)?.let { token ->
                    setAuthorization(token)
                    extractUserIdFromAuthorization(token)?.let(setRequestUserId)
                }
                extractRequestUserId(text)?.let(setRequestUserId)
                absorbCookieHeader(text, cookies)
                cookieNames.forEach { key ->
                    if (cookies[key].isNullOrBlank()) extractLooseValue(text, key)?.let { cookies[key] = it }
                }
            }

            private fun absorbCookieHeader(text: String, out: MutableMap<String, String>) {
                cookieNames.forEach { key ->
                    Regex("""(?:^|[;\s"'&])$key=([^;"'<>\s&]+)""").find(text)
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.let { out[key] = decode(it) }
                }
            }

            private fun extractLooseValue(text: String, key: String): String? {
                val escapedKey = Regex.escape(key)
                val match = Regex("""["']?$escapedKey["']?\s*[:=]\s*["']?([^"',;<>\s{}]+)""").find(text)
                return match?.groupValues?.getOrNull(1)?.let(::decode)?.takeIf { it.isNotBlank() && it != "null" }
            }

            private fun extractAuthorization(text: String): String? {
                Regex("""Bearer\s+IGT:[^"'<>\s]+""").find(text)?.value?.let { return decode(it) }
                Regex("""IGT:2:[^"'<>\s]+""").find(text)?.value?.let { return "Bearer ${decode(it)}" }
                return null
            }

            private fun extractUserIdFromAuthorization(authorization: String): String? {
                val token = authorization.removePrefix("Bearer ").trim()
                val candidates = buildList {
                    add(token)
                    token.split(':', '.', '_', '-').forEach { add(it) }
                }
                candidates.forEach { candidate ->
                    if (candidate.length < 8) return@forEach
                    decodeBase64Text(candidate)?.let { decoded ->
                        extractRequestUserId(decoded)?.let { return it }
                        Regex("""(?i)["']?(?:user_id|uid|pk|pk_id|ds_user_id)["']?\s*[:=]\s*["']?(\d{3,})""")
                            .find(decoded)
                            ?.groupValues
                            ?.getOrNull(1)
                            ?.let { return it }
                    }
                }
                return null
            }

            private fun decodeBase64Text(value: String): String? {
                val normalized = value.replace('-', '+').replace('_', '/')
                val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
                return runCatching {
                    String(Base64.decode(padded, Base64.DEFAULT), Charsets.UTF_8)
                }.getOrNull()?.takeIf { it.any(Char::isDigit) }
            }

            private fun extractRequestUserId(text: String): String? {
                val headerPattern = Regex(
                    """(?i)(?:ig-intended-user-id|ig-u-ds-user-id|ig-user-id|x-ig-user-id|ds_user_id|target_user_id|logged_in_user_id)["']?\s*[:=]\s*["']?(\d{3,})"""
                )
                return headerPattern.find(text)?.groupValues?.getOrNull(1)
            }

            private fun decode(value: String): String {
                return runCatching { URLDecoder.decode(value.replace("\\/", "/"), "UTF-8") }.getOrDefault(value)
                    .trim()
                    .trim('"', '\'')
            }

            private fun Any?.orEmptyText(): String = this?.toString().orEmpty()
        }
    }
}
