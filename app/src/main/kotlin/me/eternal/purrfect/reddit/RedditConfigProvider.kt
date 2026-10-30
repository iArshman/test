package me.eternal.purrfect.reddit

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import me.eternal.purrfect.RemoteSideContext
import org.json.JSONObject
import java.io.File

class RedditConfigProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != METHOD_GET_FEATURES) return null
        val appContext = context?.applicationContext ?: return null
        val jsonFile = File(appContext.filesDir, RemoteSideContext.REDDIT_FEATURE_CONFIG_FILE)
        val json = if (jsonFile.exists()) {
            JSONObject(jsonFile.readText(Charsets.UTF_8))
        } else {
            readFromMainConfig(File(appContext.filesDir, "config.json"))
        }

        return Bundle().apply {
            putBoolean(KEY_BLOCK_PROMOTED_POSTS, json.optBoolean(KEY_BLOCK_PROMOTED_POSTS, false))
            putBoolean(KEY_BLOCK_COMMENT_ADS, json.optBoolean(KEY_BLOCK_COMMENT_ADS, false))
            putBoolean(KEY_UNLOCK_REDDIT_PREMIUM, json.optBoolean(KEY_UNLOCK_REDDIT_PREMIUM, false))
            putBoolean(KEY_OPEN_LINKS_EXTERNAL, json.optBoolean(KEY_OPEN_LINKS_EXTERNAL, false))
            putBoolean(KEY_RESTORE_DELETED_CONTENT, json.optBoolean(KEY_RESTORE_DELETED_CONTENT, false))
            putBoolean("json_file_exists", jsonFile.exists())
            putString("source", if (jsonFile.exists()) jsonFile.absolutePath else "config.json fallback")
            putString("json", json.toString())
        }
    }

    private fun readFromMainConfig(configFile: File): JSONObject {
        if (!configFile.exists()) return JSONObject()
        return runCatching {
            val reddit = JSONObject(configFile.readText(Charsets.UTF_8)).optJSONObject("reddit") ?: JSONObject()
            val properties = reddit.optJSONObject("properties") ?: reddit
            JSONObject().apply {
                copyBoolean(properties, "block_promoted_posts", "ad_blocking")
                copyBoolean(properties, "block_comment_ads", "ad_blocking")
                copyBoolean(properties, "unlock_reddit_premium", "premium")
                copyBoolean(properties, "open_links_in_external_browser", "links")
                copyBoolean(properties, "restore_deleted_content", "deleted_content")
            }
        }.getOrDefault(JSONObject())
    }

    private fun JSONObject.copyBoolean(source: JSONObject, key: String, category: String) {
        val nested = source.optJSONObject(category)
            ?.optJSONObject("properties")
            ?.optBoolean(key, false) ?: false
        put(key, source.optBoolean(key, nested))
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
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0

    companion object {
        const val AUTHORITY = "me.eternal.purrfect.reddit.config"
        const val METHOD_GET_FEATURES = "getRedditFeatures"
        const val KEY_BLOCK_PROMOTED_POSTS = "block_promoted_posts"
        const val KEY_BLOCK_COMMENT_ADS = "block_comment_ads"
        const val KEY_UNLOCK_REDDIT_PREMIUM = "unlock_reddit_premium"
        const val KEY_OPEN_LINKS_EXTERNAL = "open_links_in_external_browser"
        const val KEY_RESTORE_DELETED_CONTENT = "restore_deleted_content"
    }
}
