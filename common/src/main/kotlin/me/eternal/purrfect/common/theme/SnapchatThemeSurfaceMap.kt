package me.eternal.purrfect.common.theme

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

data class SnapchatThemeMappedSurface(
    val key: String,
    val label: String,
    val description: String = "",
    val category: String = "Observed",
    val source: String = "runtime",
    val attributeName: String? = null,
    val className: String? = null,
    val valueSummary: String? = null,
    val valueType: String? = null,
    val viewClass: String? = null,
    val resourceName: String? = null,
    val selector: String? = null,
    val selectorDisplayName: String? = null,
    val screenHint: String? = null,
    val role: String? = null,
    val screenBounds: String? = null,
    val localBounds: String? = null,
    val rootBounds: String? = null,
    val parentClassName: String? = null,
    val parentResourceName: String? = null,
    val ancestry: String? = null,
    val firstSeenMs: Long = 0L,
    val lastSeenMs: Long = 0L,
    val hitCount: Int = 0,
    val defaultColor: Int? = null,
    val confidence: Float = 0f,
    val details: Map<String, String> = emptyMap()
)

data class SnapchatThemeSurfaceMap(
    val schemaVersion: Int = SnapchatThemeSurfaceMapCodec.SCHEMA_VERSION,
    val uniqueHash: Long = -1L,
    val snapchatVersionName: String? = null,
    val snapchatVersionCode: Long = -1L,
    val generatedAtMs: Long = 0L,
    val updatedAtMs: Long = 0L,
    val generator: String = "runtime",
    val surfaces: List<SnapchatThemeMappedSurface> = emptyList()
)

object SnapchatThemeSurfaceMapCodec {
    const val SCHEMA_VERSION = 1

    fun normalizeKey(value: String?): String {
        return value
            ?.trim()
            ?.lowercase(Locale.US)
            ?.replace(Regex("[^a-z0-9_.-]+"), "_")
            ?.trim('_', '.', '-')
            .orEmpty()
    }

    fun friendlyLabelForKey(key: String): String {
        val normalized = normalizeKey(key)
        val token = normalized
            .removePrefix("valdi.attr.")
            .removePrefix("valdi.token.")
            .removePrefix("android.attr.")
            .substringAfterLast('.')
            .replace('_', ' ')
            .replace('-', ' ')
            .trim()
        val expanded = token
            .replace("backgroundmain", "main page background")
            .replace("backgroundsubscreen", "subscreen background")
            .replace("backgroundsurfaceup", "raised sheet background")
            .replace("backgroundsurfacedown", "inset sheet background")
            .replace("backgroundsurface", "sheet background")
            .replace("backgroundobjectdown", "pressed row background")
            .replace("backgroundobject", "row and card background")
            .replace("backgroundabovesurface", "floating block background")
            .replace("backgrounddisabled", "disabled surface background")
            .replace("backgroundoverlay", "overlay background")
            .replace("backgroundcolor", "background color")
            .replace("surfacecolor", "surface color")
            .replace("containercolor", "container color")
            .replace("cardcolor", "card color")
            .replace("panelcolor", "panel color")
            .replace("sheetcolor", "sheet color")
            .replace("rowbackgroundcolor", "row background color")
            .replace("rowbackground", "row background")
            .replace("cellbackgroundcolor", "cell background color")
            .replace("cellbackground", "cell background")
            .replace("itembackgroundcolor", "item background color")
            .replace("itembackground", "item background")
            .replace("selectedbackgroundcolor", "selected background color")
            .replace("selectedbackground", "selected background")
            .replace("primaryfillcolor", "primary fill color")
            .replace("secondaryfillcolor", "secondary fill color")
            .replace("chromecolor", "chrome color")
            .ifBlank { normalized }
        return expanded.split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .joinToString(" ") { word ->
                word.replaceFirstChar { char ->
                    if (char.isLowerCase()) char.titlecase(Locale.US) else char.toString()
                }
            }
    }

    fun parse(raw: String): SnapchatThemeSurfaceMap {
        if (raw.isBlank()) return SnapchatThemeSurfaceMap()
        val root = JSONObject(raw)
        val surfaces = root.optJSONArray("surfaces") ?: JSONArray()
        return SnapchatThemeSurfaceMap(
            schemaVersion = root.optInt("schema_version", SCHEMA_VERSION),
            uniqueHash = root.optLong("unique_hash", -1L),
            snapchatVersionName = root.optString("snapchat_version_name").takeIf { it.isNotBlank() },
            snapchatVersionCode = root.optLong("snapchat_version_code", -1L),
            generatedAtMs = root.optLong("generated_at_ms", 0L),
            updatedAtMs = root.optLong("updated_at_ms", 0L),
            generator = root.optString("generator", "runtime"),
            surfaces = (0 until surfaces.length()).mapNotNull { index ->
                surfaces.optJSONObject(index)?.let(::parseSurface)
            }
        )
    }

