package me.eternal.purrfect.ui.manager.pages.features

import me.eternal.purrfect.RemoteSideContext
import org.json.JSONObject

internal object ScopedConfigJson {
    fun exportForActiveTarget(
        context: RemoteSideContext,
        exportSensitiveData: Boolean,
        includeSavedLocations: Boolean,
        savedLocations: List<me.eternal.purrfect.bridge.location.LocationCoordinates>? = null
    ): String {
        val full = JSONObject(context.config.exportToString(exportSensitiveData, includeSavedLocations, savedLocations))
        return when {
            context.isRedditMode -> JSONObject()
                    .put("reddit", full.optJSONObject("reddit") ?: JSONObject(context.getRedditFeaturesJson()))
                    .put("_target_app", "reddit")
                    .toString(2)
            context.isWhatsAppMode -> JSONObject()
                    .put("whatsapp", full.optJSONObject("whatsapp") ?: JSONObject(context.getWhatsAppFeaturesJson()))
                    .put("_target_app", "whatsapp")
                    .toString(2)
            context.isInstagramMode -> JSONObject()
                    .put("instagram", full.optJSONObject("instagram") ?: JSONObject(context.getInstagramFeaturesJson()))
                    .put("_target_app", "instagram")
                    .toString(2)
            else -> {
                full.remove("reddit")
                full.remove("whatsapp")
                full.remove("instagram")
                full.put("_target_app", "snapchat")
                full.toString(2)
            }
        }
    }

    fun importForActiveTarget(context: RemoteSideContext, json: String): String {
        val input = JSONObject(json)
        return when {
            context.isRedditMode -> {
                val reddit = input.optJSONObject("reddit") ?: input
                val redditContainer = if (reddit.has("properties")) {
                    reddit
                } else {
                    JSONObject()
                        .put("state", JSONObject.NULL)
                        .put("properties", reddit)
                }
                JSONObject()
                    .put("reddit", redditContainer)
                    .put("_target_app", "reddit")
                    .toString()
            }
            context.isWhatsAppMode -> {
                val whatsApp = input.optJSONObject("whatsapp") ?: input
                val whatsAppContainer = if (whatsApp.has("properties")) {
                    whatsApp
                } else {
                    JSONObject()
                        .put("state", JSONObject.NULL)
                        .put("properties", whatsApp)
                }
                JSONObject()
                    .put("whatsapp", whatsAppContainer)
                    .put("_target_app", "whatsapp")
                    .toString()
            }
            context.isInstagramMode -> {
                val instagram = input.optJSONObject("instagram") ?: input
                val instagramContainer = if (instagram.has("properties")) {
                    instagram
                } else {
                    JSONObject()
                        .put("state", JSONObject.NULL)
                        .put("properties", instagram)
                }
                JSONObject()
                    .put("instagram", instagramContainer)
                    .put("_target_app", "instagram")
                    .toString()
            }
            else -> {
                input.remove("reddit")
                input.remove("whatsapp")
                input.remove("instagram")
                input.put("_target_app", "snapchat")
                input.toString()
            }
        }
    }
}
