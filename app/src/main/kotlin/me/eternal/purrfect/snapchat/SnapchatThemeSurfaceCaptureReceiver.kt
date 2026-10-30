package me.eternal.purrfect.snapchat

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import me.eternal.purrfect.SharedContextHolder
import me.eternal.purrfect.common.Constants
import org.json.JSONObject

class SnapchatThemeSurfaceCaptureReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "SnapThemeCapture"
        private const val SNAPCHAT_VIRTUAL_SELECTOR_PREFIX = "snapvirtual:v1|"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Constants.SNAPCHAT_THEME_SURFACE_CAPTURED_ACTION) return

        val rawValue = intent.getStringExtra(Constants.SNAPCHAT_THEME_SURFACE_VALUE_EXTRA).orEmpty()
        val isSelector = intent.getBooleanExtra(Constants.SNAPCHAT_THEME_SURFACE_IS_SELECTOR_EXTRA, false)
        val clean = if (isSelector) normalizeSelector(rawValue) else normalizeId(rawValue)
        Log.d(TAG, "received raw=$rawValue selector=$isSelector clean=$clean package=${intent.`package`}")
        if (clean.isEmpty()) {
            Log.w(TAG, "ignored empty theme surface capture raw=$rawValue selector=$isSelector")
            return
        }

        val label = intent.getStringExtra(Constants.SNAPCHAT_THEME_SURFACE_LABEL_EXTRA)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: if (isSelector) "Captured surface" else clean
        val color = intent.getStringExtra(Constants.SNAPCHAT_THEME_SURFACE_COLOR_EXTRA)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: "#FF000000"
        val role = intent.getStringExtra(Constants.SNAPCHAT_THEME_SURFACE_ROLE_EXTRA).orEmpty()
        val previewMode = intent.getStringExtra(Constants.SNAPCHAT_THEME_SURFACE_PREVIEW_MODE_EXTRA).orEmpty()
        val bounds = intent.getStringExtra(Constants.SNAPCHAT_THEME_SURFACE_BOUNDS_EXTRA).orEmpty()
        val localBounds = intent.getStringExtra(Constants.SNAPCHAT_THEME_SURFACE_LOCAL_BOUNDS_EXTRA).orEmpty()
        val detail = intent.getStringExtra(Constants.SNAPCHAT_THEME_SURFACE_DETAIL_EXTRA).orEmpty()
        val targetClass = intent.getStringExtra(Constants.SNAPCHAT_THEME_SURFACE_TARGET_CLASS_EXTRA).orEmpty()
        val targetId = intent.getStringExtra(Constants.SNAPCHAT_THEME_SURFACE_TARGET_ID_EXTRA).orEmpty()
        val targetSummary = intent.getStringExtra(Constants.SNAPCHAT_THEME_SURFACE_TARGET_SUMMARY_EXTRA).orEmpty()

        runCatching {
            val remoteContext = SharedContextHolder.remote(context)
            val customTheme = remoteContext.config.root.userInterface.customTheme
            val oldRules = customTheme.customSurfaceRules.getNullable().orEmpty()
            val oldParsedRules = oldRules.lineSequence().mapNotNull(::parseRule).toList()
            val replacingExisting = oldParsedRules.any { it.target == clean && it.isSelector == isSelector }
            val droppedBroadRules = mutableListOf<ThemeSurfaceRule>()
            val newRules = appendOrReplaceRule(
                oldRules,
                ThemeSurfaceRule(
                    target = clean,
                    isSelector = isSelector,
                    label = label,
                    color = color
                ),
                role = role,
                targetId = targetId,
                droppedBroadRules = droppedBroadRules
            )
            Log.d(
                TAG,
                "persist target=$clean selector=$isSelector label=$label color=$color replacing=$replacingExisting " +
                    "role=${role.ifBlank { "none" }} mode=${previewMode.ifBlank { "none" }} " +
                    "bounds=${bounds.ifBlank { "none" }} localBounds=${localBounds.ifBlank { "none" }} " +
                    "targetClass=${targetClass.ifBlank { "none" }} targetId=${targetId.ifBlank { "none" }} " +
                    "detail=${detail.take(700).ifBlank { "none" }} targetSummary=${targetSummary.take(700).ifBlank { "none" }} " +
                    "oldRuleCount=${oldParsedRules.size} oldBytes=${oldRules.length} newBytes=${newRules.length} " +
                    "droppedBroadRules=${droppedBroadRules.size}:${droppedBroadRules.joinToString { it.logSummary() }.take(700)} " +
                    "oldTargets=${oldParsedRules.take(24).joinToString { it.logSummary() }} " +
                    "newPreview=${newRules.replace('\n', '|').take(1200)}"
            )
            customTheme.customSurfaceRules.set(
                newRules
            )
            customTheme.enabled.set(true)
            remoteContext.config.writeConfig()
            Log.d(TAG, "config written target=$clean selector=$isSelector")

            context.sendBroadcast(
                Intent(Constants.SNAPCHAT_THEME_CONFIG_CHANGED_ACTION)
                    .setPackage(Constants.SNAPCHAT_PACKAGE_NAME)
                    .putExtra(Constants.SNAPCHAT_THEME_SURFACE_VALUE_EXTRA, clean)
                    .putExtra(Constants.SNAPCHAT_THEME_SURFACE_IS_SELECTOR_EXTRA, isSelector)
                    .putExtra(Constants.SNAPCHAT_THEME_SURFACE_LABEL_EXTRA, label)
                    .putExtra(Constants.SNAPCHAT_THEME_SURFACE_COLOR_EXTRA, color)
                    .putExtra(Constants.SNAPCHAT_THEME_SURFACE_ROLE_EXTRA, role)
                    .putExtra(Constants.SNAPCHAT_THEME_SURFACE_PREVIEW_MODE_EXTRA, previewMode)
                    .putExtra(Constants.SNAPCHAT_THEME_SURFACE_BOUNDS_EXTRA, bounds)
                    .putExtra(Constants.SNAPCHAT_THEME_SURFACE_LOCAL_BOUNDS_EXTRA, localBounds)
                    .putExtra(Constants.SNAPCHAT_THEME_SURFACE_DETAIL_EXTRA, detail)
                    .putExtra(Constants.SNAPCHAT_THEME_SURFACE_TARGET_CLASS_EXTRA, targetClass)
                    .putExtra(Constants.SNAPCHAT_THEME_SURFACE_TARGET_ID_EXTRA, targetId)
                    .putExtra(Constants.SNAPCHAT_THEME_SURFACE_TARGET_SUMMARY_EXTRA, targetSummary)
            )
            Log.d(TAG, "theme config broadcast sent targetPackage=${Constants.SNAPCHAT_PACKAGE_NAME}")
        }.onFailure {
            Log.e(TAG, "failed to persist captured theme surface target=$clean selector=$isSelector", it)
        }
    }

    private fun appendOrReplaceRule(
        raw: String,
        rule: ThemeSurfaceRule,
        role: String,
        targetId: String,
        droppedBroadRules: MutableList<ThemeSurfaceRule>
    ): String {
        val values = raw.lineSequence()
            .mapNotNull(::parseRule)
            .filterNot { existing ->
                val exactReplacement = existing.target == rule.target && existing.isSelector == rule.isSelector
                val broadReplacement = shouldReplaceBroadRuleWithScopedRule(existing, rule, role, targetId)
                if (broadReplacement) droppedBroadRules += existing
                exactReplacement || broadReplacement
            }
            .toMutableList()
        values += rule
        return values.joinToString("\n") { it.toJsonLine() }
    }

    private fun shouldReplaceBroadRuleWithScopedRule(
        existing: ThemeSurfaceRule,
        replacement: ThemeSurfaceRule,
        role: String,
        targetId: String
    ): Boolean {
        if (!replacement.isSelector || !replacement.target.startsWith("selector:v1|")) return false
        if (!replacement.target.contains("scope=theme_self")) return false
        if (existing.isSelector) return false
        val cleanTargetId = normalizeId(targetId)
        if (!isRepeatedSnapchatSurfaceId(cleanTargetId)) return false
        if (normalizeId(existing.target) != cleanTargetId) return false
        val cleanRole = role.trim()
        if (cleanRole.isEmpty()) return true
        return existing.label.startsWith(cleanRole, ignoreCase = true)
    }

    private fun isRepeatedSnapchatSurfaceId(value: String): Boolean {
        if (value.isBlank()) return false
        return value == "ff_item" ||
            value.startsWith("ngs_") ||
            value.endsWith("_icon_container") ||
            value.endsWith("_item") ||
            value.endsWith("_row")
    }

    private fun parseRule(rawLine: String): ThemeSurfaceRule? {
        val line = rawLine.trim()
        if (line.isEmpty()) return null
        return runCatching {
            val json = JSONObject(line)
            ThemeSurfaceRule(
                target = json.optString("target").trim(),
                isSelector = json.optBoolean("selector", false),
                label = json.optString("label").trim().ifEmpty { "Captured surface" },
                color = json.optString("color").trim().ifEmpty { "#FF000000" }
            )
        }.getOrNull()?.takeIf { it.target.isNotEmpty() }
    }

    private fun normalizeSelector(raw: String): String {
        val clean = raw.trim()
        return if (clean.startsWith("selector:v1|") || clean.startsWith(SNAPCHAT_VIRTUAL_SELECTOR_PREFIX)) clean else ""
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
        if (isObfuscatedResourcePlaceholder(clean)) return ""
        return clean
    }

    private fun isObfuscatedResourcePlaceholder(value: String): Boolean {
        return value.endsWith("_resource_name_obfuscated") ||
            value == "0" ||
            value.matches(Regex("\\d+_resource_name_obfuscated"))
    }

    private data class ThemeSurfaceRule(
        val target: String,
        val isSelector: Boolean,
        val label: String,
        val color: String
    ) {
        fun logSummary(): String {
            return "target=$target selector=$isSelector label=$label color=$color"
        }

        fun toJsonLine(): String {
            return JSONObject()
                .put("target", target)
                .put("selector", isSelector)
                .put("label", label)
                .put("color", color)
                .toString()
        }
    }
}
