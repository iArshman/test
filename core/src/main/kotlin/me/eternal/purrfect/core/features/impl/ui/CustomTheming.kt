package me.eternal.purrfect.core.features.impl.ui

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.content.res.Resources
import android.content.res.TypedArray
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.TypedValue
import android.view.View
import android.view.Window
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageView
import android.widget.TextView
import me.eternal.purrfect.common.BuildConfig
import me.eternal.purrfect.common.Constants
import me.eternal.purrfect.common.bridge.FileHandleScope
import me.eternal.purrfect.common.bridge.InternalFileHandleType
import me.eternal.purrfect.common.bridge.toWrapper
import me.eternal.purrfect.common.theme.SnapchatThemeMappedSurface
import me.eternal.purrfect.common.theme.SnapchatThemeSurfaceMap
import me.eternal.purrfect.common.theme.SnapchatThemeSurfaceMapCodec
import me.eternal.purrfect.core.event.events.impl.AddViewEvent
import me.eternal.purrfect.core.features.Feature
import me.eternal.purrfect.core.ui.getValdiViewNode
import me.eternal.purrfect.core.ui.randomTag
import me.eternal.purrfect.core.util.hook.HookAdapter
import me.eternal.purrfect.core.util.hook.HookStage
import me.eternal.purrfect.core.util.hook.Hooker
import me.eternal.purrfect.core.util.hook.hook
import me.eternal.purrfect.core.util.ktx.getObjectField
import me.eternal.purrfect.core.whatsapp.WhatsAppUiElementSelector
import me.eternal.purrfect.core.wrapper.impl.valdi.ValdiViewNode
import me.eternal.purrfect.mapper.impl.ThemeSurfaceMapper
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.lang.reflect.Modifier
import java.util.LinkedHashMap
import java.util.concurrent.Executors
import kotlin.concurrent.thread

class CustomTheming : Feature("Custom Theming") {
    private val amoledBlack = Color.BLACK
    private val maxSurfaceAverage = 0x38
    private val valdiPreDrawInstalledTag = randomTag()
    private val androidLayoutPatchInstalledTag = randomTag()
    private val viewMatchNameTag = randomTag()
    private val viewThemeMatchTag = randomTag()
    private val viewAppliedThemeColorTag = randomTag()
    private val viewAppliedThemeBackgroundTag = randomTag()
    private val viewAppliedVirtualThemeOverlaysTag = randomTag()
    private val whitespaceRegex = Regex("\\s+")
    private val maxThemeSurfaceMapEntries = 2400
    private val maxThemeSurfaceMapJsonBytes = 8 * 1024 * 1024
    private val maxThemeSurfaceDetailsPerEntry = 48
    private val virtualThemeLayoutPatchInstalledTag = randomTag()
    private val virtualThemeLayoutPatchSignatureTag = randomTag()
    private val virtualThemeLifecycleRetryTag = randomTag()
    private val virtualThemeDrawApplySignatureTag = randomTag()
    private val viewPreviewOriginalStateTag = randomTag()
    private val viewAttachPatchSignatureTag = randomTag()
    private val viewValdiDebugDescriptionTag = randomTag()
    private val surfaceLayoutPatchSignatureTag = randomTag()
    private val androidLayoutPatchSignatureTag = randomTag()
    private var hookInstallLogged = false
    private var attrPatchLogCount = 0
    private var valdiPatchLogCount = 0
    private var valdiPreDrawPatchLogCount = 0
    private var androidLayoutPatchLogCount = 0
    private var amoledTraceSequence = 0
    private val internalThemeMutation = ThreadLocal<Boolean>()
    private val activeThemeDrawSurface = ThreadLocal<View>()
    private val themeDrawSurfaceStack = ThreadLocal<MutableList<View?>>()
    private val amoledTraceExecutor by lazy(LazyThreadSafetyMode.PUBLICATION) {
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "PurrfectThemeTrace").apply {
                isDaemon = true
                priority = Thread.MIN_PRIORITY
            }
        }
    }
    private val virtualThemeKnownHosts = java.util.Collections.newSetFromMap(java.util.WeakHashMap<View, Boolean>())
    private val accessibilityGetChildIdMethod by lazy {
        runCatching {
            AccessibilityNodeInfo::class.java.getDeclaredMethod(
                "getChildId",
                Int::class.javaPrimitiveType!!
            ).apply { isAccessible = true }
        }.getOrNull()
    }
    private val accessibilityGetVirtualDescendantIdMethod by lazy {
        runCatching {
            AccessibilityNodeInfo::class.java.getDeclaredMethod(
                "getVirtualDescendantId",
                Long::class.javaPrimitiveType!!
            ).apply { isAccessible = true }
        }.getOrNull()
    }
    @Volatile private var cachedThemeRuntime = ThemeRuntime.EMPTY
    @Volatile private var previewRule: ThemePreviewRule? = null
    private var currentActivityRef: WeakReference<Activity>? = null
    private var themeBroadcastReceiver: BroadcastReceiver? = null
    private var themeBroadcastReceiverRegistered = false
    private val themeSurfaceMapLock = Any()
    private val themeSurfaceMapEntries = LinkedHashMap<String, SnapchatThemeMappedSurface>()
    private val staticThemeIndexLock = Any()
    @Volatile private var themeSurfaceMapLoaded = false
    @Volatile private var themeSurfaceMapLoadedHash = Long.MIN_VALUE
    @Volatile private var themeSurfaceMapDirty = false
    @Volatile private var themeSurfaceMapFlushScheduled = false
    @Volatile private var staticThemeSurfaceIndex = StaticThemeSurfaceIndex.EMPTY
    @Volatile private var retainedValdiSurfaceAttributeSnapshot = emptyArray<String>()
    @Volatile private var retainedValdiSurfaceAttributeSnapshotSignature = Int.MIN_VALUE
    private val themeSurfaceMapFile by lazy {
        context.fileHandlerManager
            .getFileHandle(FileHandleScope.INTERNAL.key, InternalFileHandleType.SNAPCHAT_THEME_SURFACES.key)
            .toWrapper()
    }

    private val legacyKnownAttrIds = setOf(
        0x7f0404b8 // older Snapchat builds
    )

    private val patchedAttrIds = hashSetOf<Int>()

    private val colorTypes = setOf(
        TypedValue.TYPE_INT_COLOR_ARGB8,
        TypedValue.TYPE_INT_COLOR_RGB8,
        TypedValue.TYPE_INT_COLOR_ARGB4,
        TypedValue.TYPE_INT_COLOR_RGB4
    )

    private val valdiClassNameCache = object : LinkedHashMap<Long, String?>(160, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, String?>): Boolean = size > 512
    }

    private val valdiContentHandleCache = object : LinkedHashMap<Long, Boolean>(160, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Boolean>): Boolean = size > 512
    }

    private val backgroundDrawableStateCache = object : LinkedHashMap<String, Drawable.ConstantState?>(12, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Drawable.ConstantState?>): Boolean = size > 16
    }

    private val drawableReflectionFieldCache = object : LinkedHashMap<Class<*>, List<java.lang.reflect.Field>>(48, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Class<*>, List<java.lang.reflect.Field>>): Boolean = size > 128
    }

    private val valdiValuePatchCache = object : LinkedHashMap<String, Any?>(192, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Any?>): Boolean = size > 768
    }

    private val valdiPatchLogKeys = object : LinkedHashMap<String, Boolean>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>): Boolean = size > 128
    }

    private val amoledTraceLogKeys = object : LinkedHashMap<String, Boolean>(512, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>): Boolean = size > 4096
    }

    private val amoledTraceCategoryCounts = object : LinkedHashMap<String, Int>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>): Boolean = size > 96
    }

    private val amoledTraceCappedCategories = object : LinkedHashMap<String, Boolean>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>): Boolean = size > 96
    }

    private val contentTokens = listOf(
        "avatar",
        "bitmoji",
        "camera",
        "canvas",
        "capture",
        "creative",
        "editor",
        "flatlandmap",
        "geo",
        "glsurface",
        "image",
        "lens",
        "location",
        "map",
        "mapkit",
        "mappreview",
        "map_preview",
        "maprenderer",
        "map_renderer",
        "mapview",
        "media",
        "photo",
        "picture",
        "preview",
        "snapmap",
        "snappreview",
        "snap_preview",
        "sticker",
        "surfaceview",
        "texture",
        "textureview",
        "thumbnail",
        "tile",
        "tileview",
        "tile_view",
        "video"
    )

    private val valdiDebugContentTokens = listOf(
        "camera",
        "canvas",
        "capture",
        "creative",
        "editor",
        "flatlandmap",
        "geo",
        "glsurface",
        "lens",
        "location",
        "map",
        "mapkit",
        "mappreview",
        "map_preview",
        "maprenderer",
        "map_renderer",
        "mapview",
        "media",
        "photo",
        "picture",
        "preview",
        "snapmap",
        "snappreview",
        "snap_preview",
        "surfaceview",
        "texture",
        "textureview",
        "tile",
        "tileview",
        "tile_view",
        "video"
    )

    private val surfaceAttributeTokens = listOf(
        "background",
        "surface",
        "container",
        "card",
        "panel",
        "sheet",
        "scrim",
        "overlay",
        "page",
        "row",
        "cell",
        "chrome",
        "bar"
    )

    private val controlSurfaceTokens = listOf(
        "button",
        "close",
        "dismiss",
        "icon",
        "loading",
        "pill",
        "badge",
        "chip",
        "spinner",
        "avatar",
        "bitmoji"
    )

    private val namedAndroidSurfaceTokens = listOf(
        "recipient_bar",
        "header_text_field",
        "select_recipients_",
        "new_group_confirm_chat",
        "tabs_header_item"
    )

    private val globalBackgroundCatalogTargets = setOf(
        "global",
        "__global__",
        "android.view.global",
        "background",
        "appbackground",
        "valdi.token.backgroundmain",
        "valdi.token.backgroundsubscreen",
        "backgroundmain",
        "backgroundsubscreen",
        "android.attr.colorbackground",
        "android.attr.windowbackground",
        "android.attr.0x1010031",
        "android.attr.0x1010054"
    )

    private val valdiDiagnosticAttributes = arrayOf(
        "id",
        "name",
        "key",
        "role",
        "label",
        "title",
        "text",
        "value",
        "testID",
        "testId",
        "test_id",
        "identifier",
        "accessibilityIdentifier",
        "accessibilityLabel",
        "accessibilityLabelValue",
        "accessibilityHint",
        "accessibilityRole",
        "resourceID",
        "resourceId",
        "resource_id",
        "resourceName",
        "resource_name",
        "viewId",
        "view_id",
        "elementId",
        "element_id",
        "background",
        "backgroundColor",
        "background_color",
        "backgroundFill",
        "background_fill",
        "backgroundToken",
        "background_token",
        "surface",
        "surfaceColor",
        "surface_color",
        "container",
        "containerColor",
        "container_color",
        "card",
        "cardColor",
        "card_color",
        "panel",
        "panelColor",
        "panel_color",
        "sheet",
        "sheetColor",
        "sheet_color",
        "rowBackground",
        "rowBackgroundColor",
        "itemBackground",
        "itemBackgroundColor",
        "selectedBackground",
        "selectedBackgroundColor",
        "fill",
        "fillColor",
        "fill_color",
        "fillToken",
        "fill_token",
        "color",
        "colorToken",
        "color_token",
        "theme",
        "themeToken",
        "theme_token",
        "style",
        "decoration",
        "bounds",
        "frame",
        "layout",
        "size",
        "width",
        "height",
        "x",
        "y"
    )


    private val retainedValdiSurfaceAttributes = arrayOf(
        "background",
        "backgroundColor",
        "background_color",
        "backgroundFill",
        "background_fill",
        "surface",
        "surfaceColor",
        "surface_color",
        "container",
        "containerColor",
        "container_color",
        "card",
        "cardColor",
        "card_color",
        "panel",
        "panelColor",
        "panel_color",
        "sheet",
        "sheetColor",
        "sheet_color",
        "rowBackground",
        "rowBackgroundColor",
        "row_background",
        "row_background_color",
        "cellBackground",
        "cellBackgroundColor",
        "cell_background",
        "cell_background_color",
        "itemBackground",
        "itemBackgroundColor",
        "item_background",
        "item_background_color",
        "selectedBackground",
        "selectedBackgroundColor",
        "selected_background",
        "selected_background_color",
        "fill",
        "fillColor",
        "fill_color",
        "primaryFill",
        "primaryFillColor",
        "secondaryFill",
        "secondaryFillColor",
        "chromeColor",
        "color"
    )

    private val valdiDebugSurfaceAttributes = arrayOf(
        "background",
        "backgroundcolor",
        "background_color",
        "backgroundfill",
        "background_fill",
        "surface",
        "surfacecolor",
        "surface_color",
        "container",
        "containercolor",
        "container_color",
        "card",
        "cardcolor",
        "card_color",
        "panel",
        "panelcolor",
        "panel_color",
        "sheet",
        "sheetcolor",
        "sheet_color",
        "rowbackground",
        "rowbackgroundcolor",
        "row_background",
        "row_background_color",
        "cellbackground",
        "cellbackgroundcolor",
        "cell_background",
        "cell_background_color",
        "itembackground",
        "itembackgroundcolor",
        "item_background",
        "item_background_color",
        "selectedbackground",
        "selectedbackgroundcolor",
        "selected_background",
        "selected_background_color",
        "primaryfill",
        "primaryfillcolor",
        "secondaryfill",
        "secondaryfillcolor",
        "chromecolor"
    )

    private val valdiGenericSurfaceAttributes = setOf(
        "fill",
        "fillcolor",
        "color"
    )

    private data class ThemeRuntime(
        val forceAmoled: Boolean,
        val customEnabled: Boolean,
        val rawSurfaceOverrides: String,
        val rawSurfaceRules: String,
        val rawScreenBackgrounds: String,
        val surfaceOverrides: Map<String, Int>,
        val rules: List<ThemeSurfaceRule>,
        val backgrounds: List<ThemeBackgroundRule>,
        val selectorRules: List<ThemeSurfaceRule>,
        val resourceRuleColors: Map<String, Int>,
        val globalRuleColors: Map<String, Int>,
        val selectorBackgrounds: List<ThemeBackgroundRule>,
        val resourceBackgroundPaths: Map<String, String>,
        val globalBackgroundPaths: Map<String, String>
    ) {
        val hasActivePatches: Boolean
            get() = forceAmoled ||
                (customEnabled && (surfaceOverrides.isNotEmpty() || rules.isNotEmpty() || backgrounds.isNotEmpty()))

        val hasValdiPatches: Boolean
            get() = forceAmoled || (customEnabled && surfaceOverrides.isNotEmpty())

        val viewRuleSignature: Int
            get() = 31 * rawSurfaceRules.hashCode() + rawScreenBackgrounds.hashCode()

        companion object {
            val EMPTY = ThemeRuntime(
                forceAmoled = false,
                customEnabled = false,
                rawSurfaceOverrides = "",
                rawSurfaceRules = "",
                rawScreenBackgrounds = "",
                surfaceOverrides = emptyMap(),
                rules = emptyList(),
                backgrounds = emptyList(),
                selectorRules = emptyList(),
                resourceRuleColors = emptyMap(),
                globalRuleColors = emptyMap(),
                selectorBackgrounds = emptyList(),
                resourceBackgroundPaths = emptyMap(),
                globalBackgroundPaths = emptyMap()
            )
        }
    }

    private data class ThemeSurfaceRule(
        val target: String,
        val selector: Boolean,
        val label: String,
        val color: Int
    )

    private data class ThemeBackgroundRule(
        val target: String,
        val selector: Boolean,
        val label: String,
        val path: String
    )

    private inline fun <T : Any, V> Sequence<T>.associateByNormalizedTarget(valueSelector: (T) -> V): Map<String, V> {
        val values = linkedMapOf<String, V>()
        for (item in this) {
            val target = when (item) {
                is ThemeSurfaceRule -> item.target
                is ThemeBackgroundRule -> item.target
                else -> continue
            }
            val normalized = normalizeSurfaceKey(target)
            if (normalized.isBlank()) continue
            values[normalized] = valueSelector(item)
            if (normalized.startsWith("android.view.")) {
                values[normalized.removePrefix("android.view.")] = valueSelector(item)
            } else {
                values["android.view.$normalized"] = valueSelector(item)
            }
        }
        return values
    }

    private data class ThemePreviewRule(
        val target: String,
        val selector: Boolean,
        val color: Int,
        val expiresAtMs: Long
    ) {
        val signature: Int
            get() = 31 * target.hashCode() + color
    }

    private data class ViewThemeMatch(
        val signature: Int,
        val viewStateSignature: Int,
        val color: Int?,
        val backgroundPath: String?,
        val colorSource: String?,
        val backgroundSource: String?,
        val computedAtMs: Long
    )

    private data class OriginalViewThemeState(
        val background: Drawable?,
        val backgroundTint: ColorStateList?,
        val imageTint: ColorStateList?,
        val textColor: Int? = null
    )

    private data class VirtualThemeOverlay(
        val selector: String,
        val color: Int,
        val localBounds: Rect,
        val text: String? = null
    )

    private data class VirtualThemeOverlayState(
        val signature: Int,
        val drawables: List<Drawable>,
        val textOverlayCount: Int = 0
    )

    private data class CanvasTextDrawCall(
        val text: String?,
        val x: Float,
        val y: Float,
        val paintIndex: Int,
        val paint: Paint
    )

    private class VirtualTextThemeDrawable(
        private val text: String,
        private val color: Int,
        private val bold: Boolean,
        private val backgroundColor: Int,
        textBounds: Rect
    ) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            this.color = this@VirtualTextThemeDrawable.color
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            style = Paint.Style.FILL
        }
        private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = this@VirtualTextThemeDrawable.backgroundColor
            style = Paint.Style.FILL
        }

        init {
            bounds = textBounds
        }

        override fun draw(canvas: Canvas) {
            if (text.isBlank() || bounds.isEmpty) return
            val oldSize = paint.textSize
            val mask = Rect(bounds).apply {
                inset(-6, -4)
            }
            canvas.drawRect(mask, backgroundPaint)
            paint.textSize = (bounds.height() * 0.64f).coerceAtLeast(10f)
            val metrics = paint.fontMetrics
            val baseline = bounds.centerY() - (metrics.ascent + metrics.descent) / 2f
            val save = canvas.save()
            canvas.clipRect(bounds)
            canvas.drawText(text, bounds.left.toFloat(), baseline, paint)
            canvas.restoreToCount(save)
            paint.textSize = oldSize
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
            invalidateSelf()
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
            invalidateSelf()
        }

        @Deprecated("Deprecated in Android framework")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    private data class RetainedValdiScanGate(
        val allowed: Boolean,
        val reason: String,
        val debugDescription: String
    )

    private data class GlobalBackgroundHostDecision(
        val matched: Boolean,
        val reason: String
    )

    private data class SnapchatThemeMapBuildInfo(
        val uniqueHash: Long,
        val versionName: String?,
        val versionCode: Long
    )

    private data class StaticThemeSurfaceIndex(
        val uniqueHash: Long,
        val loaded: Boolean,
        val attributeStrings: Map<String, String?>,
        val tokenStrings: Map<String, String?>,
        val ownerSamples: Map<String, String?>,
        val candidateClasses: Map<String, String?>,
        val candidateMethods: Map<String, String?>,
        val valdiRootClasses: Map<String, String?>,
        val valdiBridgeClass: String?,
        val valdiSetValueMethod: String?,
        val valdiGetValueMethod: String?,
        val valdiDebugDescriptionMethod: String?,
        val valdiRetainedChildrenMethod: String?
    ) {
        val normalizedAttributes: Set<String> = attributeStrings.flatMap { (normalized, raw) ->
            listOfNotNull(normalized, raw)
        }.map { SnapchatThemeSurfaceMapCodec.normalizeKey(it) }
            .filter { it.isNotBlank() }
            .toSet()

        val normalizedTokens: Set<String> = tokenStrings.flatMap { (normalized, raw) ->
            listOfNotNull(normalized, raw)
        }.map { SnapchatThemeSurfaceMapCodec.normalizeKey(it) }
            .filter { it.isNotBlank() }
            .toSet()

        val signature: Int = 31 * uniqueHash.hashCode() + normalizedAttributes.hashCode()

        companion object {
            val EMPTY = StaticThemeSurfaceIndex(
                uniqueHash = Long.MIN_VALUE,
                loaded = false,
                attributeStrings = emptyMap(),
                tokenStrings = emptyMap(),
                ownerSamples = emptyMap(),
                candidateClasses = emptyMap(),
                candidateMethods = emptyMap(),
                valdiRootClasses = emptyMap(),
                valdiBridgeClass = null,
                valdiSetValueMethod = null,
                valdiGetValueMethod = null,
                valdiDebugDescriptionMethod = null,
                valdiRetainedChildrenMethod = null
            )
        }
    }

    private fun enqueueAmoledTraceLog(message: String) {
        enqueueAmoledTraceLog { message }
    }

    private fun enqueueAmoledTraceLog(message: () -> String) {
        val executor = runCatching { amoledTraceExecutor }.getOrNull()
        if (executor == null) {
            runCatching { context.log.verbose(message()) }
            return
        }
        runCatching {
            executor.execute {
                runCatching {
                    context.log.verbose(message())
                }.onFailure { error ->
                    runCatching {
                        context.log.verbose(
                            "[AMOLED TRACE][logger] trace_message_failed " +
                                "error=${error.javaClass.name}:${error.message}"
                        )
                    }
                }
            }
        }.onFailure {
            runCatching { context.log.verbose(message()) }
        }
    }

    private fun isCustomThemeTraceCategory(category: String): Boolean {
        return category.startsWith("theme-") ||
            category.startsWith("valdi") ||
            category.startsWith("android-") ||
            category.startsWith("surface-") ||
            category.startsWith("typed-array") ||
            category.startsWith("drawable") ||
            category.startsWith("background") ||
            category.startsWith("view-") ||
            category.startsWith("add-view") ||
            category.startsWith("patch-existing") ||
            category.startsWith("image-preview") ||
            category.startsWith("canvas") ||
            category == "activity" ||
            category == "window" ||
            category == "hook-install" ||
            category == "runtime" ||
            category == "preview"
    }

    private fun amoledTrace(
        category: String,
        key: String,
        maxPerCategory: Int = 400,
        unique: Boolean = true,
        message: () -> String
    ) {
        var sequence = 0
        var shouldLog = false
        var cappedKey: String? = null
        val effectiveMaxPerCategory = if (isCustomThemeTraceCategory(category)) Int.MAX_VALUE else maxPerCategory
        synchronized(amoledTraceLogKeys) {
            val currentCount = amoledTraceCategoryCounts[category] ?: 0
            if (currentCount < effectiveMaxPerCategory) {
                val fullKey = "$category|$key"
                if (!unique || !amoledTraceLogKeys.containsKey(fullKey)) {
                    if (unique) amoledTraceLogKeys[fullKey] = true
                    amoledTraceCategoryCounts[category] = currentCount + 1
                    amoledTraceSequence++
                    sequence = amoledTraceSequence
                    shouldLog = true
                }
            } else {
                val capKey = "$category|$effectiveMaxPerCategory"
                if (!amoledTraceCappedCategories.containsKey(capKey)) {
                    amoledTraceCappedCategories[capKey] = true
                    cappedKey = capKey
                }
            }
        }
        cappedKey?.let {
            enqueueAmoledTraceLog("[AMOLED TRACE][$category] capped at $effectiveMaxPerCategory entries; further matching logs are suppressed for this boot")
        }
        if (!shouldLog) return
        val queuedAt = SystemClock.uptimeMillis()
        val queuedThread = Thread.currentThread().name
        enqueueAmoledTraceLog {
            val loggerThread = Thread.currentThread().name
            val body = runCatching { message().take(2600) }
                .getOrElse { "trace_message_failed error=${it.javaClass.name}:${it.message}" }
            "[AMOLED TRACE #$sequence][$category][uptime=$queuedAt][thread=$queuedThread][logger=$loggerThread] " +
                body
        }
    }

    private fun colorHex(color: Int?): String {
        if (color == null) return "none"
        return "#${color.toUInt().toString(16).padStart(8, '0')}"
    }

    private fun valueSummary(value: Any?): String {
        if (value == null) return "null"
        val raw = value.toString()
            .replace('\n', ' ')
            .replace(Regex("\\s+"), " ")
            .take(260)
        val color = colorIntFromValue(value)
        return buildString {
            append(value.javaClass.name.substringAfterLast('.'))
            append('(').append(raw).append(')')
            if (color != null) append(" color=").append(colorHex(color))
        }
    }

    private fun currentThemeMapBuildInfo(): SnapchatThemeMapBuildInfo {
        val packageInfo = runCatching {
            context.androidContext.packageManager.getPackageInfo(Constants.SNAPCHAT_PACKAGE_NAME, 0)
        }.getOrNull()
        val versionCode = packageInfo?.longVersionCode ?: -1L
        return SnapchatThemeMapBuildInfo(
            uniqueHash = versionCode xor BuildConfig.BUILD_HASH.hashCode().toLong(),
            versionName = packageInfo?.versionName,
            versionCode = versionCode
        )
    }

    private fun mappedThemeSurfaceIndex(reason: String, force: Boolean = false): StaticThemeSurfaceIndex {
        val buildInfo = currentThemeMapBuildInfo()
        val current = staticThemeSurfaceIndex
        if (!force && current.loaded && current.uniqueHash == buildInfo.uniqueHash) return current

        synchronized(staticThemeIndexLock) {
            val locked = staticThemeSurfaceIndex
            if (!force && locked.loaded && locked.uniqueHash == buildInfo.uniqueHash) return locked

            var next = StaticThemeSurfaceIndex.EMPTY.copy(uniqueHash = buildInfo.uniqueHash)
            runCatching {
                if (!context.mappings.hasMapper(ThemeSurfaceMapper::class)) {
                    context.log.verbose("[THEME MAP] Static theme mapper disabled for $reason; using dynamic surface discovery")
                } else {
                    context.mappings.useMapper(ThemeSurfaceMapper::class) {
                        next = StaticThemeSurfaceIndex(
                            uniqueHash = buildInfo.uniqueHash,
                            loaded = true,
                            attributeStrings = surfaceAttributeStrings.getAsMap().orEmpty(),
                            tokenStrings = surfaceTokenStrings.getAsMap().orEmpty(),
                            ownerSamples = surfaceStringOwners.getAsMap().orEmpty(),
                            candidateClasses = themeCandidateClasses.getAsMap().orEmpty(),
                            candidateMethods = themeCandidateMethods.getAsMap().orEmpty(),
                            valdiRootClasses = valdiGeneratedRootClasses.getAsMap().orEmpty(),
                            valdiBridgeClass = valdiBridgeClass.getAsString(),
                            valdiSetValueMethod = valdiSetValueMethod.getAsString(),
                            valdiGetValueMethod = valdiGetValueMethod.getAsString(),
                            valdiDebugDescriptionMethod = valdiDebugDescriptionMethod.getAsString(),
                            valdiRetainedChildrenMethod = valdiRetainedChildrenMethod.getAsString()
                        )
                    }
                }
            }.onFailure {
                context.log.verbose("[THEME MAP] Static theme mapper index unavailable for $reason: ${it.message}")
            }

            staticThemeSurfaceIndex = next
            retainedValdiSurfaceAttributeSnapshotSignature = Int.MIN_VALUE
            amoledTrace("theme-map-index", "$reason|hash=${buildInfo.uniqueHash}|loaded=${next.loaded}", maxPerCategory = 180, unique = false) {
                "reason=$reason force=$force uniqueHash=${buildInfo.uniqueHash} snapVersion=${buildInfo.versionName}/${buildInfo.versionCode} " +
                    "loaded=${next.loaded} attrs=${next.normalizedAttributes.size}:${next.normalizedAttributes.take(60)} " +
                    "tokens=${next.normalizedTokens.size}:${next.normalizedTokens.take(60)} " +
                    "owners=${next.ownerSamples.size} candidateClasses=${next.candidateClasses.size} candidateMethods=${next.candidateMethods.size} " +
                    "valdiRoots=${next.valdiRootClasses.size} bridge=${next.valdiBridgeClass ?: "unknown"} " +
                    "set=${next.valdiSetValueMethod ?: "unknown"} get=${next.valdiGetValueMethod ?: "unknown"} " +
                    "debug=${next.valdiDebugDescriptionMethod ?: "unknown"} children=${next.valdiRetainedChildrenMethod ?: "unknown"} " +
                    "ownerSamples=${next.ownerSamples.values.take(18).joinToString(" || ")} " +
                    "candidateClassSamples=${next.candidateClasses.values.take(10).joinToString(" || ")} " +
                    "candidateMethodSamples=${next.candidateMethods.values.take(10).joinToString(" || ")}"
            }
            return next
        }
    }

    private fun builtInThemeSurfaceSeeds(now: Long): List<SnapchatThemeMappedSurface> {
        fun seed(key: String, label: String, description: String, category: String = "Built-in") =
            SnapchatThemeMappedSurface(
                key = key,
                label = label,
                description = description,
                category = category,
                source = "builtin-seed",
                firstSeenMs = now,
                lastSeenMs = now,
                hitCount = 0,
                defaultColor = amoledBlack,
                confidence = 1f,
                details = mapOf("seed_reason" to "baseline_custom_theme_surface")
            )
        return listOf(
            seed("background", "App background", "Root page surfaces and generic background fallbacks"),
            seed("valdi.token.backgroundmain", "Main pages", "Camera, Chat, Stories, Spotlight, and Map base pages"),
            seed("valdi.token.backgroundsubscreen", "Subscreens", "Secondary pages opened from Chat, Profile, Spotlight, and settings"),
            seed("valdi.attr.background", "Composer background attribute", "Generic Valdi/Composer background attribute"),
            seed("valdi.attr.backgroundcolor", "Composer background color", "Explicit Composer backgroundColor values"),
            seed("valdi.token.backgroundsurface", "Sheets and panels", "Bottom sheets, popups, modal panels, and raised containers"),
            seed("valdi.token.backgroundsurfaceup", "Raised sheets", "Elevated cards and overlays above the main surface"),
            seed("valdi.token.backgroundsurfacedown", "Inset sheets", "Lowered panels, pressed sheet states, and inset surfaces"),
            seed("valdi.token.backgroundobject", "Rows and cards", "Chat rows, list cells, profile cards, and repeated objects"),
            seed("android.attr.colorbackground", "Android color background", "Android framework/app theme background attribute", "Android theme attr"),
            seed("android.attr.windowbackground", "Android window background", "Window background attribute from Android themed arrays", "Android theme attr"),
            seed("android.attr.0x7f0404b8", "Legacy Snapchat background attr", "Older Snapchat builds used this resource id for background surfaces", "Android theme attr")
        )
    }

    private fun staticThemeSurfaceSeedsFromMappings(now: Long, forceIndex: Boolean = false): List<SnapchatThemeMappedSurface> {
        val seeds = mutableListOf<SnapchatThemeMappedSurface>()
        val index = mappedThemeSurfaceIndex("static-seeds", force = forceIndex)
        val bridgeDetails = mapOf(
            "valdi_bridge_class" to (index.valdiBridgeClass ?: "unknown"),
            "valdi_set_value_method" to (index.valdiSetValueMethod ?: "unknown"),
            "valdi_get_value_method" to (index.valdiGetValueMethod ?: "unknown"),
            "valdi_debug_description_method" to (index.valdiDebugDescriptionMethod ?: "unknown"),
            "valdi_retained_children_method" to (index.valdiRetainedChildrenMethod ?: "unknown"),
            "static_attribute_count" to index.attributeStrings.size.toString(),
            "static_token_count" to index.tokenStrings.size.toString(),
            "static_owner_count" to index.ownerSamples.size.toString(),
            "candidate_class_count" to index.candidateClasses.size.toString(),
            "candidate_method_count" to index.candidateMethods.size.toString(),
            "valdi_root_class_count" to index.valdiRootClasses.size.toString()
        )

        amoledTrace("theme-map-static-detail", "mapper-counts-${now}", maxPerCategory = 120, unique = false) {
            "bridge=$bridgeDetails " +
                "attributes=${index.attributeStrings.entries.take(40).joinToString { "${it.key}=${it.value}" }} " +
                "tokens=${index.tokenStrings.entries.take(40).joinToString { "${it.key}=${it.value}" }} " +
                "owners=${index.ownerSamples.values.filterNotNull().take(40).joinToString(" || ")} " +
                "candidateClasses=${index.candidateClasses.values.filterNotNull().take(32).joinToString(" || ")} " +
                "candidateMethods=${index.candidateMethods.values.filterNotNull().take(32).joinToString(" || ")} " +
                "valdiRoots=${index.valdiRootClasses.values.filterNotNull().take(32).joinToString(" || ")}"
        }

        index.attributeStrings.forEach { (normalized, rawValue) ->
            val key = "valdi.attr.${normalizeSurfaceKey(normalized)}"
            if (key.isBlank() || key == "valdi.attr.") return@forEach
            seeds += SnapchatThemeMappedSurface(
                key = key,
                label = SnapchatThemeSurfaceMapCodec.friendlyLabelForKey(key),
                description = "Static APK mapper found Valdi/Composer surface attribute '$rawValue'",
                category = "Static Valdi attribute",
                source = "apk-mapper",
                attributeName = rawValue ?: normalized,
                valueSummary = rawValue,
                valueType = "dex.const-string",
                firstSeenMs = now,
                lastSeenMs = now,
                hitCount = 0,
                defaultColor = amoledBlack,
                confidence = 0.84f,
                details = bridgeDetails + mapOf(
                    "normalized_attribute" to normalized,
                    "raw_attribute" to (rawValue ?: normalized),
                    "owner_samples" to index.ownerSamples.values
                        .filterNotNull()
                        .filter { it.startsWith("attribute:$normalized|") }
                        .take(4)
                        .joinToString(" || ")
                )
            )
        }

        index.tokenStrings.forEach { (normalized, rawValue) ->
            val key = "valdi.token.${normalizeSurfaceKey(normalized)}"
            if (key.isBlank() || key == "valdi.token.") return@forEach
            seeds += SnapchatThemeMappedSurface(
                key = key,
                label = SnapchatThemeSurfaceMapCodec.friendlyLabelForKey(key),
                description = "Static APK mapper found Valdi/Composer theme token '$rawValue'",
                category = "Static Valdi token",
                source = "apk-mapper",
                valueSummary = rawValue,
                valueType = "dex.const-string",
                firstSeenMs = now,
                lastSeenMs = now,
                hitCount = 0,
                defaultColor = amoledBlack,
                confidence = 0.88f,
                details = bridgeDetails + mapOf(
                    "normalized_token" to normalized,
                    "raw_token" to (rawValue ?: normalized),
                    "owner_samples" to index.ownerSamples.values
                        .filterNotNull()
                        .filter { it.startsWith("token:$normalized|") }
                        .take(4)
                        .joinToString(" || ")
                )
            )
        }
        amoledTrace("theme-map-static", "seeds=${seeds.size}", maxPerCategory = 100, unique = false) {
            "static mapper seeds=${seeds.size} sample=${seeds.take(12).joinToString { it.key }}"
        }
        return seeds
    }

    private fun seedThemeSurfaceMapLocked(now: Long, staticSeeds: List<SnapchatThemeMappedSurface>) {
        builtInThemeSurfaceSeeds(now).forEach { seed ->
            val key = SnapchatThemeSurfaceMapCodec.normalizeKey(seed.key)
            themeSurfaceMapEntries.putIfAbsent(key, seed.copy(key = key))
        }
        staticSeeds.forEach { seed ->
            val key = SnapchatThemeSurfaceMapCodec.normalizeKey(seed.key)
            themeSurfaceMapEntries.putIfAbsent(key, seed.copy(key = key))
        }
    }

    private fun themeSurfaceMapPriority(surface: SnapchatThemeMappedSurface): Int {
        val source = surface.source.lowercase()
        return (surface.confidence * 10_000).toInt() +
            surface.hitCount.coerceIn(0, 4_000) +
            when {
                source.contains("builtin") -> 1_000_000
                source.contains("apk-mapper") -> 900_000
                !surface.selector.isNullOrBlank() -> 700_000
                !surface.resourceName.isNullOrBlank() -> 500_000
                !surface.attributeName.isNullOrBlank() -> 420_000
                !surface.screenBounds.isNullOrBlank() -> 220_000
                else -> 0
            }
    }

    private fun boundedThemeSurfaceMapSnapshot(
        surfaces: Collection<SnapchatThemeMappedSurface>,
        reason: String
    ): List<SnapchatThemeMappedSurface> {
        if (surfaces.isEmpty()) return emptyList()
        val deduped = LinkedHashMap<String, SnapchatThemeMappedSurface>()
        surfaces.forEach { surface ->
            val key = SnapchatThemeSurfaceMapCodec.normalizeKey(surface.key)
            if (key.isBlank()) return@forEach
            val normalized = surface.copy(
                key = key,
                details = surface.details
                    .toSortedMap()
                    .entries
                    .take(maxThemeSurfaceDetailsPerEntry)
                    .associate { it.key to it.value }
            )
            val existing = deduped[key]
            if (existing == null || themeSurfaceMapPriority(normalized) >= themeSurfaceMapPriority(existing)) {
                deduped[key] = normalized
            }
        }
        if (deduped.size <= maxThemeSurfaceMapEntries) return deduped.values.toList()

        val bounded = deduped.values
            .sortedWith(
                compareByDescending<SnapchatThemeMappedSurface> { themeSurfaceMapPriority(it) }
                    .thenByDescending { it.lastSeenMs }
                    .thenBy { it.key }
            )
            .take(maxThemeSurfaceMapEntries)
            .sortedWith(compareBy<SnapchatThemeMappedSurface> { it.category }.thenBy { it.key })
        val keptKeys = bounded.mapTo(hashSetOf()) { it.key }

        amoledTrace("theme-map-prune", "$reason|${deduped.size}->${bounded.size}", maxPerCategory = 240, unique = false) {
            "reason=$reason pruned surface map entries from ${deduped.size} to ${bounded.size} " +
                "limit=$maxThemeSurfaceMapEntries keptSamples=${bounded.take(24).joinToString { "${it.key}:${it.source}:${it.hitCount}:${it.confidence}" }} " +
                "droppedSamples=${deduped.values.filterNot { keptKeys.contains(it.key) }.take(24).joinToString { "${it.key}:${it.source}:${it.hitCount}:${it.confidence}" }}"
        }
        return bounded
    }

    private fun loadThemeSurfaceMapIfNeeded(reason: String, force: Boolean = false) {
        val buildInfo = currentThemeMapBuildInfo()
        if (!force && themeSurfaceMapLoaded && themeSurfaceMapLoadedHash == buildInfo.uniqueHash) return

        val rawMap = runCatching {
            if (themeSurfaceMapFile.exists()) {
                themeSurfaceMapFile.readBytes().toString(Charsets.UTF_8)
            } else {
                ""
            }
        }.onFailure {
            context.log.verbose("[THEME MAP] Failed to read surface map for $reason: ${it.message}")
        }.getOrDefault("")

        val boundedRawMap = rawMap.takeUnless { it.length > maxThemeSurfaceMapJsonBytes } ?: run {
            context.log.verbose("[THEME MAP] Ignoring oversized surface map for $reason: bytes=${rawMap.length}")
            amoledTrace("theme-map-load", "$reason|oversized|bytes=${rawMap.length}", maxPerCategory = 120, unique = false) {
                "reason=$reason raw map exceeded bounded persistence budget bytes=${rawMap.length} " +
                    "limit=$maxThemeSurfaceMapJsonBytes; mapper will rebuild from static seeds and live observations"
            }
            ""
        }

        val parsed = runCatching {
            boundedRawMap.takeIf { it.isNotBlank() }?.let(SnapchatThemeSurfaceMapCodec::parse)
        }.onFailure {
            context.log.verbose("[THEME MAP] Failed to parse surface map for $reason: ${it.message}")
        }.getOrNull()

        val now = System.currentTimeMillis()
        val staticSeeds = staticThemeSurfaceSeedsFromMappings(now)
        val acceptedSurfaces = boundedThemeSurfaceMapSnapshot(
            parsed
            ?.takeIf { it.uniqueHash == buildInfo.uniqueHash && it.schemaVersion == SnapchatThemeSurfaceMapCodec.SCHEMA_VERSION }
            ?.surfaces
            .orEmpty(),
            reason = "load:$reason"
        )

        synchronized(themeSurfaceMapLock) {
            themeSurfaceMapEntries.clear()
            acceptedSurfaces.forEach { surface ->
                val key = SnapchatThemeSurfaceMapCodec.normalizeKey(surface.key)
                if (key.isNotBlank()) themeSurfaceMapEntries[key] = surface.copy(key = key)
            }
            seedThemeSurfaceMapLocked(now, staticSeeds)
            themeSurfaceMapLoaded = true
            themeSurfaceMapLoadedHash = buildInfo.uniqueHash
            themeSurfaceMapDirty = acceptedSurfaces.isEmpty() ||
                parsed?.uniqueHash != buildInfo.uniqueHash ||
                parsed?.schemaVersion != SnapchatThemeSurfaceMapCodec.SCHEMA_VERSION
        }

        amoledTrace("theme-map-load", "$reason|hash=${buildInfo.uniqueHash}|accepted=${acceptedSurfaces.size}", maxPerCategory = 180, unique = false) {
                "reason=$reason uniqueHash=${buildInfo.uniqueHash} snapVersion=${buildInfo.versionName}/${buildInfo.versionCode} " +
                "rawBytes=${rawMap.length} boundedRawBytes=${boundedRawMap.length} parsedHash=${parsed?.uniqueHash} parsedSchema=${parsed?.schemaVersion} " +
                "accepted=${acceptedSurfaces.size} staticSeeds=${staticSeeds.size} total=${synchronized(themeSurfaceMapLock) { themeSurfaceMapEntries.size }} " +
                "bridgeFile=${InternalFileHandleType.SNAPCHAT_THEME_SURFACES.key}/${InternalFileHandleType.SNAPCHAT_THEME_SURFACES.fileName}"
        }
        if (themeSurfaceMapDirty) scheduleThemeSurfaceMapFlush("load:$reason")
    }

    private fun scheduleThemeSurfaceMapFlush(reason: String) {
        var shouldSchedule = false
        synchronized(themeSurfaceMapLock) {
            if (!themeSurfaceMapFlushScheduled) {
                themeSurfaceMapFlushScheduled = true
                shouldSchedule = true
            }
        }
        amoledTrace("theme-map-schedule", "$reason|scheduled=$shouldSchedule", maxPerCategory = 260) {
            "reason=$reason scheduled=$shouldSchedule loaded=$themeSurfaceMapLoaded dirty=$themeSurfaceMapDirty " +
                "entries=${synchronized(themeSurfaceMapLock) { themeSurfaceMapEntries.size }}"
        }
        if (!shouldSchedule) return
        thread(name = "PurrfectThemeSurfaceMapFlush", isDaemon = true) {
            Thread.sleep(1200L)
            flushThemeSurfaceMap("debounced:$reason")
        }
    }

    private fun flushThemeSurfaceMap(reason: String) {
        val buildInfo = currentThemeMapBuildInfo()
        val snapshot: List<SnapchatThemeMappedSurface>
        var pruned = 0
        synchronized(themeSurfaceMapLock) {
            themeSurfaceMapFlushScheduled = false
            if (!themeSurfaceMapDirty) {
                amoledTrace("theme-map-flush", "$reason|skip-clean", maxPerCategory = 160, unique = false) {
                    "reason=$reason skip clean entries=${themeSurfaceMapEntries.size}"
                }
                return
            }
            val rawSnapshot = themeSurfaceMapEntries.values.toList()
            snapshot = boundedThemeSurfaceMapSnapshot(rawSnapshot, reason = "flush:$reason")
            pruned = rawSnapshot.size - snapshot.size
            if (pruned > 0) {
                themeSurfaceMapEntries.clear()
                snapshot.forEach { surface ->
                    themeSurfaceMapEntries[SnapchatThemeSurfaceMapCodec.normalizeKey(surface.key)] = surface
                }
            }
            themeSurfaceMapDirty = false
        }

        val now = System.currentTimeMillis()
        val json = runCatching {
            SnapchatThemeSurfaceMapCodec.toJson(
                SnapchatThemeSurfaceMap(
                    uniqueHash = buildInfo.uniqueHash,
                    snapchatVersionName = buildInfo.versionName,
                    snapchatVersionCode = buildInfo.versionCode,
                    generatedAtMs = snapshot.minOfOrNull { it.firstSeenMs.takeIf { first -> first > 0L } ?: now } ?: now,
                    updatedAtMs = now,
                    generator = "runtime-hooks",
                    surfaces = snapshot
                )
            )
        }.onFailure {
            synchronized(themeSurfaceMapLock) {
                themeSurfaceMapDirty = true
            }
            context.log.error("[THEME MAP] Failed to encode Snapchat theme surface map", it)
            amoledTrace("theme-map-flush", "$reason|encode-failed|count=${snapshot.size}", maxPerCategory = 240, unique = false) {
                "reason=$reason failed while encoding count=${snapshot.size} pruned=$pruned " +
                    "uniqueHash=${buildInfo.uniqueHash} error=${it.javaClass.name}:${it.message}"
            }
        }.getOrNull() ?: return

        runCatching {
            themeSurfaceMapFile.writeBytes(json.toByteArray(Charsets.UTF_8))
            context.androidContext.sendBroadcast(
                Intent(Constants.SNAPCHAT_THEME_SURFACE_MAP_UPDATED_ACTION)
                    .setPackage(Constants.MODULE_PACKAGE_NAME)
            )
            amoledTrace("theme-map-flush", "$reason|ok|count=${snapshot.size}|bytes=${json.length}", maxPerCategory = 240, unique = false) {
                "reason=$reason wrote count=${snapshot.size} pruned=$pruned bytes=${json.length} uniqueHash=${buildInfo.uniqueHash} " +
                    "snapVersion=${buildInfo.versionName}/${buildInfo.versionCode} " +
                    "sample=${snapshot.take(24).joinToString { "${it.key}:${it.source}:${it.hitCount}" }}"
            }
        }.onFailure {
            synchronized(themeSurfaceMapLock) {
                themeSurfaceMapDirty = true
            }
            context.log.error("[THEME MAP] Failed to flush Snapchat theme surface map", it)
        }
    }

    private fun rebuildThemeSurfaceMap(reason: String) {
        val buildInfo = currentThemeMapBuildInfo()
        val now = System.currentTimeMillis()
        runCatching {
            context.mappings.init(context.androidContext)
            amoledTrace("theme-map-refresh", "$reason|mappings-reloaded", maxPerCategory = 120, unique = false) {
                "reloaded mappings before theme map rebuild reason=$reason mappingVersion=${context.mappings.getGeneratedBuildNumber()} " +
                    "outdated=${context.mappings.isMappingsOutdated()}"
            }
        }.onFailure {
            context.log.verbose("[THEME MAP] Failed to reload mappings before surface map rebuild: ${it.message}")
        }
        val staticSeeds = staticThemeSurfaceSeedsFromMappings(now, forceIndex = true)
        synchronized(themeSurfaceMapLock) {
            themeSurfaceMapEntries.clear()
            seedThemeSurfaceMapLocked(now, staticSeeds)
            themeSurfaceMapLoaded = true
            themeSurfaceMapLoadedHash = buildInfo.uniqueHash
            themeSurfaceMapDirty = true
        }
        amoledTrace("theme-map-refresh", "$reason|hash=${buildInfo.uniqueHash}", maxPerCategory = 120, unique = false) {
            "manual refresh reason=$reason uniqueHash=${buildInfo.uniqueHash} snapVersion=${buildInfo.versionName}/${buildInfo.versionCode} " +
                "staticSeeds=${staticSeeds.size} seedEntries=${synchronized(themeSurfaceMapLock) { themeSurfaceMapEntries.size }}"
        }
        scheduleThemeSurfaceMapFlush("refresh:$reason")
    }

    private fun shouldObserveThemeSurfaceMap(runtime: ThemeRuntime = themeRuntime()): Boolean {
        if (runtime.forceAmoled || runtime.customEnabled) return true
        return runCatching {
            context.config.userInterface.customTheme.captureThemeSurfaces.get()
        }.getOrDefault(false)
    }

    private fun hasGlobalCustomTargets(runtime: ThemeRuntime): Boolean {
        return runtime.customEnabled &&
            (runtime.globalRuleColors.isNotEmpty() || runtime.globalBackgroundPaths.isNotEmpty())
    }

    private fun hasSpecificCustomViewTargets(runtime: ThemeRuntime): Boolean {
        if (!runtime.customEnabled) return false
        if (runtime.surfaceOverrides.isNotEmpty()) return true
        if (runtime.selectorRules.isNotEmpty() || runtime.selectorBackgrounds.isNotEmpty()) return true
        if (runtime.resourceRuleColors.keys.any { it !in globalBackgroundCatalogTargets }) return true
        if (runtime.resourceBackgroundPaths.keys.any { it !in globalBackgroundCatalogTargets }) return true
        return false
    }

    private fun shouldEvaluateCustomThemeForView(view: View, runtime: ThemeRuntime): Boolean {
        if (activePreviewRule() != null) return true
        if (!runtime.customEnabled) return false
        if (hasSpecificCustomViewTargets(runtime)) return true
        if (hasGlobalCustomTargets(runtime)) return globalBackgroundHostDecision(view).matched
        return false
    }

    private fun sanitizedThemeMapDetail(value: String?): String? {
        return value
            ?.replace('\n', ' ')
            ?.replace(Regex("\\s+"), " ")
            ?.take(360)
            ?.takeIf { it.isNotBlank() }
    }

    private fun mergeThemeMapDetails(
        old: Map<String, String>,
        new: Map<String, String>
    ): Map<String, String> {
        return (old + new.mapValues { (_, value) -> sanitizedThemeMapDetail(value) ?: "" })
            .filterValues { it.isNotBlank() }
            .toSortedMap()
    }

    private fun observeThemeSurface(surface: SnapchatThemeMappedSurface, reason: String, runtime: ThemeRuntime = themeRuntime()) {
        val key = SnapchatThemeSurfaceMapCodec.normalizeKey(surface.key)
        if (!shouldObserveThemeSurfaceMap(runtime)) {
            amoledTrace("theme-map-observe-skip", "disabled|$reason|$key", maxPerCategory = 360) {
                "skip disabled reason=$reason rawKey=${surface.key} normalized=$key source=${surface.source} " +
                    "attr=${surface.attributeName ?: "none"} class=${surface.className ?: "none"} " +
                    "forceAmoled=${runtime.forceAmoled} customEnabled=${runtime.customEnabled}"
            }
            return
        }
        if (key.isBlank()) {
            amoledTrace("theme-map-observe-skip", "blank|$reason|${surface.source}", maxPerCategory = 360) {
                "skip blank key reason=$reason rawKey=${surface.key} source=${surface.source} " +
                    "attr=${surface.attributeName ?: "none"} class=${surface.className ?: "none"} value=${surface.valueSummary ?: "none"}"
            }
            return
        }
        loadThemeSurfaceMapIfNeeded("observe:$reason")

        val now = System.currentTimeMillis()
        val cleanSurface = surface.copy(
            key = key,
            label = surface.label.ifBlank { SnapchatThemeSurfaceMapCodec.friendlyLabelForKey(key) },
            description = sanitizedThemeMapDetail(surface.description).orEmpty(),
            valueSummary = sanitizedThemeMapDetail(surface.valueSummary),
            selector = surface.selector
                ?.replace('\n', ' ')
                ?.replace(Regex("\\s+"), " ")
                ?.take(1800)
                ?.takeIf { it.isNotBlank() },
            selectorDisplayName = sanitizedThemeMapDetail(surface.selectorDisplayName),
            role = sanitizedThemeMapDetail(surface.role),
            screenBounds = sanitizedThemeMapDetail(surface.screenBounds),
            localBounds = sanitizedThemeMapDetail(surface.localBounds),
            rootBounds = sanitizedThemeMapDetail(surface.rootBounds),
            parentClassName = sanitizedThemeMapDetail(surface.parentClassName),
            parentResourceName = sanitizedThemeMapDetail(surface.parentResourceName),
            ancestry = sanitizedThemeMapDetail(surface.ancestry),
            details = surface.details
                .toSortedMap()
                .entries
                .asSequence()
                .mapNotNull { (name, value) -> sanitizedThemeMapDetail(value)?.let { name to it } }
                .take(maxThemeSurfaceDetailsPerEntry)
                .toMap()
                .filterValues { it.isNotBlank() }
        )
        val updated: SnapchatThemeMappedSurface
        synchronized(themeSurfaceMapLock) {
            val existing = themeSurfaceMapEntries[key]
            updated = if (existing == null) {
                cleanSurface.copy(
                    firstSeenMs = now,
                    lastSeenMs = now,
                    hitCount = 1
                )
            } else {
                existing.copy(
                    label = cleanSurface.label.ifBlank { existing.label },
                    description = cleanSurface.description.ifBlank { existing.description },
                    category = cleanSurface.category.ifBlank { existing.category },
                    source = cleanSurface.source.ifBlank { existing.source },
                    attributeName = cleanSurface.attributeName ?: existing.attributeName,
                    className = cleanSurface.className ?: existing.className,
                    valueSummary = cleanSurface.valueSummary ?: existing.valueSummary,
                    valueType = cleanSurface.valueType ?: existing.valueType,
                    viewClass = cleanSurface.viewClass ?: existing.viewClass,
                    resourceName = cleanSurface.resourceName ?: existing.resourceName,
                    selector = cleanSurface.selector ?: existing.selector,
                    selectorDisplayName = cleanSurface.selectorDisplayName ?: existing.selectorDisplayName,
                    screenHint = cleanSurface.screenHint ?: existing.screenHint,
                    role = cleanSurface.role ?: existing.role,
                    screenBounds = cleanSurface.screenBounds ?: existing.screenBounds,
                    localBounds = cleanSurface.localBounds ?: existing.localBounds,
                    rootBounds = cleanSurface.rootBounds ?: existing.rootBounds,
                    parentClassName = cleanSurface.parentClassName ?: existing.parentClassName,
                    parentResourceName = cleanSurface.parentResourceName ?: existing.parentResourceName,
                    ancestry = cleanSurface.ancestry ?: existing.ancestry,
                    firstSeenMs = existing.firstSeenMs.takeIf { it > 0L } ?: now,
                    lastSeenMs = now,
                    hitCount = (existing.hitCount + 1).coerceAtLeast(1),
                    defaultColor = cleanSurface.defaultColor ?: existing.defaultColor,
                    confidence = maxOf(existing.confidence, cleanSurface.confidence),
                    details = mergeThemeMapDetails(existing.details, cleanSurface.details)
                )
            }
            themeSurfaceMapEntries[key] = updated
            themeSurfaceMapDirty = true
        }

        amoledTrace("theme-map-observe", "$reason|$key|${surface.source}|${surface.attributeName}|${surface.className}|${surface.resourceName}", maxPerCategory = 3200) {
            "reason=$reason key=$key label=${updated.label} category=${updated.category} source=${updated.source} " +
                "attr=${updated.attributeName ?: "none"} class=${updated.className ?: "none"} " +
                "valueType=${updated.valueType ?: "none"} value=${updated.valueSummary ?: "none"} " +
                "viewClass=${updated.viewClass ?: "none"} res=${updated.resourceName ?: "none"} " +
                "selector=${updated.selector ?: "none"} selectorName=${updated.selectorDisplayName ?: "none"} " +
                "role=${updated.role ?: "none"} bounds=${updated.screenBounds ?: "none"} local=${updated.localBounds ?: "none"} " +
                "screen=${updated.screenHint ?: "none"} hits=${updated.hitCount} " +
                "default=${colorHex(updated.defaultColor)} confidence=${updated.confidence} details=${updated.details}"
        }
        scheduleThemeSurfaceMapFlush("observe:$reason")
    }

    private fun currentActivityName(): String {
        return currentActivityRef?.get()?.javaClass?.name ?: "unknown"
    }

    private fun currentThemeScreenHint(view: View? = null): String {
        val activityName = currentActivityName()
        val viewPart = view?.let { target ->
            val resource = getResourceEntryName(target)
            val parent = (target.parent as? View)?.let { parentView ->
                "${parentView.javaClass.name.substringAfterLast('.')}#${getResourceEntryName(parentView) ?: "none"}"
            } ?: "none"
            "${target.javaClass.name.substringAfterLast('.')}#${resource ?: "none"} parent=$parent"
        }
        return listOfNotNull(
            activityName.substringAfterLast('.').takeIf { it.isNotBlank() },
            viewPart
        ).joinToString(" / ").ifBlank { activityName }
    }

    private fun themeScreenDetails(view: View? = null): Map<String, String> {
        val activity = currentActivityName()
        return buildMap {
            put("activity_class", activity)
            put("activity_simple_name", activity.substringAfterLast('.'))
            if (view != null) {
                put("screen_hint", currentThemeScreenHint(view))
                put("view_ancestry", viewAncestrySummary(view, maxDepth = 5))
                (view.rootView as? View)?.let { root ->
                    put("root_view_class", root.javaClass.name)
                    put("root_view_resource", getResourceEntryName(root) ?: "none")
                    put("root_view_size", "${root.width}x${root.height}")
                }
            } else {
                put("screen_hint", currentThemeScreenHint(null))
            }
        }
    }

    private fun screenBoundsFor(view: View?): String? {
        view ?: return null
        if (view.width <= 0 || view.height <= 0) return null
        val location = IntArray(2)
        return runCatching {
            view.getLocationOnScreen(location)
            "[${location[0]},${location[1]}][${location[0] + view.width},${location[1] + view.height}]"
        }.getOrNull()
    }

    private fun localBoundsFor(view: View?): String? {
        view ?: return null
        if (view.width <= 0 || view.height <= 0) return null
        return "[${view.left},${view.top}][${view.right},${view.bottom}]"
    }

    private fun selectorHashHex(selector: String): String {
        return ((selector.hashCode().toLong()) and 0xFFFFFFFFL).toString(16)
    }

    private fun siblingIndexOf(view: View): Int {
        val parent = view.parent as? android.view.ViewGroup ?: return -1
        for (index in 0 until parent.childCount) {
            if (parent.getChildAt(index) === view) return index
        }
        return -1
    }

    private fun reasonHasThemeTintSignal(reason: String): Boolean {
        return reason.contains("tint=true", ignoreCase = true) ||
            reason.contains("argTint=", ignoreCase = true) ||
            reason.contains("imageTint", ignoreCase = true) ||
            reason.contains("image_tint", ignoreCase = true)
    }

    private fun reasonHasThemeImageSignal(reason: String): Boolean {
        return reason.contains("image=true", ignoreCase = true) ||
            reason.contains("imageView", ignoreCase = true) ||
            reason.contains("imageDrawable", ignoreCase = true)
    }

    private fun themeSurfaceRoleForView(view: View, reason: String): String {
        val name = viewMatchName(view)
        val className = view.javaClass.name.lowercase()
        val surfacePart = themeSurfacePartForView(view, reason)
        return when {
            view is ImageView -> "Icon / outline surface"
            surfacePart == "Button fill" -> "Button fill surface"
            isIconLikeThemeView(view) -> "Icon / outline surface"
            "dialog" in name || "modal" in name -> "Dialog / modal surface"
            "sheet" in name || "bottomsheet" in name -> "Sheet surface"
            "profile" in name -> "Profile surface"
            "map" in name || "snapmap" in name -> "Map surface"
            "chat" in name || "conversation" in name -> "Chat surface"
            "recipient" in name || "select_recipients" in name -> "Recipient picker surface"
            "spotlight" in name -> "Spotlight surface"
            "camera" in name -> "Camera chrome surface"
            "tab" in name || "nav" in name -> "Navigation surface"
            "row" in name || "cell" in name || "item" in name -> "List row / cell surface"
            "card" in name -> "Card surface"
            view is TextView -> "Text surface"
            "button" in name || view.isClickable -> "Button / action surface"
            className.contains("recyclerview") || className.contains("scroll") -> "Scrollable container surface"
            view is android.view.ViewGroup && view.childCount > 0 -> "Container surface"
            drawableMayRepresentSurface(view.background) -> "Drawable-backed surface"
            reasonHasThemeTintSignal(reason) -> "Tinted surface"
            reason.contains("argColor", ignoreCase = true) -> "Color argument surface"
            else -> "Observed surface"
        }
    }

    private fun compactThemeText(value: CharSequence?): String? {
        return value
            ?.toString()
            ?.replace('\n', ' ')
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.take(80)
            ?.takeIf { it.isNotBlank() }
    }

    private fun viewOwnSemanticName(view: View?): String? {
        view ?: return null
        compactThemeText(view.contentDescription)?.let { return it }
        if (view is TextView) {
            compactThemeText(view.text)?.let { return it }
        }
        return null
    }

    private fun childSemanticName(view: View?, maxChildren: Int = 8): String? {
        val group = view as? android.view.ViewGroup ?: return null
        val limit = minOf(group.childCount, maxChildren)
        for (index in 0 until limit) {
            viewOwnSemanticName(group.getChildAt(index))?.let { return it }
        }
        for (index in 0 until limit) {
            childSemanticName(group.getChildAt(index), maxChildren = 4)?.let { return it }
        }
        return null
    }

    private fun isLargeContainerForBorrowedSemantic(view: View): Boolean {
        val root = view.rootView ?: return false
        val rootWidth = root.width.takeIf { it > 0 } ?: return false
        val rootHeight = root.height.takeIf { it > 0 } ?: return false
        return view is android.view.ViewGroup &&
            view.width >= (rootWidth * 0.72f).toInt() &&
            view.height >= (rootHeight * 0.34f).toInt()
    }

    private fun nearbySemanticName(view: View): String? {
        viewOwnSemanticName(view)?.let { return it }
        if (!isLargeContainerForBorrowedSemantic(view)) {
            childSemanticName(view)?.let { return it }
        }
        val parent = view.parent as? android.view.ViewGroup ?: return null
        viewOwnSemanticName(parent)?.let { return it }
        if (!isLargeContainerForBorrowedSemantic(parent)) {
            childSemanticName(parent, maxChildren = 12)?.let { return it }
        }
        return null
    }

    private fun hasOwnOrChildImageSignal(view: View): Boolean {
        if (view is ImageView) return true
        val group = view as? android.view.ViewGroup ?: return false
        val limit = minOf(group.childCount, 10)
        for (index in 0 until limit) {
            val child = group.getChildAt(index)
            if (child is ImageView) return true
            val childGroup = child as? android.view.ViewGroup ?: continue
            val childLimit = minOf(childGroup.childCount, 8)
            for (childIndex in 0 until childLimit) {
                if (childGroup.getChildAt(childIndex) is ImageView) return true
            }
        }
        return false
    }

    private fun hasNearbyImageSignal(view: View): Boolean {
        if (view is ImageView) return true
        if ((view as? android.view.ViewGroup)?.let { group ->
                val limit = minOf(group.childCount, 8)
                (0 until limit).any { index -> group.getChildAt(index) is ImageView }
            } == true) {
            return true
        }
        val parent = view.parent as? android.view.ViewGroup ?: return false
        val limit = minOf(parent.childCount, 12)
        return (0 until limit).any { index ->
            val child = parent.getChildAt(index)
            child is ImageView || (child as? android.view.ViewGroup)?.let { group ->
                val childLimit = minOf(group.childCount, 6)
                (0 until childLimit).any { childIndex -> group.getChildAt(childIndex) is ImageView }
            } == true
        }
    }

    private fun isIconLikeThemeView(view: View): Boolean {
        if (view is ImageView) return true
        val name = viewMatchName(view)
        val parent = view.parent as? View
        val parentName = parent?.let(::viewMatchName).orEmpty()
        val parentResource = getResourceEntryName(parent).orEmpty().lowercase()
        val semantic = viewOwnSemanticName(view).orEmpty().lowercase()
        val drawableClass = view.background?.javaClass?.name.orEmpty().lowercase()
        val small = view.width in 24..190 && view.height in 24..190
        val containerWithOwnIcon = view is android.view.ViewGroup &&
            hasOwnOrChildImageSignal(view) &&
            (view.isClickable || view.isFocusable || view.background != null || "button" in name || "container" in name)
        if (small && containerWithOwnIcon) {
            return false
        }
        return small && (
            "icon" in name ||
                "icon" in parentName ||
                "icon" in parentResource ||
                "image" in name ||
                "image" in parentName ||
                "image" in parentResource ||
                "avatar" in name ||
                "avatar" in parentName ||
                "avatar" in parentResource ||
                ("bitmapdrawable" in drawableClass && ("icon" in parentResource || "icon" in parentName)) ||
                "button" in name && view.background == null ||
                semantic in setOf("map", "chat", "camera", "stories", "spotlight", "search", "notification", "add friends")
            )
    }

    private fun isFloatingActionSurface(view: View): Boolean {
        val root = view.rootView ?: return false
        val rootWidth = root.width.takeIf { it > 0 } ?: return false
        val rootHeight = root.height.takeIf { it > 0 } ?: return false
        if (view.width !in 96..260 || view.height !in 96..260) return false
        val bounds = Rect()
        return runCatching {
            view.getGlobalVisibleRect(bounds)
            bounds.centerX() > (rootWidth * 0.68f).toInt() &&
                bounds.centerY() > (rootHeight * 0.62f).toInt() &&
                hasOwnOrChildImageSignal(view)
        }.getOrDefault(false)
    }

    private fun themeRegionNameForView(view: View): String? {
        if (isFloatingActionSurface(view)) return "Floating action button"
        val root = view.rootView ?: return null
        val rootWidth = root.width.takeIf { it > 0 } ?: return null
        val rootHeight = root.height.takeIf { it > 0 } ?: return null
        val bounds = Rect()
        if (!runCatching { view.getGlobalVisibleRect(bounds) }.getOrDefault(false)) return null
        return when {
            bounds.top <= (rootHeight * 0.12f).toInt() && (view.isClickable || hasOwnOrChildImageSignal(view)) -> "Top action"
            bounds.bottom >= (rootHeight * 0.88f).toInt() && bounds.width() <= (rootWidth * 0.28f).toInt() -> "Navigation item"
            bounds.width() >= (rootWidth * 0.70f).toInt() && bounds.height() in 48..260 -> "List row"
            bounds.height() >= (rootHeight * 0.18f).toInt() && bounds.width() >= (rootWidth * 0.70f).toInt() -> "Screen panel"
            else -> null
        }
    }

    private fun themeSurfacePartForView(view: View, reason: String): String? {
        if (view is ImageView || reasonHasThemeImageSignal(reason) || reasonHasThemeTintSignal(reason)) {
            return "Icon / outline"
        }

        val hasSurfaceDrawable = view.background != null ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && view.foreground != null)
        val parent = view.parent as? View
        val smallChrome = view.width in 32..260 && view.height in 32..260
        val interactiveContext = view.isClickable ||
            view.isFocusable ||
            parent?.isClickable == true ||
            parent?.isFocusable == true ||
            parent?.isLongClickable == true

        if (hasSurfaceDrawable && smallChrome && hasNearbyImageSignal(view) && interactiveContext) {
            return "Button fill"
        }
        if (hasSurfaceDrawable && smallChrome && hasNearbyImageSignal(view)) {
            return "Button fill"
        }
        if (smallChrome && hasOwnOrChildImageSignal(view) && interactiveContext) {
            return "Button fill"
        }
        if (isIconLikeThemeView(view)) {
            return "Icon / outline"
        }
        if (hasSurfaceDrawable) return "Background fill"
        if (view is TextView) return "Text"
        return null
    }

    private fun humanThemeSurfaceLabelForView(
        view: View,
        key: String,
        resourceName: String?,
        selectorDisplayName: String?,
        role: String,
        part: String?
    ): String {
        val semantic = nearbySemanticName(view)
        val resourceLabel = resourceName?.let {
            SnapchatThemeSurfaceMapCodec.friendlyLabelForKey("android.view.$it")
        }
        val selectorLabel = selectorDisplayName
            ?.takeIf { it.isNotBlank() }
            ?.replace("Captured View under #", "")
            ?.replace("Captured ", "")
            ?.trim()
            ?.takeIf { it.isNotBlank() && it != "View" }
        val base = semantic
            ?: resourceLabel
            ?: selectorLabel
            ?: themeRegionNameForView(view)
            ?: role.removeSuffix(" surface").takeIf { it.isNotBlank() }
            ?: SnapchatThemeSurfaceMapCodec.friendlyLabelForKey(key)

        return when {
            !part.isNullOrBlank() && base.contains(part, ignoreCase = true) -> base
            !part.isNullOrBlank() && base.contains("surface", ignoreCase = true) -> "$part ${view.javaClass.simpleName.ifBlank { "View" }}"
            !part.isNullOrBlank() -> "$base $part"
            else -> base
        }.replace(Regex("\\s+"), " ").trim()
    }

    private fun imageTintColorForView(view: View): Int? {
        val image = view as? ImageView ?: return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) image.imageTintList?.defaultColor else null
    }

    private fun textColorForView(view: View): Int? {
        if (view is TextView) return view.currentTextColor
        return reflectiveTextColorForView(view)
    }

    private fun isReflectiveTextColorTarget(view: View): Boolean {
        if (view is TextView) return true
        val signal = textLikeViewSignal(view)
        return listOf(
            "snaplabelview",
            "snapfonttextview",
            "composersnaptextview",
            "snaptextview",
            "labelview",
            "textview"
        ).any { it in signal }
    }

    private fun textLikeViewSignal(view: View): String {
        val parent = view.parent as? View
        return listOf(
            view.javaClass.name,
            view.javaClass.simpleName,
            getResourceEntryName(view).orEmpty(),
            view.contentDescription?.toString().orEmpty(),
            parent?.javaClass?.name.orEmpty(),
            parent?.javaClass?.simpleName.orEmpty(),
            getResourceEntryName(parent).orEmpty(),
            parent?.contentDescription?.toString().orEmpty()
        ).joinToString(" ").lowercase()
    }

    private fun reflectiveTextColorForView(view: View): Int? {
        val methodColor = reflectiveTextColorFromMethods(view)
        if (methodColor != null) return methodColor
        val helperColor = reflectiveTextColorFromHelperFields(view)
        if (helperColor != null) return helperColor
        return reflectiveTextColorFromPaintFields(view)
    }

    private fun reflectiveTextColorFromMethods(view: View): Int? {
        val methodNames = setOf("getCurrentTextColor", "getTextColor", "getColor", "textColor")
        for (clazz in viewClassHierarchy(view.javaClass)) {
            for (method in clazz.declaredMethods) {
                if (method.name !in methodNames || method.parameterTypes.isNotEmpty()) continue
                val value = runCatching {
                    method.isAccessible = true
                    method.invoke(view)
                }.getOrNull()
                when (value) {
                    is Int -> return value
                    is ColorStateList -> return value.defaultColor
                }
            }
        }
        return null
    }

    private fun reflectiveTextColorFromPaintFields(view: View): Int? {
        for (clazz in viewClassHierarchy(view.javaClass)) {
            for (field in clazz.declaredFields) {
                if (java.lang.reflect.Modifier.isStatic(field.modifiers)) continue
                if (!Paint::class.java.isAssignableFrom(field.type)) continue
                val paint = runCatching {
                    field.isAccessible = true
                    field.get(view) as? Paint
                }.getOrNull() ?: continue
                return paint.color
            }
        }
        return null
    }

    private fun applyReflectiveTextColor(view: View, color: Int, attempts: MutableList<String>): Boolean {
        return applyReflectiveTextColorByMethod(view, color, attempts) ||
            applyReflectiveTextColorByHelperField(view, color, attempts) ||
            applyReflectiveTextColorByPaintField(view, color, attempts) ||
            applyReflectiveTextColorByIntField(view, color, attempts)
    }

    private fun applyReflectiveTextColorByMethod(view: View, color: Int, attempts: MutableList<String>): Boolean {
        val preferredNames = listOf(
            "setTextColor",
            "setTextColors",
            "setLabelColor",
            "setTextPaintColor",
            "setForegroundColor",
            "setColor",
            "D"
        )
        val methods = viewClassHierarchy(view.javaClass)
            .asSequence()
            .flatMap { it.declaredMethods.asSequence() }
            .filter { method ->
                method.parameterTypes.size == 1 &&
                    preferredNames.any { method.name.equals(it, ignoreCase = true) }
            }
            .sortedBy { method -> preferredNames.indexOfFirst { method.name.equals(it, ignoreCase = true) }.let { if (it < 0) Int.MAX_VALUE else it } }
            .toList()
        for (method in methods) {
            val parameter = method.parameterTypes.first()
            val arg: Any = when {
                parameter == Int::class.javaPrimitiveType || parameter == Int::class.javaObjectType -> color
                ColorStateList::class.java.isAssignableFrom(parameter) -> ColorStateList.valueOf(color)
                else -> {
                    attempts += "method:${method.declaringClass.name}.${method.name} unsupportedParam=${parameter.name}"
                    continue
                }
            }
            val ok = runCatching {
                method.isAccessible = true
                method.invoke(view, arg)
            }.onFailure {
                attempts += "method:${method.declaringClass.name}.${method.name} failed=${it.javaClass.simpleName}:${it.message}"
            }.isSuccess
            if (ok) {
                attempts += "method:${method.declaringClass.name}.${method.name} ok param=${parameter.name}"
                return true
            }
        }
        return false
    }

    private fun reflectiveTextColorFromHelperFields(view: View): Int? {
        for (clazz in viewClassHierarchy(view.javaClass)) {
            for (field in clazz.declaredFields) {
                if (java.lang.reflect.Modifier.isStatic(field.modifiers) || field.type.isPrimitive) continue
                val helper = runCatching {
                    field.isAccessible = true
                    field.get(view)
                }.getOrNull() ?: continue
                if (!looksLikeReflectiveTextHelper(helper)) continue
                reflectiveHelperTextColor(helper)?.let { return it }
            }
        }
        return null
    }

    private fun reflectiveHelperTextColor(helper: Any): Int? {
        val methodNames = setOf("R", "getCurrentTextColor", "getTextColor", "getColor")
        for (clazz in viewClassHierarchy(helper.javaClass)) {
            for (method in clazz.declaredMethods) {
                if (method.name !in methodNames || method.parameterTypes.isNotEmpty()) continue
                val value = runCatching {
                    method.isAccessible = true
                    method.invoke(helper)
                }.getOrNull()
                when (value) {
                    is Int -> return value
                    is ColorStateList -> return value.defaultColor
                }
            }
        }
        return null
    }

    private fun applyReflectiveTextColorByHelperField(view: View, color: Int, attempts: MutableList<String>): Boolean {
        for (clazz in viewClassHierarchy(view.javaClass)) {
            for (field in clazz.declaredFields) {
                if (java.lang.reflect.Modifier.isStatic(field.modifiers) || field.type.isPrimitive) continue
                val helper = runCatching {
                    field.isAccessible = true
                    field.get(view)
                }.onFailure {
                    attempts += "helperField:${clazz.name}.${field.name} readFailed=${it.javaClass.simpleName}:${it.message}"
                }.getOrNull() ?: continue
                if (!looksLikeReflectiveTextHelper(helper)) {
                    attempts += "helperField:${clazz.name}.${field.name} skippedHelper=${helper.javaClass.name}"
                    continue
                }
                if (applyReflectiveTextColorToHelper(helper, color, attempts, "${clazz.name}.${field.name}")) {
                    return true
                }
            }
        }
        return false
    }

    private fun looksLikeReflectiveTextHelper(helper: Any): Boolean {
        val methods = viewClassHierarchy(helper.javaClass)
            .asSequence()
            .flatMap { it.declaredMethods.asSequence() }
            .toList()
        val hasTextSetter = methods.any { method ->
            method.parameterTypes.size == 1 &&
                CharSequence::class.java.isAssignableFrom(method.parameterTypes[0])
        }
        val hasColorReader = methods.any { it.name == "R" && it.parameterTypes.isEmpty() && it.returnType == Int::class.javaPrimitiveType }
        val hasColorWriter = methods.any { it.name == "c0" && it.parameterTypes.size == 1 && it.parameterTypes[0] == Int::class.javaPrimitiveType }
        return hasTextSetter || (hasColorReader && hasColorWriter)
    }

    private fun applyReflectiveTextColorToHelper(
        helper: Any,
        color: Int,
        attempts: MutableList<String>,
        fieldLabel: String
    ): Boolean {
        val preferredNames = listOf("c0", "setTextColor", "setTextColors", "setColor", "setLabelColor", "setTextPaintColor")
        val methods = viewClassHierarchy(helper.javaClass)
            .asSequence()
            .flatMap { it.declaredMethods.asSequence() }
            .filter { method ->
                method.parameterTypes.size == 1 &&
                    preferredNames.any { method.name.equals(it, ignoreCase = true) }
            }
            .sortedBy { method -> preferredNames.indexOfFirst { method.name.equals(it, ignoreCase = true) }.let { if (it < 0) Int.MAX_VALUE else it } }
            .toList()
        for (method in methods) {
            val parameter = method.parameterTypes.first()
            val arg: Any = when {
                parameter == Int::class.javaPrimitiveType || parameter == Int::class.javaObjectType -> color
                ColorStateList::class.java.isAssignableFrom(parameter) -> ColorStateList.valueOf(color)
                else -> {
                    attempts += "helperMethod:$fieldLabel.${method.name} unsupportedParam=${parameter.name}"
                    continue
                }
            }
            val ok = runCatching {
                method.isAccessible = true
                method.invoke(helper, arg)
            }.onFailure {
                attempts += "helperMethod:$fieldLabel.${method.name} failed=${it.javaClass.simpleName}:${it.message}"
            }.isSuccess
            if (ok) {
                attempts += "helperMethod:$fieldLabel.${method.name} ok param=${parameter.name}"
                return true
            }
        }
        return false
    }

    private fun applyReflectiveTextColorByPaintField(view: View, color: Int, attempts: MutableList<String>): Boolean {
        for (clazz in viewClassHierarchy(view.javaClass)) {
            for (field in clazz.declaredFields) {
                if (java.lang.reflect.Modifier.isStatic(field.modifiers)) continue
                if (!Paint::class.java.isAssignableFrom(field.type)) continue
                val ok = runCatching {
                    field.isAccessible = true
                    val paint = field.get(view) as? Paint ?: return@runCatching false
                    paint.color = color
                    true
                }.onFailure {
                    attempts += "paintField:${clazz.name}.${field.name} failed=${it.javaClass.simpleName}:${it.message}"
                }.getOrDefault(false)
                if (ok) {
                    attempts += "paintField:${clazz.name}.${field.name} ok"
                    return true
                }
            }
        }
        return false
    }

    private fun applyReflectiveTextColorByIntField(view: View, color: Int, attempts: MutableList<String>): Boolean {
        val nameHints = listOf("textcolor", "labelcolor", "foregroundcolor", "fontcolor", "color")
        for (clazz in viewClassHierarchy(view.javaClass)) {
            for (field in clazz.declaredFields) {
                if (java.lang.reflect.Modifier.isStatic(field.modifiers) || java.lang.reflect.Modifier.isFinal(field.modifiers)) continue
                val name = field.name.lowercase()
                if (nameHints.none { it in name }) continue
                val ok = runCatching {
                    field.isAccessible = true
                    when {
                        field.type == Int::class.javaPrimitiveType || field.type == Int::class.javaObjectType -> {
                            field.set(view, color)
                            true
                        }
                        ColorStateList::class.java.isAssignableFrom(field.type) -> {
                            field.set(view, ColorStateList.valueOf(color))
                            true
                        }
                        else -> false
                    }
                }.onFailure {
                    attempts += "colorField:${clazz.name}.${field.name} failed=${it.javaClass.simpleName}:${it.message}"
                }.getOrDefault(false)
                if (ok) {
                    attempts += "colorField:${clazz.name}.${field.name} ok type=${field.type.name}"
                    return true
                }
            }
        }
        return false
    }

    private fun viewClassHierarchy(start: Class<*>): List<Class<*>> {
        val out = mutableListOf<Class<*>>()
        var current: Class<*>? = start
        while (current != null && current != Any::class.java) {
            out += current
            current = current.superclass
        }
        return out
    }

    private fun foregroundColorForView(view: View): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
        return when (val foreground = view.foreground) {
            is ColorDrawable -> foreground.color
            is GradientDrawable -> gradientDrawableSolidColor(foreground)
            is ShapeDrawable -> foreground.paint.color
            else -> null
        }
    }

    private fun backgroundColorForView(view: View): Int? {
        return colorForDrawable(view.background)
    }

    private fun colorForDrawable(drawable: Drawable?): Int? {
        return when (drawable) {
            is ColorDrawable -> drawable.color
            is GradientDrawable -> gradientDrawableSolidColor(drawable)
            is ShapeDrawable -> drawable.paint.color
            is LayerDrawable -> firstThemeColorInLayerDrawable(drawable)
            is StateListDrawable -> firstThemeColorInStateListDrawable(drawable)
            is InsetDrawable -> runCatching { drawable.drawable }.getOrNull()?.let(::colorForDrawable)
            else -> null
        }
    }

    private fun themeDefaultColorForView(view: View): Int? {
        val backgroundTint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            view.backgroundTintList?.defaultColor
        } else {
            null
        }
        return imageTintColorForView(view)
            ?: textColorForView(view)
            ?: backgroundTint
            ?: backgroundColorForView(view)
            ?: foregroundColorForView(view)
    }

    private fun themeValueSummaryForView(view: View): String {
        val values = linkedMapOf<String, String>()
        imageTintColorForView(view)?.let { values["imageTint"] = colorHex(it) }
        textColorForView(view)?.let { values["textColor"] = colorHex(it) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            view.backgroundTintList?.defaultColor?.let { values["backgroundTint"] = colorHex(it) }
        }
        backgroundColorForView(view)?.let { values["backgroundColor"] = colorHex(it) }
        foregroundColorForView(view)?.let { values["foregroundColor"] = colorHex(it) }
        val drawable = drawableSummary(view.background).takeIf { it != "none" }
        val foreground = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) drawableSummary(view.foreground).takeIf { it != "none" } else null
        val imageDrawable = (view as? ImageView)?.drawable?.let(::drawableSummary)?.takeIf { it != "none" }
        values["background"] = drawable ?: "none"
        if (foreground != null) values["foreground"] = foreground
        if (imageDrawable != null) values["imageDrawable"] = imageDrawable
        return values.entries.joinToString(",") { "${it.key}=${it.value}" }.take(360)
    }

    private fun themeValueTypeForView(view: View): String? {
        return when {
            view is ImageView && imageTintColorForView(view) != null -> "android.widget.ImageView.imageTintList"
            view is ImageView -> view.drawable?.javaClass?.name ?: "android.widget.ImageView.drawable"
            view is TextView -> "android.widget.TextView.currentTextColor"
            isReflectiveTextColorTarget(view) -> "reflective.textColor"
            view.background != null -> view.background.javaClass.name
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && view.foreground != null -> view.foreground.javaClass.name
            else -> null
        }
    }

    private fun shouldObserveSemanticThemeSurface(view: View): Boolean {
        if (isIconLikeThemeView(view)) return true
        if (view is TextView && (view.text?.isNotBlank() == true || view.contentDescription?.isNotBlank() == true)) return true
        if (view.isClickable || view.isFocusable || view.isLongClickable) {
            if (nearbySemanticName(view) != null || hasOwnOrChildImageSignal(view) || themeRegionNameForView(view) != null) return true
        }
        if (view is android.view.ViewGroup && hasOwnOrChildImageSignal(view) && view.width in 48..360 && view.height in 48..360) return true
        return false
    }

    private fun shouldObserveAndroidSurfaceCandidate(
        view: View,
        runtime: ThemeRuntime,
        hasPatchableTint: Boolean,
        drawableSurface: Boolean,
        catalogColor: Int?,
        extraCandidate: Boolean = false
    ): Boolean {
        if (!shouldObserveThemeSurfaceMap(runtime)) return false
        if (isExcludedAndroidView(view) && !isIconLikeThemeView(view)) return false
        if (view.width <= 0 || view.height <= 0) return false
        return isNamedAndroidSurface(view) ||
            getResourceEntryName(view) != null ||
            drawableSurface ||
            hasPatchableTint ||
            catalogColor != null ||
            extraCandidate ||
            shouldObserveSemanticThemeSurface(view) ||
            view.background != null ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && view.foreground != null)
    }

    private fun themeObservationGateDiagnostics(
        view: View,
        hasPatchableTint: Boolean,
        drawableSurface: Boolean,
        catalogColor: Int?,
        extraCandidate: Boolean = false
    ): String {
        val parent = view.parent as? View
        return "capture=${shouldObserveThemeSurfaceMap()} excluded=${isExcludedAndroidView(view)} " +
            "iconLike=${isIconLikeThemeView(view)} semantic=${shouldObserveSemanticThemeSurface(view)} " +
            "resource=${getResourceEntryName(view) ?: "none"} parentResource=${getResourceEntryName(parent) ?: "none"} " +
            "ownOrChildImage=${hasOwnOrChildImageSignal(view)} region=${themeRegionNameForView(view) ?: "none"} " +
            "hasBg=${(view.background != null)} bg=${view.background?.javaClass?.name ?: "none"} " +
            "hasFg=${Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && view.foreground != null} " +
            "hasTint=$hasPatchableTint drawableSurface=$drawableSurface catalogColor=${colorHex(catalogColor)} " +
            "extraCandidate=$extraCandidate size=${view.width}x${view.height}"
    }

    private fun viewThemeStateSignature(view: View): Int {
        val root = view.rootView
        var result = view.javaClass.name.hashCode()
        result = 31 * result + view.id
        result = 31 * result + (getResourceEntryName(view)?.hashCode() ?: 0)
        result = 31 * result + view.width
        result = 31 * result + view.height
        result = 31 * result + view.visibility
        result = 31 * result + (view.alpha * 1000f).toInt()
        result = 31 * result + if (view.isClickable) 1 else 0
        result = 31 * result + if (view.isEnabled) 1 else 0
        result = 31 * result + if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT && view.isAttachedToWindow) 1 else 0
        result = 31 * result + if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT && view.isLaidOut) 1 else 0
        result = 31 * result + ((view as? android.view.ViewGroup)?.childCount ?: 0)
        result = 31 * result + (root?.width ?: 0)
        result = 31 * result + (root?.height ?: 0)
        result = 31 * result + (view.background?.javaClass?.name?.hashCode() ?: 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            result = 31 * result + (view.foreground?.javaClass?.name?.hashCode() ?: 0)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            result = 31 * result + (view.backgroundTintList?.defaultColor ?: 0)
        }
        if (view is TextView) {
            result = 31 * result + view.currentTextColor
        }
        return result
    }

    private fun viewThemeDiagnostics(view: View): String {
        val root = view.rootView
        return buildString {
            append("screen=").append(currentThemeScreenHint(view))
            append(" stateSig=").append(viewThemeStateSignature(view))
            append(" root=").append(root?.javaClass?.name ?: "none")
            append("#").append(getResourceEntryName(root) ?: "none")
            append(" rootSize=").append(root?.width ?: 0).append('x').append(root?.height ?: 0)
            append(" attached=").append(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) view.isAttachedToWindow else view.windowToken != null)
            append(" laidOut=").append(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) view.isLaidOut else (view.width > 0 && view.height > 0))
            append(" appliedColor=").append(colorHex(view.getTag(viewAppliedThemeColorTag) as? Int))
            append(" appliedBackground=").append(view.getTag(viewAppliedThemeBackgroundTag) as? String ?: "none")
            append(" summary=").append(viewSummary(view))
            append(" ancestry=").append(viewAncestrySummary(view, maxDepth = 8))
        }.take(2400)
    }

    private fun viewThemeBrief(view: View): String {
        val parent = view.parent as? View
        return buildString {
            append("screen=").append(currentThemeScreenHint(view))
            append(" stateSig=").append(viewThemeStateSignature(view))
            append(" class=").append(view.javaClass.name)
            append(" id=").append(getResourceEntryName(view) ?: "none")
            append(" hash=").append(System.identityHashCode(view))
            append(" size=").append(view.width).append('x').append(view.height)
            append(" visibility=").append(view.visibility)
            append(" alpha=").append(view.alpha)
            append(" clickable=").append(view.isClickable)
            append(" enabled=").append(view.isEnabled)
            append(" bg=").append(drawableSummary(view.background))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                append(" tint=").append(colorHex(view.backgroundTintList?.defaultColor))
            }
            if (parent != null) {
                append(" parent=").append(parent.javaClass.name)
                append("#").append(getResourceEntryName(parent) ?: "none")
            }
            append(" appliedColor=").append(colorHex(view.getTag(viewAppliedThemeColorTag) as? Int))
            append(" appliedBackground=").append(view.getTag(viewAppliedThemeBackgroundTag) as? String ?: "none")
        }.take(1200)
    }

    private fun viewThemeLogDetails(view: View, full: Boolean): String {
        return if (full) viewThemeDiagnostics(view) else viewThemeBrief(view)
    }

    private fun themeMapCategoryForValdiKey(key: String): String {
        return when {
            key.startsWith("valdi.token.") -> "Valdi token"
            key.startsWith("valdi.attr.") -> "Valdi attribute"
            key.startsWith("valdi.") -> "Valdi class surface"
            else -> "Valdi alias"
        }
    }

    private fun themeMapDescriptionForValdiKey(
        key: String,
        source: String,
        className: String?,
        attributeName: String,
        value: Any?,
        patched: Any?,
        surfaceCandidate: Boolean,
        excluded: Boolean
    ): String {
        return "source=$source key=$key class=${className ?: "unknown"} attr=$attributeName " +
            "value=${valueSummary(value)} patched=${valueSummary(patched)} " +
            "surfaceCandidate=$surfaceCandidate excluded=$excluded"
    }

    private fun observeValdiThemeSurface(
        source: String,
        handle: Long,
        className: String?,
        attributeName: String,
        value: Any?,
        patched: Any?,
        surfaceCandidate: Boolean,
        excluded: Boolean,
        runtime: ThemeRuntime
    ) {
        if (!surfaceCandidate || excluded || value == null) return
        val keys = surfaceKeysFor(attributeName, value, className)
        if (keys.isEmpty()) return
        val defaultColor = colorIntFromValue(value) ?: colorIntFromValue(patched) ?: amoledBlack
        val allKeys = keys.joinToString(",")
        keys.forEach { key ->
            observeThemeSurface(
                SnapchatThemeMappedSurface(
                    key = key,
                    label = SnapchatThemeSurfaceMapCodec.friendlyLabelForKey(key),
                    description = themeMapDescriptionForValdiKey(
                        key = key,
                        source = source,
                        className = className,
                        attributeName = attributeName,
                        value = value,
                        patched = patched,
                        surfaceCandidate = surfaceCandidate,
                        excluded = excluded
                    ),
                    category = themeMapCategoryForValdiKey(key),
                    source = source,
                    attributeName = attributeName,
                    className = className,
                    screenHint = currentThemeScreenHint(null),
                    valueSummary = valueSummary(value),
                    valueType = value.javaClass.name,
                    defaultColor = defaultColor,
                    confidence = when {
                        patched != null -> 0.98f
                        key.startsWith("valdi.token.") -> 0.9f
                        key.startsWith("valdi.attr.") -> 0.86f
                        else -> 0.72f
                    },
                    details = themeScreenDetails(null) + mapOf(
                        "handle" to handle.toString(),
                        "all_surface_keys" to allKeys,
                        "patched_value" to valueSummary(patched),
                        "surface_candidate" to surfaceCandidate.toString(),
                        "excluded" to excluded.toString(),
                        "runtime_force_amoled" to runtime.forceAmoled.toString(),
                        "runtime_custom_enabled" to runtime.customEnabled.toString(),
                        "runtime_surface_override_count" to runtime.surfaceOverrides.size.toString(),
                        "runtime_rule_count" to runtime.rules.size.toString(),
                        "runtime_background_count" to runtime.backgrounds.size.toString()
                    )
                ),
                reason = "valdi:$source:$attributeName",
                runtime = runtime
            )
        }
    }

    private fun observeAndroidAttrThemeSurface(
        source: String,
        attrId: Int,
        attrName: String?,
        originalColor: Int,
        customColor: Int?,
        shouldPatch: Boolean,
        runtime: ThemeRuntime
    ) {
        val normalizedName = normalizeSurfaceKey(attrName)
        val keys = linkedSetOf<String>()
        if (normalizedName.isNotEmpty()) {
            keys += "android.attr.$normalizedName"
            keys += normalizedName
        }
        keys += "android.attr.0x${attrId.toString(16)}"
        keys.forEach { key ->
            observeThemeSurface(
                SnapchatThemeMappedSurface(
                    key = key,
                    label = SnapchatThemeSurfaceMapCodec.friendlyLabelForKey(key),
                    description = "source=$source attrId=0x${attrId.toString(16)} attrName=${attrName ?: "unknown"} " +
                        "original=${colorHex(originalColor)} custom=${colorHex(customColor)} patch=$shouldPatch",
                    category = "Android theme attr",
                    source = source,
                    attributeName = attrName ?: "0x${attrId.toString(16)}",
                    screenHint = currentThemeScreenHint(null),
                    valueSummary = colorHex(originalColor),
                    valueType = "android.content.res.TypedArray.color",
                    defaultColor = originalColor,
                    confidence = if (shouldPatch || customColor != null) 0.96f else 0.78f,
                    details = themeScreenDetails(null) + mapOf(
                        "attr_id_hex" to "0x${attrId.toString(16)}",
                        "attr_name" to (attrName ?: "unknown"),
                        "original_color" to colorHex(originalColor),
                        "custom_color" to colorHex(customColor),
                        "should_patch" to shouldPatch.toString(),
                        "runtime_force_amoled" to runtime.forceAmoled.toString(),
                        "runtime_custom_enabled" to runtime.customEnabled.toString()
                    )
                ),
                reason = "android-attr:$source:${attrName ?: attrId.toString(16)}",
                runtime = runtime
            )
        }
    }

    private fun observeAndroidViewThemeSurface(
        source: String,
        view: View,
        reason: String,
        runtime: ThemeRuntime = themeRuntime()
    ) {
        val resourceName = getResourceEntryName(view)
        val normalizedResource = normalizeSurfaceKey(resourceName)
        val root = view.rootView ?: view
        val selector = runCatching { WhatsAppUiElementSelector.build(view, root) }
            .getOrNull()
            .orEmpty()
            .takeIf { it.startsWith("selector:v1|") }
        val selectorDisplayName = selector?.let {
            runCatching { WhatsAppUiElementSelector.toDisplayName(it) }.getOrNull()
        }
        val role = themeSurfaceRoleForView(view, reason)
        val surfacePart = themeSurfacePartForView(view, reason)
        val classSimple = normalizeSurfaceKey(view.javaClass.simpleName.ifBlank { view.javaClass.name.substringAfterLast('.') })
        val parent = view.parent as? View
        val screenBounds = screenBoundsFor(view)
        val localBounds = localBoundsFor(view)
        val rootBounds = screenBoundsFor(root)
        val parentResource = getResourceEntryName(parent)
        val stableSurfaceSignal = listOf(
            classSimple.ifBlank { "view" },
            normalizeSurfaceKey(role).ifBlank { "surface" },
            normalizeSurfaceKey(surfacePart).ifBlank { "part" },
            normalizeSurfaceKey(parentResource).ifBlank { "no_parent_resource" },
            normalizeSurfaceKey(parent?.javaClass?.simpleName).ifBlank { "no_parent_class" },
            screenBounds ?: localBounds ?: "${view.width}x${view.height}",
            currentThemeScreenHint(view)
        ).joinToString("|")
        val key = when {
            normalizedResource.isNotBlank() -> "android.view.$normalizedResource"
            !selector.isNullOrBlank() -> "android.selector.${classSimple.ifBlank { "view" }}.${selectorHashHex(selector)}"
            else -> "android.view.${classSimple.ifBlank { "view" }}.${normalizeSurfaceKey(role).ifBlank { "surface" }}.${selectorHashHex(stableSurfaceSignal)}"
        }
        observeThemeSurface(
            SnapchatThemeMappedSurface(
                key = key,
                label = humanThemeSurfaceLabelForView(view, key, resourceName, selectorDisplayName, role, surfacePart),
                description = "source=$source reason=$reason resource=$resourceName ${viewSummary(view)}",
                category = if (resourceName == null && selector != null) {
                    "Android selector surface"
                } else {
                    "Android view surface"
                },
                source = source,
                viewClass = view.javaClass.name,
                resourceName = resourceName,
                selector = selector,
                selectorDisplayName = selectorDisplayName,
                screenHint = currentThemeScreenHint(view),
                role = role,
                screenBounds = screenBounds,
                localBounds = localBounds,
                rootBounds = rootBounds,
                parentClassName = parent?.javaClass?.name,
                parentResourceName = parentResource,
                ancestry = viewAncestrySummary(view, maxDepth = 10),
                valueSummary = themeValueSummaryForView(view),
                valueType = themeValueTypeForView(view),
                defaultColor = themeDefaultColorForView(view) ?: amoledBlack,
                confidence = when {
                    isNamedAndroidSurface(view) -> 0.9f
                    isIconLikeThemeView(view) -> 0.88f
                    view is TextView -> 0.86f
                    shouldObserveSemanticThemeSurface(view) -> 0.84f
                    drawableMayRepresentSurface(view.background) -> 0.82f
                    else -> 0.66f
                },
                details = themeScreenDetails(view) + mapOf(
                    "reason" to reason,
                    "resource_name" to (resourceName ?: "none"),
                    "selector" to (selector ?: "none"),
                    "selector_display" to (selectorDisplayName ?: "none"),
                    "role" to role,
                    "surface_part" to (surfacePart ?: "none"),
                    "semantic_name" to (nearbySemanticName(view) ?: "none"),
                    "own_semantic_name" to (viewOwnSemanticName(view) ?: "none"),
                    "child_semantic_name" to (childSemanticName(view) ?: "none"),
                    "region_name" to (themeRegionNameForView(view) ?: "none"),
                    "icon_like" to isIconLikeThemeView(view).toString(),
                    "semantic_surface" to shouldObserveSemanticThemeSurface(view).toString(),
                    "view_class" to view.javaClass.name,
                    "view_hash" to System.identityHashCode(view).toString(),
                    "view_size" to "${view.width}x${view.height}",
                    "view_position" to "${view.left},${view.top}-${view.right},${view.bottom}",
                    "view_screen_bounds" to (screenBounds ?: "none"),
                    "view_local_bounds" to (localBounds ?: "none"),
                    "view_alpha" to view.alpha.toString(),
                    "view_visibility" to view.visibility.toString(),
                    "view_clickable" to view.isClickable.toString(),
                    "view_enabled" to view.isEnabled.toString(),
                    "parent_class" to (parent?.javaClass?.name ?: "none"),
                    "parent_resource" to (parentResource ?: "none"),
                    "sibling_index" to siblingIndexOf(view).toString(),
                    "child_count" to ((view as? android.view.ViewGroup)?.childCount ?: 0).toString(),
                    "background" to drawableSummary(view.background),
                    "foreground" to if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) drawableSummary(view.foreground) else "sdk_lt_m",
                    "image_drawable" to ((view as? ImageView)?.drawable?.let(::drawableSummary) ?: "none"),
                    "image_tint" to colorHex(imageTintColorForView(view)),
                    "text_color" to colorHex(textColorForView(view)),
                    "default_theme_color" to colorHex(themeDefaultColorForView(view)),
                    "background_tint" to if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) colorHex(view.backgroundTintList?.defaultColor) else "sdk_lt_l",
                    "runtime_force_amoled" to runtime.forceAmoled.toString(),
                    "runtime_custom_enabled" to runtime.customEnabled.toString()
                )
            ),
            reason = "android-view:$source:$normalizedResource",
            runtime = runtime
        )
    }

    private fun drawableSummary(drawable: Drawable?, depth: Int = 0): String {
        if (drawable == null) return "none"
        return buildString {
            append(drawable.javaClass.name)
            when (drawable) {
                is ColorDrawable -> append(" color=").append(colorHex(drawable.color))
                is GradientDrawable -> {
                    append(" solid=").append(colorHex(gradientDrawableSolidColor(drawable)))
                    append(" colors=").append(gradientDrawableColors(drawable)?.joinToString(prefix = "[", postfix = "]") { colorHex(it) } ?: "none")
                }
                is ShapeDrawable -> append(" paint=").append(colorHex(drawable.paint.color))
                is LayerDrawable -> {
                    append(" layers=").append(drawable.numberOfLayers)
                    appendLayerDrawableSummary(drawable, depth)
                }
                is RippleDrawable -> {
                    append(" rippleLayers=").append(drawable.numberOfLayers)
                    appendLayerDrawableSummary(drawable, depth)
                }
                is InsetDrawable -> append(" inner=")
                    .append(
                        runCatching { drawable.drawable }
                            .getOrNull()
                            ?.let { if (depth >= 2) it.javaClass.name else drawableSummary(it, depth + 1) }
                            ?: "unknown"
                    )
            }
        }
    }

    private fun StringBuilder.appendLayerDrawableSummary(drawable: LayerDrawable, depth: Int) {
        if (depth >= 2) return
        val maxLayers = drawable.numberOfLayers.coerceAtMost(4)
        append(" children=[")
        for (index in 0 until maxLayers) {
            if (index > 0) append(", ")
            val child = runCatching { drawable.getDrawable(index) }.getOrNull()
            append(index).append(':').append(drawableSummary(child, depth + 1))
        }
        if (drawable.numberOfLayers > maxLayers) append(", +").append(drawable.numberOfLayers - maxLayers)
        append(']')
    }

    private fun gradientDrawableSolidColor(drawable: GradientDrawable): Int? {
        val state = runCatching { drawable.getObjectField("mGradientState") }.getOrNull()
        val solidColors = runCatching { state?.getObjectField("mSolidColors") as? ColorStateList }.getOrNull()
        return solidColors?.defaultColor
            ?: runCatching { state?.getObjectField("mSolidColor") as? Int }.getOrNull()
    }

    private fun gradientDrawableColors(drawable: GradientDrawable): IntArray? {
        val state = runCatching { drawable.getObjectField("mGradientState") }.getOrNull()
        return runCatching { state?.getObjectField("mGradientColors") as? IntArray }.getOrNull()
            ?: runCatching { state?.getObjectField("mColors") as? IntArray }.getOrNull()
    }

    private fun colorLikeFieldName(name: String): Boolean {
        val lower = name.lowercase()
        return listOf("color", "colour", "paint", "tint", "background", "fill").any { it in lower }
    }

    private fun drawableReflectionFields(drawable: Drawable): List<java.lang.reflect.Field> {
        synchronized(drawableReflectionFieldCache) {
            drawableReflectionFieldCache[drawable.javaClass]?.let { return it }
        }
        val fields = mutableListOf<java.lang.reflect.Field>()
        var current: Class<*>? = drawable.javaClass
        while (current != null && current != Drawable::class.java && current != Any::class.java && current != Object::class.java) {
            current.declaredFields
                .filterNot { Modifier.isStatic(it.modifiers) }
                .forEach { field ->
                    runCatching { field.isAccessible = true }
                    fields += field
                }
            current = current.superclass
        }
        synchronized(drawableReflectionFieldCache) {
            drawableReflectionFieldCache[drawable.javaClass] = fields
        }
        return fields
    }

    private fun reflectionFieldValueSummary(value: Any?): String {
        if (value == null) return "null"
        return when (value) {
            is Int -> "Int($value color=${colorHex(value)})"
            is Long -> "Long($value)"
            is Float -> "Float($value)"
            is Double -> "Double($value)"
            is Boolean -> "Boolean($value)"
            is String -> "String(${value.take(160)})"
            is ColorStateList -> "ColorStateList(default=${colorHex(value.defaultColor)})"
            is Paint -> "Paint(color=${colorHex(value.color)} style=${value.style} alpha=${value.alpha})"
            is Drawable -> "Drawable(${drawableSummary(value)})"
            is IntArray -> "IntArray(size=${value.size}, values=${value.take(8).joinToString { colorHex(it) }})"
            is Array<*> -> "Array(size=${value.size}, values=${value.take(6).joinToString { it?.javaClass?.name ?: "null" }})"
            else -> "${value.javaClass.name}(${value.toString().replace('\n', ' ').take(180)})"
        }
    }

    private fun drawableReflectionSummary(drawable: Drawable, maxFields: Int = 36): String {
        val fields = drawableReflectionFields(drawable)
        return fields.take(maxFields).joinToString(separator = "; ") { field ->
            val value = runCatching {
                field.get(drawable)
            }.getOrNull()
            "${field.declaringClass.name.substringAfterLast('.')}.${field.name}:${field.type.name.substringAfterLast('.')}=" +
                reflectionFieldValueSummary(value)
        }.take(1400)
    }

    private fun logUnknownDrawable(source: String, drawable: Drawable, changed: Boolean) {
        if (drawable.javaClass.name.startsWith("android.")) return
        val key = if (changed) {
            "$source|${drawable.javaClass.name}|changed=true|${System.identityHashCode(drawable)}"
        } else {
            "$source|${drawable.javaClass.name}|changed=false"
        }
        amoledTrace("drawable-reflect", key, maxPerCategory = 900) {
            "source=$source changed=$changed drawable=${drawableSummary(drawable)} fields=${drawableReflectionSummary(drawable)}"
        }
    }

    private fun viewTraceKey(view: View?): String {
        if (view == null) return "null"
        return buildString {
            append(System.identityHashCode(view))
            append('|').append(view.javaClass.name)
            getResourceEntryName(view)?.let { append('|').append(it) }
        }
    }

    private fun viewClassTraceKey(view: View?): String {
        if (view == null) return "null"
        return buildString {
            append(view.javaClass.name)
            append('|').append(getResourceEntryName(view) ?: "none")
            append('|').append(view.visibility)
            append('|').append(view.width).append('x').append(view.height)
        }
    }

    private fun isLowValueNoOpReason(reason: String): Boolean {
        return reason.startsWith("skip_prelayout_not_surface") ||
            reason.startsWith("skip_too_small") ||
            reason.startsWith("skip_small_interactive_control") ||
            reason.startsWith("skip_inside_image_preview") ||
            reason.startsWith("skip_excluded_content_or_media_ancestor") ||
            reason.startsWith("skip_retained_valdi") ||
            reason.startsWith("initial_tree_no_changes") ||
            reason.startsWith("needsLayoutPatch=false") ||
            reason.startsWith("parent_no_change") ||
            reason.startsWith("eligible_no_patchable_background")
    }

    private fun isLowValuePatchOutcome(outcome: String): Boolean {
        return outcome.startsWith("skip_prelayout_not_surface") ||
            outcome.startsWith("skip_too_small") ||
            outcome.startsWith("skip_small_interactive_control") ||
            outcome.startsWith("skip_inside_image_preview") ||
            outcome.startsWith("skip_excluded_content_or_media_ancestor") ||
            outcome == "eligible_no_patchable_background" ||
            outcome == "amoled_disabled_no_custom_match" ||
            outcome == "skip_internal_theme_mutation"
    }

    private fun viewSummary(view: View?): String {
        if (view == null) return "null"
        val parent = view.parent as? View
        return buildString {
            append("class=").append(view.javaClass.name)
            append(" id=").append(getResourceEntryName(view) ?: "none")
            append(" hash=").append(System.identityHashCode(view))
            append(" size=").append(view.width).append('x').append(view.height)
            append(" pos=").append(view.left).append(',').append(view.top).append('-')
                .append(view.right).append(',').append(view.bottom)
            append(" alpha=").append(view.alpha)
            append(" visibility=").append(view.visibility)
            append(" clickable=").append(view.isClickable)
            append(" enabled=").append(view.isEnabled)
            append(" name=").append(viewMatchName(view).take(220))
            append(" bg=").append(drawableSummary(view.background))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                append(" fg=").append(drawableSummary(view.foreground))
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                append(" tint=").append(colorHex(view.backgroundTintList?.defaultColor))
            }
            if (parent != null) {
                append(" parent=").append(parent.javaClass.name)
                append(" parentId=").append(getResourceEntryName(parent) ?: "none")
            }
        }.take(1300)
    }

    private fun viewAncestrySummary(view: View?, maxDepth: Int = 6): String {
        if (view == null) return "none"
        val parts = mutableListOf<String>()
        var current: View? = view
        var depth = 0
        while (current != null && depth < maxDepth) {
            parts += "${depth}:${current.javaClass.name}#${getResourceEntryName(current) ?: "none"}@${System.identityHashCode(current)}" +
                "[${current.width}x${current.height},v=${current.visibility}]"
            current = current.parent as? View
            depth++
        }
        if (current != null) parts += "..."
        return parts.joinToString(" <- ").take(1200)
    }

    private fun mappedOwnerSamplesFor(kind: String, normalized: String, maxSamples: Int = 5): String {
        if (normalized.isBlank()) return "none"
        val prefix = "$kind:$normalized|"
        val samples = staticThemeSurfaceIndex.ownerSamples.values
            .asSequence()
            .filterNotNull()
            .filter { it.startsWith(prefix) }
            .take(maxSamples)
            .toList()
        return samples.takeIf { it.isNotEmpty() }
            ?.joinToString(" || ")
            ?.take(1000)
            ?: "none"
    }

    private fun valdiNodeDebugSummary(node: ValdiViewNode): String {
        return runCatching { node.toString() }.getOrNull()
            .orEmpty()
            .replace('\n', ' ')
            .replace(Regex("\\s+"), " ")
            .take(800)
    }

    private fun valdiAttributeSnapshot(node: ValdiViewNode, requestedAttribute: String? = null): String {
        val names = linkedSetOf<String>()
        if (!requestedAttribute.isNullOrBlank()) names += requestedAttribute
        names += valdiDiagnosticAttributes
        return names.mapNotNull { attributeName ->
            val value = runCatching { node.getAttribute(attributeName) }.getOrNull() ?: return@mapNotNull null
            "$attributeName=${valueSummary(value)}"
        }.joinToString(separator = "; ").take(1000)
    }

    private fun logValdiNodeSnapshot(source: String, node: ValdiViewNode, className: String?, depth: Int) {
        val debugDescription = valdiNodeDebugSummary(node)
        val interesting = className.orEmpty().isNotBlank() ||
            debugDescription.isNotBlank() ||
            namedAndroidSurfaceTokens.any { it in debugDescription.lowercase() }
        if (!interesting) return
        val key = "${source}|${className.orEmpty()}|${debugDescription.take(180)}|$depth"
        amoledTrace("valdi-node", key, maxPerCategory = 1400) {
            "source=$source depth=$depth class=${className ?: "unknown"} attrs=${valdiAttributeSnapshot(node)} debug=$debugDescription"
        }
    }

    private fun logValdiAttributeDecision(
        source: String,
        handle: Long,
        className: String?,
        attributeName: String,
        value: Any?,
        patched: Any?,
        surfaceCandidate: Boolean,
        excluded: Boolean,
        runtime: ThemeRuntime
    ) {
        val key = buildString {
            append(source).append('|')
            append(className.orEmpty()).append('|')
            append(attributeName).append('|')
            append(value?.javaClass?.name.orEmpty()).append('|')
            append(value?.toString()?.take(160).orEmpty()).append('|')
            append(patched?.toString()?.take(80).orEmpty()).append('|')
            append(surfaceCandidate).append('|').append(excluded)
        }
        val normalizedAttr = normalizeSurfaceKey(attributeName)
        val normalizedToken = (value as? String)?.let(::normalizeSurfaceKey).orEmpty()
        val surfaceKeys = surfaceKeysFor(attributeName, value, className)
        val mappedAttr = isMappedValdiSurfaceAttributeName(attributeName)
        val mappedToken = (value as? String)?.let(::isMappedValdiSurfaceToken) ?: false
        amoledTrace("valdi-attribute", key, maxPerCategory = 2200) {
            "source=$source handle=$handle class=${className ?: "unknown"} attr=$attributeName " +
                "normalizedAttr=$normalizedAttr normalizedToken=${normalizedToken.ifBlank { "none" }} keys=${surfaceKeys.joinToString()} " +
                "value=${valueSummary(value)} valueColor=${colorHex(colorIntFromValue(value))} surfaceCandidate=$surfaceCandidate excluded=$excluded " +
                "patched=${valueSummary(patched)} forceAmoled=${runtime.forceAmoled} customEnabled=${runtime.customEnabled} " +
                "mappedAttr=$mappedAttr mappedToken=$mappedToken attrOwners=${mappedOwnerSamplesFor("attribute", normalizedAttr)} " +
                "tokenOwners=${mappedOwnerSamplesFor("token", normalizedToken)}"
        }
    }

    private fun logAndroidSurfaceDecision(
        source: String,
        view: View,
        reason: String,
        runtime: ThemeRuntime = themeRuntime(),
        unique: Boolean = true
    ) {
        val key = if (isLowValueNoOpReason(reason)) {
            "$source|$reason|${viewClassTraceKey(view)}"
        } else {
            "$source|$reason|${viewTraceKey(view)}"
        }
        val maxLogs = if (isLowValueNoOpReason(reason)) 520 else 1600
        amoledTrace("android-surface", key, maxPerCategory = maxLogs, unique = unique) {
            "source=$source reason=$reason forceAmoled=${runtime.forceAmoled} customEnabled=${runtime.customEnabled} " +
                "details=${viewThemeLogDetails(view, full = true)}"
        }
    }

    private fun logAndroidTreePass(
        source: String,
        host: View,
        maxItems: Int,
        maxRuntimeMs: Long,
        visited: Int,
        changed: Int,
        elapsedMs: Long,
        extra: String = ""
    ) {
        val interesting = changed > 0 || elapsedMs > maxRuntimeMs || source == "retained-valdi" || source == "surface-layout"
        val key = if (interesting) {
            "$source|${viewTraceKey(host)}|$visited|$changed|$extra"
        } else {
            "$source|noop|${viewClassTraceKey(host)}|visited=$visited|$extra"
        }
        val maxLogs = if (interesting) 900 else 360
        amoledTrace("android-tree", key, maxPerCategory = maxLogs) {
            "source=$source host=${viewSummary(host)} max=$maxItems budget=${maxRuntimeMs}ms " +
                "visited=$visited changed=$changed elapsed=${elapsedMs}ms " +
                "itemLimitHit=${visited >= maxItems && visited >= 0} timeLimitHit=${elapsedMs > maxRuntimeMs} " +
                "frameRisk=${elapsedMs >= 8L} screen=${currentThemeScreenHint(host)} " +
                "hostAncestry=${viewAncestrySummary(host)} hostState=${viewThemeStateSignature(host)} $extra"
        }
    }

    private fun logNativeBridgeDiagnostics() {
        runCatching {
            val bridge = context.classCache.nativeBridge
            val methods = bridge.declaredMethods
                .filter {
                    it.name.contains("Attribute", ignoreCase = true) ||
                        it.name.contains("ViewNode", ignoreCase = true) ||
                        it.name.contains("Retained", ignoreCase = true) ||
                        it.name.contains("Children", ignoreCase = true)
                }
                .sortedWith(compareBy({ it.name }, { it.parameterTypes.size }))
                .joinToString(separator = " | ") { method ->
                    val params = method.parameterTypes.joinToString(",") { it.name.substringAfterLast('.') }
                    "${method.name}($params):${method.returnType.name.substringAfterLast('.')}"
                }
            amoledTrace("bridge", bridge.name, maxPerCategory = 20) {
                "nativeBridge=${bridge.name} valdiView=${context.classCache.valdiView?.name ?: "none"} methods=$methods"
            }
        }.onFailure {
            context.log.verbose("[AMOLED TRACE][bridge] Failed to inspect native bridge: ${it.message}")
        }
    }

    private fun isPatchableSurfaceColor(color: Int): Boolean {
        if (color == amoledBlack) return false
        if (Color.alpha(color) != 0xFF) return false
        val avg = (Color.red(color) + Color.green(color) + Color.blue(color)) / 3
        return avg <= maxSurfaceAverage
    }

    private fun isPatchableSurfaceOrOverlayColor(color: Int): Boolean {
        if ((color and 0x00FFFFFF) == 0) return false
        val alpha = Color.alpha(color)
        if (alpha <= 0) return false
        if (alpha == 0xFF) return isPatchableSurfaceColor(color)
        val avg = (Color.red(color) + Color.green(color) + Color.blue(color)) / 3
        val effectiveAvg = (avg * alpha) / 255
        return effectiveAvg <= 0x70
    }

    private fun isPatchableCanvasSurfaceColor(color: Int): Boolean {
        if ((color and 0x00FFFFFF) == 0) return false
        if (Color.alpha(color) < 0x80) return false
        val avg = (Color.red(color) + Color.green(color) + Color.blue(color)) / 3
        return avg <= 0x60
    }

    private fun canvasAmoledColor(color: Int): Int {
        return Color.argb(Color.alpha(color), 0, 0, 0)
    }

    private fun amoledSurfaceColorFor(color: Int): Int {
        return if (Color.alpha(color) == 0xFF) amoledBlack else canvasAmoledColor(color)
    }

    private fun hasVisibleDrawableColor(color: Int): Boolean {
        return Color.alpha(color) > 0
    }

    private fun hasVisibleDrawablePaint(drawable: Drawable?): Boolean {
        if (drawable == null) return false
        return when (drawable) {
            is ColorDrawable -> hasVisibleDrawableColor(drawable.color)
            is GradientDrawable -> {
                gradientDrawableSolidColor(drawable)?.let(::hasVisibleDrawableColor)
                    ?: gradientDrawableColors(drawable)?.any(::hasVisibleDrawableColor)
                    ?: false
            }
            is ShapeDrawable -> hasVisibleDrawableColor(drawable.paint.color)
            is InsetDrawable -> {
                val inner = runCatching { drawable.drawable }.getOrNull()
                    ?: runCatching { drawable.getObjectField("mDrawable") as? Drawable }.getOrNull()
                hasVisibleDrawablePaint(inner)
            }
            is LayerDrawable -> {
                var visible = false
                for (index in 0 until drawable.numberOfLayers) {
                    if (hasVisibleDrawablePaint(runCatching { drawable.getDrawable(index) }.getOrNull())) {
                        visible = true
                        break
                    }
                }
                visible
            }
            is RippleDrawable -> {
                var visible = false
                for (index in 0 until drawable.numberOfLayers) {
                    if (hasVisibleDrawablePaint(runCatching { drawable.getDrawable(index) }.getOrNull())) {
                        visible = true
                        break
                    }
                }
                visible
            }
            else -> true
        }
    }

    private fun drawableContainsPatchableSurfacePaint(drawable: Drawable?, depth: Int = 0): Boolean {
        if (drawable == null || depth > 6) return false
        return when (drawable) {
            is ColorDrawable -> isPatchableSurfaceOrOverlayColor(drawable.color)
            is GradientDrawable -> {
                gradientDrawableSolidColor(drawable)?.let(::isPatchableSurfaceOrOverlayColor)
                    ?: gradientDrawableColors(drawable)?.any(::isPatchableSurfaceOrOverlayColor)
                    ?: false
            }
            is ShapeDrawable -> isPatchableSurfaceOrOverlayColor(drawable.paint.color)
            is InsetDrawable -> {
                val inner = runCatching { drawable.drawable }.getOrNull()
                    ?: runCatching { drawable.getObjectField("mDrawable") as? Drawable }.getOrNull()
                drawableContainsPatchableSurfacePaint(inner, depth + 1)
            }
            is LayerDrawable -> {
                for (index in 0 until drawable.numberOfLayers) {
                    if (drawableContainsPatchableSurfacePaint(runCatching { drawable.getDrawable(index) }.getOrNull(), depth + 1)) {
                        return true
                    }
                }
                false
            }
            is RippleDrawable -> {
                for (index in 0 until drawable.numberOfLayers) {
                    if (drawableContainsPatchableSurfacePaint(runCatching { drawable.getDrawable(index) }.getOrNull(), depth + 1)) {
                        return true
                    }
                }
                false
            }
            else -> !drawable.javaClass.name.startsWith("android.")
        }
    }

    private fun needsAmoledDrawableColor(color: Int): Boolean {
        return hasVisibleDrawableColor(color) && (color and 0x00FFFFFF) != 0
    }

    private fun normalizePossiblyRgbColor(color: Int): Int {
        val rgb = color and 0x00FFFFFF
        return if ((color ushr 24) == 0 && rgb != 0) {
            0xFF000000.toInt() or rgb
        } else {
            color
        }
    }

    private fun parseColorString(value: String): Int? {
        val raw = value.trim()
        if (raw.isEmpty()) return null
        val lower = raw.lowercase()

        if (lower.startsWith("#")) {
            val hex = lower.removePrefix("#")
            val expanded = when (hex.length) {
                3 -> hex.flatMap { listOf(it, it) }.joinToString("")
                4 -> hex.flatMap { listOf(it, it) }.joinToString("")
                6, 8 -> hex
                else -> return null
            }
            val parsed = expanded.toLongOrNull(16) ?: return null
            return when (expanded.length) {
                6 -> 0xFF000000.toInt() or parsed.toInt()
                8 -> parsed.toInt()
                else -> null
            }
        }

        if (lower.startsWith("0x")) {
            val parsed = lower.removePrefix("0x").toLongOrNull(16) ?: return null
            return normalizePossiblyRgbColor((parsed and 0xFFFFFFFFL).toInt())
        }

        if (lower.startsWith("rgb")) {
            val start = lower.indexOf('(')
            val end = lower.lastIndexOf(')')
            if (start == -1 || end <= start) return null
            val parts = lower.substring(start + 1, end)
                .split(',')
                .map { it.trim().removeSuffix("%") }
            if (parts.size < 3) return null
            val red = parts[0].toFloatOrNull()?.toInt()?.coerceIn(0, 255) ?: return null
            val green = parts[1].toFloatOrNull()?.toInt()?.coerceIn(0, 255) ?: return null
            val blue = parts[2].toFloatOrNull()?.toInt()?.coerceIn(0, 255) ?: return null
            val alpha = parts.getOrNull(3)?.toFloatOrNull()?.let {
                if (it <= 1f) (it * 255f).toInt() else it.toInt()
            }?.coerceIn(0, 255) ?: 0xFF
            return Color.argb(alpha, red, green, blue)
        }

        return null
    }

    private fun colorIntFromValue(value: Any?): Int? {
        return when (value) {
            is Int -> normalizePossiblyRgbColor(value)
            is Long -> normalizePossiblyRgbColor((value and 0xFFFFFFFFL).toInt())
            is Float -> {
                if (value.isNaN() || value.isInfinite()) return null
                if (value < 0f || value > 0xFFFFFFFFL.toFloat()) return null
                val rounded = value.toLong()
                if (rounded.toFloat() != value) return null
                normalizePossiblyRgbColor((rounded and 0xFFFFFFFFL).toInt())
            }
            is Double -> {
                if (value.isNaN() || value.isInfinite()) return null
                if (value < 0.0 || value > 0xFFFFFFFFL.toDouble()) return null
                val rounded = value.toLong()
                if (rounded.toDouble() != value) return null
                normalizePossiblyRgbColor((rounded and 0xFFFFFFFFL).toInt())
            }
            is String -> parseColorString(value)
            else -> null
        }
    }

    private fun themeRuntime(): ThemeRuntime {
        val customTheme = context.config.userInterface.customTheme
        val forceAmoled = context.config.userInterface.forceAmoledTheme.get()
        val customEnabled = customTheme.enabled.get()
        val rawSurfaceOverrides = customTheme.surfaceColorOverrides.getNullable().orEmpty()
        val rawSurfaceRules = customTheme.customSurfaceRules.getNullable().orEmpty()
        val rawScreenBackgrounds = customTheme.screenBackgrounds.getNullable().orEmpty()
        val current = cachedThemeRuntime
        if (current.forceAmoled == forceAmoled &&
            current.customEnabled == customEnabled &&
            current.rawSurfaceOverrides == rawSurfaceOverrides &&
            current.rawSurfaceRules == rawSurfaceRules &&
            current.rawScreenBackgrounds == rawScreenBackgrounds
        ) {
            return current
        }

        val surfaceOverrides = parseSurfaceOverrides(rawSurfaceOverrides)
        val rules = parseThemeSurfaceRules(rawSurfaceRules)
        val backgrounds = parseThemeBackgroundRules(rawScreenBackgrounds)
        val resourceRuleColors = rules
            .asSequence()
            .filterNot { it.selector }
            .associateByNormalizedTarget { it.color }
        val resourceBackgroundPaths = backgrounds
            .asSequence()
            .filterNot { it.selector }
            .associateByNormalizedTarget { it.path }

        val next = ThemeRuntime(
            forceAmoled = forceAmoled,
            customEnabled = customEnabled,
            rawSurfaceOverrides = rawSurfaceOverrides,
            rawSurfaceRules = rawSurfaceRules,
            rawScreenBackgrounds = rawScreenBackgrounds,
            surfaceOverrides = surfaceOverrides,
            rules = rules,
            backgrounds = backgrounds,
            selectorRules = rules.filter { it.selector },
            resourceRuleColors = resourceRuleColors,
            globalRuleColors = resourceRuleColors.filterKeys { it in globalBackgroundCatalogTargets },
            selectorBackgrounds = backgrounds.filter { it.selector },
            resourceBackgroundPaths = resourceBackgroundPaths,
            globalBackgroundPaths = resourceBackgroundPaths.filterKeys { it in globalBackgroundCatalogTargets }
        )
        cachedThemeRuntime = next
        amoledTrace(
            category = "runtime",
            key = "${next.forceAmoled}|${next.customEnabled}|${next.rawSurfaceOverrides.hashCode()}|" +
                "${next.rawSurfaceRules.hashCode()}|${next.rawScreenBackgrounds.hashCode()}",
            maxPerCategory = 80
        ) {
                "forceAmoled=${next.forceAmoled} customEnabled=${next.customEnabled} " +
                "surfaceOverrides=${next.surfaceOverrides.size}:${next.surfaceOverrides.keys.take(32)} " +
                "surfaceRules=${next.rules.size}:${next.rules.map { it.target }.take(24)} " +
                "selectorRules=${next.selectorRules.size} resourceRuleColors=${next.resourceRuleColors.size} " +
                "globalRuleColors=${next.globalRuleColors.size} backgrounds=${next.backgrounds.size}:${next.backgrounds.map { it.target }.take(24)} " +
                "selectorBackgrounds=${next.selectorBackgrounds.size} resourceBackgrounds=${next.resourceBackgroundPaths.size} " +
                "globalBackgrounds=${next.globalBackgroundPaths.size} " +
                "rawOverrideBytes=${next.rawSurfaceOverrides.length} rawRuleBytes=${next.rawSurfaceRules.length} " +
                "rawBackgroundBytes=${next.rawScreenBackgrounds.length}"
        }
        return next
    }

    private fun parseSurfaceOverrides(raw: String): Map<String, Int> {
        if (raw.isBlank()) return emptyMap()
        val values = linkedMapOf<String, Int>()
        raw.lineSequence().forEachIndexed { lineIndex, rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) return@forEachIndexed
            val parsed = runCatching {
                if (line.startsWith("{")) {
                    val json = JSONObject(line)
                    val key = normalizeSurfaceKey(
                        json.optString("key").ifEmpty { json.optString("surface") }
                    )
                    val color = parseThemeColor(json.optString("color"))
                    if (key.isNotEmpty() && color != null) key to color else null
                } else {
                    val key = normalizeSurfaceKey(line.substringBefore('=').trim())
                    val color = parseThemeColor(line.substringAfter('=', "").trim())
                    if (key.isNotEmpty() && color != null) key to color else null
                }
            }.onFailure {
                amoledTrace("theme-config-parse", "override-error|$lineIndex|${line.take(140)}", maxPerCategory = 500) {
                    "surface override parse error line=$lineIndex raw=$line error=${it.message}"
                }
            }.getOrNull()
            if (parsed != null) {
                values[parsed.first] = parsed.second
                amoledTrace("theme-config-parse", "override-ok|${parsed.first}|${colorHex(parsed.second)}", maxPerCategory = 700) {
                    "surface override parsed line=$lineIndex key=${parsed.first} color=${colorHex(parsed.second)} raw=$line"
                }
            } else {
                amoledTrace("theme-config-parse", "override-skip|$lineIndex|${line.take(140)}", maxPerCategory = 500) {
                    "surface override skipped line=$lineIndex raw=$line"
                }
            }
        }
        return values
    }

    private fun isThemeSelectorTarget(target: String): Boolean {
        return target.startsWith("selector:v1|") || target.startsWith("snapvirtual:v1|")
    }

    private fun isSnapchatVirtualThemeSelector(target: String): Boolean {
        return target.startsWith("snapvirtual:v1|")
    }

    private fun snapchatVirtualSelectorValue(selector: String, key: String): String? {
        if (!isSnapchatVirtualThemeSelector(selector)) return null
        return selector.substring("snapvirtual:v1|".length)
            .split('|')
            .mapNotNull { part ->
                val equals = part.indexOf('=')
                if (equals <= 0) null else part.substring(0, equals) to unescapeSnapchatVirtualSelectorValue(part.substring(equals + 1))
            }
            .firstOrNull { it.first == key }
            ?.second
            ?.takeIf { it.isNotBlank() }
    }

    private fun unescapeSnapchatVirtualSelectorValue(value: String): String {
        return value
            .replace("%0A", "\n")
            .replace("%3D", "=")
            .replace("%7C", "|")
            .replace("%25", "%")
    }

    private fun cleanSnapchatVirtualTextValue(value: String): String {
        val clean = value.trim()
        return clean.takeUnless { it.equals("none", ignoreCase = true) }.orEmpty()
    }

    private fun snapchatVirtualThemeText(selector: String): String {
        val text = cleanSnapchatVirtualTextValue(snapchatVirtualSelectorValue(selector, "text").orEmpty())
        val desc = cleanSnapchatVirtualTextValue(snapchatVirtualSelectorValue(selector, "desc").orEmpty())
        return text.ifBlank { desc }
    }

    private fun isSnapchatVirtualTextThemeSelector(selector: String): Boolean {
        val text = cleanSnapchatVirtualTextValue(snapchatVirtualSelectorValue(selector, "text").orEmpty())
        if (text.isBlank()) return false
        if (text.matches(Regex("213\\d{6,}"))) return false
        if ("_" in text || text.equals("none", ignoreCase = true)) return false
        val signal = listOf(
            text,
            snapchatVirtualSelectorValue(selector, "desc").orEmpty(),
            snapchatVirtualSelectorValue(selector, "class").orEmpty(),
            snapchatVirtualSelectorValue(selector, "view_id").orEmpty(),
            snapchatVirtualSelectorValue(selector, "path").orEmpty()
        ).joinToString(" ").lowercase()
        if (listOf("avatar", "bitmoji", "thumbnail", "friendmoji").any { it in signal }) return false
        if (listOf("button", "feed_chat_button", "camera reply").any { it in signal }) return false
        return true
    }

    private fun normalizedVirtualThemeText(value: String): String {
        return whitespaceRegex.replace(value.trim(), " ").trim()
    }

    private fun compactVirtualThemeText(value: String): String {
        return normalizedVirtualThemeText(value)
            .filterNot(Char::isWhitespace)
            .lowercase()
    }

    private fun virtualThemeTextMatches(expectedText: String, drawnText: String): Boolean {
        val expected = normalizedVirtualThemeText(expectedText)
        val drawn = normalizedVirtualThemeText(drawnText)
        if (expected.isBlank() || drawn.isBlank()) return false
        if (expected == drawn) return true
        if (drawn.length >= 2 && expected.contains(drawn)) return true
        if (expected.length >= 2 && drawn.contains(expected)) return true
        val compactExpected = compactVirtualThemeText(expected)
        val compactDrawn = compactVirtualThemeText(drawn)
        if (compactExpected.isBlank() || compactDrawn.isBlank()) return false
        return compactDrawn.length >= 2 && compactExpected.contains(compactDrawn) ||
            compactExpected.length >= 2 && compactDrawn.contains(compactExpected)
    }

    private fun rectContainsTextBaseline(rect: Rect, x: Float, y: Float): Boolean {
        val horizontalTolerance = 48f
        val verticalTolerance = 30f
        return x >= rect.left - horizontalTolerance &&
            x <= rect.right + horizontalTolerance &&
            y >= rect.top - verticalTolerance &&
            y <= rect.bottom + verticalTolerance
    }

    private fun parseCanvasTextDrawCall(args: Array<Any?>): CanvasTextDrawCall? {
        val paintIndex = args.indexOfLast { it is Paint }
        if (paintIndex < 0) return null
        val paint = args[paintIndex] as? Paint ?: return null
        fun numberAt(index: Int): Float? = (args.getOrNull(index) as? Number)?.toFloat()
        fun intAt(index: Int): Int? = (args.getOrNull(index) as? Number)?.toInt()
        fun textSlice(source: Any?, start: Int, endOrCount: Int, countMode: Boolean): String? {
            return when (source) {
                is CharArray -> {
                    val safeStart = start.coerceIn(0, source.size)
                    val count = if (countMode) {
                        endOrCount.coerceIn(0, source.size - safeStart)
                    } else {
                        endOrCount.coerceIn(safeStart, source.size) - safeStart
                    }
                    String(source, safeStart, count)
                }
                is CharSequence -> {
                    val safeStart = start.coerceIn(0, source.length)
                    val safeEnd = if (countMode) {
                        (safeStart + endOrCount).coerceIn(safeStart, source.length)
                    } else {
                        endOrCount.coerceIn(safeStart, source.length)
                    }
                    source.subSequence(safeStart, safeEnd).toString()
                }
                else -> null
            }
        }
        return when (paintIndex) {
            3 -> {
                val text = args.getOrNull(0)?.toString()
                val x = numberAt(1) ?: return null
                val y = numberAt(2) ?: return null
                CanvasTextDrawCall(text, x, y, paintIndex, paint)
            }
            5 -> {
                val start = intAt(1) ?: return null
                val endOrCount = intAt(2) ?: return null
                val x = numberAt(3) ?: return null
                val y = numberAt(4) ?: return null
                val text = textSlice(args.getOrNull(0), start, endOrCount, countMode = args.getOrNull(0) is CharArray)
                CanvasTextDrawCall(text, x, y, paintIndex, paint)
            }
            6 -> {
                val positions = args.getOrNull(2) as? FloatArray ?: return null
                val positionOffset = intAt(3)?.coerceAtLeast(0) ?: return null
                if (positionOffset + 1 >= positions.size) return null
                CanvasTextDrawCall(
                    text = null,
                    x = positions[positionOffset],
                    y = positions[positionOffset + 1],
                    paintIndex = paintIndex,
                    paint = paint
                )
            }
            8 -> {
                val start = intAt(1) ?: return null
                val endOrCount = intAt(2) ?: return null
                val x = numberAt(5) ?: return null
                val y = numberAt(6) ?: return null
                val text = textSlice(args.getOrNull(0), start, endOrCount, countMode = args.getOrNull(0) is CharArray)
                CanvasTextDrawCall(text, x, y, paintIndex, paint)
            }
            else -> null
        }
    }

    private fun refinedSnapchatVirtualTextBounds(host: View, selector: String, localBounds: Rect): Rect {
        val refined = Rect(localBounds)
        val hostId = snapchatVirtualSelectorValue(selector, "host_id").orEmpty()
        val path = snapchatVirtualSelectorValue(selector, "path").orEmpty()
        val text = snapchatVirtualThemeText(selector)
        if (
            hostId == "ff_item" &&
            path == "2" &&
            text.isNotBlank() &&
            refined.left <= 8 &&
            host.height in 120..260
        ) {
            val old = Rect(refined)
            val textStart = (host.height * 1.05f).toInt().coerceIn(0, (refined.right - 12).coerceAtLeast(0))
            val measuredWidth = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
                typeface = Typeface.DEFAULT_BOLD
                textSize = (refined.height() * 0.64f).coerceAtLeast(10f)
            }.measureText(text).toInt()
            val rowActionSafeRight = (host.width - (host.height * 1.35f).toInt()).coerceAtLeast(textStart + 24)
            val measuredRight = (textStart + measuredWidth + 30).coerceAtLeast(refined.right)
            val verticalOffset = (host.height * 0.16f).toInt().coerceAtLeast(0)
            refined.left = textStart
            refined.right = measuredRight.coerceAtMost(rowActionSafeRight).coerceAtLeast(refined.left + 24)
            refined.offset(0, verticalOffset)
            amoledTrace("virtual-theme-text", "refine|${selector.take(140)}|${viewTraceKey(host)}", maxPerCategory = 1200) {
                "refined virtual text bounds old=${rectToThemeString(old)} new=${rectToThemeString(refined)} " +
                    "measuredWidth=$measuredWidth safeRight=$rowActionSafeRight verticalOffset=$verticalOffset " +
                    "text=$text host=${viewSummary(host)}"
            }
        }
        return refined
    }

    private fun parseSnapchatVirtualBounds(value: String?): Rect? {
        val raw = value?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val match = Regex("\\[(-?\\d+),(-?\\d+)]\\[(-?\\d+),(-?\\d+)]").matchEntire(raw) ?: return null
        val left = match.groupValues[1].toIntOrNull() ?: return null
        val top = match.groupValues[2].toIntOrNull() ?: return null
        val right = match.groupValues[3].toIntOrNull() ?: return null
        val bottom = match.groupValues[4].toIntOrNull() ?: return null
        return Rect(left, top, right, bottom).takeIf { !it.isEmpty }
    }

    private fun parseThemeSurfaceRules(raw: String): List<ThemeSurfaceRule> {
        if (raw.isBlank()) return emptyList()
        return raw.lineSequence()
            .mapIndexedNotNull { lineIndex, rawLine ->
                val line = rawLine.trim()
                if (line.isEmpty()) return@mapIndexedNotNull null
                runCatching {
                    val json = JSONObject(line)
                    val target = json.optString("target").trim()
                    val color = parseThemeColor(json.optString("color")) ?: return@runCatching null
                    ThemeSurfaceRule(
                        target = target,
                        selector = json.optBoolean("selector", isThemeSelectorTarget(target)),
                        label = json.optString("label").trim().ifEmpty { "Captured surface" },
                        color = color
                    )
                }.onFailure {
                    amoledTrace("theme-config-parse", "rule-error|$lineIndex|${line.take(140)}", maxPerCategory = 500) {
                        "surface rule parse error line=$lineIndex raw=$line error=${it.message}"
                    }
                }.getOrNull()?.also { rule ->
                    amoledTrace("theme-config-parse", "rule-ok|${rule.selector}|${rule.target.take(140)}|${colorHex(rule.color)}", maxPerCategory = 700) {
                        "surface rule parsed line=$lineIndex target=${rule.target} selector=${rule.selector} " +
                            "label=${rule.label} color=${colorHex(rule.color)} raw=$line"
                    }
                } ?: run {
                    amoledTrace("theme-config-parse", "rule-skip|$lineIndex|${line.take(140)}", maxPerCategory = 500) {
                        "surface rule skipped line=$lineIndex raw=$line"
                    }
                    null
                }
            }
            .filter { it.target.isNotEmpty() }
            .toList()
    }

    private fun parseThemeBackgroundRules(raw: String): List<ThemeBackgroundRule> {
        if (raw.isBlank()) return emptyList()
        return raw.lineSequence()
            .mapIndexedNotNull { lineIndex, rawLine ->
                val line = rawLine.trim()
                if (line.isEmpty()) return@mapIndexedNotNull null
                runCatching {
                    val json = JSONObject(line)
                    val target = json.optString("target").trim()
                    val path = json.optString("path").trim()
                    if (target.isEmpty() || path.isEmpty()) return@runCatching null
                    ThemeBackgroundRule(
                        target = target,
                        selector = json.optBoolean("selector", isThemeSelectorTarget(target)),
                        label = json.optString("label").trim().ifEmpty { "Background" },
                        path = path
                    )
                }.onFailure {
                    amoledTrace("theme-config-parse", "background-error|$lineIndex|${line.take(140)}", maxPerCategory = 500) {
                        "background rule parse error line=$lineIndex raw=$line error=${it.message}"
                    }
                }.getOrNull()?.also { background ->
                    amoledTrace("theme-config-parse", "background-ok|${background.selector}|${background.target.take(140)}|${background.path.take(140)}", maxPerCategory = 700) {
                        "background rule parsed line=$lineIndex target=${background.target} selector=${background.selector} " +
                            "label=${background.label} path=${background.path} raw=$line"
                    }
                } ?: run {
                    amoledTrace("theme-config-parse", "background-skip|$lineIndex|${line.take(140)}", maxPerCategory = 500) {
                        "background rule skipped line=$lineIndex raw=$line"
                    }
                    null
                }
            }
            .filter { it.target.isNotEmpty() && it.path.isNotEmpty() }
            .toList()
    }

    private fun parseThemeColor(value: String?): Int? {
        val raw = value?.trim().orEmpty()
        if (raw.isEmpty()) return null
        return runCatching { Color.parseColor(raw) }.getOrNull()
            ?: parseColorString(raw)
    }

    private fun normalizeSurfaceKey(value: String?): String {
        return value
            ?.trim()
            ?.lowercase()
            ?.replace(Regex("[^a-z0-9_.-]+"), "_")
            ?.trim('_', '.', '-')
            .orEmpty()
    }

    private fun isMappedValdiSurfaceAttributeName(attributeName: String): Boolean {
        val normalized = normalizeSurfaceKey(attributeName)
        if (normalized.isBlank()) return false
        val index = staticThemeSurfaceIndex
        val mapped = normalized in index.normalizedAttributes
        if (mapped) {
            amoledTrace("theme-map-index-hit", "attr|$normalized", maxPerCategory = 900) {
                "mapped Valdi surface attribute hit attr=$attributeName normalized=$normalized " +
                    "hash=${index.uniqueHash} attrs=${index.normalizedAttributes.size} owners=${index.ownerSamples.size}"
            }
        }
        return mapped
    }

    private fun isMappedValdiSurfaceToken(value: String): Boolean {
        val normalized = normalizeSurfaceKey(value)
        if (normalized.isBlank()) return false
        val index = staticThemeSurfaceIndex
        val mapped = normalized in index.normalizedTokens
        if (mapped) {
            amoledTrace("theme-map-index-hit", "token|$normalized", maxPerCategory = 900) {
                "mapped Valdi surface token hit value=$value normalized=$normalized " +
                    "hash=${index.uniqueHash} tokens=${index.normalizedTokens.size} owners=${index.ownerSamples.size}"
            }
        }
        return mapped
    }

    private fun retainedValdiSurfaceAttributesForIndex(): Array<String> {
        val index = staticThemeSurfaceIndex
        if (retainedValdiSurfaceAttributeSnapshotSignature == index.signature &&
            retainedValdiSurfaceAttributeSnapshot.isNotEmpty()
        ) {
            return retainedValdiSurfaceAttributeSnapshot
        }

        synchronized(staticThemeIndexLock) {
            if (retainedValdiSurfaceAttributeSnapshotSignature == index.signature &&
                retainedValdiSurfaceAttributeSnapshot.isNotEmpty()
            ) {
                return retainedValdiSurfaceAttributeSnapshot
            }

            val mappedAttributes = index.attributeStrings.flatMap { (normalized, raw) ->
                listOfNotNull(raw, normalized)
            }.map { it.trim() }
                .filter { it.isNotBlank() && it.length <= 80 }

            val snapshot = (retainedValdiSurfaceAttributes.asSequence() + mappedAttributes.asSequence())
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinctBy { normalizeSurfaceKey(it) }
                .take(128)
                .toList()
                .toTypedArray()
            retainedValdiSurfaceAttributeSnapshot = snapshot
            retainedValdiSurfaceAttributeSnapshotSignature = index.signature
            amoledTrace("theme-map-index", "retained-attrs|${index.signature}", maxPerCategory = 120, unique = false) {
                "retained Valdi attr snapshot hash=${index.uniqueHash} loaded=${index.loaded} " +
                    "base=${retainedValdiSurfaceAttributes.size} mapped=${mappedAttributes.size} final=${snapshot.size} " +
                    "attrs=${snapshot.take(80).joinToString()}"
            }
            return snapshot
        }
    }

    private fun colorToRgbaString(color: Int): String {
        val alpha = Color.alpha(color) / 255f
        val alphaText = if (alpha >= 0.999f) {
            "1"
        } else {
            String.format(java.util.Locale.US, "%.3f", alpha).trimEnd('0').trimEnd('.')
        }
        return "rgba(${Color.red(color)}, ${Color.green(color)}, ${Color.blue(color)}, $alphaText)"
    }

    private fun themedValueFor(original: Any, color: Int): Any? {
        return when (original) {
            is Int -> color
            is Long -> color.toLong() and 0xFFFFFFFFL
            is Float -> (color.toLong() and 0xFFFFFFFFL).toFloat()
            is Double -> (color.toLong() and 0xFFFFFFFFL).toDouble()
            is String -> colorToRgbaString(color)
            else -> null
        }
    }

    private fun surfaceKeysFor(attributeName: String, value: Any?, className: String?): List<String> {
        val keys = linkedSetOf<String>()
        val normalizedAttr = normalizeSurfaceKey(attributeName)
        if (normalizedAttr.isNotEmpty()) {
            keys += "valdi.attr.$normalizedAttr"
            keys += normalizedAttr
        }

        if (value is String) {
            val normalizedToken = normalizeSurfaceKey(value)
            if (normalizedToken.isNotEmpty() && parseColorString(value) == null) {
                keys += "valdi.token.$normalizedToken"
                keys += normalizedToken
            }
        }

        className?.let {
            val normalizedClass = normalizeSurfaceKey(it.substringAfterLast('.'))
            if (normalizedClass.isNotEmpty() && normalizedAttr.isNotEmpty()) {
                keys += "valdi.$normalizedClass.$normalizedAttr"
            }
        }
        return keys.toList()
    }

    private fun previewColorForSurfaceKeys(keys: List<String>): Int? {
        val preview = activePreviewRule() ?: return null
        if (preview.selector) return null
        val target = normalizeSurfaceKey(preview.target)
        if (target.isBlank()) return null
        val matchedKey = keys.firstOrNull { normalizeSurfaceKey(it) == target }
        amoledTrace("surface-color-lookup", "preview|$target|${matchedKey ?: "miss"}", maxPerCategory = 1800) {
            "preview target=$target matchedKey=${matchedKey ?: "none"} color=${colorHex(preview.color)} keys=${keys.joinToString()}"
        }
        return if (matchedKey != null) preview.color else null
    }

    private fun customSurfaceColor(attributeName: String, value: Any?, className: String?): Int? {
        val runtime = themeRuntime()
        val keys = surfaceKeysFor(attributeName, value, className)
        previewColorForSurfaceKeys(keys)?.let { return it }
        if (!runtime.customEnabled || runtime.surfaceOverrides.isEmpty()) return null
        val matchedKey = keys.firstOrNull { runtime.surfaceOverrides.containsKey(it) }
        val color = matchedKey?.let { runtime.surfaceOverrides[it] }
        amoledTrace("surface-color-lookup", "valdi|$attributeName|${className.orEmpty()}|${matchedKey ?: "miss"}|${value?.toString()?.take(120)}", maxPerCategory = 2600) {
            "valdi attr=$attributeName class=${className ?: "unknown"} value=${valueSummary(value)} " +
                "keys=${keys.joinToString()} matchedKey=${matchedKey ?: "none"} color=${colorHex(color)} " +
                "overrides=${runtime.surfaceOverrides.size}"
        }
        return color
    }

    private fun surfaceKeysForView(view: View): List<String> {
        val resourceName = normalizeSurfaceKey(getResourceEntryName(view))
        if (resourceName.isBlank()) return emptyList()
        return listOf("android.view.$resourceName", resourceName)
    }

    private fun customCatalogColorForView(view: View, runtime: ThemeRuntime = themeRuntime()): Int? {
        val keys = surfaceKeysForView(view)
        if (keys.isEmpty()) return null
        previewColorForSurfaceKeys(keys)?.let { return it }
        if (!runtime.customEnabled || runtime.surfaceOverrides.isEmpty()) return null
        val matchedKey = keys.firstOrNull { runtime.surfaceOverrides.containsKey(it) }
        val color = matchedKey?.let { runtime.surfaceOverrides[it] }
        amoledTrace("surface-color-lookup", "view|${viewClassTraceKey(view)}|${matchedKey ?: "miss"}", maxPerCategory = 1800) {
            "view catalog lookup keys=${keys.joinToString()} matchedKey=${matchedKey ?: "none"} " +
                "color=${colorHex(color)} overrides=${runtime.surfaceOverrides.size} ${viewSummary(view)}"
        }
        return color
    }

    private fun valdiValuePatchCacheKey(
        runtime: ThemeRuntime,
        attributeName: String,
        value: Any,
        className: String?
    ): String {
        return buildString {
            append(runtime.forceAmoled).append('|')
            append(runtime.customEnabled).append('|')
            append(runtime.rawSurfaceOverrides.hashCode()).append('|')
            append(activePreviewRule()?.signature ?: 0).append('|')
            append(attributeName.lowercase()).append('|')
            append(className.orEmpty()).append('|')
            append(value.javaClass.name).append('|')
            append(value)
        }
    }

    private fun cachedPatchedValdiValue(
        runtime: ThemeRuntime,
        attributeName: String,
        value: Any,
        className: String?
    ): Any? {
        val key = valdiValuePatchCacheKey(runtime, attributeName, value, className)
        synchronized(valdiValuePatchCache) {
            if (valdiValuePatchCache.containsKey(key)) {
                val cached = valdiValuePatchCache[key]
                amoledTrace("valdi-patch-decision", "cache-hit|${key.hashCode()}|${value.javaClass.name}|${cached?.toString()?.take(80)}", maxPerCategory = 2400) {
                    "cache hit attr=$attributeName class=${className ?: "unknown"} value=${valueSummary(value)} " +
                        "patched=${valueSummary(cached)} runtimeForce=${runtime.forceAmoled} custom=${runtime.customEnabled}"
                }
                return cached
            }
        }

        var patchReason = "none"
        val patched = customSurfaceColor(attributeName, value, className)?.let { color ->
            patchReason = "custom_surface_color_${colorHex(color)}"
            themedValueFor(value, color)
        } ?: run {
            if (!runtime.forceAmoled) {
                patchReason = "force_amoled_disabled"
                null
            } else if (value is String && isPatchableSurfaceToken(value)) {
                patchReason = "patchable_surface_token"
                "flatPureBlack"
            } else {
                val color = colorIntFromValue(value)
                if (color != null && isPatchableSurfaceOrOverlayColor(color)) {
                    patchReason = "patchable_surface_color_${colorHex(color)}"
                    themedValueFor(value, amoledSurfaceColorFor(color))
                } else {
                    patchReason = "not_patchable_color_${colorHex(color)}"
                    null
                }
            }
        }

        synchronized(valdiValuePatchCache) {
            valdiValuePatchCache[key] = patched
        }
        amoledTrace("valdi-patch-decision", "cache-miss|${key.hashCode()}|$patchReason|${value.javaClass.name}|${patched?.toString()?.take(80)}", maxPerCategory = 2600) {
            "cache miss attr=$attributeName class=${className ?: "unknown"} value=${valueSummary(value)} " +
                "patched=${valueSummary(patched)} reason=$patchReason forceAmoled=${runtime.forceAmoled} " +
                "customEnabled=${runtime.customEnabled} keys=${surfaceKeysFor(attributeName, value, className).joinToString()}"
        }
        return patched
    }

    private fun customAndroidAttrColor(attrId: Int, attrName: String?): Int? {
        val runtime = themeRuntime()
        val normalizedName = normalizeSurfaceKey(attrName)
        val keys = buildList {
            if (normalizedName.isNotEmpty()) {
                add("android.attr.$normalizedName")
                add(normalizedName)
            }
            add("android.attr.0x${attrId.toString(16)}")
        }
        previewColorForSurfaceKeys(keys)?.let { return it }
        if (!runtime.customEnabled || runtime.surfaceOverrides.isEmpty()) return null
        val matchedKey = keys.firstOrNull { runtime.surfaceOverrides.containsKey(it) }
        val color = matchedKey?.let { runtime.surfaceOverrides[it] }
        amoledTrace("surface-color-lookup", "android-attr|0x${attrId.toString(16)}|${attrName.orEmpty()}|${matchedKey ?: "miss"}", maxPerCategory = 1600) {
            "android attr lookup attrId=0x${attrId.toString(16)} attrName=${attrName ?: "unknown"} " +
                "keys=${keys.joinToString()} matchedKey=${matchedKey ?: "none"} color=${colorHex(color)} " +
                "overrides=${runtime.surfaceOverrides.size}"
        }
        return color
    }

    private fun activePreviewRule(): ThemePreviewRule? {
        val preview = previewRule ?: return null
        if (SystemClock.uptimeMillis() <= preview.expiresAtMs) return preview
        previewRule = null
        return null
    }

    private fun matchesThemeTarget(view: View, target: String, selector: Boolean): Boolean {
        if (target == "__global__") {
            val decision = globalBackgroundHostDecision(view)
            logThemeTargetEvaluation(view, target, selector, decision.matched, "global_background:${decision.reason}")
            return decision.matched
        }
        if (!selector && normalizeSurfaceKey(target) in globalBackgroundCatalogTargets) {
            val decision = globalBackgroundHostDecision(view)
            logThemeTargetEvaluation(view, target, selector, decision.matched, "catalog_global_background:${decision.reason}")
            return decision.matched
        }
        return if (selector) {
            val matched = WhatsAppUiElementSelector.matches(view, target)
            logThemeTargetEvaluation(view, target, selector, matched, "selector")
            matched
        } else {
            val resourceName = getResourceEntryName(view)
            if (resourceName == null) {
                logThemeTargetEvaluation(view, target, selector, false, "missing_resource")
                return false
            }
            val normalizedTarget = normalizeSurfaceKey(target)
            val normalizedResource = normalizeSurfaceKey(resourceName)
            val matched = when {
                normalizedTarget.startsWith("android.view.") -> normalizedTarget == "android.view.$normalizedResource"
                else -> resourceName == target || normalizedResource == normalizedTarget
            }
            logThemeTargetEvaluation(view, target, selector, matched, "resource=$resourceName normalized=$normalizedResource target=$normalizedTarget")
            matched
        }
    }

    private fun logThemeTargetEvaluation(
        view: View,
        target: String,
        selector: Boolean,
        matched: Boolean,
        reason: String
    ) {
        val key = if (matched) {
            "match|$selector|${target.take(140)}|${viewTraceKey(view)}"
        } else {
            "miss|$selector|${target.take(140)}|${viewClassTraceKey(view)}|$reason"
        }
        amoledTrace("theme-target", key, maxPerCategory = if (matched) 1600 else 700) {
            "matched=$matched selector=$selector target=$target reason=$reason " +
                "resource=${getResourceEntryName(view) ?: "none"} keys=${surfaceKeysForView(view).joinToString()} " +
                "class=${view.javaClass.name} size=${view.width}x${view.height} " +
                "details=${viewThemeLogDetails(view, full = true)}"
        }
    }

    private fun viewBoundsRect(view: View): Rect? {
        if (!view.isAttachedToWindow) return null
        if (view.width <= 0 || view.height <= 0) return null
        val location = IntArray(2)
        return runCatching {
            view.getLocationOnScreen(location)
            Rect(location[0], location[1], location[0] + view.width, location[1] + view.height)
        }.getOrNull()?.takeIf { !it.isEmpty }
    }

    private fun rectToThemeString(rect: Rect): String {
        return "[${rect.left},${rect.top}][${rect.right},${rect.bottom}]"
    }

    private fun reflectedAccessibilityVirtualChildId(node: AccessibilityNodeInfo, index: Int): Int? {
        val childId = runCatching {
            accessibilityGetChildIdMethod?.invoke(node, index) as? Long
        }.getOrNull() ?: return null
        val reflected = runCatching {
            accessibilityGetVirtualDescendantIdMethod?.invoke(null, childId) as? Int
        }.getOrNull()
        if (reflected != null && reflected != Int.MAX_VALUE) return reflected
        val fallback = (childId shr 32).toInt()
        return fallback.takeIf { it != Int.MAX_VALUE }
    }

    private fun resolveAccessibilityChildNode(
        node: AccessibilityNodeInfo,
        index: Int,
        provider: android.view.accessibility.AccessibilityNodeProvider?
    ): AccessibilityNodeInfo? {
        runCatching { node.getChild(index) }.getOrNull()?.let { return it }
        val virtualId = reflectedAccessibilityVirtualChildId(node, index) ?: return null
        return provider?.let { runCatching { it.createAccessibilityNodeInfo(virtualId) }.getOrNull() }
    }

    private fun closeEnoughRect(left: Rect, right: Rect, tolerance: Int = 3): Boolean {
        return kotlin.math.abs(left.left - right.left) <= tolerance &&
            kotlin.math.abs(left.top - right.top) <= tolerance &&
            kotlin.math.abs(left.right - right.right) <= tolerance &&
            kotlin.math.abs(left.bottom - right.bottom) <= tolerance
    }

    private fun virtualScreenBoundsTolerance(localBounds: Rect, hostBounds: Rect): Int {
        val dominantSize = maxOf(localBounds.width(), localBounds.height(), hostBounds.height())
        return (dominantSize / 8).coerceIn(8, 28)
    }

    private fun shouldGateVirtualSelectorByScreenBounds(
        resourceName: String?,
        hostId: String?,
        hostClass: String?
    ): Boolean {
        val identity = listOfNotNull(resourceName, hostId, hostClass)
            .joinToString(" ")
            .lowercase()
        return identity.contains("ff_item") ||
            identity.contains("recycler") ||
            identity.contains("cell") ||
            identity.endsWith("_item") ||
            identity.endsWith("_row") ||
            identity.startsWith("ngs_")
    }

    private fun hasSnapchatVirtualThemeSelectors(runtime: ThemeRuntime = themeRuntime()): Boolean {
        val preview = activePreviewRule()
        return preview?.let { it.selector && isSnapchatVirtualThemeSelector(it.target) } == true ||
            runtime.customEnabled && runtime.selectorRules.any { isSnapchatVirtualThemeSelector(it.target) }
    }

    private fun hasSnapchatVirtualTextThemeSelectors(runtime: ThemeRuntime = themeRuntime()): Boolean {
        val preview = activePreviewRule()
        return preview?.let {
            it.selector &&
                isSnapchatVirtualThemeSelector(it.target) &&
                isSnapchatVirtualTextThemeSelector(it.target)
        } == true ||
            runtime.customEnabled && runtime.selectorRules.any {
                isSnapchatVirtualThemeSelector(it.target) && isSnapchatVirtualTextThemeSelector(it.target)
            }
    }

    private fun virtualThemeLayoutSignature(runtime: ThemeRuntime = themeRuntime()): Int {
        return 31 * runtime.viewRuleSignature + (activePreviewRule()?.signature ?: 0)
    }

    private fun snapchatVirtualSelectorHostIdentityMatches(
        view: View,
        selector: String,
        resourceName: String? = getResourceEntryName(view)
    ): Boolean {
        val hostId = snapchatVirtualSelectorValue(selector, "host_id")
        val hostSelector = snapchatVirtualSelectorValue(selector, "host_selector")
        val hostClass = snapchatVirtualSelectorValue(selector, "host_class")
        return when {
            !hostId.isNullOrBlank() -> resourceName == hostId
            !hostSelector.isNullOrBlank() -> runCatching {
                WhatsAppUiElementSelector.matches(view, hostSelector)
            }.getOrDefault(false)
            !hostClass.isNullOrBlank() -> view.javaClass.name == hostClass || view.javaClass.simpleName == hostClass
            else -> false
        }
    }

    private fun snapchatVirtualThemeSelectorsForHost(view: View, runtime: ThemeRuntime): List<String> {
        if (!hasSnapchatVirtualThemeSelectors(runtime)) return emptyList()
        val resourceName = getResourceEntryName(view)
        val selectors = linkedSetOf<String>()
        activePreviewRule()?.let { preview ->
            if (preview.selector && isSnapchatVirtualThemeSelector(preview.target)) {
                selectors += preview.target
            }
        }
        if (runtime.customEnabled) {
            runtime.selectorRules
                .asSequence()
                .map { it.target }
                .filter(::isSnapchatVirtualThemeSelector)
                .forEach(selectors::add)
        }
        val matched = selectors.filter { selector ->
            snapchatVirtualSelectorHostIdentityMatches(view, selector, resourceName)
        }
        if (selectors.isNotEmpty() && (matched.isNotEmpty() || !resourceName.isNullOrBlank())) {
            amoledTrace("virtual-theme-host", "${viewTraceKey(view)}|rules=${selectors.size}|matched=${matched.size}", maxPerCategory = 2600) {
                "virtual host candidate check selectors=${selectors.size} matched=${matched.size} " +
                    "resource=${resourceName ?: "none"} class=${view.javaClass.name} simple=${view.javaClass.simpleName} " +
                    "matchedSelectors=${matched.joinToString(" || ") { it.take(220) }} host=${viewThemeLogDetails(view, full = true)}"
            }
        }
        return matched
    }

    private fun rememberVirtualThemeHost(view: View, selectors: List<String>, trigger: String) {
        if (selectors.isEmpty()) return
        val added = synchronized(virtualThemeKnownHosts) {
            virtualThemeKnownHosts.add(view)
        }
        if (added || selectors.isNotEmpty()) {
            amoledTrace("virtual-theme-known-host", "$trigger|added=$added|${viewTraceKey(view)}", maxPerCategory = 1800) {
                "remember virtual host trigger=$trigger added=$added knownCount=${synchronized(virtualThemeKnownHosts) { virtualThemeKnownHosts.size }} " +
                    "selectors=${selectors.size} host=${viewThemeLogDetails(view, full = true)}"
            }
        }
    }

    private fun patchKnownVirtualThemeHosts(
        trigger: String,
        runtime: ThemeRuntime = themeRuntime(),
        maxHosts: Int = 80,
        maxRuntimeMs: Long = 16L
    ): Int {
        if (!hasSnapchatVirtualThemeSelectors(runtime)) return 0
        val startedAt = SystemClock.uptimeMillis()
        val effectiveBudgetMs = if (activePreviewRule()?.target?.let(::isSnapchatVirtualThemeSelector) == true) {
            maxRuntimeMs.coerceAtLeast(96L)
        } else {
            maxRuntimeMs
        }
        val hosts = synchronized(virtualThemeKnownHosts) {
            virtualThemeKnownHosts.toList()
        }.sortedBy { host ->
            viewBoundsRect(host)?.top ?: Int.MAX_VALUE
        }.take(maxHosts)
        var visited = 0
        var changed = 0
        var attached = 0
        var detachedSkipped = 0
        for (host in hosts) {
            if (SystemClock.uptimeMillis() - startedAt > effectiveBudgetMs) break
            visited++
            if (host.isAttachedToWindow) {
                attached++
            } else {
                detachedSkipped++
                synchronized(virtualThemeKnownHosts) {
                    virtualThemeKnownHosts.remove(host)
                }
                amoledTrace("virtual-theme-known-host", "$trigger|skip-detached|${viewTraceKey(host)}", maxPerCategory = 1600) {
                    "skip detached known virtual host trigger=$trigger host=${viewThemeLogDetails(host, full = true)}"
                }
                continue
            }
            if (applyVirtualThemeHostFromLifecycle(
                    view = host,
                    trigger = "$trigger:known",
                    runtime = runtime,
                    scheduleRetry = false
                )
            ) {
                changed++
            }
        }
        amoledTrace("virtual-theme-known-host", "$trigger|visited=$visited|changed=$changed|known=${hosts.size}", maxPerCategory = 1000) {
            "patched known virtual hosts trigger=$trigger knownSnapshot=${hosts.size} visited=$visited attached=$attached " +
                "detachedSkipped=$detachedSkipped changed=$changed elapsed=${SystemClock.uptimeMillis() - startedAt}ms maxHosts=$maxHosts " +
                "budget=${effectiveBudgetMs}ms requestedBudget=${maxRuntimeMs}ms previewVirtual=${activePreviewRule()?.target?.let(::isSnapchatVirtualThemeSelector) == true}"
        }
        return changed
    }

    private fun installVirtualThemeLayoutPatch(
        host: View,
        runtime: ThemeRuntime,
        reason: String,
        selector: String
    ) {
        val signature = virtualThemeLayoutSignature(runtime)
        if (host.getTag(virtualThemeLayoutPatchInstalledTag) == true) {
            amoledTrace("virtual-theme-layout", "skip-installed|$reason|${viewTraceKey(host)}", maxPerCategory = 1600) {
                "skip virtual layout retry already installed reason=$reason selector=${selector.take(240)} host=${viewSummary(host)}"
            }
            return
        }
        if (host.getTag(virtualThemeLayoutPatchSignatureTag) == signature && host.width > 0 && host.height > 0) {
            amoledTrace("virtual-theme-layout", "skip-signature|$reason|$signature|${viewTraceKey(host)}", maxPerCategory = 1600) {
                "skip virtual layout retry same signature=$signature reason=$reason selector=${selector.take(240)} host=${viewSummary(host)}"
            }
            return
        }

        fun runPatch(target: View, trigger: String) {
            if (target.getTag(virtualThemeLayoutPatchInstalledTag) != true) {
                amoledTrace("virtual-theme-layout", "run-skip-cleared|$trigger|$reason|${viewTraceKey(target)}", maxPerCategory = 1200) {
                    "skip virtual layout retry because another trigger already handled it trigger=$trigger reason=$reason " +
                        "selector=${selector.take(240)} host=${viewSummary(target)}"
                }
                return
            }
            target.setTag(virtualThemeLayoutPatchInstalledTag, null)
            val startedAt = SystemClock.uptimeMillis()
            val changed = applyVirtualThemeOverlaysForView(target, themeRuntime())
            val elapsed = SystemClock.uptimeMillis() - startedAt
            target.setTag(virtualThemeLayoutPatchSignatureTag, virtualThemeLayoutSignature())
            amoledTrace("virtual-theme-layout", "run|$trigger|$reason|changed=$changed|${viewTraceKey(target)}", maxPerCategory = 1800) {
                "virtual layout retry trigger=$trigger reason=$reason changed=$changed elapsed=${elapsed}ms " +
                    "selector=${selector.take(360)} host=${viewThemeLogDetails(target, full = true)}"
            }
        }

        fun installLayoutListener(target: View, trigger: String) {
            if (target.getTag(virtualThemeLayoutPatchInstalledTag) != true) return
            amoledTrace("virtual-theme-layout", "listener|$trigger|$reason|${viewTraceKey(target)}", maxPerCategory = 1800) {
                "install virtual layout listener on UI path trigger=$trigger reason=$reason selector=${selector.take(300)} " +
                    "attached=${target.isAttachedToWindow} laidOut=${target.isLaidOut} size=${target.width}x${target.height} host=${viewSummary(target)}"
            }
            target.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
                override fun onLayoutChange(
                    v: View,
                    left: Int,
                    top: Int,
                    right: Int,
                    bottom: Int,
                    oldLeft: Int,
                    oldTop: Int,
                    oldRight: Int,
                    oldBottom: Int
                ) {
                    if (right <= left || bottom <= top) return
                    v.removeOnLayoutChangeListener(this)
                    runPatch(v, "layout:$trigger")
                }
            })
        }

        fun attemptOnUiPath(trigger: String) {
            if (host.getTag(virtualThemeLayoutPatchInstalledTag) != true) return
            if (host.width > 0 && host.height > 0) {
                runPatch(host, trigger)
            } else {
                installLayoutListener(host, trigger)
            }
        }

        host.setTag(virtualThemeLayoutPatchInstalledTag, true)
        amoledTrace("virtual-theme-layout", "schedule|$reason|${viewTraceKey(host)}", maxPerCategory = 1800) {
            "schedule virtual layout retry reason=$reason signature=$signature selector=${selector.take(360)} " +
                "attached=${host.isAttachedToWindow} laidOut=${host.isLaidOut} size=${host.width}x${host.height} host=${viewSummary(host)}"
        }
        val posted = host.post { attemptOnUiPath("post") }
        val delayed = host.postDelayed({ attemptOnUiPath("postDelayed120") }, 120L)
        val activity = currentActivityRef?.get()
        activity?.runOnUiThread { attemptOnUiPath("activity") }
        amoledTrace("virtual-theme-layout", "posted|$reason|post=$posted|delay=$delayed|activity=${activity != null}|${viewTraceKey(host)}", maxPerCategory = 1800) {
            "posted virtual layout retry reason=$reason postAccepted=$posted delayedAccepted=$delayed " +
                "activityFallback=${activity != null} selector=${selector.take(300)} host=${viewSummary(host)}"
        }
        if (!posted && !delayed && activity == null) {
            installLayoutListener(host, "direct-fallback")
        }
    }

    private fun snapchatVirtualSelectorHostMatches(view: View, selector: String): Boolean {
        val hostId = snapchatVirtualSelectorValue(selector, "host_id")
        val hostSelector = snapchatVirtualSelectorValue(selector, "host_selector")
        val hostClass = snapchatVirtualSelectorValue(selector, "host_class")
        val resourceName = getResourceEntryName(view)
        val hostIdentityMatches = snapchatVirtualSelectorHostIdentityMatches(view, selector, resourceName)
        if (!hostIdentityMatches) {
            if ((!hostId.isNullOrBlank() && resourceName == hostId) ||
                (!hostClass.isNullOrBlank() && view.javaClass.simpleName == hostClass)
            ) {
                amoledTrace("virtual-theme-target", "identity-near-miss|${selector.take(140)}|${viewTraceKey(view)}", maxPerCategory = 1200) {
                    "identity near miss selector=$selector hostId=${hostId ?: "none"} hostSelector=${hostSelector ?: "none"} " +
                        "hostClass=${hostClass ?: "none"} resource=${resourceName ?: "none"} viewClass=${view.javaClass.name} host=${viewSummary(view)}"
                }
            }
            return false
        }

        val localBounds = parseSnapchatVirtualBounds(snapchatVirtualSelectorValue(selector, "local_bounds"))
        if (localBounds == null) {
            amoledTrace("virtual-theme-target", "skip-no-local|${selector.take(140)}|${viewTraceKey(view)}", maxPerCategory = 1800) {
                "matched=false reason=missing_local_bounds selector=$selector hostId=${hostId ?: "none"} " +
                    "resource=${resourceName ?: "none"} host=${viewThemeLogDetails(view, full = true)}"
            }
            return false
        }
        val hostBounds = viewBoundsRect(view)
        if (hostBounds == null) {
            installVirtualThemeLayoutPatch(view, themeRuntime(), "host_unmeasured", selector)
            amoledTrace("virtual-theme-target", "skip-unmeasured|${selector.take(140)}|${viewTraceKey(view)}", maxPerCategory = 2200) {
                "matched=false reason=host_unmeasured selector=$selector hostId=${hostId ?: "none"} " +
                    "hostSelector=${hostSelector ?: "none"} hostClass=${hostClass ?: "none"} resource=${resourceName ?: "none"} " +
                    "localBounds=${rectToThemeString(localBounds)} attached=${view.isAttachedToWindow} laidOut=${view.isLaidOut} " +
                    "size=${view.width}x${view.height} host=${viewThemeLogDetails(view, full = true)}"
            }
            return false
        }
        val selectorScreenBounds = parseSnapchatVirtualBounds(snapchatVirtualSelectorValue(selector, "bounds"))
        val hostExpectedBounds = Rect(
            hostBounds.left + localBounds.left,
            hostBounds.top + localBounds.top,
            hostBounds.left + localBounds.right,
            hostBounds.top + localBounds.bottom
        )
        val expectedText = snapchatVirtualSelectorValue(selector, "text").orEmpty()
        val expectedDesc = snapchatVirtualSelectorValue(selector, "desc").orEmpty()
        val expectedViewId = snapchatVirtualSelectorValue(selector, "view_id").orEmpty()
        fun isStrongVirtualIdentity(value: String): Boolean {
            val clean = value.trim()
            if (clean.isBlank() || clean.equals("none", ignoreCase = true)) return false
            val lower = clean.lowercase()
            if (clean.matches(Regex("213\\d{6,}"))) return false
            if ("_" in clean) return false
            if (lower in setOf(
                    "avatar container",
                    "avatar_container",
                    "feed chat button",
                    "feed_chat_button",
                    "camera reply",
                    "snap",
                    "map",
                    "chat",
                    "camera",
                    "stories",
                    "spotlight"
                )
            ) {
                return false
            }
            return true
        }
        val strongVirtualIdentity = isStrongVirtualIdentity(expectedText) ||
            isStrongVirtualIdentity(expectedDesc) ||
            isStrongVirtualIdentity(expectedViewId)
        val screenBoundsTolerance = virtualScreenBoundsTolerance(localBounds, hostBounds)
        val hostScreenMatchesSelector = selectorScreenBounds?.let { selectorBounds ->
            closeEnoughRect(hostExpectedBounds, selectorBounds, tolerance = screenBoundsTolerance)
        } == true
        if (
            selectorScreenBounds != null &&
            !hostScreenMatchesSelector &&
            shouldGateVirtualSelectorByScreenBounds(resourceName, hostId, hostClass)
        ) {
            amoledTrace("virtual-theme-target", "skip-screen-mismatch|${selector.take(140)}|${viewTraceKey(view)}", maxPerCategory = 2600) {
                "matched=false reason=screen_bounds_mismatch selector=$selector hostId=${hostId ?: "none"} " +
                    "hostSelector=${hostSelector ?: "none"} hostClass=${hostClass ?: "none"} resource=${resourceName ?: "none"} " +
                    "hostBounds=${rectToThemeString(hostBounds)} localBounds=${rectToThemeString(localBounds)} " +
                    "hostExpectedBounds=${rectToThemeString(hostExpectedBounds)} selectorBounds=${rectToThemeString(selectorScreenBounds)} " +
                    "tolerance=$screenBoundsTolerance strongIdentity=$strongVirtualIdentity host=${viewThemeLogDetails(view, full = true)}"
            }
            return false
        }
        val provider = runCatching { view.accessibilityNodeProvider }.getOrNull()
        val roots = mutableListOf<AccessibilityNodeInfo>()
        provider?.let {
            runCatching { it.createAccessibilityNodeInfo(-1) }.getOrNull()?.let(roots::add)
        }
        runCatching { view.createAccessibilityNodeInfo() }.getOrNull()?.let(roots::add)
        if (roots.isEmpty()) {
            if (hostScreenMatchesSelector) {
                amoledTrace("virtual-theme-target", "host-screen-no-roots|${selector.take(140)}|${viewTraceKey(view)}", maxPerCategory = 2200) {
                    "matched=true reason=host_screen_bounds_without_accessibility_roots selector=$selector hostId=${hostId ?: "none"} " +
                        "hostSelector=${hostSelector ?: "none"} hostClass=${hostClass ?: "none"} resource=${resourceName ?: "none"} " +
                        "hostBounds=${rectToThemeString(hostBounds)} localBounds=${rectToThemeString(localBounds)} " +
                        "hostExpectedBounds=${rectToThemeString(hostExpectedBounds)} selectorBounds=${selectorScreenBounds?.let(::rectToThemeString) ?: "none"} " +
                        "tolerance=$screenBoundsTolerance strongIdentity=$strongVirtualIdentity provider=${provider?.javaClass?.name ?: "none"} " +
                        "host=${viewThemeLogDetails(view, full = true)}"
                }
                return true
            }
            amoledTrace("virtual-theme-target", "skip-no-roots|${selector.take(140)}|${viewTraceKey(view)}", maxPerCategory = 1800) {
                "matched=false reason=no_accessibility_roots selector=$selector hostId=${hostId ?: "none"} " +
                    "hostBounds=${rectToThemeString(hostBounds)} localBounds=${rectToThemeString(localBounds)} " +
                    "hostExpectedBounds=${rectToThemeString(hostExpectedBounds)} " +
                    "selectorBounds=${selectorScreenBounds?.let(::rectToThemeString) ?: "none"} " +
                    "hostScreenMatchesSelector=$hostScreenMatchesSelector tolerance=$screenBoundsTolerance " +
                    "provider=${provider?.javaClass?.name ?: "none"} " +
                    "host=${viewThemeLogDetails(view, full = true)}"
            }
            return false
        }
        var visited = 0
        var matched = false
        var matchedBoundsSource = "none"
        var matchedNodeBounds = "none"
        var matchedNodeText = "none"
        val rootSummaries = mutableListOf<String>()

        fun boundsFromAnchor(anchor: Rect, bounds: Rect): Rect {
            return Rect(
                anchor.left + bounds.left,
                anchor.top + bounds.top,
                anchor.left + bounds.right,
                anchor.top + bounds.bottom
            )
        }

        fun expectedBoundsCandidates(rootBounds: Rect?): List<Pair<String, Rect>> {
            val candidates = mutableListOf<Pair<String, Rect>>()
            if (strongVirtualIdentity) {
                candidates += "host_screen_plus_local" to hostExpectedBounds
            }
            selectorScreenBounds?.let { candidates += "selector_screen" to it }
            if (strongVirtualIdentity && rootBounds != null && !rootBounds.isEmpty) {
                candidates += "accessibility_root_plus_local" to boundsFromAnchor(rootBounds, localBounds)
            }
            return candidates
        }

        fun nodeMatches(node: AccessibilityNodeInfo, rootBounds: Rect?): Boolean {
            val bounds = Rect()
            runCatching { node.getBoundsInScreen(bounds) }
            if (bounds.isEmpty) return false
            val boundsMatch = expectedBoundsCandidates(rootBounds)
                .firstOrNull { (_, expected) -> closeEnoughRect(bounds, expected, tolerance = 6) }
                ?: return false
            val text = node.text?.toString().orEmpty().trim()
            val desc = node.contentDescription?.toString().orEmpty().trim()
            val viewId = runCatching { node.viewIdResourceName.orEmpty() }.getOrDefault("").trim()
            val textMatches = expectedText.isBlank() || text == expectedText
            val descMatches = expectedDesc.isBlank() || desc == expectedDesc
            val idMatches = expectedViewId.isBlank() || viewId == expectedViewId
            val matchedNode = textMatches && descMatches && idMatches
            if (matchedNode) {
                matchedBoundsSource = boundsMatch.first
                matchedNodeBounds = rectToThemeString(bounds)
                matchedNodeText = text.ifBlank { desc }.ifBlank { viewId }.ifBlank { "none" }
            }
            return matchedNode
        }

        fun walk(node: AccessibilityNodeInfo, depth: Int, rootBounds: Rect?) {
            if (matched || visited++ > 160 || depth > 10) return
            if (nodeMatches(node, rootBounds)) {
                matched = true
                return
            }
            for (index in 0 until node.childCount) {
                val child = resolveAccessibilityChildNode(node, index, provider) ?: continue
                try {
                    walk(child, depth + 1, rootBounds)
                } finally {
                    runCatching { child.recycle() }
                }
                if (matched) break
            }
        }

        roots.forEach { rootNode ->
            try {
                val rootBounds = Rect()
                runCatching { rootNode.getBoundsInScreen(rootBounds) }
                rootSummaries += "bounds=${if (rootBounds.isEmpty) "none" else rectToThemeString(rootBounds)} " +
                    "class=${rootNode.className ?: "none"} text=${rootNode.text ?: "none"} " +
                    "desc=${rootNode.contentDescription ?: "none"} children=${rootNode.childCount}"
                walk(rootNode, 0, rootBounds.takeIf { !it.isEmpty })
            } finally {
                runCatching { rootNode.recycle() }
            }
        }
        if (!matched && hostScreenMatchesSelector) {
            matched = true
            matchedBoundsSource = "host_screen_selector_fallback"
            matchedNodeBounds = rectToThemeString(hostExpectedBounds)
            matchedNodeText = snapchatVirtualThemeText(selector)
                .ifBlank { expectedDesc }
                .ifBlank { expectedViewId }
                .ifBlank { "screen-bound-fallback" }
        }

        amoledTrace("virtual-theme-target", "${matched}|${selector.take(140)}|${viewTraceKey(view)}", maxPerCategory = 1800) {
            "matched=$matched selector=$selector hostId=${hostId ?: "none"} hostSelector=${hostSelector ?: "none"} " +
                "hostClass=${hostClass ?: "none"} resource=${resourceName ?: "none"} hostBounds=${rectToThemeString(hostBounds)} " +
                "localBounds=${rectToThemeString(localBounds)} hostExpectedBounds=${rectToThemeString(hostExpectedBounds)} " +
                "selectorBounds=${selectorScreenBounds?.let(::rectToThemeString) ?: "none"} " +
                "strongIdentity=$strongVirtualIdentity hostScreenMatchesSelector=$hostScreenMatchesSelector " +
                "screenBoundsTolerance=$screenBoundsTolerance matchedBoundsSource=$matchedBoundsSource " +
                "matchedNodeBounds=$matchedNodeBounds matchedNodeText=$matchedNodeText " +
                "expectedText=${expectedText.ifBlank { "none" }} expectedDesc=${expectedDesc.ifBlank { "none" }} " +
                "expectedViewId=${expectedViewId.ifBlank { "none" }} provider=${provider?.javaClass?.name ?: "none"} " +
                "visited=$visited hiddenChildIdMethod=${accessibilityGetChildIdMethod != null} " +
                "hiddenVirtualIdMethod=${accessibilityGetVirtualDescendantIdMethod != null} roots=${rootSummaries.joinToString(" || ").ifBlank { "none" }}"
        }
        return matched
    }

    private fun virtualThemeOverlaysForView(view: View, runtime: ThemeRuntime): List<VirtualThemeOverlay> {
        val overlays = mutableListOf<VirtualThemeOverlay>()
        val preview = activePreviewRule()
        val virtualRuleCount = if (runtime.customEnabled) {
            runtime.selectorRules.count { isSnapchatVirtualThemeSelector(it.target) }
        } else {
            0
        }
        val resourceName = getResourceEntryName(view)
        if (runtime.customEnabled) {
            runtime.selectorRules
                .asSequence()
                .filter { isSnapchatVirtualThemeSelector(it.target) }
                .forEach { rule ->
                    if (snapchatVirtualSelectorHostMatches(view, rule.target)) {
                        parseSnapchatVirtualBounds(snapchatVirtualSelectorValue(rule.target, "local_bounds"))?.let { local ->
                            overlays += VirtualThemeOverlay(
                                selector = rule.target,
                                color = rule.color,
                                localBounds = local,
                                text = snapchatVirtualThemeText(rule.target).takeIf { isSnapchatVirtualTextThemeSelector(rule.target) }
                            )
                        }
                    }
                }
        }
        activePreviewRule()?.let { preview ->
            if (preview.selector && isSnapchatVirtualThemeSelector(preview.target) && snapchatVirtualSelectorHostMatches(view, preview.target)) {
                parseSnapchatVirtualBounds(snapchatVirtualSelectorValue(preview.target, "local_bounds"))?.let { local ->
                    overlays += VirtualThemeOverlay(
                        selector = preview.target,
                        color = preview.color,
                        localBounds = local,
                        text = snapchatVirtualThemeText(preview.target).takeIf { isSnapchatVirtualTextThemeSelector(preview.target) }
                    )
                }
            }
        }
        if (preview?.target?.let(::isSnapchatVirtualThemeSelector) == true || virtualRuleCount > 0) {
            val shouldLog = overlays.isNotEmpty() || !resourceName.isNullOrBlank()
            if (shouldLog) {
                amoledTrace("virtual-theme-overlays", "${viewTraceKey(view)}|rules=$virtualRuleCount|overlays=${overlays.size}", maxPerCategory = 2600) {
                    "virtual overlay evaluation previewVirtual=${preview?.target?.let(::isSnapchatVirtualThemeSelector) == true} " +
                        "runtimeVirtualRules=$virtualRuleCount overlays=${overlays.size} resource=${resourceName ?: "none"} " +
                        "items=${overlays.joinToString(" || ") { "${it.selector.take(220)} color=${colorHex(it.color)} local=${rectToThemeString(it.localBounds)} text=${it.text ?: "none"}" }} " +
                        "host=${viewThemeLogDetails(view, full = true)}"
                }
            }
        }
        if (overlays.size <= 1) return overlays
        val deduped = LinkedHashMap<String, VirtualThemeOverlay>()
        overlays.forEach { overlay -> deduped[overlay.selector] = overlay }
        val result = deduped.values.toList()
        if (result.size != overlays.size) {
            amoledTrace("virtual-theme-overlays", "dedupe|${viewTraceKey(view)}|${overlays.size}->${result.size}", maxPerCategory = 1400) {
                "deduped virtual overlays original=${overlays.size} deduped=${result.size} " +
                    "items=${result.joinToString(" || ") { "${it.selector.take(220)} color=${colorHex(it.color)} local=${rectToThemeString(it.localBounds)} text=${it.text ?: "none"}" }} " +
                    "host=${viewSummary(view)}"
            }
        }
        return result
    }

    private fun clearVirtualThemeOverlays(view: View): Boolean {
        val state = view.getTag(viewAppliedVirtualThemeOverlaysTag) as? VirtualThemeOverlayState ?: return false
        state.drawables.forEach { drawable ->
            runCatching { view.overlay.remove(drawable) }
        }
        view.setTag(viewAppliedVirtualThemeOverlaysTag, null)
        view.invalidate()
        amoledTrace("virtual-theme-apply", "clear|${viewTraceKey(view)}|count=${state.drawables.size}", maxPerCategory = 800) {
            "cleared virtual overlays count=${state.drawables.size} textDeferred=${state.textOverlayCount} ${viewSummary(view)}"
        }
        return state.drawables.isNotEmpty() || state.textOverlayCount > 0
    }

    private fun applyVirtualThemeOverlaysForView(view: View, runtime: ThemeRuntime = themeRuntime()): Boolean {
        val overlays = virtualThemeOverlaysForView(view, runtime)
        if (overlays.isEmpty()) {
            if (hasSnapchatVirtualThemeSelectors(runtime) && !getResourceEntryName(view).isNullOrBlank()) {
                amoledTrace("virtual-theme-apply", "empty|${viewTraceKey(view)}", maxPerCategory = 2200) {
                    "no virtual overlays for named host; clearing stale overlays if present " +
                        "runtimeVirtualRules=${runtime.selectorRules.count { isSnapchatVirtualThemeSelector(it.target) }} " +
                        "host=${viewThemeLogDetails(view, full = true)}"
                }
            }
            return clearVirtualThemeOverlays(view)
        }
        val signature = overlays.fold(17) { acc, overlay ->
            31 * acc + overlay.selector.hashCode() + overlay.color + overlay.localBounds.hashCode() + overlay.text.orEmpty().hashCode()
        }
        val oldState = view.getTag(viewAppliedVirtualThemeOverlaysTag) as? VirtualThemeOverlayState
        if (oldState?.signature == signature) return false
        oldState?.drawables?.forEach { drawable ->
            runCatching { view.overlay.remove(drawable) }
        }
        val textOverlays = overlays.filter { it.text != null }
        val drawables = overlays.map { overlay ->
            if (overlay.text != null) {
                VirtualTextThemeDrawable(
                    text = overlay.text,
                    color = overlay.color,
                    bold = snapchatVirtualSelectorValue(overlay.selector, "path") == "2" || overlay.localBounds.height() >= 56,
                    backgroundColor = Color.rgb(18, 18, 18),
                    textBounds = refinedSnapchatVirtualTextBounds(view, overlay.selector, overlay.localBounds)
                )
            } else {
                ColorDrawable(overlay.color).apply { bounds = overlay.localBounds }
            }
        }
        drawables.forEach { drawable -> view.overlay.add(drawable) }
        view.setTag(viewAppliedVirtualThemeOverlaysTag, VirtualThemeOverlayState(signature, drawables, textOverlays.size))
        view.invalidate()
        amoledTrace("virtual-theme-apply", "apply|${viewTraceKey(view)}|count=${overlays.size}|sig=$signature", maxPerCategory = 1200) {
            "applied virtual overlays count=${overlays.size} drawableCount=${drawables.size} textDeferred=${textOverlays.size} signature=$signature " +
                "items=${overlays.joinToString(" || ") { "${it.selector.take(180)} color=${colorHex(it.color)} local=${rectToThemeString(it.localBounds)} text=${it.text ?: "none"}" }} " +
                "host=${viewSummary(view)}"
        }
        return true
    }

    private fun applyVirtualThemeHostFromLifecycle(
        view: View,
        trigger: String,
        runtime: ThemeRuntime = themeRuntime(),
        scheduleRetry: Boolean = true
    ): Boolean {
        val selectors = snapchatVirtualThemeSelectorsForHost(view, runtime)
        if (selectors.isEmpty()) return false
        rememberVirtualThemeHost(view, selectors, trigger)
        val hostBounds = viewBoundsRect(view)
        if (hostBounds == null) {
            selectors.firstOrNull()?.let { selector ->
                installVirtualThemeLayoutPatch(view, runtime, "$trigger:host_not_ready", selector)
            }
            amoledTrace("virtual-theme-lifecycle", "not-ready|$trigger|${viewTraceKey(view)}", maxPerCategory = 2200) {
                "virtual lifecycle host not ready trigger=$trigger selectors=${selectors.size} " +
                    "attached=${view.isAttachedToWindow} laidOut=${view.isLaidOut} size=${view.width}x${view.height} " +
                    "host=${viewThemeLogDetails(view, full = true)}"
            }
            return false
        }

        val startedAt = SystemClock.uptimeMillis()
        val changed = applyVirtualThemeOverlaysForView(view, runtime)
        val elapsed = SystemClock.uptimeMillis() - startedAt
        val state = view.getTag(viewAppliedVirtualThemeOverlaysTag) as? VirtualThemeOverlayState
        val matched = state?.let { it.drawables.isNotEmpty() || it.textOverlayCount > 0 } == true
        amoledTrace("virtual-theme-lifecycle", "$trigger|matched=$matched|changed=$changed|${viewTraceKey(view)}", maxPerCategory = 3200) {
            "virtual lifecycle apply trigger=$trigger selectors=${selectors.size} matchedOverlay=$matched changed=$changed " +
                "elapsed=${elapsed}ms bounds=${rectToThemeString(hostBounds)} overlayCount=${state?.drawables?.size ?: 0} " +
                "textDeferred=${state?.textOverlayCount ?: 0} signature=${state?.signature ?: 0} selectors=${selectors.joinToString(" || ") { it.take(260) }} " +
                "host=${viewThemeLogDetails(view, full = true)}"
        }

        if (!matched && scheduleRetry && view.getTag(virtualThemeLifecycleRetryTag) != true) {
            view.setTag(virtualThemeLifecycleRetryTag, true)
            val accepted = view.postDelayed({
                view.setTag(virtualThemeLifecycleRetryTag, null)
                applyVirtualThemeHostFromLifecycle(
                    view = view,
                    trigger = "$trigger:delayed180",
                    runtime = themeRuntime(),
                    scheduleRetry = false
                )
            }, 180L)
            amoledTrace("virtual-theme-lifecycle", "retry|$trigger|accepted=$accepted|${viewTraceKey(view)}", maxPerCategory = 2200) {
                "scheduled virtual lifecycle retry trigger=$trigger accepted=$accepted " +
                    "selectors=${selectors.size} bounds=${rectToThemeString(hostBounds)} host=${viewSummary(view)}"
            }
        }
        return changed
    }

    private fun patchVirtualThemeHostTree(
        host: View,
        maxViews: Int = 360,
        maxRuntimeMs: Long = 6L,
        trigger: String = "tree"
    ): Int {
        val runtime = themeRuntime()
        if (!hasSnapchatVirtualThemeSelectors(runtime)) return 0
        val startedAt = SystemClock.uptimeMillis()
        var visited = 0
        var candidates = 0
        var changed = 0
        var viewLimitHit = false
        var timeLimitHit = false

        fun walk(view: View?) {
            if (view == null) return
            if (visited >= maxViews) {
                viewLimitHit = true
                return
            }
            if (SystemClock.uptimeMillis() - startedAt > maxRuntimeMs) {
                timeLimitHit = true
                return
            }
            visited++
            val selectors = snapchatVirtualThemeSelectorsForHost(view, runtime)
            if (selectors.isNotEmpty()) {
                candidates++
                if (applyVirtualThemeHostFromLifecycle(
                        view = view,
                        trigger = "$trigger:tree",
                        runtime = runtime,
                        scheduleRetry = true
                    )
                ) {
                    changed++
                }
            }
            if (view is android.view.ViewGroup) {
                for (index in 0 until view.childCount) {
                    if (visited >= maxViews || SystemClock.uptimeMillis() - startedAt > maxRuntimeMs) break
                    walk(view.getChildAt(index))
                }
            }
        }

        walk(host)
        amoledTrace("virtual-theme-tree", "$trigger|visited=$visited|candidates=$candidates|changed=$changed|${viewTraceKey(host)}", maxPerCategory = 1200) {
            "virtual host tree trigger=$trigger visited=$visited candidates=$candidates changed=$changed " +
                "elapsed=${SystemClock.uptimeMillis() - startedAt}ms maxViews=$maxViews budget=${maxRuntimeMs}ms " +
                "viewLimitHit=$viewLimitHit timeLimitHit=$timeLimitHit host=${viewThemeLogDetails(host, full = true)}"
        }
        return changed
    }

    private fun themeMatchForView(view: View, runtime: ThemeRuntime = themeRuntime()): ViewThemeMatch {
        val preview = activePreviewRule()
        val signature = 31 * runtime.viewRuleSignature + (preview?.signature ?: 0)
        val viewStateSignature = viewThemeStateSignature(view)
        val computedAtMs = SystemClock.uptimeMillis()
        (view.getTag(viewThemeMatchTag) as? ViewThemeMatch)?.let { cached ->
            if (cached.signature == signature && cached.viewStateSignature == viewStateSignature) {
                logThemeMatchCache(view, cached, viewStateSignature, "hit")
                return cached
            }
            logThemeMatchCache(view, cached, viewStateSignature, "stale")
        }

        if (!runtime.customEnabled && preview == null) {
            val match = ViewThemeMatch(signature, viewStateSignature, null, null, null, null, computedAtMs)
            logThemeMatchForView(view, runtime, match, "disabled")
            return match.also { view.setTag(viewThemeMatchTag, it) }
        }
        if (isExcludedAndroidView(view)) {
            val match = ViewThemeMatch(signature, viewStateSignature, null, null, null, null, computedAtMs)
            logThemeMatchForView(view, runtime, match, "excluded")
            return match.also { view.setTag(viewThemeMatchTag, it) }
        }

        val viewKeys = surfaceKeysForView(view)
        var color: Int? = null
        var colorSource: String? = null
        if (preview != null && !isSnapchatVirtualThemeSelector(preview.target) && matchesThemeTarget(view, preview.target, preview.selector)) {
            color = preview.color
            colorSource = "preview:${preview.target}"
        }
        if (color == null) {
            color = customCatalogColorForView(view, runtime)
            if (color != null) {
                val matchedKey = viewKeys.firstOrNull { runtime.surfaceOverrides.containsKey(it) }
                colorSource = "surface_override:${matchedKey ?: "preview_or_catalog"}"
            }
        }
        if (color == null && runtime.customEnabled) {
            if (runtime.globalRuleColors.isNotEmpty()) {
                val decision = globalBackgroundHostDecision(view)
                val match = runtime.globalRuleColors.entries.firstOrNull()
                if (decision.matched) {
                    color = match?.value
                    if (match != null) {
                        colorSource = "global_color_rule:${match.key}"
                        logThemeTargetEvaluation(view, match.key, false, true, "indexed_global_color_rule:${decision.reason}")
                    }
                } else if (match != null) {
                    logThemeTargetEvaluation(view, match.key, false, false, "indexed_global_color_rule:${decision.reason}")
                }
            }
            if (color == null && runtime.resourceRuleColors.isNotEmpty()) {
                val matchedKey = viewKeys.firstOrNull { it !in globalBackgroundCatalogTargets && runtime.resourceRuleColors.containsKey(it) }
                color = matchedKey?.let { runtime.resourceRuleColors[it] }
                if (matchedKey != null) {
                    colorSource = "resource_color_rule:$matchedKey"
                    logThemeTargetEvaluation(view, matchedKey, false, true, "indexed_resource_color_rule")
                }
            }
        }
        if (color == null && runtime.customEnabled && runtime.selectorRules.isNotEmpty()) {
            for (rule in runtime.selectorRules) {
                if (isSnapchatVirtualThemeSelector(rule.target)) continue
                if (matchesThemeTarget(view, rule.target, rule.selector)) {
                    color = rule.color
                    colorSource = "selector_color_rule:${rule.target}"
                    break
                }
            }
        }

        var backgroundPath: String? = null
        var backgroundSource: String? = null
        if (runtime.customEnabled) {
            if (runtime.globalBackgroundPaths.isNotEmpty()) {
                val decision = globalBackgroundHostDecision(view)
                val match = runtime.globalBackgroundPaths.entries.firstOrNull()
                if (decision.matched) {
                    backgroundPath = match?.value
                    if (match != null) {
                        backgroundSource = "global_background_rule:${match.key}"
                        logThemeTargetEvaluation(view, match.key, false, true, "indexed_global_background_rule:${decision.reason}")
                    }
                } else if (match != null) {
                    logThemeTargetEvaluation(view, match.key, false, false, "indexed_global_background_rule:${decision.reason}")
                }
            }
            if (backgroundPath == null && runtime.resourceBackgroundPaths.isNotEmpty()) {
                val matchedKey = viewKeys.firstOrNull { it !in globalBackgroundCatalogTargets && runtime.resourceBackgroundPaths.containsKey(it) }
                backgroundPath = matchedKey?.let { runtime.resourceBackgroundPaths[it] }
                if (matchedKey != null) {
                    backgroundSource = "resource_background_rule:$matchedKey"
                    logThemeTargetEvaluation(view, matchedKey, false, true, "indexed_resource_background_rule")
                }
            }
        }
        if (backgroundPath == null && runtime.customEnabled && runtime.selectorBackgrounds.isNotEmpty()) {
            for (background in runtime.selectorBackgrounds) {
                if (isSnapchatVirtualThemeSelector(background.target)) continue
                if (matchesThemeTarget(view, background.target, background.selector)) {
                    backgroundPath = background.path
                    backgroundSource = "selector_background_rule:${background.target}"
                    break
                }
            }
        }

        return ViewThemeMatch(
            signature = signature,
            viewStateSignature = viewStateSignature,
            color = color,
            backgroundPath = backgroundPath,
            colorSource = colorSource,
            backgroundSource = backgroundSource,
            computedAtMs = computedAtMs
        ).also {
            view.setTag(viewThemeMatchTag, it)
            logThemeMatchForView(view, runtime, it, "computed")
        }
    }

    private fun logThemeMatchForView(view: View, runtime: ThemeRuntime, match: ViewThemeMatch, reason: String) {
        val hasMatch = match.color != null || match.backgroundPath != null
        val key = if (hasMatch) {
            "match|$reason|${viewTraceKey(view)}|${colorHex(match.color)}|${match.backgroundPath.orEmpty()}|" +
                "${match.colorSource.orEmpty()}|${match.backgroundSource.orEmpty()}"
        } else {
            "miss|$reason|${viewClassTraceKey(view)}|sig=${match.signature}|state=${match.viewStateSignature}"
        }
        val matchAgeMs = SystemClock.uptimeMillis() - match.computedAtMs
        val previewTarget = activePreviewRule()?.target ?: "none"
        amoledTrace("theme-match", key, maxPerCategory = if (hasMatch) 1400 else 520) {
            "reason=$reason color=${colorHex(match.color)} background=${match.backgroundPath ?: "none"} " +
                "colorSource=${match.colorSource ?: "none"} backgroundSource=${match.backgroundSource ?: "none"} " +
                "signature=${match.signature} viewState=${match.viewStateSignature} computedAt=${match.computedAtMs} " +
                "age=${matchAgeMs}ms preview=$previewTarget " +
                "forceAmoled=${runtime.forceAmoled} customEnabled=${runtime.customEnabled} " +
                "rules=${runtime.rules.size} selectorRules=${runtime.selectorRules.size} resourceRules=${runtime.resourceRuleColors.size} " +
                "backgrounds=${runtime.backgrounds.size} selectorBackgrounds=${runtime.selectorBackgrounds.size} " +
                "resourceBackgrounds=${runtime.resourceBackgroundPaths.size} " +
                "catalogKeys=${surfaceKeysForView(view).joinToString()} details=${viewThemeLogDetails(view, full = true)}"
        }
    }

    private fun logThemeMatchCache(
        view: View,
        cached: ViewThemeMatch,
        currentViewStateSignature: Int,
        reason: String
    ) {
        val stale = cached.viewStateSignature != currentViewStateSignature
        val key = "$reason|stale=$stale|${viewClassTraceKey(view)}|cached=${cached.viewStateSignature}|current=$currentViewStateSignature"
        val cacheAgeMs = SystemClock.uptimeMillis() - cached.computedAtMs
        amoledTrace("theme-match-cache", key, maxPerCategory = if (stale) 1200 else 500) {
            "reason=$reason stale=$stale cachedSig=${cached.signature} cachedState=${cached.viewStateSignature} " +
                "currentState=$currentViewStateSignature cachedColor=${colorHex(cached.color)} cachedBackground=${cached.backgroundPath ?: "none"} " +
                "colorSource=${cached.colorSource ?: "none"} backgroundSource=${cached.backgroundSource ?: "none"} " +
                "cacheAge=${cacheAgeMs}ms details=${viewThemeLogDetails(view, full = true)}"
        }
    }

    private fun customColorForView(view: View, runtime: ThemeRuntime = themeRuntime()): Int? {
        if ((!runtime.customEnabled || (runtime.rules.isEmpty() && runtime.surfaceOverrides.isEmpty())) && activePreviewRule() == null) {
            return null
        }
        if (!shouldEvaluateCustomThemeForView(view, runtime)) return null
        return themeMatchForView(view, runtime).color
    }

    private fun customBackgroundForView(view: View, runtime: ThemeRuntime = themeRuntime()): String? {
        if (!runtime.customEnabled || runtime.backgrounds.isEmpty()) return null
        if (!shouldEvaluateCustomThemeForView(view, runtime)) return null
        return themeMatchForView(view, runtime).backgroundPath
    }

    private fun hasActivePatchesOrPreview(runtime: ThemeRuntime = themeRuntime()): Boolean {
        return runtime.hasActivePatches || activePreviewRule() != null
    }

    private fun amoledValueFor(original: Any): Any? {
        return when (original) {
            is Int -> amoledBlack
            is Long -> amoledBlack.toLong() and 0xFFFFFFFFL
            is Float -> (amoledBlack.toLong() and 0xFFFFFFFFL).toFloat()
            is Double -> (amoledBlack.toLong() and 0xFFFFFFFFL).toDouble()
            is String -> {
                val lower = original.trim().lowercase()
                when {
                    lower.startsWith("rgba") -> "rgba(0, 0, 0, 1)"
                    lower.startsWith("rgb") -> "rgb(0, 0, 0)"
                    lower.startsWith("0x") -> "0xff000000"
                    lower.startsWith("#") && lower.removePrefix("#").length == 8 && lower.endsWith("ff") -> "#000000ff"
                    lower.startsWith("#") && lower.removePrefix("#").length == 8 -> "#ff000000"
                    lower.startsWith("#") -> "#000000"
                    else -> null
                }
            }
            else -> null
        }
    }

    private fun containsContentToken(value: String): Boolean {
        val lower = value.lowercase()
        return contentTokens.any { it in lower }
    }

    private fun containsValdiDebugContentToken(value: String): Boolean {
        val lower = value.lowercase()
        return valdiDebugContentTokens.any { it in lower }
    }

    private fun isSurfaceAttributeName(attributeName: String): Boolean {
        val lower = attributeName.lowercase()
        if (listOf("text", "foreground", "icon", "tint", "stroke", "border", "outline", "shadow").any { it in lower }) {
            return false
        }
        return surfaceAttributeTokens.any { it in lower }
    }

    private fun isValdiSurfaceAttributeName(attributeName: String): Boolean {
        val lower = attributeName.lowercase()
        if (listOf("text", "foreground", "icon", "tint", "stroke", "border", "outline", "shadow").any { it in lower }) {
            return false
        }
        return isSurfaceAttributeName(attributeName) ||
            lower in valdiGenericSurfaceAttributes ||
            isMappedValdiSurfaceAttributeName(attributeName)
    }

    private fun isPatchableSurfaceToken(value: String): Boolean {
        val lower = value.trim().lowercase()
        if (lower.isEmpty()) return false
        if (parseColorString(lower) != null) return false
        if (lower in setOf("flatpureblack", "flatblack", "black", "clear", "flatclear", "transparent", "none")) {
            return false
        }
        if (listOf("flatclear", "transparent").any { it in lower }) return false
        if (containsContentToken(lower)) return false

        return isMappedValdiSurfaceToken(value) ||
            surfaceAttributeTokens.any { it in lower } ||
            listOf(
                "backgroundmain",
                "backgroundsubscreen",
                "backgroundobject",
                "backgroundobjectdown",
                "backgroundsurfacedown",
                "backgroundsurfaceup",
                "backgroundabovesurface",
                "backgrounddisabled",
                "darkgray",
                "darkgrey",
                "flatgray",
                "flatgrey",
                "gray900",
                "grey900",
                "gray850",
                "grey850",
                "gray800",
                "grey800"
            ).any { it in lower }
    }

    private fun shouldPatchAndroidAttr(attrId: Int, attrName: String?, originalColor: Int): Boolean {
        if (originalColor == amoledBlack) return false
        if (attrId in legacyKnownAttrIds) return true
        if (!isPatchableSurfaceOrOverlayColor(originalColor)) return false

        val name = (attrName ?: return true).lowercase()
        if (containsContentToken(name)) return false
        return isSurfaceAttributeName(name)
    }

    private fun patchTypedArray(result: TypedArray, attrIds: IntArray?) {
        val requestedAttrs = attrIds?.takeIf { it.isNotEmpty() } ?: return
        val typedArrayData = runCatching { result.getObjectField("mData") as IntArray }.getOrNull() ?: return
        val stride = (typedArrayData.size / requestedAttrs.size).takeIf { it >= 2 } ?: return
        amoledTrace("typed-array-request", "count=${requestedAttrs.size}|stride=$stride|hash=${requestedAttrs.contentHashCode()}", maxPerCategory = 420) {
            "typedArray request count=${requestedAttrs.size} dataSize=${typedArrayData.size} stride=$stride " +
                "attrs=${requestedAttrs.take(80).joinToString { attrId ->
                    val name = runCatching { context.androidContext.resources.getResourceEntryName(attrId) }.getOrNull()
                    "0x${attrId.toString(16)}:${name ?: "unknown"}"
                }}"
        }

        requestedAttrs.forEachIndexed { index, attrId ->
            val offset = index * stride
            if (offset + 1 >= typedArrayData.size) return@forEachIndexed
            val attrName = runCatching { context.androidContext.resources.getResourceEntryName(attrId) }.getOrNull()
            val dataType = typedArrayData[offset]
            if (dataType !in colorTypes) {
                amoledTrace("typed-array-skip", "non-color|0x${attrId.toString(16)}|${attrName.orEmpty()}|type=$dataType", maxPerCategory = 1000) {
                    "skip non-color typedArray attrId=0x${attrId.toString(16)} attrName=${attrName ?: "unknown"} " +
                        "index=$index offset=$offset type=$dataType stride=$stride"
                }
                return@forEachIndexed
            }

            val originalColor = runCatching { result.getColor(index, Int.MIN_VALUE) }.getOrNull()
                ?.takeIf { it != Int.MIN_VALUE }
                ?: run {
                    amoledTrace("typed-array-skip", "no-color|0x${attrId.toString(16)}|${attrName.orEmpty()}|type=$dataType", maxPerCategory = 1000) {
                        "skip getColor failed typedArray attrId=0x${attrId.toString(16)} attrName=${attrName ?: "unknown"} " +
                            "index=$index offset=$offset type=$dataType stride=$stride"
                    }
                    return@forEachIndexed
                }

            val runtime = themeRuntime()
            val customColor = customAndroidAttrColor(attrId, attrName)
            val shouldPatch = customColor != null ||
                (runtime.forceAmoled && shouldPatchAndroidAttr(attrId, attrName, originalColor))
            amoledTrace(
                category = "typed-array",
                key = "0x${attrId.toString(16)}|${attrName.orEmpty()}|${colorHex(originalColor)}|$shouldPatch|${colorHex(customColor)}",
                maxPerCategory = 900
            ) {
                "attrId=0x${attrId.toString(16)} attrName=${attrName ?: "unknown"} " +
                    "original=${colorHex(originalColor)} custom=${colorHex(customColor)} patch=$shouldPatch " +
                    "forceAmoled=${runtime.forceAmoled} type=${typedArrayData[offset]}"
            }
            val observableAttr = customColor != null ||
                shouldPatch ||
                attrId in legacyKnownAttrIds ||
                attrName?.let(::isSurfaceAttributeName) == true
            if (observableAttr) {
                observeAndroidAttrThemeSurface(
                    source = "typed-array",
                    attrId = attrId,
                    attrName = attrName,
                    originalColor = originalColor,
                    customColor = customColor,
                    shouldPatch = shouldPatch,
                    runtime = runtime
                )
            }
            if (!shouldPatch) return@forEachIndexed

            typedArrayData[offset + 1] = customColor ?: amoledSurfaceColorFor(originalColor)
            if (customColor == null && patchedAttrIds.add(attrId) && attrPatchLogCount < 24) {
                attrPatchLogCount++
                context.log.verbose(
                    "[AMOLED PATCH] Patched attrId 0x${attrId.toString(16)} (${attrName ?: "unknown"}) from 0x${originalColor.toUInt().toString(16)}"
                )
            }
        }
    }

    private fun getValdiClassName(handle: Long): String? {
        if (handle == 0L) {
            amoledTrace("valdi-class", "zero-handle", maxPerCategory = 120) {
                "skip class resolve for handle=0"
            }
            return null
        }
        synchronized(valdiClassNameCache) {
            if (valdiClassNameCache.containsKey(handle)) {
                val cached = valdiClassNameCache[handle]
                amoledTrace("valdi-class", "cache|$handle|${cached.orEmpty()}", maxPerCategory = 900) {
                    "cache hit handle=$handle class=${cached ?: "unknown"}"
                }
                return cached
            }
        }
        val className = runCatching { ValdiViewNode(handle).getClassName() }.getOrNull()
        synchronized(valdiClassNameCache) {
            valdiClassNameCache[handle] = className
        }
        amoledTrace("valdi-class", "resolve|$handle|${className.orEmpty()}", maxPerCategory = 1200) {
            "resolved handle=$handle class=${className ?: "unknown"}"
        }
        return className
    }

    private fun isExcludedValdiHandle(handle: Long, className: String?): Boolean {
        if (className != null && containsContentToken(className)) {
            amoledTrace("valdi-exclusion", "class-content|$className", maxPerCategory = 700) {
                "excluded by class content token handle=$handle class=$className"
            }
            return true
        }
        if (handle == 0L) {
            amoledTrace("valdi-exclusion", "zero-handle", maxPerCategory = 120) {
                "not excluded handle=0 class=${className ?: "unknown"}"
            }
            return false
        }

        synchronized(valdiContentHandleCache) {
            if (valdiContentHandleCache.containsKey(handle)) {
                val cached = valdiContentHandleCache[handle] == true
                amoledTrace("valdi-exclusion", "cache|$handle|$cached|${className.orEmpty()}", maxPerCategory = 900) {
                    "cache hit handle=$handle excluded=$cached class=${className ?: "unknown"}"
                }
                return cached
            }
        }

        val debugDescription = runCatching { ValdiViewNode(handle).toString() }.getOrNull()
        val excluded = debugDescription?.let(::containsValdiDebugContentToken) == true
        synchronized(valdiContentHandleCache) {
            valdiContentHandleCache[handle] = excluded
        }
        amoledTrace("valdi-exclusion", "resolve|$handle|$excluded|${className.orEmpty()}|${debugDescription?.take(120).orEmpty()}", maxPerCategory = 1200) {
            "resolved exclusion handle=$handle excluded=$excluded class=${className ?: "unknown"} " +
                "debug=${debugDescription?.replace('\n', ' ')?.take(900) ?: "none"}"
        }
        return excluded
    }

    private fun patchedValdiValue(
        runtime: ThemeRuntime,
        handle: Long,
        attributeName: String,
        value: Any?,
        className: String?
    ): Any? {
        if (value == null) return null
        if (!isValdiSurfaceAttributeName(attributeName)) return null
        if (isExcludedValdiHandle(handle, className)) return null

        return cachedPatchedValdiValue(runtime, attributeName, value, className)
    }

    private fun patchValdiSetAttributeArg(param: HookAdapter) {
        val runtime = themeRuntime()
        val canPatch = runtime.hasValdiPatches || activePreviewRule() != null
        val canObserve = shouldObserveThemeSurfaceMap(runtime)
        if (!canPatch && !canObserve) return
        val args = param.args()
        val handle = (args.getOrNull(0) as? Number)?.toLong() ?: 0L
        val attributeName = args.getOrNull(1) as? String ?: return
        val value = args.getOrNull(2) ?: return

        val className = getValdiClassName(handle)
        val surfaceCandidate = isValdiSurfaceAttributeName(attributeName)
        val excluded = surfaceCandidate && isExcludedValdiHandle(handle, className)
        val patched = if (canPatch && surfaceCandidate && !excluded) {
            patchedValdiValue(runtime, handle, attributeName, value, className)
        } else {
            null
        }
        logValdiAttributeDecision(
            source = "setValueForAttribute",
            handle = handle,
            className = className,
            attributeName = attributeName,
            value = value,
            patched = patched,
            surfaceCandidate = surfaceCandidate,
            excluded = excluded,
            runtime = runtime
        )
        observeValdiThemeSurface(
            source = "setValueForAttribute",
            handle = handle,
            className = className,
            attributeName = attributeName,
            value = value,
            patched = patched,
            surfaceCandidate = surfaceCandidate,
            excluded = excluded,
            runtime = runtime
        )
        if (patched == null) return
        param.setArg(2, patched)
        logValdiPatch("setValueForAttribute", className, attributeName, value)
    }

    private fun patchValdiGetAttributeResult(param: HookAdapter) {
        val runtime = themeRuntime()
        val canPatch = runtime.hasValdiPatches || activePreviewRule() != null
        val canObserve = shouldObserveThemeSurfaceMap(runtime)
        if (!canPatch && !canObserve) return
        val args = param.args()
        val handle = (args.getOrNull(0) as? Number)?.toLong() ?: 0L
        val attributeName = args.getOrNull(1) as? String ?: return
        val value = param.getResult() ?: return

        val className = getValdiClassName(handle)
        val surfaceCandidate = isValdiSurfaceAttributeName(attributeName)
        val excluded = surfaceCandidate && isExcludedValdiHandle(handle, className)
        val patched = if (canPatch && surfaceCandidate && !excluded) {
            patchedValdiValue(runtime, handle, attributeName, value, className)
        } else {
            null
        }
        logValdiAttributeDecision(
            source = "getValueForAttribute",
            handle = handle,
            className = className,
            attributeName = attributeName,
            value = value,
            patched = patched,
            surfaceCandidate = surfaceCandidate,
            excluded = excluded,
            runtime = runtime
        )
        observeValdiThemeSurface(
            source = "getValueForAttribute",
            handle = handle,
            className = className,
            attributeName = attributeName,
            value = value,
            patched = patched,
            surfaceCandidate = surfaceCandidate,
            excluded = excluded,
            runtime = runtime
        )
        if (patched == null) return
        param.setResult(patched)
        logValdiPatch("getValueForAttribute", className, attributeName, value)
    }

    private fun patchedRetainedValdiValue(
        runtime: ThemeRuntime,
        attributeName: String,
        value: Any?,
        className: String?
    ): Any? {
        if (value == null) return null
        if (!isValdiSurfaceAttributeName(attributeName)) return null
        if (className != null && containsContentToken(className)) return null

        return cachedPatchedValdiValue(runtime, attributeName, value, className)
    }

    private fun patchRetainedValdiNode(
        node: ValdiViewNode,
        className: String?,
        depth: Int,
        runtime: ThemeRuntime,
        stats: IntArray
    ) {
        if (className != null && containsContentToken(className)) {
            amoledTrace("valdi-retained-node", "skip-content|$className|$depth", maxPerCategory = 700) {
                "skip retained node depth=$depth class=$className reason=content_token"
            }
            return
        }
        logValdiNodeSnapshot("retainedLayout", node, className, depth)
        val handle = runCatching { (node.instanceNonNull() as? Number)?.toLong() }.getOrNull() ?: 0L

        for (attributeName in retainedValdiSurfaceAttributesForIndex()) {
            val original = runCatching { node.getAttribute(attributeName) }.getOrNull()
            if (original == null) {
                amoledTrace("valdi-retained-attr", "missing|${className.orEmpty()}|$attributeName|$depth", maxPerCategory = 900) {
                    "retained attr missing depth=$depth handle=$handle class=${className ?: "unknown"} attr=$attributeName"
                }
                continue
            }
            val surfaceCandidate = isValdiSurfaceAttributeName(attributeName)
            val patched = patchedRetainedValdiValue(runtime, attributeName, original, className)
            logValdiAttributeDecision(
                source = "retainedLayout",
                handle = handle,
                className = className,
                attributeName = attributeName,
                value = original,
                patched = patched,
                surfaceCandidate = surfaceCandidate,
                excluded = false,
                runtime = runtime
            )
            observeValdiThemeSurface(
                source = "retainedLayout",
                handle = handle,
                className = className,
                attributeName = attributeName,
                value = original,
                patched = patched,
                surfaceCandidate = surfaceCandidate,
                excluded = false,
                runtime = runtime
            )
            if (patched == null) continue
            if (patched == original) {
                amoledTrace("valdi-retained-attr", "same|${className.orEmpty()}|$attributeName|${original.toString().take(120)}", maxPerCategory = 900) {
                    "retained attr unchanged because patched value equals original handle=$handle class=${className ?: "unknown"} " +
                        "attr=$attributeName value=${valueSummary(original)}"
                }
                continue
            }
            if (runCatching { node.setAttribute(attributeName, patched) }.isSuccess) {
                stats[0]++
                logValdiPatch("retainedLayout", className, attributeName, original)
            } else {
                amoledTrace("valdi-retained-attr", "set-failed|${className.orEmpty()}|$attributeName|${original.toString().take(120)}", maxPerCategory = 900) {
                    "retained attr set failed handle=$handle class=${className ?: "unknown"} " +
                        "attr=$attributeName original=${valueSummary(original)} patched=${valueSummary(patched)}"
                }
            }
        }
    }

    private fun patchRetainedValdiTree(host: View, maxNodes: Int = 96, maxRuntimeMs: Long = 4L): Int {
        val runtime = themeRuntime()
        if (!runtime.hasValdiPatches && activePreviewRule() == null) return 0
        if (isExcludedAndroidView(host)) return 0
        val rootNode = host.getValdiViewNode() ?: return 0
        val startedAt = SystemClock.uptimeMillis()
        val stats = intArrayOf(0)
        var visited = 0
        var maxDepthSeen = 0
        var nodeLimitHit = false
        var timeLimitHit = false
        var depthLimitHit = false
        var skippedContentNodes = 0
        var childEdgesSeen = 0
        var childLookupFailures = 0

        fun walk(node: ValdiViewNode, depth: Int) {
            if (visited >= maxNodes) {
                if (!nodeLimitHit) {
                    nodeLimitHit = true
                    amoledTrace("valdi-retained-stop", "node-limit|$maxNodes|$depth|$visited", maxPerCategory = 360, unique = false) {
                        "retained walk stopped by node cap maxNodes=$maxNodes visited=$visited depth=$depth " +
                            "class=${runCatching { node.getClassName() }.getOrNull() ?: "unknown"} debug=${valdiNodeDebugSummary(node)}"
                    }
                }
                return
            }
            if (depth > 18) {
                if (!depthLimitHit) {
                    depthLimitHit = true
                    amoledTrace("valdi-retained-stop", "depth-limit|$depth|$visited", maxPerCategory = 360, unique = false) {
                        "retained walk stopped by depth cap depth=$depth visited=$visited maxNodes=$maxNodes " +
                            "class=${runCatching { node.getClassName() }.getOrNull() ?: "unknown"} debug=${valdiNodeDebugSummary(node)}"
                    }
                }
                return
            }
            val elapsedBefore = SystemClock.uptimeMillis() - startedAt
            if (elapsedBefore > maxRuntimeMs) {
                if (!timeLimitHit) {
                    timeLimitHit = true
                    amoledTrace("valdi-retained-stop", "time-limit|${maxRuntimeMs}ms|$elapsedBefore|$visited|$depth", maxPerCategory = 360, unique = false) {
                        "retained walk stopped by time cap budget=${maxRuntimeMs}ms elapsed=${elapsedBefore}ms " +
                            "visited=$visited depth=$depth maxNodes=$maxNodes " +
                            "class=${runCatching { node.getClassName() }.getOrNull() ?: "unknown"} debug=${valdiNodeDebugSummary(node)}"
                    }
                }
                return
            }
            visited++
            if (depth > maxDepthSeen) maxDepthSeen = depth

            val className = runCatching { node.getClassName() }.getOrNull()
            if (className != null && containsContentToken(className)) {
                skippedContentNodes++
                amoledTrace("valdi-retained-node", "walk-skip-content|$className|$depth", maxPerCategory = 700) {
                    "skip retained walk node depth=$depth class=$className visited=$visited reason=content_token"
                }
                return
            }
            amoledTrace("valdi-retained-node", "walk|${className.orEmpty()}|$depth|$visited", maxPerCategory = 1600) {
                "walk retained node depth=$depth visited=$visited class=${className ?: "unknown"} debug=${valdiNodeDebugSummary(node)}"
            }
            patchRetainedValdiNode(node, className, depth, runtime, stats)

            val children = runCatching { node.getChildren() }
                .onFailure {
                    childLookupFailures++
                    amoledTrace("valdi-retained-stop", "children-failed|${className.orEmpty()}|$depth|$visited", maxPerCategory = 360) {
                        "retained child lookup failed depth=$depth visited=$visited class=${className ?: "unknown"} error=${it.message} " +
                            "debug=${valdiNodeDebugSummary(node)}"
                    }
                }
                .getOrDefault(emptyList())
            childEdgesSeen += children.size
            for (child in children) {
                if (visited >= maxNodes || SystemClock.uptimeMillis() - startedAt > maxRuntimeMs) break
                walk(child, depth + 1)
            }
        }

        walk(rootNode, 0)
        logAndroidTreePass(
            source = "retained-valdi",
            host = host,
            maxItems = maxNodes,
            maxRuntimeMs = maxRuntimeMs,
            visited = visited,
            changed = stats[0],
            elapsedMs = SystemClock.uptimeMillis() - startedAt,
            extra = "nodeLimitHit=$nodeLimitHit timeLimitHit=$timeLimitHit depthLimitHit=$depthLimitHit " +
                "maxDepth=$maxDepthSeen skippedContentNodes=$skippedContentNodes childEdges=$childEdgesSeen " +
                "childLookupFailures=$childLookupFailures"
        )
        return stats[0]
    }

    private fun androidSurfaceRejectionReason(view: View, allowPreLayout: Boolean = false): String? {
        if (isExcludedAndroidView(view)) return "excluded_content_or_media_ancestor"
        if (isSmallInteractiveControl(view)) return "small_interactive_control"
        if (isInsideImagePreview(view)) return "inside_image_preview"
        if (isNamedAndroidSurface(view)) return null

        val width = view.width
        val height = view.height
        if ((width <= 0 || height <= 0) && allowPreLayout) {
            return if (shouldPatchPreLayoutSurface(view)) null else "prelayout_not_surface"
        }
        if (width < 96 || height < 48) return "too_small_${width}x$height"
        if (view.alpha < 0.98f) return "transparent_alpha_${view.alpha}"

        val root = view.rootView
        val rootHeight = root?.height?.takeIf { it > 0 } ?: return "missing_root_height"
        if (height > (rootHeight * 0.42f).toInt()) return "too_tall_for_surface_$height/$rootHeight"
        if (view.isClickable && width < 180 && height < 180) return "small_clickable"
        if (height > (rootHeight * 0.18f).toInt() && hasContentDescendant(view)) return "large_content_container"

        return null
    }

    private fun shouldPatchExistingAndroidSurface(view: View, allowPreLayout: Boolean = false): Boolean {
        return androidSurfaceRejectionReason(view, allowPreLayout) == null
    }

    private fun valdiDebugDescriptionFor(view: View): String {
        (view.getTag(viewValdiDebugDescriptionTag) as? String)?.let { return it }
        val description = runCatching { view.getValdiViewNode()?.toString() }
            .getOrNull()
            .orEmpty()
            .replace('\n', ' ')
            .replace(Regex("\\s+"), " ")
        view.setTag(viewValdiDebugDescriptionTag, description)
        return description
    }

    private fun isPreLayoutValdiSurface(view: View): Boolean {
        val description = valdiDebugDescriptionFor(view)
        if (description.isBlank()) return false
        val lower = description.lowercase()
        if (containsValdiDebugContentToken(lower)) return false
        return valdiDebugPatchableSurfaceReason(lower) != null ||
            namedAndroidSurfaceTokens.any { it in lower }
    }

    private fun valdiDebugAttributeValue(lower: String, attributeName: String): String? {
        val equalsToken = "$attributeName=\""
        val equalsIndex = lower.indexOf(equalsToken)
        if (equalsIndex >= 0) {
            val start = equalsIndex + equalsToken.length
            val end = lower.indexOf('"', start).takeIf { it >= start } ?: return null
            return lower.substring(start, end).trim()
        }

        val bareToken = "$attributeName="
        val bareIndex = lower.indexOf(bareToken)
        if (bareIndex < 0) return null
        val start = bareIndex + bareToken.length
        val end = lower.indexOfAny(charArrayOf(' ', '>', '/'), start).takeIf { it >= start } ?: lower.length
        return lower.substring(start, end).trim().trim('"', '\'')
    }

    private fun isPatchableValdiDebugSurfaceValue(value: String): Boolean {
        if (value.isBlank()) return false
        parseColorString(value)?.let { color ->
            return isPatchableSurfaceOrOverlayColor(color)
        }
        return isPatchableSurfaceToken(value)
    }

    private fun valdiDebugPatchableSurfaceReason(lower: String): String? {
        for (attributeName in valdiDebugSurfaceAttributes) {
            val value = valdiDebugAttributeValue(lower, attributeName) ?: continue
            if (isPatchableValdiDebugSurfaceValue(value)) {
                return "valdi_debug_attr=$attributeName value=${value.take(80)}"
            }
            return null
        }
        return null
    }

    private fun retainedValdiScanGate(view: View): RetainedValdiScanGate {
        val debugDescription by lazy { valdiDebugDescriptionFor(view) }
        if (view.getValdiViewNode() == null) {
            return RetainedValdiScanGate(false, "skip_retained_valdi_no_node", "")
        }
        if (isExcludedAndroidView(view)) {
            return RetainedValdiScanGate(false, "skip_retained_valdi_content_or_media_ancestor", debugDescription)
        }
        if (isSmallInteractiveControl(view)) {
            return RetainedValdiScanGate(false, "skip_retained_valdi_small_interactive_control", debugDescription)
        }
        if (isInsideImagePreview(view)) {
            return RetainedValdiScanGate(false, "skip_retained_valdi_inside_image_preview", debugDescription)
        }

        val name = viewMatchName(view)
        if (containsContentToken(name)) {
            return RetainedValdiScanGate(false, "skip_retained_valdi_content_name", debugDescription)
        }
        if (isAndroidContentClassName(name)) {
            return RetainedValdiScanGate(false, "skip_retained_valdi_android_content_class", debugDescription)
        }
        if (namedAndroidSurfaceTokens.any { it in name }) {
            return RetainedValdiScanGate(true, "named_android_surface_name", debugDescription)
        }
        surfaceAttributeTokens.firstOrNull { it in name }?.let { token ->
            return RetainedValdiScanGate(true, "android_surface_name_token=$token", debugDescription)
        }

        val description = debugDescription
        if (description.isBlank()) {
            return RetainedValdiScanGate(false, "skip_retained_valdi_blank_debug", description)
        }
        val lower = description.lowercase()
        if (containsValdiDebugContentToken(lower)) {
            return RetainedValdiScanGate(false, "skip_retained_valdi_debug_content_token", description)
        }
        namedAndroidSurfaceTokens.firstOrNull { it in lower }?.let { token ->
            return RetainedValdiScanGate(true, "valdi_debug_named_token=$token", description)
        }
        valdiDebugPatchableSurfaceReason(lower)?.let { reason ->
            return RetainedValdiScanGate(true, reason, description)
        }
        return RetainedValdiScanGate(false, "skip_retained_valdi_no_surface_attribute", description)
    }

    private fun logRetainedValdiScanGate(source: String, view: View, gate: RetainedValdiScanGate, reason: String) {
        val key = if (gate.allowed) {
            "$source|allow|${gate.reason}|${viewTraceKey(view)}"
        } else {
            "$source|skip|${gate.reason}|${viewClassTraceKey(view)}"
        }
        amoledTrace("valdi-retained-gate", key, maxPerCategory = if (gate.allowed) 900 else 700) {
            "source=$source reason=$reason allowed=${gate.allowed} gate=${gate.reason} " +
                "debug=${gate.debugDescription.take(700)} view=${viewSummary(view)}"
        }
    }

    private fun retainedLayoutScanSkipReason(view: View, gate: RetainedValdiScanGate): String? {
        val width = view.width
        val height = view.height
        if (width <= 0 || height <= 0) return "zero_size_after_layout_${width}x$height"
        if (view.alpha < 0.98f) return "transparent_alpha_${view.alpha}"
        if (width < 96 || height < 48) return "tiny_surface_${width}x$height"

        val root = view.rootView
        val displayMetrics = context.androidContext.resources.displayMetrics
        val rootWidth = root?.width?.takeIf { it > 0 } ?: displayMetrics.widthPixels
        val rootHeight = root?.height?.takeIf { it > 0 } ?: displayMetrics.heightPixels
        val lowerReason = gate.reason.lowercase()
        val simpleSurfaceGate = lowerReason.startsWith("valdi_debug_attr=") ||
            lowerReason.startsWith("valdi_debug_named_token=") ||
            lowerReason == "named_android_surface_name" ||
            lowerReason.startsWith("android_surface_name_token=")
        val compactSurfaceWidth = width >= (rootWidth * 0.40f).toInt()
        val rowLike = compactSurfaceWidth && height in 48..220
        val shallowBand = width >= (rootWidth * 0.70f).toInt() && height <= (rootHeight * 0.10f).toInt()
        if (rowLike && simpleSurfaceGate) return "simple_row_surface_android_patch_sufficient_${width}x$height"
        if (shallowBand && simpleSurfaceGate) return "simple_band_surface_android_patch_sufficient_${width}x$height"

        val largeContainer = width >= (rootWidth * 0.70f).toInt() && height >= (rootHeight * 0.25f).toInt()
        val collectionGate = lowerReason.contains("select_recipients_") ||
            lowerReason.contains("recipient_bar") ||
            lowerReason.contains("tabs_header_item")
        if (largeContainer && collectionGate) {
            return "collection_container_android_patch_sufficient_${width}x$height"
        }
        val compactCollection = compactSurfaceWidth && height <= (rootHeight * 0.18f).toInt()
        if (compactCollection && collectionGate) {
            return "compact_collection_surface_android_patch_sufficient_${width}x$height"
        }

        return null
    }

    private fun shouldPatchPreLayoutSurface(view: View): Boolean {
        if (view.alpha < 0.98f) return false
        val name = viewMatchName(view)
        if (containsContentToken(name)) return false
        if (isAndroidContentClassName(name)) return false
        if (controlSurfaceTokens.any { it in name }) return false
        if (namedAndroidSurfaceTokens.any { it in name }) return true
        if (surfaceAttributeTokens.any { it in name }) return true

        val background = view.background ?: return false
        if (!background.javaClass.name.startsWith("android.") && isPreLayoutValdiSurface(view)) return true
        return drawableMayRepresentSurface(background)
    }

    private fun isEmptySurfaceWithoutPatchableMaterial(view: View): Boolean {
        if (view.background != null) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && view.backgroundTintList != null) return false
        if (hasVisibleForeground(view)) return false
        if (view.width > 0 && view.height > 0 && view.isAttachedToWindow && view.isLaidOut) return false
        return true
    }

    private fun hasVisibleForeground(view: View): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        val foreground = view.foreground ?: return false
        return foreground !is ColorDrawable || Color.alpha(foreground.color) != 0
    }

    private fun hasOverlayPaintCandidate(view: View): Boolean {
        if (hasVisibleDrawablePaint(view.background) || hasVisibleForeground(view)) return true
        if (view !is android.view.ViewGroup) return false
        val limit = view.childCount.coerceAtMost(8)
        for (index in 0 until limit) {
            val child = view.getChildAt(index) ?: continue
            if (hasVisibleDrawablePaint(child.background) || hasVisibleForeground(child)) return true
        }
        return false
    }

    private fun shouldScanImagePreviewOverlay(host: View): Boolean {
        if (!themeRuntime().forceAmoled) return false
        if (!hasOverlayPaintCandidate(host)) {
            amoledTrace("image-preview", "skip-no-paint|${viewClassTraceKey(host)}", maxPerCategory = 260) {
                "skip overlay scan: no background/foreground candidate ${viewSummary(host)}"
            }
            return false
        }
        val name = viewMatchName(host)
        val likelyPreview = containsContentToken(name) ||
            "preview" in name ||
            "image" in name ||
            "photo" in name ||
            "thumbnail" in name ||
            "rounded" in name ||
            isInsideImagePreview(host)
        if (!likelyPreview) {
            amoledTrace("image-preview", "skip-not-preview|${viewClassTraceKey(host)}", maxPerCategory = 260) {
                "skip overlay scan: paint exists but no preview ancestry/name ${viewSummary(host)}"
            }
        }
        return likelyPreview
    }

    private fun drawableMayRepresentSurface(drawable: Drawable?): Boolean {
        return drawableContainsPatchableSurfacePaint(drawable)
    }

    private fun logBackgroundArgGate(
        kind: String,
        view: View,
        payload: String,
        allowed: Boolean,
        reason: String
    ) {
        val key = if (allowed) {
            "allow|$kind|$payload|${viewTraceKey(view)}"
        } else {
            "skip|$kind|$reason|$payload|${viewClassTraceKey(view)}"
        }
        amoledTrace("background-arg-gate", key, maxPerCategory = if (allowed) 1600 else 900) {
            "kind=$kind allowed=$allowed reason=$reason payload=$payload ${viewSummary(view)}"
        }
    }

    private fun shouldPatchBackgroundColorArgAsSurface(view: View, color: Int): Boolean {
        if (!isPatchableSurfaceOrOverlayColor(color)) {
            logBackgroundArgGate("color", view, colorHex(color), false, "unpatchable_color")
            return false
        }
        if (isExcludedAndroidView(view)) {
            logBackgroundArgGate("color", view, colorHex(color), false, "excluded_content_or_media_ancestor")
            return false
        }
        val name = viewMatchName(view)
        if (containsContentToken(name)) {
            logBackgroundArgGate("color", view, colorHex(color), false, "content_name")
            return false
        }
        if (isAndroidContentClassName(name)) {
            logBackgroundArgGate("color", view, colorHex(color), false, "content_class_name")
            return false
        }
        if (isSmallInteractiveControl(view)) {
            logBackgroundArgGate("color", view, colorHex(color), false, "small_interactive_control")
            return false
        }
        if (isInsideImagePreview(view)) {
            logBackgroundArgGate("color", view, colorHex(color), false, "inside_image_preview")
            return false
        }
        logBackgroundArgGate("color", view, colorHex(color), true, "patchable_surface_color")
        return true
    }

    private fun shouldPatchBackgroundArgAsSurface(view: View, drawable: Drawable?): Boolean {
        if (drawable == null) {
            logBackgroundArgGate("drawable", view, "null", false, "null_drawable")
            return false
        }
        val drawableText = drawableSummary(drawable)
        if (isExcludedAndroidView(view)) {
            logBackgroundArgGate("drawable", view, drawableText, false, "excluded_content_or_media_ancestor")
            return false
        }
        val name = viewMatchName(view)
        if (containsContentToken(name)) {
            logBackgroundArgGate("drawable", view, drawableText, false, "content_name")
            return false
        }
        if (isAndroidContentClassName(name)) {
            logBackgroundArgGate("drawable", view, drawableText, false, "content_class_name")
            return false
        }
        if (isSmallInteractiveControl(view)) {
            logBackgroundArgGate("drawable", view, drawableText, false, "small_interactive_control")
            return false
        }
        if (isInsideImagePreview(view)) {
            logBackgroundArgGate("drawable", view, drawableText, false, "inside_image_preview")
            return false
        }
        if (isNamedAndroidSurface(view)) {
            logBackgroundArgGate("drawable", view, drawableText, true, "named_android_surface")
            return true
        }
        if (shouldPatchPreLayoutSurface(view)) {
            logBackgroundArgGate("drawable", view, drawableText, true, "prelayout_surface")
            return true
        }
        val drawableSurface = drawableMayRepresentSurface(drawable)
        logBackgroundArgGate("drawable", view, drawableText, drawableSurface, if (drawableSurface) "drawable_surface" else "drawable_not_surface")
        return drawableSurface
    }

    private fun logAndroidAttachGate(view: View?, runtime: ThemeRuntime, allowed: Boolean, reason: String) {
        val key = if (view == null) {
            "null|$reason"
        } else if (allowed) {
            "allow|$reason|${viewTraceKey(view)}"
        } else {
            "skip|$reason|${viewClassTraceKey(view)}"
        }
        amoledTrace("android-attach-gate", key, maxPerCategory = if (allowed) 1400 else 800) {
            "allowed=$allowed reason=$reason preview=${activePreviewRule()?.target ?: "none"} " +
                "forceAmoled=${runtime.forceAmoled} customEnabled=${runtime.customEnabled} " +
                "rules=${runtime.rules.size} backgrounds=${runtime.backgrounds.size} " +
                "view=${view?.let(::viewSummary) ?: "null"}"
        }
    }

    private fun shouldRunAndroidAttachPatch(view: View?, runtime: ThemeRuntime): Boolean {
        if (view == null) {
            logAndroidAttachGate(null, runtime, false, "null_view")
            return false
        }
        if (isExcludedAndroidView(view)) {
            logAndroidAttachGate(view, runtime, false, "excluded_content_or_media_ancestor")
            return false
        }
        if (isSmallInteractiveControl(view)) {
            logAndroidAttachGate(view, runtime, false, "small_interactive_control")
            return false
        }
        val name = viewMatchName(view)
        if (containsContentToken(name)) {
            logAndroidAttachGate(view, runtime, false, "content_name")
            return false
        }
        if (isAndroidContentClassName(name)) {
            logAndroidAttachGate(view, runtime, false, "content_class_name")
            return false
        }
        val hasSpecificCustomTargets = hasSpecificCustomViewTargets(runtime)
        if (activePreviewRule() != null || hasSpecificCustomTargets) {
            val zeroSized = view.width <= 0 || view.height <= 0
            val hasEarlySurfaceSignal = isNamedAndroidSurface(view) ||
                shouldPatchPreLayoutSurface(view) ||
                drawableMayRepresentSurface(view.background) ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP &&
                    view.backgroundTintList?.defaultColor?.let(::isPatchableSurfaceOrOverlayColor) == true)
            if (zeroSized && !hasEarlySurfaceSignal) {
                logAndroidAttachGate(
                    view,
                    runtime,
                    false,
                    if (activePreviewRule() != null) {
                        "preview_zero_size_wait_for_surface_signal"
                    } else {
                        "specific_custom_zero_size_wait_for_surface_signal"
                    }
                )
                return false
            }
        }
        if (hasGlobalCustomTargets(runtime)) {
            val decision = globalBackgroundHostDecision(view)
            if (decision.matched) {
                logAndroidAttachGate(view, runtime, true, "global_custom_host:${decision.reason}")
                return true
            }
            if (!runtime.forceAmoled) {
                logAndroidAttachGate(view, runtime, false, "global_custom_skip:${decision.reason}")
                return false
            }
        }
        if (isEmptySurfaceWithoutPatchableMaterial(view) && isNamedAndroidSurface(view)) {
            logAndroidAttachGate(view, runtime, false, "empty_surface_no_material")
            return false
        }
        if (isNamedAndroidSurface(view)) {
            logAndroidAttachGate(view, runtime, true, "named_android_surface")
            return true
        }
        if (shouldPatchPreLayoutSurface(view)) {
            logAndroidAttachGate(view, runtime, true, "prelayout_surface")
            return true
        }
        if (drawableMayRepresentSurface(view.background)) {
            logAndroidAttachGate(view, runtime, true, "background_drawable_surface")
            return true
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && view.backgroundTintList?.defaultColor?.let(::isPatchableSurfaceOrOverlayColor) == true) {
            logAndroidAttachGate(view, runtime, true, "background_tint_surface_${colorHex(view.backgroundTintList?.defaultColor)}")
            return true
        }
        logAndroidAttachGate(view, runtime, false, "no_surface_signal")
        return false
    }

    private fun shouldInstallValdiLayoutPatchFor(view: View): Boolean {
        if (view.getValdiViewNode() == null) return false
        val gate = retainedValdiScanGate(view)
        logRetainedValdiScanGate("shouldInstallValdiLayoutPatchFor", view, gate, "candidate")
        return gate.allowed
    }

    private fun copyThemeDrawable(drawable: Drawable?): Drawable? {
        drawable ?: return null
        return runCatching { drawable.constantState?.newDrawable(context.androidContext.resources)?.mutate() }.getOrNull()
            ?: runCatching { drawable.constantState?.newDrawable()?.mutate() }.getOrNull()
    }

    private fun applyCustomThemeColorToDrawable(drawable: Drawable?, color: Int, reason: String, depth: Int = 0): Boolean {
        drawable ?: return false
        if (depth > 5) {
            amoledTrace("custom-drawable-color", "skip-depth|$reason|${drawable.javaClass.name}", maxPerCategory = 700) {
                "skip custom drawable recolor depth=$depth reason=$reason color=${colorHex(color)} drawable=${drawableSummary(drawable)}"
            }
            return false
        }
        val before = drawableSummary(drawable)
        val changed = when (drawable) {
            is ColorDrawable -> runCatching {
                drawable.mutate()
                drawable.color = color
            }.isSuccess
            is GradientDrawable -> runCatching {
                drawable.mutate()
                drawable.setColor(color)
            }.isSuccess
            is ShapeDrawable -> runCatching {
                drawable.mutate()
                drawable.paint.color = color
            }.isSuccess
            is StateListDrawable -> applyCustomThemeColorToStateListDrawable(drawable, color, reason, depth)
            is RippleDrawable -> applyCustomThemeColorToLayerDrawable(drawable, color, reason, depth)
            is LayerDrawable -> applyCustomThemeColorToLayerDrawable(drawable, color, reason, depth)
            is InsetDrawable -> runCatching { drawable.drawable }.getOrNull()?.let {
                applyCustomThemeColorToDrawable(it, color, "$reason:inset", depth + 1)
            } == true
            else -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                runCatching {
                    drawable.mutate()
                    drawable.setTintList(ColorStateList.valueOf(color))
                }.isSuccess
            } else {
                false
            }
        }
        if (changed) {
            runCatching { drawable.invalidateSelf() }
            amoledTrace("custom-drawable-color", "$reason|${drawable.javaClass.name}|${colorHex(color)}", maxPerCategory = 1800) {
                "custom drawable recolor reason=$reason color=${colorHex(color)} before=$before after=${drawableSummary(drawable)}"
            }
        } else {
            amoledTrace("custom-drawable-color", "skip|$reason|${drawable.javaClass.name}|${colorHex(color)}", maxPerCategory = 1000) {
                "custom drawable recolor skipped reason=$reason color=${colorHex(color)} drawable=$before"
            }
        }
        return changed
    }

    private fun applyCustomThemeColorToLayerDrawable(drawable: LayerDrawable, color: Int, reason: String, depth: Int): Boolean {
        var changed = false
        for (index in 0 until drawable.numberOfLayers) {
            val layerId = runCatching { drawable.getId(index) }.getOrNull()
            if (layerId == android.R.id.mask) {
                amoledTrace("custom-drawable-color", "skip-mask|$reason|${drawable.javaClass.name}|$index", maxPerCategory = 600) {
                    "skip mask layer while custom recoloring reason=$reason index=$index drawable=${drawableSummary(drawable)}"
                }
                continue
            }
            val layer = runCatching { drawable.getDrawable(index) }.getOrNull()
            val layerChanged = applyCustomThemeColorToDrawable(layer, color, "$reason:layer$index", depth + 1)
            changed = layerChanged || changed
        }
        return changed
    }

    private fun firstThemeColorInStateListDrawable(drawable: StateListDrawable): Int? {
        val count = stateListDrawableCount(drawable)
        for (index in 0 until count) {
            stateListDrawableChild(drawable, index)?.let(::colorForDrawable)?.let { return it }
        }
        return runCatching { drawable.current }.getOrNull()?.takeIf { it !== drawable }?.let(::colorForDrawable)
    }

    private fun firstThemeColorInLayerDrawable(drawable: LayerDrawable): Int? {
        for (index in 0 until drawable.numberOfLayers) {
            val child = runCatching { drawable.getDrawable(index) }.getOrNull() ?: continue
            colorForDrawable(child)?.let { return it }
        }
        return null
    }

    private fun applyCustomThemeColorToStateListDrawable(
        drawable: StateListDrawable,
        color: Int,
        reason: String,
        depth: Int
    ): Boolean {
        val stateList = runCatching { drawable.mutate() as? StateListDrawable }.getOrNull() ?: drawable
        val count = stateListDrawableCount(stateList)
        val attempts = mutableListOf<String>()
        var changed = false
        for (index in 0 until count) {
            val child = stateListDrawableChild(stateList, index)
            if (child == null) {
                attempts += "child[$index]=none"
                continue
            }
            val childChanged = applyCustomThemeColorToDrawable(child, color, "$reason:state$index", depth + 1)
            attempts += "child[$index]=${child.javaClass.name}:$childChanged"
            changed = childChanged || changed
        }
        if (!changed) {
            val current = runCatching { stateList.current }.getOrNull()?.takeIf { it !== stateList }
            if (current != null) {
                val currentChanged = applyCustomThemeColorToDrawable(current, color, "$reason:current", depth + 1)
                attempts += "current=${current.javaClass.name}:$currentChanged"
                changed = currentChanged || changed
            }
        }
        if (!changed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val tinted = runCatching {
                stateList.setTintList(ColorStateList.valueOf(color))
            }.isSuccess
            attempts += "tintList:$tinted"
            changed = tinted
        }
        amoledTrace("custom-drawable-color", "state-list|$reason|${colorHex(color)}|$changed", maxPerCategory = 1800) {
            "custom state-list recolor reason=$reason color=${colorHex(color)} depth=$depth count=$count " +
                "changed=$changed attempts=${attempts.joinToString()} drawable=${drawableSummary(stateList)}"
        }
        return changed
    }

    private fun customColoredDrawable(original: Drawable?, color: Int, reason: String): Drawable {
        val copy = copyThemeDrawable(original)
        val drawable = copy ?: original?.mutate() ?: ColorDrawable(color)
        val changed = applyCustomThemeColorToDrawable(drawable, color, reason)
        if (!changed && drawable !is ColorDrawable) {
            amoledTrace("custom-drawable-color", "fallback-color-drawable|$reason|${colorHex(color)}", maxPerCategory = 600) {
                "fallback custom drawable reason=$reason color=${colorHex(color)} original=${drawableSummary(original)}"
            }
            return ColorDrawable(color)
        }
        return drawable
    }

    private fun stateListDrawableCount(drawable: StateListDrawable): Int {
        return runCatching {
            StateListDrawable::class.java.getMethod("getStateCount").invoke(drawable) as? Int
        }.getOrNull()
            ?: runCatching {
                val state = drawable.getObjectField("mStateListState")
                state?.getObjectField("mNumChildren") as? Int
            }.getOrNull()
            ?: runCatching {
                val state = drawable.getObjectField("mStateListState")
                (state?.getObjectField("mDrawables") as? Array<*>)?.size
            }.getOrNull()
            ?: 0
    }

    private fun stateListDrawableChild(drawable: StateListDrawable, index: Int): Drawable? {
        return runCatching {
            StateListDrawable::class.java
                .getMethod("getStateDrawable", Int::class.javaPrimitiveType)
                .invoke(drawable, index) as? Drawable
        }.getOrNull()
            ?: runCatching {
                val state = drawable.getObjectField("mStateListState")
                (state?.getObjectField("mDrawables") as? Array<*>)?.getOrNull(index) as? Drawable
            }.getOrNull()
    }

    private fun setViewBackgroundColor(view: View, color: Int, preview: Boolean = false): Boolean {
        if (view.getTag(viewAppliedThemeColorTag) == color) return false
        return runCatching {
            internalThemeMutation.set(true)
            val before = drawableSummary(view.background)
            if (preview && view.getTag(viewPreviewOriginalStateTag) == null) {
                view.setTag(
                    viewPreviewOriginalStateTag,
                    OriginalViewThemeState(
                        background = copyThemeDrawable(view.background) ?: view.background,
                        backgroundTint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            view.backgroundTintList
                        } else {
                            null
                        },
                        imageTint = if (view is ImageView && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            view.imageTintList
                        } else {
                            null
                        },
                        textColor = textColorForView(view)
                    )
                )
            }
            val recoloredDrawable = applyCustomThemeColorToDrawable(
                drawable = view.background,
                color = color,
                reason = "view-background:${viewTraceKey(view)}:preview=$preview"
            )
            if (!recoloredDrawable) {
                view.setBackgroundColor(color)
            } else {
                view.invalidate()
            }
            view.setTag(viewAppliedThemeColorTag, color)
            view.setTag(viewAppliedThemeBackgroundTag, null)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                view.backgroundTintList = null
            }
            amoledTrace("view-background", "setColor|${viewTraceKey(view)}|${colorHex(color)}|preview=$preview", maxPerCategory = 900) {
                "setBackgroundColor preview=$preview color=${colorHex(color)} preserveDrawable=$recoloredDrawable " +
                    "before=$before after=${drawableSummary(view.background)} ${viewSummary(view)}"
            }
        }.onFailure {
            context.log.verbose("[THEME PATCH] Failed to apply captured surface color: ${it.message}")
        }.also {
            internalThemeMutation.remove()
        }.isSuccess
    }

    private fun setImageViewTintColor(view: ImageView, color: Int, preview: Boolean = false): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return false
        if (view.getTag(viewAppliedThemeColorTag) == color && view.imageTintList?.defaultColor == color) return false
        return runCatching {
            internalThemeMutation.set(true)
            val before = colorHex(view.imageTintList?.defaultColor)
            if (preview && view.getTag(viewPreviewOriginalStateTag) == null) {
                view.setTag(
                    viewPreviewOriginalStateTag,
                    OriginalViewThemeState(
                        background = view.background,
                        backgroundTint = view.backgroundTintList,
                        imageTint = view.imageTintList,
                        textColor = textColorForView(view)
                    )
                )
            }
            view.imageTintList = ColorStateList.valueOf(color)
            view.setTag(viewAppliedThemeColorTag, color)
            amoledTrace("view-image-tint", "setTint|${viewTraceKey(view)}|${colorHex(color)}|preview=$preview", maxPerCategory = 1200) {
                "setImageTint preview=$preview color=${colorHex(color)} before=$before after=${colorHex(view.imageTintList?.defaultColor)} " +
                    "${viewSummary(view)}"
            }
        }.onFailure {
            context.log.verbose("[THEME PATCH] Failed to apply captured icon tint: ${it.message}")
        }.also {
            internalThemeMutation.remove()
        }.isSuccess
    }

    private fun setTextLikeViewTextColor(view: View, color: Int, preview: Boolean = false): Boolean {
        if (view is TextView) return setTextViewTextColor(view, color, preview)
        if (view.getTag(viewAppliedThemeColorTag) == color && textColorForView(view) == color) return false
        return runCatching {
            internalThemeMutation.set(true)
            val before = colorHex(textColorForView(view))
            if (preview && view.getTag(viewPreviewOriginalStateTag) == null) {
                view.setTag(
                    viewPreviewOriginalStateTag,
                    OriginalViewThemeState(
                        background = view.background,
                        backgroundTint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            view.backgroundTintList
                        } else {
                            null
                        },
                        imageTint = if (view is ImageView && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            view.imageTintList
                        } else {
                            null
                        },
                        textColor = textColorForView(view)
                    )
                )
            }
            val attempts = mutableListOf<String>()
            val applied = applyReflectiveTextColor(view, color, attempts)
            if (!applied) error("no reflective text color path; attempts=${attempts.joinToString(" -> ")}")
            view.setTag(viewAppliedThemeColorTag, color)
            view.invalidate()
            amoledTrace("view-text-color", "setReflectiveTextColor|${viewTraceKey(view)}|${colorHex(color)}|preview=$preview", maxPerCategory = 1600) {
                "setReflectiveTextColor preview=$preview color=${colorHex(color)} before=$before after=${colorHex(textColorForView(view))} " +
                    "attempts=${attempts.joinToString(" -> ").take(1200)} ${viewSummary(view)}"
            }
        }.onFailure {
            context.log.verbose("[THEME PATCH] Failed to apply reflected captured text color: ${it.message}")
            amoledTrace("view-text-color", "setReflectiveTextColor-failed|${viewTraceKey(view)}|${colorHex(color)}", maxPerCategory = 1600) {
                "failed reflected text color color=${colorHex(color)} error=${it.javaClass.name}:${it.message} ${viewThemeLogDetails(view, full = true)}"
            }
        }.also {
            internalThemeMutation.remove()
        }.isSuccess
    }

    private fun setTextViewTextColor(view: TextView, color: Int, preview: Boolean = false): Boolean {
        if (view.getTag(viewAppliedThemeColorTag) == color && view.currentTextColor == color) return false
        return runCatching {
            internalThemeMutation.set(true)
            val before = colorHex(view.currentTextColor)
            if (preview && view.getTag(viewPreviewOriginalStateTag) == null) {
                view.setTag(
                    viewPreviewOriginalStateTag,
                    OriginalViewThemeState(
                        background = view.background,
                        backgroundTint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            view.backgroundTintList
                        } else {
                            null
                        },
                        imageTint = null,
                        textColor = view.currentTextColor
                    )
                )
            }
            view.setTextColor(color)
            view.setTag(viewAppliedThemeColorTag, color)
            amoledTrace("view-text-color", "setTextColor|${viewTraceKey(view)}|${colorHex(color)}|preview=$preview", maxPerCategory = 1200) {
                "setTextColor preview=$preview color=${colorHex(color)} before=$before after=${colorHex(view.currentTextColor)} " +
                    "text=${view.text?.toString()?.take(120) ?: "none"} ${viewSummary(view)}"
            }
        }.onFailure {
            context.log.verbose("[THEME PATCH] Failed to apply captured text color: ${it.message}")
        }.also {
            internalThemeMutation.remove()
        }.isSuccess
    }

    private fun shouldApplyCustomColorAsTextColor(view: View, colorSource: String?): Boolean {
        val source = colorSource.orEmpty().lowercase()
        if (view is TextView) {
            return "text" in source ||
                "selector_color_rule" in source ||
                "preview:" in source ||
                "currenttextcolor" in source ||
                view.text?.isNotBlank() == true ||
                view.contentDescription?.isNotBlank() == true
        }
        if (!isReflectiveTextColorTarget(view)) return false
        return "text" in source ||
            "label" in source ||
            "snaplabelview" in source ||
            "composersnaptextview" in source ||
            "snapfonttextview" in source ||
            "selector_color_rule" in source ||
            "preview:" in source
    }

    private fun shouldApplyCustomColorAsImageTint(view: View, colorSource: String?): Boolean {
        if (view !is ImageView) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return false
        val name = viewMatchName(view).lowercase()
        val source = colorSource.orEmpty().lowercase()
        val desc = view.contentDescription?.toString().orEmpty().lowercase()
        return "imageview" in name &&
            (
                "icon" in name ||
                    "icon" in source ||
                    "outline" in source ||
                    "image" in source ||
                    "icon" in desc ||
                    view.background == null ||
                    (view.width in 1..220 && view.height in 1..220)
                )
    }

    private fun applyMatchedCustomColor(
        view: View,
        color: Int,
        colorSource: String?,
        preview: Boolean = false
    ): Boolean {
        return when {
            shouldApplyCustomColorAsTextColor(view, colorSource) -> {
                setTextLikeViewTextColor(view, color, preview = preview)
            }
            shouldApplyCustomColorAsImageTint(view, colorSource) -> {
                setImageViewTintColor(view as ImageView, color, preview = preview)
            }
            else -> {
                setViewBackgroundColor(view, color, preview = preview)
            }
        }
    }

    private fun drawableForBackgroundPath(path: String): Drawable? {
        if (path.isBlank()) {
            amoledTrace("background-drawable", "blank-path", maxPerCategory = 80, unique = false) {
                "background drawable request ignored because path is blank"
            }
            return null
        }
        synchronized(backgroundDrawableStateCache) {
            if (backgroundDrawableStateCache.containsKey(path)) {
                val cached = backgroundDrawableStateCache[path]
                    ?.newDrawable(context.androidContext.resources)
                    ?.mutate()
                amoledTrace("background-drawable", "cache|$path|hit=${cached != null}", maxPerCategory = 600) {
                    "background drawable cache path=$path cachedState=${backgroundDrawableStateCache[path] != null} " +
                        "drawable=${drawableSummary(cached)}"
                }
                return cached
            }
        }

        val drawable = if (path.startsWith("content://", ignoreCase = true)) {
            runCatching {
                context.androidContext.contentResolver.openInputStream(Uri.parse(path))?.use { input ->
                    Drawable.createFromStream(input, path)?.mutate()
                }
            }.getOrNull()
        } else {
            Drawable.createFromPath(path)?.mutate()
        }
        synchronized(backgroundDrawableStateCache) {
            backgroundDrawableStateCache[path] = drawable?.constantState
        }
        amoledTrace("background-drawable", "load|$path|ok=${drawable != null}", maxPerCategory = 600) {
            "background drawable load path=$path ok=${drawable != null} drawable=${drawableSummary(drawable)} " +
                "cachedState=${drawable?.constantState != null}"
        }
        return drawable
    }

    private fun setViewBackgroundDrawable(view: View, path: String, drawable: Drawable): Boolean {
        if (view.getTag(viewAppliedThemeBackgroundTag) == path) return false
        return runCatching {
            internalThemeMutation.set(true)
            val before = drawableSummary(view.background)
            view.background = drawable
            view.setTag(viewAppliedThemeBackgroundTag, path)
            view.setTag(viewAppliedThemeColorTag, null)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                view.backgroundTintList = null
            }
            amoledTrace("view-background", "setDrawable|${viewTraceKey(view)}|$path", maxPerCategory = 500) {
                "setBackgroundDrawable path=$path before=$before after=${drawableSummary(view.background)} ${viewSummary(view)}"
            }
        }.onFailure {
            context.log.verbose("[THEME PATCH] Failed to apply background image: ${it.message}")
        }.also {
            internalThemeMutation.remove()
        }.isSuccess
    }

    private fun applyCustomBackgroundForView(view: View, runtime: ThemeRuntime = themeRuntime()): Boolean {
        val path = customBackgroundForView(view, runtime) ?: return false
        val drawable = drawableForBackgroundPath(path)
        if (drawable == null) {
            logAndroidSurfaceDecision("customBackground", view, "drawable_load_failed path=$path", runtime, unique = false)
            return false
        }
        val changed = setViewBackgroundDrawable(view, path, drawable)
        logAndroidSurfaceDecision("customBackground", view, "apply path=$path changed=$changed", runtime, unique = false)
        return changed
    }

    private fun restorePreviewState(view: View): Boolean {
        val state = view.getTag(viewPreviewOriginalStateTag) as? OriginalViewThemeState ?: return false
        return runCatching {
            internalThemeMutation.set(true)
            view.background = state.background
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                view.backgroundTintList = state.backgroundTint
                if (view is ImageView) {
                    view.imageTintList = state.imageTint
                }
            }
            if (state.textColor != null && isReflectiveTextColorTarget(view)) {
                setTextLikeViewTextColor(view, state.textColor, preview = false)
            }
            view.setTag(viewPreviewOriginalStateTag, null)
            view.setTag(viewAppliedThemeColorTag, null)
            view.setTag(viewAppliedThemeBackgroundTag, null)
            view.setTag(viewThemeMatchTag, null)
        }.also {
            internalThemeMutation.remove()
        }.isSuccess
    }

    private fun restorePreviewStateTree(host: View, maxViews: Int = 260, maxRuntimeMs: Long = 8L): Int {
        val startedAt = SystemClock.uptimeMillis()
        val clearVirtualOverlays = !hasSnapchatVirtualThemeSelectors(themeRuntime())
        var visited = 0
        var restored = 0

        fun walk(view: View?) {
            if (view == null || visited >= maxViews) return
            if (SystemClock.uptimeMillis() - startedAt > maxRuntimeMs) return
            visited++
            if (clearVirtualOverlays && clearVirtualThemeOverlays(view)) restored++
            if (restorePreviewState(view)) restored++
            if (view is android.view.ViewGroup) {
                for (index in 0 until view.childCount) {
                    if (visited >= maxViews || SystemClock.uptimeMillis() - startedAt > maxRuntimeMs) break
                    walk(view.getChildAt(index))
                }
            }
        }

        walk(host)
        return restored
    }

    private fun patchExistingAndroidSurface(
        view: View,
        allowPreLayout: Boolean = false,
        runtime: ThemeRuntime = themeRuntime()
    ): Boolean {
        val startedAt = SystemClock.uptimeMillis()
        fun finish(changed: Boolean, outcome: String): Boolean {
            val elapsed = SystemClock.uptimeMillis() - startedAt
            val keyView = viewTraceKey(view)
            amoledTrace("patch-existing-result", "$outcome|changed=$changed|elapsed=$elapsed|$keyView", maxPerCategory = if (changed || elapsed >= 2L) 1800 else 700) {
                "outcome=$outcome changed=$changed elapsed=${elapsed}ms allowPreLayout=$allowPreLayout " +
                    "forceAmoled=${runtime.forceAmoled} customEnabled=${runtime.customEnabled} " +
                    "details=${viewThemeLogDetails(view, full = true)}"
            }
            return changed
        }
        if (internalThemeMutation.get() == true) {
            logAndroidSurfaceDecision("patchExisting", view, "skip_internal_theme_mutation allowPreLayout=$allowPreLayout", runtime)
            return finish(false, "skip_internal_theme_mutation")
        }
        if (shouldObserveThemeSurfaceMap(runtime)) {
            val hasPatchableTint = Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP &&
                view.backgroundTintList?.defaultColor?.let(::isPatchableSurfaceOrOverlayColor) == true
            val namedSurface = isNamedAndroidSurface(view)
            val drawableSurface = drawableMayRepresentSurface(view.background)
            val catalogColor = customCatalogColorForView(view, runtime)
            val observable = shouldObserveAndroidSurfaceCandidate(
                view = view,
                runtime = runtime,
                hasPatchableTint = hasPatchableTint,
                drawableSurface = drawableSurface,
                catalogColor = catalogColor
            )
            if (observable) {
                observeAndroidViewThemeSurface(
                    source = "patchExisting",
                    view = view,
                    reason = "allowPreLayout=$allowPreLayout named=$namedSurface " +
                        "drawableSurface=$drawableSurface tint=$hasPatchableTint catalogColor=${colorHex(catalogColor)}",
                    runtime = runtime
                )
            } else {
                logAndroidSurfaceDecision(
                    "patchExistingObserve",
                    view,
                    "not_observable allowPreLayout=$allowPreLayout named=$namedSurface drawableSurface=$drawableSurface " +
                        "tint=$hasPatchableTint catalogColor=${colorHex(catalogColor)} " +
                        themeObservationGateDiagnostics(view, hasPatchableTint, drawableSurface, catalogColor),
                    runtime
                )
            }
        }
        if (runtime.forceAmoled && clearImagePreviewOverlayBackground(view)) {
            logAndroidSurfaceDecision("patchExisting", view, "cleared_image_preview_overlay", runtime)
            return finish(true, "cleared_image_preview_overlay")
        }
        var virtualOverlayChanged = applyVirtualThemeOverlaysForView(view, runtime)
        if (applyCustomBackgroundForView(view, runtime)) {
            logAndroidSurfaceDecision("patchExisting", view, "custom_background_applied", runtime)
            return finish(true, "custom_background_applied")
        }
        activePreviewRule()?.let { preview ->
            if (!isSnapchatVirtualThemeSelector(preview.target) && matchesThemeTarget(view, preview.target, preview.selector)) {
                val changed = applyMatchedCustomColor(
                    view = view,
                    color = preview.color,
                    colorSource = "preview:${preview.target}",
                    preview = true
                )
                logAndroidSurfaceDecision("patchExisting", view, "preview_color target=${preview.target} changed=$changed", runtime)
                return finish(changed, "preview_color")
            }
        }
        val customMatch = themeMatchForView(view, runtime)
        customMatch.color?.let { color ->
            val changed = applyMatchedCustomColor(
                view = view,
                color = color,
                colorSource = customMatch.colorSource,
                preview = false
            )
            logAndroidSurfaceDecision(
                "patchExisting",
                view,
                "custom_color ${colorHex(color)} source=${customMatch.colorSource ?: "unknown"} changed=$changed",
                runtime
            )
            return finish(changed, "custom_color")
        }
        if (!runtime.forceAmoled) {
            return finish(virtualOverlayChanged, if (virtualOverlayChanged) "virtual_overlay_changed" else "amoled_disabled_no_custom_match")
        }
        val rejectionReason = androidSurfaceRejectionReason(view, allowPreLayout)
        if (rejectionReason != null) {
            if (isNamedAndroidSurface(view) || view.background != null || Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && view.backgroundTintList != null) {
                logAndroidSurfaceDecision("patchExisting", view, "skip_$rejectionReason allowPreLayout=$allowPreLayout", runtime)
            }
            return finish(virtualOverlayChanged, if (virtualOverlayChanged) "virtual_overlay_changed_skip_$rejectionReason" else "skip_$rejectionReason")
        }

        var changed = virtualOverlayChanged
        val background = view.background
        if (background != null) {
            if (patchDrawable(background)) {
                changed = true
                logAndroidSurfaceDecision("patchExisting", view, "patched_background ${drawableSummary(background)}", runtime)
            } else if (!background.javaClass.name.startsWith("android.")) {
                logAndroidSurfaceDecision(
                    "patchExisting",
                    view,
                    "unknown_drawable_unpatched ${drawableSummary(background)}",
                    runtime
                )
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val tint = view.backgroundTintList
            if (tint != null && isPatchableSurfaceOrOverlayColor(tint.defaultColor)) {
                val patchedTint = amoledSurfaceColorFor(tint.defaultColor)
                view.backgroundTintList = ColorStateList.valueOf(patchedTint)
                changed = true
                logAndroidSurfaceDecision("patchExisting", view, "patched_tint ${colorHex(tint.defaultColor)}->${colorHex(patchedTint)}", runtime)
            }
        }

        if (!changed) {
            logAndroidSurfaceDecision("patchExisting", view, "eligible_no_patchable_background allowPreLayout=$allowPreLayout", runtime)
        }
        return finish(changed, if (changed) "surface_or_virtual_changed" else "eligible_no_patchable_background")
    }

    private fun patchAndroidSurfaceTree(
        host: View,
        maxViews: Int = 96,
        maxRuntimeMs: Long = 3L,
        allowPreLayout: Boolean = false
    ): Int {
        val runtime = themeRuntime()
        if (!hasActivePatchesOrPreview(runtime)) return 0
        if (isExcludedAndroidView(host)) return 0
        val startedAt = SystemClock.uptimeMillis()
        var visited = 0
        var changed = 0
        var maxDepthSeen = 0
        var viewLimitHit = false
        var timeLimitHit = false
        var childEdgesSeen = 0

        fun walk(view: View?, depth: Int = 0) {
            if (view == null) return
            if (visited >= maxViews) {
                if (!viewLimitHit) {
                    viewLimitHit = true
                    amoledTrace("android-tree-stop", "view-limit|$maxViews|$depth|$visited|${viewClassTraceKey(view)}", maxPerCategory = 520, unique = false) {
                        "android surface walk stopped by view cap maxViews=$maxViews visited=$visited depth=$depth " +
                            "host=${viewSummary(host)} next=${viewSummary(view)} ancestry=${viewAncestrySummary(view)}"
                    }
                }
                return
            }
            val elapsedBefore = SystemClock.uptimeMillis() - startedAt
            if (elapsedBefore > maxRuntimeMs) {
                if (!timeLimitHit) {
                    timeLimitHit = true
                    amoledTrace("android-tree-stop", "time-limit|${maxRuntimeMs}ms|$elapsedBefore|$visited|$depth|${viewClassTraceKey(view)}", maxPerCategory = 520, unique = false) {
                        "android surface walk stopped by time cap budget=${maxRuntimeMs}ms elapsed=${elapsedBefore}ms " +
                            "visited=$visited depth=$depth maxViews=$maxViews host=${viewSummary(host)} " +
                            "next=${viewSummary(view)} ancestry=${viewAncestrySummary(view)}"
                    }
                }
                return
            }
            visited++
            if (depth > maxDepthSeen) maxDepthSeen = depth

            val nodeElapsedMs = SystemClock.uptimeMillis() - startedAt
            amoledTrace("android-tree-node", "${viewClassTraceKey(view)}|host=${viewClassTraceKey(host)}|allowPreLayout=$allowPreLayout", maxPerCategory = 2200) {
                "source=android-surface host=${viewTraceKey(host)} visited=$visited depth=$depth allowPreLayout=$allowPreLayout " +
                    "elapsed=${nodeElapsedMs}ms ${viewSummary(view)}"
            }
            if (patchExistingAndroidSurface(view, allowPreLayout, runtime)) changed++

            if (view is android.view.ViewGroup) {
                childEdgesSeen += view.childCount
                for (index in 0 until view.childCount) {
                    if (visited >= maxViews || SystemClock.uptimeMillis() - startedAt > maxRuntimeMs) break
                    walk(view.getChildAt(index), depth + 1)
                }
            }
        }

        walk(host)
        logAndroidTreePass(
            source = "android-surface",
            host = host,
            maxItems = maxViews,
            maxRuntimeMs = maxRuntimeMs,
            visited = visited,
            changed = changed,
            elapsedMs = SystemClock.uptimeMillis() - startedAt,
            extra = "allowPreLayout=$allowPreLayout viewLimitHit=$viewLimitHit timeLimitHit=$timeLimitHit " +
                "maxDepth=$maxDepthSeen childEdges=$childEdgesSeen"
        )
        return changed
    }

    private fun clearImagePreviewOverlayTree(host: View, maxViews: Int = 40, maxRuntimeMs: Long = 1L): Int {
        if (!themeRuntime().forceAmoled) return 0
        val startedAt = SystemClock.uptimeMillis()
        var visited = 0
        var changed = 0

        fun walk(view: View?) {
            if (view == null || visited >= maxViews) return
            if (SystemClock.uptimeMillis() - startedAt > maxRuntimeMs) return
            visited++

            if (clearImagePreviewOverlayBackground(view)) changed++

            if (view is android.view.ViewGroup) {
                for (index in 0 until view.childCount) {
                    if (visited >= maxViews || SystemClock.uptimeMillis() - startedAt > maxRuntimeMs) break
                    walk(view.getChildAt(index))
                }
            }
        }

        walk(host)
        logAndroidTreePass(
            source = "image-preview-overlay",
            host = host,
            maxItems = maxViews,
            maxRuntimeMs = maxRuntimeMs,
            visited = visited,
            changed = changed,
            elapsedMs = SystemClock.uptimeMillis() - startedAt
        )
        return changed
    }

    private fun collectValdiHosts(start: View?, maxViews: Int = 48, maxHosts: Int = 4): List<View> {
        val hosts = mutableListOf<View>()
        var visited = 0
        var skippedValdiHosts = 0

        fun walk(view: View?) {
            if (view == null || visited >= maxViews || hosts.size >= maxHosts) return
            visited++
            if (isExcludedAndroidView(view)) return
            if (view.getValdiViewNode() != null) {
                val gate = retainedValdiScanGate(view)
                logRetainedValdiScanGate("collectValdiHosts", view, gate, "walk")
                if (gate.allowed) {
                    hosts += view
                    return
                }
                skippedValdiHosts++
            }
            if (view is android.view.ViewGroup) {
                for (index in 0 until view.childCount) {
                    walk(view.getChildAt(index))
                    if (visited >= maxViews || hosts.size >= maxHosts) break
                }
            }
        }

        walk(start)
        if (start != null) {
            val key = if (hosts.isEmpty()) {
                "startClass=${viewClassTraceKey(start)}|hosts=0|visited=$visited|skipped=$skippedValdiHosts"
            } else {
                "start=${viewTraceKey(start)}|hosts=${hosts.size}"
            }
            val maxLogs = if (hosts.isEmpty()) 260 else 600
            amoledTrace("valdi-hosts", key, maxPerCategory = maxLogs) {
                "start=${viewSummary(start)} maxViews=$maxViews maxHosts=$maxHosts visited=$visited " +
                    "skippedValdiHosts=$skippedValdiHosts hosts=${hosts.joinToString(" || ") { viewSummary(it) }}"
            }
        }
        return hosts
    }

    private fun installSurfaceLayoutPatch(
        host: View,
        reason: String,
        maxNodes: Int,
        maxRuntimeMs: Long,
        tagKey: Int,
        includeValdi: Boolean
    ) {
        if (host.getTag(tagKey) == true) {
            logAndroidSurfaceDecision("installSurfaceLayoutPatch", host, "skip_already_installed reason=$reason", unique = true)
            return
        }
        val signature = attachPatchSignature(themeRuntime())
        if (host.getTag(surfaceLayoutPatchSignatureTag) == signature) {
            logAndroidSurfaceDecision("installSurfaceLayoutPatch", host, "skip_same_signature reason=$reason signature=$signature", unique = true)
            return
        }
        logAndroidSurfaceDecision(
            "installSurfaceLayoutPatch",
            host,
            "schedule reason=$reason maxNodes=$maxNodes budget=${maxRuntimeMs}ms includeValdi=$includeValdi signature=$signature",
            unique = true
        )

        fun runPatch(target: View, clearTag: Boolean) {
            val startedAt = SystemClock.uptimeMillis()
            if (clearTag) target.setTag(tagKey, null)
            val androidRuntimeMs = if (includeValdi) {
                (maxRuntimeMs / 2).coerceAtLeast(1L)
            } else {
                maxRuntimeMs
            }
            val valdiGate = if (includeValdi) retainedValdiScanGate(target) else null
            var retainedSkipReason: String? = null
            val valdiChanged = if (valdiGate?.allowed == true) {
                logRetainedValdiScanGate("installSurfaceLayoutPatch.runPatch", target, valdiGate, reason)
                retainedSkipReason = retainedLayoutScanSkipReason(target, valdiGate)
                if (retainedSkipReason != null) {
                    amoledTrace(
                        "valdi-retained-skip",
                        "$retainedSkipReason|${valdiGate.reason}|${viewTraceKey(target)}",
                        maxPerCategory = 1600,
                        unique = false
                    ) {
                        "skip retained layout scan reason=$retainedSkipReason layoutReason=$reason " +
                            "gate=${valdiGate.reason} target=${viewThemeDiagnostics(target)} " +
                            "debug=${valdiGate.debugDescription.take(900)}"
                    }
                    logAndroidSurfaceDecision(
                        "installSurfaceLayoutPatch",
                        target,
                        "skip_retained_layout_scan=$retainedSkipReason reason=$reason gate=${valdiGate.reason}",
                        unique = true
                    )
                    0
                } else {
                    patchRetainedValdiTree(target, maxNodes, maxRuntimeMs)
                }
            } else {
                if (includeValdi && valdiGate != null) {
                    logRetainedValdiScanGate("installSurfaceLayoutPatch.runPatch", target, valdiGate, reason)
                    logAndroidSurfaceDecision(
                        "installSurfaceLayoutPatch",
                        target,
                        "${valdiGate.reason} reason=$reason",
                        unique = true
                    )
                }
                0
            }
            val changed = valdiChanged +
                patchAndroidSurfaceTree(target, maxNodes, androidRuntimeMs)
            if (changed > 0 && valdiPreDrawPatchLogCount < 16) {
                valdiPreDrawPatchLogCount++
                context.log.verbose("[AMOLED PATCH] Layout retained surface patch $reason: changes=$changed")
            }
            logAndroidTreePass(
                source = "surface-layout",
                host = target,
                maxItems = maxNodes,
                maxRuntimeMs = maxRuntimeMs,
                visited = -1,
                changed = changed,
                elapsedMs = SystemClock.uptimeMillis() - startedAt,
                extra = "reason=$reason includeValdi=$includeValdi valdiGate=${valdiGate?.reason ?: "disabled"} " +
                    "retainedSkip=${retainedSkipReason ?: "none"} clearTag=$clearTag signature=$signature"
            )
            target.setTag(surfaceLayoutPatchSignatureTag, signature)
        }

        val initialValdiGate = if (includeValdi) retainedValdiScanGate(host) else null
        if (initialValdiGate?.allowed == true && (host.width <= 0 || host.height <= 0)) {
            logRetainedValdiScanGate("installSurfaceLayoutPatch.defer", host, initialValdiGate, reason)
            logAndroidSurfaceDecision(
                "installSurfaceLayoutPatch",
                host,
                "defer_zero_size_retained_valdi reason=$reason gate=${initialValdiGate.reason}",
                unique = true
            )
        }

        if (host.width > 0 && host.height > 0) {
            host.setTag(tagKey, true)
            runPatch(host, clearTag = false)
            host.postDelayed({
                if (host.getTag(tagKey) == true) host.setTag(tagKey, null)
            }, 160L)
            return
        }

        host.setTag(tagKey, true)
        host.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            override fun onLayoutChange(
                v: View,
                left: Int,
                top: Int,
                right: Int,
                bottom: Int,
                oldLeft: Int,
                oldTop: Int,
                oldRight: Int,
                oldBottom: Int
            ) {
                if (right <= left || bottom <= top) return
                v.removeOnLayoutChangeListener(this)
                runPatch(v, clearTag = true)
            }
        })
    }

    private fun installAndroidFirstLayoutPatch(
        host: View,
        reason: String,
        maxViews: Int,
        maxRuntimeMs: Long,
        runIfAlreadyLaidOut: Boolean
    ) {
        if (host.getTag(androidLayoutPatchInstalledTag) == true) {
            logAndroidSurfaceDecision("installAndroidFirstLayoutPatch", host, "skip_already_installed reason=$reason", unique = true)
            return
        }
        val signature = attachPatchSignature(themeRuntime())
        if (host.getTag(androidLayoutPatchSignatureTag) == signature) {
            logAndroidSurfaceDecision("installAndroidFirstLayoutPatch", host, "skip_same_signature reason=$reason signature=$signature", unique = true)
            return
        }
        logAndroidSurfaceDecision(
            "installAndroidFirstLayoutPatch",
            host,
            "schedule reason=$reason maxViews=$maxViews budget=${maxRuntimeMs}ms runIfLaidOut=$runIfAlreadyLaidOut signature=$signature",
            unique = true
        )

        fun runPatch(target: View) {
            val startedAt = SystemClock.uptimeMillis()
            target.setTag(androidLayoutPatchInstalledTag, null)
            val changed = patchAndroidSurfaceTree(target, maxViews = maxViews, maxRuntimeMs = maxRuntimeMs)
            if (changed > 0 && androidLayoutPatchLogCount < 12) {
                androidLayoutPatchLogCount++
                context.log.verbose("[AMOLED PATCH] Layout surface patch $reason: changes=$changed")
            }
            logAndroidTreePass(
                source = "android-first-layout",
                host = target,
                maxItems = maxViews,
                maxRuntimeMs = maxRuntimeMs,
                visited = -1,
                changed = changed,
                elapsedMs = SystemClock.uptimeMillis() - startedAt,
                extra = "reason=$reason signature=$signature"
            )
            target.setTag(androidLayoutPatchSignatureTag, signature)
        }

        if (host.width > 0 && host.height > 0) {
            if (runIfAlreadyLaidOut) runPatch(host)
            return
        }

        host.setTag(androidLayoutPatchInstalledTag, true)
        host.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            override fun onLayoutChange(
                v: View,
                left: Int,
                top: Int,
                right: Int,
                bottom: Int,
                oldLeft: Int,
                oldTop: Int,
                oldRight: Int,
                oldBottom: Int
            ) {
                if (right <= left || bottom <= top) return
                v.removeOnLayoutChangeListener(this)
                runPatch(v)
            }
        })
    }

    private fun patchAndroidAddedSurface(event: AddViewEvent) {
        val runtime = themeRuntime()
        val activePatching = hasActivePatchesOrPreview(runtime)
        val observeOnly = shouldObserveThemeSurfaceMap(runtime)
        if (!activePatching && !observeOnly) return

        val reason = event.viewClassName.substringAfterLast('.')
        val shouldPatchView = activePatching && shouldRunAndroidAttachPatch(event.view, runtime)
        val shouldPatchParent = activePatching && shouldRunAndroidAttachPatch(event.parent, runtime)
        val hasOverlayCandidate = hasOverlayPaintCandidate(event.view)
        val addViewKey = if (shouldPatchView || shouldPatchParent || hasOverlayCandidate || event.view.getValdiViewNode() != null) {
            "${event.viewClassName}|${viewTraceKey(event.view)}|${viewTraceKey(event.parent)}"
        } else {
            "noop|${event.viewClassName}|view=${viewClassTraceKey(event.view)}|parent=${viewClassTraceKey(event.parent)}"
        }
        amoledTrace("add-view", addViewKey, maxPerCategory = if (addViewKey.startsWith("noop|")) 420 else 1200) {
            "reason=$reason index=${event.index} layoutParams=${event.layoutParams.javaClass.name} " +
                "patchView=$shouldPatchView patchParent=$shouldPatchParent overlayCandidate=$hasOverlayCandidate " +
                "screen=${currentThemeScreenHint(event.view)} viewState=${viewThemeStateSignature(event.view)} " +
                "parentState=${viewThemeStateSignature(event.parent)} " +
                "view=${viewThemeLogDetails(event.view, full = true)} " +
                "parent=${viewThemeLogDetails(event.parent, full = true)} " +
                "viewAncestry=${viewAncestrySummary(event.view)}"
        }
        if (observeOnly) {
            val viewHasPatchableTint = Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP &&
                event.view.backgroundTintList?.defaultColor?.let(::isPatchableSurfaceOrOverlayColor) == true
            val viewDrawableSurface = drawableMayRepresentSurface(event.view.background)
            val viewCatalogColor = customCatalogColorForView(event.view, runtime)
            val viewObservable = shouldObserveAndroidSurfaceCandidate(
                view = event.view,
                runtime = runtime,
                hasPatchableTint = viewHasPatchableTint,
                drawableSurface = viewDrawableSurface,
                catalogColor = viewCatalogColor,
                extraCandidate = hasOverlayCandidate || event.view.getValdiViewNode() != null
            )
            if (viewObservable) {
                observeAndroidViewThemeSurface(
                    source = "addView",
                    view = event.view,
                    reason = "child reason=$reason index=${event.index} patchView=$shouldPatchView " +
                        "overlayCandidate=$hasOverlayCandidate tint=$viewHasPatchableTint " +
                        "drawableSurface=$viewDrawableSurface catalogColor=${colorHex(viewCatalogColor)}",
                    runtime = runtime
                )
            }
            val parentHasPatchableTint = Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP &&
                event.parent.backgroundTintList?.defaultColor?.let(::isPatchableSurfaceOrOverlayColor) == true
            val parentDrawableSurface = drawableMayRepresentSurface(event.parent.background)
            val parentCatalogColor = customCatalogColorForView(event.parent, runtime)
            val parentObservable = shouldObserveAndroidSurfaceCandidate(
                view = event.parent,
                runtime = runtime,
                hasPatchableTint = parentHasPatchableTint,
                drawableSurface = parentDrawableSurface,
                catalogColor = parentCatalogColor,
                extraCandidate = shouldPatchParent
            )
            if (parentObservable) {
                observeAndroidViewThemeSurface(
                    source = "addViewParent",
                    view = event.parent,
                    reason = "parent childReason=$reason patchParent=$shouldPatchParent " +
                        "tint=$parentHasPatchableTint drawableSurface=$parentDrawableSurface " +
                        "catalogColor=${colorHex(parentCatalogColor)}",
                    runtime = runtime
                )
            }
        }
        if (!activePatching) return
        if (shouldScanImagePreviewOverlay(event.view)) {
            clearImagePreviewOverlayTree(event.view, maxViews = 36, maxRuntimeMs = 1L)
        }
        if (shouldPatchView) {
            val changed = patchAndroidSurfaceTree(event.view, maxViews = 12, maxRuntimeMs = 1L, allowPreLayout = true)
            if (changed == 0) {
                logAndroidSurfaceDecision("addViewPatch", event.view, "initial_tree_no_changes reason=$reason", runtime)
            }
            val needsLayoutPatch = activePreviewRule() != null ||
                (runtime.customEnabled && (runtime.rules.isNotEmpty() || runtime.backgrounds.isNotEmpty())) ||
                isNamedAndroidSurface(event.view)
            logAndroidSurfaceDecision(
                "addViewPatch",
                event.view,
                "needsLayoutPatch=$needsLayoutPatch reason=$reason changed=$changed",
                runtime
            )
            if (needsLayoutPatch) {
                installAndroidFirstLayoutPatch(
                    host = event.view,
                    reason = "addView:$reason",
                    maxViews = 18,
                    maxRuntimeMs = 1L,
                    runIfAlreadyLaidOut = true
                )
            }
        } else {
            amoledTrace("add-view-skip", "$reason|view=${viewClassTraceKey(event.view)}|parent=${viewClassTraceKey(event.parent)}", maxPerCategory = 420) {
                "skip attach patch: not a surface/prelayout/custom candidate reason=$reason ${viewThemeDiagnostics(event.view)}"
            }
        }

        if (shouldPatchParent) {
            val parentChanged = patchExistingAndroidSurface(event.parent, runtime = runtime)
            if (!parentChanged) {
                logAndroidSurfaceDecision("addViewParent", event.parent, "parent_no_change childReason=$reason", runtime)
            }
        } else {
            amoledTrace("add-view-skip", "parent|$reason|${viewClassTraceKey(event.parent)}", maxPerCategory = 420) {
                "skip parent patch: not a surface/prelayout/custom candidate childReason=$reason ${viewThemeDiagnostics(event.parent)}"
            }
        }
    }

    private fun installValdiPreDrawPatches(event: AddViewEvent) {
        val runtime = themeRuntime()
        if (!runtime.hasValdiPatches && activePreviewRule() == null) return
        val reason = event.viewClassName.substringAfterLast('.')
        if (containsContentToken(reason)) return
        val hosts = linkedSetOf<View>()
        if (shouldInstallValdiLayoutPatchFor(event.view)) hosts += event.view
        hosts += collectValdiHosts(event.view, maxViews = 24, maxHosts = 2)
        val predrawKey = if (hosts.isEmpty()) {
            "skip|$reason|${viewClassTraceKey(event.view)}"
        } else {
            "${reason}|${viewTraceKey(event.view)}|hosts=${hosts.size}"
        }
        amoledTrace("valdi-predraw", predrawKey, maxPerCategory = if (hosts.isEmpty()) 360 else 800) {
            "reason=$reason hosts=${hosts.size} view=${viewThemeDiagnostics(event.view)} " +
                "hostSummaries=${hosts.joinToString(" || ") { viewThemeDiagnostics(it) }}"
        }
        if (hosts.isEmpty()) return

        hosts.forEachIndexed { index, host ->
            val isValdiHost = host.getValdiViewNode() != null
            val root = host.rootView
            val rootWidth = root?.width?.takeIf { it > 0 } ?: context.androidContext.resources.displayMetrics.widthPixels
            val rootHeight = root?.height?.takeIf { it > 0 } ?: context.androidContext.resources.displayMetrics.heightPixels
            val isLargeHost = host.width == 0 ||
                host.height == 0 ||
                host.width >= (rootWidth * 0.65f).toInt() ||
                host.height >= (rootHeight * 0.25f).toInt()
            installSurfaceLayoutPatch(
                host = host,
                reason = reason,
                maxNodes = when {
                    isValdiHost && isLargeHost -> 144
                    index == 0 -> 96
                    isValdiHost -> 64
                    else -> 40
                },
                maxRuntimeMs = when {
                    isValdiHost && isLargeHost -> 3L
                    index == 0 -> 2L
                    isValdiHost -> 2L
                    else -> 1L
                },
                tagKey = valdiPreDrawInstalledTag,
                includeValdi = true
            )
        }
    }

    private fun logValdiPatch(source: String, className: String?, attributeName: String, original: Any?) {
        val key = "$source|${className.orEmpty()}|$attributeName|$original"
        synchronized(valdiPatchLogKeys) {
            if (valdiPatchLogKeys.containsKey(key)) return
            valdiPatchLogKeys[key] = true
        }
        if (valdiPatchLogCount < 256) {
            valdiPatchLogCount++
            context.log.verbose(
                "[AMOLED PATCH] Patched Composer $source attr=$attributeName class=${className ?: "unknown"} value=$original"
            )
        }
        amoledTrace("valdi-patch", key, maxPerCategory = 500) {
            "source=$source class=${className ?: "unknown"} attr=$attributeName original=${valueSummary(original)}"
        }
    }

    private fun isExcludedAndroidView(view: View?): Boolean {
        var current = view
        var depth = 0
        while (current != null && depth < 10) {
            val name = viewMatchName(current)
            if (containsContentToken(name)) return true
            current = current.parent as? View
            depth++
        }
        return false
    }

    private fun isAndroidContentClassName(name: String): Boolean {
        return "textview" in name ||
            "edittext" in name ||
            "imageview" in name ||
            "textureview" in name ||
            "surfaceview" in name
    }

    private fun isNamedAndroidSurface(view: View?): Boolean {
        if (view == null || isExcludedAndroidView(view)) return false
        if (isSmallInteractiveControl(view)) return false
        if (isInsideImagePreview(view)) return false
        val name = viewMatchName(view)
        if (containsContentToken(name)) return false
        if (isAndroidContentClassName(name)) return false
        if (namedAndroidSurfaceTokens.any { it in name }) return true
        return surfaceAttributeTokens.any { it in name } ||
            listOf("dialog", "modal", "drawer", "bottom", "list", "recycler").any { it in name }
    }

    private fun themedCanvasSurfaceFor(view: View?): View? {
        val runtime = themeRuntime()
        val virtualThemeActive = hasSnapchatVirtualThemeSelectors(runtime)
        if (!runtime.forceAmoled && !virtualThemeActive) return null
        var current = view
        var depth = 0
        while (current != null && depth < 8) {
            if (virtualThemeActive) {
                val selectors = snapchatVirtualThemeSelectorsForHost(current, runtime)
                if (selectors.isNotEmpty()) {
                    val textSelectors = selectors.count(::isSnapchatVirtualTextThemeSelector)
                    amoledTrace("canvas-surface", "virtual-theme|${viewTraceKey(current)}|selectors=${selectors.size}|text=$textSelectors", maxPerCategory = 1200) {
                        "active virtual theme canvas surface selectors=${selectors.size} textSelectors=$textSelectors " +
                            "selectors=${selectors.joinToString(" || ") { it.take(240) }} source=${viewSummary(view)} host=${viewSummary(current)}"
                    }
                    return current
                }
            }
            val name = viewMatchName(current)
            if (runtime.forceAmoled) {
                if (containsContentToken(name)) return null
                if (namedAndroidSurfaceTokens.any { it in name }) return current
            }
            current = current.parent as? View
            depth++
        }
        return null
    }

    private fun pushThemeDrawSurface(view: View?): View? {
        val stack = themeDrawSurfaceStack.get() ?: mutableListOf<View?>().also(themeDrawSurfaceStack::set)
        stack += activeThemeDrawSurface.get()
        val active = themedCanvasSurfaceFor(view)
        if (active != null) {
            activeThemeDrawSurface.set(active)
            amoledTrace("canvas-surface", "push|${viewTraceKey(active)}", maxPerCategory = 500) {
                "active=${viewSummary(active)} source=${viewSummary(view)} stackDepth=${stack.size}"
            }
        } else {
            activeThemeDrawSurface.remove()
        }
        return active
    }

    private fun popThemeDrawSurface() {
        val stack = themeDrawSurfaceStack.get() ?: return
        val previous = if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) else null
        if (previous != null) {
            activeThemeDrawSurface.set(previous)
        } else {
            activeThemeDrawSurface.remove()
        }
        if (stack.isEmpty()) themeDrawSurfaceStack.remove()
    }

    private fun virtualThemeDrawApplySignature(view: View, runtime: ThemeRuntime): Int {
        return 31 * virtualThemeLayoutSignature(runtime) + (viewBoundsRect(view)?.hashCode() ?: 0)
    }

    private fun patchCanvasSurfacePaintArg(param: HookAdapter) {
        if (!themeRuntime().forceAmoled) return
        val surface = activeThemeDrawSurface.get() ?: return
        val args = param.args()
        val paintIndex = args.indexOfLast { it is Paint }
        if (paintIndex < 0) {
            amoledTrace("canvas-paint", "${param.method().name}|noPaint|${viewTraceKey(surface)}", maxPerCategory = 600) {
                "method=${param.method().name} surface=${viewSummary(surface)} args=${args.map { it?.javaClass?.name ?: "null" }}"
            }
            return
        }
        val paint = args[paintIndex] as? Paint ?: return
        if (paint.style == Paint.Style.STROKE) {
            amoledTrace("canvas-paint", "${param.method().name}|stroke|${paint.color}|${viewTraceKey(surface)}", maxPerCategory = 600) {
                "method=${param.method().name} skip=stroke color=${colorHex(paint.color)} surface=${viewSummary(surface)}"
            }
            return
        }
        val color = paint.color
        if (!isPatchableCanvasSurfaceColor(color)) {
            amoledTrace("canvas-paint", "${param.method().name}|unpatchable|${paint.style}|${paint.color}|${viewTraceKey(surface)}", maxPerCategory = 800) {
                "method=${param.method().name} skip=unpatchable style=${paint.style} color=${colorHex(color)} alpha=${paint.alpha} surface=${viewSummary(surface)}"
            }
            return
        }
        param.setArg(paintIndex, Paint(paint).apply {
            this.color = canvasAmoledColor(color)
        })
        amoledTrace("canvas-paint", "${param.method().name}|patched|${paint.style}|${paint.color}|${viewTraceKey(surface)}", maxPerCategory = 1000) {
            "method=${param.method().name} patched style=${paint.style} from=${colorHex(color)} to=${colorHex(canvasAmoledColor(color))} " +
                "alpha=${paint.alpha} surface=${viewSummary(surface)}"
        }
    }

    private fun patchCanvasVirtualTextPaintArg(param: HookAdapter) {
        val surface = activeThemeDrawSurface.get() ?: return
        val runtime = themeRuntime()
        if (!hasSnapchatVirtualTextThemeSelectors(runtime)) return
        val drawCall = parseCanvasTextDrawCall(param.args()) ?: run {
            amoledTrace("canvas-text", "${param.method().name}|unparsed|${viewTraceKey(surface)}", maxPerCategory = 1400) {
                "unparsed drawText method=${param.method().name} args=${param.args().map { it?.javaClass?.name ?: "null" }} surface=${viewSummary(surface)}"
            }
            return
        }
        val overlays = virtualThemeOverlaysForView(surface, runtime)
            .asSequence()
            .filter { it.text != null }
            .map { overlay ->
                overlay to refinedSnapchatVirtualTextBounds(surface, overlay.selector, overlay.localBounds)
            }
            .toList()
        if (overlays.isEmpty()) {
            amoledTrace("canvas-text", "${param.method().name}|no-overlays|${viewTraceKey(surface)}|${drawCall.text.orEmpty().take(60)}", maxPerCategory = 2200) {
                "no text overlays for text draw text=${drawCall.text?.take(160) ?: "unknown"} x=${drawCall.x} y=${drawCall.y} " +
                    "paintColor=${colorHex(drawCall.paint.color)} surface=${viewSummary(surface)}"
            }
            return
        }
        val boundMatches = overlays.filter { (_, bounds) ->
            rectContainsTextBaseline(bounds, drawCall.x, drawCall.y)
        }
        val matched = drawCall.text
            ?.takeIf { it.isNotBlank() }
            ?.let { actualText ->
                boundMatches.firstOrNull { (overlay, _) ->
                    virtualThemeTextMatches(overlay.text.orEmpty(), actualText)
                }
            }
            ?: boundMatches.singleOrNull()
        if (matched == null) {
            amoledTrace("canvas-text", "${param.method().name}|miss|${viewTraceKey(surface)}|${drawCall.text.orEmpty().take(60)}|${drawCall.x.toInt()}x${drawCall.y.toInt()}", maxPerCategory = 4200) {
                "miss text draw text=${drawCall.text?.take(180) ?: "unknown"} normalized=${drawCall.text?.let(::normalizedVirtualThemeText) ?: "unknown"} " +
                    "x=${drawCall.x} y=${drawCall.y} paintColor=${colorHex(drawCall.paint.color)} " +
                    "boundMatches=${boundMatches.size} " +
                    "candidates=${overlays.joinToString(" || ") { (overlay, bounds) ->
                        "expected=${overlay.text?.take(120) ?: "none"} color=${colorHex(overlay.color)} bounds=${rectToThemeString(bounds)} selector=${overlay.selector.take(160)}"
                    }} surface=${viewSummary(surface)}"
            }
            return
        }
        val (overlay, bounds) = matched
        param.setArg(drawCall.paintIndex, Paint(drawCall.paint).apply {
            color = overlay.color
        })
        amoledTrace("canvas-text", "${param.method().name}|patched|${viewTraceKey(surface)}|${drawCall.text.orEmpty().take(80)}|${colorHex(overlay.color)}", maxPerCategory = 5200, unique = false) {
            "patched text draw method=${param.method().name} text=${drawCall.text?.take(220) ?: "unknown"} " +
                "expected=${overlay.text?.take(220) ?: "none"} x=${drawCall.x} y=${drawCall.y} " +
                "bounds=${rectToThemeString(bounds)} from=${colorHex(drawCall.paint.color)} to=${colorHex(overlay.color)} " +
                "selector=${overlay.selector.take(360)} surface=${viewSummary(surface)}"
        }
    }

    private fun viewMatchName(view: View): String {
        (view.getTag(viewMatchNameTag) as? String)?.let { return it }
        val resourceName = getResourceEntryName(view)
        val name = buildString {
            append(view.javaClass.name.lowercase())
            resourceName?.let {
                append(' ').append(it.lowercase())
            }
        }
        view.setTag(viewMatchNameTag, name)
        amoledTrace("view-name", viewTraceKey(view), maxPerCategory = 1800) {
            val parent = view.parent as? View
            "class=${view.javaClass.name} id=${resourceName ?: "none"} name=$name " +
                "hash=${System.identityHashCode(view)} size=${view.width}x${view.height} " +
                "visibility=${view.visibility} alpha=${view.alpha} clickable=${view.isClickable} enabled=${view.isEnabled} " +
                "parent=${parent?.javaClass?.name ?: "none"} parentId=${getResourceEntryName(parent) ?: "none"}"
        }
        return name
    }

    private fun getResourceEntryName(view: View?): String? {
        if (view == null || view.id == View.NO_ID) return null
        return runCatching {
            view.resources?.getResourceEntryName(view.id)
        }.getOrNull()?.takeUnless { value ->
            value.endsWith("_resource_name_obfuscated") ||
                value == "0" ||
                value.matches(Regex("\\d+_resource_name_obfuscated"))
        }
    }

    private fun isSmallInteractiveControl(view: View): Boolean {
        val width = view.width
        val height = view.height
        if (width > 0 && height > 0 && view.isClickable && width <= 220 && height <= 220) return true
        val name = viewMatchName(view)
        if (controlSurfaceTokens.none { it in name }) return false
        return width <= 0 || height <= 0 || (width <= 260 && height <= 260)
    }

    private fun hasContentDescendant(view: View, maxDepth: Int = 3, maxViews: Int = 48): Boolean {
        if (view !is android.view.ViewGroup) return false
        var visited = 0

        fun walk(current: View, depth: Int): Boolean {
            if (visited++ >= maxViews || depth > maxDepth) return false
            if (current !== view && containsContentToken(viewMatchName(current))) return true
            if (current is android.view.ViewGroup) {
                for (index in 0 until current.childCount) {
                    if (walk(current.getChildAt(index), depth + 1)) return true
                    if (visited >= maxViews) break
                }
            }
            return false
        }

        return walk(view, 0)
    }

    private fun isInsideImagePreview(view: View, maxDepth: Int = 5): Boolean {
        var parent = view.parent as? View
        var depth = 0
        while (parent is android.view.ViewGroup && depth < maxDepth) {
            if (hasDominantImageChild(parent)) return true
            parent = parent.parent as? View
            depth++
        }
        return false
    }

    private fun hasDominantImageChild(group: android.view.ViewGroup): Boolean {
        val width = group.width
        val height = group.height
        if (width < 280 || height < 180) return false

        for (index in 0 until group.childCount) {
            val child = group.getChildAt(index) ?: continue
            val name = viewMatchName(child)
            val isImage = "imageview" in name || "photo" in name || "thumbnail" in name
            if (!isImage) continue
            if (child.width >= (width * 0.72f).toInt() && child.height >= (height * 0.55f).toInt()) {
                return true
            }
        }
        return false
    }

    private fun hasDescendantNamed(
        view: View,
        maxDepth: Int,
        predicate: (String) -> Boolean
    ): Boolean {
        if (view !is android.view.ViewGroup) return false
        var visited = 0

        fun walk(current: View, depth: Int): Boolean {
            if (visited++ > 24 || depth > maxDepth) return false
            if (current !== view && predicate(viewMatchName(current))) return true
            if (current is android.view.ViewGroup) {
                for (index in 0 until current.childCount) {
                    if (walk(current.getChildAt(index), depth + 1)) return true
                    if (visited > 24) break
                }
            }
            return false
        }

        return walk(view, 0)
    }

    private fun clearImagePreviewOverlayBackground(view: View): Boolean {
        if (!isInsideImagePreview(view)) return false
        if (view.width <= 0 || view.height <= 0) return false

        val name = viewMatchName(view)
        if ("textview" in name || "edittext" in name || "button" in name) return false
        if (view.width > 260 || view.height > 260) return false
        val isImageView = "imageview" in name
        if (!isImageView && !hasDescendantNamed(view, maxDepth = 2) { "imageview" in it }) return false
        if (!isImageView && hasDescendantNamed(view, maxDepth = 2) { "textview" in it }) return false

        val background = view.background ?: return clearImagePreviewOverlayForeground(view)
        if (background is ColorDrawable) {
            if (Color.alpha(background.color) == 0) return clearImagePreviewOverlayForeground(view)
            if ((background.color and 0x00FFFFFF) != 0 && !isPatchableSurfaceOrOverlayColor(background.color)) {
                return clearImagePreviewOverlayForeground(view)
            }
        }

        val clearedBackground = runCatching {
            internalThemeMutation.set(true)
            val before = drawableSummary(view.background)
            view.background = null
            view.setTag(viewAppliedThemeColorTag, Color.TRANSPARENT)
            view.setTag(viewAppliedThemeBackgroundTag, null)
            amoledTrace("image-preview", "clearBackground|${viewTraceKey(view)}|$before", maxPerCategory = 400) {
                "cleared preview overlay background before=$before ${viewSummary(view)}"
            }
        }.also {
            internalThemeMutation.remove()
        }.isSuccess
        return clearImagePreviewOverlayForeground(view) || clearedBackground
    }

    private fun clearImagePreviewOverlayForeground(view: View): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        val foreground = view.foreground as? ColorDrawable ?: return false
        if (Color.alpha(foreground.color) == 0) return false
        if ((foreground.color and 0x00FFFFFF) != 0 && !isPatchableSurfaceOrOverlayColor(foreground.color)) return false
        return runCatching {
            internalThemeMutation.set(true)
            val before = colorHex(foreground.color)
            view.foreground = null
            amoledTrace("image-preview", "clearForeground|${viewTraceKey(view)}|$before", maxPerCategory = 400) {
                "cleared preview overlay foreground before=$before ${viewSummary(view)}"
            }
        }.also {
            internalThemeMutation.remove()
        }.isSuccess
    }

    private fun hasExistingGlobalSurfaceMaterial(view: View): Boolean {
        if (drawableMayRepresentSurface(view.background)) return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP &&
            view.backgroundTintList?.defaultColor?.let(::isPatchableSurfaceOrOverlayColor) == true
        ) {
            return true
        }
        return false
    }

    private fun isKnownGlobalBackgroundHost(view: View, resourceName: String?, matchName: String): Boolean {
        if (resourceName != null && isNamedAndroidSurface(view)) return true
        if (resourceName != null && globalBackgroundCatalogTargets.any { target ->
            target == normalizeSurfaceKey(resourceName) || target == "android.view.${normalizeSurfaceKey(resourceName)}"
        }) {
            return true
        }
        return listOf(
            "recyclerview",
            "scrollview",
            "pulltorefresh",
            "ptr_container",
            "page",
            "screen",
            "background",
            "contentpanel",
            "custompanel"
        ).any { it in matchName }
    }

    private fun isGenericFullScreenGlobalOverlay(
        view: View,
        resourceName: String?,
        width: Int,
        height: Int,
        displayWidth: Int,
        displayHeight: Int
    ): Boolean {
        if (resourceName != null) return false
        val className = view.javaClass.name
        val genericClass = className == "android.view.View" ||
            className == "android.widget.FrameLayout" ||
            className == "android.widget.RelativeLayout" ||
            className == "android.widget.LinearLayout"
        if (!genericClass) return false
        return width >= (displayWidth * 0.94f).toInt() &&
            height >= (displayHeight * 0.80f).toInt()
    }

    private fun globalBackgroundHostDecision(view: View): GlobalBackgroundHostDecision {
        if (isExcludedAndroidView(view)) {
            return GlobalBackgroundHostDecision(false, "excluded_content_or_media_ancestor name=${viewMatchName(view).take(220)}")
        }
        if (isSmallInteractiveControl(view)) {
            return GlobalBackgroundHostDecision(false, "small_interactive_control ${view.width}x${view.height}")
        }
        val root = view.rootView ?: return GlobalBackgroundHostDecision(false, "missing_root")
        val displayMetrics = context.androidContext.resources.displayMetrics
        val decor = currentActivityRef?.get()?.window?.decorView
        val rootActualWidth = root.width.takeIf { it > 0 } ?: 0
        val rootActualHeight = root.height.takeIf { it > 0 } ?: 0
        val decorWidth = decor?.width?.takeIf { it > 0 } ?: 0
        val decorHeight = decor?.height?.takeIf { it > 0 } ?: 0
        val displayWidth = displayMetrics.widthPixels
        val displayHeight = displayMetrics.heightPixels
        val rootWidth = maxOf(rootActualWidth, decorWidth, displayWidth)
        val rootHeight = maxOf(rootActualHeight, decorHeight, displayHeight)
        val width = view.width
        val height = view.height
        val scaleDetails = "root=${rootActualWidth}x${rootActualHeight} decor=${decorWidth}x${decorHeight} display=${displayWidth}x${displayHeight} effective=${rootWidth}x${rootHeight}"
        if (width <= 0 || height <= 0) {
            return GlobalBackgroundHostDecision(false, "unmeasured ${width}x$height $scaleDetails")
        }
        val minWidth = maxOf((rootWidth * 0.72f).toInt(), (displayWidth * 0.72f).toInt(), 360)
        val minHeight = maxOf((rootHeight * 0.12f).toInt(), (displayHeight * 0.12f).toInt(), 240)
        if (width < minWidth || height < minHeight) {
            return GlobalBackgroundHostDecision(false, "too_small_for_global ${width}x$height min=${minWidth}x$minHeight $scaleDetails")
        }
        val resourceName = getResourceEntryName(view)
        val matchName = viewMatchName(view)
        if (isGenericFullScreenGlobalOverlay(view, resourceName, width, height, displayWidth, displayHeight)) {
            return GlobalBackgroundHostDecision(
                false,
                "generic_fullscreen_overlay_no_resource class=${view.javaClass.name} view=${width}x$height $scaleDetails"
            )
        }
        val hasSurfaceMaterial = hasExistingGlobalSurfaceMaterial(view)
        val knownHost = isKnownGlobalBackgroundHost(view, resourceName, matchName)
        if (!hasSurfaceMaterial && !knownHost) {
            return GlobalBackgroundHostDecision(
                false,
                "no_global_surface_signal material=$hasSurfaceMaterial knownHost=$knownHost resource=${resourceName ?: "none"} " +
                    "class=${view.javaClass.name} view=${width}x$height $scaleDetails name=${matchName.take(180)}"
            )
        }
        val hasContent = hasContentDescendant(view, maxDepth = 2, maxViews = 36)
        if (hasContent) {
            return GlobalBackgroundHostDecision(
                false,
                "content_descendant material=$hasSurfaceMaterial knownHost=$knownHost resource=${resourceName ?: "none"} " +
                    "$scaleDetails view=${width}x$height"
            )
        }
        return GlobalBackgroundHostDecision(
            true,
            "large_empty_surface material=$hasSurfaceMaterial knownHost=$knownHost resource=${resourceName ?: "none"} " +
                "$scaleDetails view=${width}x$height min=${minWidth}x$minHeight"
        )
    }

    private fun isGlobalBackgroundHost(view: View): Boolean {
        return globalBackgroundHostDecision(view).matched
    }

    private fun patchUnknownDrawable(drawable: Drawable): Boolean {
        if (drawable.javaClass.name.startsWith("android.")) return false
        var changed = false
        var handled = false
        for (field in drawableReflectionFields(drawable)) {
            val value = runCatching {
                field.isAccessible = true
                field.get(drawable)
            }.getOrNull() ?: continue
            val beforeSummary = reflectionFieldValueSummary(value)
            var fieldChanged = false
            var fieldHandled = false
            when (value) {
                is ColorStateList -> {
                    if (hasVisibleDrawableColor(value.defaultColor)) {
                        fieldHandled = true
                        handled = true
                        runCatching {
                            if (needsAmoledDrawableColor(value.defaultColor)) {
                                field.set(drawable, ColorStateList.valueOf(canvasAmoledColor(value.defaultColor)))
                                fieldChanged = true
                                changed = true
                            }
                        }
                    }
                }
                is Paint -> {
                    if (value.style != Paint.Style.STROKE && hasVisibleDrawableColor(value.color)) {
                        fieldHandled = true
                        handled = true
                        if (needsAmoledDrawableColor(value.color)) {
                            value.color = canvasAmoledColor(value.color)
                            fieldChanged = true
                            changed = true
                        }
                    }
                }
                is Drawable -> {
                    val childHandled = patchDrawable(value)
                    changed = childHandled || changed
                    handled = childHandled || handled
                    fieldHandled = childHandled
                    fieldChanged = childHandled
                }
                is IntArray -> {
                    var arrayHandled = false
                    var arrayChanged = false
                    for (index in value.indices) {
                        val color = value[index]
                        if (hasVisibleDrawableColor(color)) {
                            arrayHandled = true
                            if (needsAmoledDrawableColor(color)) {
                                value[index] = canvasAmoledColor(color)
                                arrayChanged = true
                            }
                        }
                    }
                    if (arrayChanged) {
                        changed = true
                    }
                    handled = arrayHandled || handled
                    fieldHandled = arrayHandled
                    fieldChanged = arrayChanged
                }
                is Int -> {
                    if ((hasVisibleDrawableColor(value) || colorLikeFieldName(field.name)) && hasVisibleDrawableColor(value)) {
                        fieldHandled = true
                        handled = true
                        runCatching {
                            if (needsAmoledDrawableColor(value)) {
                                field.setInt(drawable, canvasAmoledColor(value))
                                fieldChanged = true
                                changed = true
                            }
                        }
                    }
                }
            }
            if (fieldHandled || colorLikeFieldName(field.name)) {
                amoledTrace("drawable-reflect-field", "${drawable.javaClass.name}|${field.declaringClass.name}|${field.name}|handled=$fieldHandled|changed=$fieldChanged|$beforeSummary", maxPerCategory = 2200) {
                    "drawable=${drawable.javaClass.name} field=${field.declaringClass.name}.${field.name} " +
                        "type=${field.type.name} handled=$fieldHandled changed=$fieldChanged before=$beforeSummary " +
                        "after=${runCatching { reflectionFieldValueSummary(field.get(drawable)) }.getOrNull() ?: "unreadable"}"
                }
            }
        }
        if (changed) {
            runCatching { drawable.invalidateSelf() }
        }
        logUnknownDrawable("patchUnknownDrawable handled=$handled", drawable, changed)
        return changed || handled
    }

    private fun patchDrawable(drawable: Drawable): Boolean {
        return when (drawable) {
            is ColorDrawable -> {
                val color = drawable.color
                if (!isPatchableSurfaceOrOverlayColor(color)) {
                    amoledTrace("drawable", "skip|ColorDrawable|${colorHex(color)}", maxPerCategory = 900) {
                        "skip ColorDrawable color=${colorHex(color)} alpha=${Color.alpha(color)} patchable=false"
                    }
                    return false
                }
                val patchedColor = amoledSurfaceColorFor(color)
                runCatching {
                    drawable.mutate()
                    drawable.color = patchedColor
                    amoledTrace("drawable", "ColorDrawable|${colorHex(color)}", maxPerCategory = 600) {
                        "patched ColorDrawable from=${colorHex(color)} to=${colorHex(patchedColor)}"
                    }
                }.isSuccess
            }
            is GradientDrawable -> patchGradientDrawable(drawable)
            is ShapeDrawable -> {
                val color = drawable.paint.color
                if (!isPatchableSurfaceOrOverlayColor(color)) {
                    amoledTrace("drawable", "skip|ShapeDrawable|${colorHex(color)}", maxPerCategory = 900) {
                        "skip ShapeDrawable color=${colorHex(color)} alpha=${Color.alpha(color)} patchable=false"
                    }
                    return false
                }
                val patchedColor = amoledSurfaceColorFor(color)
                runCatching {
                    drawable.mutate()
                    drawable.paint.color = patchedColor
                    amoledTrace("drawable", "ShapeDrawable|${colorHex(color)}", maxPerCategory = 600) {
                        "patched ShapeDrawable from=${colorHex(color)} to=${colorHex(patchedColor)}"
                    }
                }.isSuccess
            }
            is RippleDrawable -> patchLayerDrawable(drawable)
            is LayerDrawable -> patchLayerDrawable(drawable)
            is InsetDrawable -> {
                val inner = runCatching { drawable.drawable }.getOrNull()
                    ?: runCatching { drawable.getObjectField("mDrawable") as? Drawable }.getOrNull()
                    ?: run {
                        amoledTrace("drawable", "skip|InsetDrawable|no-inner|${drawable.javaClass.name}", maxPerCategory = 500) {
                            "skip InsetDrawable without readable inner drawable=${drawableSummary(drawable)}"
                        }
                        return false
                    }
                patchDrawable(inner)
            }
            else -> patchUnknownDrawable(drawable)
        }
    }

    private fun patchGradientDrawable(drawable: GradientDrawable): Boolean {
        val color = gradientDrawableSolidColor(drawable)
        if (color != null && isPatchableSurfaceOrOverlayColor(color)) {
            val patchedColor = amoledSurfaceColorFor(color)
            return runCatching {
                drawable.mutate()
                drawable.setColor(patchedColor)
                amoledTrace("drawable", "GradientDrawable|solid|${colorHex(color)}", maxPerCategory = 600) {
                    "patched GradientDrawable solid from=${colorHex(color)} to=${colorHex(patchedColor)}"
                }
            }.isSuccess
        }
        val colors = gradientDrawableColors(drawable)
        if (colors != null) {
            var changed = false
            val patchedColors = IntArray(colors.size) { index ->
                val original = colors[index]
                if (isPatchableSurfaceOrOverlayColor(original)) {
                    changed = true
                    amoledSurfaceColorFor(original)
                } else {
                    original
                }
            }
            if (changed) {
                return runCatching {
                    drawable.mutate()
                    drawable.setColors(patchedColors)
                    amoledTrace("drawable", "GradientDrawable|colors|${colors.joinToString { colorHex(it) }}", maxPerCategory = 600) {
                        "patched GradientDrawable colors from=${colors.joinToString { colorHex(it) }} " +
                            "to=${patchedColors.joinToString { colorHex(it) }}"
                    }
                }.isSuccess
            }
        }
        amoledTrace("drawable", "skip|GradientDrawable|${colorHex(color)}|${drawableSummary(drawable)}", maxPerCategory = 900) {
            "skip GradientDrawable solid=${colorHex(color)} colors=${colors?.joinToString { colorHex(it) } ?: "none"} " +
                "patchableSolid=${color?.let(::isPatchableSurfaceOrOverlayColor) ?: false} " +
                "patchableColors=${colors?.any(::isPatchableSurfaceOrOverlayColor) ?: false} drawable=${drawableSummary(drawable)}"
        }
        return false
    }

    private fun patchLayerDrawable(drawable: LayerDrawable): Boolean {
        var changed = false
        for (index in 0 until drawable.numberOfLayers) {
            val layer = runCatching { drawable.getDrawable(index) }.getOrNull()
            val layerChanged = layer?.let(::patchDrawable) == true
            if (layerChanged) {
                amoledTrace("drawable", "LayerDrawable|${drawable.javaClass.name}|$index", maxPerCategory = 600) {
                    "patched layer index=$index layerCount=${drawable.numberOfLayers} drawable=${drawableSummary(drawable)}"
                }
            } else {
                amoledTrace("drawable", "skip|LayerDrawable|${drawable.javaClass.name}|$index|${layer?.javaClass?.name ?: "null"}", maxPerCategory = 900) {
                    "skip layer index=$index layerCount=${drawable.numberOfLayers} layer=${drawableSummary(layer)} parent=${drawableSummary(drawable)}"
                }
            }
            changed = layerChanged || changed
        }
        return changed
    }

    private fun attachPatchSignature(runtime: ThemeRuntime): Int {
        val preview = activePreviewRule()
        var result = if (runtime.forceAmoled) 1 else 0
        result = 31 * result + if (runtime.customEnabled) 1 else 0
        result = 31 * result + runtime.rawSurfaceOverrides.hashCode()
        result = 31 * result + runtime.rawSurfaceRules.hashCode()
        result = 31 * result + runtime.rawScreenBackgrounds.hashCode()
        result = 31 * result + (preview?.signature ?: 0)
        return result
    }

    private fun patchAttachedViewBeforeDraw(view: View) {
        val runtime = themeRuntime()
        val activePatching = hasActivePatchesOrPreview(runtime)
        val observing = shouldObserveThemeSurfaceMap(runtime)
        if (!activePatching && !observing) return
        if (activePatching && !shouldRunAndroidAttachPatch(view, runtime)) return
        if (observing) {
            val hasPatchableTint = Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP &&
                view.backgroundTintList?.defaultColor?.let(::isPatchableSurfaceOrOverlayColor) == true
            val drawableSurface = drawableMayRepresentSurface(view.background)
            val catalogColor = customCatalogColorForView(view, runtime)
            val observable = shouldObserveAndroidSurfaceCandidate(
                view = view,
                runtime = runtime,
                hasPatchableTint = hasPatchableTint,
                drawableSurface = drawableSurface,
                catalogColor = catalogColor,
                extraCandidate = activePatching
            )
            if (observable) {
                observeAndroidViewThemeSurface(
                    source = "View.onAttachedToWindow",
                    view = view,
                    reason = "activePatching=$activePatching tint=$hasPatchableTint " +
                        "drawableSurface=$drawableSurface catalogColor=${colorHex(catalogColor)}",
                    runtime = runtime
                )
            }
        }
        if (!activePatching) return
        val signature = attachPatchSignature(runtime)
        if (view.getTag(viewAttachPatchSignatureTag) == signature) {
            logAndroidSurfaceDecision("View.onAttachedToWindow", view, "skip_same_attach_signature signature=$signature", runtime)
            return
        }
        view.setTag(viewAttachPatchSignatureTag, signature)
        val changed = patchExistingAndroidSurface(view, allowPreLayout = true, runtime = runtime)
        logAndroidSurfaceDecision("View.onAttachedToWindow", view, "patched_before_draw changed=$changed signature=$signature", runtime)
    }

    private fun patchViewColorArg(param: HookAdapter, index: Int) {
        val runtime = themeRuntime()
        val activePatching = hasActivePatchesOrPreview(runtime)
        val observing = shouldObserveThemeSurfaceMap(runtime)
        if (internalThemeMutation.get() == true || (!activePatching && !observing)) return
        val view = param.nullableThisObject<View>() ?: return
        val color = param.args().getOrNull(index) as? Int
        if (observing && color != null) {
            val hasPatchableTint = Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP &&
                view.backgroundTintList?.defaultColor?.let(::isPatchableSurfaceOrOverlayColor) == true
            val drawableSurface = drawableMayRepresentSurface(view.background)
            val catalogColor = customCatalogColorForView(view, runtime)
            val colorSurface = shouldPatchBackgroundColorArgAsSurface(view, color)
            if (shouldObserveAndroidSurfaceCandidate(
                    view = view,
                    runtime = runtime,
                    hasPatchableTint = hasPatchableTint,
                    drawableSurface = drawableSurface,
                    catalogColor = catalogColor,
                    extraCandidate = colorSurface
                )
            ) {
                observeAndroidViewThemeSurface(
                    source = "View.setBackgroundColor",
                    view = view,
                    reason = "argColor=${colorHex(color)} activePatching=$activePatching colorSurface=$colorSurface " +
                        "tint=$hasPatchableTint drawableSurface=$drawableSurface catalogColor=${colorHex(catalogColor)}",
                    runtime = runtime
                )
            }
        }
        if (!activePatching) return
        val evaluateCustomTheme = shouldEvaluateCustomThemeForView(view, runtime)
        if (evaluateCustomTheme) {
            if (customBackgroundForView(view, runtime) != null) return
            val customMatch = themeMatchForView(view, runtime)
            customMatch.color?.let { color ->
                if (shouldApplyCustomColorAsImageTint(view, customMatch.colorSource)) {
                    val changed = applyMatchedCustomColor(view, color, customMatch.colorSource)
                    logAndroidSurfaceDecision(
                        "View.setBackgroundColor",
                        view,
                        "custom_image_tint ${colorHex(color)} source=${customMatch.colorSource ?: "unknown"} changed=$changed",
                        runtime
                    )
                    return
                }
                val changed = applyMatchedCustomColor(view, color, customMatch.colorSource)
                if (changed) {
                    param.setResult(null)
                    logAndroidSurfaceDecision(
                        "View.setBackgroundColor",
                        view,
                        "custom_preserve_background ${colorHex(color)} source=${customMatch.colorSource ?: "unknown"} changed=true skippedOriginal=true",
                        runtime
                    )
                    return
                }
                param.setArg(index, color)
                logAndroidSurfaceDecision(
                    "View.setBackgroundColor",
                    view,
                    "custom_arg_color_fallback ${colorHex(color)} source=${customMatch.colorSource ?: "unknown"} changed=false",
                    runtime
                )
                return
            }
        }
        if (!runtime.forceAmoled) return
        val originalColor = color ?: return
        if (shouldPatchBackgroundColorArgAsSurface(view, originalColor)) {
            val patchedColor = amoledSurfaceColorFor(originalColor)
            param.setArg(index, patchedColor)
            logAndroidSurfaceDecision("View.setBackgroundColor", view, "amoled_arg_color ${colorHex(originalColor)}->${colorHex(patchedColor)}", runtime)
        } else {
            logAndroidSurfaceDecision("View.setBackgroundColor", view, "skip_unpatchable_arg_color ${colorHex(originalColor)}", runtime)
        }
    }

    private fun patchViewColorStateListArg(param: HookAdapter, index: Int) {
        val runtime = themeRuntime()
        val activePatching = hasActivePatchesOrPreview(runtime)
        val observing = shouldObserveThemeSurfaceMap(runtime)
        if (internalThemeMutation.get() == true || (!activePatching && !observing)) return
        val view = param.nullableThisObject<View>() ?: return
        val colorStateList = param.args().getOrNull(index) as? ColorStateList
        if (observing && colorStateList != null) {
            val hasPatchableTint = colorStateList.defaultColor.let(::isPatchableSurfaceOrOverlayColor)
            val drawableSurface = drawableMayRepresentSurface(view.background)
            val catalogColor = customCatalogColorForView(view, runtime)
            val colorSurface = shouldPatchBackgroundColorArgAsSurface(view, colorStateList.defaultColor)
            if (shouldObserveAndroidSurfaceCandidate(
                    view = view,
                    runtime = runtime,
                    hasPatchableTint = hasPatchableTint,
                    drawableSurface = drawableSurface,
                    catalogColor = catalogColor,
                    extraCandidate = colorSurface
                )
            ) {
                observeAndroidViewThemeSurface(
                    source = "View.setBackgroundTintList",
                    view = view,
                    reason = "argTint=${colorHex(colorStateList.defaultColor)} activePatching=$activePatching " +
                        "colorSurface=$colorSurface tint=$hasPatchableTint drawableSurface=$drawableSurface " +
                        "catalogColor=${colorHex(catalogColor)}",
                    runtime = runtime
                )
            }
        }
        if (!activePatching) return
        val evaluateCustomTheme = shouldEvaluateCustomThemeForView(view, runtime)
        if (evaluateCustomTheme) {
            if (customBackgroundForView(view, runtime) != null) return
            val customMatch = themeMatchForView(view, runtime)
            customMatch.color?.let { color ->
                if (shouldApplyCustomColorAsImageTint(view, customMatch.colorSource)) {
                    val changed = applyMatchedCustomColor(view, color, customMatch.colorSource)
                    logAndroidSurfaceDecision(
                        "View.setBackgroundTintList",
                        view,
                        "custom_image_tint ${colorHex(color)} source=${customMatch.colorSource ?: "unknown"} changed=$changed",
                        runtime
                    )
                    return
                }
                val changed = applyMatchedCustomColor(view, color, customMatch.colorSource)
                if (changed) {
                    param.setResult(null)
                    logAndroidSurfaceDecision(
                        "View.setBackgroundTintList",
                        view,
                        "custom_preserve_background ${colorHex(color)} source=${customMatch.colorSource ?: "unknown"} changed=true skippedOriginal=true",
                        runtime
                    )
                    return
                }
                param.setArg(index, ColorStateList.valueOf(color))
                logAndroidSurfaceDecision(
                    "View.setBackgroundTintList",
                    view,
                    "custom_tint_fallback ${colorHex(color)} source=${customMatch.colorSource ?: "unknown"} changed=false",
                    runtime
                )
                return
            }
        }
        if (!runtime.forceAmoled) return
        val originalTint = colorStateList ?: return
        if (shouldPatchBackgroundColorArgAsSurface(view, originalTint.defaultColor)) {
            val patchedTint = amoledSurfaceColorFor(originalTint.defaultColor)
            param.setArg(index, ColorStateList.valueOf(patchedTint))
            logAndroidSurfaceDecision(
                "View.setBackgroundTintList",
                view,
                "amoled_tint ${colorHex(originalTint.defaultColor)}->${colorHex(patchedTint)}",
                runtime
            )
        } else {
            logAndroidSurfaceDecision("View.setBackgroundTintList", view, "skip_unpatchable_tint ${colorHex(originalTint.defaultColor)}", runtime)
        }
    }

    private fun patchViewDrawableArg(param: HookAdapter, index: Int) {
        val runtime = themeRuntime()
        val activePatching = hasActivePatchesOrPreview(runtime)
        val observing = shouldObserveThemeSurfaceMap(runtime)
        if (internalThemeMutation.get() == true || (!activePatching && !observing)) return
        val view = param.nullableThisObject<View>() ?: return
        val drawable = param.args().getOrNull(index) as? Drawable
        if (observing && drawable != null) {
            val hasPatchableTint = Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP &&
                view.backgroundTintList?.defaultColor?.let(::isPatchableSurfaceOrOverlayColor) == true
            val drawableSurface = drawableMayRepresentSurface(drawable) || drawableMayRepresentSurface(view.background)
            val catalogColor = customCatalogColorForView(view, runtime)
            val drawableArgSurface = shouldPatchBackgroundArgAsSurface(view, drawable)
            if (shouldObserveAndroidSurfaceCandidate(
                    view = view,
                    runtime = runtime,
                    hasPatchableTint = hasPatchableTint,
                    drawableSurface = drawableSurface,
                    catalogColor = catalogColor,
                    extraCandidate = drawableArgSurface
                )
            ) {
                observeAndroidViewThemeSurface(
                    source = "View.setBackground",
                    view = view,
                    reason = "argDrawable=${drawableSummary(drawable)} activePatching=$activePatching " +
                        "drawableArgSurface=$drawableArgSurface tint=$hasPatchableTint drawableSurface=$drawableSurface " +
                        "catalogColor=${colorHex(catalogColor)}",
                    runtime = runtime
                )
            }
        }
        if (!activePatching) return
        val evaluateCustomTheme = shouldEvaluateCustomThemeForView(view, runtime)
        if (evaluateCustomTheme) {
            customBackgroundForView(view, runtime)?.let { path ->
                drawableForBackgroundPath(path)?.let { drawable ->
                    param.setArg(index, drawable)
                    view.setTag(viewAppliedThemeBackgroundTag, path)
                    view.setTag(viewAppliedThemeColorTag, null)
                    logAndroidSurfaceDecision("View.setBackground", view, "custom_drawable_arg path=$path drawable=${drawableSummary(drawable)}", runtime)
                    return
                }
            }
            val customMatch = themeMatchForView(view, runtime)
            customMatch.color?.let { color ->
                if (shouldApplyCustomColorAsImageTint(view, customMatch.colorSource)) {
                    val changed = applyMatchedCustomColor(view, color, customMatch.colorSource)
                    logAndroidSurfaceDecision(
                        "View.setBackground",
                        view,
                        "custom_image_tint ${colorHex(color)} source=${customMatch.colorSource ?: "unknown"} changed=$changed",
                        runtime
                    )
                    return
                }
                val themedDrawable = customColoredDrawable(drawable, color, "View.setBackground:${viewTraceKey(view)}")
                param.setArg(index, themedDrawable)
                logAndroidSurfaceDecision(
                    "View.setBackground",
                    view,
                    "custom_color_drawable_arg_preserve ${colorHex(color)} source=${customMatch.colorSource ?: "unknown"} " +
                        "argBefore=${drawableSummary(drawable)} argAfter=${drawableSummary(themedDrawable)}",
                    runtime
                )
                return
            }
        }
        if (!runtime.forceAmoled) return
        val originalDrawable = drawable ?: return
        if (!shouldPatchBackgroundArgAsSurface(view, originalDrawable)) {
            logAndroidSurfaceDecision("View.setBackground", view, "skip_not_surface_drawable_arg ${drawableSummary(originalDrawable)}", runtime)
            return
        }
        if (patchDrawable(originalDrawable)) {
            param.setArg(index, originalDrawable)
            logAndroidSurfaceDecision("View.setBackground", view, "amoled_drawable_arg ${drawableSummary(originalDrawable)}", runtime)
        } else {
            logAndroidSurfaceDecision("View.setBackground", view, "skip_unpatchable_drawable_arg ${drawableSummary(originalDrawable)}", runtime)
        }
    }

    private fun patchWindow(window: Window?) {
        if (!themeRuntime().forceAmoled) return
        window ?: return
        val oldStatus = window.statusBarColor
        val oldNav = window.navigationBarColor
        if (window.statusBarColor != amoledBlack) window.statusBarColor = amoledBlack
        if (window.navigationBarColor != amoledBlack) window.navigationBarColor = amoledBlack
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && window.navigationBarDividerColor != amoledBlack) {
            window.navigationBarDividerColor = amoledBlack
        }
        amoledTrace("window", "${window.javaClass.name}|${colorHex(oldStatus)}|${colorHex(oldNav)}", maxPerCategory = 120) {
            "patched window=${window.javaClass.name} status=${colorHex(oldStatus)}->${colorHex(window.statusBarColor)} " +
                "nav=${colorHex(oldNav)}->${colorHex(window.navigationBarColor)}"
        }
    }

    private fun installHook(name: String, block: () -> Unit) {
        runCatching(block).onSuccess {
            amoledTrace("hook-install", name, maxPerCategory = 120) {
                "installed $name"
            }
        }.onFailure {
            context.log.error("[AMOLED PATCH] Failed to install $name hook", it)
        }
    }

    private fun installConcreteWindowColorHooks() {
        val phoneWindowClass = runCatching {
            Class.forName("com.android.internal.policy.PhoneWindow")
        }.getOrNull() ?: return

        installHook("PhoneWindow.setStatusBarColor") {
            Hooker.hook(phoneWindowClass, "setStatusBarColor", HookStage.BEFORE) { param ->
                if (!themeRuntime().forceAmoled) return@hook
                val color = param.args().getOrNull(0) as? Int ?: return@hook
                if (isPatchableSurfaceColor(color)) {
                    param.setArg(0, amoledBlack)
                    amoledTrace("window", "setStatusBarColor|${colorHex(color)}", maxPerCategory = 120) {
                        "PhoneWindow.setStatusBarColor ${colorHex(color)}->${colorHex(amoledBlack)}"
                    }
                }
            }
        }

        installHook("PhoneWindow.setNavigationBarColor") {
            Hooker.hook(phoneWindowClass, "setNavigationBarColor", HookStage.BEFORE) { param ->
                if (!themeRuntime().forceAmoled) return@hook
                val color = param.args().getOrNull(0) as? Int ?: return@hook
                if (isPatchableSurfaceColor(color)) {
                    param.setArg(0, amoledBlack)
                    amoledTrace("window", "setNavigationBarColor|${colorHex(color)}", maxPerCategory = 120) {
                        "PhoneWindow.setNavigationBarColor ${colorHex(color)}->${colorHex(amoledBlack)}"
                    }
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            installHook("PhoneWindow.setNavigationBarDividerColor") {
                Hooker.hook(phoneWindowClass, "setNavigationBarDividerColor", HookStage.BEFORE) { param ->
                    if (!themeRuntime().forceAmoled) return@hook
                    val color = param.args().getOrNull(0) as? Int ?: return@hook
                    if (isPatchableSurfaceColor(color)) {
                        param.setArg(0, amoledBlack)
                        amoledTrace("window", "setNavigationBarDividerColor|${colorHex(color)}", maxPerCategory = 120) {
                            "PhoneWindow.setNavigationBarDividerColor ${colorHex(color)}->${colorHex(amoledBlack)}"
                        }
                    }
                }
            }
        }
    }

    private fun installValdiComposerHooks() {
        logNativeBridgeDiagnostics()
        installHook("NativeBridge.setValueForAttribute") {
            context.classCache.nativeBridge.hook("setValueForAttribute", HookStage.BEFORE) { param ->
                patchValdiSetAttributeArg(param)
            }
        }

        installHook("NativeBridge.getValueForAttribute") {
            context.classCache.nativeBridge.hook("getValueForAttribute", HookStage.AFTER) { param ->
                patchValdiGetAttributeResult(param)
            }
        }

        context.event.subscribe(AddViewEvent::class) { event ->
            patchAndroidAddedSurface(event)
            installValdiPreDrawPatches(event)
        }
    }

    private fun installHooks() {
        if (!hookInstallLogged) {
            context.log.verbose("[THEME PATCH] Custom theme hooks installed")
            hookInstallLogged = true
        }

        installHook("Resources.Theme.obtainStyledAttributes") {
            Resources.Theme::class.java.hook("obtainStyledAttributes", HookStage.AFTER) { param ->
                val requestedAttrs = param.args().firstOrNull { it is IntArray } as? IntArray
                val result = param.getResult() as? TypedArray ?: return@hook
                patchTypedArray(result, requestedAttrs)
            }
        }

        installHook("Context.obtainStyledAttributes") {
            context.androidContext.javaClass.hook("obtainStyledAttributes", HookStage.AFTER) { param ->
                val requestedAttrs = param.args().firstOrNull { it is IntArray } as? IntArray
                val result = param.getResult() as? TypedArray ?: return@hook
                patchTypedArray(result, requestedAttrs)
            }
        }

        installConcreteWindowColorHooks()
        installValdiComposerHooks()

        installHook("View.onAttachedToWindow.applyTheme") {
            View::class.java.hook("onAttachedToWindow", HookStage.BEFORE) { param ->
                if (internalThemeMutation.get() == true) return@hook
                val view = param.nullableThisObject<View>() ?: return@hook
                patchAttachedViewBeforeDraw(view)
            }
        }

        installHook("View.onAttachedToWindow.applyVirtualThemeAfter") {
            View::class.java.hook("onAttachedToWindow", HookStage.AFTER) { param ->
                if (internalThemeMutation.get() == true) return@hook
                val view = param.nullableThisObject<View>() ?: return@hook
                applyVirtualThemeHostFromLifecycle(view, "onAttachedAfter")
            }
        }

        installHook("View.layout.applyVirtualThemeAfter") {
            View::class.java.hook("layout", HookStage.AFTER) { param ->
                if (internalThemeMutation.get() == true) return@hook
                val view = param.nullableThisObject<View>() ?: return@hook
                applyVirtualThemeHostFromLifecycle(view, "layoutAfter")
            }
        }

        installHook("View.onDetachedFromWindow.clearVirtualTheme") {
            View::class.java.hook("onDetachedFromWindow", HookStage.BEFORE) { param ->
                if (internalThemeMutation.get() == true) return@hook
                val view = param.nullableThisObject<View>() ?: return@hook
                view.setTag(virtualThemeLifecycleRetryTag, null)
                view.setTag(virtualThemeDrawApplySignatureTag, null)
                if (clearVirtualThemeOverlays(view)) {
                    amoledTrace("virtual-theme-lifecycle", "detached-clear|${viewTraceKey(view)}", maxPerCategory = 900) {
                        "cleared virtual overlays before detach host=${viewSummary(view)}"
                    }
                }
            }
        }

        installHook("View.draw.trackThemeSurface") {
            View::class.java.hook("draw", HookStage.BEFORE) { param ->
                if (internalThemeMutation.get() == true) return@hook
                val runtime = themeRuntime()
                val virtualThemeActive = hasSnapchatVirtualThemeSelectors(runtime)
                if (!runtime.forceAmoled && !virtualThemeActive) return@hook
                val active = pushThemeDrawSurface(param.nullableThisObject())
                if (virtualThemeActive && active != null) {
                    val signature = virtualThemeDrawApplySignature(active, runtime)
                    if (active.getTag(virtualThemeDrawApplySignatureTag) != signature) {
                        active.setTag(virtualThemeDrawApplySignatureTag, signature)
                        applyVirtualThemeHostFromLifecycle(
                            view = active,
                            trigger = "drawBefore",
                            runtime = runtime,
                            scheduleRetry = false
                        )
                    }
                }
            }
            View::class.java.hook("draw", HookStage.AFTER) {
                popThemeDrawSurface()
            }
        }

        installHook("Canvas.drawRoundRect.applyAmoledSurface") {
            Canvas::class.java.hook("drawRoundRect", HookStage.BEFORE) { param ->
                patchCanvasSurfacePaintArg(param)
            }
        }

        installHook("Canvas.drawRect.applyAmoledSurface") {
            Canvas::class.java.hook("drawRect", HookStage.BEFORE) { param ->
                patchCanvasSurfacePaintArg(param)
            }
        }

        installHook("Canvas.drawText.applyVirtualThemeText") {
            Canvas::class.java.hook("drawText", HookStage.BEFORE) { param ->
                patchCanvasVirtualTextPaintArg(param)
            }
        }

        installHook("Canvas.drawTextRun.applyVirtualThemeText") {
            Canvas::class.java.hook("drawTextRun", HookStage.BEFORE) { param ->
                patchCanvasVirtualTextPaintArg(param)
            }
        }

        installHook("Canvas.drawGlyphs.applyVirtualThemeText") {
            Canvas::class.java.hook("drawGlyphs", HookStage.BEFORE) { param ->
                patchCanvasVirtualTextPaintArg(param)
            }
        }

        installHook("View.setBackgroundColor") {
            View::class.java.hook("setBackgroundColor", HookStage.BEFORE) { param ->
                patchViewColorArg(param, 0)
            }
        }

        installHook("View.setBackgroundColor.applyThemeBackground") {
            View::class.java.hook("setBackgroundColor", HookStage.AFTER) { param ->
                if (internalThemeMutation.get() == true) return@hook
                val view = param.nullableThisObject<View>() ?: return@hook
                applyCustomBackgroundForView(view)
            }
        }

        installHook("View.setBackground") {
            View::class.java.hook("setBackground", HookStage.BEFORE) { param ->
                patchViewDrawableArg(param, 0)
            }
        }

        installHook("View.setBackground.applyThemeBackground") {
            View::class.java.hook("setBackground", HookStage.AFTER) { param ->
                if (internalThemeMutation.get() == true) return@hook
                val view = param.nullableThisObject<View>() ?: return@hook
                applyCustomBackgroundForView(view)
            }
        }

        installHook("View.setBackgroundDrawable") {
            View::class.java.hook("setBackgroundDrawable", HookStage.BEFORE) { param ->
                patchViewDrawableArg(param, 0)
            }
        }

        installHook("View.setBackgroundDrawable.applyThemeBackground") {
            View::class.java.hook("setBackgroundDrawable", HookStage.AFTER) { param ->
                if (internalThemeMutation.get() == true) return@hook
                val view = param.nullableThisObject<View>() ?: return@hook
                applyCustomBackgroundForView(view)
            }
        }

        installHook("View.setBackgroundTintList") {
            View::class.java.hook("setBackgroundTintList", HookStage.BEFORE) { param ->
                patchViewColorStateListArg(param, 0)
            }
        }
    }

    private fun applyThemeToCurrentRoot(maxViews: Int = 220, maxRuntimeMs: Long = 8L) {
        val root = currentActivityRef?.get()?.window?.decorView ?: return
        context.runOnUiThread {
            runCatching {
                val changed = patchAndroidSurfaceTree(root, maxViews = maxViews, maxRuntimeMs = maxRuntimeMs)
                val knownVirtualChanged = patchKnownVirtualThemeHosts(
                    trigger = "current-root-apply",
                    maxRuntimeMs = maxRuntimeMs.coerceAtLeast(12L)
                )
                val virtualChanged = patchVirtualThemeHostTree(
                    host = root,
                    maxViews = maxViews.coerceAtLeast(360),
                    maxRuntimeMs = maxRuntimeMs.coerceAtLeast(6L),
                    trigger = "current-root-apply"
                )
                logAndroidTreePass(
                    source = "current-root-apply",
                    host = root,
                    maxItems = maxViews,
                    maxRuntimeMs = maxRuntimeMs,
                    visited = -1,
                    changed = changed + knownVirtualChanged + virtualChanged,
                    elapsedMs = -1L,
                    extra = "knownVirtualChanged=$knownVirtualChanged virtualChanged=$virtualChanged"
                )
            }.onFailure {
                context.log.verbose("[THEME PATCH] Failed to refresh current theme root: ${it.message}")
            }
        }
    }

    private fun restorePreviewOnCurrentRoot(maxViews: Int = 260, maxRuntimeMs: Long = 8L) {
        val root = currentActivityRef?.get()?.window?.decorView ?: return
        context.runOnUiThread {
            runCatching {
                val restored = restorePreviewStateTree(root, maxViews = maxViews, maxRuntimeMs = maxRuntimeMs)
                val changed = patchAndroidSurfaceTree(root, maxViews = maxViews, maxRuntimeMs = maxRuntimeMs)
                val knownVirtualChanged = patchKnownVirtualThemeHosts(
                    trigger = "current-root-restore-preview",
                    maxRuntimeMs = maxRuntimeMs.coerceAtLeast(12L)
                )
                val virtualChanged = patchVirtualThemeHostTree(
                    host = root,
                    maxViews = maxViews.coerceAtLeast(360),
                    maxRuntimeMs = maxRuntimeMs.coerceAtLeast(6L),
                    trigger = "current-root-restore-preview"
                )
                logAndroidTreePass(
                    source = "current-root-restore-preview",
                    host = root,
                    maxItems = maxViews,
                    maxRuntimeMs = maxRuntimeMs,
                    visited = -1,
                    changed = changed + knownVirtualChanged + virtualChanged,
                    elapsedMs = -1L,
                    extra = "restored=$restored knownVirtualChanged=$knownVirtualChanged virtualChanged=$virtualChanged"
                )
            }.onFailure {
                context.log.verbose("[THEME PATCH] Failed to restore custom theme preview: ${it.message}")
            }
        }
    }

    private fun capturedThemeSelectorDisplayName(target: String): String? {
        if (target.isBlank()) return null
        return when {
            isSnapchatVirtualThemeSelector(target) -> {
                val raw = snapchatVirtualSelectorValue(target, "text")
                    ?: snapchatVirtualSelectorValue(target, "desc")
                    ?: snapchatVirtualSelectorValue(target, "view_id")?.substringAfterLast('/')
                    ?: snapchatVirtualSelectorValue(target, "class")?.substringAfterLast('.')
                    ?: "Virtual surface"
                val bounds = snapchatVirtualSelectorValue(target, "bounds")
                buildString {
                    append(SnapchatThemeSurfaceMapCodec.friendlyLabelForKey(raw))
                    if (!bounds.isNullOrBlank()) append(" ").append(bounds)
                }.trim()
            }
            target.startsWith("selector:v1|") -> runCatching { WhatsAppUiElementSelector.toDisplayName(target) }.getOrNull()
            else -> null
        }
    }

    private fun observeCapturedThemeSurfaceBroadcast(intent: Intent, runtime: ThemeRuntime = themeRuntime()) {
        val target = intent.getStringExtra(Constants.SNAPCHAT_THEME_SURFACE_VALUE_EXTRA)?.trim().orEmpty()
        if (target.isBlank()) return
        val isSelector = intent.getBooleanExtra(Constants.SNAPCHAT_THEME_SURFACE_IS_SELECTOR_EXTRA, isThemeSelectorTarget(target))
        val role = intent.getStringExtra(Constants.SNAPCHAT_THEME_SURFACE_ROLE_EXTRA)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: "Selected surface"
        val label = intent.getStringExtra(Constants.SNAPCHAT_THEME_SURFACE_LABEL_EXTRA)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: capturedThemeSelectorDisplayName(target)
            ?: SnapchatThemeSurfaceMapCodec.friendlyLabelForKey(target)
        val rawColor = intent.getStringExtra(Constants.SNAPCHAT_THEME_SURFACE_COLOR_EXTRA)?.trim().orEmpty()
        val color = parseThemeColor(rawColor)
        val previewMode = intent.getStringExtra(Constants.SNAPCHAT_THEME_SURFACE_PREVIEW_MODE_EXTRA)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        val bounds = intent.getStringExtra(Constants.SNAPCHAT_THEME_SURFACE_BOUNDS_EXTRA)
            ?.trim()
            ?.takeIf { it.isNotBlank() && it != "none" }
            ?: if (isSnapchatVirtualThemeSelector(target)) snapchatVirtualSelectorValue(target, "bounds") else null
        val localBounds = intent.getStringExtra(Constants.SNAPCHAT_THEME_SURFACE_LOCAL_BOUNDS_EXTRA)
            ?.trim()
            ?.takeIf { it.isNotBlank() && it != "none" }
            ?: if (isSnapchatVirtualThemeSelector(target)) snapchatVirtualSelectorValue(target, "local_bounds") else null
        val detail = intent.getStringExtra(Constants.SNAPCHAT_THEME_SURFACE_DETAIL_EXTRA)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        val targetClass = intent.getStringExtra(Constants.SNAPCHAT_THEME_SURFACE_TARGET_CLASS_EXTRA)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        val targetId = intent.getStringExtra(Constants.SNAPCHAT_THEME_SURFACE_TARGET_ID_EXTRA)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        val targetSummary = intent.getStringExtra(Constants.SNAPCHAT_THEME_SURFACE_TARGET_SUMMARY_EXTRA)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        val normalizedTarget = normalizeSurfaceKey(if (isSelector) targetId ?: target else target)
        val roleKey = normalizeSurfaceKey(role).ifBlank { "selected_surface" }
        val key = when {
            isSelector -> "snapchat.selector.${roleKey}.${selectorHashHex(target)}"
            normalizedTarget.startsWith("android.view.") -> normalizedTarget
            normalizedTarget.isNotBlank() -> "android.view.$normalizedTarget"
            else -> "snapchat.capture.${roleKey}.${selectorHashHex(target)}"
        }
        val selectorDisplayName = if (isSelector) capturedThemeSelectorDisplayName(target) else null
        val details = themeScreenDetails(null) + mapOf(
            "captured_target" to target,
            "captured_selector" to isSelector.toString(),
            "captured_label" to label,
            "captured_role" to role,
            "captured_preview_mode" to (previewMode ?: "none"),
            "captured_color" to rawColor.ifBlank { "none" },
            "captured_bounds" to (bounds ?: "none"),
            "captured_local_bounds" to (localBounds ?: "none"),
            "captured_target_class" to (targetClass ?: "none"),
            "captured_target_id" to (targetId ?: "none"),
            "captured_target_summary" to (targetSummary ?: "none"),
            "captured_detail" to (detail ?: "none")
        )
        observeThemeSurface(
            SnapchatThemeMappedSurface(
                key = key,
                label = label,
                description = "Captured from live picker role=$role preview=${previewMode ?: "unknown"} " +
                    "target=${target.take(360)} detail=${detail?.take(520) ?: "none"}",
                category = "Captured picker surface",
                source = "live-picker-capture",
                viewClass = targetClass,
                resourceName = if (isSelector) targetId else targetId ?: normalizeSurfaceKey(target).takeIf { it.isNotBlank() },
                selector = target.takeIf { isSelector },
                selectorDisplayName = selectorDisplayName,
                screenHint = currentThemeScreenHint(null),
                role = role,
                screenBounds = bounds,
                localBounds = localBounds,
                valueSummary = listOfNotNull(
                    rawColor.takeIf { it.isNotBlank() }?.let { "color=$it" },
                    previewMode?.let { "previewMode=$it" },
                    targetSummary?.let { "target=$it" }
                ).joinToString(",").takeIf { it.isNotBlank() },
                valueType = previewMode ?: "captured-picker-rule",
                defaultColor = color ?: amoledBlack,
                confidence = 0.99f,
                details = details
            ),
            reason = "captured-picker-broadcast:$roleKey",
            runtime = runtime
        )
        amoledTrace("theme-map-capture", "$roleKey|${selectorHashHex(target)}|${colorHex(color)}", maxPerCategory = 1200, unique = false) {
            "captured picker observed key=$key selector=$isSelector label=$label role=$role " +
                "mode=${previewMode ?: "none"} color=${colorHex(color)} bounds=${bounds ?: "none"} " +
                "local=${localBounds ?: "none"} class=${targetClass ?: "none"} id=${targetId ?: "none"} target=${target.take(900)}"
        }
    }

    private fun handleThemePreview(intent: Intent) {
        val target = intent.getStringExtra(Constants.SNAPCHAT_THEME_SURFACE_VALUE_EXTRA)?.trim().orEmpty()
        if (target.isEmpty()) return
        val rawColor = intent.getStringExtra(Constants.SNAPCHAT_THEME_SURFACE_COLOR_EXTRA)?.trim().orEmpty()
        if (rawColor.isEmpty()) {
            previewRule = null
            amoledTrace("preview", "clear|$target", maxPerCategory = 160, unique = false) {
                "preview clear target=$target selector=${intent.getBooleanExtra(Constants.SNAPCHAT_THEME_SURFACE_IS_SELECTOR_EXTRA, isThemeSelectorTarget(target))}"
            }
            restorePreviewOnCurrentRoot(maxViews = 220, maxRuntimeMs = 6L)
            return
        }
        val color = parseThemeColor(rawColor) ?: amoledBlack
        amoledTrace("preview", "$target|${colorHex(color)}", maxPerCategory = 120) {
            "preview target=$target selector=${intent.getBooleanExtra(Constants.SNAPCHAT_THEME_SURFACE_IS_SELECTOR_EXTRA, isThemeSelectorTarget(target))} color=${colorHex(color)}"
        }
        restorePreviewOnCurrentRoot(maxViews = 220, maxRuntimeMs = 6L)
        val preview = ThemePreviewRule(
            target = target,
            selector = intent.getBooleanExtra(Constants.SNAPCHAT_THEME_SURFACE_IS_SELECTOR_EXTRA, isThemeSelectorTarget(target)),
            color = color,
            expiresAtMs = SystemClock.uptimeMillis() + 5_000L
        )
        previewRule = preview
        applyThemeToCurrentRoot()
        currentActivityRef?.get()?.window?.decorView?.postDelayed({
            if (previewRule == preview) {
                previewRule = null
                restorePreviewOnCurrentRoot()
            }
        }, 5_100L)
    }

    private fun registerThemeConfigReceiver() {
        if (themeBroadcastReceiverRegistered) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                when (intent.action) {
                    Constants.SNAPCHAT_THEME_CONFIG_CHANGED_ACTION -> {
                        runCatching {
                            context.reloadConfig()
                            cachedThemeRuntime = ThemeRuntime.EMPTY
                            previewRule = null
                            synchronized(backgroundDrawableStateCache) {
                                backgroundDrawableStateCache.clear()
                            }
                            synchronized(valdiValuePatchCache) {
                                valdiValuePatchCache.clear()
                            }
                            synchronized(valdiPatchLogKeys) {
                                valdiPatchLogKeys.clear()
                            }
                            synchronized(amoledTraceLogKeys) {
                                amoledTraceLogKeys.clear()
                                amoledTraceCategoryCounts.clear()
                                amoledTraceCappedCategories.clear()
                            }
                            observeCapturedThemeSurfaceBroadcast(intent)
                            restorePreviewOnCurrentRoot(maxViews = 220, maxRuntimeMs = 6L)
                            context.log.verbose("[THEME PATCH] Reloaded custom theme config from broadcast")
                            amoledTrace("runtime", "config-broadcast-${SystemClock.uptimeMillis()}", maxPerCategory = 80, unique = false) {
                                "config broadcast reloaded; caches cleared"
                            }
                            applyThemeToCurrentRoot()
                        }.onFailure {
                            context.log.error("[THEME PATCH] Failed to reload custom theme config", it)
                        }
                    }
                    Constants.SNAPCHAT_THEME_PREVIEW_ACTION -> {
                        runCatching {
                            handleThemePreview(intent)
                        }.onFailure {
                            context.log.error("[THEME PATCH] Failed to preview custom theme surface", it)
                        }
                    }
                    Constants.SNAPCHAT_THEME_SURFACE_MAP_REFRESH_ACTION -> {
                        runCatching {
                            rebuildThemeSurfaceMap("broadcast")
                            applyThemeToCurrentRoot(maxViews = 180, maxRuntimeMs = 5L)
                        }.onFailure {
                            context.log.error("[THEME MAP] Failed to rebuild Snapchat theme surface map", it)
                        }
                    }
                }
            }
        }
        val filter = IntentFilter(Constants.SNAPCHAT_THEME_CONFIG_CHANGED_ACTION).apply {
            addAction(Constants.SNAPCHAT_THEME_PREVIEW_ACTION)
            addAction(Constants.SNAPCHAT_THEME_SURFACE_MAP_REFRESH_ACTION)
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.androidContext.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.androidContext.registerReceiver(receiver, filter)
            }
            themeBroadcastReceiver = receiver
            themeBroadcastReceiverRegistered = true
        }.onFailure {
            context.log.error("[THEME PATCH] Failed to register custom theme config receiver", it)
        }
    }

    override fun init() {
        if (!CustomThemingRuntime.ENABLED) {
            context.log.verbose("[THEME PATCH] ${CustomThemingRuntime.DISABLED_REASON}; skipping hook installation")
            return
        }
        registerThemeConfigReceiver()
        loadThemeSurfaceMapIfNeeded("feature-init", force = true)
        installHooks()
        onNextActivityCreate { activity ->
            currentActivityRef = WeakReference(activity)
            amoledTrace("activity", activity.javaClass.name, maxPerCategory = 120) {
                "activityCreated class=${activity.javaClass.name} package=${activity.packageName} decor=${viewSummary(activity.window?.decorView)}"
            }
            patchWindow(activity.window)
            applyThemeToCurrentRoot(maxViews = 180, maxRuntimeMs = 6L)
        }
    }
}
