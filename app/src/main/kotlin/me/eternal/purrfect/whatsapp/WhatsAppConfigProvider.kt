package me.eternal.purrfect.whatsapp

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import me.eternal.purrfect.RemoteSideContext
import org.json.JSONObject
import java.io.File

class WhatsAppConfigProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != METHOD_GET_FEATURES) return null
        val appContext = context?.applicationContext ?: return null
        val jsonFile = File(appContext.filesDir, RemoteSideContext.WHATSAPP_FEATURE_CONFIG_FILE)
        val json = if (jsonFile.exists()) {
            JSONObject(jsonFile.readText(Charsets.UTF_8))
        } else {
            readFromMainConfig(File(appContext.filesDir, "config.json"))
        }

        return Bundle().apply {
            FEATURE_KEYS.forEach { key ->
                putBoolean(key, json.optBoolean(key, false))
            }
            STRING_FEATURE_KEYS.forEach { key ->
                putString(key, json.optString(key, ""))
            }
            putBoolean("json_file_exists", jsonFile.exists())
            putString("source", if (jsonFile.exists()) jsonFile.absolutePath else "config.json fallback")
            putString("json", json.toString())
        }
    }

    private fun readFromMainConfig(configFile: File): JSONObject {
        if (!configFile.exists()) return JSONObject()
        return runCatching {
            val whatsapp = JSONObject(configFile.readText(Charsets.UTF_8)).optJSONObject("whatsapp") ?: JSONObject()
            val properties = whatsapp.optJSONObject("properties") ?: whatsapp
            JSONObject().apply {
                FEATURE_KEYS.forEach { key ->
                    put(key, readBoolean(properties, key))
                }
                STRING_FEATURE_KEYS.forEach { key ->
                    put(key, readString(properties, key))
                }
            }
        }.getOrDefault(JSONObject())
    }

    private fun readBoolean(properties: JSONObject, key: String): Boolean {
        if (properties.has(key)) return properties.optBoolean(key, false)
        FEATURE_GROUPS.forEach { group ->
            val nested = properties.optJSONObject(group)
                ?.optJSONObject("properties")
                ?.takeIf { it.has(key) }
                ?.optBoolean(key, false)
            if (nested != null) return nested
        }
        return false
    }

    private fun readString(properties: JSONObject, key: String): String {
        if (properties.has(key)) return properties.optString(key, "")
        FEATURE_GROUPS.forEach { group ->
            val nested = properties.optJSONObject(group)
                ?.optJSONObject("properties")
                ?.takeIf { it.has(key) }
                ?.optString(key, "")
            if (nested != null) return nested
        }
        return ""
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
        const val AUTHORITY = "me.eternal.purrfect.whatsapp.config"
        const val METHOD_GET_FEATURES = "getWhatsAppFeatures"
        const val KEY_HIDE_CHANNELS = "hide_channels"
        const val KEY_HIDE_CHANNEL_RECOMMENDATIONS = "hide_channel_recommendations"
        private val FEATURE_KEYS = listOf(
            KEY_HIDE_CHANNELS,
            KEY_HIDE_CHANNEL_RECOMMENDATIONS,
            "hide_communities_tab",
            "hide_typing_indicators",
            "hide_recording_audio",
            "hide_delivered",
            "hide_audio_seen",
            "hide_status_view",
            "hide_start_chatting",
            "unlimited_view_once",
            "hide_blue_ticks",
            "show_deleted_messages",
            "hide_ui_elements",
            "capture_ui_elements",
            "liquid_class"
        )
        private val STRING_FEATURE_KEYS = listOf(
            "hidden_ui_element_ids",
            "hidden_ui_element_selectors"
        )
        private val FEATURE_GROUPS = listOf("channels", "privacy", "messages", "ui_elements")
    }
}