    fun toJson(map: SnapchatThemeSurfaceMap): String {
        return JSONObject()
            .put("schema_version", map.schemaVersion)
            .put("unique_hash", map.uniqueHash)
            .put("snapchat_version_name", map.snapchatVersionName ?: JSONObject.NULL)
            .put("snapchat_version_code", map.snapchatVersionCode)
            .put("generated_at_ms", map.generatedAtMs)
            .put("updated_at_ms", map.updatedAtMs)
            .put("generator", map.generator)
            .put(
                "surfaces",
                JSONArray().also { array ->
                    map.surfaces
                        .sortedWith(compareBy<SnapchatThemeMappedSurface> { it.category }.thenBy { it.key })
                        .forEach { array.put(surfaceToJson(it)) }
                }
            )
            .toString()
    }

    private fun parseSurface(json: JSONObject): SnapchatThemeMappedSurface? {
        val key = normalizeKey(json.optString("key"))
        if (key.isBlank()) return null
        val detailsJson = json.optJSONObject("details")
        val details = if (detailsJson == null) {
            emptyMap()
        } else {
            detailsJson.keys().asSequence().associateWith { name -> detailsJson.optString(name) }
        }
        return SnapchatThemeMappedSurface(
            key = key,
            label = json.optString("label").ifBlank { friendlyLabelForKey(key) },
            description = json.optString("description"),
            category = json.optString("category", "Observed"),
            source = json.optString("source", "runtime"),
            attributeName = json.optString("attribute_name").takeIf { it.isNotBlank() },
            className = json.optString("class_name").takeIf { it.isNotBlank() },
            valueSummary = json.optString("value_summary").takeIf { it.isNotBlank() },
            valueType = json.optString("value_type").takeIf { it.isNotBlank() },
            viewClass = json.optString("view_class").takeIf { it.isNotBlank() },
            resourceName = json.optString("resource_name").takeIf { it.isNotBlank() },
            selector = json.optString("selector").takeIf { it.isNotBlank() },
            selectorDisplayName = json.optString("selector_display_name").takeIf { it.isNotBlank() },
            screenHint = json.optString("screen_hint").takeIf { it.isNotBlank() },
            role = json.optString("role").takeIf { it.isNotBlank() },
            screenBounds = json.optString("screen_bounds").takeIf { it.isNotBlank() },
            localBounds = json.optString("local_bounds").takeIf { it.isNotBlank() },
            rootBounds = json.optString("root_bounds").takeIf { it.isNotBlank() },
            parentClassName = json.optString("parent_class_name").takeIf { it.isNotBlank() },
            parentResourceName = json.optString("parent_resource_name").takeIf { it.isNotBlank() },
            ancestry = json.optString("ancestry").takeIf { it.isNotBlank() },
            firstSeenMs = json.optLong("first_seen_ms", 0L),
            lastSeenMs = json.optLong("last_seen_ms", 0L),
            hitCount = json.optInt("hit_count", 0),
            defaultColor = if (json.has("default_color") && !json.isNull("default_color")) json.optInt("default_color") else null,
            confidence = json.optDouble("confidence", 0.0).toFloat(),
            details = details
        )
    }

    private fun surfaceToJson(surface: SnapchatThemeMappedSurface): JSONObject {
        return JSONObject()
            .put("key", normalizeKey(surface.key))
            .put("label", surface.label.ifBlank { friendlyLabelForKey(surface.key) })
            .put("description", surface.description)
            .put("category", surface.category)
            .put("source", surface.source)
            .put("attribute_name", surface.attributeName ?: JSONObject.NULL)
            .put("class_name", surface.className ?: JSONObject.NULL)
            .put("value_summary", surface.valueSummary ?: JSONObject.NULL)
            .put("value_type", surface.valueType ?: JSONObject.NULL)
            .put("view_class", surface.viewClass ?: JSONObject.NULL)
            .put("resource_name", surface.resourceName ?: JSONObject.NULL)
            .put("selector", surface.selector ?: JSONObject.NULL)
            .put("selector_display_name", surface.selectorDisplayName ?: JSONObject.NULL)
            .put("screen_hint", surface.screenHint ?: JSONObject.NULL)
            .put("role", surface.role ?: JSONObject.NULL)
            .put("screen_bounds", surface.screenBounds ?: JSONObject.NULL)
            .put("local_bounds", surface.localBounds ?: JSONObject.NULL)
            .put("root_bounds", surface.rootBounds ?: JSONObject.NULL)
            .put("parent_class_name", surface.parentClassName ?: JSONObject.NULL)
            .put("parent_resource_name", surface.parentResourceName ?: JSONObject.NULL)
            .put("ancestry", surface.ancestry ?: JSONObject.NULL)
            .put("first_seen_ms", surface.firstSeenMs)
            .put("last_seen_ms", surface.lastSeenMs)
            .put("hit_count", surface.hitCount)
            .put("default_color", surface.defaultColor ?: JSONObject.NULL)
            .put("confidence", surface.confidence.toDouble())
            .put(
                "details",
                JSONObject().also { details ->
                    surface.details.toSortedMap().forEach { (key, value) ->
                        details.put(key, value)
                    }
                }
            )
    }
}
