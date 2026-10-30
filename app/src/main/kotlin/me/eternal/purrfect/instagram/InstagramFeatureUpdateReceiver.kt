package me.eternal.purrfect.instagram

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import me.eternal.purrfect.SharedContextHolder
import me.eternal.purrfect.common.Constants
import me.eternal.purrfect.common.config.ConfigContainer
import org.json.JSONObject

class InstagramFeatureUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Constants.INSTAGRAM_FEATURE_PREF_UPDATE_ACTION) return

        runCatching {
            val remoteContext = SharedContextHolder.remote(context)
            val container = remoteContext.config.root.instagram
            val batchJson = intent.getStringExtra(Constants.INSTAGRAM_FEATURE_PREF_BATCH_JSON_EXTRA)
            val changed = if (!batchJson.isNullOrBlank()) {
                val batch = JSONObject(batchJson)
                batch.keys().asSequence().fold(false) { anyChanged, key ->
                    val value = batch.opt(key)
                    setProperty(container, key, value) || anyChanged
                }
            } else {
                val key = intent.getStringExtra(Constants.INSTAGRAM_FEATURE_PREF_KEY_EXTRA)?.takeIf { it.isNotBlank() } ?: return
                val isString = intent.getBooleanExtra(Constants.INSTAGRAM_FEATURE_PREF_IS_STRING_EXTRA, false)
                val value: Any = if (isString) {
                    intent.getStringExtra(Constants.INSTAGRAM_FEATURE_PREF_STRING_EXTRA).orEmpty()
                } else {
                    intent.getBooleanExtra(Constants.INSTAGRAM_FEATURE_PREF_BOOLEAN_EXTRA, false)
                }
                setProperty(container, key, value)
            }

            if (changed) {
                remoteContext.config.writeConfig()
                remoteContext.mirrorInstagramFeaturePrefs()
            }
        }
    }

    private fun setProperty(container: ConfigContainer, key: String, value: Any): Boolean {
        container.properties.entries.firstOrNull { it.key.name == key }?.let { (_, propertyValue) ->
            propertyValue.setAny(value)
            return true
        }

        container.properties.values.forEach { propertyValue ->
            val child = propertyValue.getNullable() as? ConfigContainer ?: return@forEach
            if (setProperty(child, key, value)) return true
        }
        return false
    }
}
