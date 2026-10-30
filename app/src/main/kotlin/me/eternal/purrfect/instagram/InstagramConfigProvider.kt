package me.eternal.purrfect.instagram

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import me.eternal.purrfect.RemoteSideContext
import me.eternal.purrfect.common.Constants
import org.json.JSONObject
import java.io.File

class InstagramConfigProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != METHOD_GET_FEATURES) return null
        val appContext = context?.applicationContext ?: return null
        val jsonFile = File(appContext.filesDir, RemoteSideContext.INSTAGRAM_FEATURE_CONFIG_FILE)
        val json = if (jsonFile.exists()) {
            JSONObject(jsonFile.readText(Charsets.UTF_8))
        } else {
            readFromMainConfig(File(appContext.filesDir, "config.json"))
        }

        return Bundle().apply {
            BOOLEAN_FEATURE_KEYS.forEach { key -> putBoolean(key, json.optBoolean(key, defaultBoolean(key))) }
            STRING_FEATURE_KEYS.forEach { key -> putString(key, json.optString(key, defaultString(key))) }
            putBoolean("json_file_exists", jsonFile.exists())
            putString("source", if (jsonFile.exists()) jsonFile.absolutePath else "config.json fallback")
            putString("json", json.toString())
        }
    }

    private fun readFromMainConfig(configFile: File): JSONObject {
        if (!configFile.exists()) return JSONObject()
        return runCatching {
            val instagram = JSONObject(configFile.readText(Charsets.UTF_8)).optJSONObject("instagram") ?: JSONObject()
            val properties = instagram.optJSONObject("properties") ?: instagram
            JSONObject().apply {
                BOOLEAN_FEATURE_KEYS.forEach { key -> put(key, readBoolean(properties, key)) }
                STRING_FEATURE_KEYS.forEach { key -> put(key, readString(properties, key)) }
            }
        }.getOrDefault(JSONObject())
    }

    private fun readBoolean(properties: JSONObject, key: String): Boolean {
        return when (val value = findPropertyValue(properties, key) ?: return defaultBoolean(key)) {
            is Boolean -> value
            is Number -> value.toInt() != 0
            is String -> value.toBooleanStrictOrNull() ?: defaultBoolean(key)
            else -> defaultBoolean(key)
        }
    }

    private fun readString(properties: JSONObject, key: String): String {
        return when (val value = findPropertyValue(properties, key) ?: return defaultString(key)) {
            is String -> value
            else -> value.toString()
        }
    }

    private fun findPropertyValue(root: JSONObject, key: String): Any? {
        if (root.has(key)) return root.opt(key).takeUnless { it == JSONObject.NULL }

        root.optJSONObject("properties")?.let { nestedProperties ->
            findPropertyValue(nestedProperties, key)?.let { return it }
        }

        val keys = root.keys()
        while (keys.hasNext()) {
            val childKey = keys.next()
            if (childKey == key || childKey == "properties") continue
            val child = root.optJSONObject(childKey) ?: continue
            findPropertyValue(child, key)?.let { return it }
        }
        return null
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        val AUTHORITY get() = Constants.INSTAGRAM_CONFIG_PROVIDER_AUTHORITY
        const val METHOD_GET_FEATURES = "getInstagramFeatures"

        val BOOLEAN_FEATURE_KEYS = listOf(
            "isDevEnabled", "removeBuildExpiredPopup",
            "isGhostModeEnabled", "isGhostSeen", "markTextsSeenAfterReply", "isGhostTyping", "isGhostStory",
            "storyInteractionSendsSeen", "isGhostLive", "hideVoiceMessageSeen",
            "allowScreenshots", "isGhostScreenshot", "isGhostViewOnce",
            "enableUnlimitedReplays", "permanentViewMode",
            "keepUnsentMessages", "quickToggleSeen", "quickToggleTyping",
            "quickToggleScreenshot", "quickToggleViewOnce", "quickToggleStory",
            "quickToggleLive", "quickToggleUnsend",
            "quickToggleReplays", "quickTogglePermanentView", "quickToggleAllowScreenshots",
            "isExtremeMode", "isDistractionFree", "disableStories", "disableFeed",
            "disableReels", "disableReelsExceptDM", "disableExplore", "disableComments",
            "isAdBlockEnabled", "isAnalyticsBlocked", "disableTrackingLinks",
            "stripShareTrackingParameters", "openLinksExternally", "replaceShareLinkDomain",
            "hideSuggestionsInFeed", "hideSuggestedForYouInFeed", "hideSuggestionsInDm",
            "hideDiscoverPeopleInProfile", "doNotSaveRecentSearches", "enablePostDownload", "enableStoryDownload",
            "enableReelDownload", "enableProfileDownload",
            "enableDmContextMenuOptions",
            "enableReelThumbnailDownload", "enableStoryMarkSeenButton", "enableStoryRepostButton",
            "enableCopyBio", "enableHighQualityStoryUpload",
            "enableDmAnyFileUpload", "enableUploadInstantsFromGallery",
            "preventDmMessageListAutoscroll", "downloaderUsernameFolder",
            "downloaderAddTimestamp", "isMiscEnabled", "disableStoryFlipping", "disableVideoAutoPlay",
            "feedVideosStartWithSound", "storiesStartWithSound", "disableDoubleTapLike",
            "enableConfirmRefresh", "customEmojiFontEnabled",
            "enableShareSheetEmojiShortcuts", "enableNavigationTabCustomization",
            "enableStoryTrayLongPressActions", "captureUiElementIdsEnabled",
            "showFollowerToast", "showFeatureToasts", "enableStoryMentions",
            "localInstagramPlus", "restoreOldPostReelContextMenu", "sendCustomEmojiReactionsToStory", "changeLikeReactions",
            "customizeStoryRingSize", "disableGroupCreationFromShareSheet",
            "improveImageViewing", "moreOptionsOnPost", "removeEmptyBottomSpace",
            "enableHideChats", "enableActivityHistory",
            "enableCopyComment", "enableCustomDateFormat", "customDateFormatFeed",
            "customDateFormatComments", "customDateFormatReels", "customDateFormatStories",
            "customDateFormatDirect", "enableNotesLocationSpoof"
        )

        val STRING_FEATURE_KEYS = listOf(
            "dmMarkSeenControlMode", "shareLinkReplacementDomain", "downloaderCustomPath",
            "downloaderCustomUri", "customEmojiFontPath", "customEmojiFontName",
            "customEmojiFontUri", "navigationTabOrder", "navigationTabHidden",
            "navigationDefaultTab", "storyRingSize", "likeReactionAnimation", "hiddenUiElementIds",
            "hiddenUiElementSelectors", "hiddenChatNames", "knownChatNames",
            "customDateFormat", "confirmRefreshScope", "reelDownloadControlMode",
            "notesSpoofLatitude", "notesSpoofLongitude", "notesSpoofMapLocation"
        )

        fun defaultBoolean(key: String): Boolean {
            return key == "restoreOldPostReelContextMenu" ||
                key == "quickToggleUnsend" ||
                key == "enableDmContextMenuOptions" ||
                key == "customDateFormatFeed" ||
                key == "customDateFormatComments" ||
                key == "customDateFormatReels" ||
                key == "customDateFormatStories" ||
                key == "customDateFormatDirect"
        }

        fun defaultString(key: String): String {
            return when (key) {
                "dmMarkSeenControlMode" -> "eye"
                "shareLinkReplacementDomain" -> "kkinstagram.com"
                "navigationTabOrder" -> "home,search,reels,create,direct,shop,profile"
                "navigationDefaultTab" -> "home"
                "storyRingSize" -> "default"
                "likeReactionAnimation" -> "ARES_LIKE_ACTIVATION"
                "customDateFormat" -> "yyyy-MM-dd HH:mm"
                "confirmRefreshScope" -> "both"
                "reelDownloadControlMode" -> "menu"
                else -> ""
            }
        }
    }
}
