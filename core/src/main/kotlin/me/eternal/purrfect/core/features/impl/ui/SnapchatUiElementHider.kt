package me.eternal.purrfect.core.features.impl.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.res.ColorStateList
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
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewStub
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import me.eternal.purrfect.common.Constants
import me.eternal.purrfect.core.ModContext
import me.eternal.purrfect.core.ui.getValdiViewNode
import me.eternal.purrfect.core.util.ktx.getObjectField
import me.eternal.purrfect.core.wrapper.impl.valdi.ValdiViewNode
import me.eternal.purrfect.core.whatsapp.WhatsAppUiElementSelector
import org.json.JSONObject
import java.util.Collections
import java.util.Locale
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

class SnapchatUiElementHider(
    private val context: ModContext
) {
    companion object {
        private const val TAG = "SnapchatUiElementHider"
        private const val MAX_COLLAPSE_WRAPPER_DEPTH = 8
        private const val SNAPCHAT_VIRTUAL_SELECTOR_PREFIX = "snapvirtual:v1|"
        private const val ACCESSIBILITY_HOST_VIEW_ID = -1
        private val activeHooks = Collections.synchronizedSet(
            Collections.newSetFromMap(WeakHashMap<SnapchatUiElementHider, Boolean>())
        )
        private val platformHooksInstalled = AtomicBoolean(false)
        private val internalChange = ThreadLocal<Boolean>()
        private val setMeasuredDimensionMethod by lazy {
            View::class.java.getDeclaredMethod(
                "setMeasuredDimension",
                Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!
            ).apply { isAccessible = true }
        }
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

        fun refreshActiveHooks(reason: String) {
            activeHooksSnapshot().forEach { hook ->
                hook.refreshVisibleRoots(reason)
            }
        }

        private fun activeHooksSnapshot(): List<SnapchatUiElementHider> {
            return synchronized(activeHooks) { activeHooks.toList() }
        }

        private fun isInternalChange(): Boolean = internalChange.get() == true
    }

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val trackedActivities = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<Activity, Boolean>()))
    private val trackedRoots = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<View, Boolean>()))
    private val originalStates = Collections.synchronizedMap(WeakHashMap<View, HiddenState>())
    private val collapsedWrappers = Collections.synchronizedMap(WeakHashMap<View, View>())
    private val loggedHiddenKeys = Collections.synchronizedSet(mutableSetOf<String>())
    private val captureOverlay = CaptureOverlay()
    @Volatile private var cachedRules = HiddenRules(false, "", "", emptySet(), emptyList())

    fun init() {
        synchronized(activeHooks) { activeHooks += this }
        installPlatformHooks()
        refreshVisibleRoots("init")
    }

    fun onActivityVisible(activity: Activity, reason: String) {
        if (activity.packageName != Constants.SNAPCHAT_PACKAGE_NAME) return
        if (activity.isFinishing || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && activity.isDestroyed)) {
            captureOverlay.removeFromActivity(activity)
            return
        }
        synchronized(trackedActivities) { trackedActivities += activity }
        applyToActivity(activity, reason)
    }

    private fun installPlatformHooks() {
        if (!platformHooksInstalled.compareAndSet(false, true)) return

        hookSafe("Activity.onResume") {
            XposedBridge.hookAllMethods(Activity::class.java, "onResume", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam<*>) {
                    (param.thisObject as? Activity)?.let { activity ->
                        activeHooksSnapshot().forEach { it.onActivityVisible(activity, "resume") }
                    }
                }
            })
        }

        hookSafe("Activity.onWindowFocusChanged") {
            XposedBridge.hookAllMethods(Activity::class.java, "onWindowFocusChanged", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam<*>) {
                    if (param.args.firstOrNull() != true) return
                    (param.thisObject as? Activity)?.let { activity ->
                        activeHooksSnapshot().forEach { it.onActivityVisible(activity, "focus") }
                    }
                }
            })
        }

        hookSafe("Activity.onDestroy") {
            XposedBridge.hookAllMethods(Activity::class.java, "onDestroy", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam<*>) {
                    (param.thisObject as? Activity)?.let { activity ->
                        activeHooksSnapshot().forEach { it.onActivityDestroyed(activity) }
                    }
                }
            })
        }

        hookSafe("View.onAttachedToWindow") {
            XposedBridge.hookAllMethods(View::class.java, "onAttachedToWindow", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam<*>) {
                    if (isInternalChange()) return
                    val view = param.thisObject as? View ?: return
                    activeHooksSnapshot().forEach { it.enforceVisibility(view) }
                }
            })
        }

        hookSafe("View.setVisibility") {
            XposedBridge.hookAllMethods(View::class.java, "setVisibility", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam<*>) {
                    if (isInternalChange()) return
                    val view = param.thisObject as? View ?: return
                    for (hook in activeHooksSnapshot()) {
                        if (hook.shouldHide(view)) {
                            hook.rememberOriginalState(view)
                            param.args[0] = View.GONE
                            break
                        }
                    }
                }
            })
        }

        hookSafe("View.setAlpha") {
            XposedBridge.hookAllMethods(View::class.java, "setAlpha", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam<*>) {
                    if (isInternalChange()) return
                    val view = param.thisObject as? View ?: return
                    for (hook in activeHooksSnapshot()) {
                        if (hook.shouldHide(view)) {
                            hook.rememberOriginalState(view)
                            param.args[0] = 0f
                            break
                        }
                    }
                }
            })
        }

        hookSafe("View.setEnabled") {
            XposedBridge.hookAllMethods(View::class.java, "setEnabled", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam<*>) {
                    if (isInternalChange()) return
                    val view = param.thisObject as? View ?: return
                    for (hook in activeHooksSnapshot()) {
                        if (hook.shouldHide(view)) {
                            hook.rememberOriginalState(view)
                            param.args[0] = false
                            break
                        }
                    }
                }
            })
        }

        hookSafe("View.setClickable") {
            XposedBridge.hookAllMethods(View::class.java, "setClickable", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam<*>) {
                    if (isInternalChange()) return
                    val view = param.thisObject as? View ?: return
                    for (hook in activeHooksSnapshot()) {
                        if (hook.shouldHide(view)) {
                            hook.rememberOriginalState(view)
                            param.args[0] = false
                            break
                        }
                    }
                }
            })
        }

        hookSafe("View.setLayoutParams") {
            XposedBridge.hookAllMethods(View::class.java, "setLayoutParams", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam<*>) {
                    if (isInternalChange()) return
                    val view = param.thisObject as? View ?: return
                    activeHooksSnapshot().forEach { it.enforceVisibility(view) }
                }
            })
        }

        hookSafe("View.measure") {
            XposedBridge.hookAllMethods(View::class.java, "measure", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam<*>) {
                    if (isInternalChange()) return
                    val view = param.thisObject as? View ?: return
                    for (hook in activeHooksSnapshot()) {
                        if (hook.shouldHide(view)) {
                            hook.rememberOriginalState(view)
                            hook.forceMeasuredZero(view)
                            break
                        }
                    }
                }
            })
        }

        hookSafe("View.layout") {
            XposedBridge.hookAllMethods(View::class.java, "layout", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam<*>) {
                    if (isInternalChange()) return
                    val view = param.thisObject as? View ?: return
                    activeHooksSnapshot().forEach { it.enforceVisibility(view) }
                }
            })
        }

        hookSafe("ViewGroup.addView") {
            XposedBridge.hookAllMethods(ViewGroup::class.java, "addView", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam<*>) {
                    if (isInternalChange()) return
                    val child = param.args.firstOrNull { it is View } as? View ?: return
                    activeHooksSnapshot().forEach { it.enforceTree(child) }
                }
            })
        }

        log("Snapchat UI element hooks installed")
    }

    private fun hookSafe(name: String, block: () -> Unit) {
        runCatching(block).onFailure { throwable ->
            log("Failed to install $name hook: ${throwable.message}")
        }
    }

    private fun onActivityDestroyed(activity: Activity) {
        synchronized(trackedActivities) { trackedActivities -= activity }
        captureOverlay.removeFromActivity(activity)
    }

    private fun refreshVisibleRoots(reason: String) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            refreshVisibleRootsOnMain(reason)
        } else {
            mainHandler.post { refreshVisibleRootsOnMain(reason) }
        }
    }

    private fun refreshVisibleRootsOnMain(reason: String) {
        val activities = synchronized(trackedActivities) { trackedActivities.toList() }
        activities.forEach { applyToActivity(it, reason) }

        val roots = synchronized(trackedRoots) { trackedRoots.toList() }
        roots.forEach { root ->
            if (!root.isAttachedToWindowCompat()) return@forEach
            runSafe("refresh $reason") { enforceTree(root) }
            root.post { runSafe("refresh $reason posted") { enforceTree(root) } }
            root.postDelayed({ runSafe("refresh $reason delayed") { enforceTree(root) } }, 200L)
        }
    }

    private fun applyToActivity(activity: Activity, reason: String) {
        runSafe("apply activity $reason") {
            val decor = activity.window?.decorView as? ViewGroup ?: return@runSafe
            synchronized(trackedRoots) { trackedRoots += decor }
            enforceTree(decor)
            if (isAnyCaptureEnabled()) {
                captureOverlay.applyToActivity(activity, decor)
            } else {
                captureOverlay.removeFromActivity(activity)
            }
        }
    }

    private fun enforceTree(view: View?) {
        view ?: return
        enforceVisibility(view)
        val group = view as? ViewGroup ?: return
        for (i in 0 until group.childCount) {
            enforceTree(group.getChildAt(i))
        }
    }

    private fun enforceVisibility(view: View?) {
        view ?: return
        val currentRules = rules()
        if (shouldHide(view, currentRules)) {
            val reflowTarget = if (matchesHiddenRule(view, currentRules)) {
                findCollapseTarget(view)
            } else {
                view
            }
            rememberOriginalState(view)
            logHiddenMatch(view)
            forceHidden(view)
            if (reflowTarget !== view) {
                collapseWrapper(view, reflowTarget)
            }
            return
        }

        collapsedWrappers.remove(view)
        originalStates.remove(view)?.let { restoreState(view, it) }
    }

    private fun shouldHide(view: View, rules: HiddenRules = rules()): Boolean {
        if (captureOverlay.isOverlayView(view) || isProtectedRoot(view)) return false
        if (!rules.hasAny) return false
        return matchesHiddenRule(view, rules) || shouldKeepCollapsedWrapperHidden(view, rules)
    }

    private fun matchesHiddenRule(view: View, rules: HiddenRules): Boolean {
        if (!rules.hasAny) return false

        val id = view.id
        if (id != View.NO_ID) {
            val idName = getResourceEntryName(view)
            if (!idName.isNullOrBlank() && idName in rules.ids) return true
        }

        if (rules.selectors.isNotEmpty()) {
            for (selector in rules.selectors) {
                if (WhatsAppUiElementSelector.matches(view, selector)) return true
            }
        }
        return false
    }

    private fun shouldKeepCollapsedWrapperHidden(view: View, rules: HiddenRules): Boolean {
        val source = collapsedWrappers[view] ?: return false
        if (source === view) return false
        val keepHidden = matchesHiddenRule(source, rules)
        if (!keepHidden) collapsedWrappers.remove(view)
        return keepHidden
    }

    private fun rememberOriginalState(view: View) {
        if (originalStates.containsKey(view)) return
        val params = view.layoutParams
        val marginParams = params as? ViewGroup.MarginLayoutParams
        originalStates[view] = HiddenState(
            visibility = view.visibility,
            alpha = view.alpha,
            enabled = view.isEnabled,
            clickable = view.isClickable,
            importantForAccessibility = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                view.importantForAccessibility
            } else {
                0
            },
            minimumWidth = view.minimumWidth,
            minimumHeight = view.minimumHeight,
            hasLayoutParams = params != null,
            layoutWidth = params?.width ?: ViewGroup.LayoutParams.WRAP_CONTENT,
            layoutHeight = params?.height ?: ViewGroup.LayoutParams.WRAP_CONTENT,
            hasMargins = marginParams != null,
            leftMargin = marginParams?.leftMargin ?: 0,
            topMargin = marginParams?.topMargin ?: 0,
            rightMargin = marginParams?.rightMargin ?: 0,
            bottomMargin = marginParams?.bottomMargin ?: 0
        )
    }

    private fun forceHidden(view: View) {
        runCatching {
            internalChange.set(true)
            view.alpha = 0f
            view.isEnabled = false
            view.isClickable = false
            if (view.visibility != View.GONE) view.visibility = View.GONE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            }
            collapseLayoutFootprint(view)
        }.onFailure { throwable ->
            log("Force hide failed: ${throwable.message}")
        }.also {
            internalChange.remove()
        }
    }

    private fun restoreState(view: View, state: HiddenState) {
        runCatching {
            internalChange.set(true)
            restoreLayoutFootprint(view, state)
            view.visibility = state.visibility
            view.alpha = state.alpha
            view.isEnabled = state.enabled
            view.isClickable = state.clickable
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                view.importantForAccessibility = state.importantForAccessibility
            }
        }.onFailure { throwable ->
            log("Restore hidden UI state failed: ${throwable.message}")
        }.also {
            internalChange.remove()
        }
    }

    private fun collapseWrapper(source: View, collapseTarget: View) {
        runCatching {
            collapsedWrappers[collapseTarget] = source
            rememberOriginalState(collapseTarget)
            forceHidden(collapseTarget)
            logCollapsedWrapper(source, collapseTarget)
        }.onFailure { throwable ->
            log("Wrapper collapse failed: ${throwable.message}")
        }
    }

    private fun findCollapseTarget(source: View): View {
        var best = source
        var current = source
        var depth = 0
        while (depth < MAX_COLLAPSE_WRAPPER_DEPTH && current.parent is ViewGroup) {
            val parent = current.parent as ViewGroup
            if (!isLayoutShell(parent, current)) break
            best = parent
            current = parent
            depth++
        }
        return best
    }

    private fun isLayoutShell(parent: ViewGroup, child: View): Boolean {
        var usefulChildren = 0
        for (i in 0 until parent.childCount) {
            val candidate = parent.getChildAt(i) ?: continue
            if (!isUsefulLayoutChild(candidate, child)) continue
            usefulChildren++
        }
        if (usefulChildren > 1) return false
        if (!isMeaningfullySmallerThanRoot(parent)) return false
        return isCompactByLayoutParams(parent) ||
            isCompactAroundChild(parent, child) ||
            hasSameScreenBounds(parent, child) ||
            hasWrapContentAxis(parent)
    }

    private fun isUsefulLayoutChild(candidate: View, hiddenChild: View): Boolean {
        if (candidate === hiddenChild) return true
        if (candidate is ViewStub) return false
        if (candidate.visibility == View.GONE || candidate.alpha <= 0.01f) return false
        if (candidate.width > 0 && candidate.height > 0) return true
        return !candidate.isLaidOutCompat()
    }

    private fun hasWrapContentAxis(view: View): Boolean {
        val params = view.layoutParams ?: return false
        return params.width == ViewGroup.LayoutParams.WRAP_CONTENT ||
            params.height == ViewGroup.LayoutParams.WRAP_CONTENT
    }

    private fun isCompactByLayoutParams(view: View): Boolean {
        val params = view.layoutParams ?: return false
        val maxCompactSide = dp(96)
        return isCompactDimension(params.width, maxCompactSide) &&
            isCompactDimension(params.height, maxCompactSide)
    }

    private fun isCompactDimension(value: Int, maxCompactSide: Int): Boolean {
        return value == ViewGroup.LayoutParams.WRAP_CONTENT || value in 1..maxCompactSide
    }

    private fun isCompactAroundChild(parent: View, child: View): Boolean {
        if (parent.width <= 0 || parent.height <= 0 || child.width <= 0 || child.height <= 0) return false
        val slackX = parent.width - child.width
        val slackY = parent.height - child.height
        val allowedSlack = dp(24)
        return slackX in 0..allowedSlack && slackY in 0..allowedSlack
    }

    private fun isMeaningfullySmallerThanRoot(view: View): Boolean {
        val root = view.rootView ?: return true
        if (view === root) return false
        if (view.width <= 0 || view.height <= 0 || root.width <= 0 || root.height <= 0) return true
        val viewArea = view.width.toLong() * view.height.toLong()
        val rootArea = root.width.toLong() * root.height.toLong()
        return viewArea * 100L < rootArea * 85L
    }

    private fun hasSameScreenBounds(parent: View, child: View): Boolean {
        if (parent.width <= 0 || parent.height <= 0 || child.width <= 0 || child.height <= 0) return false
        if (kotlin.math.abs(parent.width - child.width) > 2 || kotlin.math.abs(parent.height - child.height) > 2) return false

        val parentLocation = IntArray(2)
        val childLocation = IntArray(2)
        parent.getLocationOnScreen(parentLocation)
        child.getLocationOnScreen(childLocation)
        return kotlin.math.abs(parentLocation[0] - childLocation[0]) <= 2 &&
            kotlin.math.abs(parentLocation[1] - childLocation[1]) <= 2
    }

    private fun collapseLayoutFootprint(view: View) {
        val params = view.layoutParams
        if (params != null) {
            var changed = false
            if (params.width != 0) {
                params.width = 0
                changed = true
            }
            if (params.height != 0) {
                params.height = 0
                changed = true
            }
            if (params is ViewGroup.MarginLayoutParams &&
                (params.leftMargin != 0 || params.topMargin != 0 || params.rightMargin != 0 || params.bottomMargin != 0)
            ) {
                params.setMargins(0, 0, 0, 0)
                changed = true
            }
            if (changed) view.layoutParams = params
        }

        view.minimumWidth = 0
        view.minimumHeight = 0
        forceMeasuredZero(view)
        requestLayoutAround(view)
    }

    private fun restoreLayoutFootprint(view: View, state: HiddenState) {
        val params = view.layoutParams
        if (params != null && state.hasLayoutParams) {
            var changed = false
            if (params.width != state.layoutWidth) {
                params.width = state.layoutWidth
                changed = true
            }
            if (params.height != state.layoutHeight) {
                params.height = state.layoutHeight
                changed = true
            }
            if (params is ViewGroup.MarginLayoutParams && state.hasMargins) {
                if (params.leftMargin != state.leftMargin ||
                    params.topMargin != state.topMargin ||
                    params.rightMargin != state.rightMargin ||
                    params.bottomMargin != state.bottomMargin
                ) {
                    params.setMargins(state.leftMargin, state.topMargin, state.rightMargin, state.bottomMargin)
                    changed = true
                }
            }
            if (changed) view.layoutParams = params
        }
        view.minimumWidth = state.minimumWidth
        view.minimumHeight = state.minimumHeight
        requestLayoutAround(view)
    }

    private fun forceMeasuredZero(view: View) {
        runCatching {
            setMeasuredDimensionMethod.invoke(view, 0, 0)
        }
    }

    private fun requestLayoutAround(view: View) {
        view.requestLayout()
        var parent = view.parent
        var depth = 0
        while (parent is View && depth < 3) {
            parent.requestLayout()
            parent = parent.parent
            depth++
        }
    }

    private fun handleCapturedElement(activity: Activity, rawValue: String) {
        val isSelector = WhatsAppUiElementSelector.isSelector(rawValue)
        val clean = if (isSelector) WhatsAppUiElementSelector.normalize(rawValue) else normalizeUiElementId(rawValue)
        if (clean.isEmpty()) {
            Toast.makeText(activity, "This element cannot be hidden precisely.", Toast.LENGTH_SHORT).show()
            return
        }

        val uiElements = context.config.userInterface.uiElements
        if (isSelector) {
            uiElements.hiddenUiElementSelectors.set(appendUnique(uiElements.hiddenUiElementSelectors.getNullable().orEmpty(), clean))
        } else {
            uiElements.hiddenUiElementIds.set(appendUnique(uiElements.hiddenUiElementIds.getNullable().orEmpty(), clean))
        }
        uiElements.hideUiElements.set(true)

        runCatching {
            activity.sendBroadcast(
                Intent(Constants.SNAPCHAT_UI_ELEMENT_CAPTURED_ACTION)
                    .setPackage(Constants.MODULE_PACKAGE_NAME)
                    .putExtra(Constants.SNAPCHAT_UI_ELEMENT_CAPTURED_VALUE_EXTRA, clean)
                    .putExtra(Constants.SNAPCHAT_UI_ELEMENT_CAPTURED_IS_SELECTOR_EXTRA, isSelector)
            )
        }.onFailure { throwable ->
            log("Failed to persist captured Snapchat UI element: ${throwable.message}")
        }

        refreshVisibleRoots("captured")
        Toast.makeText(activity, if (isSelector) "Hidden exact element." else "Hidden: $clean", Toast.LENGTH_SHORT).show()
    }

    private fun handleCapturedThemeSurface(
        activity: Activity,
        target: View,
        rawValue: String,
        label: String,
        previewMode: ThemeSurfacePreviewMode = ThemeSurfacePreviewMode.AUTO,
        role: String = "Selected surface",
        bounds: String? = null,
        detail: String? = null
    ) {
        val isVirtualSelector = isSnapchatVirtualSelector(rawValue)
        val isSelector = isVirtualSelector || WhatsAppUiElementSelector.isSelector(rawValue)
        val clean = when {
            isVirtualSelector -> normalizeSnapchatVirtualSelector(rawValue)
            isSelector -> WhatsAppUiElementSelector.normalize(rawValue)
            else -> normalizeUiElementId(rawValue)
        }
        if (clean.isEmpty()) {
            Toast.makeText(activity, "This surface cannot be themed precisely.", Toast.LENGTH_SHORT).show()
            return
        }

        val existingRule = context.config.userInterface.customTheme.customSurfaceRules
            .getNullable()
            .orEmpty()
            .lineSequence()
            .mapNotNull(::parseThemeRule)
            .firstOrNull { it.target == clean && it.selector == isSelector }
        val initialColor = parseThemeColor(existingRule?.color)
            ?: currentThemeColorForTarget(target, previewMode)
            ?: 0xFF000000.toInt()
        showThemeSurfaceEditor(activity, target, clean, isSelector, label, initialColor, previewMode, role, bounds, detail)
    }

    private fun showThemeSurfaceEditor(
        activity: Activity,
        target: View,
        clean: String,
        isSelector: Boolean,
        label: String,
        initialColor: Int,
        requestedPreviewMode: ThemeSurfacePreviewMode = ThemeSurfacePreviewMode.AUTO,
        role: String = "Selected surface",
        bounds: String? = null,
        detail: String? = null
    ) {
        val originalBackground = target.background
        val originalTint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) target.backgroundTintList else null
        val originalImageTint = if (target is ImageView && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            target.imageTintList
        } else {
            null
        }
        val originalTextColor = themeTextColorForTargetCompat(target)
        val virtualPreviewBounds = if (isSnapchatVirtualSelector(clean)) {
            virtualSelectorValue(clean, "local_bounds")?.let(::parseVirtualBounds)
        } else {
            null
        }
        val previewMode = when {
            virtualPreviewBounds != null &&
                (requestedPreviewMode == ThemeSurfacePreviewMode.VIRTUAL_TEXT || isVirtualTextThemeSelector(clean)) -> ThemeSurfacePreviewMode.VIRTUAL_TEXT
            virtualPreviewBounds != null -> ThemeSurfacePreviewMode.VIRTUAL_OVERLAY
            requestedPreviewMode == ThemeSurfacePreviewMode.TEXT_COLOR && isThemeTextColorTarget(target) -> ThemeSurfacePreviewMode.TEXT_COLOR
            requestedPreviewMode == ThemeSurfacePreviewMode.IMAGE_TINT -> ThemeSurfacePreviewMode.IMAGE_TINT
            target is TextView && isSelector -> ThemeSurfacePreviewMode.TEXT_COLOR
            target is ImageView && isSelector -> ThemeSurfacePreviewMode.IMAGE_TINT
            else -> ThemeSurfacePreviewMode.BACKGROUND
        }
        var virtualPreviewDrawable: Drawable? = null
        var backgroundPreviewDrawable: Drawable? = null
        var latestColor = initialColor
        var applied = false

        fun previewBackgroundDrawable(): Drawable? {
            backgroundPreviewDrawable?.let { return it }
            val copy = cloneThemeDrawable(originalBackground)
            if (copy != null) {
                backgroundPreviewDrawable = copy
                target.background = copy
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    target.backgroundTintList = originalTint
                }
                log(
                    "Theme background preview cloned original target=$clean label=$label " +
                        "original=${originalBackground?.javaClass?.name ?: "none"} copy=${copy.javaClass.name} " +
                        "view=${themeCaptureTargetSummary(target)}"
                )
            }
            return backgroundPreviewDrawable
        }

        fun applyPreview(color: Int) {
            latestColor = color
            runCatching {
                when (previewMode) {
                    ThemeSurfacePreviewMode.VIRTUAL_TEXT -> {
                        val bounds = virtualPreviewBounds ?: return@runCatching
                        virtualPreviewDrawable?.let { target.overlay.remove(it) }
                        virtualPreviewDrawable = null
                        sendThemePreviewBroadcast(activity, clean, isSelector, colorHex = themeColorToHex(color), reason = "virtual-text-preview")
                        log(
                            "Theme virtual text preview delegated target=$clean color=${themeColorToHex(color)} mode=$previewMode " +
                                "localBounds=${boundsToString(bounds)} text=${virtualThemeTextForSelector(clean).ifBlank { "none" }} " +
                                "host=${target.javaClass.name} hostBounds=${screenBounds(target)}"
                        )
                    }
                    ThemeSurfacePreviewMode.VIRTUAL_OVERLAY -> {
                        val bounds = virtualPreviewBounds ?: return@runCatching
                        virtualPreviewDrawable?.let { target.overlay.remove(it) }
                        val previewDrawable = virtualThemePreviewDrawable(target, clean, color, bounds, previewMode)
                        virtualPreviewDrawable = previewDrawable
                        target.overlay.add(previewDrawable)
                        log(
                            "Theme virtual preview applied target=$clean color=${themeColorToHex(color)} mode=$previewMode " +
                                "localBounds=${boundsToString(bounds)} drawableBounds=${boundsToString(previewDrawable.bounds)} " +
                                "text=${virtualThemeTextForSelector(clean).ifBlank { "none" }} " +
                                "host=${target.javaClass.name} hostBounds=${screenBounds(target)}"
                        )
                    }
                    ThemeSurfacePreviewMode.IMAGE_TINT -> {
                        if (target is ImageView && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            target.imageTintList = ColorStateList.valueOf(color)
                            log(
                                "Theme image tint preview applied target=$clean color=${themeColorToHex(color)} " +
                                    "label=$label view=${themeCaptureTargetSummary(target)}"
                            )
                        } else {
                            target.setBackgroundColor(color)
                            log(
                                "Theme image tint preview fell back to background target=$clean color=${themeColorToHex(color)} " +
                                    "label=$label view=${themeCaptureTargetSummary(target)}"
                            )
                        }
                    }
                    ThemeSurfacePreviewMode.TEXT_COLOR -> {
                        if (applyThemeTextColorCompat(target, color, "preview", clean, label)) {
                            log(
                                "Theme text color preview applied target=$clean color=${themeColorToHex(color)} " +
                                    "label=$label view=${themeCaptureTargetSummary(target)}"
                            )
                        } else {
                            target.setBackgroundColor(color)
                            log(
                                "Theme text color preview fell back to background target=$clean color=${themeColorToHex(color)} " +
                                    "label=$label view=${themeCaptureTargetSummary(target)}"
                            )
                        }
                    }
                    ThemeSurfacePreviewMode.BACKGROUND,
                    ThemeSurfacePreviewMode.AUTO -> {
                        val drawable = previewBackgroundDrawable()
                        val appliedToDrawable = applyThemeColorToDrawable(drawable, color)
                        if (appliedToDrawable) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                                target.backgroundTintList = null
                            }
                            log(
                                "Theme background drawable preview applied target=$clean color=${themeColorToHex(color)} " +
                                    "label=$label drawable=${drawable?.javaClass?.name ?: "none"} view=${themeCaptureTargetSummary(target)}"
                            )
                        } else {
                            target.setBackgroundColor(color)
                            log(
                                "Theme background color preview fallback target=$clean color=${themeColorToHex(color)} " +
                                    "label=$label original=${originalBackground?.javaClass?.name ?: "none"} view=${themeCaptureTargetSummary(target)}"
                            )
                        }
                    }
                }
                target.invalidate()
            }
        }

        fun restorePreview() {
            when (previewMode) {
                ThemeSurfacePreviewMode.VIRTUAL_TEXT -> {
                    virtualPreviewDrawable?.let { target.overlay.remove(it) }
                    virtualPreviewDrawable = null
                    sendThemePreviewBroadcast(activity, clean, isSelector, colorHex = null, reason = "virtual-text-restore")
                    target.invalidate()
                }
                ThemeSurfacePreviewMode.VIRTUAL_OVERLAY -> {
                    virtualPreviewDrawable?.let { target.overlay.remove(it) }
                    virtualPreviewDrawable = null
                    target.invalidate()
                }
                ThemeSurfacePreviewMode.IMAGE_TINT -> {
                    if (target is ImageView && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        target.imageTintList = originalImageTint
                        target.invalidate()
                    } else {
                        restoreThemeTargetBackground(target, originalBackground, originalTint)
                    }
                }
                ThemeSurfacePreviewMode.TEXT_COLOR -> {
                    if (originalTextColor != null && applyThemeTextColorCompat(target, originalTextColor, "restore", clean, label)) {
                        target.invalidate()
                    } else {
                        restoreThemeTargetBackground(target, originalBackground, originalTint)
                    }
                }
                ThemeSurfacePreviewMode.BACKGROUND,
                ThemeSurfacePreviewMode.AUTO -> {
                    restoreThemeTargetBackground(target, originalBackground, originalTint)
                }
            }
        }

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val description = TextView(activity).apply {
            text = if (virtualPreviewBounds != null) {
                "Preview exact virtual bounds for $label."
            } else {
                "Preview and apply a color for $label."
            }
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        }
        val preview = TextView(activity).apply {
            text = themeColorToHex(initialColor)
            gravity = Gravity.CENTER
            setTextColor(if (isLightColor(initialColor)) Color.BLACK else Color.WHITE)
            setPadding(0, dp(12), 0, dp(12))
            background = roundedColorDrawable(initialColor)
        }
        val input = EditText(activity).apply {
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            setText(themeColorToHex(initialColor))
            selectAll()
        }
        val presets = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val channelControls = mutableListOf<Pair<String, SeekBar>>()
        var syncingEditorControls = false

        fun setPreviewSwatch(color: Int) {
            val hex = themeColorToHex(color)
            preview.text = hex
            preview.setTextColor(if (isLightColor(color)) Color.BLACK else Color.WHITE)
            preview.background = roundedColorDrawable(color)
        }

        fun channelValue(color: Int, channel: String): Int {
            return when (channel) {
                "A" -> Color.alpha(color)
                "R" -> Color.red(color)
                "G" -> Color.green(color)
                else -> Color.blue(color)
            }
        }

        fun colorFromChannelValues(): Int {
            var alpha = Color.alpha(latestColor)
            var red = Color.red(latestColor)
            var green = Color.green(latestColor)
            var blue = Color.blue(latestColor)
            channelControls.forEach { (channel, seekBar) ->
                when (channel) {
                    "A" -> alpha = seekBar.progress
                    "R" -> red = seekBar.progress
                    "G" -> green = seekBar.progress
                    "B" -> blue = seekBar.progress
                }
            }
            return Color.argb(alpha, red, green, blue)
        }

        fun syncChannelControls(color: Int) {
            syncingEditorControls = true
            channelControls.forEach { (channel, seekBar) ->
                val next = channelValue(color, channel)
                if (seekBar.progress != next) seekBar.progress = next
                (seekBar.tag as? TextView)?.text = "$channel $next"
            }
            syncingEditorControls = false
        }

        fun setEditorColor(color: Int) {
            latestColor = color
            val hex = themeColorToHex(color)
            syncingEditorControls = true
            runCatching {
                if (input.text?.toString() != hex) {
                    input.setText(hex)
                    input.setSelection(input.text?.length ?: 0)
                }
                setPreviewSwatch(color)
                channelControls.forEach { (channel, seekBar) ->
                    val next = channelValue(color, channel)
                    if (seekBar.progress != next) seekBar.progress = next
                    (seekBar.tag as? TextView)?.text = "$channel $next"
                }
            }
            syncingEditorControls = false
            applyPreview(color)
        }

        listOf(
            "AMOLED" to 0xFF000000.toInt(),
            "Panel" to 0xFF121212.toInt(),
            "Card" to 0xFF1D1D1D.toInt(),
            "White" to 0xFFFFFFFF.toInt()
        ).forEach { (name, color) ->
            presets.addView(
                TextView(activity).apply {
                    text = name
                    gravity = Gravity.CENTER
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setTextColor(if (isLightColor(color)) Color.BLACK else Color.WHITE)
                    background = roundedColorDrawable(color)
                    setPadding(dp(10), dp(8), dp(10), dp(8))
                    setOnClickListener { setEditorColor(color) }
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(dp(2), dp(8), dp(2), 0)
                }
            )
        }

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (syncingEditorControls) return
                val parsed = parseThemeColor(s?.toString()) ?: return
                latestColor = parsed
                setPreviewSwatch(parsed)
                syncChannelControls(parsed)
                applyPreview(parsed)
            }
        })

        val channels = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, 0)
        }

        fun addChannelRow(channel: String, initialValue: Int) {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val labelView = TextView(activity).apply {
                text = "$channel $initialValue"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            }
            val seekBar = SeekBar(activity).apply {
                max = 255
                progress = initialValue
                tag = labelView
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        labelView.text = "$channel $progress"
                        if (!fromUser || syncingEditorControls) return
                        setEditorColor(colorFromChannelValues())
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                    override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
                })
            }
            channelControls += channel to seekBar
            row.addView(
                labelView,
                LinearLayout.LayoutParams(dp(56), ViewGroup.LayoutParams.WRAP_CONTENT)
            )
            row.addView(
                seekBar,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
            channels.addView(
                row,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        addChannelRow("A", Color.alpha(initialColor))
        addChannelRow("R", Color.red(initialColor))
        addChannelRow("G", Color.green(initialColor))
        addChannelRow("B", Color.blue(initialColor))

        container.addView(description)
        container.addView(
            preview,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, dp(10), 0, dp(8))
            }
        )
        container.addView(input)
        container.addView(presets)
        container.addView(channels)

        val dialog = AlertDialog.Builder(activity)
            .setTitle("Customize theme surface")
            .setView(
                ScrollView(activity).apply {
                    isFillViewport = false
                    addView(container)
                }
            )
            .setPositiveButton("Apply", null)
            .setNegativeButton("Cancel", null)
            .setNeutralButton("AMOLED", null)
            .create()

        dialog.setOnShowListener {
            applyPreview(initialColor)
            dialog.getButton(DialogInterface.BUTTON_POSITIVE)?.setOnClickListener {
                val parsed = parseThemeColor(input.text?.toString())
                if (parsed == null) {
                    Toast.makeText(activity, "Enter a color like #FF000000.", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                persistCapturedThemeSurface(activity, target, clean, isSelector, label, parsed, previewMode, role, bounds, detail)
                applied = true
                dialog.dismiss()
            }
            dialog.getButton(DialogInterface.BUTTON_NEGATIVE)?.setOnClickListener {
                restorePreview()
                dialog.dismiss()
            }
            dialog.getButton(DialogInterface.BUTTON_NEUTRAL)?.setOnClickListener {
                setEditorColor(0xFF000000.toInt())
            }
        }
        dialog.setOnDismissListener {
            if (!applied) restorePreview()
        }
        runCatching { dialog.show() }.onFailure { throwable ->
            log("Failed to show Snapchat theme surface editor: ${throwable.message}")
            persistCapturedThemeSurface(activity, target, clean, isSelector, label, latestColor, previewMode, role, bounds, detail)
        }
    }

    private fun sendThemePreviewBroadcast(
        activity: Activity,
        target: String,
        isSelector: Boolean,
        colorHex: String?,
        reason: String
    ) {
        runCatching {
            activity.sendBroadcast(
                Intent(Constants.SNAPCHAT_THEME_PREVIEW_ACTION)
                    .setPackage(Constants.SNAPCHAT_PACKAGE_NAME)
                    .putExtra(Constants.SNAPCHAT_THEME_SURFACE_VALUE_EXTRA, target)
                    .putExtra(Constants.SNAPCHAT_THEME_SURFACE_IS_SELECTOR_EXTRA, isSelector)
                    .putExtra(Constants.SNAPCHAT_THEME_SURFACE_COLOR_EXTRA, colorHex.orEmpty())
            )
            log(
                "Theme preview broadcast sent reason=$reason target=$target selector=$isSelector " +
                    "color=${colorHex ?: "clear"} targetPackage=${Constants.SNAPCHAT_PACKAGE_NAME}"
            )
        }.onFailure { throwable ->
            log(
                "Theme preview broadcast failed reason=$reason target=$target selector=$isSelector " +
                    "color=${colorHex ?: "clear"} error=${throwable.message}"
            )
        }
    }

    private fun persistCapturedThemeSurface(
        activity: Activity,
        target: View,
        clean: String,
        isSelector: Boolean,
        label: String,
        color: Int,
        previewMode: ThemeSurfacePreviewMode = ThemeSurfacePreviewMode.AUTO,
        role: String = "Selected surface",
        bounds: String? = null,
        detail: String? = null
    ) {
        val customTheme = context.config.userInterface.customTheme
        val colorHex = themeColorToHex(color)
        customTheme.customSurfaceRules.set(
            appendOrReplaceThemeRule(
                customTheme.customSurfaceRules.getNullable().orEmpty(),
                ThemeSurfaceRule(
                    target = clean,
                    selector = isSelector,
                    label = label,
                    color = colorHex
                )
            )
        )
        customTheme.enabled.set(true)

        runCatching {
            val virtualBounds = if (isSnapchatVirtualSelector(clean)) {
                virtualSelectorValue(clean, "local_bounds")?.let(::parseVirtualBounds)
            } else {
                null
            }
            when {
                virtualBounds != null && previewMode == ThemeSurfacePreviewMode.VIRTUAL_TEXT -> {
                    sendThemePreviewBroadcast(activity, clean, isSelector, colorHex = colorHex, reason = "virtual-text-persist")
                    log(
                        "Theme virtual text apply delegated target=$clean color=$colorHex mode=$previewMode " +
                            "localBounds=${boundsToString(virtualBounds)} text=${virtualThemeTextForSelector(clean).ifBlank { "none" }} " +
                            "host=${target.javaClass.name} hostBounds=${screenBounds(target)}"
                    )
                }
                virtualBounds != null -> {
                    val drawable = virtualThemePreviewDrawable(target, clean, color, virtualBounds, previewMode)
                    target.overlay.add(drawable)
                    log(
                        "Theme virtual apply overlay target=$clean color=$colorHex mode=$previewMode " +
                            "localBounds=${boundsToString(virtualBounds)} drawableBounds=${boundsToString(drawable.bounds)} " +
                            "text=${virtualThemeTextForSelector(clean).ifBlank { "none" }} " +
                            "host=${target.javaClass.name} hostBounds=${screenBounds(target)}"
                    )
                }
                previewMode == ThemeSurfacePreviewMode.IMAGE_TINT && target is ImageView &&
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP -> {
                    target.imageTintList = ColorStateList.valueOf(color)
                    log(
                        "Theme image tint applied target=$clean color=$colorHex label=$label " +
                        "view=${themeCaptureTargetSummary(target)}"
                    )
                }
                previewMode == ThemeSurfacePreviewMode.TEXT_COLOR && isThemeTextColorTarget(target) -> {
                    val applied = applyThemeTextColorCompat(target, color, "apply", clean, label)
                    log(
                        "Theme text color applied target=$clean color=$colorHex label=$label " +
                            "applied=$applied view=${themeCaptureTargetSummary(target)}"
                    )
                }
                else -> {
                    val appliedToDrawable = applyThemeColorToDrawable(target.background, color)
                    if (appliedToDrawable) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            target.backgroundTintList = null
                        }
                        log(
                            "Theme background drawable applied target=$clean color=$colorHex label=$label " +
                                "mode=$previewMode drawable=${target.background?.javaClass?.name ?: "none"} " +
                                "view=${themeCaptureTargetSummary(target)}"
                        )
                    } else {
                        target.setBackgroundColor(color)
                        log(
                            "Theme background applied target=$clean color=$colorHex label=$label " +
                                "mode=$previewMode drawableFallback=false view=${themeCaptureTargetSummary(target)}"
                        )
                    }
                }
            }
            target.invalidate()
        }

        runCatching {
            val targetId = getResourceEntryName(target).orEmpty()
            val targetBounds = screenBounds(target)
            val localBounds = if (isSnapchatVirtualSelector(clean)) {
                virtualSelectorValue(clean, "local_bounds").orEmpty()
            } else {
                ""
            }
            val selectedBounds = bounds?.takeIf { it.isNotBlank() && it != "none" }
                ?: if (isSnapchatVirtualSelector(clean)) {
                    virtualSelectorValue(clean, "bounds").orEmpty()
                } else {
                    targetBounds
                }
            val targetSummary = themeCaptureTargetSummary(target)
            log(
                "Theme surface capture broadcast target=$clean selector=$isSelector label=$label role=$role " +
                    "mode=$previewMode color=$colorHex selectedBounds=${selectedBounds.ifBlank { "none" }} " +
                    "targetClass=${target.javaClass.name} targetId=${targetId.ifBlank { "none" }} " +
                    "targetBounds=$targetBounds localBounds=${localBounds.ifBlank { "none" }} " +
                    "detail=${detail?.take(900) ?: "none"} summary=$targetSummary"
            )
            activity.sendBroadcast(
                Intent(Constants.SNAPCHAT_THEME_SURFACE_CAPTURED_ACTION)
                    .setPackage(Constants.MODULE_PACKAGE_NAME)
                    .putExtra(Constants.SNAPCHAT_THEME_SURFACE_VALUE_EXTRA, clean)
                    .putExtra(Constants.SNAPCHAT_THEME_SURFACE_IS_SELECTOR_EXTRA, isSelector)
                    .putExtra(Constants.SNAPCHAT_THEME_SURFACE_LABEL_EXTRA, label)
                    .putExtra(Constants.SNAPCHAT_THEME_SURFACE_COLOR_EXTRA, colorHex)
                    .putExtra(Constants.SNAPCHAT_THEME_SURFACE_ROLE_EXTRA, role)
                    .putExtra(Constants.SNAPCHAT_THEME_SURFACE_PREVIEW_MODE_EXTRA, previewMode.name)
                    .putExtra(Constants.SNAPCHAT_THEME_SURFACE_BOUNDS_EXTRA, selectedBounds)
                    .putExtra(Constants.SNAPCHAT_THEME_SURFACE_LOCAL_BOUNDS_EXTRA, localBounds)
                    .putExtra(Constants.SNAPCHAT_THEME_SURFACE_DETAIL_EXTRA, detail.orEmpty())
                    .putExtra(Constants.SNAPCHAT_THEME_SURFACE_TARGET_CLASS_EXTRA, target.javaClass.name)
                    .putExtra(Constants.SNAPCHAT_THEME_SURFACE_TARGET_ID_EXTRA, targetId)
                    .putExtra(Constants.SNAPCHAT_THEME_SURFACE_TARGET_SUMMARY_EXTRA, targetSummary)
            )
        }.onFailure { throwable ->
            log("Failed to persist captured Snapchat theme surface: ${throwable.message}")
        }

        Toast.makeText(activity, "Theme surface updated: $label", Toast.LENGTH_SHORT).show()
    }

    private fun virtualSelectorValue(selector: String, key: String): String? {
        val clean = normalizeSnapchatVirtualSelector(selector)
        if (clean.isEmpty()) return null
        return clean.substring(SNAPCHAT_VIRTUAL_SELECTOR_PREFIX.length)
            .split('|')
            .mapNotNull { part ->
                val equals = part.indexOf('=')
                if (equals <= 0) null else part.substring(0, equals) to unescapeVirtualSelectorValue(part.substring(equals + 1))
            }
            .firstOrNull { it.first == key }
            ?.second
            ?.takeIf { it.isNotBlank() }
    }

    private fun unescapeVirtualSelectorValue(value: String): String {
        return value
            .replace("%0A", "\n")
            .replace("%3D", "=")
            .replace("%7C", "|")
            .replace("%25", "%")
    }

    private fun parseVirtualBounds(value: String): Rect? {
        val match = Regex("\\[(-?\\d+),(-?\\d+)]\\[(-?\\d+),(-?\\d+)]").matchEntire(value.trim()) ?: return null
        val left = match.groupValues[1].toIntOrNull() ?: return null
        val top = match.groupValues[2].toIntOrNull() ?: return null
        val right = match.groupValues[3].toIntOrNull() ?: return null
        val bottom = match.groupValues[4].toIntOrNull() ?: return null
        return Rect(left, top, right, bottom).takeIf { !it.isEmpty }
    }

    private fun virtualThemePreviewModeForRole(role: String): ThemeSurfacePreviewMode {
        return if (role == "Text color" || role == "Badge text") {
            ThemeSurfacePreviewMode.VIRTUAL_TEXT
        } else {
            ThemeSurfacePreviewMode.VIRTUAL_OVERLAY
        }
    }

    private fun virtualThemeTextForSelector(selector: String): String {
        val text = virtualSelectorValue(selector, "text").orEmpty()
        val desc = virtualSelectorValue(selector, "desc").orEmpty()
        return cleanVirtualSelectorTextValue(text).ifBlank { cleanVirtualSelectorTextValue(desc) }
    }

    private fun isVirtualTextThemeSelector(selector: String): Boolean {
        val text = cleanVirtualSelectorTextValue(virtualSelectorValue(selector, "text").orEmpty())
        if (text.isBlank()) return false
        if (text.matches(Regex("213\\d{6,}"))) return false
        if ("_" in text || text.equals("none", ignoreCase = true)) return false
        val signal = listOf(
            text,
            virtualSelectorValue(selector, "desc").orEmpty(),
            virtualSelectorValue(selector, "class").orEmpty(),
            virtualSelectorValue(selector, "view_id").orEmpty(),
            virtualSelectorValue(selector, "path").orEmpty()
        ).joinToString(" ").lowercase(Locale.US)
        if (listOf("avatar", "bitmoji", "thumbnail", "friendmoji").any { it in signal }) return false
        if (listOf("button", "feed_chat_button", "camera reply").any { it in signal }) return false
        return true
    }

    private fun cleanVirtualSelectorTextValue(value: String): String {
        val clean = value.trim()
        return clean.takeUnless { it.equals("none", ignoreCase = true) }.orEmpty()
    }

    private fun refinedVirtualTextBounds(host: View, selector: String, localBounds: Rect): Rect {
        val refined = Rect(localBounds)
        val hostId = virtualSelectorValue(selector, "host_id").orEmpty()
        val path = virtualSelectorValue(selector, "path").orEmpty()
        val text = virtualThemeTextForSelector(selector)
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
            log(
                "Theme virtual text bounds refined selector=${selector.take(220)} " +
                    "old=${boundsToString(old)} new=${boundsToString(refined)} measuredWidth=$measuredWidth " +
                    "safeRight=$rowActionSafeRight verticalOffset=$verticalOffset host=${themeCaptureTargetSummary(host)}"
            )
        }
        return refined
    }

    private fun virtualThemePreviewDrawable(
        host: View,
        selector: String,
        color: Int,
        localBounds: Rect,
        previewMode: ThemeSurfacePreviewMode
    ): Drawable {
        return if (previewMode == ThemeSurfacePreviewMode.VIRTUAL_TEXT || isVirtualTextThemeSelector(selector)) {
            VirtualTextThemeDrawable(
                text = virtualThemeTextForSelector(selector),
                color = color,
                bold = virtualSelectorValue(selector, "path") == "2" || localBounds.height() >= 56,
                backgroundColor = Color.rgb(18, 18, 18),
                textBounds = refinedVirtualTextBounds(host, selector, localBounds)
            )
        } else {
            ColorDrawable(color).apply { bounds = localBounds }
        }
    }

    private fun boundsToString(rect: Rect): String {
        return "[${rect.left},${rect.top}][${rect.right},${rect.bottom}]"
    }

    private fun screenBounds(view: View?): String {
        view ?: return "none"
        val location = IntArray(2)
        val rect = runCatching {
            view.getLocationOnScreen(location)
            Rect(location[0], location[1], location[0] + view.width, location[1] + view.height)
        }.getOrDefault(Rect())
        return if (rect.isEmpty) "none" else boundsToString(rect)
    }

    private fun themeCaptureTargetSummary(view: View?): String {
        view ?: return "none"
        val id = getResourceEntryName(view) ?: "none"
        val parent = view.parent as? View
        return buildString {
            append("class=").append(view.javaClass.name)
            append(" id=").append(id)
            append(" hash=").append(System.identityHashCode(view))
            append(" bounds=").append(screenBounds(view))
            append(" size=").append(view.width).append('x').append(view.height)
            append(" clickable=").append(view.isClickable)
            append(" enabled=").append(view.isEnabled)
            append(" bg=").append(view.background?.javaClass?.name ?: "none")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                append(" bgTint=").append(view.backgroundTintList?.defaultColor?.let(::themeColorToHex) ?: "none")
            }
            if (view is ImageView && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                append(" imageTint=").append(view.imageTintList?.defaultColor?.let(::themeColorToHex) ?: "none")
            }
            append(" parent=").append(parent?.javaClass?.name ?: "none")
            append("#").append(getResourceEntryName(parent) ?: "none")
        }.take(1200)
    }

    private fun restoreThemeTargetBackground(view: View, background: Drawable?, tint: ColorStateList?) {
        runCatching {
            view.background = background
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                view.backgroundTintList = tint
            }
            view.invalidate()
        }
    }

    private fun cloneThemeDrawable(drawable: Drawable?): Drawable? {
        drawable ?: return null
        return runCatching { drawable.constantState?.newDrawable()?.mutate() }.getOrNull()
    }

    private fun currentThemeColorForTarget(view: View, previewMode: ThemeSurfacePreviewMode): Int? {
        if (previewMode == ThemeSurfacePreviewMode.TEXT_COLOR && isThemeTextColorTarget(view)) {
            themeTextColorForTargetCompat(view)?.let { return it }
        }
        if (previewMode == ThemeSurfacePreviewMode.IMAGE_TINT && view is ImageView && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            view.imageTintList?.defaultColor?.let { return it }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            view.backgroundTintList?.defaultColor?.let { return it }
        }
        return when (val background = view.background) {
            is ColorDrawable -> background.color
            is GradientDrawable -> gradientDrawableSolidColor(background)
            is ShapeDrawable -> background.paint.color
            is LayerDrawable -> firstThemeColorInLayerDrawable(background)
            is StateListDrawable -> firstThemeColorInStateListDrawable(background)
            is InsetDrawable -> runCatching { background.drawable }.getOrNull()?.let {
                currentThemeColorForDrawable(it)
            }
            else -> null
        }
    }

    private fun isThemeTextColorTarget(view: View): Boolean {
        if (view is TextView) return true
        val signal = themeTextTargetSignal(view)
        return listOf(
            "snaplabelview",
            "snapfonttextview",
            "composersnaptextview",
            "snaptextview",
            "labelview",
            "textview"
        ).any { it in signal }
    }

    private fun themeTextTargetSignal(view: View): String {
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
        ).joinToString(" ").lowercase(Locale.US)
    }

    private fun themeTextColorForTargetCompat(view: View): Int? {
        if (view is TextView) return view.currentTextColor
        val methodColor = themeTextColorFromMethods(view)
        if (methodColor != null) return methodColor
        val helperColor = themeTextColorFromHelperFields(view)
        if (helperColor != null) return helperColor
        return themeTextColorFromPaintFields(view)
    }

    private fun themeTextColorFromMethods(view: View): Int? {
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

    private fun themeTextColorFromPaintFields(view: View): Int? {
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

    private fun applyThemeTextColorCompat(
        view: View,
        color: Int,
        reason: String,
        target: String,
        label: String
    ): Boolean {
        if (view is TextView) {
            view.setTextColor(color)
            log(
                "Theme text compat applied TextView reason=$reason target=$target color=${themeColorToHex(color)} " +
                    "label=$label view=${themeCaptureTargetSummary(view)}"
            )
            return true
        }
        if (!isThemeTextColorTarget(view)) {
            log(
                "Theme text compat skipped non-text target reason=$reason target=$target color=${themeColorToHex(color)} " +
                    "label=$label signal=${themeTextTargetSignal(view).take(360)} view=${themeCaptureTargetSummary(view)}"
            )
            return false
        }

        val attempts = mutableListOf<String>()
        if (applyThemeTextColorByMethod(view, color, attempts)) {
            view.invalidate()
            log(
                "Theme text compat applied reflected method reason=$reason target=$target color=${themeColorToHex(color)} " +
                    "label=$label attempts=${attempts.joinToString(" -> ").take(900)} view=${themeCaptureTargetSummary(view)}"
            )
            return true
        }
        if (applyThemeTextColorByHelperField(view, color, attempts)) {
            view.invalidate()
            log(
                "Theme text compat applied helper field reason=$reason target=$target color=${themeColorToHex(color)} " +
                    "label=$label attempts=${attempts.joinToString(" -> ").take(900)} view=${themeCaptureTargetSummary(view)}"
            )
            return true
        }
        if (applyThemeTextColorByPaintField(view, color, attempts)) {
            view.invalidate()
            log(
                "Theme text compat applied paint field reason=$reason target=$target color=${themeColorToHex(color)} " +
                    "label=$label attempts=${attempts.joinToString(" -> ").take(900)} view=${themeCaptureTargetSummary(view)}"
            )
            return true
        }
        if (applyThemeTextColorByIntField(view, color, attempts)) {
            view.invalidate()
            log(
                "Theme text compat applied color field reason=$reason target=$target color=${themeColorToHex(color)} " +
                    "label=$label attempts=${attempts.joinToString(" -> ").take(900)} view=${themeCaptureTargetSummary(view)}"
            )
            return true
        }
        log(
            "Theme text compat failed reason=$reason target=$target color=${themeColorToHex(color)} label=$label " +
                "signal=${themeTextTargetSignal(view).take(360)} attempts=${attempts.joinToString(" -> ").take(1200)} " +
                "view=${themeCaptureTargetSummary(view)}"
        )
        return false
    }

    private fun applyThemeTextColorByMethod(view: View, color: Int, attempts: MutableList<String>): Boolean {
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

    private fun themeTextColorFromHelperFields(view: View): Int? {
        for (clazz in viewClassHierarchy(view.javaClass)) {
            for (field in clazz.declaredFields) {
                if (java.lang.reflect.Modifier.isStatic(field.modifiers) || field.type.isPrimitive) continue
                val helper = runCatching {
                    field.isAccessible = true
                    field.get(view)
                }.getOrNull() ?: continue
                if (!looksLikeTextHelper(helper)) continue
                helperTextColor(helper)?.let { return it }
            }
        }
        return null
    }

    private fun helperTextColor(helper: Any): Int? {
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

    private fun applyThemeTextColorByHelperField(view: View, color: Int, attempts: MutableList<String>): Boolean {
        for (clazz in viewClassHierarchy(view.javaClass)) {
            for (field in clazz.declaredFields) {
                if (java.lang.reflect.Modifier.isStatic(field.modifiers) || field.type.isPrimitive) continue
                val helper = runCatching {
                    field.isAccessible = true
                    field.get(view)
                }.onFailure {
                    attempts += "helperField:${clazz.name}.${field.name} readFailed=${it.javaClass.simpleName}:${it.message}"
                }.getOrNull() ?: continue
                if (!looksLikeTextHelper(helper)) {
                    attempts += "helperField:${clazz.name}.${field.name} skippedHelper=${helper.javaClass.name}"
                    continue
                }
                if (applyThemeTextColorToHelper(helper, color, attempts, "${clazz.name}.${field.name}")) {
                    return true
                }
            }
        }
        return false
    }

    private fun looksLikeTextHelper(helper: Any): Boolean {
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

    private fun applyThemeTextColorToHelper(
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

    private fun applyThemeTextColorByPaintField(view: View, color: Int, attempts: MutableList<String>): Boolean {
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

    private fun applyThemeTextColorByIntField(view: View, color: Int, attempts: MutableList<String>): Boolean {
        val nameHints = listOf("textcolor", "labelcolor", "foregroundcolor", "fontcolor", "color")
        for (clazz in viewClassHierarchy(view.javaClass)) {
            for (field in clazz.declaredFields) {
                if (java.lang.reflect.Modifier.isStatic(field.modifiers) || java.lang.reflect.Modifier.isFinal(field.modifiers)) continue
                val name = field.name.lowercase(Locale.US)
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

    private fun currentThemeColorForDrawable(drawable: Drawable): Int? {
        return when (drawable) {
            is ColorDrawable -> drawable.color
            is GradientDrawable -> gradientDrawableSolidColor(drawable)
            is ShapeDrawable -> drawable.paint.color
            is LayerDrawable -> firstThemeColorInLayerDrawable(drawable)
            is InsetDrawable -> runCatching { drawable.drawable }.getOrNull()?.let(::currentThemeColorForDrawable)
            else -> null
        }
    }

    private fun firstThemeColorInLayerDrawable(drawable: LayerDrawable): Int? {
        for (index in 0 until drawable.numberOfLayers) {
            val child = runCatching { drawable.getDrawable(index) }.getOrNull() ?: continue
            currentThemeColorForDrawable(child)?.let { return it }
        }
        return null
    }

    private fun gradientDrawableSolidColor(drawable: GradientDrawable): Int? {
        val state = runCatching { drawable.getObjectField("mGradientState") }.getOrNull()
        val solidColors = runCatching { state?.getObjectField("mSolidColors") as? ColorStateList }.getOrNull()
        return solidColors?.defaultColor
            ?: runCatching { state?.getObjectField("mSolidColor") as? Int }.getOrNull()
    }

    private fun applyThemeColorToDrawable(drawable: Drawable?, color: Int, depth: Int = 0): Boolean {
        drawable ?: return false
        if (depth > 5) return false
        return when (drawable) {
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
            is StateListDrawable -> applyThemeColorToStateListDrawable(drawable, color, depth)
            is RippleDrawable -> applyThemeColorToLayerDrawable(drawable, color, depth)
            is LayerDrawable -> applyThemeColorToLayerDrawable(drawable, color, depth)
            is InsetDrawable -> runCatching { drawable.drawable }.getOrNull()?.let {
                applyThemeColorToDrawable(it, color, depth + 1)
            } == true
            else -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                runCatching {
                    drawable.mutate()
                    drawable.setTintList(ColorStateList.valueOf(color))
                }.isSuccess
            } else {
                false
            }
        }.also {
            if (it) runCatching { drawable.invalidateSelf() }
        }
    }

    private fun firstThemeColorInStateListDrawable(drawable: StateListDrawable): Int? {
        val count = stateListDrawableCount(drawable)
        for (index in 0 until count) {
            stateListDrawableChild(drawable, index)?.let(::currentThemeColorForDrawable)?.let { return it }
        }
        return runCatching { drawable.current }.getOrNull()?.takeIf { it !== drawable }?.let(::currentThemeColorForDrawable)
    }

    private fun applyThemeColorToStateListDrawable(drawable: StateListDrawable, color: Int, depth: Int): Boolean {
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
            val childChanged = applyThemeColorToDrawable(child, color, depth + 1)
            attempts += "child[$index]=${child.javaClass.name}:$childChanged"
            changed = childChanged || changed
        }
        if (!changed) {
            val current = runCatching { stateList.current }.getOrNull()?.takeIf { it !== stateList }
            if (current != null) {
                val currentChanged = applyThemeColorToDrawable(current, color, depth + 1)
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
        log(
            "Theme state-list drawable recolor color=${themeColorToHex(color)} depth=$depth count=$count " +
                "changed=$changed attempts=${attempts.joinToString()} drawable=${stateList.javaClass.name}"
        )
        return changed
    }

    private fun applyThemeColorToLayerDrawable(drawable: LayerDrawable, color: Int, depth: Int): Boolean {
        var changed = false
        for (index in 0 until drawable.numberOfLayers) {
            if (runCatching { drawable.getId(index) }.getOrNull() == android.R.id.mask) continue
            val child = runCatching { drawable.getDrawable(index) }.getOrNull() ?: continue
            changed = applyThemeColorToDrawable(child, color, depth + 1) || changed
        }
        return changed
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

    private fun parseThemeColor(value: String?): Int? {
        val raw = value?.trim().orEmpty()
        if (raw.isEmpty()) return null
        return runCatching { Color.parseColor(raw) }.getOrNull()
            ?: runCatching {
                val clean = raw.removePrefix("0x").removePrefix("0X").removePrefix("#")
                val parsed = clean.toLong(16)
                when (clean.length) {
                    6 -> 0xFF000000.toInt() or parsed.toInt()
                    8 -> parsed.toInt()
                    else -> null
                }
            }.getOrNull()
    }

    private fun themeColorToHex(color: Int): String {
        return "#%08X".format(Locale.US, color)
    }

    private fun isLightColor(color: Int): Boolean {
        val red = Color.red(color)
        val green = Color.green(color)
        val blue = Color.blue(color)
        return (red * 299 + green * 587 + blue * 114) / 1000 > 160
    }

    private fun roundedColorDrawable(color: Int): Drawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = 10f
        }
    }

    private fun isAnyCaptureEnabled(): Boolean {
        return context.config.userInterface.uiElements.captureUiElements.get() ||
            isThemeCaptureEnabled()
    }

    private fun isThemeCaptureEnabled(): Boolean {
        return CustomThemingRuntime.ENABLED &&
            context.config.userInterface.customTheme.captureThemeSurfaces.get()
    }

    private fun isSnapchatVirtualSelector(raw: String?): Boolean {
        return raw?.trim()?.startsWith(SNAPCHAT_VIRTUAL_SELECTOR_PREFIX) == true
    }

    private fun normalizeSnapchatVirtualSelector(raw: String?): String {
        val clean = raw?.trim().orEmpty()
        return if (clean.startsWith(SNAPCHAT_VIRTUAL_SELECTOR_PREFIX)) clean else ""
    }

    private fun appendOrReplaceThemeRule(raw: String, rule: ThemeSurfaceRule): String {
        val values = raw.lineSequence()
            .mapNotNull(::parseThemeRule)
            .filterNot { it.target == rule.target && it.selector == rule.selector }
            .toMutableList()
        values += rule
        return values.joinToString("\n") { it.toJsonLine() }
    }

    private fun parseThemeRule(rawLine: String): ThemeSurfaceRule? {
        val line = rawLine.trim()
        if (line.isEmpty()) return null
        return runCatching {
            val json = JSONObject(line)
            ThemeSurfaceRule(
                target = json.optString("target").trim(),
                selector = json.optBoolean("selector", false),
                label = json.optString("label").trim().ifEmpty { "Captured surface" },
                color = json.optString("color").trim().ifEmpty { "#FF000000" }
            )
        }.getOrNull()?.takeIf { it.target.isNotEmpty() }
    }

    private fun rules(): HiddenRules {
        val uiElements = context.config.userInterface.uiElements
        val current = cachedRules
        val enabled = uiElements.hideUiElements.get()
        val rawIds = uiElements.hiddenUiElementIds.getNullable().orEmpty()
        val rawSelectors = uiElements.hiddenUiElementSelectors.getNullable().orEmpty()
        if (current.enabled == enabled &&
            current.rawIds == rawIds &&
            current.rawSelectors == rawSelectors
        ) {
            return current
        }

        val next = HiddenRules(
            enabled = enabled,
            rawIds = rawIds,
            rawSelectors = rawSelectors,
            ids = parseIds(rawIds),
            selectors = parseSelectors(rawSelectors)
        )
        cachedRules = next
        return next
    }

    private fun parseIds(raw: String): Set<String> {
        if (raw.isBlank()) return emptySet()
        return raw.lineSequence()
            .map { normalizeUiElementId(it) }
            .filter { it.isNotEmpty() }
            .toCollection(LinkedHashSet())
    }

    private fun parseSelectors(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        return raw.lineSequence()
            .map { WhatsAppUiElementSelector.normalize(it) }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()
    }

    private fun appendUnique(raw: String, value: String): String {
        val values = raw.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toMutableList()
        if (values.none { it == value }) values += value
        return values.joinToString("\n")
    }

    private fun normalizeUiElementId(raw: String?): String {
        var clean = raw?.trim().orEmpty()
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

    private fun isProtectedRoot(view: View): Boolean {
        if (view.parent == null) return true
        val className = view.javaClass.name
        return className.endsWith("DecorView") ||
            className.contains("StatusBar", ignoreCase = true) ||
            className.contains("NavigationBar", ignoreCase = true)
    }

    private fun getResourceEntryName(view: View?): String? {
        if (view == null || view.id == View.NO_ID) return null
        return runCatching {
            view.resources?.getResourceEntryName(view.id)
        }.getOrNull()?.takeUnless(::isObfuscatedResourcePlaceholder)
    }

    private fun isObfuscatedResourcePlaceholder(value: String): Boolean {
        return value.endsWith("_resource_name_obfuscated") ||
            value == "0" ||
            value.matches(Regex("\\d+_resource_name_obfuscated"))
    }

    private fun logHiddenMatch(view: View) {
        val idName = getResourceEntryName(view)
        val key = idName ?: WhatsAppUiElementSelector.build(view, view.rootView)
        if (key.isBlank() || !loggedHiddenKeys.add(key)) return
        log("Hiding Snapchat UI ${idName?.let { "#$it" } ?: key} view=${view.javaClass.name}")
    }

    private fun logCollapsedWrapper(source: View, wrapper: View) {
        val sourceName = getResourceEntryName(source)?.let { "#$it" } ?: source.javaClass.simpleName
        val wrapperName = getResourceEntryName(wrapper)?.let { "#$it" } ?: wrapper.javaClass.simpleName
        log("Collapsed Snapchat UI slot $wrapperName for hidden $sourceName")
    }

    private fun runSafe(label: String, block: () -> Unit) {
        runCatching(block).onFailure { throwable ->
            log("$label failed: ${throwable.message}")
        }
    }

    private fun log(message: String) {
        XposedBridge.log("[$TAG] $message")
        context.log.verbose(message, TAG)
    }

    private fun View.isAttachedToWindowCompat(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT || isAttachedToWindow
    }

    private fun View.isLaidOutCompat(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            isLaidOut
        } else {
            width > 0 || height > 0
        }
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            context.resources.displayMetrics
        ).toInt()
    }

    private data class HiddenRules(
        val enabled: Boolean,
        val rawIds: String,
        val rawSelectors: String,
        val ids: Set<String>,
        val selectors: List<String>
    ) {
        val hasAny get() = enabled && (ids.isNotEmpty() || selectors.isNotEmpty())
    }

    private data class HiddenState(
        val visibility: Int,
        val alpha: Float,
        val enabled: Boolean,
        val clickable: Boolean,
        val importantForAccessibility: Int,
        val minimumWidth: Int,
        val minimumHeight: Int,
        val hasLayoutParams: Boolean,
        val layoutWidth: Int,
        val layoutHeight: Int,
        val hasMargins: Boolean,
        val leftMargin: Int,
        val topMargin: Int,
        val rightMargin: Int,
        val bottomMargin: Int
    )

    private data class ThemeSurfaceRule(
        val target: String,
        val selector: Boolean,
        val label: String,
        val color: String
    ) {
        fun toJsonLine(): String {
            return JSONObject()
                .put("target", target)
                .put("selector", selector)
                .put("label", label)
                .put("color", color)
                .toString()
        }
    }

    private inner class CaptureOverlay {
        private val states = Collections.synchronizedMap(WeakHashMap<Activity, OverlayState>())
        private val overlayTag = "purrfect_snapchat_ui_element_overlay"

        fun applyToActivity(activity: Activity, decor: ViewGroup) {
            val uiCaptureEnabled = context.config.userInterface.uiElements.captureUiElements.get()
            val themeCaptureEnabled = isThemeCaptureEnabled()
            if (!uiCaptureEnabled && !themeCaptureEnabled) {
                removeFromActivity(activity)
                log("Capture overlay removed: uiCapture=false themeCapture=false activity=${activity.javaClass.name}")
                return
            }

            val state = states.getOrPut(activity) { OverlayState() }
            if (state.button == null) {
                state.button = createButton(activity, decor, state)
                log("Capture overlay button created activity=${activity.javaClass.name} decor=${decor.javaClass.name}")
            }

            val button = state.button ?: return
            button.text = if (themeCaptureEnabled) {
                "TH"
            } else {
                "ID"
            }
            log("Capture overlay apply activity=${activity.javaClass.name} uiCapture=$uiCaptureEnabled themeCapture=$themeCaptureEnabled label=${button.text}")
            if (button.parent !== decor) {
                removeFromParent(button)
                decor.addView(button, createInitialButtonLayout(activity))
                log("Capture overlay attached activity=${activity.javaClass.name} decor=${decor.javaClass.name}")
            }
        }

        fun removeFromActivity(activity: Activity) {
            val state = states.remove(activity) ?: return
            removeFromParent(state.pickerOverlay)
            setLauncherSuppressed(state, suppressed = false, reason = "remove_activity")
            removeFromParent(state.button)
            state.pickerOverlay = null
            state.button = null
        }

        fun isOverlayView(view: View): Boolean {
            if (view.tag == overlayTag) return true
            synchronized(states) {
                for (state in states.values) {
                    if (view === state.button || view === state.pickerOverlay) return true
                }
            }
            return false
        }

        private fun createButton(activity: Activity, decor: ViewGroup, state: OverlayState): TextView {
            return TextView(activity).apply {
                tag = overlayTag
                text = "ID"
                setTextColor(0xFFFFFFFF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) elevation = dp(activity, 12).toFloat()

                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0xE6000000.toInt())
                    setStroke(dp(activity, 2), 0xFFFFFFFF.toInt())
                }

                setOnTouchListener(object : View.OnTouchListener {
                    private val touchSlop = ViewConfiguration.get(activity).scaledTouchSlop
                    private var downRawX = 0f
                    private var downRawY = 0f
                    private var downLeft = 0
                    private var downTop = 0
                    private var dragging = false

                    override fun onTouch(view: View, event: MotionEvent): Boolean {
                        val currentDecor = view.parent as? ViewGroup ?: decor
                        when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN -> {
                                downRawX = event.rawX
                                downRawY = event.rawY
                                val params = getFrameParams(view)
                                downLeft = params.leftMargin
                                downTop = params.topMargin
                                dragging = false
                                return true
                            }
                            MotionEvent.ACTION_MOVE -> {
                                val dx = event.rawX - downRawX
                                val dy = event.rawY - downRawY
                                if (kotlin.math.abs(dx) > touchSlop || kotlin.math.abs(dy) > touchSlop) {
                                    dragging = true
                                }
                                if (dragging) {
                                    moveButton(activity, currentDecor, view, downLeft + dx.toInt(), downTop + dy.toInt())
                                }
                                return true
                            }
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                if (!dragging && event.actionMasked == MotionEvent.ACTION_UP) {
                                    startPicker(activity, currentDecor, state)
                                }
                                return true
                            }
                            else -> return true
                        }
                    }
                })
            }
        }

        private fun createInitialButtonLayout(activity: Activity): FrameLayout.LayoutParams {
            val size = dp(activity, 52)
            val margin = dp(activity, 16)
            val width = activity.resources.displayMetrics.widthPixels
            return FrameLayout.LayoutParams(size, size).apply {
                gravity = Gravity.TOP or Gravity.START
                leftMargin = kotlin.math.max(margin, width - size - margin)
                topMargin = dp(activity, 160)
            }
        }

        private fun moveButton(activity: Activity, decor: ViewGroup, button: View, left: Int, top: Int) {
            val params = getFrameParams(button)
            val width = if (decor.width > 0) decor.width else activity.resources.displayMetrics.widthPixels
            val height = if (decor.height > 0) decor.height else activity.resources.displayMetrics.heightPixels
            val buttonWidth = if (button.width > 0) button.width else params.width
            val buttonHeight = if (button.height > 0) button.height else params.height
            params.leftMargin = left.coerceIn(0, kotlin.math.max(0, width - buttonWidth))
            params.topMargin = top.coerceIn(0, kotlin.math.max(0, height - buttonHeight))
            button.layoutParams = params
        }

        private fun getFrameParams(view: View): FrameLayout.LayoutParams {
            val rawParams = view.layoutParams
            if (rawParams is FrameLayout.LayoutParams) {
                rawParams.gravity = Gravity.TOP or Gravity.START
                return rawParams
            }

            val width = rawParams?.width ?: ViewGroup.LayoutParams.WRAP_CONTENT
            val height = rawParams?.height ?: ViewGroup.LayoutParams.WRAP_CONTENT
            return FrameLayout.LayoutParams(width, height).apply {
                gravity = Gravity.TOP or Gravity.START
                view.layoutParams = this
            }
        }

        private fun startPicker(activity: Activity, decor: ViewGroup, state: OverlayState) {
            if (state.pickerOverlay?.parent != null) {
                removeFromParent(state.pickerOverlay)
                state.pickerOverlay = null
                setLauncherSuppressed(state, suppressed = false, reason = "replace_picker")
            }

            if (isThemeCaptureEnabled()) {
                startPreciseThemePicker(activity, decor, state)
                return
            }

            val overlay = FrameLayout(activity).apply {
                tag = overlayTag
                isClickable = true
                isFocusable = true
                setBackgroundColor(0x330A84FF)
            }

            val label = TextView(activity).apply {
                tag = overlayTag
                text = if (isThemeCaptureEnabled()) "Tap a surface" else "Tap an element"
                setTextColor(0xFFFFFFFF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setPadding(dp(activity, 16), dp(activity, 8), dp(activity, 16), dp(activity, 8))
                background = GradientDrawable().apply {
                    setColor(0xE6000000.toInt())
                    cornerRadius = dp(activity, 20).toFloat()
                }
            }
            overlay.addView(
                label,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.CENTER_HORIZONTAL
                ).apply { topMargin = dp(activity, 28) }
            )

            overlay.setOnTouchListener { _, event ->
                if (event.actionMasked != MotionEvent.ACTION_UP) return@setOnTouchListener true
                val rawX = event.rawX
                val rawY = event.rawY
                removeFromParent(overlay)
                state.pickerOverlay = null

                try {
                    val target = findBestSelectionTargetAt(decor, rawX, rawY, state)
                    showSelectedView(activity, decor, target)
                } finally {
                    setLauncherSuppressed(state, suppressed = false, reason = "tap_picker_done")
                }
                true
            }

            state.pickerOverlay = overlay
            setLauncherSuppressed(state, suppressed = true, reason = "tap_picker_start")
            decor.addView(
                overlay,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )
            overlay.bringToFront()
            log(
                "Capture picker started activity=${activity.javaClass.name} themeCapture=${isThemeCaptureEnabled()} " +
                    "uiCapture=${context.config.userInterface.uiElements.captureUiElements.get()} " +
                    "decor=${selectionViewSummary(decor, decor)}"
            )
        }

        private fun startPreciseThemePicker(activity: Activity, decor: ViewGroup, state: OverlayState) {
            val overlay = FrameLayout(activity).apply {
                tag = overlayTag
                isClickable = true
                isFocusable = true
                setBackgroundColor(0x220A84FF)
            }

            val label = TextView(activity).apply {
                tag = overlayTag
                text = "Drag cursor to exact surface, then OK"
                setTextColor(0xFFFFFFFF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setPadding(dp(activity, 16), dp(activity, 8), dp(activity, 16), dp(activity, 8))
                background = GradientDrawable().apply {
                    setColor(0xE6000000.toInt())
                    cornerRadius = dp(activity, 20).toFloat()
                }
            }
            overlay.addView(
                label,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.CENTER_HORIZONTAL
                ).apply { topMargin = dp(activity, 28) }
            )

            val info = TextView(activity).apply {
                tag = overlayTag
                text = "Cursor: waiting"
                setTextColor(0xFFFFFFFF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                gravity = Gravity.CENTER
                setPadding(dp(activity, 12), dp(activity, 6), dp(activity, 12), dp(activity, 6))
                background = GradientDrawable().apply {
                    setColor(0xCC000000.toInt())
                    cornerRadius = dp(activity, 14).toFloat()
                }
            }
            overlay.addView(
                info,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.CENTER_HORIZONTAL
                ).apply { topMargin = dp(activity, 74) }
            )

            val cursorSize = dp(activity, 56)
            val cursor = TextView(activity).apply {
                tag = overlayTag
                text = "+"
                setTextColor(0xFFFFFFFF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 32f)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                isClickable = true
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) elevation = dp(activity, 24).toFloat()
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0x33000000)
                    setStroke(dp(activity, 2), 0xFFFFD400.toInt())
                }
            }
            overlay.addView(
                cursor,
                FrameLayout.LayoutParams(cursorSize, cursorSize, Gravity.TOP or Gravity.START)
            )

            val controls = LinearLayout(activity).apply {
                tag = overlayTag
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }

            fun moveCursorTo(rawX: Float, rawY: Float, source: String) {
                val params = getFrameParams(cursor)
                val width = if (overlay.width > 0) overlay.width else activity.resources.displayMetrics.widthPixels
                val height = if (overlay.height > 0) overlay.height else activity.resources.displayMetrics.heightPixels
                val controlsBottomMargin = overlayControlsBottomMargin(activity, overlay)
                params.leftMargin = (rawX.toInt() - cursorSize / 2).coerceIn(0, kotlin.math.max(0, width - cursorSize))
                params.topMargin = (rawY.toInt() - cursorSize / 2).coerceIn(0, kotlin.math.max(0, height - cursorSize))
                cursor.layoutParams = params
                val controlsParams = (controls.layoutParams as? FrameLayout.LayoutParams)
                    ?: FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                if (rawY > height - dp(activity, 360)) {
                    controlsParams.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    controlsParams.topMargin = dp(activity, 310)
                    controlsParams.bottomMargin = 0
                } else {
                    controlsParams.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                    controlsParams.topMargin = 0
                    controlsParams.bottomMargin = controlsBottomMargin
                }
                controls.layoutParams = controlsParams
                info.text = "Cursor: ${rawX.toInt()},${rawY.toInt()}"
                log(
                    "Theme cursor moved source=$source raw=${rawX.toInt()},${rawY.toInt()} " +
                        "cursorBounds=${screenBounds(cursor)} controls=${screenBounds(controls)} " +
                        "controlsBottomMargin=$controlsBottomMargin " +
                        "decor=${selectionViewSummary(decor, decor)}"
                )
            }

            cursor.setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN,
                    MotionEvent.ACTION_MOVE,
                    MotionEvent.ACTION_UP -> {
                        moveCursorTo(event.rawX, event.rawY, "cursor:${event.actionMasked}")
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> true
                    else -> true
                }
            }

            val cancel = createOverlayAction(activity, "Cancel").apply {
                setOnClickListener {
                    log("Theme cursor cancelled activity=${activity.javaClass.name}")
                    removeFromParent(overlay)
                    state.pickerOverlay = null
                    setLauncherSuppressed(state, suppressed = false, reason = "cursor_cancel")
                }
            }
            val ok = createOverlayAction(activity, "OK").apply {
                setOnClickListener {
                    val location = IntArray(2)
                    cursor.getLocationOnScreen(location)
                    val rawX = location[0] + cursor.width / 2f
                    val rawY = location[1] + cursor.height / 2f
                    log(
                        "Theme cursor confirm raw=${rawX.toInt()},${rawY.toInt()} " +
                            "cursorBounds=${screenBounds(cursor)} activity=${activity.javaClass.name}"
                    )
                    removeFromParent(overlay)
                    state.pickerOverlay = null
                    try {
                        val target = findBestSelectionTargetAt(decor, rawX, rawY, state)
                        showSelectedView(activity, decor, target)
                    } finally {
                        setLauncherSuppressed(state, suppressed = false, reason = "cursor_confirm")
                    }
                }
            }
            controls.addView(cancel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(activity, 32)).apply {
                rightMargin = dp(activity, 10)
            })
            controls.addView(ok, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(activity, 32)))
            overlay.addView(
                controls,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                ).apply { bottomMargin = overlayControlsBottomMargin(activity, overlay) }
            )

            overlay.setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN,
                    MotionEvent.ACTION_MOVE,
                    MotionEvent.ACTION_UP -> {
                        moveCursorTo(event.rawX, event.rawY, "overlay:${event.actionMasked}")
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> true
                    else -> true
                }
            }

            state.pickerOverlay = overlay
            setLauncherSuppressed(state, suppressed = true, reason = "cursor_start")
            decor.addView(
                overlay,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )
            overlay.bringToFront()
            overlay.post {
                val width = if (overlay.width > 0) overlay.width else activity.resources.displayMetrics.widthPixels
                val height = if (overlay.height > 0) overlay.height else activity.resources.displayMetrics.heightPixels
                moveCursorTo(width / 2f, height / 2f, "initial")
            }
            log(
                "Theme cursor picker started activity=${activity.javaClass.name} " +
                    "uiCapture=${context.config.userInterface.uiElements.captureUiElements.get()} " +
                    "decor=${selectionViewSummary(decor, decor)}"
            )
        }

        private fun createOverlayAction(activity: Activity, value: String): TextView {
            return TextView(activity).apply {
                tag = overlayTag
                text = value
                setTextColor(0xFFFFFFFF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                setPadding(dp(activity, 18), 0, dp(activity, 18), 0)
                background = GradientDrawable().apply {
                    setColor(0xE6000000.toInt())
                    cornerRadius = dp(activity, 16).toFloat()
                    setStroke(dp(activity, 1), 0xFFFFFFFF.toInt())
                }
            }
        }

        private fun overlayControlsBottomMargin(activity: Activity, overlay: View): Int {
            val minMargin = dp(activity, 20)
            val overlayHeight = if (overlay.height > 0) {
                overlay.height
            } else {
                activity.resources.displayMetrics.heightPixels
            }
            val visibleFrame = Rect()
            val visibleInset = runCatching {
                overlay.getWindowVisibleDisplayFrame(visibleFrame)
                if (!visibleFrame.isEmpty && visibleFrame.bottom in 1..overlayHeight) {
                    (overlayHeight - visibleFrame.bottom).coerceAtLeast(0)
                } else {
                    0
                }
            }.getOrDefault(0)
            val rootInset = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                runCatching { overlay.rootWindowInsets?.systemWindowInsetBottom ?: 0 }.getOrDefault(0)
            } else {
                0
            }
            val margin = kotlin.math.max(minMargin, kotlin.math.max(visibleInset, rootInset) + minMargin)
            return margin.coerceAtMost(kotlin.math.max(minMargin, overlayHeight / 2))
        }

        private fun setLauncherSuppressed(state: OverlayState, suppressed: Boolean, reason: String) {
            val button = state.button ?: return
            if (suppressed) {
                if (!state.launcherSuppressed) {
                    state.launcherPreviousVisibility = button.visibility
                    state.launcherPreviousClickable = button.isClickable
                    state.launcherPreviousFocusable = button.isFocusable
                }
                state.launcherSuppressed = true
                button.visibility = View.GONE
                button.isClickable = false
                button.isFocusable = false
                log(
                    "Capture launcher suppressed reason=$reason " +
                        "button=${selectionViewSummary(button, button)}"
                )
            } else if (state.launcherSuppressed) {
                button.visibility = state.launcherPreviousVisibility
                button.isClickable = state.launcherPreviousClickable
                button.isFocusable = state.launcherPreviousFocusable
                state.launcherSuppressed = false
                log(
                    "Capture launcher restored reason=$reason " +
                        "button=${selectionViewSummary(button, button)}"
                )
            }
        }

        private fun findBestSelectionTargetAt(root: View, rawX: Float, rawY: Float, state: OverlayState): SelectionTarget? {
            val candidates = mutableListOf<ViewCandidate>()
            collectViewsAt(root, root, rawX, rawY, state, 0, candidates)
            val sortedCandidates = candidates.sorted()
            val selectedView = sortedCandidates.firstOrNull()?.view
            val virtualTargets = if (isThemeCaptureEnabled()) {
                sortedCandidates
                    .asSequence()
                    .take(18)
                    .mapNotNull { candidate -> findBestVirtualNodeAt(root, candidate.view, rawX, rawY) }
                    .sortedWith(compareBy<VirtualNodeTarget> { it.area }.thenByDescending { it.depth })
                    .distinctBy { it.selector }
                    .toList()
            } else {
                emptyList()
            }
            val virtualTarget = virtualTargets.firstOrNull()
            val selected = when {
                virtualTarget != null -> SelectionTarget(
                    view = virtualTarget.hostView,
                    virtualNode = virtualTarget,
                    virtualCandidates = virtualTargets,
                    viewCandidates = sortedCandidates
                )
                selectedView != null -> SelectionTarget(
                    view = selectedView,
                    viewCandidates = sortedCandidates
                )
                else -> null
            }
            log(
                "Capture picker tap raw=${rawX.toInt()},${rawY.toInt()} candidates=${candidates.size} " +
                    "selected=${selectionTargetSummary(selected, root)} " +
                    "virtualSelected=${virtualTarget?.let(::virtualNodeSummary) ?: "none"} " +
                    "top=${sortedCandidates.take(12).mapIndexed { index, candidate ->
                        "#$index depth=${candidate.depth} area=${candidate.area} viewGroup=${candidate.viewGroup} " +
                            "hasId=${candidate.hasId} interactive=${candidate.interactive} ${selectionViewSummary(candidate.view, root)}"
                    }.joinToString(" || ")}"
            )
            return selected
        }

        private fun findBestVirtualNodeAt(root: View, host: View, rawX: Float, rawY: Float): VirtualNodeTarget? {
            val hostId = getResourceEntryName(host)
            val hostSelector = runCatching { WhatsAppUiElementSelector.build(host, root) }.getOrNull().orEmpty()
            if (hostId.isNullOrBlank() && hostSelector.isBlank()) return null

            val hostBounds = boundsRect(host)
            if (hostBounds.isEmpty) return null
            val rawXi = rawX.toInt()
            val rawYi = rawY.toInt()
            val out = mutableListOf<VirtualNodeTarget>()
            val provider = runCatching { host.accessibilityNodeProvider }.getOrNull()
            val roots = mutableListOf<Pair<String, AccessibilityNodeInfo>>()
            val rootSummaries = mutableListOf<String>()
            val reflectedIds = mutableListOf<String>()
            var visited = 0
            var directChildHits = 0
            var providerChildHits = 0
            var missingChildHits = 0

            provider?.let { nodeProvider ->
                runCatching { nodeProvider.createAccessibilityNodeInfo(ACCESSIBILITY_HOST_VIEW_ID) }
                    .getOrNull()
                    ?.let { roots += "provider_host" to it }
            }
            runCatching { host.createAccessibilityNodeInfo() }
                .getOrNull()
                ?.let { roots += "view_create" to it }

            fun resolveChild(node: AccessibilityNodeInfo, index: Int): AccessibilityNodeInfo? {
                runCatching { node.getChild(index) }.getOrNull()?.let { child ->
                    directChildHits++
                    return child
                }
                val virtualId = reflectedAccessibilityVirtualChildId(node, index)
                if (virtualId != null && provider != null) {
                    reflectedIds += "$index->$virtualId"
                    runCatching { provider.createAccessibilityNodeInfo(virtualId) }.getOrNull()?.let { child ->
                        providerChildHits++
                        return child
                    }
                }
                missingChildHits++
                return null
            }

            fun walk(node: AccessibilityNodeInfo, depth: Int, path: String, source: String) {
                if (visited++ > 160 || depth > 10) return
                val bounds = Rect()
                runCatching { node.getBoundsInScreen(bounds) }
                if (!bounds.isEmpty && bounds.contains(rawXi, rawYi)) {
                    val text = node.text?.toString().orEmpty().trim()
                    val contentDescription = node.contentDescription?.toString().orEmpty().trim()
                    val viewId = runCatching { node.viewIdResourceName.orEmpty() }.getOrDefault("").trim()
                    val className = node.className?.toString().orEmpty().trim()
                    if (isUsefulVirtualNode(bounds, hostBounds, text, contentDescription, viewId, className)) {
                        val local = Rect(
                            bounds.left - hostBounds.left,
                            bounds.top - hostBounds.top,
                            bounds.right - hostBounds.left,
                            bounds.bottom - hostBounds.top
                        )
                        val selector = buildSnapchatVirtualSelector(
                            source = source,
                            hostId = hostId,
                            hostSelector = hostSelector,
                            hostClass = host.javaClass.name,
                            path = path.ifBlank { "host" },
                            bounds = boundsToString(bounds),
                            localBounds = boundsToString(local),
                            className = className,
                            text = text,
                            contentDescription = contentDescription,
                            viewId = viewId
                        )
                        if (selector.isNotBlank()) {
                            out += VirtualNodeTarget(
                                hostView = host,
                                selector = selector,
                                displayName = virtualDisplayName(text, contentDescription, viewId, className, bounds),
                                bounds = boundsToString(bounds),
                                localBounds = boundsToString(local),
                                path = path.ifBlank { "host" },
                                className = className.ifBlank { "unknown" },
                                text = text.ifBlank { "none" },
                                contentDescription = contentDescription.ifBlank { "none" },
                                viewIdResourceName = viewId.ifBlank { "none" },
                                depth = depth,
                                area = virtualNodeArea(bounds)
                            )
                        }
                    }
                }

                for (index in 0 until node.childCount) {
                    val child = resolveChild(node, index) ?: continue
                    try {
                        walk(child, depth + 1, if (path.isBlank()) index.toString() else "$path/$index", source)
                    } finally {
                        runCatching { child.recycle() }
                    }
                }
            }

            roots.forEach { (source, node) ->
                try {
                    val bounds = Rect()
                    runCatching { node.getBoundsInScreen(bounds) }
                    rootSummaries += "$source childCount=${node.childCount} bounds=${boundsToString(bounds)} " +
                        "class=${node.className ?: "none"} text=${node.text ?: "none"} desc=${node.contentDescription ?: "none"}"
                    walk(node, 0, "", source)
                } finally {
                    runCatching { node.recycle() }
                }
            }

            findBestValdiNodeAt(root, host, hostId, hostSelector, hostBounds, rawXi, rawYi)?.let { out += it }

            val selected = out.sortedWith(compareBy<VirtualNodeTarget> { it.area }.thenByDescending { it.depth }).firstOrNull()
            log(
                "Theme virtual probe host=${selectionViewSummary(host, root)} raw=${rawXi},${rawYi} " +
                    "provider=${provider?.javaClass?.name ?: "none"} roots=${rootSummaries.joinToString(" || ").ifBlank { "none" }} " +
                    "hiddenChildIdMethod=${accessibilityGetChildIdMethod != null} hiddenVirtualIdMethod=${accessibilityGetVirtualDescendantIdMethod != null} " +
                    "visited=$visited directChildHits=$directChildHits providerChildHits=$providerChildHits missingChildHits=$missingChildHits " +
                    "reflectedIds=${reflectedIds.take(32).joinToString(",").ifBlank { "none" }} count=${out.size} " +
                    "selected=${selected?.let(::virtualNodeSummary) ?: "none"} " +
                    "top=${out.sortedWith(compareBy<VirtualNodeTarget> { it.area }.thenByDescending { it.depth }).take(12).joinToString(" || ") { virtualNodeSummary(it) }.ifBlank { "none" }}"
            )
            return selected
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

        private fun findBestValdiNodeAt(
            root: View,
            host: View,
            hostId: String?,
            hostSelector: String,
            hostBounds: Rect,
            rawXi: Int,
            rawYi: Int
        ): VirtualNodeTarget? {
            val rootNode = runCatching { host.getValdiViewNode() }.getOrNull()
            if (rootNode == null) {
                log("Theme Valdi probe host=${selectionViewSummary(host, root)} raw=${rawXi},${rawYi} rootNode=none")
                return null
            }

            val out = mutableListOf<VirtualNodeTarget>()
            val sampledNodes = mutableListOf<String>()
            var visited = 0
            var childLinks = 0
            var attrBoundsHits = 0

            fun walk(node: ValdiViewNode, depth: Int, path: String) {
                if (visited++ > 160 || depth > 12) return
                val className = runCatching { node.getClassName() }.getOrNull().orEmpty().trim()
                val debug = runCatching { node.toString() }.getOrNull().orEmpty().trim()
                val text = firstValdiAttribute(node, "text", "title", "label", "name", "accessibilityLabel", "accessibilityLabelValue")
                val contentDescription = firstValdiAttribute(node, "contentDescription", "content_description", "accessibilityHint", "accessibilityRole")
                val viewId = firstValdiAttribute(
                    node,
                    "id",
                    "identifier",
                    "accessibilityIdentifier",
                    "resourceID",
                    "resourceId",
                    "resource_id",
                    "viewID",
                    "viewId",
                    "view_id",
                    "testID",
                    "testId",
                    "test_id"
                )
                val explicitBoundsRaw = firstValdiAttribute(node, "bounds", "frame", "rect", "layout", "screenBounds", "screen_bounds")
                val boundsRaw = explicitBoundsRaw.ifBlank {
                    if (looksLikeValdiBoundsDebug(debug)) debug else ""
                }
                val bounds = parseValdiScreenBounds(boundsRaw, hostBounds, rawXi, rawYi)
                if (sampledNodes.size < 18) {
                    sampledNodes += "path=${path.ifBlank { "root" }} class=${className.ifBlank { "unknown" }} " +
                        "text=${text.ifBlank { "none" }} desc=${contentDescription.ifBlank { "none" }} " +
                        "viewId=${viewId.ifBlank { "none" }} boundsRaw=${boundsRaw.take(180).ifBlank { "none" }} " +
                        "parsed=${bounds?.let(::boundsToString) ?: "none"}"
                }
                if (bounds != null && bounds.contains(rawXi, rawYi)) {
                    attrBoundsHits++
                    val local = Rect(
                        bounds.left - hostBounds.left,
                        bounds.top - hostBounds.top,
                        bounds.right - hostBounds.left,
                        bounds.bottom - hostBounds.top
                    )
                    if (isUsefulVirtualNode(bounds, hostBounds, text, contentDescription, viewId, className)) {
                        val selector = buildSnapchatVirtualSelector(
                            source = "valdi",
                            hostId = hostId,
                            hostSelector = hostSelector,
                            hostClass = host.javaClass.name,
                            path = path.ifBlank { "valdi" },
                            bounds = boundsToString(bounds),
                            localBounds = boundsToString(local),
                            className = className,
                            text = text,
                            contentDescription = contentDescription,
                            viewId = viewId
                        )
                        if (selector.isNotBlank()) {
                            out += VirtualNodeTarget(
                                hostView = host,
                                selector = selector,
                                displayName = virtualDisplayName(text, contentDescription, viewId, className, bounds),
                                bounds = boundsToString(bounds),
                                localBounds = boundsToString(local),
                                path = "valdi:${path.ifBlank { "root" }}",
                                className = className.ifBlank { "unknown" },
                                text = text.ifBlank { "none" },
                                contentDescription = contentDescription.ifBlank { "none" },
                                viewIdResourceName = viewId.ifBlank { "none" },
                                depth = depth,
                                area = virtualNodeArea(bounds)
                            )
                        }
                    }
                }

                val children = runCatching { node.getChildren() }.getOrNull().orEmpty()
                childLinks += children.size
                children.forEachIndexed { index, child ->
                    walk(child, depth + 1, if (path.isBlank()) index.toString() else "$path/$index")
                }
            }

            walk(rootNode, 0, "")
            val selected = out.sortedWith(compareBy<VirtualNodeTarget> { it.area }.thenByDescending { it.depth }).firstOrNull()
            log(
                "Theme Valdi probe host=${selectionViewSummary(host, root)} raw=${rawXi},${rawYi} " +
                    "visited=$visited childLinks=$childLinks attrBoundsHits=$attrBoundsHits count=${out.size} " +
                    "selected=${selected?.let(::virtualNodeSummary) ?: "none"} " +
                    "samples=${sampledNodes.joinToString(" || ").ifBlank { "none" }}"
            )
            return selected
        }

        private fun firstValdiAttribute(node: ValdiViewNode, vararg names: String): String {
            for (name in names) {
                val value = runCatching { node.getAttribute(name) }.getOrNull()?.toString()?.trim().orEmpty()
                if (value.isNotBlank() && value != "null") return value
            }
            return ""
        }

        private fun looksLikeValdiBoundsDebug(value: String): Boolean {
            if (value.isBlank()) return false
            return Regex("(?i)(bounds|frame|rect|screen_bounds|screenBounds|left\\s*[=:]|top\\s*[=:]|right\\s*[=:]|bottom\\s*[=:]|x\\s*[=:]|y\\s*[=:]|width\\s*[=:]|height\\s*[=:]|w\\s*[=:]|h\\s*[=:])")
                .containsMatchIn(value)
        }

        private fun parseValdiScreenBounds(value: String, hostBounds: Rect, rawXi: Int, rawYi: Int): Rect? {
            if (value.isBlank()) return null
            val candidates = mutableListOf<Rect>()
            parseVirtualBounds(value)?.let { rect ->
                candidates += rect
                candidates += Rect(
                    hostBounds.left + rect.left,
                    hostBounds.top + rect.top,
                    hostBounds.left + rect.right,
                    hostBounds.top + rect.bottom
                )
            }

            fun labeled(label: String): Int? {
                val pattern = Regex("(?i)(?:^|[^a-zA-Z])$label\\s*[=:]\\s*(-?\\d+(?:\\.\\d+)?)")
                return pattern.find(value)?.groupValues?.getOrNull(1)?.toFloatOrNull()?.toInt()
            }

            val left = labeled("left")
            val top = labeled("top")
            val right = labeled("right")
            val bottom = labeled("bottom")
            if (left != null && top != null && right != null && bottom != null) {
                candidates += Rect(left, top, right, bottom)
                candidates += Rect(hostBounds.left + left, hostBounds.top + top, hostBounds.left + right, hostBounds.top + bottom)
            }

            val x = labeled("x")
            val y = labeled("y")
            val width = labeled("width") ?: labeled("w")
            val height = labeled("height") ?: labeled("h")
            if (x != null && y != null && width != null && height != null) {
                candidates += Rect(x, y, x + width, y + height)
                candidates += Rect(hostBounds.left + x, hostBounds.top + y, hostBounds.left + x + width, hostBounds.top + y + height)
            }

            val numbers = Regex("-?\\d+(?:\\.\\d+)?")
                .findAll(value)
                .mapNotNull { it.value.toFloatOrNull()?.toInt() }
                .take(16)
                .toList()
            if (numbers.size >= 4) {
                for (offset in 0..(numbers.size - 4)) {
                    val a = numbers[offset]
                    val b = numbers[offset + 1]
                    val c = numbers[offset + 2]
                    val d = numbers[offset + 3]
                    candidates += Rect(a, b, c, d)
                    candidates += Rect(a, b, a + c, b + d)
                    candidates += Rect(hostBounds.left + a, hostBounds.top + b, hostBounds.left + c, hostBounds.top + d)
                    candidates += Rect(hostBounds.left + a, hostBounds.top + b, hostBounds.left + a + c, hostBounds.top + b + d)
                }
            }

            return candidates.asSequence()
                .filter { !it.isEmpty }
                .filter { it.contains(rawXi, rawYi) }
                .filter { isReasonableVirtualBounds(it, hostBounds) }
                .filter {
                    Rect.intersects(it, hostBounds) ||
                        (it.left >= hostBounds.left && it.right <= hostBounds.right &&
                            it.top >= hostBounds.top && it.bottom <= hostBounds.bottom)
                }
                .distinctBy { boundsToString(it) }
                .sortedWith(compareBy<Rect> { kotlin.math.max(1, it.width()) * kotlin.math.max(1, it.height()) })
                .firstOrNull()
        }

        private fun isUsefulVirtualNode(
            bounds: Rect,
            hostBounds: Rect,
            text: String,
            contentDescription: String,
            viewId: String,
            className: String
        ): Boolean {
            if (bounds.width() <= 0 || bounds.height() <= 0) return false
            if (!isReasonableVirtualBounds(bounds, hostBounds)) return false
            val sameAsHost = bounds == hostBounds
            val named = text.isNotBlank() || contentDescription.isNotBlank() || viewId.isNotBlank()
            if (!sameAsHost && named) return true
            if (!sameAsHost && className.isNotBlank() && bounds.width() < hostBounds.width()) return true
            return false
        }

        private fun isReasonableVirtualBounds(bounds: Rect, hostBounds: Rect): Boolean {
            if (bounds.width() <= 0 || bounds.height() <= 0) return false
            if (hostBounds.width() <= 0 || hostBounds.height() <= 0) return false
            val hostWidth = kotlin.math.max(1, hostBounds.width()).toLong()
            val hostHeight = kotlin.math.max(1, hostBounds.height()).toLong()
            val boundsWidth = bounds.width().toLong()
            val boundsHeight = bounds.height().toLong()
            val maxWidth = kotlin.math.max(hostWidth * 3L, 4096L)
            val maxHeight = kotlin.math.max(hostHeight * 3L, 4096L)
            if (boundsWidth > maxWidth || boundsHeight > maxHeight) return false
            val leftLimit = hostBounds.left.toLong() - hostWidth
            val rightLimit = hostBounds.right.toLong() + hostWidth
            val topLimit = hostBounds.top.toLong() - hostHeight
            val bottomLimit = hostBounds.bottom.toLong() + hostHeight
            return bounds.right.toLong() >= leftLimit &&
                bounds.left.toLong() <= rightLimit &&
                bounds.bottom.toLong() >= topLimit &&
                bounds.top.toLong() <= bottomLimit
        }

        private fun virtualNodeArea(bounds: Rect): Long {
            return kotlin.math.max(1, bounds.width()).toLong() *
                kotlin.math.max(1, bounds.height()).toLong()
        }

        private fun buildSnapchatVirtualSelector(
            source: String,
            hostId: String?,
            hostSelector: String,
            hostClass: String,
            path: String,
            bounds: String,
            localBounds: String,
            className: String,
            text: String,
            contentDescription: String,
            viewId: String
        ): String {
            if (hostId.isNullOrBlank() && hostSelector.isBlank()) return ""
            return SNAPCHAT_VIRTUAL_SELECTOR_PREFIX +
                listOf(
                    "source=${escapeVirtualSelectorValue(source)}",
                    "host_id=${escapeVirtualSelectorValue(hostId.orEmpty())}",
                    "host_selector=${escapeVirtualSelectorValue(hostSelector)}",
                    "host_class=${escapeVirtualSelectorValue(hostClass)}",
                    "path=${escapeVirtualSelectorValue(path)}",
                    "bounds=${escapeVirtualSelectorValue(bounds)}",
                    "local_bounds=${escapeVirtualSelectorValue(localBounds)}",
                    "class=${escapeVirtualSelectorValue(className)}",
                    "text=${escapeVirtualSelectorValue(text)}",
                    "desc=${escapeVirtualSelectorValue(contentDescription)}",
                    "view_id=${escapeVirtualSelectorValue(viewId)}"
                ).joinToString("|")
        }

        private fun escapeVirtualSelectorValue(value: String): String {
            return value
                .replace("%", "%25")
                .replace("|", "%7C")
                .replace("=", "%3D")
                .replace("\n", "%0A")
        }

        private fun virtualDisplayName(
            text: String,
            contentDescription: String,
            viewId: String,
            className: String,
            bounds: Rect
        ): String {
            val raw = text.ifBlank { contentDescription }
                .ifBlank { viewId.substringAfterLast('/').ifBlank { className.substringAfterLast('.') } }
                .ifBlank { "Virtual surface" }
            return "Virtual ${friendlyLabel(raw)}\n${boundsToString(bounds)}"
        }

        private fun collectViewsAt(
            root: View,
            view: View?,
            rawX: Float,
            rawY: Float,
            state: OverlayState,
            depth: Int,
            out: MutableList<ViewCandidate>
        ) {
            view ?: return
            if (view === root) {
                (view as? ViewGroup)?.let { collectChildren(root, it, rawX, rawY, state, depth, out) }
                return
            }
            if (view === state.button || view === state.pickerOverlay || isOverlayView(view)) return
            if (view.visibility != View.VISIBLE || view.alpha <= 0.01f) return
            if (!isPointInside(view, rawX, rawY)) return

            (view as? ViewGroup)?.let { collectChildren(root, it, rawX, rawY, state, depth, out) }
            if (isUsefulSelectionCandidate(view)) {
                out += ViewCandidate(view, depth)
            }
        }

        private fun collectChildren(
            root: View,
            group: ViewGroup,
            rawX: Float,
            rawY: Float,
            state: OverlayState,
            depth: Int,
            out: MutableList<ViewCandidate>
        ) {
            for (i in group.childCount - 1 downTo 0) {
                collectViewsAt(root, group.getChildAt(i), rawX, rawY, state, depth + 1, out)
            }
        }

        private fun isUsefulSelectionCandidate(view: View): Boolean {
            if (view.width <= 0 || view.height <= 0) return false
            val className = view.javaClass.name
            return !className.contains("Guideline") &&
                !className.contains("Barrier") &&
                !className.contains("ViewStub") &&
                !className.endsWith(".Space")
        }

        private fun isPointInside(view: View, rawX: Float, rawY: Float): Boolean {
            val location = IntArray(2)
            view.getLocationOnScreen(location)
            return view.width > 0 &&
                view.height > 0 &&
                rawX >= location[0] &&
                rawX <= location[0] + view.width &&
                rawY >= location[1] &&
                rawY <= location[1] + view.height
        }

        private fun buildThemeSurfaceCandidates(
            root: View,
            selection: SelectionTarget,
            fallbackRawValue: String,
            fallbackLabel: String
        ): List<ThemeSurfaceCandidate> {
            val out = linkedMapOf<String, ThemeSurfaceCandidate>()

            fun add(candidate: ThemeSurfaceCandidate) {
                if (candidate.rawValue.isBlank()) return
                val geometryKey = candidate.bounds.ifBlank { candidate.rawValue }
                out.putIfAbsent("${candidate.role}|${candidate.previewMode}|$geometryKey", candidate)
            }

            fun addViewCandidate(role: String, view: View, previewMode: ThemeSurfacePreviewMode) {
                val raw = themeRawValueForView(view, root, role)
                if (raw.isBlank()) return
                val surfaceName = themeSurfaceViewName(view)
                add(
                    ThemeSurfaceCandidate(
                        role = role,
                        label = "$role - $surfaceName",
                        target = view,
                        rawValue = raw,
                        previewMode = previewMode,
                        bounds = screenBounds(view),
                        detail = selectionViewSummary(view, root)
                    )
                )
            }

            val rawActualViews = selection.viewCandidates
                .asSequence()
                .map { it.view }
                .distinctBy { System.identityHashCode(it) }
                .filter { it !== root && it.width > 0 && it.height > 0 && it.visibility == View.VISIBLE }
                .take(40)
                .toList()
            val actualViews = filterThemeActualViewsForSelection(root, selection, rawActualViews)
            val scopedViews = if (shouldExpandThemeCandidateScope(root, selection)) {
                collectThemeCandidateScopeViews(root, selection, actualViews)
            } else {
                emptyList()
            }
            val unboundedCandidateViews = (actualViews + scopedViews)
                .asSequence()
                .distinctBy { System.identityHashCode(it) }
                .filter { it !== root && it.width > 0 && it.height > 0 && it.visibility == View.VISIBLE }
                .toList()
            val rowView = findThemeRowCandidateView(root, unboundedCandidateViews)
            if (rowView != null && shouldOfferThemeContainerCandidate(selection, rowView)) {
                addViewCandidate("Row / cell background", rowView, ThemeSurfacePreviewMode.BACKGROUND)
            }
            val candidateViews = constrainThemeCandidateViewsToSelectedSurface(root, selection, unboundedCandidateViews)
            val selectedIsPreciseBadge = selection.virtualNode == null && isThemeBadgeCandidateView(selection.view)
            val selectedIsPreciseAvatar = selection.virtualNode == null && isThemeAvatarCandidateView(selection.view)
            val selectedBadgeSharesControl = selectedIsPreciseBadge &&
                !isThemeBadgeLabelLikeView(selection.view) &&
                candidateViews.any { it !== selection.view && isThemeIconCandidateView(it) && !isThemeBadgeCandidateView(it) }
            val suppressIconAndFillForBadge = selectedIsPreciseBadge && !selectedBadgeSharesControl

            candidateViews
                .asSequence()
                .filter(::isThemeTextCandidateView)
                .sortedWith(compareBy<View> { themeViewArea(it) }.thenByDescending { viewDepthInSelection(selection, it) })
                .take(4)
                .forEach { addViewCandidate("Text color", it, ThemeSurfacePreviewMode.TEXT_COLOR) }

            val avatarViews = candidateViews
                .asSequence()
                .filter(::isThemeAvatarCandidateView)
                .sortedWith(compareBy<View> { themeViewArea(it) }.thenByDescending { viewDepthInSelection(selection, it) })
                .toList()
            val avatarCandidateViews = if (selectedIsPreciseAvatar) {
                avatarViews.take(1)
            } else {
                avatarViews.take(3)
            }
            avatarCandidateViews
                .forEach { addViewCandidate("Avatar / thumbnail", it, ThemeSurfacePreviewMode.BACKGROUND) }

            val badgeViews = candidateViews
                .asSequence()
                .filter(::isThemeBadgeCandidateView)
                .sortedWith(compareBy<View> { themeViewArea(it) }.thenByDescending { viewDepthInSelection(selection, it) })
                .toList()
            val badgeCandidateViews = if (selectedIsPreciseBadge) {
                compactPreciseBadgeCandidates(selection, badgeViews)
            } else {
                badgeViews.take(4)
            }
            badgeCandidateViews
                .forEach { view ->
                    val textLikeBadge = isThemeBadgeLabelLikeView(view)
                    addViewCandidate(
                        if (textLikeBadge) "Badge text" else "Badge / status fill",
                        view,
                        if (textLikeBadge) ThemeSurfacePreviewMode.TEXT_COLOR else ThemeSurfacePreviewMode.BACKGROUND
                    )
                }

            val iconView = candidateViews
                .asSequence()
                .filter(::isThemeIconCandidateView)
                .sortedWith(compareBy<View> { themeViewArea(it) }.thenByDescending { viewDepthInSelection(selection, it) })
                .firstOrNull()
            val virtualFillView = findVirtualThemeFillCandidateView(root, selection, candidateViews)
            val fillView = if (suppressIconAndFillForBadge || selectedIsPreciseAvatar) {
                null
            } else {
                iconView?.let { findThemeFillCandidateView(candidateViews, it) }
                    ?: virtualFillView
                    ?: candidateViews.firstOrNull { it === selection.view && isThemeFillCandidateView(it) }
                    ?: candidateViews
                        .asSequence()
                        .filter(::isThemeButtonCandidateView)
                        .sortedWith(compareBy<View> { themeViewArea(it) }.thenByDescending { viewDepthInSelection(selection, it) })
                        .firstOrNull()
            }

            if (fillView != null && fillView !== rowView) addViewCandidate("Button fill", fillView, ThemeSurfacePreviewMode.BACKGROUND)
            if (!suppressIconAndFillForBadge && !selectedIsPreciseAvatar) {
                if (iconView != null) addViewCandidate("Icon / outline", iconView, ThemeSurfacePreviewMode.IMAGE_TINT)
                candidateViews
                    .asSequence()
                    .filter(::isThemeIconCandidateView)
                    .filter { it !== iconView }
                    .sortedWith(compareBy<View> { themeViewArea(it) }.thenByDescending { viewDepthInSelection(selection, it) })
                    .take(3)
                    .forEach { addViewCandidate("Icon / outline", it, ThemeSurfacePreviewMode.IMAGE_TINT) }
            }

            val actualSurfaceCandidates = out.values.toList()
            val virtualRoleCandidates = when {
                selection.virtualNode != null -> listOf(selection.virtualNode)
                else -> selection.virtualCandidates.take(8)
            }
            virtualRoleCandidates
                .asSequence()
                .take(8)
                .flatMapIndexed { index, virtual ->
                    val roles = virtualThemeCandidateRoles(virtual, exact = index == 0)
                    val suppressionReason = themeVirtualCandidateSuppressionReason(
                        root = root,
                        virtual = virtual,
                        roles = roles,
                        actualCandidates = actualSurfaceCandidates
                    )
                    if (suppressionReason != null) {
                        log(
                            "Theme virtual candidate suppressed reason=$suppressionReason exact=${index == 0} " +
                                "roles=${roles.joinToString(",")} virtual=${virtualNodeSummary(virtual)} " +
                                "actual=${actualSurfaceCandidates.joinToString(" || ") { candidate ->
                                    "role=${candidate.role} label=${candidate.label} bounds=${candidate.bounds} " +
                                        "mode=${candidate.previewMode} detail=${candidate.detail.take(360)}"
                                }.ifBlank { "none" }}"
                        )
                        emptySequence()
                    } else {
                        roles.asSequence().map { role -> role to virtual }
                    }
                }
                .distinctBy { (role, virtual) -> "$role|${virtualThemeCandidateKey(virtual, role)}" }
                .take(8)
                .forEach { (role, virtual) ->
                        add(
                            ThemeSurfaceCandidate(
                                role = role,
                                label = "$role - ${virtualThemeDisplayName(virtual).replace('\n', ' ')}",
                                target = virtual.hostView,
                                rawValue = virtual.selector,
                                previewMode = virtualThemePreviewModeForRole(role),
                                bounds = virtual.bounds,
                                detail = virtualNodeSummary(virtual)
                            )
                        )
                }

            if (out.isEmpty() && fallbackRawValue.isNotBlank()) {
                add(
                    ThemeSurfaceCandidate(
                        role = "Selected surface",
                        label = fallbackLabel,
                        target = selection.view,
                        rawValue = fallbackRawValue,
                        previewMode = if (selection.view is ImageView) {
                            ThemeSurfacePreviewMode.IMAGE_TINT
                        } else {
                            ThemeSurfacePreviewMode.AUTO
                        },
                        bounds = selection.virtualNode?.bounds ?: screenBounds(selection.view),
                        detail = selectionTargetSummary(selection, root)
                    )
                )
            }

            val candidates = out.values.toList()
            log(
                "Theme granular candidates count=${candidates.size} selected=${selectionTargetSummary(selection, root)} " +
                    "items=${candidates.joinToString(" || ") { candidate ->
                        "role=${candidate.role} label=${candidate.label} mode=${candidate.previewMode} " +
                            "bounds=${candidate.bounds} raw=${candidate.rawValue.take(420)} detail=${candidate.detail.take(520)}"
                    }.ifBlank { "none" }} rawActualViews=${rawActualViews.size} actualViews=${actualViews.size} " +
                    "scopedViews=${scopedViews.size} " +
                    "unboundedCandidateViews=${unboundedCandidateViews.size} " +
                    "candidateViews=${candidateViews.size} row=${rowView?.let { selectionViewSummary(it, root) } ?: "none"}"
            )
            return candidates
        }

        private fun themeVirtualCandidateSuppressionReason(
            root: View,
            virtual: VirtualNodeTarget,
            roles: List<String>,
            actualCandidates: List<ThemeSurfaceCandidate>
        ): String? {
            if (actualCandidates.isEmpty()) return null
            val classLower = virtual.className.lowercase(Locale.US)
            val text = cleanVirtualThemeValue(virtual.text)
            val desc = cleanVirtualThemeValue(virtual.contentDescription)
            val visibleText = text.ifBlank { desc }
            val displayLower = virtual.displayName.lowercase(Locale.US)
            val signal = virtualThemeCandidateSignal(virtual)
            val virtualBounds = parseVirtualBounds(virtual.bounds)
            val relatedActual = actualCandidates.filter { candidate ->
                val candidateBounds = parseVirtualBounds(candidate.bounds)
                candidateBounds == null ||
                    virtualBounds == null ||
                    themeRectsTouchSameSurface(virtualBounds, candidateBounds)
            }
            if (relatedActual.isEmpty()) return null

            val preciseActual = relatedActual.filter { candidate ->
                candidate.role == "Text color" ||
                    candidate.role == "Badge text" ||
                    candidate.role == "Badge / status fill" ||
                    candidate.role == "Avatar / thumbnail" ||
                    candidate.role == "Button fill" ||
                    candidate.role == "Icon / outline"
            }
            val hasPrecisePart = preciseActual.any { candidate ->
                candidate.role == "Text color" ||
                    candidate.role == "Badge text" ||
                    candidate.role == "Badge / status fill" ||
                    candidate.role == "Avatar / thumbnail" ||
                    candidate.role == "Icon / outline"
            }
            val layoutLikeEmpty = visibleText.isBlank() && (
                "layout" in classLower ||
                    "layout" in displayLower ||
                    "layout" in signal ||
                    "viewgroup" in classLower ||
                    "viewgroup" in displayLower ||
                    "viewgroup" in signal ||
                    "container" in signal ||
                    classLower.endsWith(".view") ||
                    classLower == "android.view.view"
                )
            val imageLike = "imageview" in classLower
            val virtualAreaOnly = roles.isNotEmpty() && roles.all { role ->
                role == "Virtual exact area" || role == "Virtual nested area"
            }

            if (imageLike && relatedActual.any { it.role == "Button fill" || it.role == "Icon / outline" }) {
                return "actual_image_part_covers_virtual_imageview"
            }
            if (layoutLikeEmpty && hasPrecisePart) {
                return "actual_precise_part_covers_empty_layout"
            }
            if (layoutLikeEmpty && virtualAreaOnly && preciseActual.isNotEmpty()) {
                return "actual_surface_covers_empty_virtual_area"
            }

            val textRoleOnly = roles.isNotEmpty() && roles.all { role ->
                role == "Text color" || role == "Badge text"
            }
            if (visibleText.isNotBlank() && textRoleOnly) {
                val duplicate = relatedActual.firstOrNull { candidate ->
                    (candidate.role == "Text color" || candidate.role == "Badge text") &&
                        themeActualCandidateText(candidate).equals(visibleText, ignoreCase = false)
                }
                if (duplicate != null) {
                    return "actual_text_candidate_duplicates_virtual_text:${duplicate.label}"
                }
            }

            if ((layoutLikeEmpty || imageLike) && virtualAreaOnly && virtualBounds != null) {
                val covering = preciseActual.firstOrNull { candidate ->
                    val candidateBounds = parseVirtualBounds(candidate.bounds) ?: return@firstOrNull false
                    val virtualArea = virtualNodeArea(virtualBounds).coerceAtLeast(1L)
                    val actualArea = virtualNodeArea(candidateBounds).coerceAtLeast(1L)
                    themeRectsTouchSameSurface(virtualBounds, candidateBounds) &&
                        actualArea <= virtualArea * 3L
                }
                if (covering != null) {
                    return "actual_candidate_nearer_than_virtual_area:${covering.role}:${covering.label}"
                }
            }

            if (virtualBounds != null) {
                log(
                    "Theme virtual candidate retained roles=${roles.joinToString(",")} " +
                        "virtual=${virtualNodeSummary(virtual)} " +
                        "relatedActual=${relatedActual.joinToString(" || ") { candidate ->
                            "role=${candidate.role} label=${candidate.label} bounds=${candidate.bounds} " +
                                "text=${themeActualCandidateText(candidate).ifBlank { "none" }}"
                        }.ifBlank { "none" }} root=${selectionViewSummary(root, root)}"
                )
            }
            return null
        }

        private fun themeActualCandidateText(candidate: ThemeSurfaceCandidate): String {
            val textView = candidate.target as? TextView ?: return ""
            val text = textView.text?.toString()?.trim().orEmpty()
            val desc = textView.contentDescription?.toString()?.trim().orEmpty()
            return text.ifBlank { desc }
        }

        private fun themeRectsTouchSameSurface(first: Rect, second: Rect): Boolean {
            if (rectContainsOrCenters(first, second) || rectContainsOrCenters(second, first)) return true
            val left = kotlin.math.max(first.left, second.left)
            val top = kotlin.math.max(first.top, second.top)
            val right = kotlin.math.min(first.right, second.right)
            val bottom = kotlin.math.min(first.bottom, second.bottom)
            if (right <= left || bottom <= top) return false
            val overlapArea = (right - left).toLong() * (bottom - top).toLong()
            val smallerArea = kotlin.math.min(virtualNodeArea(first), virtualNodeArea(second)).coerceAtLeast(1L)
            return overlapArea * 5L >= smallerArea
        }

        private fun constrainThemeCandidateViewsToSelectedSurface(
            root: View,
            selection: SelectionTarget,
            candidateViews: List<View>
        ): List<View> {
            if (selection.virtualNode != null) return constrainThemeCandidateViewsToVirtualSurface(root, selection, candidateViews)
            if (candidateViews.size <= 2) return candidateViews
            val selected = selection.view
            if (selected is TextView) return candidateViews
            if (isThemeIconCandidateView(selected) || isThemeAvatarCandidateView(selected) || isThemeBadgeCandidateView(selected)) {
                return candidateViews
            }
            val selectedBounds = boundsRect(selected).takeIf { !it.isEmpty } ?: return candidateViews
            val rootWidth = root.width.takeIf { it > 0 } ?: selected.resources.displayMetrics.widthPixels
            val rootHeight = root.height.takeIf { it > 0 } ?: selected.resources.displayMetrics.heightPixels
            val compactSurface = selected is ViewGroup &&
                selected.width in dp(selected.context, 32)..rootWidth &&
                selected.height in dp(selected.context, 24)..dp(selected.context, 360)
            val pageSized = selected.width >= (rootWidth * 0.94f).toInt() &&
                selected.height >= (rootHeight * 0.35f).toInt()
            if (!compactSurface || pageSized) return candidateViews

            val selectedArea = themeViewArea(selected)
            val kept = candidateViews.filter { view ->
                if (view === selected) return@filter true
                if (isViewAncestorOf(selected, view)) return@filter true
                val bounds = boundsRect(view)
                if (bounds.isEmpty) return@filter false
                if (themeViewArea(view) > selectedArea * 3) return@filter false
                rectContainsOrCenters(selectedBounds, bounds)
            }
            if (kept.isNotEmpty() && kept.size != candidateViews.size) {
                log(
                    "Theme selected surface constrained selected=${selectionTargetSummary(selection, root)} " +
                        "raw=${candidateViews.size} kept=${kept.size} dropped=${candidateViews.size - kept.size} " +
                        "selectedBounds=${formatRect(selectedBounds)} keptItems=${kept.joinToString(" || ") { selectionViewSummary(it, root).take(420) }}"
                )
                return kept
            }
            return candidateViews
        }

        private fun constrainThemeCandidateViewsToVirtualSurface(
            root: View,
            selection: SelectionTarget,
            candidateViews: List<View>
        ): List<View> {
            if (candidateViews.size <= 2) return candidateViews
            val virtual = selection.virtualNode ?: return candidateViews
            val virtualBounds = parseVirtualBounds(virtual.bounds)?.takeIf { !it.isEmpty } ?: return candidateViews
            val context = root.context ?: selection.view.context
            val rootWidth = root.width.takeIf { it > 0 } ?: context.resources.displayMetrics.widthPixels
            val rootHeight = root.height.takeIf { it > 0 } ?: context.resources.displayMetrics.heightPixels
            val virtualArea = virtualNodeArea(virtualBounds)
            val maxArea = kotlin.math.max(virtualArea * 40L, dp(context, 320).toLong() * dp(context, 240).toLong())
            val maxHeight = kotlin.math.max(virtualBounds.height() * 8, dp(context, 520))
            val kept = candidateViews.filter { view ->
                val bounds = boundsRect(view)
                if (bounds.isEmpty) return@filter false
                val pageSized = bounds.width() >= (rootWidth * 0.94f).toInt() &&
                    bounds.height() >= (rootHeight * 0.35f).toInt()
                if (pageSized) return@filter false
                val relatedToVirtualBounds = rectContainsOrCenters(bounds, virtualBounds) ||
                    rectContainsOrCenters(virtualBounds, bounds)
                if (!relatedToVirtualBounds) return@filter false
                val area = virtualNodeArea(bounds)
                val isUsefulThemePart = view is TextView ||
                    isThemeFillCandidateView(view) ||
                    isThemeButtonCandidateView(view) ||
                    isThemeIconCandidateView(view) ||
                    isThemeBadgeCandidateView(view) ||
                    isThemeAvatarCandidateView(view)
                isUsefulThemePart &&
                    area <= maxArea &&
                    bounds.height() <= maxHeight &&
                    bounds.width() <= rootWidth + dp(context, 16)
            }
            if (kept.isNotEmpty() && kept.size != candidateViews.size) {
                log(
                    "Theme virtual surface constrained selected=${selectionTargetSummary(selection, root)} " +
                        "virtualBounds=${formatRect(virtualBounds)} raw=${candidateViews.size} kept=${kept.size} " +
                        "dropped=${candidateViews.size - kept.size} maxArea=$maxArea maxHeight=$maxHeight " +
                        "keptItems=${kept.joinToString(" || ") { selectionViewSummary(it, root).take(420) }}"
                )
                return kept
            }
            return candidateViews
        }

        private fun filterThemeActualViewsForSelection(
            root: View,
            selection: SelectionTarget,
            actualViews: List<View>
        ): List<View> {
            if (selection.virtualNode != null || actualViews.size <= 2) return actualViews
            val selected = selection.view
            if (!shouldUsePreciseThemeCluster(selected)) return actualViews
            val selectedBounds = boundsRect(selected).takeIf { !it.isEmpty } ?: return actualViews
            val selectedArea = themeViewArea(selected)
            val selectedBadgeControlHost = if (isThemeBadgeCandidateView(selected) && !isThemeBadgeLabelLikeView(selected)) {
                nearestThemeControlHost(selected)
            } else {
                null
            }
            val filtered = actualViews.filter { view ->
                if (view === selected) return@filter true
                if (isViewAncestorOf(view, selected) || isViewAncestorOf(selected, view)) return@filter true
                if (selectedBadgeControlHost != null &&
                    (view === selectedBadgeControlHost || isViewAncestorOf(selectedBadgeControlHost, view)) &&
                    (isThemeIconCandidateView(view) || isThemeButtonCandidateView(view) || isThemeBadgeCandidateView(view))
                ) {
                    return@filter true
                }
                val bounds = boundsRect(view)
                if (bounds.isEmpty) return@filter false
                val area = kotlin.math.max(1, bounds.width()) * kotlin.math.max(1, bounds.height())
                rectContainsOrCenters(bounds, selectedBounds) &&
                    area <= selectedArea * 24 &&
                    (isThemeFillCandidateView(view) || isThemeButtonCandidateView(view))
            }
            if (filtered.isNotEmpty() && filtered.size != actualViews.size) {
                log(
                    "Theme precise cluster filtered root=${selectionViewSummary(root, root)} " +
                        "selected=${selectionTargetSummary(selection, root)} raw=${actualViews.size} kept=${filtered.size} " +
                        "dropped=${actualViews.size - filtered.size} " +
                        "badgeHost=${selectedBadgeControlHost?.let { selectionViewSummary(it, root) } ?: "none"} " +
                        "keptItems=${filtered.take(10).joinToString(" || ") { selectionViewSummary(it, root) }}"
                )
            }
            return filtered.ifEmpty { actualViews.take(2) }
        }

        private fun shouldUsePreciseThemeCluster(view: View): Boolean {
            if (isThemeIconCandidateView(view) || isThemeAvatarCandidateView(view) || isThemeBadgeCandidateView(view)) return true
            val compactButton = view.width in dp(view.context, 32)..dp(view.context, 420) &&
                view.height in dp(view.context, 28)..dp(view.context, 240)
            return compactButton && isThemeButtonCandidateView(view)
        }

        private fun nearestThemeControlHost(view: View): View? {
            var parent = view.parent
            while (parent is View) {
                if (isThemeBadgeCandidateView(parent) && !parent.isClickable && !parent.isFocusable) {
                    parent = parent.parent
                    continue
                }
                val signal = themeCandidateSignal(parent)
                val resourceName = getResourceEntryName(parent).orEmpty().lowercase(Locale.US)
                if (parent.isClickable ||
                    parent.isFocusable ||
                    "icon_container" in resourceName ||
                    listOf("button", "container", "pill").any { it in signal }
                ) {
                    return parent
                }
                parent = parent.parent
            }
            return null
        }

        private fun compactPreciseBadgeCandidates(
            selection: SelectionTarget,
            badgeViews: List<View>
        ): List<View> {
            if (badgeViews.isEmpty()) return emptyList()
            val selected = selection.view
            val selectedBounds = boundsRect(selected).takeIf { !it.isEmpty }
            val related = badgeViews.filter { view ->
                if (view === selected) return@filter true
                if (isViewAncestorOf(view, selected) || isViewAncestorOf(selected, view)) return@filter true
                val bounds = boundsRect(view)
                selectedBounds != null &&
                    !bounds.isEmpty &&
                    (rectContainsOrCenters(bounds, selectedBounds) || rectContainsOrCenters(selectedBounds, bounds))
            }.ifEmpty { badgeViews.take(1) }
            val textCandidate = related.firstOrNull(::isThemeBadgeLabelLikeView)
            val fillCandidate = related.firstOrNull { it !is TextView && !isThemeBadgeLabelLikeView(it) }
                ?: related.firstOrNull { it !is TextView }
                ?: related.firstOrNull()
            return listOfNotNull(textCandidate, fillCandidate)
                .distinctBy { System.identityHashCode(it) }
                .ifEmpty { related.take(1) }
        }

        private fun collectThemeCandidateScopeViews(
            root: View,
            selection: SelectionTarget,
            actualViews: List<View>
        ): List<View> {
            val scopeHosts = actualViews
                .asSequence()
                .filter { isThemeCandidateScopeHost(root, it) }
                .sortedWith(compareBy<View> { themeViewArea(it) }.thenByDescending { viewDepthInSelection(selection, it) })
                .take(3)
                .toList()
            if (scopeHosts.isEmpty()) return emptyList()
            val out = linkedSetOf<View>()
            scopeHosts.forEach { host ->
                out += host
                collectThemeDescendantCandidates(host, out)
            }
            return out.toList()
        }

        private fun collectThemeDescendantCandidates(host: View, out: MutableSet<View>, maxDepth: Int = 4, maxViews: Int = 96) {
            var visited = 0
            fun walk(view: View, depth: Int) {
                if (visited++ >= maxViews || depth > maxDepth) return
                if (view !== host && isUsefulSelectionCandidate(view)) out += view
                val group = view as? ViewGroup ?: return
                for (index in 0 until group.childCount) {
                    if (visited >= maxViews) break
                    walk(group.getChildAt(index), depth + 1)
                }
            }
            walk(host, 0)
        }

        private fun shouldExpandThemeCandidateScope(root: View, selection: SelectionTarget): Boolean {
            if (selection.virtualNode != null) return false
            val view = selection.view
            if (view is TextView) return false
            if (isThemeIconCandidateView(view) || isThemeAvatarCandidateView(view) || isThemeBadgeCandidateView(view)) return false
            val rootWidth = root.width.takeIf { it > 0 } ?: view.resources.displayMetrics.widthPixels
            val rootHeight = root.height.takeIf { it > 0 } ?: view.resources.displayMetrics.heightPixels
            val rowSized = view.width >= (rootWidth * 0.55f).toInt() &&
                view.height in dp(view.context, 36)..dp(view.context, 360)
            val pageSized = view.width >= (rootWidth * 0.94f).toInt() &&
                view.height >= (rootHeight * 0.50f).toInt()
            val signal = themeCandidateSignal(view)
            return rowSized && !pageSized &&
                (view is ViewGroup || view.background != null || listOf("ff_item", "row", "cell", "item", "list").any { it in signal })
        }

        private fun shouldOfferThemeContainerCandidate(selection: SelectionTarget, rowView: View): Boolean {
            if (selection.virtualNode != null) return false
            val view = selection.view
            if (view === rowView) return true
            if (view is TextView) return false
            if (isThemeIconCandidateView(view) || isThemeAvatarCandidateView(view) || isThemeBadgeCandidateView(view)) return false
            return false
        }

        private fun isThemeCandidateScopeHost(root: View, view: View): Boolean {
            if (view === root || view.width <= 0 || view.height <= 0) return false
            val rootWidth = root.width.takeIf { it > 0 } ?: view.resources.displayMetrics.widthPixels
            val rootHeight = root.height.takeIf { it > 0 } ?: view.resources.displayMetrics.heightPixels
            if (view.width >= (rootWidth * 0.94f).toInt() && view.height >= (rootHeight * 0.50f).toInt()) return false
            val name = themeCandidateSignal(view)
            val rowSized = view.width >= (rootWidth * 0.55f).toInt() && view.height in dp(view.context, 36)..dp(view.context, 360)
            val chromeSized = view.width in dp(view.context, 36)..dp(view.context, 520) && view.height in dp(view.context, 36)..dp(view.context, 220)
            return rowSized ||
                (chromeSized && (view.isClickable || view.isFocusable || "button" in name || "container" in name || "pill" in name)) ||
                listOf("ff_item", "list-picker-pill", "ngs_", "header", "button", "container", "cell", "row", "item").any { it in name }
        }

        private fun findThemeRowCandidateView(root: View, views: List<View>): View? {
            val rootWidth = root.width.takeIf { it > 0 } ?: root.resources.displayMetrics.widthPixels
            return views
                .asSequence()
                .filter { view ->
                    if (view.width < (rootWidth * 0.55f).toInt()) return@filter false
                    if (view.height !in dp(view.context, 48)..dp(view.context, 360)) return@filter false
                    val signal = themeCandidateSignal(view)
                    view.isClickable || view.isFocusable || view.background != null ||
                        listOf("ff_item", "row", "cell", "item", "list", "chat").any { it in signal }
                }
                .sortedWith(compareBy<View> { themeViewArea(it) }.thenByDescending { themeCandidateSignal(it).contains("ff_item") })
                .firstOrNull()
        }

        private fun showThemeSurfaceCandidateChooser(activity: Activity, candidates: List<ThemeSurfaceCandidate>) {
            if (candidates.isEmpty()) return
            if (candidates.size == 1) {
                val candidate = candidates.first()
                handleCapturedThemeSurface(
                    activity = activity,
                    target = candidate.target,
                    rawValue = candidate.rawValue,
                    label = candidate.label,
                    previewMode = candidate.previewMode,
                    role = candidate.role,
                    bounds = candidate.bounds,
                    detail = candidate.detail
                )
                return
            }
            val labels = candidates.map { candidate ->
                buildString {
                    append(candidate.role)
                    append('\n')
                    append(candidate.label.removePrefix("${candidate.role} - "))
                    append('\n')
                    append(candidate.bounds)
                }
            }.toTypedArray()
            log(
                "Theme granular chooser opening count=${candidates.size} " +
                    "items=${candidates.joinToString(" || ") { "${it.role}:${it.rawValue.take(260)}" }}"
            )
            AlertDialog.Builder(activity)
                .setTitle("Choose part to customize")
                .setItems(labels) { _, which ->
                    val candidate = candidates.getOrNull(which) ?: return@setItems
                    log(
                        "Theme granular chooser selected index=$which role=${candidate.role} " +
                            "mode=${candidate.previewMode} label=${candidate.label} raw=${candidate.rawValue.take(700)}"
                    )
                    handleCapturedThemeSurface(
                        activity = activity,
                        target = candidate.target,
                        rawValue = candidate.rawValue,
                        label = candidate.label,
                        previewMode = candidate.previewMode,
                        role = candidate.role,
                        bounds = candidate.bounds,
                        detail = candidate.detail
                    )
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        private fun themeRawValueForView(view: View, root: View, role: String? = null): String {
            val selector = runCatching { WhatsAppUiElementSelector.build(view, root) }.getOrNull().orEmpty()
            if (selector.isNotBlank()) return selector
            val scopedSelector = buildThemeSelfScopedSelector(view, root, role)
            return scopedSelector.ifBlank { getResourceEntryName(view).orEmpty() }
        }

        private fun buildThemeSelfScopedSelector(view: View, root: View, role: String?): String {
            if (view === root || view.width <= 0 || view.height <= 0) return ""
            val anchor = findNearestNamedAncestorAbove(view, root) ?: return ""
            val anchorId = getResourceEntryName(anchor).orEmpty()
            if (anchorId.isEmpty()) return ""

            val indexes = mutableListOf<Int>()
            val classes = mutableListOf<String>()
            var current: View? = view
            while (current != null && current !== anchor) {
                val parent = current.parent as? ViewGroup ?: return ""
                val index = parent.indexOfChild(current)
                if (index < 0) return ""
                indexes += index
                classes += current.javaClass.name
                current = parent
            }
            if (current !== anchor || indexes.isEmpty()) return ""

            val raw = "selector:v1|" +
                "anchor=${escapeThemeSelectorValue(anchorId)}" +
                "|indexes=${indexes.asReversed().joinToString("/")}" +
                "|classes=${escapeThemeSelectorValue(classes.asReversed().joinToString("/"))}" +
                "|scope=theme_self" +
                "|role=${escapeThemeSelectorValue(role.orEmpty())}" +
                "|bounds=${escapeThemeSelectorValue(screenBounds(view))}"
            log(
                "Theme scoped selector generated role=${role ?: "none"} " +
                    "target=${selectionViewSummary(view, root)} anchor=${selectionViewSummary(anchor, root)} raw=$raw"
            )
            return raw
        }

        private fun findNearestNamedAncestorAbove(view: View, root: View): ViewGroup? {
            var current = view.parent as? View
            while (current != null) {
                if (getResourceEntryName(current) != null && current is ViewGroup) return current
                if (current === root) break
                current = current.parent as? View
            }
            return null
        }

        private fun escapeThemeSelectorValue(value: String): String {
            return value
                .replace("%", "%25")
                .replace("|", "%7C")
                .replace("=", "%3D")
                .replace("\n", "%0A")
        }

        private fun themeSurfaceViewName(view: View): String {
            val text = (view as? TextView)?.text?.toString().orEmpty().trim()
            val contentDescription = view.contentDescription?.toString().orEmpty().trim()
            val id = getResourceEntryName(view).orEmpty()
            val className = view.javaClass.simpleName.ifBlank { view.javaClass.name.substringAfterLast('.') }
            return friendlyLabel(
                text.ifBlank { contentDescription }
                    .ifBlank { id }
                    .ifBlank { className }
            )
        }

        private fun themeCandidateSignal(view: View): String {
            val parent = view.parent as? View
            return listOf(
                view.javaClass.name,
                view.javaClass.simpleName,
                getResourceEntryName(view).orEmpty(),
                view.contentDescription?.toString().orEmpty(),
                (view as? TextView)?.text?.toString().orEmpty(),
                parent?.javaClass?.name.orEmpty(),
                parent?.javaClass?.simpleName.orEmpty(),
                getResourceEntryName(parent).orEmpty(),
                parent?.contentDescription?.toString().orEmpty()
            )
                .joinToString(" ")
                .lowercase(Locale.US)
        }

        private fun virtualThemeCandidateSignal(virtual: VirtualNodeTarget): String {
            return listOf(
                virtual.displayName,
                virtual.className,
                virtual.text,
                virtual.contentDescription,
                virtual.viewIdResourceName,
                virtual.path,
                virtual.selector
            )
                .joinToString(" ")
                .lowercase(Locale.US)
        }

        private fun virtualThemeDisplayName(virtual: VirtualNodeTarget): String {
            val signal = virtualThemeCandidateSignal(virtual)
            val bounds = parseVirtualBounds(virtual.bounds)
            val width = bounds?.width() ?: 0
            val height = bounds?.height() ?: 0
            val small = width in 1..120 && height in 1..120
            val raw = when {
                virtual.text != "none" -> virtual.text
                virtual.contentDescription != "none" -> virtual.contentDescription
                virtual.viewIdResourceName != "none" -> virtual.viewIdResourceName.substringAfterLast('/')
                small && "badge" in signal -> "Badge exact area"
                small && listOf("notification", "status", "unread", "count").any { it in signal } -> "Status badge area"
                small && listOf("icon", "imageview").any { it in signal } -> "Icon exact area"
                "button" in signal || "pill" in signal -> "Button exact area"
                else -> virtual.displayName
                    .lineSequence()
                    .firstOrNull()
                    ?.removePrefix("Virtual ")
                    .orEmpty()
                    .ifBlank { "Virtual surface" }
            }
            return "Virtual ${friendlyLabel(raw)}\n${virtual.bounds}"
        }

        private fun virtualThemeCandidateKey(virtual: VirtualNodeTarget, role: String): String {
            val semanticName = virtualThemeDisplayName(virtual)
                .lineSequence()
                .firstOrNull()
                .orEmpty()
                .removePrefix("Virtual ")
                .lowercase(Locale.US)
            val bounds = parseVirtualBounds(virtual.bounds)
            val coarseBounds = bounds?.let {
                val left = (it.left / 8) * 8
                val top = (it.top / 8) * 8
                val right = (it.right / 8) * 8
                val bottom = (it.bottom / 8) * 8
                "$left,$top,$right,$bottom"
            }.orEmpty()
            return "$role|$semanticName|$coarseBounds"
        }

        private fun virtualThemeCandidateRoles(virtual: VirtualNodeTarget, exact: Boolean): List<String> {
            val signal = virtualThemeCandidateSignal(virtual)
            val text = cleanVirtualThemeValue(virtual.text)
            val desc = cleanVirtualThemeValue(virtual.contentDescription)
            val visibleText = text.ifBlank { desc }
            val classLower = virtual.className.lowercase(Locale.US)
            val ownSignal = listOf(
                virtual.displayName,
                virtual.className,
                virtual.text,
                virtual.contentDescription,
                virtual.viewIdResourceName
            )
                .joinToString(" ")
                .lowercase(Locale.US)
            val bounds = parseVirtualBounds(virtual.bounds)
            val width = bounds?.width() ?: 0
            val height = bounds?.height() ?: 0
            val small = width in 10..240 && height in 10..180
            val nearSquare = width in 24..220 && height in 24..220 && kotlin.math.abs(width - height) <= 72
            val numericText = visibleText.matches(Regex("\\d\\+?"))
            val resourceIconText = visibleText.matches(Regex("213\\d{6,}"))
            val avatarLike = nearSquare && listOf("avatar", "bitmoji", "profile", "thumbnail", "friendmoji").any { it in signal }
            val buttonLike = listOf(
                "button",
                "pill",
                "reply",
                "add",
                "accept",
                "dismiss",
                "feed_chat_button",
                "camera reply"
            ).any { it in signal }
            val structuralText = "_" in visibleText || visibleText.equals("none", ignoreCase = true)
            val virtualTextClassLike = ("textview" in classLower || "edittext" in classLower) &&
                width >= 48 &&
                height in 10..320 &&
                !nearSquare &&
                !resourceIconText &&
                !avatarLike
            val textLike = visibleText.isNotBlank() &&
                !resourceIconText &&
                !structuralText &&
                !avatarLike &&
                !buttonLike
            val explicitIconLike = "imageview" in classLower ||
                listOf(
                    "icon",
                    "glyph",
                    "chevron",
                    "close",
                    "dismiss",
                    "more",
                    "overflow",
                    "search",
                    "camera",
                    "spotlight",
                    "map",
                    "stories"
                ).any { it in ownSignal }
            val compactNavigationIconLike = nearSquare && listOf(
                "camera reply",
                "feed_chat_button",
                "notification",
                "reply",
                "add friends",
                "dismiss",
                "map",
                "chat",
                "camera",
                "stories",
                "spotlight"
            ).any { it in signal }
            val iconLike = resourceIconText ||
                explicitIconLike ||
                compactNavigationIconLike
            val statusFillLike = resourceIconText ||
                (!textLike && "badge" in signal && small) ||
                (!textLike && !buttonLike && !avatarLike && listOf("status", "received", "unread", "notificationbadge").any { it in signal } && small)
            val textButtonLike = textLike &&
                listOf("button", "pill", "reply", "add", "accept", "dismiss", "feed_chat_button", "camera reply").any { it in signal }

            val roles = linkedSetOf<String>()
            if (textLike) roles += if (numericText) "Badge text" else "Text color"
            if (avatarLike) roles += "Avatar / thumbnail"
            if (statusFillLike) roles += "Badge / status fill"
            if (buttonLike && !avatarLike && (!textLike || textButtonLike)) roles += "Button fill"
            if (iconLike && !avatarLike && !textLike && !virtualTextClassLike) roles += "Icon / outline"
            if (roles.isEmpty()) roles += if (exact) "Virtual exact area" else "Virtual nested area"
            return roles.toList()
        }

        private fun cleanVirtualThemeValue(value: String): String {
            val clean = value.trim()
            return clean.takeUnless { it.equals("none", ignoreCase = true) }.orEmpty()
        }

        private fun isThemeTextCandidateView(view: View): Boolean {
            val text = (view as? TextView) ?: return false
            val value = text.text?.toString()?.trim().orEmpty()
            val desc = text.contentDescription?.toString()?.trim().orEmpty()
            return value.isNotBlank() || desc.isNotBlank()
        }

        private fun isThemeAvatarCandidateView(view: View): Boolean {
            val signal = themeCandidateSignal(view)
            val nearSquare = view.width in dp(view.context, 28)..dp(view.context, 180) &&
                view.height in dp(view.context, 28)..dp(view.context, 180) &&
                kotlin.math.abs(view.width - view.height) <= dp(view.context, 42)
            return nearSquare && listOf(
                "avatar",
                "bitmoji",
                "profile",
                "thumbnail",
                "story sent",
                "friendmoji"
            ).any { it in signal }
        }

        private fun isThemeBadgeCandidateView(view: View): Boolean {
            val signal = themeCandidateSignal(view)
            val small = view.width in 1..dp(view.context, 240) &&
                view.height in 1..dp(view.context, 120)
            val text = (view as? TextView)?.text?.toString().orEmpty().trim()
            val desc = view.contentDescription?.toString().orEmpty().trim()
            val explicitBadge = listOf("badge", "notificationbadge", "count", "unread", "received", "status", "pill").any { it in signal }
            val numericText = text.matches(Regex("\\d\\+?")) || desc.matches(Regex("\\d\\+?"))
            val inferredTinyOverlay = small && isThemeTinyOverlayBadgeView(view)
            if (inferredTinyOverlay) {
                log(
                    "Theme badge inferred tinyOverlay view=${selectionViewSummary(view, view.rootView)} " +
                        "signal=${signal.take(360)} bounds=${screenBounds(view)}"
                )
            }
            return small && (
                explicitBadge ||
                    numericText ||
                    (view is TextView && Regex("\\b\\d\\+?\\b").containsMatchIn(text)) ||
                    inferredTinyOverlay
                )
        }

        private fun isThemeTinyOverlayBadgeView(view: View): Boolean {
            if (view is TextView) return false
            val tiny = view.width in dp(view.context, 8)..dp(view.context, 72) &&
                view.height in dp(view.context, 8)..dp(view.context, 72) &&
                kotlin.math.abs(view.width - view.height) <= dp(view.context, 32)
            if (!tiny) return false
            val ownSignal = listOf(
                view.javaClass.name,
                view.javaClass.simpleName,
                getResourceEntryName(view).orEmpty(),
                view.contentDescription?.toString().orEmpty()
            ).joinToString(" ").lowercase(Locale.US)
            if (listOf("avatar", "bitmoji", "profile", "thumbnail").any { it in ownSignal }) return false
            val viewBounds = boundsRect(view).takeIf { !it.isEmpty } ?: return false
            val viewArea = themeViewArea(view)
            var childOnPath: View = view
            var parent = view.parent
            var depth = 0
            while (parent is View && depth < 4) {
                val parentBounds = boundsRect(parent)
                val parentSignal = themeCandidateSignal(parent)
                val parentCompact = parent.width in dp(view.context, 32)..dp(view.context, 360) &&
                    parent.height in dp(view.context, 32)..dp(view.context, 360)
                val parentContainerLike = parent.isClickable ||
                    parent.isFocusable ||
                    listOf("container", "button", "tab", "nav", "icon", "memory", "memories", "chat", "story", "spotlight", "map", "camera", "community").any { it in parentSignal }
                val siblingVisual = (parent as? ViewGroup)?.let { group ->
                    (0 until group.childCount).any { index ->
                        val child = group.getChildAt(index)
                        child !== childOnPath &&
                            child.visibility == View.VISIBLE &&
                            child.width > 0 &&
                            child.height > 0 &&
                            themeViewArea(child) >= viewArea * 3 &&
                            (child is ImageView ||
                                child is TextView ||
                                child.contentDescription?.toString()?.isNotBlank() == true ||
                                listOf("icon", "image", "label", "text").any { it in themeCandidateSignal(child) })
                    }
                } == true
                val cornerOverlay = !parentBounds.isEmpty &&
                    viewBounds.left >= parentBounds.left + (parentBounds.width() / 2) - dp(view.context, 16) &&
                    viewBounds.top <= parentBounds.top + (parentBounds.height() / 2) + dp(view.context, 16)
                if (parentCompact && parentContainerLike && siblingVisual && cornerOverlay) {
                    log(
                        "Theme tiny overlay badge matched view=${selectionViewSummary(view, view.rootView)} " +
                            "parent=${selectionViewSummary(parent, view.rootView)} depth=$depth " +
                            "viewBounds=${formatRect(viewBounds)} parentBounds=${formatRect(parentBounds)} " +
                            "siblingVisual=$siblingVisual cornerOverlay=$cornerOverlay parentSignal=${parentSignal.take(320)}"
                    )
                    return true
                }
                childOnPath = parent
                parent = parent.parent
                depth++
            }
            return false
        }

        private fun isThemeBadgeLabelLikeView(view: View): Boolean {
            if (view is TextView) return true
            val signal = themeCandidateSignal(view)
            val small = view.width in 1..dp(view.context, 160) &&
                view.height in 1..dp(view.context, 120)
            return small &&
                listOf("label", "text", "count", "number").any { it in signal } &&
                listOf("badge", "notificationbadge", "unread", "received").any { it in signal }
        }

        private fun isThemeButtonCandidateView(view: View): Boolean {
            if (view.width <= 0 || view.height <= 0) return false
            val signal = themeCandidateSignal(view)
            val buttonSized = view.width in dp(view.context, 36)..dp(view.context, 420) &&
                view.height in dp(view.context, 32)..dp(view.context, 180)
            if (view is TextView) {
                val ownSignal = listOf(
                    view.javaClass.name,
                    view.javaClass.simpleName,
                    getResourceEntryName(view).orEmpty(),
                    view.contentDescription?.toString().orEmpty(),
                    view.text?.toString().orEmpty()
                ).joinToString(" ").lowercase(Locale.US)
                return buttonSized && listOf("button", "pill", "add", "accept", "camera reply", "reply", "dismiss").any { it in ownSignal }
            }
            return buttonSized && (
                view.isClickable ||
                    view.isFocusable ||
                    view.background != null ||
                    listOf("button", "add", "accept", "camera reply", "reply", "dismiss", "search", "notification", "more").any { it in signal }
                )
        }

        private fun isThemeIconCandidateView(view: View): Boolean {
            if (view is TextView) return false
            if (isThemeAvatarCandidateView(view) || isThemeBadgeCandidateView(view)) return false
            val className = view.javaClass.name.lowercase(Locale.US)
            val id = getResourceEntryName(view).orEmpty().lowercase(Locale.US)
            val contentDescription = view.contentDescription?.toString().orEmpty().lowercase(Locale.US)
            val signal = themeCandidateSignal(view)
            val small = view.width in dp(view.context, 16)..dp(view.context, 180) &&
                view.height in dp(view.context, 16)..dp(view.context, 180)
            val drawable = view.background?.javaClass?.name.orEmpty().lowercase(Locale.US)
            val imageBacked = view is ImageView || "imageview" in className ||
                (small && "bitmapdrawable" in drawable)
            if (!imageBacked && (view is ViewGroup || view.isClickable || view.isFocusable) && isThemeButtonCandidateView(view)) {
                return false
            }
            return view is ImageView ||
                "imageview" in className ||
                "icon" in id ||
                "icon" in contentDescription ||
                ("add" in contentDescription && view.width <= 180 && view.height <= 180) ||
                (small && "bitmapdrawable" in drawable && listOf("icon", "ngs_", "map", "chat", "camera", "stories", "spotlight").any { it in signal }) ||
                (small && listOf(
                    "search",
                    "notification",
                    "camera",
                    "reply",
                    "chat",
                    "map",
                    "stories",
                    "spotlight",
                    "dismiss",
                    "more",
                    "add friend",
                    "add friends",
                    "feed_chat_button"
                ).any { it in signal })
        }

        private fun isThemeFillCandidateView(view: View): Boolean {
            if (isThemeIconCandidateView(view)) return false
            if (view.width <= 0 || view.height <= 0) return false
            val className = view.javaClass.name.lowercase(Locale.US)
            val id = getResourceEntryName(view).orEmpty().lowercase(Locale.US)
            if (view is TextView) return isThemeButtonCandidateView(view) && (view.background != null || view.isClickable)
            return view.background != null ||
                isThemeButtonCandidateView(view) ||
                view.isClickable ||
                "button" in id ||
                "container" in id ||
                className == "android.view.view" ||
                view is ViewGroup
        }

        private fun findThemeFillCandidateView(views: List<View>, icon: View): View? {
            val iconBounds = boundsRect(icon).takeIf { !it.isEmpty } ?: return null
            val iconArea = themeViewArea(icon)
            val maxWidth = kotlin.math.max(iconBounds.width() * 4, dp(icon.context, 320))
            val maxHeight = kotlin.math.max(iconBounds.height() * 4, dp(icon.context, 320))
            return views
                .asSequence()
                .filter { it !== icon && isThemeFillCandidateView(it) }
                .mapNotNull { view ->
                    val bounds = boundsRect(view)
                    if (bounds.isEmpty) return@mapNotNull null
                    if (!rectContainsOrCenters(bounds, iconBounds)) return@mapNotNull null
                    if (bounds.width() > maxWidth || bounds.height() > maxHeight) return@mapNotNull null
                    val area = kotlin.math.max(1, bounds.width()) * kotlin.math.max(1, bounds.height())
                    if (area < iconArea || area > iconArea * 18) return@mapNotNull null
                    view to themeFillCandidateScore(view, area)
                }
                .sortedWith(compareBy<Pair<View, Int>> { it.second }.thenBy { themeViewArea(it.first) })
                .firstOrNull()
                ?.first
        }

        private fun findVirtualThemeFillCandidateView(root: View, selection: SelectionTarget, views: List<View>): View? {
            val virtual = selection.virtualNode ?: return null
            val virtualBounds = parseVirtualBounds(virtual.bounds)?.takeIf { !it.isEmpty } ?: return null
            val context = root.context ?: selection.view.context
            val rootWidth = root.width.takeIf { it > 0 } ?: context.resources.displayMetrics.widthPixels
            val rootHeight = root.height.takeIf { it > 0 } ?: context.resources.displayMetrics.heightPixels
            val virtualArea = virtualNodeArea(virtualBounds)
            val maxArea = kotlin.math.max(virtualArea * 40L, dp(context, 320).toLong() * dp(context, 240).toLong())
            val maxHeight = kotlin.math.max(virtualBounds.height() * 8, dp(context, 520))
            val chosen = views
                .asSequence()
                .filter { isThemeFillCandidateView(it) || isThemeButtonCandidateView(it) }
                .mapNotNull { view ->
                    val bounds = boundsRect(view)
                    if (bounds.isEmpty) return@mapNotNull null
                    val pageSized = bounds.width() >= (rootWidth * 0.94f).toInt() &&
                        bounds.height() >= (rootHeight * 0.35f).toInt()
                    if (pageSized) return@mapNotNull null
                    if (!rectContainsOrCenters(bounds, virtualBounds)) return@mapNotNull null
                    if (bounds.height() > maxHeight || bounds.width() > rootWidth + dp(context, 16)) return@mapNotNull null
                    val area = virtualNodeArea(bounds)
                    if (area < virtualArea / 12L || area > maxArea) return@mapNotNull null
                    view to virtualThemeFillCandidateScore(view, area, virtualArea)
                }
                .sortedWith(compareBy<Pair<View, Long>> { it.second }.thenBy { themeViewArea(it.first) })
                .firstOrNull()
                ?.first
            if (chosen != null) {
                log(
                    "Theme virtual fill candidate selected virtual=${virtualNodeSummary(virtual)} " +
                        "chosen=${selectionViewSummary(chosen, root)} chosenBounds=${screenBounds(chosen)} " +
                        "virtualBounds=${formatRect(virtualBounds)} candidates=${views.size}"
                )
            }
            return chosen
        }

        private fun virtualThemeFillCandidateScore(view: View, area: Long, virtualArea: Long): Long {
            var score = kotlin.math.abs(area - virtualArea)
            val className = view.javaClass.name.lowercase(Locale.US)
            val id = getResourceEntryName(view).orEmpty().lowercase(Locale.US)
            if (view.background != null) score -= 80_000L
            if (className == "android.view.view") score -= 60_000L
            if ("bar" in id || "field" in id || "input" in id || "recipient" in id || "button" in id || "container" in id) {
                score -= 50_000L
            }
            if (view.isClickable || view.isFocusable) score -= 20_000L
            if (view is ViewGroup) score += 15_000L
            return score
        }

        private fun themeFillCandidateScore(view: View, area: Int): Int {
            var score = area
            val className = view.javaClass.name
            val id = getResourceEntryName(view).orEmpty().lowercase(Locale.US)
            if (className == "android.view.View") score -= 100_000
            if ("button" in id || "container" in id) score -= 30_000
            if (view.background != null) score -= 20_000
            if (view is ViewGroup) score += 15_000
            if (view.isClickable) score -= 5_000
            return score
        }

        private fun rectContainsOrCenters(outer: Rect, inner: Rect): Boolean {
            val centerX = inner.left + inner.width() / 2
            val centerY = inner.top + inner.height() / 2
            return outer.contains(centerX, centerY) ||
                (outer.left <= inner.left + 2 &&
                    outer.top <= inner.top + 2 &&
                    outer.right >= inner.right - 2 &&
                    outer.bottom >= inner.bottom - 2)
        }

        private fun formatRect(rect: Rect): String = "[${rect.left},${rect.top}][${rect.right},${rect.bottom}]"

        private fun isViewAncestorOf(candidateAncestor: View, child: View): Boolean {
            var parent = child.parent
            while (parent is View) {
                if (parent === candidateAncestor) return true
                parent = parent.parent
            }
            return false
        }

        private fun themeViewArea(view: View): Int = kotlin.math.max(1, view.width) * kotlin.math.max(1, view.height)

        private fun viewDepthInSelection(selection: SelectionTarget, view: View): Int {
            return selection.viewCandidates.firstOrNull { it.view === view }?.depth ?: 0
        }

        private fun showSelectedView(activity: Activity, root: View, selection: SelectionTarget?) {
            if (selection == null) {
                log("Capture picker selected nothing root=${selectionViewSummary(root, root)}")
                Toast.makeText(activity, "No UI element found here.", Toast.LENGTH_SHORT).show()
                return
            }

            val target = selection.view
            val virtualNode = selection.virtualNode
            val targetId = getResourceEntryName(target)
            val themeCapture = isThemeCaptureEnabled()
            val selector = virtualNode?.selector ?: runCatching { WhatsAppUiElementSelector.build(target, root) }
                .getOrNull()
                .orEmpty()
            val selectorDisplay = selector.takeIf { it.isNotEmpty() }?.let {
                if (isSnapchatVirtualSelector(it)) {
                    virtualNode?.displayName ?: it
                } else {
                    runCatching { WhatsAppUiElementSelector.toDisplayName(it) }.getOrNull()
                }
            }
            val namedTarget = if (!themeCapture && targetId == null) findNearestNamedView(target, root) else target
            val effectiveId = namedTarget?.let { getResourceEntryName(it) }
            val copyValue = when {
                themeCapture && selector.isNotEmpty() -> selector
                targetId != null -> targetId
                selector.isNotEmpty() -> selector
                !effectiveId.isNullOrEmpty() -> effectiveId
                else -> describePath(target, root)
            }
            val hideTarget = when {
                themeCapture && selector.isNotEmpty() -> selector
                targetId != null -> targetId
                selector.isNotEmpty() -> selector
                !effectiveId.isNullOrEmpty() -> effectiveId
                else -> ""
            }
            val themeLabel = buildThemeSurfaceLabel(target, targetId, effectiveId, selector, preferSelector = themeCapture)
            val themeCandidates = if (themeCapture && hideTarget.isNotEmpty()) {
                buildThemeSurfaceCandidates(root, selection, hideTarget, themeLabel)
            } else {
                emptyList()
            }
            val themeTargetMode = when {
                themeCapture && virtualNode != null -> "virtual_accessibility_selector"
                themeCapture && selector.isNotEmpty() -> "exact_selector"
                themeCapture && !targetId.isNullOrBlank() -> "direct_resource_id"
                themeCapture && !effectiveId.isNullOrBlank() -> "nearest_resource_id"
                else -> "ui_capture"
            }
            val bounds = virtualNode?.bounds ?: screenBounds(target)
            log(
                "Capture picker selected themeCapture=${isThemeCaptureEnabled()} uiCapture=${context.config.userInterface.uiElements.captureUiElements.get()} " +
                    "target=${selectionTargetSummary(selection, root)} targetId=${targetId ?: "none"} effectiveId=${effectiveId ?: "none"} " +
                    "themeTargetMode=$themeTargetMode selector=${selector.ifBlank { "none" }} " +
                    "selectorDisplay=${selectorDisplay ?: "none"} bounds=$bounds " +
                    "hideTarget=${hideTarget.ifBlank { "none" }} candidateCount=${themeCandidates.size} " +
                    "candidateRoles=${themeCandidates.joinToString(",") { it.role }.ifBlank { "none" }} copyValue=${copyValue.take(300)}"
            )

            val message = buildString {
                append("Selected: ").append(virtualNode?.let(::virtualThemeDisplayName) ?: target.javaClass.name).append('\n')
                append("ID: ").append(targetId?.let { "#$it" } ?: "NO_ID").append('\n')
                append("Bounds: ").append(bounds).append('\n')
                if (virtualNode != null) {
                    append("Host: ").append(target.javaClass.name).append(targetId?.let { "#$it" } ?: "").append('\n')
                    append("Virtual path: ").append(virtualNode.path).append('\n')
                    append("Local bounds: ").append(virtualNode.localBounds).append('\n')
                }
                if (!themeCapture && targetId == null && namedTarget != null) {
                    append("Nearest ID: #").append(effectiveId).append('\n')
                    append("Nearest view: ").append(namedTarget.javaClass.name).append('\n')
                }
                if (selector.isNotEmpty()) {
                    append("Exact selector: ").append(selectorDisplay ?: selector).append('\n')
                }
                append("Target mode: ").append(themeTargetMode).append('\n')
                if (themeCandidates.isNotEmpty()) {
                    append("Theme parts: ")
                        .append(themeCandidates.joinToString(", ") { it.role })
                        .append('\n')
                }
                append('\n').append("Path: ").append(describePath(target, root))
            }

            runCatching {
                val builder = AlertDialog.Builder(activity)
                    .setTitle(if (isThemeCaptureEnabled()) "Snapchat Theme Surface" else "Snapchat UI Element")
                    .setMessage(message)
                    .setNegativeButton("Close", null)
                if (isThemeCaptureEnabled() && hideTarget.isNotEmpty()) {
                    builder.setPositiveButton("Theme") { _, _ ->
                        showThemeSurfaceCandidateChooser(
                            activity,
                            themeCandidates.ifEmpty {
                                listOf(
                                    ThemeSurfaceCandidate(
                                        role = "Selected surface",
                                        label = themeLabel,
                                        target = target,
                                        rawValue = hideTarget,
                                        previewMode = if (target is ImageView) {
                                            ThemeSurfacePreviewMode.IMAGE_TINT
                                        } else {
                                            ThemeSurfacePreviewMode.AUTO
                                        },
                                        bounds = bounds,
                                        detail = selectionTargetSummary(selection, root)
                                    )
                                )
                            }
                        )
                    }
                    if (context.config.userInterface.uiElements.captureUiElements.get()) {
                        builder.setNeutralButton("Hide") { _, _ -> handleCapturedElement(activity, hideTarget) }
                    }
                } else {
                    builder.setPositiveButton("Copy") { _, _ -> copyToClipboard(activity, copyValue) }
                    if (hideTarget.isNotEmpty()) {
                        builder.setNeutralButton("Hide") { _, _ -> handleCapturedElement(activity, hideTarget) }
                    }
                }
                builder.show()
            }.onFailure { throwable ->
                log("Failed to show selected Snapchat UI element: ${throwable.message}")
                Toast.makeText(activity, copyValue, Toast.LENGTH_LONG).show()
            }
        }

        private fun buildThemeSurfaceLabel(
            target: View,
            targetId: String?,
            effectiveId: String?,
            selector: String,
            preferSelector: Boolean = false
        ): String {
            return when {
                preferSelector && isSnapchatVirtualSelector(selector) -> virtualThemeSurfaceLabel(selector)
                preferSelector && selector.isNotEmpty() -> WhatsAppUiElementSelector.toDisplayName(selector)
                !targetId.isNullOrBlank() -> friendlyLabel(targetId)
                isSnapchatVirtualSelector(selector) -> virtualThemeSurfaceLabel(selector)
                selector.isNotEmpty() -> WhatsAppUiElementSelector.toDisplayName(selector)
                !effectiveId.isNullOrBlank() -> friendlyLabel(effectiveId)
                else -> target.javaClass.simpleName.ifBlank { "Captured surface" }
            }
        }

        private fun virtualThemeSurfaceLabel(selector: String): String {
            val raw = virtualSelectorValue(selector, "text")
                ?: virtualSelectorValue(selector, "desc")
                ?: virtualSelectorValue(selector, "view_id")?.substringAfterLast('/')
                ?: virtualSelectorValue(selector, "class")?.substringAfterLast('.')
                ?: "Virtual surface"
            val label = friendlyLabel(raw)
            val bounds = virtualSelectorValue(selector, "bounds")
            return if (!bounds.isNullOrBlank()) "$label\n$bounds" else label
        }

        private fun friendlyLabel(value: String): String {
            return value
                .replace('_', ' ')
                .replace('-', ' ')
                .split(' ')
                .filter { it.isNotBlank() }
                .joinToString(" ") { word ->
                    word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                }
                .ifBlank { value }
        }

        private fun findNearestNamedView(target: View, root: View): View? {
            var current: View? = target
            while (current != null) {
                if (getResourceEntryName(current) != null) return current
                if (current === root) break
                current = current.parent as? View
            }
            return null
        }

        private fun describePath(target: View, root: View): String {
            val builder = StringBuilder()
            var current: View? = target
            var depth = 0
            while (current != null && depth < 8) {
                if (builder.isNotEmpty()) builder.append(" <- ")
                builder.append(current.javaClass.simpleName)
                getResourceEntryName(current)?.let { builder.append('#').append(it) }
                if (current === root) break
                current = current.parent as? View
                depth++
            }
            return builder.toString()
        }

        private fun selectionViewSummary(view: View?, root: View): String {
            if (view == null) return "none"
            val id = getResourceEntryName(view)
            val selector = runCatching { WhatsAppUiElementSelector.build(view, root) }.getOrNull().orEmpty()
            return buildString {
                append("class=").append(view.javaClass.name)
                append(" id=").append(id ?: "none")
                append(" hash=").append(System.identityHashCode(view))
                append(" size=").append(view.width).append('x').append(view.height)
                append(" pos=").append(view.left).append(',').append(view.top).append('-')
                    .append(view.right).append(',').append(view.bottom)
                append(" alpha=").append(view.alpha)
                append(" visibility=").append(view.visibility)
                append(" clickable=").append(view.isClickable)
                append(" enabled=").append(view.isEnabled)
                append(" bg=").append(drawableSummary(view.background))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    append(" fg=").append(drawableSummary(view.foreground))
                }
                append(" selector=").append(selector.ifBlank { "none" })
                append(" path=").append(describePath(view, root))
            }.take(1200)
        }

        private fun selectionTargetSummary(target: SelectionTarget?, root: View): String {
            target ?: return "none"
            val virtual = target.virtualNode
            return if (virtual != null) {
                "virtual=${virtualNodeSummary(virtual)} host=${selectionViewSummary(target.view, root)}"
            } else {
                selectionViewSummary(target.view, root)
            }
        }

        private fun virtualNodeSummary(target: VirtualNodeTarget): String {
            return "display=${target.displayName.replace('\n', ' ')} bounds=${target.bounds} local=${target.localBounds} " +
                "path=${target.path} class=${target.className} text=${target.text} desc=${target.contentDescription} " +
                "viewId=${target.viewIdResourceName} area=${target.area} depth=${target.depth} selector=${target.selector.take(700)}"
        }

        private fun boundsRect(view: View): Rect {
            val location = IntArray(2)
            return runCatching {
                view.getLocationOnScreen(location)
                Rect(location[0], location[1], location[0] + view.width, location[1] + view.height)
            }.getOrDefault(Rect())
        }

        private fun screenBounds(view: View?): String {
            if (view == null) return "none"
            val rect = boundsRect(view)
            return if (rect.isEmpty) "none" else boundsToString(rect)
        }

        private fun boundsToString(rect: Rect): String {
            return "[${rect.left},${rect.top}][${rect.right},${rect.bottom}]"
        }

        private fun drawableSummary(drawable: Drawable?): String {
            if (drawable == null) return "none"
            return buildString {
                append(drawable.javaClass.name)
                if (drawable is GradientDrawable) append("(gradient)")
            }
        }

        private fun copyToClipboard(activity: Activity, value: String) {
            val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
            clipboard.setPrimaryClip(ClipData.newPlainText("Snapchat UI element", value))
            Toast.makeText(activity, "Copied: $value", Toast.LENGTH_SHORT).show()
        }

        private fun removeFromParent(view: View?) {
            val parent = view?.parent as? ViewGroup ?: return
            parent.removeView(view)
        }

        private fun dp(context: Context, value: Int): Int {
            return TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                value.toFloat(),
                context.resources.displayMetrics
            ).toInt()
        }
    }

    private data class OverlayState(
        var button: TextView? = null,
        var pickerOverlay: View? = null,
        var launcherSuppressed: Boolean = false,
        var launcherPreviousVisibility: Int = View.VISIBLE,
        var launcherPreviousClickable: Boolean = true,
        var launcherPreviousFocusable: Boolean = true
    )

    private data class SelectionTarget(
        val view: View,
        val virtualNode: VirtualNodeTarget? = null,
        val virtualCandidates: List<VirtualNodeTarget> = emptyList(),
        val viewCandidates: List<ViewCandidate> = emptyList()
    )

    private data class ThemeSurfaceCandidate(
        val role: String,
        val label: String,
        val target: View,
        val rawValue: String,
        val previewMode: ThemeSurfacePreviewMode,
        val bounds: String,
        val detail: String
    )

    private enum class ThemeSurfacePreviewMode {
        AUTO,
        BACKGROUND,
        IMAGE_TINT,
        TEXT_COLOR,
        VIRTUAL_TEXT,
        VIRTUAL_OVERLAY
    }

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

    private data class VirtualNodeTarget(
        val hostView: View,
        val selector: String,
        val displayName: String,
        val bounds: String,
        val localBounds: String,
        val path: String,
        val className: String,
        val text: String,
        val contentDescription: String,
        val viewIdResourceName: String,
        val depth: Int,
        val area: Long
    )

    private data class ViewCandidate(
        val view: View,
        val depth: Int
    ) : Comparable<ViewCandidate> {
        val area = kotlin.math.max(1, view.width) * kotlin.math.max(1, view.height)
        val viewGroup = view is ViewGroup
        val hasId = WhatsAppUiElementSelector.getResourceEntryName(view) != null
        val interactive = view.isClickable || view.isLongClickable || view.isFocusable

        override fun compareTo(other: ViewCandidate): Int {
            area.compareTo(other.area).takeIf { it != 0 }?.let { return it }
            if (viewGroup != other.viewGroup) return if (viewGroup) 1 else -1
            other.depth.compareTo(depth).takeIf { it != 0 }?.let { return it }
            if (hasId != other.hasId) return if (hasId) -1 else 1
            if (interactive != other.interactive) return if (interactive) -1 else 1
            return 0
        }
    }
}
