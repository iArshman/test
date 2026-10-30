package me.eternal.purrfect.core.whatsapp

import android.app.Activity
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import me.eternal.purrfect.core.logger.CoreLogger
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class WhatsAppLiquidGlassHooks(
    private val androidContext: Context,
    @Suppress("unused") private val appClassLoader: ClassLoader = androidContext.classLoader
) {
    companion object {
        private const val TAG = WhatsAppChannelHooks.TAG
        private val activeHooks = Collections.synchronizedSet(
            Collections.newSetFromMap(WeakHashMap<WhatsAppLiquidGlassHooks, Boolean>())
        )
        private val platformHooksInstalled = AtomicBoolean(false)
        private val globallyStyledNavs = Collections.synchronizedSet(
            Collections.newSetFromMap(WeakHashMap<View, Boolean>())
        )
        private val globallyHiddenNativeSlots = Collections.synchronizedSet(
            Collections.newSetFromMap(WeakHashMap<View, Boolean>())
        )

        fun refreshActiveHooks(reason: String) {
            activeHooksSnapshot().forEach { hook ->
                hook.refreshVisibleRoots(reason)
            }
        }

        private fun activeHooksSnapshot(): List<WhatsAppLiquidGlassHooks> {
            return synchronized(activeHooks) { activeHooks.toList() }
        }

        private fun isGloballyStyledNav(view: View): Boolean {
            return synchronized(globallyStyledNavs) { view in globallyStyledNavs }
        }

        private fun globallyStyledNavFor(view: View): View? {
            if (synchronized(globallyStyledNavs) { globallyStyledNavs.isEmpty() }) return null
            var current: View? = view
            repeat(8) {
                val candidate = current ?: return null
                if (isGloballyStyledNav(candidate)) return candidate
                current = candidate.parent as? View
            }
            return null
        }

        private fun isGloballyHiddenNativeSlot(view: View): Boolean {
            return synchronized(globallyHiddenNativeSlots) { view in globallyHiddenNativeSlots }
        }
    }

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val trackedActivities = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<Activity, Boolean>()))
    private val trackedRoots = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<View, Boolean>()))
    private val pendingScans = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<View, Boolean>()))
    private val pendingNavStylePosts = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<View, Boolean>()))
    private val pendingBackdropCaptures = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<View, Boolean>()))
    private val pendingActionButtonLifts = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<View, Boolean>()))
    private val lastScanTimes = Collections.synchronizedMap(WeakHashMap<View, Long>())
    private val lastBackdropCaptureTimes = Collections.synchronizedMap(WeakHashMap<View, Long>())
    private val protectedBottomHeights = Collections.synchronizedMap(WeakHashMap<View, Int>())
    private val originalNavStates = Collections.synchronizedMap(WeakHashMap<View, NavOriginalState>())
    private val originalItemStates = Collections.synchronizedMap(WeakHashMap<View, ItemOriginalState>())
    private val originalAuxStates = Collections.synchronizedMap(WeakHashMap<View, AuxOriginalState>())
    private val originalTouchListeners = Collections.synchronizedMap(WeakHashMap<View, View.OnTouchListener?>())
    private val originalFabTranslations = Collections.synchronizedMap(WeakHashMap<View, Float>())
    private val styledNavs = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<View, Boolean>()))
    private val styledNavByRoot = Collections.synchronizedMap(WeakHashMap<View, View>())
    private val barDrawables = Collections.synchronizedMap(WeakHashMap<View, LiquidGlassBarDrawable>())
    private val overlayBars = Collections.synchronizedMap(WeakHashMap<View, WhatsAppLiquidGlassComposeOverlay>())
    private val liquidTabCache = Collections.synchronizedMap(WeakHashMap<View, List<WhatsAppLiquidGlassTab>>())
    private val dragStates = Collections.synchronizedMap(WeakHashMap<View, DragState>())
    private val density = androidContext.resources.displayMetrics.density
    private val activeTint = ColorStateList(
        arrayOf(
            intArrayOf(android.R.attr.state_checked),
            intArrayOf(android.R.attr.state_selected),
            intArrayOf(android.R.attr.state_activated),
            intArrayOf()
        ),
        intArrayOf(
            Color.WHITE,
            Color.WHITE,
            Color.WHITE,
            Color.rgb(190, 199, 205)
        )
    )
    private val transparentTint = ColorStateList.valueOf(Color.TRANSPARENT)
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
                    val view = param.thisObject as? View ?: return
                    activeHooksSnapshot().forEach { it.onViewSignal(view, "attached") }
                }
            })
        }

        hookSafe("View.onFinishInflate") {
            XposedBridge.hookAllMethods(View::class.java, "onFinishInflate", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam<*>) {
                    val view = param.thisObject as? View ?: return
                    activeHooksSnapshot().forEach { it.onViewSignal(view, "finishInflate") }
                }
            })
        }

        hookSafe("View.onLayout") {
            XposedBridge.hookAllMethods(View::class.java, "onLayout", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam<*>) {
                    val view = param.thisObject as? View ?: return
                    activeHooksSnapshot().forEach { it.onViewLayout(view) }
                }
            })
        }

        hookSafe("View.setVisibility") {
            XposedBridge.hookAllMethods(View::class.java, "setVisibility", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam<*>) {
                    val view = param.thisObject as? View ?: return
                    if (!isGloballyHiddenNativeSlot(view) || view.visibility == View.GONE) return
                    activeHooksSnapshot().forEach { hook ->
                        if (hook.featureState.liquidClass) hook.forceHideNativeSlot(view)
                    }
                }
            })
        }

        hookSafe("ViewGroup.addView") {
            XposedBridge.hookAllMethods(ViewGroup::class.java, "addView", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam<*>) {
                    val parent = param.thisObject as? ViewGroup ?: return
                    val child = param.args.firstOrNull { it is View } as? View
                    activeHooksSnapshot().forEach { hook ->
                        hook.onViewSignal(parent, "addView-parent")
                        child?.let { hook.onViewSignal(it, "addView-child") }
                    }
                }
            })
        }

        hookSafe("ViewGroup.addViewInLayout") {
            XposedBridge.hookAllMethods(ViewGroup::class.java, "addViewInLayout", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam<*>) {
                    val parent = param.thisObject as? ViewGroup ?: return
                    val child = param.args.firstOrNull { it is View } as? View
                    activeHooksSnapshot().forEach { hook ->
                        hook.onViewSignal(parent, "addViewInLayout-parent")
                        child?.let { hook.onViewSignal(it, "addViewInLayout-child") }
                    }
                }
            })
        }

        logStatic("WhatsApp liquid glass hooks installed")
    }

    private fun hookSafe(name: String, block: () -> Unit) {
        runCatching(block).onFailure { throwable ->
            logStatic("Failed to install $name hook: ${throwable.message}")
        }
    }

    private fun onActivityVisible(activity: Activity, reason: String) {
        if (activity.isFinishing || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && activity.isDestroyed)) {
            synchronized(trackedActivities) { trackedActivities -= activity }
            return
        }
        synchronized(trackedActivities) { trackedActivities += activity }
        val decor = activity.window?.decorView ?: return
        rememberRoot(decor)
        scheduleScan(decor, "activity-$reason")
    }

    private fun onActivityDestroyed(activity: Activity) {
        synchronized(trackedActivities) { trackedActivities -= activity }
    }

    private fun refreshVisibleRoots(reason: String) {
        if (!featureState.liquidClass) {
            restoreAll("refresh-disabled-$reason")
            return
        }

        synchronized(trackedActivities) { trackedActivities.toList() }.forEach { activity ->
            activity.window?.decorView?.let { root ->
                rememberRoot(root)
                scheduleScan(root, "refresh-activity-$reason")
            }
        }

        synchronized(trackedRoots) { trackedRoots.toList() }.forEach { root ->
            if (root.isAttachedToWindowCompat()) {
                scheduleScan(root, "refresh-root-$reason")
            }
        }

        synchronized(styledNavs) { styledNavs.toList() }.forEach { nav ->
            if (nav.isAttachedToWindowCompat()) {
                styleBottomNavigation(nav, "refresh-styled-$reason")
            }
        }
    }

    private fun onViewSignal(view: View, reason: String) {
        if (!featureState.liquidClass) {
            if (styledNavs.isNotEmpty()) restoreAll("disabled-$reason")
            return
        }

        scheduleActionButtonLiftForSignal(view)
        if (!hasDirectBottomNavigationSignal(view)) return
        findBottomNavigationHostFromSignal(view)?.let { host ->
            styleBottomNavigation(host, "signal-$reason")
        }
        rememberRoot(view)
        if (resourceEntryName(view).orEmpty().startsWith("bottom_nav") ||
            "bottombar" in view.javaClass.name.lowercase() ||
            "bottomnavigation" in view.javaClass.name.lowercase()
        ) {
            scheduleScan(view.rootView ?: view, reason, immediate = true)
        }
    }

    private fun onViewLayout(view: View) {
        if (!featureState.liquidClass || view is WhatsAppLiquidGlassComposeOverlay) return
        scheduleActionButtonLiftForSignal(view)
    }

    private fun scheduleActionButtonLiftForSignal(view: View) {
        val root = view.rootView as? ViewGroup ?: return
        val protectedHeight = synchronized(protectedBottomHeights) { protectedBottomHeights[root] ?: return }
        if (!isPossibleBottomActionSignal(root, view, protectedHeight)) return
        scheduleActionButtonLift(root, protectedHeight)
    }

    private fun isPossibleBottomActionSignal(root: ViewGroup, view: View, protectedHeight: Int): Boolean {
        if (view.visibility != View.VISIBLE || view.width <= 0 || view.height <= 0) return false
        if (view === root || view is WhatsAppLiquidGlassComposeOverlay) return false
        val rootWidth = root.width.takeIf { it > 0 } ?: return false
        val rootHeight = root.height.takeIf { it > 0 } ?: return false
        val rootLocation = IntArray(2)
        val viewLocation = IntArray(2)
        return runCatching {
            root.getLocationOnScreen(rootLocation)
            view.getLocationOnScreen(viewLocation)
            val left = viewLocation[0] - rootLocation[0]
            val top = viewLocation[1] - rootLocation[1]
            val right = left + view.width
            val bottom = top + view.height
            val className = view.javaClass.name.lowercase()
            val resourceName = resourceEntryName(view).orEmpty().lowercase()
            val knownAction = isKnownBottomActionResource(resourceName) || isKnownBottomActionClass(className)
            right > rootWidth * 0.12f &&
                view.width < rootWidth * 0.90f &&
                bottom > rootHeight - protectedHeight - dp(160f) &&
                top > rootHeight * 0.34f &&
                (knownAction || !isNavigationLike(className, resourceName))
        }.getOrDefault(false)
    }

    private fun rememberRoot(view: View) {
        val root = view.rootView ?: view
        synchronized(trackedRoots) { trackedRoots += root }
    }

    private fun scheduleScan(root: View, reason: String, immediate: Boolean = false) {
        if (!root.isAttachedToWindowCompat() && root.rootView !== root) return
        val now = android.os.SystemClock.uptimeMillis()
        val last = synchronized(lastScanTimes) { lastScanTimes[root] ?: 0L }
        val delay = if (immediate || now - last > 650L) 0L else 650L - (now - last)
        synchronized(pendingScans) {
            if (!pendingScans.add(root)) return
        }
        mainHandler.postDelayed({
            synchronized(pendingScans) { pendingScans.remove(root) }
            runSafe("liquid scan $reason") {
                synchronized(lastScanTimes) { lastScanTimes[root] = android.os.SystemClock.uptimeMillis() }
                scanRootForBottomNavigation(root, reason)
            }
        }, delay)
    }

    private fun scanRootForBottomNavigation(root: View, reason: String) {
        if (!featureState.liquidClass) {
            restoreAll("scan-disabled-$reason")
            return
        }

        val actualRoot = root.rootView ?: root
        val signals = mutableListOf<View>()
        collectBottomNavigationSignals(actualRoot, signals, 0)
        val host = signals
            .asSequence()
            .mapNotNull { findBottomNavigationHostFromSignal(it) }
            .distinct()
            .maxByOrNull { bottomNavigationScore(it) }

        if (host != null) {
            val previous = styledNavByRoot[actualRoot]
            if (previous != null && previous !== host) {
                restoreNav(previous)
                synchronized(styledNavs) { styledNavs.remove(previous) }
            }
            styledNavByRoot[actualRoot] = host
            styleBottomNavigation(host, "scan-$reason")
        }
    }

    private fun collectBottomNavigationSignals(view: View, out: MutableList<View>, depth: Int) {
        if (depth > 14 || view.visibility != View.VISIBLE) return
        if (hasDirectBottomNavigationSignal(view)) {
            out += view
            if (isStrictBottomNavigationHost(view)) return
        }
        val group = view as? ViewGroup ?: return
        for (index in 0 until group.childCount) {
            collectBottomNavigationSignals(group.getChildAt(index), out, depth + 1)
        }
    }

    private fun findBottomNavigationHostFromSignal(view: View): View? {
        if (resourceEntryName(view).orEmpty().lowercase() == "bottom_nav_container") {
            findStrictBottomNavigationDescendant(view)?.let { return it }
        }
        var current: View? = view
        var structuralFallback: View? = null
        repeat(10) {
            val candidate = current ?: return@repeat
            if (isStrictBottomNavigationHost(candidate) && isBottomNavigationCandidate(candidate)) {
                return candidate
            }
            if (structuralFallback == null && isStructuralBottomNavigationHost(candidate)) {
                structuralFallback = candidate
            }
            current = candidate.parent as? View
        }
        return structuralFallback
    }

    private fun findStrictBottomNavigationDescendant(view: View, depth: Int = 0): View? {
        if (depth > 4) return null
        if (isStrictBottomNavigationHost(view)) return view
        val group = view as? ViewGroup ?: return null
        for (index in 0 until group.childCount) {
            findStrictBottomNavigationDescendant(group.getChildAt(index), depth + 1)?.let { return it }
        }
        return null
    }

    private fun isBottomNavigationCandidate(view: View): Boolean {
        return bottomNavigationScore(view) >= 92 || isStructuralBottomNavigationHost(view)
    }

    private fun bottomNavigationScore(view: View): Int {
        if (view.visibility != View.VISIBLE || view.width <= 0 || view.height <= 0) return 0
        val root = view.rootView ?: view
        val rootWidth = root.width.takeIf { it > 0 } ?: view.resources.displayMetrics.widthPixels
        val rootHeight = root.height.takeIf { it > 0 } ?: view.resources.displayMetrics.heightPixels
        if (rootWidth <= 0 || rootHeight <= 0) return 0

        val rootLocation = IntArray(2)
        val viewLocation = IntArray(2)
        runCatching { root.getLocationOnScreen(rootLocation) }
        runCatching { view.getLocationOnScreen(viewLocation) }
        val top = viewLocation[1] - rootLocation[1]
        val bottom = top + view.height
        if (bottom < rootHeight * 0.70f || top < rootHeight * 0.52f) return 0
        if (view.width < rootWidth * 0.44f || view.height < dp(38f) || view.height > rootHeight * 0.18f) return 0

        var score = 0
        val className = view.javaClass.name.lowercase()
        val resourceName = resourceEntryName(view).orEmpty().lowercase()

        if ("wdsbottombar" in className) score += 92
        if ("bottomnavigation" in className) score += 84
        if ("navigationbarview" in className) score += 76
        if (resourceName == "bottom_nav") score += 90
        if ("bottombar" in className || "bottom_bar" in resourceName) score += 34
        if ("navigation" in resourceName && "bar" in resourceName) score += 28
        if (hasMenuSurface(view)) score += 70

        val itemCount = countLikelyNavigationItems(view)
        if (itemCount >= 3) score += 36 + max(0, itemCount - 3) * 4
        if (itemCount in 1..2 && !isStrictBottomNavigationHost(view)) score -= 60
        if (!isStrictBottomNavigationHost(view) && itemCount < 3) return 0

        val labels = mutableListOf<String>()
        collectText(view, labels, 0, 8)
        val knownLabels = labels.map { it.normalizeUiText() }.count(::isKnownBottomTabText)
        if (knownLabels >= 2) score += 18
        if (knownLabels >= 3) score += 10

        return score
    }

    private fun styleBottomNavigation(nav: View, reason: String) {
        if (!featureState.liquidClass) {
            restoreStyledViewIfNeeded(nav)
            return
        }
        if (!isStrictBottomNavigationHost(nav) && !isBottomNavigationCandidate(nav)) return

        rememberNavState(nav)
        barDrawables.remove(nav)
        nav.background = ColorDrawable(Color.TRANSPARENT)
        nav.setBackgroundColor(Color.TRANSPARENT)
        nav.background = ColorDrawable(Color.TRANSPARENT)
        nav.elevation = max(nav.elevation, dp(8f).toFloat())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            nav.translationZ = max(nav.translationZ, dp(2f).toFloat())
            nav.clipToOutline = false
        }
        styleNavSurroundings(nav)
        applyNavigationBarReflectionStyle(nav)
        hideNativeNavHighlighters(nav, hideNavigationItems = false)
        if (overlayBars.containsKey(nav) || (nav.width > 0 && nav.height > 0)) {
            val tabs = cachedLiquidTabs(nav)
            val selected = selectedIndex(nav)
            val height = overlayHeight(nav)
            hideNativeBottomNavSlot(nav)
            if (!installLiquidOverlay(nav, tabs, selected, height)) {
                scheduleNavStyleAfterLayout(nav, reason)
            }
        } else {
            scheduleNavStyleAfterLayout(nav, reason)
        }
        nav.invalidate()

        if (styledNavs.add(nav)) {
            synchronized(globallyStyledNavs) { globallyStyledNavs += nav }
            log("Applied liquid glass bottom bar to ${nav.javaClass.name} from $reason")
        }
    }

    private fun scheduleNavStyleAfterLayout(nav: View, reason: String) {
        synchronized(pendingNavStylePosts) {
            if (!pendingNavStylePosts.add(nav)) return
        }
        nav.post {
            synchronized(pendingNavStylePosts) { pendingNavStylePosts.remove(nav) }
            if (featureState.liquidClass && nav.isAttachedToWindowCompat() && nav.width > 0 && nav.height > 0) {
                styleBottomNavigation(nav, "post-layout-$reason")
            }
        }
    }

    private fun handleNavDragEvent(
        nav: View,
        event: MotionEvent,
        sourceView: View = nav,
        ownTouchStream: Boolean = false
    ): Boolean {
        if (!featureState.liquidClass || nav !in styledNavs ||
            (!isStrictBottomNavigationHost(nav) && !isBottomNavigationCandidate(nav))
        ) {
            dragStates.remove(nav)?.cancel(mainHandler)
            return false
        }

        val state = dragStates.getOrPut(nav) { DragState() }
        val touchX = navRelativeX(nav, sourceView, event)
        val touchY = navRelativeY(nav, sourceView, event)
        val touchWidth = if (sourceView is LiquidGlassOverlayView && sourceView.width > 0) sourceView.width else nav.width
        state.touchWidth = touchWidth
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (state.downTime == event.downTime && state.longPressRunnable != null) return false
                state.cancel(mainHandler)
                state.downTime = event.downTime
                state.downX = touchX
                state.downY = touchY
                state.dragIndex = selectedIndex(nav).toFloat()
                state.dragging = false
                state.longPressRunnable = Runnable {
                    startNavDrag(nav, state, state.downX)
                }
                mainHandler.postDelayed(state.longPressRunnable!!, ViewConfiguration.getLongPressTimeout().toLong())
                return ownTouchStream
            }

            MotionEvent.ACTION_MOVE -> {
                if (!state.dragging) {
                    val slop = ViewConfiguration.get(nav.context).scaledTouchSlop
                    val horizontal = abs(touchX - state.downX)
                    val vertical = abs(touchY - state.downY)
                    if (ownTouchStream && horizontal > slop && horizontal > vertical) {
                        startNavDrag(nav, state, touchX)
                    } else if (!ownTouchStream && (horizontal > slop || vertical > slop)) {
                        state.longPressRunnable?.let { mainHandler.removeCallbacks(it) }
                        state.longPressRunnable = null
                    }
                    return ownTouchStream || state.dragging
                }
                state.dragIndex = indexFromTouch(nav, touchX, state.touchWidth)
                invalidateBar(nav)
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                state.longPressRunnable?.let { mainHandler.removeCallbacks(it) }
                state.longPressRunnable = null
                if (!state.dragging) {
                    if (ownTouchStream && event.actionMasked == MotionEvent.ACTION_UP) {
                        val target = indexFromTouch(nav, touchX, state.touchWidth).roundToInt().coerceIn(0, max(0, itemCount(nav) - 1))
                        selectNavigationItem(nav, target)
                        state.dragIndex = target.toFloat()
                        invalidateBar(nav)
                        return true
                    }
                    return ownTouchStream
                }

                val shouldSelect = event.actionMasked == MotionEvent.ACTION_UP
                state.dragging = false
                if (shouldSelect) {
                    val target = state.dragIndex.roundToInt().coerceIn(0, max(0, itemCount(nav) - 1))
                    selectNavigationItem(nav, target)
                    state.dragIndex = target.toFloat()
                } else {
                    state.dragIndex = selectedIndex(nav).toFloat()
                }
                animatePress(nav, state, 0f)
                nav.parent?.requestDisallowInterceptTouchEvent(false)
                invalidateBar(nav)
                return true
            }
        }
        return false
    }

    private fun startNavDrag(nav: View, state: DragState, x: Float) {
        if (!featureState.liquidClass || nav !in styledNavs || !nav.isAttachedToWindowCompat()) return
        state.longPressRunnable?.let { mainHandler.removeCallbacks(it) }
        state.longPressRunnable = null
        if (!state.dragging) {
            runCatching { nav.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) }
        }
        state.dragging = true
        state.dragIndex = indexFromTouch(nav, x, state.touchWidth)
        nav.parent?.requestDisallowInterceptTouchEvent(true)
        animatePress(nav, state, 1f)
        invalidateBar(nav)
    }

    private fun navRelativeX(nav: View, sourceView: View, event: MotionEvent): Float {
        if (sourceView is LiquidGlassOverlayView) return event.x
        if (sourceView === nav) return event.x
        val navLocation = IntArray(2)
        return runCatching {
            nav.getLocationOnScreen(navLocation)
            event.rawX - navLocation[0]
        }.getOrElse { event.x }
    }

    private fun navRelativeY(nav: View, sourceView: View, event: MotionEvent): Float {
        if (sourceView is LiquidGlassOverlayView) return event.y
        if (sourceView === nav) return event.y
        val navLocation = IntArray(2)
        return runCatching {
            nav.getLocationOnScreen(navLocation)
            event.rawY - navLocation[1]
        }.getOrElse { event.y }
    }

    private fun animatePress(nav: View, state: DragState, target: Float) {
        state.pressAnimator?.cancel()
        val start = state.pressProgress
        state.pressAnimator = ValueAnimator.ofFloat(start, target).apply {
            duration = 180L
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                state.pressProgress = animator.animatedValue as Float
                invalidateBar(nav)
            }
            start()
        }
    }

    private fun invalidateBar(nav: View) {
        barDrawables[nav]?.invalidateSelf()
        overlayBars[nav]?.invalidate()
        nav.invalidate()
    }

    private fun barVisualState(nav: View): BarVisualState {
        val state = dragStates[nav]
        val count = itemCount(nav).coerceAtLeast(1)
        val selected = selectedIndex(nav).toFloat().coerceIn(0f, (count - 1).toFloat())
        val drag = state?.dragIndex?.coerceIn(0f, (count - 1).toFloat())
        return BarVisualState(
            itemCount = count,
            selectedIndex = drag ?: selected,
            pressProgress = state?.pressProgress ?: 0f
        )
    }

    private fun indexFromTouch(nav: View, x: Float, surfaceWidth: Int = nav.width): Float {
        val count = itemCount(nav).coerceAtLeast(1)
        val widthPixels = surfaceWidth.takeIf { it > 0 } ?: nav.resources.displayMetrics.widthPixels
        val edgeInset = min(widthPixels * 0.025f, dp(12f).toFloat())
        val width = (widthPixels - edgeInset * 2f).coerceAtLeast(1f)
        return ((x - edgeInset) / width * count - 0.5f).coerceIn(0f, (count - 1).toFloat())
    }

    private fun itemCount(nav: View): Int {
        val menuCount = menuItemCount(nav)
        if (menuCount > 0) return menuCount
        return countLikelyNavigationItems(nav).takeIf { it > 0 } ?: 4
    }

    private fun selectedIndex(nav: View): Int {
        val count = itemCount(nav).coerceAtLeast(1)
        val selectedId = callGetter(nav, "getSelectedItemId") as? Int
        if (selectedId != null) {
            menuIndexForItemId(nav, selectedId)?.let { return it.coerceIn(0, count - 1) }
        }

        val items = mutableListOf<View>()
        collectNavigationItems(nav, nav, items, 0)
        val selectedChildIndex = items.distinct().indexOfFirst { it.isSelected || it.isActivated }
        if (selectedChildIndex >= 0) return selectedChildIndex.coerceIn(0, count - 1)
        return 0
    }

    private fun selectNavigationItem(nav: View, index: Int) {
        val itemId = menuItemIdAt(nav, index)
        if (itemId != null) {
            callOptional(nav, "setSelectedItemId", itemId)
            return
        }

        val items = mutableListOf<View>()
        collectNavigationItems(nav, nav, items, 0)
        items.distinct().getOrNull(index)?.performClick()
    }

    private fun rememberNavState(nav: View) {
        if (originalNavStates.containsKey(nav)) return
        val params = nav.layoutParams
        val marginParams = params as? ViewGroup.MarginLayoutParams
        originalNavStates[nav] = NavOriginalState(
            background = nav.background,
            paddingLeft = nav.paddingLeft,
            paddingTop = nav.paddingTop,
            paddingRight = nav.paddingRight,
            paddingBottom = nav.paddingBottom,
            minimumHeight = nav.minimumHeight,
            elevation = nav.elevation,
            translationZ = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) nav.translationZ else 0f,
            clipToOutline = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) nav.clipToOutline else false,
            hasLayoutParams = params != null,
            layoutWidth = params?.width ?: ViewGroup.LayoutParams.MATCH_PARENT,
            layoutHeight = params?.height ?: ViewGroup.LayoutParams.WRAP_CONTENT,
            hasMargins = marginParams != null,
            leftMargin = marginParams?.leftMargin ?: 0,
            topMargin = marginParams?.topMargin ?: 0,
            rightMargin = marginParams?.rightMargin ?: 0,
            bottomMargin = marginParams?.bottomMargin ?: 0
        ).withNavigationProperties(
            itemTextColor = callGetter(nav, "getItemTextColor") as? ColorStateList,
            itemIconTintList = callGetter(nav, "getItemIconTintList") as? ColorStateList,
            itemRippleColor = callGetter(nav, "getItemRippleColor") as? ColorStateList,
            itemActiveIndicatorColor = callGetter(nav, "getItemActiveIndicatorColor") as? ColorStateList,
            itemActiveIndicatorEnabled = (callGetter(nav, "isItemActiveIndicatorEnabled") as? Boolean)
                ?: (callGetter(nav, "getItemActiveIndicatorEnabled") as? Boolean)
        )
    }

    private fun relaxOuterMargins(nav: View) {
        val params = nav.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        val horizontal = dp(8f)
        val bottom = dp(4f)
        var changed = false
        if (params.leftMargin < horizontal) {
            params.leftMargin = horizontal
            changed = true
        }
        if (params.rightMargin < horizontal) {
            params.rightMargin = horizontal
            changed = true
        }
        if (params.bottomMargin < bottom) {
            params.bottomMargin = bottom
            changed = true
        }
        if (params.height in 1 until dp(60f)) {
            params.height = dp(60f)
            changed = true
        }
        if (changed) {
            nav.layoutParams = params
        }
    }

    private fun applyNavigationBarReflectionStyle(nav: View) {
        callOptional(nav, "setItemTextColor", activeTint)
        callOptional(nav, "setItemIconTintList", activeTint)
        callOptional(nav, "setItemRippleColor", transparentTint)
        callOptional(nav, "setItemActiveIndicatorColor", transparentTint)
        callOptional(nav, "setItemActiveIndicatorEnabled", false)
        callOptional(nav, "setItemBackground", ColorDrawable(Color.TRANSPARENT))
        callOptional(nav, "setItemBackgroundResource", 0)
    }

    private fun styleNavSurroundings(nav: View) {
        (nav as? ViewGroup)?.let {
            it.clipChildren = false
            it.clipToPadding = false
        }
        var current = nav.parent as? View
        repeat(4) { depth ->
            val parent = current ?: return@repeat
            val resourceName = resourceEntryName(parent).orEmpty().lowercase()
            if (depth == 0 || resourceName == "bottom_nav_container" || resourceName.contains("bottom_nav")) {
                rememberAuxState(parent)
                parent.background = ColorDrawable(Color.TRANSPARENT)
                parent.setBackgroundColor(Color.TRANSPARENT)
                if (parent is ViewGroup) {
                    parent.clipChildren = false
                    parent.clipToPadding = false
                    hideBottomNavDividerChildren(parent)
                }
                parent.invalidate()
            }
            current = parent.parent as? View
        }
    }

    private fun installLiquidOverlay(
        nav: View,
        tabs: List<WhatsAppLiquidGlassTab> = cachedLiquidTabs(nav),
        selected: Int = selectedIndex(nav),
        height: Int = overlayHeight(nav)
    ): Boolean {
        val root = nav.rootView as? ViewGroup ?: return false
        val existing = overlayBars[nav]
        if (existing != null) {
            existing.update(tabs, selected, null)
            updateOverlayLayout(existing, height)
            existing.bringToFront()
            existing.invalidate()
            scheduleBackdropCapture(nav, root, existing, height, force = false)
            return true
        }

        val overlay = WhatsAppLiquidGlassComposeOverlay(nav.context)
        overlay.installViewTreeOwners(root)
        overlay.update(tabs, selected, null) { index ->
            selectNavigationItem(nav, index)
            dragStates.getOrPut(nav) { DragState() }.dragIndex = index.toFloat()
            overlay.update(cachedLiquidTabs(nav), index, null)
            scheduleBackdropCapture(nav, root, overlay, height, force = true)
        }
        overlay.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        root.addView(overlay, overlayLayoutParams(height))
        overlay.bringToFront()
        overlayBars[nav] = overlay
        scheduleBackdropCapture(nav, root, overlay, height, force = true)
        return true
    }

    private fun scheduleBackdropCapture(
        nav: View,
        root: ViewGroup,
        overlay: WhatsAppLiquidGlassComposeOverlay,
        height: Int,
        force: Boolean
    ) {
        val now = SystemClock.uptimeMillis()
        val last = synchronized(lastBackdropCaptureTimes) { lastBackdropCaptureTimes[nav] ?: 0L }
        if (!force && now - last < 900L) return
        synchronized(pendingBackdropCaptures) {
            if (!pendingBackdropCaptures.add(nav)) return
        }
        overlay.post {
            synchronized(pendingBackdropCaptures) { pendingBackdropCaptures.remove(nav) }
            if (!featureState.liquidClass || overlay.parent == null || !overlay.isAttachedToWindowCompat()) return@post
            val bitmap = captureBackdropBitmap(root, height) ?: return@post
            synchronized(lastBackdropCaptureTimes) { lastBackdropCaptureTimes[nav] = SystemClock.uptimeMillis() }
            overlay.update(cachedLiquidTabs(nav), selectedIndex(nav), bitmap)
        }
    }

    private fun overlayHeight(nav: View): Int {
        val navHeight = nav.height.takeIf { it > 0 } ?: nav.measuredHeight
        return max(navHeight, dp(124f))
    }

    private fun overlayLayoutParams(height: Int): ViewGroup.LayoutParams {
        return FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height).apply {
            gravity = Gravity.BOTTOM
        }
    }

    private fun updateOverlayLayout(overlay: View, height: Int) {
        val params = overlay.layoutParams
        if (params is FrameLayout.LayoutParams) {
            if (params.height != height || params.gravity != Gravity.BOTTOM) {
                params.height = height
                params.width = ViewGroup.LayoutParams.MATCH_PARENT
                params.gravity = Gravity.BOTTOM
                overlay.layoutParams = params
            }
        } else if (params != null && params.height != height) {
            params.height = height
            params.width = ViewGroup.LayoutParams.MATCH_PARENT
            overlay.layoutParams = params
        }
    }

    private fun hideNativeBottomNavSlot(nav: View) {
        val slot = findBottomNavContainer(nav) ?: nav
        val root = nav.rootView as? ViewGroup ?: return
        val height = overlayHeight(nav)
        synchronized(protectedBottomHeights) { protectedBottomHeights[root] = height }
        if (slot !== nav) {
            keepBottomSlotForActions(slot)
        }
        hideNativeNavigationVisuals(nav)
        scheduleActionButtonLift(root, height, immediate = true)
        listOf(120L, 320L, 700L, 1200L, 2000L, 3500L, 5500L).forEach { delay ->
            root.postDelayed({ if (featureState.liquidClass) scheduleActionButtonLift(root, height, immediate = true) }, delay)
        }
    }

    private fun keepBottomSlotForActions(slot: View) {
        rememberAuxState(slot)
        slot.visibility = View.VISIBLE
        slot.alpha = 1f
        slot.background = ColorDrawable(Color.TRANSPARENT)
        slot.setBackgroundColor(Color.TRANSPARENT)
        slot.minimumHeight = 0
        if (slot is ViewGroup) {
            slot.clipChildren = false
            slot.clipToPadding = false
            hideBottomNavDividerChildren(slot)
        }
        slot.invalidate()
        (slot.parent as? View)?.requestLayout()
    }

    private fun hideNativeNavigationVisuals(nav: View) {
        rememberAuxState(nav)
        nav.visibility = View.VISIBLE
        nav.alpha = 1f
        nav.background = ColorDrawable(Color.TRANSPARENT)
        nav.setBackgroundColor(Color.TRANSPARENT)
        if (nav is ViewGroup) {
            nav.clipChildren = false
            nav.clipToPadding = false
        }
        applyNavigationBarReflectionStyle(nav)
        hideNativeNavHighlighters(nav, hideNavigationItems = true)
        nav.invalidate()
    }

    private fun containsBottomActionsOutsideNav(root: ViewGroup, slot: View, nav: View, protectedHeight: Int): Boolean {
        val group = slot as? ViewGroup ?: return false
        for (index in 0 until group.childCount) {
            val child = group.getChildAt(index)
            if (child === nav || isAncestorOf(nav, child)) continue
            if (containsBottomActionSignal(root, child, protectedHeight, 0)) return true
        }
        return false
    }

    private fun containsBottomActionsInsideNav(root: ViewGroup, nav: View, protectedHeight: Int): Boolean {
        val group = nav as? ViewGroup ?: return false
        for (index in 0 until group.childCount) {
            val child = group.getChildAt(index)
            if (isLikelyNavigationItem(nav, child) || isNavigationChromeView(child)) continue
            if (containsBottomActionSignal(root, child, protectedHeight, 0)) return true
        }
        return false
    }

    private fun containsBottomActionSignal(root: ViewGroup, view: View, protectedHeight: Int, depth: Int): Boolean {
        if (depth > 5 || view.visibility != View.VISIBLE || view.width <= 0 || view.height <= 0) return false
        if (view is WhatsAppLiquidGlassComposeOverlay) return false
        if (isBottomActionCandidate(root, view, protectedHeight)) return true
        val group = view as? ViewGroup ?: return false
        for (index in 0 until group.childCount) {
            if (containsBottomActionSignal(root, group.getChildAt(index), protectedHeight, depth + 1)) return true
        }
        return false
    }

    private fun isNavigationChromeView(view: View): Boolean {
        val className = view.javaClass.name.lowercase()
        val resourceName = resourceEntryName(view).orEmpty().lowercase()
        return isNavigationLike(className, resourceName) ||
            resourceName in NAV_ITEM_RESOURCE_NAMES ||
            "active_indicator" in resourceName ||
            "activeindicator" in className
    }

    private fun forceHideNativeSlot(slot: View) {
        rememberAuxState(slot)
        slot.background = ColorDrawable(Color.TRANSPARENT)
        slot.setBackgroundColor(Color.TRANSPARENT)
        slot.minimumHeight = 0
        slot.alpha = 0f
        slot.layoutParams?.let { params ->
            var changed = false
            if (params.height != 0) {
                params.height = 0
                changed = true
            }
            if (params is ViewGroup.MarginLayoutParams) {
                if (params.topMargin != 0 || params.bottomMargin != 0) {
                    params.topMargin = 0
                    params.bottomMargin = 0
                    changed = true
                }
            }
            if (changed) slot.layoutParams = params
        }
        slot.visibility = View.GONE
        slot.requestLayout()
        (slot.parent as? View)?.requestLayout()
        slot.invalidate()
    }

    private fun scheduleActionButtonLift(root: ViewGroup, protectedHeight: Int, immediate: Boolean = false) {
        synchronized(pendingActionButtonLifts) {
            if (!pendingActionButtonLifts.add(root)) return
        }
        val work = Runnable {
            synchronized(pendingActionButtonLifts) { pendingActionButtonLifts.remove(root) }
            if (featureState.liquidClass && root.isAttachedToWindowCompat()) {
                liftBottomActionButtons(root, protectedHeight)
            }
        }
        if (immediate) root.post(work) else root.postDelayed(work, 40L)
    }

    private fun liftBottomActionButtons(root: ViewGroup, protectedHeight: Int = dp(124f)) {
        val rootHeight = root.height.takeIf { it > 0 } ?: return
        val targetBottom = rootHeight - protectedHeight - dp(18f)
        val candidates = mutableListOf<View>()
        collectFabCandidates(root, root, candidates, 0, protectedHeight)
        val rawMovers = candidates
            .map { movableActionContainer(root, it) }
            .distinct()
        val movers = rawMovers
            .filter { candidate -> rawMovers.none { other -> other !== candidate && isAncestorOf(other, candidate) } }
            .take(10)
        movers.forEach { view ->
            prepareActionButtonForOverlay(root, view)
            if (!originalFabTranslations.containsKey(view)) {
                originalFabTranslations[view] = view.translationY
            }
            if (isKnownBottomActionView(view)) {
                view.translationY = originalFabTranslations[view] ?: view.translationY
                view.requestLayout()
                return@forEach
            }
            val bottom = bottomInRoot(root, view) ?: return@forEach
            if (bottom <= targetBottom) return@forEach
            if (moveActionButtonWithMargin(view, protectedHeight)) {
                view.translationY = originalFabTranslations[view] ?: view.translationY
                view.bringToFront()
                view.requestLayout()
                return@forEach
            }
            val adjustment = targetBottom - bottom
            val baseTranslation = originalFabTranslations[view] ?: view.translationY
            view.translationY = baseTranslation + adjustment
            view.bringToFront()
            view.requestLayout()
        }
    }

    private fun prepareActionButtonForOverlay(root: ViewGroup, view: View) {
        var current: View? = view
        var depth = 0
        while (current != null && current !== root && depth < 8) {
            val parent = current.parent as? ViewGroup ?: break
            parent.clipChildren = false
            parent.clipToPadding = false
            if (current === view ||
                isKnownBottomActionResource(resourceEntryName(current).orEmpty().lowercase()) ||
                isKnownBottomActionClass(current.javaClass.name.lowercase()) ||
                isCompactBottomActionContainer(root, current)
            ) {
                current.bringToFront()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    current.translationZ = max(current.translationZ, dp(18f).toFloat())
                }
            }
            current = parent
            depth++
        }
        view.bringToFront()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            view.translationZ = max(view.translationZ, dp(24f).toFloat())
        }
    }

    private fun moveActionButtonWithMargin(view: View, protectedHeight: Int): Boolean {
        val params = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return false
        val frameParams = params as? FrameLayout.LayoutParams
        val gravity = frameParams?.gravity ?: Gravity.NO_GRAVITY
        val parentClassName = (view.parent as? View)?.javaClass?.name?.lowercase().orEmpty()
        val bottomAnchored = (frameParams != null &&
            ((gravity and Gravity.BOTTOM) == Gravity.BOTTOM ||
                (gravity and Gravity.CENTER_VERTICAL) == Gravity.CENTER_VERTICAL)) ||
            "coordinatorlayout" in parentClassName
        if (!bottomAnchored) return false
        val desiredBottom = protectedHeight + dp(16f)
        if (params.bottomMargin >= desiredBottom) return false
        rememberAuxState(view)
        params.bottomMargin = desiredBottom
        view.layoutParams = params
        return true
    }

    private fun collectFabCandidates(root: View, view: View, out: MutableList<View>, depth: Int, protectedHeight: Int) {
        if (depth > 12 || view.visibility != View.VISIBLE || view is WhatsAppLiquidGlassComposeOverlay) return
        if (isBottomActionCandidate(root, view, protectedHeight)) {
            out += view
            if (view is ViewGroup && countBottomActionChildren(root, view, protectedHeight) >= 2) return
        }
        val group = view as? ViewGroup ?: return
        for (index in 0 until group.childCount) {
            collectFabCandidates(root, group.getChildAt(index), out, depth + 1, protectedHeight)
        }
    }

    private fun movableActionContainer(root: View, view: View): View {
        if (isKnownBottomActionView(view)) return view
        var candidate = view
        var parent = view.parent as? View
        while (parent != null && parent !== root && isCompactBottomActionContainer(root, parent)) {
            if (isKnownBottomActionView(parent)) return parent
            candidate = parent
            parent = parent.parent as? View
        }
        return candidate
    }

    private fun isKnownBottomActionView(view: View): Boolean {
        return isKnownBottomActionResource(resourceEntryName(view).orEmpty().lowercase()) ||
            isKnownBottomActionClass(view.javaClass.name.lowercase())
    }

    private fun isCompactBottomActionContainer(root: View, view: View): Boolean {
        if (view.visibility != View.VISIBLE || view.width <= 0 || view.height <= 0) return false
        if (view is WhatsAppLiquidGlassComposeOverlay) return false
        val className = view.javaClass.name.lowercase()
        val resourceName = resourceEntryName(view).orEmpty().lowercase()
        if (isNavigationLike(className, resourceName)) return false
        val rootWidth = root.width.takeIf { it > 0 } ?: return false
        val rootHeight = root.height.takeIf { it > 0 } ?: return false
        val rootLocation = IntArray(2)
        val viewLocation = IntArray(2)
        return runCatching {
            root.getLocationOnScreen(rootLocation)
            view.getLocationOnScreen(viewLocation)
            val left = viewLocation[0] - rootLocation[0]
            val top = viewLocation[1] - rootLocation[1]
            val right = left + view.width
            val bottom = top + view.height
            val sizeMin = min(view.width, view.height)
            val sizeMax = max(view.width, view.height)
            right > rootWidth * 0.12f &&
                left < rootWidth * 0.95f &&
                bottom > rootHeight * 0.45f &&
                top > rootHeight * 0.35f &&
                sizeMin >= dp(24f) &&
                view.width <= rootWidth * 0.82f &&
                view.height <= dp(260f) &&
                sizeMax <= max(dp(320f).toFloat(), rootWidth * 0.58f)
        }.getOrDefault(false)
    }

    private fun isAncestorOf(ancestor: View, descendant: View): Boolean {
        var current = descendant.parent as? View
        while (current != null) {
            if (current === ancestor) return true
            current = current.parent as? View
        }
        return false
    }

    private fun isBottomActionCandidate(root: View, view: View, protectedHeight: Int): Boolean {
        if (view.width <= 0 || view.height <= 0) return false
        val rootWidth = root.width.takeIf { it > 0 } ?: return false
        val rootHeight = root.height.takeIf { it > 0 } ?: return false
        val rootLocation = IntArray(2)
        val viewLocation = IntArray(2)
        runCatching { root.getLocationOnScreen(rootLocation) }
        runCatching { view.getLocationOnScreen(viewLocation) }
        val left = viewLocation[0] - rootLocation[0]
        val top = viewLocation[1] - rootLocation[1]
        val right = left + view.width
        val bottom = top + view.height
        val sizeMin = min(view.width, view.height)
        val sizeMax = max(view.width, view.height)
        val className = view.javaClass.name.lowercase()
        val resourceName = resourceEntryName(view).orEmpty().lowercase()
        val knownAction = isKnownBottomActionResource(resourceName) || isKnownBottomActionClass(className)
        val actionLike = view.isClickable ||
            view.isLongClickable ||
            knownAction ||
            "button" in className ||
            "floating" in className ||
            "fab" in className ||
            "progress" in className ||
            "action" in className ||
            "create" in className ||
            "compose" in className ||
            "camera" in className ||
            "meta" in className ||
            "ai" in className ||
            "button" in resourceName ||
            "floating" in resourceName ||
            "fab" in resourceName ||
            "progress" in resourceName ||
            "action" in resourceName ||
            "create" in resourceName ||
            "compose" in resourceName ||
            "camera" in resourceName ||
            "meta" in resourceName ||
            "ai" in resourceName ||
            "new" in resourceName ||
            countBottomActionChildren(root, view, protectedHeight) >= 2
        val navigationLike = isNavigationLike(className, resourceName)
        val bottomBandTop = rootHeight - protectedHeight - dp(170f)
        val compactSingle = sizeMin >= dp(28f) && sizeMax <= dp(156f)
        val compactCluster = view is ViewGroup &&
            sizeMin >= dp(28f) &&
            view.width <= rootWidth * 0.82f &&
            view.height <= dp(230f) &&
            sizeMax <= max(dp(360f).toFloat(), rootWidth * 0.62f)
        return actionLike &&
            (!navigationLike || knownAction) &&
            !isGloballyHiddenNativeSlot(view) &&
            right > rootWidth * 0.10f &&
            left < rootWidth * 0.96f &&
            bottom > bottomBandTop &&
            top > rootHeight * 0.36f &&
            (compactSingle || compactCluster)
    }

    private fun countBottomActionChildren(root: View, view: View, protectedHeight: Int, depth: Int = 0): Int {
        val group = view as? ViewGroup ?: return 0
        if (depth > 3) return 0
        var count = 0
        for (index in 0 until group.childCount) {
            val child = group.getChildAt(index)
            if (child.visibility != View.VISIBLE || child.width <= 0 || child.height <= 0) continue
            if (isSimpleBottomActionChild(root, child, protectedHeight)) {
                count++
                if (count >= 3) return count
            }
            count += countBottomActionChildren(root, child, protectedHeight, depth + 1)
            if (count >= 3) return count
        }
        return count
    }

    private fun isSimpleBottomActionChild(root: View, view: View, protectedHeight: Int): Boolean {
        val rootHeight = root.height.takeIf { it > 0 } ?: return false
        val bottom = bottomInRoot(root, view) ?: return false
        if (bottom < rootHeight - protectedHeight - dp(170f)) return false
        val className = view.javaClass.name.lowercase()
        val resourceName = resourceEntryName(view).orEmpty().lowercase()
        val knownAction = isKnownBottomActionResource(resourceName) || isKnownBottomActionClass(className)
        if ((!knownAction && isNavigationLike(className, resourceName)) || isGloballyHiddenNativeSlot(view)) return false
        val sizeMin = min(view.width, view.height)
        val sizeMax = max(view.width, view.height)
        val actionNamed = knownAction ||
            "button" in className ||
            "floating" in className ||
            "fab" in className ||
            "action" in className ||
            "create" in className ||
            "compose" in className ||
            "camera" in className ||
            "meta" in className ||
            "ai" in className ||
            "button" in resourceName ||
            "floating" in resourceName ||
            "fab" in resourceName ||
            "action" in resourceName ||
            "create" in resourceName ||
            "compose" in resourceName ||
            "camera" in resourceName ||
            "meta" in resourceName ||
            "ai" in resourceName ||
            "new" in resourceName
        return (view.isClickable || view.isLongClickable || actionNamed) &&
            sizeMin >= dp(24f) &&
            sizeMax <= dp(156f)
    }

    private fun isKnownBottomActionResource(resourceName: String): Boolean {
        if (resourceName in BOTTOM_ACTION_RESOURCE_NAMES) return true
        return resourceName.contains("_fab") ||
            resourceName.contains("fab_") ||
            resourceName.contains("floating_cta") ||
            resourceName.contains("create_status_button") ||
            resourceName.contains("camera_button") ||
            resourceName.contains("ai_reply_fab") ||
            resourceName.contains("meta_ai_widget")
    }

    private fun isKnownBottomActionClass(className: String): Boolean {
        return "wdsfab" in className ||
            "wdsextendedfab" in className ||
            "floatingactionbutton" in className
    }

    private fun isNavigationLike(className: String, resourceName: String): Boolean {
        return "navigation" in className ||
            "navigation" in resourceName ||
            "bottom_nav" in resourceName ||
            "liquid" in className
    }

    private fun bottomInRoot(root: View, view: View): Int? {
        val rootLocation = IntArray(2)
        val viewLocation = IntArray(2)
        return runCatching {
            root.getLocationOnScreen(rootLocation)
            view.getLocationOnScreen(viewLocation)
            viewLocation[1] - rootLocation[1] + view.height
        }.getOrNull()
    }

    private fun captureBackdropBitmap(root: ViewGroup, requestedHeight: Int): Bitmap? {
        val width = root.width.takeIf { it > 0 } ?: return null
        val rootHeight = root.height.takeIf { it > 0 } ?: return null
        val height = min(rootHeight, max(requestedHeight, dp(124f)))
        return runCatching {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
                val canvas = Canvas(bitmap)
                canvas.translate(0f, -(rootHeight - height).toFloat())
                root.draw(canvas)
            }
        }.getOrNull()
    }

    private fun findBottomNavContainer(nav: View): View? {
        var current = nav.parent as? View
        repeat(5) {
            val candidate = current ?: return null
            if (resourceEntryName(candidate).orEmpty().lowercase() == "bottom_nav_container") {
                return candidate
            }
            current = candidate.parent as? View
        }
        return null
    }

    private fun liquidTabs(nav: View): List<WhatsAppLiquidGlassTab> {
        val menuCount = menuItemCount(nav)
        if (menuCount > 0) {
            val tabs = (0 until menuCount).mapNotNull { index ->
                val item = menuItemAt(nav, index) ?: return@mapNotNull null
                val title = callAnyGetter(item, "getTitle")?.toString().orEmpty()
                val icon = callAnyGetter(item, "getIcon") as? Drawable
                WhatsAppLiquidGlassTab(title.ifBlank { fallbackTabTitle(index) }, cloneDrawable(icon, nav))
            }
            if (tabs.isNotEmpty()) return tabs
        }

        val labels = mutableListOf<String>()
        collectText(nav, labels, 0, 6)
        val normalized = labels
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.normalizeUiText() }
            .filter { isKnownBottomTabText(it.normalizeUiText()) }
        if (normalized.isNotEmpty()) {
            return normalized.map { WhatsAppLiquidGlassTab(it, null) }
        }
        return (0 until itemCount(nav).coerceAtLeast(1)).map { WhatsAppLiquidGlassTab(fallbackTabTitle(it), null) }
    }

    private fun cachedLiquidTabs(nav: View): List<WhatsAppLiquidGlassTab> {
        return liquidTabCache.getOrPut(nav) { liquidTabs(nav) }
    }

    private fun cloneDrawable(icon: Drawable?, owner: View): Drawable? {
        return runCatching {
            icon?.constantState?.newDrawable(owner.resources)?.mutate()
        }.getOrNull()
    }

    private fun fallbackTabTitle(index: Int): String {
        return when (index) {
            0 -> "Chats"
            1 -> "Updates"
            2 -> "Communities"
            3 -> "Calls"
            else -> ""
        }
    }

    private fun hideBottomNavDividerChildren(parent: ViewGroup) {
        for (index in 0 until parent.childCount) {
            val child = parent.getChildAt(index)
            val resourceName = resourceEntryName(child).orEmpty().lowercase()
            if (resourceName == "bottom_nav_divider" || resourceName.endsWith("_divider")) {
                rememberAuxState(child)
                child.alpha = 0f
                child.visibility = View.GONE
                child.background = ColorDrawable(Color.TRANSPARENT)
                child.invalidate()
            }
        }
    }

    private fun hideNativeNavHighlighters(nav: View, hideNavigationItems: Boolean = true) {
        callOptional(nav, "setItemActiveIndicatorColor", transparentTint)
        callOptional(nav, "setItemActiveIndicatorEnabled", false)
        callOptional(nav, "setItemRippleColor", transparentTint)
        callOptional(nav, "setItemBackground", ColorDrawable(Color.TRANSPARENT))
        callOptional(nav, "setItemBackgroundResource", 0)

        val descendants = mutableListOf<View>()
        collectDescendants(nav, descendants, 0, 8)
        descendants.distinct().forEach { child ->
            val resourceName = resourceEntryName(child).orEmpty().lowercase()
            val className = child.javaClass.name.lowercase()
            if ("active_indicator" in resourceName || "activeindicator" in className) {
                rememberAuxState(child)
                child.alpha = 0f
                child.visibility = View.INVISIBLE
                child.background = ColorDrawable(Color.TRANSPARENT)
                child.invalidate()
            } else if (hideNavigationItems && isLikelyNavigationItem(nav, child)) {
                rememberItemState(child)
                hideNativeNavigationItem(child)
                child.background = ColorDrawable(Color.TRANSPARENT)
                child.invalidate()
            }
        }
    }

    private fun hideNativeNavigationItem(item: View) {
        rememberAuxState(item)
        item.alpha = 0f
        item.visibility = View.INVISIBLE
        item.background = ColorDrawable(Color.TRANSPARENT)
        (item as? ViewGroup)?.let { group ->
            for (index in 0 until group.childCount) {
                val child = group.getChildAt(index)
                rememberAuxState(child)
                child.alpha = 0f
                child.visibility = View.INVISIBLE
                child.background = ColorDrawable(Color.TRANSPARENT)
            }
        }
    }

    private fun collectDescendants(view: View, out: MutableList<View>, depth: Int, maxDepth: Int) {
        if (depth >= maxDepth) return
        val group = view as? ViewGroup ?: return
        for (index in 0 until group.childCount) {
            val child = group.getChildAt(index)
            out += child
            collectDescendants(child, out, depth + 1, maxDepth)
        }
    }

    private fun installDragTouchSurfaces(nav: View) {
        val surfaces = mutableListOf<View>()
        surfaces += nav
        val descendants = mutableListOf<View>()
        collectDescendants(nav, descendants, 0, 6)
        descendants.forEach { child ->
            if (isNavigationTouchSurface(nav, child)) {
                surfaces += child
            }
        }
        surfaces.distinct().take(64).forEach { surface ->
            installDragTouchSurface(nav, surface)
        }
    }

    private fun installDragTouchSurface(nav: View, surface: View) {
        if (originalTouchListeners.containsKey(surface)) return
        val previous = currentOnTouchListener(surface)
        originalTouchListeners[surface] = previous
        surface.setOnTouchListener { touchedView, event ->
            val consumed = handleNavDragEvent(nav, event, touchedView, ownTouchStream = true)
            if (consumed) true else previous?.onTouch(touchedView, event) ?: false
        }
    }

    private fun isNavigationTouchSurface(nav: View, view: View): Boolean {
        val resourceName = resourceEntryName(view).orEmpty().lowercase()
        if (resourceName == "navigation_bar_item_icon_container") return false
        if (resourceName == "navigation_bar_item_active_indicator_view") return false
        if (resourceName.contains("navigation_bar_item") && view is ViewGroup) return true
        if (isLikelyNavigationItem(nav, view)) return true
        return view.isClickable && itemSizeMatches(nav, view)
    }

    private fun currentOnTouchListener(view: View): View.OnTouchListener? {
        return runCatching {
            val listenerInfoField = View::class.java.getDeclaredField("mListenerInfo")
            listenerInfoField.isAccessible = true
            val listenerInfo = listenerInfoField.get(view) ?: return@runCatching null
            val onTouchField = listenerInfo.javaClass.getDeclaredField("mOnTouchListener")
            onTouchField.isAccessible = true
            onTouchField.get(listenerInfo) as? View.OnTouchListener
        }.getOrNull()
    }

    private fun styleNavigationItems(nav: View) {
        val items = mutableListOf<View>()
        collectNavigationItems(nav, nav, items, 0)
        items.distinct().take(8).forEach { item ->
            rememberItemState(item)
            item.background = liquidTabStateDrawable()
            item.setPadding(
                max(item.paddingLeft, dp(4f)),
                max(item.paddingTop, dp(2f)),
                max(item.paddingRight, dp(4f)),
                max(item.paddingBottom, dp(2f))
            )
            item.invalidate()
        }
    }

    private fun collectNavigationItems(nav: View, view: View, out: MutableList<View>, depth: Int) {
        if (depth > 5) return
        if (view !== nav && isLikelyNavigationItem(nav, view)) {
            out += view
            return
        }
        val group = view as? ViewGroup ?: return
        for (index in 0 until group.childCount) {
            collectNavigationItems(nav, group.getChildAt(index), out, depth + 1)
        }
    }

    private fun isLikelyNavigationItem(nav: View, view: View): Boolean {
        if (view !is ViewGroup || view.visibility != View.VISIBLE || view.width <= 0 || view.height <= 0) return false
        val className = view.javaClass.name.lowercase()
        val resourceName = resourceEntryName(view).orEmpty().lowercase()
        if ("navigationbaritemview" in className || "bottomnavigationitemview" in className) return true
        if ("navigation_bar_item" in resourceName || hasNavigationItemResource(view, 0)) {
            return itemSizeMatches(nav, view)
        }

        val labels = mutableListOf<String>()
        collectText(view, labels, 0, 8)
        if (!labels.map { it.normalizeUiText() }.any(::isKnownBottomTabText)) return false
        return itemSizeMatches(nav, view)
    }

    private fun itemSizeMatches(nav: View, item: View): Boolean {
        val navWidth = nav.width.takeIf { it > 0 } ?: return false
        val navHeight = nav.height.takeIf { it > 0 } ?: return false
        return item.width in (navWidth / 9)..(navWidth / 2) &&
            item.height >= navHeight * 0.42f &&
            item.height <= navHeight * 1.2f
    }

    private fun rememberItemState(item: View) {
        if (originalItemStates.containsKey(item)) return
        originalItemStates[item] = ItemOriginalState(
            background = item.background,
            paddingLeft = item.paddingLeft,
            paddingTop = item.paddingTop,
            paddingRight = item.paddingRight,
            paddingBottom = item.paddingBottom
        )
    }

    private fun rememberAuxState(view: View) {
        if (originalAuxStates.containsKey(view)) return
        val params = view.layoutParams
        val marginParams = params as? ViewGroup.MarginLayoutParams
        originalAuxStates[view] = AuxOriginalState(
            background = view.background,
            visibility = view.visibility,
            alpha = view.alpha,
            minimumHeight = view.minimumHeight,
            paddingLeft = view.paddingLeft,
            paddingTop = view.paddingTop,
            paddingRight = view.paddingRight,
            paddingBottom = view.paddingBottom,
            hasLayoutParams = params != null,
            layoutWidth = params?.width ?: ViewGroup.LayoutParams.MATCH_PARENT,
            layoutHeight = params?.height ?: ViewGroup.LayoutParams.WRAP_CONTENT,
            hasMargins = marginParams != null,
            leftMargin = marginParams?.leftMargin ?: 0,
            topMargin = marginParams?.topMargin ?: 0,
            rightMargin = marginParams?.rightMargin ?: 0,
            bottomMargin = marginParams?.bottomMargin ?: 0
        )
    }

    private fun liquidTabStateDrawable(): Drawable {
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_checked), LiquidGlassTabDrawable(density, selected = true))
            addState(intArrayOf(android.R.attr.state_selected), LiquidGlassTabDrawable(density, selected = true))
            addState(intArrayOf(android.R.attr.state_activated), LiquidGlassTabDrawable(density, selected = true))
            addState(intArrayOf(android.R.attr.state_pressed), LiquidGlassTabDrawable(density, selected = false, pressed = true))
            addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
        }
    }

    private fun restoreAll(reason: String) {
        val navs = synchronized(originalNavStates) { originalNavStates.keys.toList() }
        navs.forEach { restoreNav(it) }
        val items = synchronized(originalItemStates) { originalItemStates.keys.toList() }
        items.forEach { restoreItem(it) }
        val auxes = synchronized(originalAuxStates) { originalAuxStates.map { it.key to it.value } }
        auxes.forEach { (view, state) -> restoreAux(view, state) }
        val touchSurfaces = synchronized(originalTouchListeners) { originalTouchListeners.keys.toList() }
        touchSurfaces.forEach { restoreTouchListener(it) }
        val fabTranslations = synchronized(originalFabTranslations) { originalFabTranslations.map { it.key to it.value } }
        fabTranslations.forEach { (view, translationY) ->
            runCatching { view.translationY = translationY }
        }
        synchronized(dragStates) {
            dragStates.values.forEach { it.cancel(mainHandler) }
            dragStates.clear()
        }
        synchronized(pendingNavStylePosts) { pendingNavStylePosts.clear() }
        synchronized(pendingBackdropCaptures) { pendingBackdropCaptures.clear() }
        synchronized(pendingActionButtonLifts) { pendingActionButtonLifts.clear() }
        synchronized(lastBackdropCaptureTimes) { lastBackdropCaptureTimes.clear() }
        synchronized(protectedBottomHeights) { protectedBottomHeights.clear() }
        synchronized(styledNavs) { styledNavs.clear() }
        synchronized(styledNavByRoot) { styledNavByRoot.clear() }
        synchronized(barDrawables) { barDrawables.clear() }
        val overlays = synchronized(overlayBars) { overlayBars.values.toList() }
        overlays.forEach { overlay -> (overlay.parent as? ViewGroup)?.removeView(overlay) }
        synchronized(overlayBars) { overlayBars.clear() }
        synchronized(liquidTabCache) { liquidTabCache.clear() }
        synchronized(globallyHiddenNativeSlots) { globallyHiddenNativeSlots.clear() }
        synchronized(originalNavStates) { originalNavStates.clear() }
        synchronized(originalItemStates) { originalItemStates.clear() }
        synchronized(originalAuxStates) { originalAuxStates.clear() }
        synchronized(originalTouchListeners) { originalTouchListeners.clear() }
        synchronized(originalFabTranslations) { originalFabTranslations.clear() }
        if (navs.isNotEmpty() || items.isNotEmpty() || auxes.isNotEmpty() || touchSurfaces.isNotEmpty()) {
            log("Restored WhatsApp bottom bar liquid glass styling for $reason")
        }
    }

    private fun restoreStyledViewIfNeeded(view: View) {
        findStyledAncestor(view)?.let { nav ->
            restoreNav(nav)
            synchronized(styledNavs) { styledNavs.remove(nav) }
        }
        originalItemStates.remove(view)?.let { state ->
            restoreItem(view, state)
        }
    }

    private fun findStyledAncestor(view: View): View? {
        var current: View? = view
        repeat(8) {
            val candidate = current ?: return@repeat
            if (originalNavStates.containsKey(candidate)) return candidate
            current = candidate.parent as? View
        }
        return null
    }

    private fun restoreNav(nav: View) {
        synchronized(globallyStyledNavs) { globallyStyledNavs.remove(nav) }
        dragStates.remove(nav)?.cancel(mainHandler)
        barDrawables.remove(nav)
        liquidTabCache.remove(nav)
        removeLiquidOverlay(nav)
        restoreTouchSurfacesFor(nav)
        val state = originalNavStates.remove(nav) ?: return
        runCatching {
            nav.background = state.background
            nav.setPadding(state.paddingLeft, state.paddingTop, state.paddingRight, state.paddingBottom)
            nav.minimumHeight = state.minimumHeight
            nav.elevation = state.elevation
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                nav.translationZ = state.translationZ
                nav.clipToOutline = state.clipToOutline
            }
            val params = nav.layoutParams
            if (state.hasLayoutParams && params != null) {
                params.width = state.layoutWidth
                params.height = state.layoutHeight
                if (params is ViewGroup.MarginLayoutParams && state.hasMargins) {
                    params.leftMargin = state.leftMargin
                    params.topMargin = state.topMargin
                    params.rightMargin = state.rightMargin
                    params.bottomMargin = state.bottomMargin
                }
                nav.layoutParams = params
            }
            state.itemTextColor?.let { callOptional(nav, "setItemTextColor", it) }
            state.itemIconTintList?.let { callOptional(nav, "setItemIconTintList", it) }
            state.itemRippleColor?.let { callOptional(nav, "setItemRippleColor", it) }
            state.itemActiveIndicatorColor?.let { callOptional(nav, "setItemActiveIndicatorColor", it) }
            state.itemActiveIndicatorEnabled?.let { callOptional(nav, "setItemActiveIndicatorEnabled", it) }
            nav.invalidate()
        }.onFailure { throwable ->
            log("Failed to restore liquid glass nav state: ${throwable.message}")
        }
    }

    private fun removeLiquidOverlay(nav: View) {
        val overlay = overlayBars.remove(nav) ?: return
        val root = overlay.rootView as? View
        synchronized(pendingBackdropCaptures) { pendingBackdropCaptures.remove(nav) }
        synchronized(lastBackdropCaptureTimes) { lastBackdropCaptureTimes.remove(nav) }
        if (root != null) {
            synchronized(pendingActionButtonLifts) { pendingActionButtonLifts.remove(root) }
            synchronized(protectedBottomHeights) { protectedBottomHeights.remove(root) }
        }
        runCatching {
            (overlay.parent as? ViewGroup)?.removeView(overlay)
        }
    }

    private fun restoreTouchSurfacesFor(nav: View) {
        restoreTouchListener(nav)
        val descendants = mutableListOf<View>()
        collectDescendants(nav, descendants, 0, 6)
        descendants.forEach { restoreTouchListener(it) }
    }

    private fun restoreTouchListener(view: View) {
        val hadOriginal = synchronized(originalTouchListeners) { originalTouchListeners.containsKey(view) }
        if (!hadOriginal) return
        val original = synchronized(originalTouchListeners) { originalTouchListeners.remove(view) }
        runCatching {
            view.setOnTouchListener(original)
        }
    }

    private fun restoreAux(view: View) {
        val state = originalAuxStates.remove(view) ?: return
        restoreAux(view, state)
    }

    private fun restoreAux(view: View, state: AuxOriginalState) {
        synchronized(globallyHiddenNativeSlots) { globallyHiddenNativeSlots.remove(view) }
        runCatching {
            view.background = state.background
            view.visibility = state.visibility
            view.alpha = state.alpha
            view.minimumHeight = state.minimumHeight
            view.setPadding(state.paddingLeft, state.paddingTop, state.paddingRight, state.paddingBottom)
            val params = view.layoutParams
            if (state.hasLayoutParams && params != null) {
                params.width = state.layoutWidth
                params.height = state.layoutHeight
                if (params is ViewGroup.MarginLayoutParams && state.hasMargins) {
                    params.leftMargin = state.leftMargin
                    params.topMargin = state.topMargin
                    params.rightMargin = state.rightMargin
                    params.bottomMargin = state.bottomMargin
                }
                view.layoutParams = params
            }
            view.requestLayout()
            (view.parent as? View)?.requestLayout()
            view.invalidate()
        }
    }

    private fun restoreItem(item: View) {
        val state = originalItemStates.remove(item) ?: return
        restoreItem(item, state)
    }

    private fun restoreItem(item: View, state: ItemOriginalState) {
        runCatching {
            item.background = state.background
            item.setPadding(state.paddingLeft, state.paddingTop, state.paddingRight, state.paddingBottom)
            item.invalidate()
        }
    }

    private fun hasNearbyBottomNavigationSignal(view: View): Boolean {
        return hasDirectBottomNavigationSignal(view)
    }

    private fun hasDirectBottomNavigationSignal(view: View): Boolean {
        val className = view.javaClass.name.lowercase()
        if ("wdsbottombar" in className ||
            "bottomnavigation" in className ||
            "navigationbarview" in className
        ) {
            return true
        }
        if (view.id == View.NO_ID) return false
        val resourceName = resourceEntryName(view).orEmpty().lowercase()
        return resourceName == "bottom_nav" ||
            resourceName == "bottom_nav_container" ||
            resourceName == "bottom_nav_divider" ||
            resourceName.startsWith("bottom_nav") ||
            "bottom_bar" in resourceName
    }

    private fun isStrictBottomNavigationHost(view: View): Boolean {
        val className = view.javaClass.name.lowercase()
        val resourceName = resourceEntryName(view).orEmpty().lowercase()
        return "wdsbottombar" in className ||
            "bottomnavigation" in className ||
            "navigationbarview" in className ||
            resourceName == "bottom_nav" ||
            (("bottom" in className || "navigation" in className || "bottom_nav" in resourceName) && hasMenuSurface(view))
    }

    private fun isStructuralBottomNavigationHost(view: View): Boolean {
        if (isStrictBottomNavigationHost(view)) return bottomNavigationScore(view) >= 92
        val className = view.javaClass.name
        val resourceName = resourceEntryName(view).orEmpty().lowercase()
        if (className == "android.widget.LinearLayout" || resourceName == "bottom_nav_container") return false
        if (!hasNavigationItemResource(view, 0)) return false
        return countLikelyNavigationItems(view) >= 3 && bottomNavigationScore(view) >= 54
    }

    private fun hasMenuSurface(view: View): Boolean {
        return menuItemCount(view) in 3..7
    }

    private fun menuItemCount(view: View): Int {
        val menu = callGetter(view, "getMenu") ?: return 0
        return runCatching {
            val sizeMethod = menu.javaClass.methods.firstOrNull {
                it.name == "size" && it.parameterTypes.isEmpty()
            } ?: return@runCatching 0
            sizeMethod.invoke(menu) as? Int ?: 0
        }.getOrDefault(0)
    }

    private fun menuItemIdAt(view: View, index: Int): Int? {
        val item = menuItemAt(view, index) ?: return null
        return runCatching {
            val idMethod = item.javaClass.methods.firstOrNull {
                it.name == "getItemId" && it.parameterTypes.isEmpty()
            } ?: return@runCatching null
            idMethod.invoke(item) as? Int
        }.getOrNull()
    }

    private fun menuIndexForItemId(view: View, itemId: Int): Int? {
        val count = menuItemCount(view)
        for (index in 0 until count) {
            if (menuItemIdAt(view, index) == itemId) return index
        }
        return null
    }

    private fun menuItemAt(view: View, index: Int): Any? {
        val menu = callGetter(view, "getMenu") ?: return null
        return runCatching {
            val itemMethod = menu.javaClass.methods.firstOrNull {
                it.name == "getItem" && it.parameterTypes.size == 1 &&
                    it.parameterTypes[0] == java.lang.Integer.TYPE
            } ?: return@runCatching null
            itemMethod.invoke(menu, index)
        }.getOrNull()
    }

    private fun countLikelyNavigationItems(view: View): Int {
        val items = mutableListOf<View>()
        collectNavigationItems(view, view, items, 0)
        return items.distinct().size
    }

    private fun hasNavigationItemResource(view: View, depth: Int): Boolean {
        if (depth > 5) return false
        val resourceName = resourceEntryName(view).orEmpty()
        if (resourceName in NAV_ITEM_RESOURCE_NAMES || resourceName.contains("navigation_bar_item")) return true
        val group = view as? ViewGroup ?: return false
        for (index in 0 until group.childCount) {
            if (hasNavigationItemResource(group.getChildAt(index), depth + 1)) return true
        }
        return false
    }

    private fun resourceEntryName(view: View): String? {
        val id = view.id
        if (id == View.NO_ID) return null
        return runCatching { view.resources.getResourceEntryName(id) }.getOrNull()
    }

    private fun collectText(view: View, out: MutableList<String>, depth: Int, maxDepth: Int) {
        if (depth > maxDepth) return
        if (view is TextView) {
            view.text?.toString()?.takeIf { it.isNotBlank() }?.let { out += it }
            view.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { out += it }
        } else {
            view.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { out += it }
        }
        val group = view as? ViewGroup ?: return
        for (index in 0 until group.childCount) {
            collectText(group.getChildAt(index), out, depth + 1, maxDepth)
        }
    }

    private fun callOptional(target: View, name: String, value: Any?) {
        runCatching {
            val method = findCompatibleSingleArgMethod(target.javaClass, name, value) ?: return
            method.isAccessible = true
            method.invoke(target, value)
        }
    }

    private fun callOptionalTyped(target: View, name: String, parameterType: Class<*>, value: Any?) {
        runCatching {
            val method = target.javaClass.methods.firstOrNull {
                it.name == name &&
                    it.parameterTypes.size == 1 &&
                    it.parameterTypes[0].isAssignableFrom(parameterType)
            } ?: target.javaClass.declaredMethods.firstOrNull {
                it.name == name &&
                    it.parameterTypes.size == 1 &&
                    it.parameterTypes[0].isAssignableFrom(parameterType)
            } ?: return
            method.isAccessible = true
            method.invoke(target, value)
        }
    }

    private fun callGetter(target: View, name: String): Any? {
        return runCatching {
            val method = target.javaClass.methods.firstOrNull {
                it.name == name && it.parameterTypes.isEmpty()
            } ?: target.javaClass.declaredMethods.firstOrNull {
                it.name == name && it.parameterTypes.isEmpty()
            } ?: return@runCatching null
            method.isAccessible = true
            method.invoke(target)
        }.getOrNull()
    }

    private fun callAnyGetter(target: Any, name: String): Any? {
        return runCatching {
            val method = target.javaClass.methods.firstOrNull {
                it.name == name && it.parameterTypes.isEmpty()
            } ?: target.javaClass.declaredMethods.firstOrNull {
                it.name == name && it.parameterTypes.isEmpty()
            } ?: return@runCatching null
            method.isAccessible = true
            method.invoke(target)
        }.getOrNull()
    }

    private fun findCompatibleSingleArgMethod(clazz: Class<*>, name: String, value: Any?): Method? {
        return clazz.methods.firstOrNull { method ->
            method.name == name &&
                method.parameterTypes.size == 1 &&
                (value == null || isCompatibleParameter(method.parameterTypes[0], value))
        } ?: clazz.declaredMethods.firstOrNull { method ->
            method.name == name &&
                method.parameterTypes.size == 1 &&
                (value == null || isCompatibleParameter(method.parameterTypes[0], value))
        }
    }

    private fun isCompatibleParameter(type: Class<*>, value: Any): Boolean {
        return when {
            type.isPrimitive && type == java.lang.Boolean.TYPE -> value is Boolean
            type.isPrimitive && type == java.lang.Integer.TYPE -> value is Int
            else -> type.isAssignableFrom(value.javaClass)
        }
    }

    private fun View.isAttachedToWindowCompat(): Boolean {
        return isAttachedToWindow
    }

    private fun dp(value: Float): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value,
            androidContext.resources.displayMetrics
        ).roundToInt()
    }

    private fun String.normalizeUiText(): String {
        return trim()
            .replace(Regex("\\s+"), " ")
            .lowercase()
    }

    private fun isKnownBottomTabText(value: String): Boolean {
        return value in KNOWN_BOTTOM_TAB_TEXTS
    }

    private fun runSafe(source: String, block: () -> Unit) {
        runCatching(block).onFailure { throwable ->
            log("WhatsApp liquid glass hook failed in $source: ${throwable.stackTraceToString()}")
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

    private data class NavOriginalState(
        val background: Drawable?,
        val paddingLeft: Int,
        val paddingTop: Int,
        val paddingRight: Int,
        val paddingBottom: Int,
        val minimumHeight: Int,
        val elevation: Float,
        val translationZ: Float,
        val clipToOutline: Boolean,
        val hasLayoutParams: Boolean,
        val layoutWidth: Int,
        val layoutHeight: Int,
        val hasMargins: Boolean,
        val leftMargin: Int,
        val topMargin: Int,
        val rightMargin: Int,
        val bottomMargin: Int,
        val itemTextColor: ColorStateList? = null,
        val itemIconTintList: ColorStateList? = null,
        val itemRippleColor: ColorStateList? = null,
        val itemActiveIndicatorColor: ColorStateList? = null,
        val itemActiveIndicatorEnabled: Boolean? = null
    ) {
        fun withNavigationProperties(
            itemTextColor: ColorStateList?,
            itemIconTintList: ColorStateList?,
            itemRippleColor: ColorStateList?,
            itemActiveIndicatorColor: ColorStateList?,
            itemActiveIndicatorEnabled: Boolean?
        ): NavOriginalState {
            return copy(
                itemTextColor = itemTextColor,
                itemIconTintList = itemIconTintList,
                itemRippleColor = itemRippleColor,
                itemActiveIndicatorColor = itemActiveIndicatorColor,
                itemActiveIndicatorEnabled = itemActiveIndicatorEnabled
            )
        }
    }

    private data class ItemOriginalState(
        val background: Drawable?,
        val paddingLeft: Int,
        val paddingTop: Int,
        val paddingRight: Int,
        val paddingBottom: Int
    )

    private data class AuxOriginalState(
        val background: Drawable?,
        val visibility: Int,
        val alpha: Float,
        val minimumHeight: Int,
        val paddingLeft: Int,
        val paddingTop: Int,
        val paddingRight: Int,
        val paddingBottom: Int,
        val hasLayoutParams: Boolean,
        val layoutWidth: Int,
        val layoutHeight: Int,
        val hasMargins: Boolean,
        val leftMargin: Int,
        val topMargin: Int,
        val rightMargin: Int,
        val bottomMargin: Int
    )

    private data class BarVisualState(
        val itemCount: Int,
        val selectedIndex: Float,
        val pressProgress: Float
    )

    private class LiquidGlassOverlayView(
        context: Context,
        private val density: Float,
        private val scaledDensity: Float,
        private val navRef: WeakReference<View>,
        private val stateProvider: (View) -> BarVisualState,
        initialTabs: List<WhatsAppLiquidGlassTab>,
        private val touchHandler: (MotionEvent, View) -> Boolean,
        private val selectHandler: (View, Int) -> Unit
    ) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val shellRect = RectF()
        private val selectorRect = RectF()
        private val iconRect = android.graphics.Rect()
        private var tabs: List<WhatsAppLiquidGlassTab> = initialTabs

        init {
            isClickable = true
            isFocusable = false
            setWillNotDraw(false)
        }

        fun updateTabs(nextTabs: List<WhatsAppLiquidGlassTab>) {
            if (nextTabs.isNotEmpty() && nextTabs != tabs) {
                tabs = nextTabs
                invalidate()
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val nav = navRef.get() ?: return
            val count = tabs.size.coerceAtLeast(1)
            val state = stateProvider(nav)
            layoutShell()
            drawShell(canvas)
            drawSelector(canvas, state, count)
            drawTabs(canvas, state, tabs)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val nav = navRef.get() ?: return false
            val handled = touchHandler(event, this)
            if (event.actionMasked == MotionEvent.ACTION_UP && !handled) {
                val index = indexForX(event.x, tabs.size.coerceAtLeast(1))
                selectHandler(nav, index)
                invalidate()
                return true
            }
            invalidate()
            return true
        }

        private fun layoutShell() {
            val horizontalInset = 22f * density
            val bottomInset = 10f * density
            val shellHeight = min(64f * density, max(48f * density, height - bottomInset - 5f * density))
            val bottom = height - bottomInset
            shellRect.set(
                horizontalInset,
                bottom - shellHeight,
                width - horizontalInset,
                bottom
            )
        }

        private fun drawShell(canvas: Canvas) {
            val radius = shellRect.height() / 2f
            paint.style = Paint.Style.FILL
            paint.shader = LinearGradient(
                shellRect.left,
                shellRect.top,
                shellRect.left,
                shellRect.bottom,
                intArrayOf(
                    Color.argb(174, 42, 49, 53),
                    Color.argb(148, 18, 23, 26),
                    Color.argb(176, 8, 10, 12)
                ),
                floatArrayOf(0f, 0.56f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(shellRect, radius, radius, paint)

            paint.shader = LinearGradient(
                shellRect.left,
                shellRect.top,
                shellRect.left,
                shellRect.bottom,
                intArrayOf(
                    Color.argb(44, 255, 255, 255),
                    Color.argb(12, 255, 255, 255),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, 0.22f, 1f),
                Shader.TileMode.CLAMP
            )
            paint.style = Paint.Style.FILL
            canvas.drawRoundRect(shellRect, radius, radius, paint)

            paint.shader = null
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.05f * density
            paint.color = Color.argb(106, 255, 255, 255)
            canvas.drawRoundRect(shellRect, radius, radius, paint)
        }

        private fun drawSelector(canvas: Canvas, state: BarVisualState, count: Int) {
            val tabWidth = shellRect.width() / count
            val press = state.pressProgress.coerceIn(0f, 1f)
            val centerX = shellRect.left + tabWidth * (state.selectedIndex.coerceIn(0f, (count - 1).toFloat()) + 0.5f)
            val width = min(tabWidth + 22f * density * press, tabWidth - 8f * density + 22f * density * press)
            val height = shellRect.height() - 8f * density
            selectorRect.set(
                centerX - width / 2f,
                shellRect.centerY() - height / 2f,
                centerX + width / 2f,
                shellRect.centerY() + height / 2f
            )
            val radius = selectorRect.height() / 2f

            paint.style = Paint.Style.FILL
            paint.shader = LinearGradient(
                selectorRect.left,
                selectorRect.top,
                selectorRect.right,
                selectorRect.bottom,
                intArrayOf(
                    Color.argb(88 + (30 * press).roundToInt(), 255, 255, 255),
                    Color.argb(54 + (20 * press).roundToInt(), 213, 222, 228),
                    Color.argb(30 + (12 * press).roundToInt(), 255, 255, 255)
                ),
                floatArrayOf(0f, 0.58f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(selectorRect, radius, radius, paint)

            paint.shader = null
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 0.8f * density
            paint.color = Color.argb(92 + (36 * press).roundToInt(), 255, 255, 255)
            canvas.drawRoundRect(selectorRect, radius, radius, paint)
        }

        private fun drawTabs(canvas: Canvas, state: BarVisualState, tabs: List<WhatsAppLiquidGlassTab>) {
            val count = tabs.size.coerceAtLeast(1)
            val selected = state.selectedIndex.roundToInt().coerceIn(0, count - 1)
            val tabWidth = shellRect.width() / count
            tabs.forEachIndexed { index, tab ->
                val centerX = shellRect.left + tabWidth * (index + 0.5f)
                val isSelected = index == selected
                val color = if (isSelected) Color.WHITE else Color.rgb(204, 212, 217)
                drawIcon(canvas, tab.icon, centerX, shellRect.top + 20f * density, color)
                drawLabel(canvas, tab.title, centerX, shellRect.top + 49f * density, color, isSelected, tabWidth)
            }
        }

        private fun drawIcon(canvas: Canvas, icon: Drawable?, centerX: Float, centerY: Float, color: Int) {
            val size = 23f * density
            if (icon == null) return
            val left = (centerX - size / 2f).roundToInt()
            val top = (centerY - size / 2f).roundToInt()
            iconRect.set(left, top, (left + size).roundToInt(), (top + size).roundToInt())
            runCatching {
                icon.mutate()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    icon.setTint(color)
                }
                icon.setBounds(iconRect)
                icon.setAlpha(255)
                icon.draw(canvas)
            }
        }

        private fun drawLabel(
            canvas: Canvas,
            label: String,
            centerX: Float,
            baseline: Float,
            color: Int,
            selected: Boolean,
            tabWidth: Float
        ) {
            if (label.isBlank()) return
            paint.shader = null
            paint.style = Paint.Style.FILL
            paint.color = color
            paint.textAlign = Paint.Align.CENTER
            paint.typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            paint.textSize = if (selected) 15f * scaledDensity else 14f * scaledDensity
            val clipped = ellipsize(label, tabWidth - 18f * density)
            canvas.drawText(clipped, centerX, baseline, paint)
            paint.typeface = Typeface.DEFAULT
        }

        private fun ellipsize(text: String, maxWidth: Float): String {
            if (paint.measureText(text) <= maxWidth) return text
            if (maxWidth <= paint.measureText("...")) return ""
            var end = text.length
            while (end > 0 && paint.measureText(text.substring(0, end) + "...") > maxWidth) {
                end--
            }
            return text.substring(0, end).trimEnd() + "..."
        }

        private fun indexForX(x: Float, count: Int): Int {
            val tabWidth = shellRect.width().coerceAtLeast(1f) / count
            return ((x - shellRect.left) / tabWidth).roundToInt().coerceIn(0, count - 1)
        }
    }

    private class DragState {
        var downX: Float = 0f
        var downY: Float = 0f
        var downTime: Long = Long.MIN_VALUE
        var touchWidth: Int = 0
        var dragging: Boolean = false
        var dragIndex: Float = 0f
        var pressProgress: Float = 0f
        var longPressRunnable: Runnable? = null
        var pressAnimator: ValueAnimator? = null

        fun cancel(handler: Handler) {
            longPressRunnable?.let { handler.removeCallbacks(it) }
            longPressRunnable = null
            pressAnimator?.cancel()
            pressAnimator = null
            dragging = false
            pressProgress = 0f
            downTime = Long.MIN_VALUE
            touchWidth = 0
        }
    }

    private class LiquidGlassBarDrawable(
        private val density: Float,
        private val navRef: WeakReference<View>,
        private val stateProvider: (View) -> BarVisualState
    ) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        private val selectorRect = RectF()

        override fun draw(canvas: Canvas) {
            if (bounds.isEmpty) return
            val insetX = max(10f * density, bounds.width() * 0.025f)
            val maxHeight = 64f * density
            val usableHeight = max(40f * density, bounds.height() - 6f * density)
            val height = min(usableHeight, maxHeight)
            val top = bounds.top + (bounds.height() - height) / 2f
            rect.set(
                bounds.left + insetX,
                top,
                bounds.right - insetX,
                top + height
            )
            val radius = rect.height() / 2f

            paint.style = Paint.Style.FILL
            paint.shader = LinearGradient(
                rect.left,
                rect.top,
                rect.left,
                rect.bottom,
                intArrayOf(
                    Color.argb(116, 44, 51, 55),
                    Color.argb(96, 18, 23, 26),
                    Color.argb(120, 7, 9, 11)
                ),
                floatArrayOf(0f, 0.54f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(rect, radius, radius, paint)

            paint.shader = null
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.0f * density
            paint.color = Color.argb(70, 255, 255, 255)
            canvas.drawRoundRect(rect, radius, radius, paint)

            drawSelectedCapsule(canvas)
        }

        private fun drawSelectedCapsule(canvas: Canvas) {
            val nav = navRef.get() ?: return
            val state = stateProvider(nav)
            val count = state.itemCount.coerceAtLeast(1)
            val tabWidth = rect.width() / count
            val baseWidth = tabWidth - 8f * density
            val baseHeight = rect.height() - 8f * density
            val press = state.pressProgress.coerceIn(0f, 1f)
            val scaleX = 1f + 0.18f * press
            val scaleY = 1f + 0.08f * press
            val centerX = rect.left + tabWidth * (state.selectedIndex.coerceIn(0f, (count - 1).toFloat()) + 0.5f)
            val centerY = rect.centerY()
            val width = min(tabWidth - 4f * density, baseWidth * scaleX)
            val height = min(rect.height() - 4f * density, baseHeight * scaleY)
            selectorRect.set(
                centerX - width / 2f,
                centerY - height / 2f,
                centerX + width / 2f,
                centerY + height / 2f
            )
            val selectorRadius = selectorRect.height() / 2f

            paint.style = Paint.Style.FILL
            paint.shader = LinearGradient(
                selectorRect.left,
                selectorRect.top,
                selectorRect.right,
                selectorRect.bottom,
                intArrayOf(
                    Color.argb(50 + (20 * press).roundToInt(), 255, 255, 255),
                    Color.argb(34 + (14 * press).roundToInt(), 207, 217, 223),
                    Color.argb(22 + (8 * press).roundToInt(), 255, 255, 255)
                ),
                floatArrayOf(0f, 0.58f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(selectorRect, selectorRadius, selectorRadius, paint)

            paint.shader = null
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 0.75f * density
            paint.color = Color.argb(56 + (34 * press).roundToInt(), 255, 255, 255)
            canvas.drawRoundRect(selectorRect, selectorRadius, selectorRadius, paint)
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    private class LiquidGlassTabDrawable(
        private val density: Float,
        private val selected: Boolean,
        private val pressed: Boolean = false
    ) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()

        override fun draw(canvas: Canvas) {
            if (bounds.isEmpty) return
            rect.set(bounds)
            rect.inset(2f * density, 3f * density)
            val radius = rect.height() / 2f
            val fillAlpha = when {
                selected -> 48
                pressed -> 34
                else -> 0
            }
            if (fillAlpha <= 0) return

            paint.style = Paint.Style.FILL
            paint.shader = LinearGradient(
                rect.left,
                rect.top,
                rect.right,
                rect.bottom,
                intArrayOf(
                    Color.argb(fillAlpha + 20, 255, 255, 255),
                    Color.argb(fillAlpha, 132, 151, 160),
                    Color.argb(max(12, fillAlpha - 18), 0, 0, 0)
                ),
                floatArrayOf(0f, 0.58f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(rect, radius, radius, paint)

            paint.shader = null
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 0.8f * density
            paint.color = Color.argb(if (selected) 92 else 52, 255, 255, 255)
            canvas.drawRoundRect(rect, radius, radius, paint)
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}

private val NAV_ITEM_RESOURCE_NAMES = setOf(
    "navigation_bar_item_icon_view",
    "navigation_bar_item_labels_group",
    "navigation_bar_item_large_label_view",
    "navigation_bar_item_small_label_view"
)

private val BOTTOM_ACTION_RESOURCE_NAMES = setOf(
    "avatar_home_fab",
    "ai_reply_fab_container",
    "ai_reply_fab_icon",
    "ai_reply_fab_pill",
    "ai_reply_fab_text",
    "camera_button",
    "camera_button_container",
    "create_event_fab",
    "create_status_button",
    "extended_fab",
    "extended_fab_second",
    "extended_mini_fab",
    "extended_mini_fab_icon",
    "extended_mini_fab_text",
    "fab",
    "fab_second",
    "fabtext",
    "fab_request_payment",
    "floating_cta_action_zone",
    "floating_cta_button",
    "floating_cta_container",
    "floating_cta_inner",
    "gallery_fab",
    "meta_ai_ring_icon",
    "meta_ai_widget",
    "meta_ai_widget_container",
    "new_order_fab",
    "selected_list_action_fab_1",
    "selected_list_action_fab_2",
    "send_payment_fab",
    "side_rail_fab_second"
)

private val KNOWN_BOTTOM_TAB_TEXTS = setOf(
    "chats",
    "chat",
    "conversations",
    "conversation",
    "updates",
    "status",
    "statuses",
    "calls",
    "call",
    "communities",
    "community",
    "you",
    "meta ai",
    "ai"
)
