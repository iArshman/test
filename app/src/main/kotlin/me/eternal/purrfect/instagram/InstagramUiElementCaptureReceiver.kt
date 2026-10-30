package me.eternal.purrfect.instagram

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import me.eternal.purrfect.SharedContextHolder
import me.eternal.purrfect.common.Constants

class InstagramUiElementCaptureReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Constants.INSTAGRAM_UI_ELEMENT_CAPTURED_ACTION) return

        val rawValue = intent.getStringExtra(Constants.INSTAGRAM_UI_ELEMENT_CAPTURED_VALUE_EXTRA).orEmpty()
        val isSelector = intent.getBooleanExtra(Constants.INSTAGRAM_UI_ELEMENT_CAPTURED_IS_SELECTOR_EXTRA, false)
        val clean = if (isSelector) normalizeSelector(rawValue) else normalizeId(rawValue)
        if (clean.isEmpty()) return

        runCatching {
            val remoteContext = SharedContextHolder.remote(context)
            val uiElements = remoteContext.config.root.instagram.hiddenUiElements
            if (isSelector) {
                uiElements.hiddenUiElementSelectors.set(appendUnique(uiElements.hiddenUiElementSelectors.getNullable().orEmpty(), clean))
            } else {
                uiElements.hiddenUiElementIds.set(appendUnique(uiElements.hiddenUiElementIds.getNullable().orEmpty(), clean))
            }
            remoteContext.config.writeConfig()
            remoteContext.mirrorInstagramFeaturePrefs()
        }
    }

    private fun appendUnique(raw: String, value: String): String {
        val values = raw.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toMutableList()
        if (values.none { it == value }) values += value
        return values.joinToString("\n")
    }

    private fun normalizeSelector(raw: String): String {
        val clean = raw.trim()
        return if (clean.startsWith("selector:v1|")) clean else ""
    }

    private fun normalizeId(raw: String): String {
        var clean = raw.trim()
        if (clean.isEmpty()) return ""
        clean = clean.substringBefore('\t').trim()
        clean = clean.substringBefore(' ').trim()
        val slashIndex = clean.lastIndexOf('/')
        if (slashIndex >= 0 && slashIndex < clean.length - 1) clean = clean.substring(slashIndex + 1).trim()
        val dotIndex = clean.lastIndexOf(".id.")
        if (dotIndex >= 0 && dotIndex + 4 < clean.length) clean = clean.substring(dotIndex + 4).trim()
        return clean
    }
}
