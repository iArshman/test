package me.eternal.purrfect.storage

import android.content.ContentValues
import me.eternal.purrfect.common.util.ktx.getStringOrNull
import org.json.JSONArray

data class AssistantRegistryEntry(
    val id: String,
    val kind: String,
    val title: String,
    val category: String,
    val path: String,
    val description: String,
    val settingKey: String? = null,
    val screenRoute: String? = null,
    val allowedActions: List<String> = emptyList(),
    val allowedValues: List<String> = emptyList(),
    val aliases: List<String> = emptyList(),
    val commonTypos: List<String> = emptyList(),
    val examples: List<String> = emptyList(),
    val searchTokens: List<String> = emptyList()
)

private fun List<String>.toJsonArrayString(): String = JSONArray(this).toString()

private fun parseStringList(raw: String?): List<String> {
    if (raw.isNullOrBlank()) return emptyList()
    return runCatching {
        val array = JSONArray(raw)
        buildList {
            for (index in 0 until array.length()) {
                array.optString(index).takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }.getOrDefault(emptyList())
}

fun AppDatabase.replaceAssistantRegistry(entries: List<AssistantRegistryEntry>) {
    database.beginTransaction()
    try {
        database.execSQL("DELETE FROM assistant_registry")
        entries.forEach { entry ->
            database.insert(
                "assistant_registry",
                null,
                ContentValues().apply {
                    put("id", entry.id)
                    put("kind", entry.kind)
                    put("title", entry.title)
                    put("category", entry.category)
                    put("path", entry.path)
                    put("description", entry.description)
                    put("settingKey", entry.settingKey)
                    put("screenRoute", entry.screenRoute)
                    put("allowedActions", entry.allowedActions.toJsonArrayString())
                    put("allowedValues", entry.allowedValues.toJsonArrayString())
                    put("aliases", entry.aliases.toJsonArrayString())
                    put("commonTypos", entry.commonTypos.toJsonArrayString())
                    put("examples", entry.examples.toJsonArrayString())
                    put("searchTokens", entry.searchTokens.toJsonArrayString())
                }
            )
        }
        database.setTransactionSuccessful()
    } finally {
        database.endTransaction()
    }
}

fun AppDatabase.getAssistantRegistryEntries(): List<AssistantRegistryEntry> {
    return database.rawQuery("SELECT * FROM assistant_registry", null).use { cursor ->
        val entries = mutableListOf<AssistantRegistryEntry>()
        while (cursor.moveToNext()) {
            entries += AssistantRegistryEntry(
                id = cursor.getStringOrNull("id") ?: continue,
                kind = cursor.getStringOrNull("kind") ?: "feature",
                title = cursor.getStringOrNull("title") ?: "",
                category = cursor.getStringOrNull("category") ?: "",
                path = cursor.getStringOrNull("path") ?: "",
                description = cursor.getStringOrNull("description") ?: "",
                settingKey = cursor.getStringOrNull("settingKey"),
                screenRoute = cursor.getStringOrNull("screenRoute"),
                allowedActions = parseStringList(cursor.getStringOrNull("allowedActions")),
                allowedValues = parseStringList(cursor.getStringOrNull("allowedValues")),
                aliases = parseStringList(cursor.getStringOrNull("aliases")),
                commonTypos = parseStringList(cursor.getStringOrNull("commonTypos")),
                examples = parseStringList(cursor.getStringOrNull("examples")),
                searchTokens = parseStringList(cursor.getStringOrNull("searchTokens"))
            )
        }
        entries
    }
}
