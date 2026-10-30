package me.eternal.purrfect.core.instagram

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.net.Uri
import android.text.PrecomputedText
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.MetricAffectingSpan
import android.util.Base64
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Collections
import java.util.LinkedHashMap
import java.util.WeakHashMap
import kotlin.math.roundToInt
import org.json.JSONObject

internal object InstagramCustomEmojiFontHooks {
    private const val FONT_DIR = "instaeclipse"
    private const val LEGACY_FONT_DIR = "purrfect_insta"
    private const val FONT_FILE = "custom_emoji_font.ttf"
    private const val PROVIDER_URI = "content://me.eternal.purrfect.instagram.emoji/font"
    private const val GENERATED_EMOJI_IMAGE_SIZE = 96
    private const val GENERATED_EMOJI_IMAGE_CACHE_LIMIT = 384
    private const val DM_REACTION_EMOJI_OVERLAY_TAG = "purrfect_dm_reaction_emoji_overlay"

    @Volatile private var installed = false
    @Volatile private var loadedTypeface: Typeface? = null
    @Volatile private var loadedPath: String = ""
    @Volatile private var loadedSourceUri: String = ""
    @Volatile private var failedPath: String = ""
    @Volatile private var appContext: Context? = null
    @Volatile private var appClassLoader: ClassLoader? = null
    @Volatile private var instagramEmojiTextDrawableClass: Class<*>? = null
    @Volatile private var loggedEmojiGridHook = false
    @Volatile private var loggedEmojiGridApply = false
    @Volatile private var loggedEmojiImageUrlHook = false
    @Volatile private var loggedEmojiImageUrlApply = false
    @Volatile private var loggedQuickEmojiRowApply = false
    private val customEmojiImageUrlCache = Collections.synchronizedMap(
        object : LinkedHashMap<String, Any>(128, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Any>?): Boolean {
                return size > GENERATED_EMOJI_IMAGE_CACHE_LIMIT
            }
        }
    )
    private val quickEmojiButtonState = Collections.synchronizedMap(WeakHashMap<TextView, String>())
    private val emojiImageViewState = Collections.synchronizedMap(WeakHashMap<ImageView, String>())
    private val emojiImageViewReapplyState = Collections.synchronizedMap(WeakHashMap<ImageView, String>())
    private val textViewEmojiSpanState = Collections.synchronizedMap(WeakHashMap<TextView, String>())
    private val quickEmojiResourceIdMatches = Collections.synchronizedMap(LinkedHashMap<Int, Boolean>())

    fun install(context: Context, classLoader: ClassLoader) {
        appContext = context.applicationContext ?: context
        appClassLoader = classLoader
        ensureFontImported(context)
        if (installed) return
        installed = true
        installInstagramEmojiSpanHooks(classLoader)
        installInstagramEmojiDrawableHooks(classLoader)
        installInstagramEmojiImageUrlHooks(classLoader)
        installInstagramEmojiGridHooks(classLoader)
        installDirectMessageReactionImageHooks()
        installFrameworkTextHooks()
        log("Installed custom emoji text and layout hooks")
    }

    fun getProviderUri(): String = PROVIDER_URI

    fun syncAfterStateUpdate(context: Context?, previous: InstagramFeatureState, next: InstagramFeatureState) {
        context?.let { appContext = it.applicationContext ?: it }
        val changed = previous.customEmojiFontEnabled != next.customEmojiFontEnabled ||
            previous.customEmojiFontPath != next.customEmojiFontPath ||
            previous.customEmojiFontName != next.customEmojiFontName ||
            previous.customEmojiFontUri != next.customEmojiFontUri
        if (!changed) return
        clearEmojiImageUrlCache()

        if (next.customEmojiFontEnabled ||
            next.customEmojiFontPath.isNotBlank() ||
            next.customEmojiFontUri.isNotBlank() ||
            next.customEmojiFontName.isNotBlank()
        ) {
            ensureFontImported(context)
        } else {
            clearImportedFont(context)
        }
    }

    fun ensureFontImported(context: Context?): Boolean {
        val ctx = context ?: appContext ?: return false
        val current = InstagramFeatureStateStore.current
        val hasSetting = current.customEmojiFontEnabled ||
            current.customEmojiFontPath.isNotBlank() ||
            current.customEmojiFontUri.isNotBlank() ||
            current.customEmojiFontName.isNotBlank()
        if (!hasSetting && !providerHasFont(ctx)) return false

        current.customEmojiFontPath.takeIf { it.isNotBlank() }?.let { path ->
            runCatching {
                val file = File(path)
                if (file.exists() && file.length() > 0L) {
                    loadedTypeface = Typeface.createFromFile(file)
                    loadedPath = path
                    loadedSourceUri = path
                    failedPath = ""
                    clearEmojiImageUrlCache()
                    updateEmojiFeatureState(true, path, current.customEmojiFontName, current.customEmojiFontUri, "emoji-font-existing")
                    log("Using existing imported emoji font $path")
                    return true
                }
            }
        }

        val uri = current.customEmojiFontUri.takeIf { it.isNotBlank() } ?: PROVIDER_URI
        val importedFile = fontFile(ctx)
        if (loadedTypeface != null &&
            loadedPath == importedFile.absolutePath &&
            loadedSourceUri == uri &&
            importedFile.exists() &&
            importedFile.length() > 0L
        ) {
            return true
        }
        val configuredPathReadable = current.customEmojiFontPath.takeIf { it.isNotBlank() }?.let { path ->
            runCatching {
                val file = File(path)
                file.exists() && file.canRead() && file.length() > 0L
            }.getOrDefault(false)
        } == true
        if (!configuredPathReadable &&
            importedFile.exists() &&
            importedFile.length() > 0L &&
            (uri == PROVIDER_URI || loadedSourceUri == uri)
        ) {
            return runCatching {
                loadedTypeface = Typeface.createFromFile(importedFile)
                loadedPath = importedFile.absolutePath
                loadedSourceUri = uri
                failedPath = ""
                updateEmojiFeatureState(true, importedFile.absolutePath, current.customEmojiFontName, uri, "emoji-font-existing")
                log("Using existing imported emoji font ${importedFile.absolutePath}")
                true
            }.getOrElse {
                false
            }
        }
        log("Importing emoji font from provider $uri")
        var imported = importFontFromUri(ctx, uri, current.customEmojiFontName)
        if (!imported && uri != PROVIDER_URI) {
            log("Retrying emoji font import through companion provider")
            imported = importFontFromUri(ctx, PROVIDER_URI, current.customEmojiFontName)
        }
        return imported
    }

    fun bootstrapFromCompanion(context: Context?): Boolean {
        var imported = ensureFontImported(context)
        val ctx = context ?: appContext
        if (!imported && ctx != null && providerHasFont(ctx)) {
            log("Found companion provider font during startup")
            imported = importFontFromUri(ctx, PROVIDER_URI, InstagramFeatureStateStore.current.customEmojiFontName)
        }
        return imported
    }

    fun importFontFromUri(context: Context?, uriString: String?, displayName: String? = null): Boolean {
        val ctx = context ?: appContext
        if (ctx == null || uriString.isNullOrBlank()) {
            return clearImportedFont(ctx)
        }

        val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return false
        runCatching {
            ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val output = fontFile(ctx)
        return runCatching {
            output.parentFile?.let { parent ->
                if (!parent.exists() && !parent.mkdirs()) error("Cannot create font folder")
            }
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(output, false).use { outputStream ->
                    input.copyTo(outputStream, bufferSize = 64 * 1024)
                    outputStream.fd.sync()
                }
            } ?: error("Cannot open font URI")

            loadedTypeface = Typeface.createFromFile(output)
            loadedPath = output.absolutePath
            loadedSourceUri = uriString
            failedPath = ""
            clearEmojiImageUrlCache()
            updateEmojiFeatureState(
                enabled = true,
                path = output.absolutePath,
                name = displayName.orEmpty(),
                uri = uriString,
                source = "emoji-font-import"
            )
            log("Imported emoji font into Instagram storage: ${output.absolutePath}")
            true
        }.getOrElse {
            log("Font import failed from $uriString: ${it.message}")
            false
        }
    }

    fun clearImportedFont(context: Context?): Boolean {
        loadedTypeface = null
        loadedPath = ""
        loadedSourceUri = ""
        failedPath = ""
        clearEmojiImageUrlCache()
        updateEmojiFeatureState(false, "", "", "", "emoji-font-clear")

        val ctx = context ?: appContext ?: return true
        return runCatching {
            fontFile(ctx).delete()
            File(File(ctx.filesDir, LEGACY_FONT_DIR), FONT_FILE).delete()
            true
        }.getOrElse {
            log("Emoji font reset failed: ${it.message}")
            false
        }
    }

    fun wrapEmojiText(source: CharSequence?, maxScanLength: Int = Int.MAX_VALUE): CharSequence? {
        if (source == null || source.isEmpty() || source.length > maxScanLength || !mightContainEmoji(source) || !containsEmoji(source)) return source
        val typeface = getTypeface() ?: return source
        if (source is Spannable && source !is PrecomputedText) {
            applyEmojiSpans(source, typeface)
            return source
        }
        return SpannableStringBuilder(source).also { applyEmojiSpans(it, typeface) }
    }

    fun currentTypeface(): Typeface? = getTypeface()

    fun applyEmojiSpans(text: Spannable?, typeface: Typeface? = getTypeface()) {
        if (typeface == null || text == null || text.isEmpty() || !mightContainEmoji(text)) return

        var index = 0
        while (index < text.length) {
            val codePoint = Character.codePointAt(text, index)
            if (!isEmojiStart(text, index, codePoint)) {
                index += Character.charCount(codePoint)
                continue
            }

            val start = index
            val end = findEmojiSequenceEnd(text, index)
            if (end > start && !hasCustomSpan(text, start, end)) {
                try {
                    text.setSpan(CustomEmojiTypefaceSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                } catch (_: IllegalArgumentException) {
                    return
                }
            }
            index = maxOf(end, start + Character.charCount(codePoint))
        }
    }

    private fun installInstagramEmojiSpanHooks(classLoader: ClassLoader) {
        arrayOf(
            "com.facebook.p042ui.emoji.FacebookTypefaceEmojiSpan",
            "com.facebook.ui.emoji.FacebookTypefaceEmojiSpan",
            "X.74b",
            "p000X.C1795074b"
        ).forEach { className ->
            runCatching {
                val spanClass = runCatching { Class.forName(className, false, classLoader) }.getOrNull()
                    ?: return@forEach
                val paintHook = object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam<*>) {
                        val textPaint = param.args.firstOrNull() as? TextPaint ?: return
                        getTypeface()?.let { textPaint.typeface = it }
                    }
                }
                XposedBridge.hookAllMethods(spanClass, "updateDrawState", paintHook)
                XposedBridge.hookAllMethods(spanClass, "updateMeasureState", paintHook)
                log("Hooked Instagram emoji span $className")
            }.onFailure {
                log("Instagram emoji span hook failed for $className: ${it.message}")
            }
        }
    }

    fun maybeApplyQuickEmojiButton(view: TextView) {
        if (!isQuickEmojiButtonView(view)) return
        if (isDirectMessageReactionTrayView(view)) return
        val emojiText = view.contentDescription?.toString()?.trim()?.takeIf(::isEmojiOnlyLabel) ?: return
        val typeface = getTypeface() ?: return

        val key = "$loadedPath:$emojiText"
        if (quickEmojiButtonState[view] == key) return

        view.text = ""
        view.gravity = Gravity.CENTER
        view.includeFontPadding = false
        view.setCompoundDrawables(null, null, null, null)
        view.setCompoundDrawablesRelative(null, null, null, null)
        view.foreground = QuickEmojiFontDrawable(emojiText, typeface, textScale = 0.58f)
        view.invalidate()
        quickEmojiButtonState[view] = key
        if (!loggedQuickEmojiRowApply) {
            loggedQuickEmojiRowApply = true
            log("Applied custom emoji font to comment quick emoji row")
        }
    }

    fun maybeApplyEmojiImageView(view: ImageView, force: Boolean = false) {
        val label = view.contentDescription?.toString()?.trim() ?: return
        val dmReactionTray = label.endsWith("Reaction") && isDirectMessageReactionTrayView(view)
        val emojiText = emojiTextFromImageLabel(label, allowReactionSuffix = dmReactionTray) ?: return
        if (!dmReactionTray && !isInstagramEmojiImageCandidate(view)) return
        val measuredSizes = listOf(
            view.width,
            view.height,
            view.layoutParams?.width ?: 0,
            view.layoutParams?.height ?: 0
        ).filter { it > 0 }
        val size = (measuredSizes.maxOrNull() ?: return).coerceAtLeast(32)
        val displayEmojiText = if (dmReactionTray) emojiText.withEmojiPresentation() else emojiText
        val key = "$loadedPath:$size:$displayEmojiText:${if (dmReactionTray) "dmReaction" else "emoji"}"
        if (!force && emojiImageViewState[view] == key) return

        if (dmReactionTray) {
            if (applyDirectMessageReactionOverlay(view, displayEmojiText, size)) {
                emojiImageViewState[view] = key
                scheduleEmojiImageViewReapply(view, key, extended = true)
                if (!loggedEmojiGridApply) {
                    loggedEmojiGridApply = true
                    log("Applied custom emoji font to Instagram emoji image view")
                }
            }
            return
        }

        val typeface = getTypeface() ?: return
        val drawable = if (isQuickEmojiImageView(view)) {
            val quickSize = ((measuredSizes.minOrNull() ?: size) * 0.72f).roundToInt().coerceAtLeast(32)
            QuickEmojiFontDrawable(displayEmojiText, typeface, textScale = 0.58f, intrinsicSize = quickSize).also {
                it.setBounds(0, 0, quickSize, quickSize)
            }
        } else {
            createInstagramEmojiTextDrawable(view.context, size, displayEmojiText, typeface)
                ?: QuickEmojiFontDrawable(displayEmojiText, typeface, intrinsicSize = size).also {
                    it.setBounds(0, 0, size, size)
                }
        } ?: return
        view.scaleType = ImageView.ScaleType.CENTER_INSIDE
        view.setImageDrawable(drawable)
        emojiImageViewState[view] = key
        scheduleEmojiImageViewReapply(view, key)
        if (!loggedEmojiGridApply) {
            loggedEmojiGridApply = true
            log("Applied custom emoji font to Instagram emoji image view")
        }
    }

    private fun emojiTextFromImageLabel(label: String, allowReactionSuffix: Boolean): String? {
        label.takeIf(::isEmojiOnlyLabel)?.let { return it }
        if (!allowReactionSuffix) return null
        val emoji = label.removeSuffix("Reaction").trim()
        return emoji.takeIf { it.isNotBlank() && isEmojiOnlyLabel(it) }
    }

    private fun String.withEmojiPresentation(): String {
        if (indexOf('\uFE0F') >= 0) return this
        return when (this) {
            "\u2764" -> "\u2764\uFE0F"
            else -> this
        }
    }

    private fun applyDirectMessageReactionOverlay(view: ImageView, emojiText: String, size: Int): Boolean {
        val parent = view.parent as? ViewGroup ?: return false
        val overlay = findDirectMessageReactionOverlay(parent)
            ?: TextView(view.context).apply {
                tag = DM_REACTION_EMOJI_OVERLAY_TAG
                gravity = Gravity.CENTER
                includeFontPadding = false
                isSingleLine = true
                isClickable = true
                isFocusable = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                setTextColor(Color.WHITE)
                setOnClickListener { view.performClick() }
                setOnLongClickListener { view.performLongClick() }
                val params = if (parent is FrameLayout) {
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        Gravity.CENTER
                    )
                } else {
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
                parent.addView(this, params)
            }
        overlay.text = emojiText
        overlay.typeface = Typeface.DEFAULT
        overlay.setTextSize(TypedValue.COMPLEX_UNIT_PX, (size * 0.56f).coerceAtLeast(28f))
        overlay.visibility = View.VISIBLE
        overlay.bringToFront()
        view.clearColorFilter()
        view.imageTintList = null
        view.imageTintMode = null
        view.imageAlpha = 0
        view.alpha = 1f
        view.visibility = View.VISIBLE
        overlay.invalidate()
        return true
    }

    private fun findDirectMessageReactionOverlay(parent: ViewGroup): TextView? {
        for (index in 0 until parent.childCount) {
            val child = parent.getChildAt(index)
            if (child is TextView && child.tag == DM_REACTION_EMOJI_OVERLAY_TAG) return child
        }
        return null
    }

    private fun scheduleEmojiImageViewReapply(view: ImageView, key: String, extended: Boolean = false) {
        if (emojiImageViewReapplyState[view] == key) return
        emojiImageViewReapplyState[view] = key
        val delays = if (extended) listOf(60L, 160L, 360L, 800L, 1400L) else listOf(80L, 220L)
        delays.forEach { delay ->
            view.postDelayed({
                if (view.isAttachedToWindow) maybeApplyEmojiImageView(view, force = true)
            }, delay)
        }
    }

    private fun isQuickEmojiButtonView(view: View): Boolean {
        val id = view.id
        if (id == View.NO_ID) return false
        quickEmojiResourceIdMatches[id]?.let { return it }
        val matches = runCatching {
            if (id <= 0x00FFFFFF) return@runCatching false
            when (view.resources.getResourceEntryName(id)) {
                "item_emoji", "quick_snap_reaction_item_emoji" -> true
                else -> false
            }
        }.getOrDefault(false)
        quickEmojiResourceIdMatches[id] = matches
        return matches
    }

    private fun isInstagramEmojiImageCandidate(view: ImageView): Boolean {
        if (isQuickEmojiImageView(view)) return true
        var current: View? = view
        repeat(4) {
            val node = current ?: return@repeat
            val name = runCatching {
                if (node.id != View.NO_ID && node.id > 0x00FFFFFF) node.resources.getResourceEntryName(node.id) else ""
            }.getOrDefault("").lowercase()
            if (name.contains("emoji") || name.contains("reaction")) return true
            current = node.parent as? View
        }
        return false
    }

    private fun installInstagramEmojiDrawableHooks(classLoader: ClassLoader) {
        arrayOf("X.455", "p000X.AnonymousClass455").forEach { className ->
            runCatching {
                val drawableClass = runCatching { Class.forName(className, false, classLoader) }.getOrNull()
                    ?: return@forEach
                XposedBridge.hookAllConstructors(
                    drawableClass,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam<*>) {
                            val typeface = getTypeface() ?: return
                            if (param.args.isNotEmpty() && (param.args[0] == null || param.args[0] is Typeface)) {
                                param.args[0] = typeface
                            }
                        }
                    }
                )
                log("Hooked Instagram emoji drawable $className")
            }.onFailure {
                log("Instagram emoji drawable hook failed for $className: ${it.message}")
            }
        }

        arrayOf("X.29Y", "p000X.C29Y").forEach { className ->
            runCatching {
                val textDrawableClass = runCatching { Class.forName(className, false, classLoader) }.getOrNull()
                    ?: return@forEach
                instagramEmojiTextDrawableClass = textDrawableClass
                val applyHook = object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam<*>) {
                        applyEmojiDrawableTypeface(param.thisObject)
                    }
                }
                XposedBridge.hookAllConstructors(textDrawableClass, applyHook)
                XposedBridge.hookAllMethods(textDrawableClass, "A0i", applyHook)
                XposedBridge.hookAllMethods(textDrawableClass, "A0l", applyHook)
                log("Hooked Instagram emoji text drawable $className")
            }.onFailure {
                log("Instagram emoji text drawable hook failed for $className: ${it.message}")
            }
        }
    }

    private fun installInstagramEmojiGridHooks(classLoader: ClassLoader) {
        arrayOf("X.9YU", "X.C9YU", "p000X.C9YU").forEach { className ->
            runCatching {
                val gridBinderClass = runCatching { Class.forName(className, false, classLoader) }.getOrNull()
                    ?: return@forEach
                val applyHook = object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam<*>) {
                        applyEmojiGridHolderTypeface(param.args)
                    }
                }
                XposedBridge.hookAllMethods(gridBinderClass, "A03", applyHook)
                XposedBridge.hookAllMethods(gridBinderClass, "A04", applyHook)
                if (!loggedEmojiGridHook) {
                    loggedEmojiGridHook = true
                    log("Hooked Instagram emoji grid binder $className")
                }
            }.onFailure {
                log("Instagram emoji grid hook failed for $className: ${it.message}")
            }
        }
    }

    private fun installDirectMessageReactionImageHooks() {
        runCatching {
            val method = View::class.java.getDeclaredMethod(
                "setContentDescription",
                CharSequence::class.java
            )
            XposedBridge.hookMethod(
                method,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam<*>) {
                        val imageView = param.thisObject as? ImageView ?: return
                        val label = param.args.firstOrNull() as? CharSequence ?: return
                        if (label.toString().trim().endsWith("Reaction")) {
                            scheduleDirectMessageReactionOverlayProbe(imageView)
                        }
                    }
                }
            )
        }.onFailure {
            log("Direct message reaction content-description hook failed: ${it.message}")
        }

        runCatching {
            val method = ImageView::class.java.getDeclaredMethod(
                "setImageDrawable",
                Drawable::class.java
            )
            XposedBridge.hookMethod(
                method,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam<*>) {
                        val imageView = param.thisObject as? ImageView ?: return
                        val label = imageView.contentDescription?.toString()?.trim() ?: return
                        if (label.endsWith("Reaction")) {
                            scheduleDirectMessageReactionOverlayProbe(imageView)
                        }
                    }
                }
            )
        }.onFailure {
            log("Direct message reaction image hook failed: ${it.message}")
        }
    }

    private fun scheduleDirectMessageReactionOverlayProbe(view: ImageView) {
        listOf(0L, 60L, 160L, 360L, 800L).forEach { delay ->
            if (delay == 0L) {
                view.post {
                    if (view.isAttachedToWindow) maybeApplyEmojiImageView(view, force = true)
                }
            } else {
                view.postDelayed({
                    if (view.isAttachedToWindow) maybeApplyEmojiImageView(view, force = true)
                }, delay)
            }
        }
    }

    private fun installInstagramEmojiImageUrlHooks(classLoader: ClassLoader) {
        arrayOf("X.9YR", "X.C9YR", "p000X.C9YR").forEach { className ->
            runCatching {
                val imageUrlClass = runCatching { Class.forName(className, false, classLoader) }.getOrNull()
                    ?: return@forEach
                val hook = object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam<*>) {
                        val emojiText = param.args
                            ?.firstOrNull { it is String && containsEmoji(it) } as? String
                            ?: return
                        createCustomEmojiImageUrl(emojiText)?.let { imageUrl ->
                            param.result = imageUrl
                            if (!loggedEmojiImageUrlApply) {
                                loggedEmojiImageUrlApply = true
                                log("Applied custom emoji font to Instagram emoji image URLs")
                            }
                        }
                    }
                }
                val methods = imageUrlClass.declaredMethods.filter { method ->
                    method.parameterTypes.any { it == String::class.java } &&
                        (method.returnType.name.contains("typedurl", ignoreCase = true) ||
                            method.returnType.name.contains("ImageUrl", ignoreCase = true) ||
                            method.name == "A00")
                }
                if (methods.isEmpty()) {
                    XposedBridge.hookAllMethods(imageUrlClass, "A00", hook)
                } else {
                    methods.forEach { method ->
                        method.isAccessible = true
                        XposedBridge.hookMethod(method, hook)
                    }
                }
                if (!loggedEmojiImageUrlHook) {
                    loggedEmojiImageUrlHook = true
                    log("Hooked Instagram emoji image URL factory $className methods=${methods.size.coerceAtLeast(1)}")
                }
            }.onFailure {
                log("Instagram emoji image URL hook failed for $className: ${it.message}")
            }
        }
    }

    private fun applyEmojiDrawableTypeface(drawable: Any?) {
        val typeface = getTypeface() ?: return
        drawable ?: return
        runCatching {
            drawable.javaClass.methods
                .firstOrNull { method ->
                    method.name == "A0f" &&
                        method.parameterTypes.contentEquals(arrayOf<Class<*>>(Typeface::class.java))
                }
                ?.invoke(drawable, typeface)
        }.onFailure {
            log("Failed applying custom emoji typeface to drawable: ${it.message}")
        }
    }

    private fun applyEmojiGridHolderTypeface(args: Array<Any?>?) {
        val typeface = getTypeface() ?: return
        val allArgs = args ?: return
        val emojiText = allArgs.firstNotNullOfOrNull(::findEmojiText) ?: return
        if (emojiText.isEmpty() || !containsEmoji(emojiText)) return
        val holder = allArgs.firstOrNull { readField(it, "A08") is ImageView }
        val imageView = (holder?.let { readField(it, "A08") } as? ImageView)
            ?: allArgs.firstNotNullOfOrNull(::findImageViewField)
            ?: return
        if (isDirectMessageReactionTrayView(imageView)) return
        val quickRow = isQuickEmojiImageView(imageView)
        val measuredSizes = listOf(
            imageView.width,
            imageView.height,
            imageView.layoutParams?.width ?: 0,
            imageView.layoutParams?.height ?: 0,
            readField(holder, "A03") as? Int ?: 0
        ).filter { it > 0 }
        val size = measuredSizes.maxOrNull() ?: return
        val drawable = if (quickRow) {
            val quickSize = ((measuredSizes.minOrNull() ?: size) * 0.72f).roundToInt().coerceAtLeast(32)
            QuickEmojiFontDrawable(emojiText, typeface, textScale = 0.58f, intrinsicSize = quickSize).also {
                it.setBounds(0, 0, quickSize, quickSize)
            }
        } else {
            createInstagramEmojiTextDrawable(imageView.context, size, emojiText, typeface)
        } ?: return
        if (quickRow) imageView.scaleType = ImageView.ScaleType.CENTER_INSIDE
        imageView.setImageDrawable(drawable)
        val key = "$loadedPath:$size:$emojiText"
        emojiImageViewState[imageView] = key
        scheduleEmojiImageViewReapply(imageView, key)
        if (!loggedEmojiGridApply) {
            loggedEmojiGridApply = true
            log("Applied custom emoji font to Instagram emoji grid row")
        }
    }

    private fun isDirectMessageReactionTrayView(view: View): Boolean {
        var current: View? = view
        var sawReactionRow = false
        repeat(10) {
            val node = current ?: return false
            val name = resourceEntryName(node)
            if (name.contains("comment")) return false
            if (name.contains("message_actions") ||
                name.contains("direct_message_actions") ||
                name.contains("message_action_sheet")
            ) {
                return true
            }
            if (name.contains("emoji_reaction_row") ||
                name.contains("reactions_container") ||
                name.contains("quick_reaction") ||
                name.contains("quick_snap_reaction")
            ) {
                sawReactionRow = true
            }
            if (sawReactionRow &&
                (name.contains("direct_thread") ||
                    name.contains("thread_view") ||
                    name.contains("message_list"))
            ) {
                return true
            }
            current = node.parent as? View
        }
        return sawReactionRow && rootContainsDirectMessageActions(view.rootView ?: view)
    }

    private fun rootContainsDirectMessageActions(root: View): Boolean {
        val stack = ArrayDeque<View>()
        stack.add(root)
        var visited = 0
        while (stack.isNotEmpty() && visited++ < 360) {
            val view = stack.removeFirst()
            val name = resourceEntryName(view)
            if (name.contains("message_actions") ||
                name.contains("direct_message_actions") ||
                name.contains("message_action_sheet")
            ) {
                return true
            }
            if (view is ViewGroup) {
                val count = view.childCount.coerceAtMost(18)
                for (index in 0 until count) stack.add(view.getChildAt(index))
            }
        }
        return false
    }

    private fun resourceEntryName(view: View): String {
        return runCatching {
            if (view.id != View.NO_ID && view.id > 0x00FFFFFF) view.resources.getResourceEntryName(view.id) else ""
        }.getOrDefault("").lowercase()
    }

    private fun findEmojiText(value: Any?): String? {
        if (value is String && containsEmoji(value)) return value
        value ?: return null
        if (value is View) return null
        val cls = value.javaClass
        if (cls.name.contains("emoji", ignoreCase = true)) {
            (readField(value, "A02") as? String)?.takeIf(::containsEmoji)?.let { return it }
        }
        var inspected = 0
        var current: Class<*>? = cls
        while (current != null && current != Any::class.java && inspected < 24) {
            current.declaredFields.forEach { field ->
                if (inspected++ >= 24 || field.type != String::class.java) return@forEach
                runCatching {
                    field.isAccessible = true
                    (field.get(value) as? String)?.takeIf(::containsEmoji)
                }.getOrNull()?.let { return it }
            }
            current = current.superclass
        }
        return null
    }

    private fun findImageViewField(value: Any?): ImageView? {
        value ?: return null
        if (value is ImageView) return value
        if (value is ViewGroup) return null
        var inspected = 0
        var current: Class<*>? = value.javaClass
        while (current != null && current != Any::class.java && inspected < 36) {
            current.declaredFields.forEach { field ->
                if (inspected++ >= 36 || !ImageView::class.java.isAssignableFrom(field.type)) return@forEach
                runCatching {
                    field.isAccessible = true
                    field.get(value) as? ImageView
                }.getOrNull()?.let { return it }
            }
            current = current.superclass
        }
        return null
    }

    private fun createInstagramEmojiTextDrawable(context: Context, size: Int, emojiText: String, typeface: Typeface): android.graphics.drawable.Drawable? {
        val loader = appClassLoader ?: return null
        val drawableClasses = buildList {
            instagramEmojiTextDrawableClass?.let(::add)
            arrayOf("X.29Y", "p000X.C29Y", "X.2IR", "p000X.C2IR").forEach { className ->
                runCatching { Class.forName(className, false, loader) }.getOrNull()?.let(::add)
            }
        }.distinct()
        drawableClasses.forEach { drawableClass ->
            val drawable = runCatching {
                val constructor = drawableClass.declaredConstructors.firstOrNull { ctor ->
                    ctor.parameterTypes.size == 2 &&
                        Context::class.java.isAssignableFrom(ctor.parameterTypes[0]) &&
                        ctor.parameterTypes[1] == java.lang.Integer.TYPE
                } ?: return@forEach
                constructor.isAccessible = true
                val drawable = constructor.newInstance(context, size)
                invokeDrawableMethod(drawable, "A0f", typeface)
                invokeDrawableMethod(drawable, "A0l", emojiText)
                invokeDrawableMethod(drawable, "A0V", size)
                invokeDrawableMethod(drawable, "A0o", java.lang.Boolean.TRUE)
                (drawable as? android.graphics.drawable.Drawable)?.also {
                    it.setBounds(0, 0, size, size)
                    instagramEmojiTextDrawableClass = drawableClass
                }
            }.getOrNull()
            if (drawable != null) return drawable
        }
        return null
    }

    private fun isQuickEmojiImageView(view: ImageView): Boolean {
        val name = runCatching {
            if (view.id != View.NO_ID && view.id > 0x00FFFFFF) view.resources.getResourceEntryName(view.id) else ""
        }.getOrDefault("")
        if (name == "item_emoji_overlay" || name == "quick_snap_reaction_item_emoji") return true
        val parent = view.parent as? ViewGroup ?: return false
        val count = parent.childCount.coerceAtMost(8)
        for (index in 0 until count) {
            if (isQuickEmojiButtonView(parent.getChildAt(index))) return true
        }
        return false
    }

    private fun createCustomEmojiImageUrl(emojiText: String): Any? {
        val typeface = getTypeface() ?: return null
        val loader = appClassLoader ?: return null
        val key = "${loadedPath}:${GENERATED_EMOJI_IMAGE_SIZE}:$emojiText"
        synchronized(customEmojiImageUrlCache) {
            customEmojiImageUrlCache[key]?.let { return it }
        }
        val dataUrl = renderEmojiDataUrl(emojiText, typeface, GENERATED_EMOJI_IMAGE_SIZE) ?: return null
        val imageUrl = runCatching {
            val cls = Class.forName("com.instagram.common.typedurl.SimpleImageUrl", false, loader)
            val constructor = cls.getConstructor(
                String::class.java,
                java.lang.Integer.TYPE,
                java.lang.Integer.TYPE
            )
            constructor.newInstance(dataUrl, GENERATED_EMOJI_IMAGE_SIZE, GENERATED_EMOJI_IMAGE_SIZE)
        }.getOrNull() ?: return null
        synchronized(customEmojiImageUrlCache) {
            customEmojiImageUrlCache[key] = imageUrl
        }
        return imageUrl
    }

    private fun renderEmojiDataUrl(emojiText: String, typeface: Typeface, size: Int): String? {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        return try {
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
                this.typeface = typeface
                textAlign = Paint.Align.CENTER
                textSize = size * 0.78f
            }
            val baseline = (size / 2f) - ((paint.descent() + paint.ascent()) / 2f)
            canvas.drawText(emojiText, size / 2f, baseline, paint)
            ByteArrayOutputStream(size * size).use { output ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) return null
                "data:image/png;base64," + Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
            }
        } catch (_: Throwable) {
            null
        } finally {
            bitmap.recycle()
        }
    }

    private fun invokeDrawableMethod(target: Any, name: String, arg: Any) {
        runCatching {
            val argClass = when (arg) {
                is Boolean -> java.lang.Boolean.TYPE
                is Int -> java.lang.Integer.TYPE
                is CharSequence -> CharSequence::class.java
                else -> arg.javaClass
            }
            val method = target.javaClass.methods.firstOrNull { method ->
                method.name == name &&
                    method.parameterTypes.size == 1 &&
                    method.parameterTypes[0].isAssignableFrom(argClass)
            } ?: target.javaClass.methods.firstOrNull { method ->
                method.name == name && method.parameterTypes.size == 1
            } ?: return
            method.isAccessible = true
            method.invoke(target, arg)
        }
    }

    private fun readField(target: Any?, name: String): Any? {
        if (target == null) return null
        var cls: Class<*>? = target.javaClass
        while (cls != null && cls != Any::class.java) {
            runCatching {
                val field = cls.getDeclaredField(name)
                field.isAccessible = true
                return field.get(target)
            }
            cls = cls.superclass
        }
        return null
    }

    private fun installFrameworkTextHooks() {
        val wrapFirstTextArg = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam<*>) {
                if (param.args.isEmpty() || param.args[0] !is CharSequence) return
                val original = param.args[0] as CharSequence
                val textStart = param.args.getOrNull(1) as? Int ?: 0
                val textEnd = param.args.getOrNull(2) as? Int ?: original.length
                if ((textEnd - textStart).coerceAtLeast(0) > 96) return
                val wrapped = wrapEmojiText(original, maxScanLength = 160)
                if (wrapped !== original) param.args[0] = wrapped
            }
        }

        runCatching {
            val method = StaticLayout.Builder::class.java.getDeclaredMethod(
                "obtain",
                CharSequence::class.java,
                java.lang.Integer.TYPE,
                java.lang.Integer.TYPE,
                TextPaint::class.java,
                java.lang.Integer.TYPE
            )
            XposedBridge.hookMethod(method, wrapFirstTextArg)
        }.onFailure { log("StaticLayout.Builder hook failed: ${it.message}") }

        // TextView.setText is handled by InstagramHooks to avoid duplicate framework hooks.
        // Keep the Builder hook for Litho-style text; broader layout constructor hooks are too hot.
    }

    private fun hasCustomSpan(text: Spannable, start: Int, end: Int): Boolean {
        return text.getSpans(start, end, CustomEmojiTypefaceSpan::class.java)
            .any { text.getSpanStart(it) == start && text.getSpanEnd(it) == end }
    }

    private fun containsEmoji(text: CharSequence): Boolean {
        if (!mightContainEmoji(text)) return false
        var index = 0
        while (index < text.length) {
            val codePoint = Character.codePointAt(text, index)
            if (isEmojiStart(text, index, codePoint)) return true
            index += Character.charCount(codePoint)
        }
        return false
    }

    private fun mightContainEmoji(text: CharSequence): Boolean {
        for (index in 0 until text.length) {
            if (text[index].code >= 0x00A9) return true
        }
        return false
    }

    private fun isEmojiOnlyLabel(text: CharSequence): Boolean {
        if (text.isBlank()) return false
        var foundEmoji = false
        var index = 0
        while (index < text.length) {
            val codePoint = Character.codePointAt(text, index)
            if (Character.isWhitespace(codePoint) ||
                codePoint == 0xFE0F ||
                codePoint == 0x200D ||
                codePoint == 0x20E3 ||
                isEmojiModifier(codePoint) ||
                isEmojiTag(codePoint)
            ) {
                index += Character.charCount(codePoint)
                continue
            }
            if (isEmojiStart(text, index, codePoint)) {
                foundEmoji = true
                index = findEmojiSequenceEnd(text, index)
                continue
            }
            return false
        }
        return foundEmoji
    }

    private fun isEmojiStart(text: CharSequence, index: Int, codePoint: Int): Boolean {
        if (isEmojiBase(codePoint) || isRegionalIndicator(codePoint)) return true
        if (codePoint == '#'.code || codePoint == '*'.code || codePoint in '0'.code..'9'.code) {
            val nextIndex = index + Character.charCount(codePoint)
            if (nextIndex >= text.length) return false
            val next = Character.codePointAt(text, nextIndex)
            if (next == 0x20E3) return true
            if (next == 0xFE0F) {
                val afterVariation = nextIndex + Character.charCount(next)
                return afterVariation < text.length && Character.codePointAt(text, afterVariation) == 0x20E3
            }
        }
        return false
    }

    private fun findEmojiSequenceEnd(text: CharSequence, index: Int): Int {
        val length = text.length
        val codePoint = Character.codePointAt(text, index)
        var cursor = index + Character.charCount(codePoint)

        if (isRegionalIndicator(codePoint) && cursor < length) {
            val next = Character.codePointAt(text, cursor)
            if (isRegionalIndicator(next)) cursor += Character.charCount(next)
            return cursor
        }

        while (cursor < length) {
            val next = Character.codePointAt(text, cursor)
            if (next == 0xFE0F || isEmojiModifier(next) || next == 0x20E3 || isEmojiTag(next)) {
                cursor += Character.charCount(next)
                continue
            }

            if (next == 0x200D) {
                val afterJoiner = cursor + Character.charCount(next)
                if (afterJoiner >= length) break
                val joined = Character.codePointAt(text, afterJoiner)
                if (!isEmojiBase(joined) && !isRegionalIndicator(joined)) break
                cursor = afterJoiner + Character.charCount(joined)
                continue
            }

            break
        }

        return cursor
    }

    private fun isEmojiBase(codePoint: Int): Boolean {
        return (codePoint in 0x1F000..0x1FAFF) ||
            (codePoint in 0x2600..0x27BF) ||
            codePoint == 0x00A9 ||
            codePoint == 0x00AE ||
            codePoint == 0x203C ||
            codePoint == 0x2049 ||
            codePoint == 0x2122 ||
            codePoint == 0x2139 ||
            codePoint == 0x3030 ||
            codePoint == 0x303D ||
            codePoint == 0x3297 ||
            codePoint == 0x3299
    }

    private fun isEmojiModifier(codePoint: Int): Boolean = codePoint in 0x1F3FB..0x1F3FF

    private fun isRegionalIndicator(codePoint: Int): Boolean = codePoint in 0x1F1E6..0x1F1FF

    private fun isEmojiTag(codePoint: Int): Boolean = codePoint in 0xE0020..0xE007F

    private fun providerHasFont(context: Context): Boolean {
        return runCatching {
            context.contentResolver.openInputStream(Uri.parse(PROVIDER_URI))?.use { input ->
                input.read() != -1
            } == true
        }.getOrDefault(false)
    }

    private fun clearEmojiImageUrlCache() {
        synchronized(customEmojiImageUrlCache) {
            customEmojiImageUrlCache.clear()
        }
    }

    private fun getTypeface(): Typeface? {
        val current = InstagramFeatureStateStore.current
        if (!current.customEmojiFontEnabled) return null

        val path = current.customEmojiFontPath.trim()
        if (path.isEmpty()) return importedTypefaceFallback()
        if (path == loadedPath && loadedTypeface != null) return loadedTypeface
        importedTypefaceFallback()?.let { fallback ->
            if (!File(path).canRead()) return fallback
        }
        if (path == failedPath) return importedTypefaceFallback()

        return try {
            val typeface = Typeface.createFromFile(path)
            if (path != loadedPath) clearEmojiImageUrlCache()
            loadedTypeface = typeface
            loadedPath = path
            if (loadedSourceUri.isBlank()) loadedSourceUri = path
            failedPath = ""
            log("Loaded emoji font $path")
            typeface
        } catch (throwable: Throwable) {
            failedPath = path
            importedTypefaceFallback() ?: run {
                loadedTypeface = null
                loadedPath = ""
                log("Cannot load emoji font $path: ${throwable.message}")
                null
            }
        }
    }

    private fun importedTypefaceFallback(): Typeface? {
        val ctx = appContext ?: return null
        val file = fontFile(ctx)
        if (!file.exists() || file.length() <= 0L) return null
        if (loadedPath == file.absolutePath && loadedTypeface != null) return loadedTypeface
        return runCatching {
            val typeface = Typeface.createFromFile(file)
            if (loadedPath != file.absolutePath) clearEmojiImageUrlCache()
            loadedTypeface = typeface
            loadedPath = file.absolutePath
            if (loadedSourceUri.isBlank()) loadedSourceUri = PROVIDER_URI
            failedPath = ""
            log("Using imported emoji font fallback ${file.absolutePath}")
            typeface
        }.getOrNull()
    }

    private fun fontFile(context: Context): File = File(File(context.filesDir, FONT_DIR), FONT_FILE)

    private fun updateEmojiFeatureState(
        enabled: Boolean,
        path: String,
        name: String,
        uri: String,
        source: String
    ) {
        val current = InstagramFeatureStateStore.current
        if (current.customEmojiFontEnabled == enabled &&
            current.customEmojiFontPath == path &&
            current.customEmojiFontName == name &&
            current.customEmojiFontUri == uri
        ) {
            return
        }
        val snapshot = JSONObject()
        InstagramFeatureState.booleanFeatureKeys.forEach { key ->
            snapshot.put(key, readFeatureField(key) as? Boolean ?: false)
        }
        InstagramFeatureState.stringFeatureKeys.forEach { key ->
            snapshot.put(key, readFeatureField(key) as? String ?: "")
        }
        snapshot.put("customEmojiFontEnabled", enabled)
        snapshot.put("customEmojiFontPath", path)
        snapshot.put("customEmojiFontName", name)
        snapshot.put("customEmojiFontUri", uri)
        InstagramFeatureStateStore.update(InstagramFeatureState.fromJson(snapshot.toString(), source))
    }

    private fun readFeatureField(key: String): Any? {
        return runCatching {
            InstagramFeatureState::class.java.getDeclaredField(key)
                .apply { isAccessible = true }
                .get(InstagramFeatureStateStore.current)
        }.getOrNull()
    }

    private class CustomEmojiTypefaceSpan : MetricAffectingSpan() {
        override fun updateMeasureState(textPaint: TextPaint) {
            getTypeface()?.let { textPaint.typeface = it }
        }

        override fun updateDrawState(textPaint: TextPaint) {
            getTypeface()?.let { textPaint.typeface = it }
        }
    }

    private class QuickEmojiFontDrawable(
        private val emojiText: String,
        private val typeface: Typeface,
        private val textScale: Float = 0.74f,
        private val intrinsicSize: Int = -1,
        private val paintColor: Int? = null
    ) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            textAlign = Paint.Align.CENTER
            this.typeface = this@QuickEmojiFontDrawable.typeface
            paintColor?.let { color = it }
        }

        override fun draw(canvas: Canvas) {
            val bounds = bounds
            if (bounds.isEmpty) return
            val size = minOf(bounds.width(), bounds.height()).toFloat()
            paint.textSize = size * textScale
            val baseline = bounds.centerY() - ((paint.descent() + paint.ascent()) / 2f)
            canvas.drawText(emojiText, bounds.centerX().toFloat(), baseline, paint)
        }

        override fun getIntrinsicWidth(): Int = intrinsicSize

        override fun getIntrinsicHeight(): Int = intrinsicSize

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Android")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    private fun log(message: String) {
        XposedBridge.log("[${InstagramFeatureState.TAG}|EmojiFont] $message")
    }
}
