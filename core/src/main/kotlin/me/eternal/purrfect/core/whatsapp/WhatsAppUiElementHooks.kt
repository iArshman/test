package me.eternal.purrfect.core.whatsapp

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewStub
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import me.eternal.purrfect.common.Constants
import me.eternal.purrfect.core.logger.CoreLogger
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

class WhatsAppUiElementHooks(
    private val androidContext: Context,
    @Suppress("unused") private val appClassLoader: ClassLoader = androidContext.classLoader
) {
    companion object {
        private const val TAG = WhatsAppChannelHooks.TAG
        private const val MAX_COLLAPSE_WRAPPER_DEPTH = 8
        private val activeHooks = Collections.synchronizedSet(
            Collections.newSetFromMap(WeakHashMap<WhatsAppUiElementHooks, Boolean>())
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

        fun refreshActiveHooks(reason: String) {
            activeHooksSnapshot().forEach { hook ->
                hook.refreshVisibleRoots(reason)
            }
        }

        private fun activeHooksSnapshot(): List<WhatsAppUiElementHooks> {
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

    private val featureState get() = WhatsAppFeatureStateStore.current

    fun init() {
        synchronized(activeHooks) { activeHooks += this }
        installPlatformHooks()
        refreshVisibleRoots("init")
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

        logStatic("WhatsApp UI element hooks installed")
    }

    private fun hookSafe(name: String, block: () -> Unit) {
        runCatching(block).onFailure { throwable ->
            logStatic("Failed to install $name hook: ${throwable.message}")
        }
    }

    private fun onActivityVisible(activity: Activity, reason: String) {
        if (activity.isFinishing || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && activity.isDestroyed)) {
            captureOverlay.removeFromActivity(activity)
            return
        }
        synchronized(trackedActivities) { trackedActivities += activity }
        applyToActivity(activity, reason)
    }

    private fun onActivityDestroyed(activity: Activity) {
        synchronized(trackedActivities) { trackedActivities -= activity }
        captureOverlay.removeFromActivity(activity)
    }

    private fun refreshVisibleRoots(reason: String) {
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
            if (featureState.captureUiElements) {
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

        val current = featureState
        val updated = if (isSelector) {
            current.copy(
                hideUiElements = true,
                hiddenUiElementSelectors = appendUnique(current.hiddenUiElementSelectors, clean)
            )
        } else {
            current.copy(
                hideUiElements = true,
                hiddenUiElementIds = appendUnique(current.hiddenUiElementIds, clean)
            )
        }
        WhatsAppFeatureStateStore.update(updated)

        runCatching {
            activity.sendBroadcast(
                Intent(Constants.WHATSAPP_UI_ELEMENT_CAPTURED_ACTION)
                    .setPackage(Constants.MODULE_PACKAGE_NAME)
                    .putExtra(Constants.WHATSAPP_UI_ELEMENT_CAPTURED_VALUE_EXTRA, clean)
                    .putExtra(Constants.WHATSAPP_UI_ELEMENT_CAPTURED_IS_SELECTOR_EXTRA, isSelector)
            )
        }.onFailure { throwable ->
            log("Failed to persist captured WhatsApp UI element: ${throwable.message}")
        }

        refreshVisibleRoots("captured")
        Toast.makeText(activity, if (isSelector) "Hidden exact element." else "Hidden: $clean", Toast.LENGTH_SHORT).show()
    }

    private fun rules(): HiddenRules {
        val state = featureState
        val current = cachedRules
        if (current.enabled == state.hideUiElements &&
            current.rawIds == state.hiddenUiElementIds &&
            current.rawSelectors == state.hiddenUiElementSelectors
        ) {
            return current
        }

        val next = HiddenRules(
            enabled = state.hideUiElements,
            rawIds = state.hiddenUiElementIds,
            rawSelectors = state.hiddenUiElementSelectors,
            ids = parseIds(state.hiddenUiElementIds),
            selectors = parseSelectors(state.hiddenUiElementSelectors)
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
        }.getOrNull()
    }

    private fun logHiddenMatch(view: View) {
        val idName = getResourceEntryName(view)
        val key = idName ?: WhatsAppUiElementSelector.build(view, view.rootView)
        if (key.isBlank() || !loggedHiddenKeys.add(key)) return
        log("Hiding WhatsApp UI ${idName?.let { "#$it" } ?: key} view=${view.javaClass.name}")
    }

    private fun logCollapsedWrapper(source: View, wrapper: View) {
        val sourceName = getResourceEntryName(source)?.let { "#$it" } ?: source.javaClass.simpleName
        val wrapperName = getResourceEntryName(wrapper)?.let { "#$it" } ?: wrapper.javaClass.simpleName
        log("Collapsed WhatsApp UI slot $wrapperName for hidden $sourceName")
    }

    private fun runSafe(label: String, block: () -> Unit) {
        runCatching(block).onFailure { throwable ->
            log("$label failed: ${throwable.message}")
        }
    }

    private fun log(message: String) {
        XposedBridge.log("[$TAG] $message")
        CoreLogger.xposedLog(message, TAG)
        WhatsAppAppLogWriter.info(androidContext, TAG, message)
    }

    private fun logStatic(message: String) {
        XposedBridge.log("[$TAG] $message")
        CoreLogger.xposedLog(message, TAG)
        WhatsAppAppLogWriter.info(null, TAG, message)
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
            androidContext.resources.displayMetrics
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

    private inner class CaptureOverlay {
        private val states = Collections.synchronizedMap(WeakHashMap<Activity, OverlayState>())
        private val overlayTag = "purrfect_whatsapp_ui_element_overlay"

        fun applyToActivity(activity: Activity, decor: ViewGroup) {
            if (!featureState.captureUiElements) {
                removeFromActivity(activity)
                return
            }

            val state = states.getOrPut(activity) { OverlayState() }
            if (state.button == null) {
                state.button = createButton(activity, decor, state)
            }

            val button = state.button ?: return
            if (button.parent !== decor) {
                removeFromParent(button)
                decor.addView(button, createInitialButtonLayout(activity))
            }
        }

        fun removeFromActivity(activity: Activity) {
            val state = states.remove(activity) ?: return
            removeFromParent(state.pickerOverlay)
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
            if (state.pickerOverlay?.parent != null) removeFromParent(state.pickerOverlay)

            val overlay = FrameLayout(activity).apply {
                tag = overlayTag
                isClickable = true
                isFocusable = true
                setBackgroundColor(0x330A84FF)
            }

            val label = TextView(activity).apply {
                tag = overlayTag
                text = "Tap an element"
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

                val target = findBestViewAt(decor, rawX, rawY, state)
                showSelectedView(activity, decor, target)
                true
            }

            state.pickerOverlay = overlay
            decor.addView(
                overlay,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )
        }

        private fun findBestViewAt(root: View, rawX: Float, rawY: Float, state: OverlayState): View? {
            val candidates = mutableListOf<ViewCandidate>()
            collectViewsAt(root, root, rawX, rawY, state, 0, candidates)
            return candidates.minOrNull()?.view
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

        private fun showSelectedView(activity: Activity, root: View, target: View?) {
            if (target == null) {
                Toast.makeText(activity, "No UI element found here.", Toast.LENGTH_SHORT).show()
                return
            }

            val targetId = getResourceEntryName(target)
            val namedTarget = if (targetId == null) findNearestNamedView(target, root) else target
            val effectiveId = namedTarget?.let { getResourceEntryName(it) }
            val selector = if (targetId == null) WhatsAppUiElementSelector.build(target, root) else ""
            val copyValue = when {
                targetId != null -> targetId
                selector.isNotEmpty() -> selector
                !effectiveId.isNullOrEmpty() -> effectiveId
                else -> describePath(target, root)
            }
            val hideTarget = when {
                targetId != null -> targetId
                selector.isNotEmpty() -> selector
                !effectiveId.isNullOrEmpty() -> effectiveId
                else -> ""
            }

            val message = buildString {
                append("Selected: ").append(target.javaClass.name).append('\n')
                append("ID: ").append(targetId?.let { "#$it" } ?: "NO_ID").append('\n')
                if (targetId == null && namedTarget != null) {
                    append("Nearest ID: #").append(effectiveId).append('\n')
                    append("Nearest view: ").append(namedTarget.javaClass.name).append('\n')
                }
                if (targetId == null && selector.isNotEmpty()) {
                    append("Exact selector: ").append(WhatsAppUiElementSelector.toDisplayName(selector)).append('\n')
                }
                append('\n').append("Path: ").append(describePath(target, root))
            }

            runCatching {
                val builder = AlertDialog.Builder(activity)
                    .setTitle("WhatsApp UI Element")
                    .setMessage(message)
                    .setPositiveButton("Copy") { _, _ -> copyToClipboard(activity, copyValue) }
                    .setNegativeButton("Close", null)
                if (hideTarget.isNotEmpty()) {
                    builder.setNeutralButton("Hide") { _, _ -> handleCapturedElement(activity, hideTarget) }
                }
                builder.show()
            }.onFailure { throwable ->
                log("Failed to show selected WhatsApp UI element: ${throwable.message}")
                Toast.makeText(activity, copyValue, Toast.LENGTH_LONG).show()
            }
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

        private fun copyToClipboard(activity: Activity, value: String) {
            val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
            clipboard.setPrimaryClip(ClipData.newPlainText("WhatsApp UI element", value))
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
        var pickerOverlay: View? = null
    )

    private data class ViewCandidate(
        val view: View,
        val depth: Int
    ) : Comparable<ViewCandidate> {
        private val area = kotlin.math.max(1, view.width) * kotlin.math.max(1, view.height)
        private val viewGroup = view is ViewGroup
        private val hasId = WhatsAppUiElementSelector.getResourceEntryName(view) != null
        private val interactive = view.isClickable || view.isLongClickable || view.isFocusable

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

object WhatsAppUiElementSelector {
    private const val PREFIX = "selector:v1|"

    fun isSelector(raw: String?): Boolean = raw?.trim()?.startsWith(PREFIX) == true

    fun normalize(raw: String?): String {
        val clean = raw?.trim().orEmpty()
        return if (clean.startsWith(PREFIX)) clean else ""
    }

    fun build(target: View?, root: View?): String {
        if (target == null || root == null || target === root) return ""
        val anchor = findNearestNamedAncestor(target, root) as? ViewGroup ?: return ""
        val anchorId = getResourceEntryName(anchor).orEmpty()
        if (anchorId.isEmpty()) return ""

        val indexes = mutableListOf<Int>()
        val classes = mutableListOf<String>()
        var current: View? = target
        while (current != null && current !== anchor) {
            val parent = current.parent as? ViewGroup ?: return ""
            val index = parent.indexOfChild(current)
            if (index < 0) return ""
            indexes += index
            classes += current.javaClass.name
            current = parent
        }

        if (indexes.isEmpty()) return ""
        return PREFIX +
            "anchor=${escape(anchorId)}" +
            "|indexes=${indexes.asReversed().joinToString("/")}" +
            "|classes=${escape(classes.asReversed().joinToString("/"))}"
    }

    fun matches(view: View?, selector: String): Boolean {
        val parsed = parse(selector) ?: return false
        if (view == null) return false

        val leafClass = parsed.lastClass()
        if (leafClass == null || !isClassCompatible(leafClass, view.javaClass.name)) return false

        var current: View? = view
        for (i in parsed.indexes.indices.reversed()) {
            val currentView = current ?: return false
            val parent = currentView.parent as? ViewGroup ?: return false
            if (parent.indexOfChild(currentView) != parsed.indexes[i]) return false
            if (!isClassCompatible(parsed.classes[i], currentView.javaClass.name)) return false
            current = parent
        }

        return parsed.anchorId == getResourceEntryName(current)
    }

    fun toDisplayName(selector: String): String {
        val parsed = parse(selector) ?: return selector
        val leaf = parsed.lastClass()?.substringAfterLast('.') ?: "View"
        return "Captured $leaf under #${parsed.anchorId}\n${parsed.indexPath()}"
    }

    fun findNearestNamedAncestor(target: View, root: View): View? {
        var current: View? = target
        while (current != null) {
            if (getResourceEntryName(current) != null) return current
            if (current === root) break
            current = current.parent as? View
        }
        return null
    }

    fun getResourceEntryName(view: View?): String? {
        if (view == null || view.id == View.NO_ID) return null
        return try {
            view.resources?.getResourceEntryName(view.id)
        } catch (_: Resources.NotFoundException) {
            null
        } catch (_: Throwable) {
            null
        }?.takeUnless(::isObfuscatedResourcePlaceholder)
    }

    private fun isObfuscatedResourcePlaceholder(value: String): Boolean {
        return value.endsWith("_resource_name_obfuscated") ||
            value == "0" ||
            value.matches(Regex("\\d+_resource_name_obfuscated"))
    }

    private fun parse(selector: String): ParsedSelector? {
        val clean = normalize(selector)
        if (clean.isEmpty()) return null
        val values = clean.substring(PREFIX.length)
            .split('|')
            .mapNotNull { part ->
                val equals = part.indexOf('=')
                if (equals <= 0) null else part.substring(0, equals) to unescape(part.substring(equals + 1))
            }
            .toMap()

        val anchor = values["anchor"].orEmpty()
        val indexes = values["indexes"].orEmpty()
        val classes = values["classes"].orEmpty()
        if (anchor.isEmpty() || indexes.isEmpty() || classes.isEmpty()) return null

        val parsedIndexes = indexes.split('/').mapNotNull { it.toIntOrNull() }
        val parsedClasses = classes.split('/').filter { it.isNotEmpty() }
        if (parsedIndexes.size != parsedClasses.size || parsedIndexes.isEmpty()) return null
        return ParsedSelector(anchor, parsedIndexes, parsedClasses)
    }

    private fun isClassCompatible(expected: String, actual: String): Boolean {
        if (expected == actual) return true
        if (expected.startsWith("X.") || actual.startsWith("X.")) return true
        if (expected.startsWith("com.whatsapp.") && actual.startsWith("com.whatsapp.")) return true
        if (expected.startsWith("com.snapchat.") && actual.startsWith("com.snapchat.")) return true
        return false
    }

    private fun escape(value: String): String {
        return value
            .replace("%", "%25")
            .replace("|", "%7C")
            .replace("=", "%3D")
            .replace("\n", "%0A")
    }

    private fun unescape(value: String): String {
        return value
            .replace("%0A", "\n")
            .replace("%3D", "=")
            .replace("%7C", "|")
            .replace("%25", "%")
    }

    private data class ParsedSelector(
        val anchorId: String,
        val indexes: List<Int>,
        val classes: List<String>
    ) {
        fun lastClass(): String? = classes.lastOrNull()
        fun indexPath(): String = indexes.joinToString("/")
    }
}
