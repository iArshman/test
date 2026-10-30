package me.eternal.purrfect.core.whatsapp

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import me.eternal.purrfect.core.logger.CoreLogger
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class WhatsAppChannelHooks(
    private val androidContext: Context,
    private val appClassLoader: ClassLoader = androidContext.classLoader
) {
    companion object {
        const val TAG = "PurrfectWA"
        private val CHANNEL_BROWSER_CATEGORY_TERMS = setOf(
            "entertainment",
            "sports",
            "news & information",
            "lifestyle",
            "people",
            "business",
            "organizations"
        )
        private val START_CHATTING_PREF_KEYS = setOf(
            "is_chat_list_suggestions_dismissed",
            "dismiss_time_key"
        )
        private const val WA_AB_ANDROID_LISTS_FILTERS_LAZY_INIT = 12339
        private const val WA_AB_CHAT_LIST_CONTACT_SUGGESTIONS_COMPONENT_INIT = 7656
        private const val WA_AB_CHAT_LIST_CONTACT_SUGGESTIONS_ENABLED = 7223
        private const val WA_AB_CHAT_LIST_CONTACT_SUGGESTIONS_EXPOSURE = 15389
        private const val WA_AB_CHAT_LIST_EMPTY_SUGGESTED_CONTACTS = 13361
        private const val WA_AB_FAVORITES_ALWAYS_SHOW_FILTER_ENABLED = 13546
        private const val WA_AB_INBOX_FILTERS_FAVORITES_ENABLED = 5172
        private const val WA_AB_INBOX_FILTERS_FAVORITES_ENABLED_COMPANIONS = 8928
        private const val WA_AB_PRIMARY_FAVORITES_SYNC_SUPPORT = 8929
        private const val WA_AB_INBOX_FILTERS_HEADER_VIEW_ENABLED = 15345
        private const val WA_AB_INBOX_FILTERS_SMB_ALWAYS_VISIBLE = 7221
        private const val WA_AB_INBOX_FILTERS_SMB_ENABLED = 7108
        private const val WA_AB_LISTS_ALWAYS_SHOW_FILTER_ENABLED = 13408
        private val activeHooks = Collections.synchronizedSet(
            Collections.newSetFromMap(WeakHashMap<WhatsAppChannelHooks, Boolean>())
        )

        fun refreshActiveHooks(reason: String) {
            synchronized(activeHooks) {
                activeHooks.toList()
            }.forEach { hook ->
                hook.refreshVisibleRoots(reason)
            }
        }
    }

    private val installed = AtomicBoolean(false)
    private val hiddenRecommendations = AtomicInteger(0)
    private val scanTimes = Collections.synchronizedMap(WeakHashMap<View, Long>())
    private val preDrawScanTimes = Collections.synchronizedMap(WeakHashMap<View, Long>())
    private val trackedRoots = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<View, Boolean>()))
    private val preDrawRoots = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<View, Boolean>()))
    private val rootScopes = Collections.synchronizedMap(WeakHashMap<View, ScreenScope>())
    private val channelHeaderTops = Collections.synchronizedMap(WeakHashMap<View, Int>())
    private val hiddenViews = Collections.newSetFromMap(WeakHashMap<View, Boolean>())
    private val hiddenCommunitiesNavs = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<View, Boolean>()))
    private val shownChatFilterBars = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<View, Boolean>()))
    private val conversationFragments = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<Any, Boolean>()))
    private val hookedUpdatesAdapterClasses = Collections.synchronizedSet(mutableSetOf<String>())
    private val forcingConversationFilters = ThreadLocal.withInitial { false }
    private val conversationFilterForceTimes = Collections.synchronizedMap(WeakHashMap<Any, Long>())
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val featureState get() = WhatsAppFeatureStateStore.current

    private enum class ScreenScope {
        UPDATES,
        CHANNELS_BROWSER,
        CHATS_HOME
    }

    fun init() {
        synchronized(activeHooks) { activeHooks += this }
        installPlatformHooks()
    }

    private fun refreshVisibleRoots(reason: String) {
        val roots = synchronized(trackedRoots) {
            trackedRoots.toList()
        }
        if (roots.isEmpty()) return

        roots.forEach { root ->
            if (root.visibility != View.VISIBLE) return@forEach
            runSafe("recommendation refresh $reason immediate") { scanRecommendationContainers(root) }
            root.post { runSafe("recommendation refresh $reason posted") { scanRecommendationContainers(root) } }
            root.postDelayed({ runSafe("recommendation refresh $reason delayed-80") { scanRecommendationContainers(root) } }, 80L)
            if (featureState.hideStartChatting) {
                root.postDelayed({ runSafe("recommendation refresh $reason delayed-240") { scanRecommendationContainers(root) } }, 240L)
                root.postDelayed({ runSafe("recommendation refresh $reason delayed-600") { scanRecommendationContainers(root) } }, 600L)
                root.postDelayed({ runSafe("recommendation refresh $reason delayed-1400") { scanRecommendationContainers(root) } }, 1_400L)
            }
        }
        if (false) {
            refreshConversationFilters(reason)
        }
        logInfo("Requested WhatsApp channel recommendation refresh for $reason roots=${roots.size}")
    }

    private fun installPlatformHooks() {
        if (!installed.compareAndSet(false, true)) return

        runCatching {
            installWhatsAppFeatureGateOverrides()
            installStartChattingDataGuards()
            XposedBridge.hookAllMethods(
                View::class.java,
                "onAttachedToWindow",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam<*>) {
                        runSafe("View.onAttachedToWindow") {
                            val view = param.thisObject as? View ?: return@runSafe
                            rememberRoot(view)
                            if (featureState.hideCommunitiesTab) hideCommunitiesMenuItemIfNeeded(view)
                            if (featureState.hideStartChatting && view is ProgressBar) {
                                primeStartChattingScrubber(view)
                                hideStartChattingLoaderBeforeDraw(view)
                            }
                            if (featureState.hideStartChatting && hideStartChattingLoaderVisualBeforeDraw(view)) {
                                return@runSafe
                            }
                            if (shouldScheduleFromView(view)) scheduleRecommendationScan(view)
                        }
                    }
                }
            )
            XposedBridge.hookAllMethods(
                View::class.java,
                "onLayout",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam<*>) {
                        runSafe("View.onLayout") {
                            val view = param.thisObject as? View ?: return@runSafe
                            rememberRoot(view)
                            if (featureState.hideCommunitiesTab) hideCommunitiesMenuItemIfNeeded(view)
                            if (featureState.hideStartChatting && view is ProgressBar) {
                                primeStartChattingScrubber(view)
                                hideStartChattingLoaderBeforeDraw(view)
                            }
                            if (featureState.hideStartChatting && hideStartChattingLoaderVisualBeforeDraw(view)) {
                                return@runSafe
                            }
                            if (shouldScheduleFromView(view)) scheduleRecommendationScan(view)
                        }
                    }
                }
            )
            XposedBridge.hookAllMethods(
                ProgressBar::class.java,
                "onDraw",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam<*>) {
                        runSafe("ProgressBar.onDraw") {
                            val progressBar = param.thisObject as? ProgressBar ?: return@runSafe
                            if (hideStartChattingLoaderBeforeDraw(progressBar)) {
                                param.cancelWithDefaultResult()
                            }
                        }
                    }
                }
            )
            XposedBridge.hookAllMethods(
                View::class.java,
                "draw",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam<*>) {
                        runSafe("View.draw start chatting loader") {
                            val view = param.thisObject as? View ?: return@runSafe
                            if (hideAnyStartChattingLoaderBeforeDraw(view)) {
                                param.cancelWithDefaultResult()
                            }
                        }
                    }
                }
            )
            XposedBridge.hookAllMethods(
                ViewGroup::class.java,
                "addView",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam<*>) {
                        runSafe("ViewGroup.addView") {
                            val parent = param.thisObject as? ViewGroup ?: return@runSafe
                            val child = param.args.firstOrNull { it is View } as? View
                            if (!featureState.hideStartChatting) return@runSafe
                            if (child != null && shouldEarlyScrubStartChatting(child)) {
                                primeStartChattingScrubber(child)
                                scanStartChattingSubtreeBeforeDraw(child)
                                scanStartChattingSubtreeBeforeDraw(parent)
                            }
                        }
                    }
                }
            )
            XposedBridge.hookAllMethods(
                ViewGroup::class.java,
                "addViewInLayout",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam<*>) {
                        runSafe("ViewGroup.addViewInLayout") {
                            val parent = param.thisObject as? ViewGroup ?: return@runSafe
                            val child = param.args.firstOrNull { it is View } as? View
                            if (!featureState.hideStartChatting) return@runSafe
                            if (child != null && shouldEarlyScrubStartChatting(child)) {
                                primeStartChattingScrubber(child)
                                scanStartChattingSubtreeBeforeDraw(child)
                                scanStartChattingSubtreeBeforeDraw(parent)
                            }
                        }
                    }
                }
            )
            XposedBridge.hookAllMethods(
                View::class.java,
                "setVisibility",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam<*>) {
                        runSafe("View.setVisibility before") {
                            val view = param.thisObject as? View ?: return@runSafe
                        }
                    }

                    override fun afterHookedMethod(param: MethodHookParam<*>) {
                        runSafe("View.setVisibility") {
                            val view = param.thisObject as? View ?: return@runSafe
                            if (!featureState.hideStartChatting) return@runSafe
                            if (view.visibility == View.VISIBLE && shouldEarlyScrubStartChatting(view)) {
                                primeStartChattingScrubber(view)
                                scanStartChattingSubtreeBeforeDraw(view)
                            }
                        }
                    }
                }
            )
            XposedBridge.hookAllMethods(
                TextView::class.java,
                "setText",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam<*>) {
                        runSafe("TextView.setText before") {
                            if (!shouldHandleChannelUiNow()) return@runSafe
                            val textView = param.thisObject as? TextView ?: return@runSafe
                            val incoming = param.args.getOrNull(0)?.toString()?.trim().orEmpty()
                            if (incoming.isBlank()) return@runSafe
                            if (suppressChannelTextBeforeBind(textView, incoming)) {
                                param.args[0] = ""
                            }
                        }
                    }

                    override fun afterHookedMethod(param: MethodHookParam<*>) {
                        runSafe("TextView.setText") {
                            if (!shouldHandleChannelUiNow()) return@runSafe
                            val textView = param.thisObject as? TextView ?: return@runSafe
                            handleRecommendationTextBind(textView)
                        }
                    }
                }
            )
            XposedBridge.hookAllMethods(
                RecyclerView.Adapter::class.java,
                "bindViewHolder",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam<*>) {
                        runSafe("RecyclerView.Adapter.bindViewHolder") {
                            if (!shouldHandleChannelUiNow()) return@runSafe
                            val adapter = param.thisObject as? RecyclerView.Adapter<*> ?: return@runSafe
                            val holder = param.args.getOrNull(0) as? RecyclerView.ViewHolder ?: return@runSafe
                            val position = (param.args.getOrNull(1) as? Int)
                                ?.takeIf { it != RecyclerView.NO_POSITION }
                                ?: holder.bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }
                                ?: return@runSafe

                            if (featureState.hideStartChatting && hideBoundStartChatting(adapter, holder, position)) {
                                return@runSafe
                            }
                            if (!shouldHideRecommendationsNow()) return@runSafe
                            if (!isLikelyUpdatesAdapter(adapter, holder.itemView)) return@runSafe
                            installRuntimeUpdatesAdapterHooks(adapter.javaClass)
                            hideBoundRecommendation(adapter, holder, position)
                        }
                    }
                }
            )
            logInfo("Installed WhatsApp channel recommendation UI hooks")
        }.onFailure { logError("Failed to install WhatsApp channel recommendation hooks", it) }
    }

    private fun installStartChattingDataGuards() {
        runCatching {
            val conversationsFragmentClass = appClassLoader.loadClass("com.whatsapp.conversationslist.ConversationsFragment")
            XposedBridge.hookAllMethods(
                conversationsFragmentClass,
                "A0l",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam<*>) {
                        if (!featureState.hideStartChatting) return
                        logStartChattingGateBlocked("ConversationsFragment.A0l")
                        param.cancelWithDefaultResult()
                    }
                }
            )
            logInfo("Hooked WhatsApp start chatting component init")
        }.onFailure { logError("Failed to hook WhatsApp start chatting component init", it) }

        runCatching {
            val sharedPreferencesImpl = Class.forName("android.app.SharedPreferencesImpl")
            XposedBridge.hookAllMethods(
                sharedPreferencesImpl,
                "getBoolean",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam<*>) {
                        if (!featureState.hideStartChatting) return
                        val key = param.args.getOrNull(0) as? String ?: return
                        if (key in START_CHATTING_PREF_KEYS && key == "is_chat_list_suggestions_dismissed") {
                            param.result = true
                        }
                    }
                }
            )
            XposedBridge.hookAllMethods(
                sharedPreferencesImpl,
                "getLong",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam<*>) {
                        if (!featureState.hideStartChatting) return
                        val key = param.args.getOrNull(0) as? String ?: return
                        if (key in START_CHATTING_PREF_KEYS && key == "dismiss_time_key") {
                            param.result = Long.MAX_VALUE / 4
                        }
                    }
                }
            )
            val editorImpl = Class.forName("android.app.SharedPreferencesImpl\$EditorImpl")
            XposedBridge.hookAllMethods(
                editorImpl,
                "putBoolean",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam<*>) {
                        if (!featureState.hideStartChatting) return
                        val key = param.args.getOrNull(0) as? String ?: return
                        if (key in START_CHATTING_PREF_KEYS && key == "is_chat_list_suggestions_dismissed") {
                            param.args[1] = true
                        }
                    }
                }
            )
            XposedBridge.hookAllMethods(
                editorImpl,
                "putLong",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam<*>) {
                        if (!featureState.hideStartChatting) return
                        val key = param.args.getOrNull(0) as? String ?: return
                        if (key in START_CHATTING_PREF_KEYS && key == "dismiss_time_key") {
                            param.args[1] = Long.MAX_VALUE / 4
                        }
                    }
                }
            )
            logInfo("Hooked WhatsApp start chatting preference gate")
        }.onFailure { logError("Failed to hook WhatsApp start chatting preference gate", it) }

        listOf("X.1el", "X.C34921el").forEach { className ->
            runCatching {
                val suggestionsFactoryClass = appClassLoader.loadClass(className)
                XposedBridge.hookAllMethods(
                    suggestionsFactoryClass,
                    "A00",
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam<*>) {
                            if (!featureState.hideStartChatting) return
                            logStartChattingGateBlocked("suggestions-factory")
                            param.cancelWithDefaultResult()
                        }
                    }
                )
                logInfo("Hooked WhatsApp start chatting suggestions factory $className")
            }
        }

        listOf("X.206", "X.AnonymousClass206").forEach { className ->
            runCatching {
                val suggestionsViewClass = appClassLoader.loadClass(className)
                hookStartChattingSuggestionsView(suggestionsViewClass)
                logInfo("Hooked WhatsApp start chatting suggestions view $className")
            }
        }
        listOf("X.0y0", "X.AbstractC21890y0").forEach { className ->
            runCatching {
                val emptyViewControllerClass = appClassLoader.loadClass(className)
                XposedBridge.hookAllMethods(
                    emptyViewControllerClass,
                    "A0C",
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam<*>) {
                            if (!featureState.hideStartChatting) return
                            val suggestions = param.args.getOrNull(0)
                            if (suggestions == null || isStartChattingSuggestionsObject(suggestions)) {
                                param.args[0] = null
                                logStartChattingGateBlocked("empty-view-controller")
                            }
                        }
                    }
                )
                logInfo("Hooked WhatsApp start chatting empty-view controller $className")
            }.onFailure { logError("Failed to hook WhatsApp start chatting empty-view controller $className", it) }
        }
        listOf("X.3Rf", "X.RunnableC76133Rf").forEach { className ->
            runCatching {
                val suggestionsRunnableClass = appClassLoader.loadClass(className)
                XposedBridge.hookAllMethods(
                    suggestionsRunnableClass,
                    "run",
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam<*>) {
                            if (!featureState.hideStartChatting) return
                            val runnable = param.thisObject ?: return
                            val type = runCatching {
                                val field = runnable.javaClass.getDeclaredField("\$t")
                                field.isAccessible = true
                                field.getInt(runnable)
                            }.getOrNull() ?: return
                            if (type != 17) return
                            val owner = runCatching {
                                val field = runnable.javaClass.getDeclaredField("A00")
                                field.isAccessible = true
                                field.get(runnable)
                            }.getOrNull() ?: return
                            val ownerName = owner.javaClass.name
                            if (ownerName == "X.1el" || ownerName == "X.C34921el") {
                                logStartChattingGateBlocked("suggestions-runnable")
                                param.cancelWithDefaultResult()
                            }
                        }
                    }
                )
                logInfo("Hooked WhatsApp start chatting suggestions runnable $className")
            }.onFailure { logError("Failed to hook WhatsApp start chatting suggestions runnable $className", it) }
        }
        listOf("X.22n", "X.C482022n").forEach { className ->
            runCatching {
                val suggestionsViewModelClass = appClassLoader.loadClass(className)
                hookStartChattingSuggestionsViewModel(suggestionsViewModelClass)
                logInfo("Hooked WhatsApp start chatting suggestions gate $className")
            }
        }
        runCatching {
            val progressClass = appClassLoader.loadClass("com.whatsapp.ui.wds.components.progressindicator.WDSCircularProgressView")
            XposedBridge.hookAllMethods(
                progressClass,
                "onDraw",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam<*>) {
                        runSafe("WDSCircularProgressView.onDraw") {
                            val view = param.thisObject as? View ?: return@runSafe
                            if (hideKnownStartChattingLoaderBeforeDraw(view) || hideStartChattingLoaderVisualBeforeDraw(view)) {
                                param.cancelWithDefaultResult()
                            }
                        }
                    }
                }
            )
            logInfo("Hooked WhatsApp WDS progress loader draw")
        }
    }

    private fun installWhatsAppFeatureGateOverrides() {
        var abGateHooked = false
        var lastAbGateError: Throwable? = null
        listOf("X.00I", "X.C00I").forEach { className ->
            runCatching {
                val abPropsClass = appClassLoader.loadClass(className)
                var hooked = 0
                runCatching {
                    XposedBridge.hookAllMethods(
                        abPropsClass,
                        "A0n",
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam<*>) {
                                val code = param.args.getOrNull(0) as? Int ?: return
                                whatsappFeatureGateOverride(code)?.let { param.result = it }
                            }
                        }
                    )
                    hooked++
                }
                runCatching {
                    XposedBridge.hookAllMethods(
                        abPropsClass,
                        "A0C",
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam<*>) {
                                val code = param.args.getOrNull(3) as? Int ?: return
                                whatsappFeatureGateOverride(code)?.let { param.result = it }
                            }
                        }
                    )
                    hooked++
                }
                if (hooked > 0) logInfo("Hooked WhatsApp AB feature gates class=$className methods=$hooked")
                abGateHooked = abGateHooked || hooked > 0
            }.onFailure { lastAbGateError = it }
        }
        if (!abGateHooked) {
            lastAbGateError?.let { logError("Failed to hook WhatsApp AB feature gates", it) }
        }

        listOf("com.whatsapp.lists.product.ListsUtilImpl").forEach { className ->
            runCatching {
                val listsUtilClass = appClassLoader.loadClass(className)
                listOf("B9U", "B9w", "BA0").forEach { methodName ->
                    XposedBridge.hookAllMethods(
                        listsUtilClass,
                        methodName,
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam<*>) {
                                if (false) {
                                    param.result = true
                                }
                            }
                        }
                    )
                }
                logInfo("Hooked WhatsApp lists filter gates $className")
            }.onFailure { logError("Failed to hook WhatsApp lists filter gates $className", it) }
        }

        listOf("X.0ug", "X.C19860ug").forEach { className ->
            runCatching {
                val filterAvailabilityClass = appClassLoader.loadClass(className)
                XposedBridge.hookAllMethods(
                    filterAvailabilityClass,
                    "A02",
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam<*>) {
                            if (false) {
                                param.result = true
                            }
                        }
                    }
                )
                listOf("A03", "A04").forEach { methodName ->
                    runCatching {
                        XposedBridge.hookAllMethods(
                            filterAvailabilityClass,
                            methodName,
                            object : XC_MethodHook() {
                                override fun beforeHookedMethod(param: MethodHookParam<*>) {
                                    if (false) {
                                        param.result = true
                                    }
                                }
                            }
                        )
                    }
                }
                logInfo("Hooked WhatsApp inbox filter availability gate $className")
            }.onFailure { logError("Failed to hook WhatsApp inbox filter availability gate $className", it) }
        }
    }

    private fun whatsappFeatureGateOverride(code: Int): Boolean? {
        if (featureState.hideStartChatting) {
            when (code) {
                WA_AB_CHAT_LIST_CONTACT_SUGGESTIONS_COMPONENT_INIT,
                WA_AB_CHAT_LIST_CONTACT_SUGGESTIONS_ENABLED,
                WA_AB_CHAT_LIST_CONTACT_SUGGESTIONS_EXPOSURE,
                WA_AB_CHAT_LIST_EMPTY_SUGGESTED_CONTACTS -> return false
            }
        }
        if (false) {
            when (code) {
                WA_AB_INBOX_FILTERS_SMB_ENABLED,
                WA_AB_INBOX_FILTERS_SMB_ALWAYS_VISIBLE,
                WA_AB_INBOX_FILTERS_HEADER_VIEW_ENABLED,
                WA_AB_INBOX_FILTERS_FAVORITES_ENABLED,
                WA_AB_INBOX_FILTERS_FAVORITES_ENABLED_COMPANIONS,
                WA_AB_PRIMARY_FAVORITES_SYNC_SUPPORT,
                WA_AB_LISTS_ALWAYS_SHOW_FILTER_ENABLED,
                WA_AB_FAVORITES_ALWAYS_SHOW_FILTER_ENABLED -> return true
                WA_AB_ANDROID_LISTS_FILTERS_LAZY_INIT -> return false
            }
        }
        return null
    }

    private fun installAlwaysShowChatFilterHooks() {
        runCatching {
            val conversationsFragmentClass = appClassLoader.loadClass("com.whatsapp.conversationslist.ConversationsFragment")
            listOf("BZB", "A1u", "A2K", "A2L", "A2M", "A2N", "A2m").forEach { methodName ->
                runCatching {
                    XposedBridge.hookAllMethods(
                        conversationsFragmentClass,
                        methodName,
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam<*>) {
                                if (false) {
                                    param.thisObject?.let { conversationFragments += it }
                                }
                            }

                            override fun afterHookedMethod(param: MethodHookParam<*>) {
                                runSafe("ConversationsFragment.$methodName chat filters") {
                                    if (false) {
                                        param.thisObject?.let { conversationFragments += it }
                                        postForceConversationFilters(conversationsFragmentClass, param.thisObject, methodName)
                                    }
                                }
                            }
                        }
                    )
                }
            }
            runCatching {
                XposedBridge.hookAllMethods(
                    conversationsFragmentClass,
                    "A0V",
                    object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam<*>) {
                                runSafe("ConversationsFragment.A0V chat filters") {
                                    if (false) {
                                        postForceConversationFilters(conversationsFragmentClass, param.args.firstOrNull(), "A0V")
                                    }
                                }
                            }
                    }
                )
            }
            listOf("A0R", "A0k").forEach { methodName ->
                runCatching {
                    XposedBridge.hookAllMethods(
                        conversationsFragmentClass,
                        methodName,
                        object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam<*>) {
                                runSafe("ConversationsFragment.$methodName filter content") {
                                    if (false) {
                                        postForceConversationFilters(conversationsFragmentClass, param.args.firstOrNull(), methodName)
                                    }
                                }
                            }
                        }
                    )
                }
            }
            logInfo("Hooked WhatsApp conversations filter host")
        }.onFailure { logError("Failed to hook WhatsApp conversations filter host", it) }

        listOf("X.1zu", "X.C47771zu").forEach { className ->
            runCatching {
                val filterHostClass = appClassLoader.loadClass(className)
                hookChatFilterHostClass(filterHostClass)
                logInfo("Hooked WhatsApp chat filter host $className")
            }
        }
        listOf("X.12E", "X.C12E").forEach { className ->
            runCatching {
                val headerAdapterClass = appClassLoader.loadClass(className)
                XposedBridge.hookAllMethods(
                    headerAdapterClass,
                    "A04",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam<*>) {
                            return
                            (param.args.getOrNull(0) as? View)
                                ?.takeIf(::isKnownChatFilterHost)
                                ?.let(::forceShowKnownChatFilterHost)
                        }
                    }
                )
                logInfo("Hooked WhatsApp conversations header adapter $className")
            }
        }
    }

    private fun hookChatFilterHostClass(filterHostClass: Class<*>) {
        runCatching {
            XposedBridge.hookAllMethods(
                filterHostClass,
                "A01",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam<*>) {
                        runSafe("${filterHostClass.name}.A01 chat filters") {
                            if (false) {
                                (param.thisObject as? View)?.let { forceShowKnownChatFilterHost(it) }
                            }
                        }
                    }
                }
            )
        }
        runCatching {
            XposedBridge.hookAllMethods(
                filterHostClass,
                "getFiltersRecyclerView",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam<*>) {
                        runSafe("${filterHostClass.name}.getFiltersRecyclerView chat filters") {
                            if (false) {
                                (param.thisObject as? View)?.let { forceShowKnownChatFilterHost(it) }
                                (param.result as? View)?.let { forceShowKnownChatFilterHost(it) }
                            }
                        }
                    }
                }
            )
        }
        runCatching {
            XposedBridge.hookAllMethods(
                filterHostClass,
                "setVisibility",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam<*>) {
                        if (false) {
                            param.args[0] = View.VISIBLE
                        }
                    }

                    override fun afterHookedMethod(param: MethodHookParam<*>) {
                        runSafe("${filterHostClass.name}.setVisibility chat filters") {
                            if (false) {
                                (param.thisObject as? View)?.let { forceShowKnownChatFilterHost(it) }
                            }
                        }
                    }
                }
            )
        }
    }

    private fun postForceConversationFilters(conversationsFragmentClass: Class<*>, fragment: Any?, reason: String) {
        conversationsFragmentClass.name
        fragment?.hashCode()
        reason.length
    }

    private fun refreshConversationFilters(reason: String) {
        reason.length
    }

    private fun forceConversationFilters(conversationsFragmentClass: Class<*>, fragment: Any?) {
        conversationsFragmentClass.name
        fragment?.hashCode()
    }

    private fun findAnyFragmentView(fragment: Any): View? {
        return findFieldValue(fragment) { value -> value is View && value.rootView != null } as? View
    }

    private fun initialiseConversationFilterAdapter(conversationsFragmentClass: Class<*>, fragment: Any) {
        if (findConversationFilterAdapter(fragment) != null) return
        val filterState = findFieldValue(fragment) { value ->
            value?.javaClass?.name?.let { it == "X.0x8" || it == "X.C21350x8" } == true
        } ?: return
        val filters = runCatching {
            val method = filterState.javaClass.declaredMethods.firstOrNull {
                it.name == "A03" &&
                    java.lang.reflect.Modifier.isStatic(it.modifiers) &&
                    it.parameterTypes.size == 1 &&
                    it.parameterTypes[0].isAssignableFrom(filterState.javaClass)
            } ?: return@runCatching null
            method.isAccessible = true
            method.invoke(null, filterState)
        }.getOrNull() ?: return
        runCatching {
            val method = conversationsFragmentClass.declaredMethods.firstOrNull {
                it.name == "A0k" &&
                    java.lang.reflect.Modifier.isStatic(it.modifiers) &&
                    it.parameterTypes.size == 2 &&
                    it.parameterTypes[0] == conversationsFragmentClass &&
                    java.util.List::class.java.isAssignableFrom(it.parameterTypes[1])
            } ?: return@runCatching
            method.isAccessible = true
            method.invoke(null, fragment, filters)
        }
    }

    private fun createConversationFilterHost(conversationsFragmentClass: Class<*>, fragment: Any) {
        runCatching {
            val context = conversationsFragmentClass.methods.firstOrNull {
                it.name == "A13" && it.parameterTypes.isEmpty() && Context::class.java.isAssignableFrom(it.returnType)
            }?.also { it.isAccessible = true }?.invoke(fragment) as? Context ?: return
            val hostClass = appClassLoader.findClassOrNull("X.1zu")
                ?: appClassLoader.findClassOrNull("X.C47771zu")
                ?: return
            val host = hostClass.constructors.firstOrNull {
                it.parameterTypes.size == 1 && Context::class.java.isAssignableFrom(it.parameterTypes[0])
            }?.also { it.isAccessible = true }?.newInstance(context) as? View ?: return
            hostClass.methods.firstOrNull { it.name == "A01" && it.parameterTypes.isEmpty() }
                ?.also { it.isAccessible = true }
                ?.invoke(host)

            setFieldValue(fragment, "A0N", host)
            val recyclerView = hostClass.methods.firstOrNull { it.name == "getFiltersRecyclerView" && it.parameterTypes.isEmpty() }
                ?.also { it.isAccessible = true }
                ?.invoke(host) as? RecyclerView
            recyclerView?.let { setFieldValue(fragment, "A0F", it) }

            val headerAdapter = findFieldValue(fragment) { value ->
                value?.javaClass?.name?.let { it == "X.12E" || it == "X.C12E" } == true
            } ?: return
            headerAdapter.javaClass.methods.firstOrNull {
                it.name == "A04" && it.parameterTypes.size == 1 && View::class.java.isAssignableFrom(it.parameterTypes[0])
            }?.also { it.isAccessible = true }?.invoke(headerAdapter, host)

            forceShowKnownChatFilterHost(host)
            recyclerView?.let(::forceShowChatFilterRecyclerView)
            logInfo("Created WhatsApp chat filter host fallback")
        }.onFailure { logError("Failed to create WhatsApp chat filter host fallback", it) }
    }

    private fun findConversationFilterHost(fragment: Any): View? {
        return findFieldValue(fragment) { value -> value is View && isKnownChatFilterHost(value) } as? View
    }

    private fun findConversationFilterRecyclerView(fragment: Any): RecyclerView? {
        return findFieldValue(fragment) { value ->
            value is RecyclerView && resourceEntryName(value).orEmpty()
                .contains("conversations_swipe_to_reveal_filter", ignoreCase = true)
        } as? RecyclerView
    }

    private fun findConversationFilterAdapter(fragment: Any): Any? {
        return findFieldValue(fragment) { value ->
            value is RecyclerView.Adapter<*> &&
                value.javaClass.name.let { it == "X.23v" || it == "X.C484823v" }
        }
    }

    private fun findFieldValue(owner: Any, predicate: (Any?) -> Boolean): Any? {
        var current: Class<*>? = owner.javaClass
        var inspected = 0
        while (current != null && inspected < 6) {
            current.declaredFields.forEach { field ->
                val value = runCatching {
                    field.isAccessible = true
                    field.get(owner)
                }.getOrNull()
                if (predicate(value)) return value
            }
            current = current.superclass
            inspected++
        }
        return null
    }

    private fun setFieldValue(owner: Any, fieldName: String, value: Any?) {
        var current: Class<*>? = owner.javaClass
        var inspected = 0
        while (current != null && inspected < 6) {
            val field = runCatching { current.getDeclaredField(fieldName) }.getOrNull()
            if (field != null) {
                runCatching {
                    field.isAccessible = true
                    field.set(owner, value)
                }
                return
            }
            current = current.superclass
            inspected++
        }
    }

    private fun isKnownChatFilterHost(view: View): Boolean {
        val className = view.javaClass.name
        if (className == "X.1zu" || className == "X.C47771zu") return true
        return hasDescendantResourceName(view, "conversations_swipe_to_reveal_filter_recycler_view", 0) ||
            hasDescendantResourceName(view, "conversations_inbox_filters_container", 0) ||
            resourceEntryName(view)?.contains("conversations_inbox_filters", ignoreCase = true) == true
    }

    private fun hookStartChattingSuggestionsViewModel(suggestionsViewModelClass: Class<*>) {
        var hookedMethods = 0
        runCatching {
            XposedBridge.hookAllMethods(
                suggestionsViewModelClass,
                "A0g",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam<*>) {
                        if (!featureState.hideStartChatting) return
                        logStartChattingGateBlocked("A0g")
                        param.cancelWithDefaultResult()
                    }
                }
            )
            hookedMethods++
        }

        suggestionsViewModelClass.declaredMethods
            .filter { method ->
                method.name == "A02" &&
                    method.returnType == Boolean::class.javaPrimitiveType &&
                    method.parameterTypes.size == 1 &&
                    method.parameterTypes[0] == suggestionsViewModelClass
            }
            .forEach { method ->
                runCatching {
                    XposedBridge.hookMethod(
                        method,
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam<*>) {
                                if (featureState.hideStartChatting) {
                                    logStartChattingGateBlocked("A02")
                                    param.result = false
                                }
                            }
                        }
                    )
                    hookedMethods++
                }
            }

        suggestionsViewModelClass.declaredMethods
            .filter { method ->
                method.name == "A01" &&
                    method.returnType == Boolean::class.javaPrimitiveType &&
                    method.parameterTypes.isEmpty()
            }
            .forEach { method ->
                runCatching {
                    XposedBridge.hookMethod(
                        method,
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam<*>) {
                                if (featureState.hideStartChatting) {
                                    logStartChattingGateBlocked("A01")
                                    param.result = false
                                }
                            }
                        }
                    )
                    hookedMethods++
                }
            }
        logInfo("WhatsApp start chatting suggestions gate methods hooked=$hookedMethods class=${suggestionsViewModelClass.name}")
    }

    private fun isStartChattingSuggestionsObject(value: Any): Boolean {
        val className = value.javaClass.name
        return className == "X.206" ||
            className == "X.AnonymousClass206" ||
            className.contains("ConversationsSuggestedContacts", ignoreCase = true)
    }

    private fun ClassLoader.findClassOrNull(name: String): Class<*>? {
        return runCatching { loadClass(name) }.getOrNull()
    }

    private val startChattingGateLogCount = AtomicInteger(0)

    private fun logStartChattingGateBlocked(methodName: String) {
        val count = startChattingGateLogCount.incrementAndGet()
        if (count <= 5 || count % 25 == 0) {
            logInfo("Blocked WhatsApp start chatting suggestions gate $methodName (count=$count)")
        }
    }

    private fun XC_MethodHook.MethodHookParam<*>.cancelWithDefaultResult() {
        result = defaultCancellationResult(method as? Method)
    }

    private fun defaultCancellationResult(method: Method?): Any? {
        val returnType = method?.returnType ?: return null
        if (returnType == Void.TYPE) return null
        if (returnType == Boolean::class.javaPrimitiveType || returnType == Boolean::class.javaObjectType) return false
        if (returnType == Int::class.javaPrimitiveType || returnType == Int::class.javaObjectType) return 0
        if (returnType == Long::class.javaPrimitiveType || returnType == Long::class.javaObjectType) return 0L
        if (returnType == Float::class.javaPrimitiveType || returnType == Float::class.javaObjectType) return 0f
        if (returnType == Double::class.javaPrimitiveType || returnType == Double::class.javaObjectType) return 0.0
        if (returnType == Short::class.javaPrimitiveType || returnType == Short::class.javaObjectType) return 0.toShort()
        if (returnType == Byte::class.javaPrimitiveType || returnType == Byte::class.javaObjectType) return 0.toByte()
        if (returnType == Char::class.javaPrimitiveType || returnType == Char::class.javaObjectType) return 0.toChar()
        return null
    }

    private fun hookStartChattingSuggestionsView(suggestionsViewClass: Class<*>) {
        runCatching {
            XposedBridge.hookAllConstructors(
                suggestionsViewClass,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam<*>) {
                        if (!featureState.hideStartChatting) return
                        (param.thisObject as? View)?.let { view ->
                            view.visibility = View.GONE
                            view.minimumHeight = 0
                            view.setWillNotDraw(true)
                            hideRecommendationView(view, "start chatting host constructed", listOf(suggestionsViewClass.name))
                        }
                    }
                }
            )
        }
        suggestionsViewClass.declaredMethods
            .filter { method ->
                method.name == "A01" &&
                    method.returnType == Void.TYPE &&
                    method.parameterTypes.size == 2 &&
                    method.parameterTypes[0] == suggestionsViewClass &&
                    List::class.java.isAssignableFrom(method.parameterTypes[1])
            }
            .forEach { method ->
                runCatching {
                    XposedBridge.hookMethod(
                        method,
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam<*>) {
                                if (!featureState.hideStartChatting) return
                                (param.args.getOrNull(0) as? View)?.let {
                                    hideRecommendationView(it, "start chatting renderer", listOf(suggestionsViewClass.name))
                                }
                                logStartChattingGateBlocked("view-renderer")
                                param.cancelWithDefaultResult()
                            }
                        }
                    )
                }
            }
        XposedBridge.hookAllMethods(
            suggestionsViewClass,
            "onAttachedToWindow",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam<*>) {
                    if (featureState.hideStartChatting) {
                        (param.thisObject as? View)?.let { hideRecommendationView(it, "start chatting host pre-attach", listOf(suggestionsViewClass.name)) }
                        logStartChattingGateBlocked("view-attach")
                        param.cancelWithDefaultResult()
                    }

                }

                override fun afterHookedMethod(param: MethodHookParam<*>) {
                    if (featureState.hideStartChatting) {
                        (param.thisObject as? View)?.let { hideRecommendationView(it, "start chatting host", listOf(suggestionsViewClass.name)) }
                    }
                }
            }
        )
        runCatching {
            XposedBridge.hookAllMethods(
                suggestionsViewClass,
                "A03",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam<*>) {
                        if (!featureState.hideStartChatting) return
                        (param.thisObject as? View)?.let { hideRecommendationView(it, "start chatting host load", listOf("A03")) }
                        param.cancelWithDefaultResult()
                    }
                }
            )
        }
        listOf("A04", "A05", "setLoadingVisibility", "setSuggestionsVisibility").forEach { methodName ->
            runCatching {
                XposedBridge.hookAllMethods(
                    suggestionsViewClass,
                    methodName,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam<*>) {
                            if (!featureState.hideStartChatting) return
                            if (param.args.isNotEmpty() && param.args[0] is Boolean) {
                                param.args[0] = false
                            }
                            (param.thisObject as? View)?.let { hideRecommendationView(it, "start chatting host visibility", listOf(methodName)) }
                        }

                        override fun afterHookedMethod(param: MethodHookParam<*>) {
                            if (featureState.hideStartChatting) {
                                (param.thisObject as? View)?.let { hideRecommendationView(it, "start chatting host visibility", listOf(methodName)) }
                            }
                        }
                    }
                )
            }
        }
        runCatching {
            XposedBridge.hookAllMethods(
                suggestionsViewClass,
                "BT4",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam<*>) {
                        if (!featureState.hideStartChatting) return
                        val view = param.thisObject as? View ?: return
                        hideRecommendationView(view, "start chatting host create", listOf(suggestionsViewClass.name))
                        logStartChattingGateBlocked("view-create")
                        param.result = view
                    }

                    override fun afterHookedMethod(param: MethodHookParam<*>) {
                        if (featureState.hideStartChatting) {
                            (param.result as? View)?.let { hideRecommendationView(it, "start chatting host create", listOf(suggestionsViewClass.name)) }
                        }
                    }
                }
            )
        }
    }

    private fun rememberRoot(view: View) {
        val root = view.rootView ?: view
        if (root === view && view.parent != null) return
        trackedRoots += root
    }

    private fun primeStartChattingScrubber(view: View) {
        if (!featureState.hideStartChatting) return
        val root = view.rootView ?: view
        trackedRoots += root
        installPreDrawScan(root)
    }

    private fun scanStartChattingSubtreeBeforeDraw(view: View): Boolean {
        if (!featureState.hideStartChatting || view in hiddenViews || view.visibility != View.VISIBLE) return false
        val root = view.rootView ?: view
        val rootHeight = root.height.takeIf { it > 0 } ?: root.resources.displayMetrics.heightPixels
        return scanStartChattingNodes(view, rootHeight, 0)
    }

    private fun shouldEarlyScrubStartChatting(view: View): Boolean {
        if (!featureState.hideStartChatting || view in hiddenViews) return false
        if (view is ProgressBar) return true
        val root = view.rootView ?: view
        val rootHeight = root.height.takeIf { it > 0 } ?: root.resources.displayMetrics.heightPixels
        if (isLikelyStartChattingLoaderVisual(view, rootHeight)) return true
        val className = view.javaClass.name
        if (className == "X.206" || className.contains("ConversationsSuggestedContactsView")) return true
        if (className == "X.29O") return true

        val contentDescription = view.contentDescription?.toString()?.trim().orEmpty().normalizeUiText()
        if (contentDescription == "dismiss" || contentDescription == "remove" || contentDescription == "close") return true

        if (view is TextView) {
            val normalized = view.text?.toString()?.trim().orEmpty().normalizeUiText()
            return normalized == "start chatting" ||
                normalized == "chat" ||
                normalized == "see all" ||
                normalized == "dismiss" ||
                normalized == "remove" ||
                normalized == "close"
        }

        if (view !is ViewGroup) return false
        if (view.childCount == 0 || view.childCount > 12) return false
        return containsProgressBar(view, 0) || containsStartChattingSignal(view, 0)
    }

    private fun containsStartChattingSignal(view: View, depth: Int): Boolean {
        if (depth > 2) return false
        if (view is TextView) {
            val normalized = view.text?.toString()?.trim().orEmpty().normalizeUiText()
            if (normalized == "start chatting" || normalized == "chat" || normalized == "dismiss") return true
        }
        if (view !is ViewGroup) return false
        for (index in 0 until view.childCount) {
            if (containsStartChattingSignal(view.getChildAt(index), depth + 1)) return true
        }
        return false
    }

    private fun shouldScheduleFromView(view: View): Boolean {
        if (!shouldHandleChannelUiNow()) return false
        val contentDescription = view.contentDescription?.toString()?.trim().orEmpty().normalizeUiText()
        if (featureState.hideCommunitiesTab && isCommunitiesText(contentDescription)) return true
        if (isChannelCollapseToggleText(contentDescription)) return true
        if (featureState.hideStartChatting && view is ProgressBar) return true
        if (view !is TextView) return false
        val normalized = view.text?.toString()?.trim().orEmpty().normalizeUiText()
        if (featureState.hideCommunitiesTab && isCommunitiesText(normalized)) return true
        if (featureState.hideStartChatting && (normalized == "chat" || normalized == "see all")) return true
        return isChannelsSectionTriggerText(normalized) ||
            isStartChattingTriggerText(normalized) ||
            isRecommendationRowTriggerText(normalized)
    }

    private fun scheduleRecommendationScan(view: View) {
        if (!shouldHandleChannelUiNow()) return
        val root = view.rootView ?: view
        installPreDrawScan(root)
        val now = SystemClock.uptimeMillis()
        val last = scanTimes[root] ?: 0L
        if (now - last < 180L) return
        scanTimes[root] = now
        root.post { runSafe("recommendation scan posted") { scanRecommendationContainers(root) } }
    }

    private fun installPreDrawScan(root: View) {
        val now = SystemClock.uptimeMillis()
        val last = preDrawScanTimes[root] ?: 0L
        if (now - last < 250L && root in preDrawRoots) return
        preDrawScanTimes[root] = now
        if (!preDrawRoots.add(root)) return
        val keepUntil = if (featureState.hideStartChatting) now + 1_500L else now
        root.viewTreeObserver.addOnPreDrawListener(
            object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    if (!root.viewTreeObserver.isAlive) return true
                    var hiddenSomething = false
                    runSafe("recommendation pre-draw scan") {
                        hiddenSomething = scanRecommendationContainers(root)
                    }
                    val keepScanning = featureState.hideStartChatting && SystemClock.uptimeMillis() < keepUntil
                    if (!keepScanning) {
                        runCatching {
                            if (root.viewTreeObserver.isAlive) {
                                root.viewTreeObserver.removeOnPreDrawListener(this)
                            }
                        }
                        preDrawRoots.remove(root)
                    }
                    return !hiddenSomething
                }
            }
        )
    }

    private fun scanRecommendationContainers(root: View): Boolean {
        if (!shouldHandleChannelUiNow()) return false
        var hiddenSomething = false
        val rootHeight = root.height.takeIf { it > 0 } ?: root.resources.displayMetrics.heightPixels
        val rootTexts = mutableListOf<String>()
        collectText(root, rootTexts, 0, 240)
        if (featureState.hideCommunitiesTab) hiddenSomething = hideCommunitiesMenuItemIfNeeded(root) || hiddenSomething
        if (featureState.hideStartChatting) {
            if (looksLikeChatsHomeRoot(rootTexts)) markRootScope(root, ScreenScope.CHATS_HOME)
            hiddenSomething = scanStartChattingNodes(root, rootHeight, 0) || hiddenSomething
        }
        if (false) {
            hiddenSomething = showChatFilterBarNodes(root, rootHeight, 0) || hiddenSomething
        }
        if (featureState.hideChannels) {
            hiddenSomething = scanHideChannelsNodes(root, rootHeight, 0) || hiddenSomething
            return hiddenSomething
        }
        val scope = updateRootScope(root, rootTexts)
        val updatesScreen = scope == ScreenScope.UPDATES || looksLikeUpdatesTabRoot(rootTexts)
        if (!updatesScreen && scope == ScreenScope.CHANNELS_BROWSER) return hiddenSomething
        if (!shouldHideRecommendationsNow()) return hiddenSomething
        val hasRecommendationSurface = updatesScreen && hasVisibleRecommendationSurface(rootTexts)
        scanNode(root, rootHeight, 0, updatesScreen, hasRecommendationSurface)
        if (updatesScreen && hasRecommendationSurface) {
            scanRecommendationLeafRows(root, rootHeight, 0)
        }
        return hiddenSomething
    }

    private fun hideStartChattingLoaderBeforeDraw(progressBar: ProgressBar): Boolean {
        if (!featureState.hideStartChatting || progressBar in hiddenViews || progressBar.visibility != View.VISIBLE) return false
        val root = progressBar.rootView ?: progressBar
        if (getCachedRootScope(root) == ScreenScope.UPDATES || getCachedRootScope(root) == ScreenScope.CHANNELS_BROWSER) return false
        val rootHeight = root.height.takeIf { it > 0 } ?: root.resources.displayMetrics.heightPixels
        if (!isLikelyStartChattingLoader(progressBar, rootHeight)) return false

        val rootTexts = mutableListOf<String>()
        collectText(root, rootTexts, 0, 160)
        if (looksLikeUpdatesTabRoot(rootTexts) || looksLikeChannelsBrowser(rootTexts)) return false
        if (!looksLikeChatsHomeRoot(rootTexts) && !looksLikeChatsHomeWeak(rootTexts)) return false

        markRootScope(root, ScreenScope.CHATS_HOME)
        hideRecommendationView(progressBar, "start chatting loader draw", listOf(progressBar.javaClass.name))
        return true
    }

    private fun hideKnownStartChattingLoaderBeforeDraw(view: View): Boolean {
        if (!featureState.hideStartChatting || view in hiddenViews || view.visibility != View.VISIBLE) return false
        val root = view.rootView ?: view
        if (getCachedRootScope(root) == ScreenScope.UPDATES || getCachedRootScope(root) == ScreenScope.CHANNELS_BROWSER) return false
        val rootHeight = root.height.takeIf { it > 0 } ?: root.resources.displayMetrics.heightPixels
        val location = IntArray(2)
        runCatching { view.getLocationOnScreen(location) }
        val top = location[1]
        if (top <= rootHeight * 0.22f || top >= rootHeight * 0.74f) return false
        if (view.width > view.resources.displayMetrics.widthPixels * 0.55f || view.height > rootHeight * 0.22f) return false

        val rootTexts = mutableListOf<String>()
        collectText(root, rootTexts, 0, 160)
        if (looksLikeUpdatesTabRoot(rootTexts) || looksLikeChannelsBrowser(rootTexts)) return false
        if (!looksLikeChatsHomeRoot(rootTexts) && !looksLikeChatsHomeWeak(rootTexts)) return false

        markRootScope(root, ScreenScope.CHATS_HOME)
        hideRecommendationView(view, "start chatting known loader draw", listOf(view.javaClass.name, resourceEntryName(view).orEmpty()))
        return true
    }

    private fun hideAnyStartChattingLoaderBeforeDraw(view: View): Boolean {
        if (!featureState.hideStartChatting || view in hiddenViews || view.visibility != View.VISIBLE) return false
        if (view is TextView) return false
        val root = view.rootView ?: view
        if (getCachedRootScope(root) == ScreenScope.UPDATES || getCachedRootScope(root) == ScreenScope.CHANNELS_BROWSER) return false
        if (!isLikelyStartChattingFloatingLoader(view)) return false

        val rootTexts = mutableListOf<String>()
        collectText(root, rootTexts, 0, 160)
        if (looksLikeUpdatesTabRoot(rootTexts) || looksLikeChannelsBrowser(rootTexts)) return false
        if (!looksLikeChatsHomeRoot(rootTexts) && !looksLikeChatsHomeWeak(rootTexts)) return false

        markRootScope(root, ScreenScope.CHATS_HOME)
        hideRecommendationView(view, "start chatting floating loader draw", listOf(view.javaClass.name, resourceEntryName(view).orEmpty()))
        return true
    }

    private fun hideStartChattingLoaderVisualBeforeDraw(view: View): Boolean {
        if (!featureState.hideStartChatting || view in hiddenViews || view.visibility != View.VISIBLE) return false
        val root = view.rootView ?: view
        if (getCachedRootScope(root) == ScreenScope.UPDATES || getCachedRootScope(root) == ScreenScope.CHANNELS_BROWSER) return false
        val rootHeight = root.height.takeIf { it > 0 } ?: root.resources.displayMetrics.heightPixels
        if (!isLikelyStartChattingLoaderVisual(view, rootHeight)) return false

        val rootTexts = mutableListOf<String>()
        collectText(root, rootTexts, 0, 160)
        if (looksLikeUpdatesTabRoot(rootTexts) || looksLikeChannelsBrowser(rootTexts)) return false
        if (!looksLikeChatsHomeRoot(rootTexts) && !looksLikeChatsHomeWeak(rootTexts)) return false

        markRootScope(root, ScreenScope.CHATS_HOME)
        hideRecommendationView(view, "start chatting loader visual", listOf(view.javaClass.name, resourceEntryName(view).orEmpty()))
        return true
    }

    private fun showChatFilterBarNodes(view: View, rootHeight: Int, depth: Int): Boolean {
        if (depth > 14 || view.visibility != View.VISIBLE || view in hiddenViews) return false
        if (!isChatsHomeRoot(view)) return false

        if (view is ViewGroup) {
            val texts = mutableListOf<String>()
            collectText(view, texts, 0, 20)
            if (looksLikeChatFilterBar(texts) && isLikelyChatFilterBar(view, rootHeight)) {
                forceShowChatFilterBar(view, texts)
                return true
            }

            var shown = false
            for (index in 0 until view.childCount) {
                if (showChatFilterBarNodes(view.getChildAt(index), rootHeight, depth + 1)) {
                    shown = true
                }
            }
            return shown
        }
        return false
    }

    private fun shouldChatFilterCandidate(view: View): Boolean {
        if (view in hiddenViews) return false
        if (isKnownChatFilterHost(view)) return true
        val resourceName = resourceEntryName(view).orEmpty()
        if (resourceName.contains("conversations_swipe_to_reveal_filter", ignoreCase = true) ||
            resourceName.contains("conversations_inbox_filters", ignoreCase = true)
        ) {
            return true
        }
        if (view is TextView) {
            val normalized = view.text?.toString()?.trim().orEmpty().normalizeUiText()
            if (isChatFilterChipText(normalized)) return true
        }
        if (view !is ViewGroup || view.childCount == 0 || view.childCount > 16) return false
        val texts = mutableListOf<String>()
        collectText(view, texts, 0, 20)
        return looksLikeChatFilterBar(texts)
    }

    private fun showChatFiltersAround(view: View): Boolean {
        view.hashCode()
        return false
    }

    private fun forceShowChatFilterBar(view: View, texts: List<String>) {
        forceShowKnownChatFilterHost(view)
        if (shownChatFilterBars.add(view)) {
            logBlock("Pinned WhatsApp chat filter bar: ${texts.joinToString(" | ").take(160)}")
        }
    }

    private fun forceShowKnownChatFilterHost(view: View) {
        var current: View? = view
        repeat(4) {
            val candidate = current ?: return@repeat
            if (candidate.visibility != View.VISIBLE) candidate.visibility = View.VISIBLE
            candidate.alpha = 1f
            candidate.translationY = 0f
            candidate.scaleY = 1f
            candidate.layoutParams?.let { params ->
                if (params.height == 0) {
                    params.height = ViewGroup.LayoutParams.WRAP_CONTENT
                    candidate.layoutParams = params
                }
            }
            (candidate as? ViewGroup)?.let {
                it.clipChildren = false
                it.clipToPadding = false
            }
            current = candidate.parent as? View
        }
    }

    private fun forceShowChatFilterRecyclerView(recyclerView: RecyclerView) {
        recyclerView.visibility = View.VISIBLE
        recyclerView.alpha = 1f
        recyclerView.translationY = 0f
        recyclerView.scaleY = 1f
        recyclerView.suppressLayout(false)
        recyclerView.isClickable = true
        recyclerView.isFocusable = false
        recyclerView.adapter?.notifyDataSetChanged()
        findAncestor(recyclerView, 4) { candidate ->
            candidate is ViewGroup && isKnownChatFilterHost(candidate)
        }?.let { host ->
            forceShowKnownChatFilterHost(host)
            host.isClickable = false
            host.isFocusable = false
        }
        if (shownChatFilterBars.add(recyclerView)) {
            logBlock("Pinned WhatsApp chat filter recycler items=${recyclerView.adapter?.itemCount ?: -1}")
        }
    }

    private fun forceShowChatFilterHostTree(view: View) {
        forceShowKnownChatFilterHost(view)
        if (view is RecyclerView) {
            forceShowChatFilterRecyclerView(view)
        }
        if (view !is ViewGroup) return
        view.clipChildren = false
        view.clipToPadding = false
        for (index in 0 until view.childCount) {
            val child = view.getChildAt(index)
            val resourceName = resourceEntryName(child).orEmpty()
            if (
                child is RecyclerView ||
                child is TextView ||
                resourceName.contains("conversations_inbox_filters", ignoreCase = true) ||
                resourceName.contains("conversations_swipe_to_reveal_filter", ignoreCase = true)
            ) {
                child.visibility = View.VISIBLE
                child.alpha = 1f
                child.translationY = 0f
                child.scaleY = 1f
            }
            if (child is ViewGroup && index < 8) {
                forceShowChatFilterHostTree(child)
            }
        }
    }

    private fun looksLikeChatFilterBar(texts: List<String>): Boolean {
        val normalized = texts.map { it.normalizeUiText() }.filter { it.isNotBlank() }
        if (normalized.any { it == "updates" || it == "status" || it == "channels" || it == "calls" }) return false
        val chipCount = normalized.count(::isChatFilterChipText)
        return "all" in normalized && chipCount >= 3
    }

    private fun isChatFilterChipText(normalized: String): Boolean {
        return normalized == "all" ||
            normalized == "unread" ||
            normalized == "favorites" ||
            normalized == "favourites" ||
            normalized == "groups"
    }

    private fun isLikelyChatFilterBar(view: View, rootHeight: Int): Boolean {
        if (view.width <= 0 || view.height <= 0) return false
        if (view.height > rootHeight * 0.16f) return false
        val location = IntArray(2)
        runCatching { view.getLocationOnScreen(location) }
        val top = location[1]
        return top > rootHeight * 0.08f && top < rootHeight * 0.42f
    }

    private fun hideUpdatesChannelsSection(root: View, rootHeight: Int): Boolean {
        val section = findUpdatesChannelsSection(root, rootHeight, 0) ?: return false
        val texts = mutableListOf<String>()
        collectText(section, texts, 0, 80)
        hideRecommendationView(section, "channels section", texts)
        return true
    }

    private fun findUpdatesChannelsSection(view: View, rootHeight: Int, depth: Int): View? {
        if (depth > 18 || view.visibility != View.VISIBLE || view in hiddenViews) return null
        if (view !is ViewGroup) return null

        for (index in 0 until view.childCount) {
            findUpdatesChannelsSection(view.getChildAt(index), rootHeight, depth + 1)?.let { return it }
        }

        val texts = mutableListOf<String>()
        collectText(view, texts, 0, 80)
        if (looksLikeUpdatesChannelsSection(texts) && isSafeToHideChannelSection(view, rootHeight)) {
            return view
        }
        return null
    }

    private fun scanHideChannelsNodes(view: View, rootHeight: Int, depth: Int): Boolean {
        if (depth > 18 || view.visibility != View.VISIBLE || view in hiddenViews) return false

        val contentDescription = view.contentDescription?.toString()?.trim().orEmpty()
        val normalizedContentDescription = contentDescription.normalizeUiText()
        if (
            isChannelCollapseToggleText(normalizedContentDescription) &&
            isSafeToHide(view, rootHeight)
        ) {
            hideRecommendationView(view, "channels toggle", listOf(contentDescription))
            return true
        }

        if (view is TextView) {
            val text = view.text?.toString()?.trim().orEmpty()
            val normalized = text.ifBlank { contentDescription }.normalizeUiText()
            if (isChannelsSectionTriggerText(normalized)) {
                rememberChannelHeaderPosition(view, normalized)
                hideChannelSectionChromeAround(view)
                return true
            }
        }

        if (view !is ViewGroup) return false

        var childHidden = false
        for (index in 0 until view.childCount) {
            if (scanHideChannelsNodes(view.getChildAt(index), rootHeight, depth + 1)) {
                childHidden = true
            }
        }

        val texts = mutableListOf<String>()
        collectText(view, texts, 0, 48)
        if (looksLikeRecommendationRow(texts) && isRecommendationRowContainer(texts) && isSafeToHide(view, rootHeight)) {
            hideRecommendationView(view, "channels row", texts)
            return true
        }

        return childHidden
    }

    private fun scanRecommendationLeafRows(view: View, rootHeight: Int, depth: Int): Boolean {
        if (depth > 18 || view.visibility != View.VISIBLE || view in hiddenViews) return false
        if (view !is ViewGroup) return false

        var childHasRecommendationRow = false
        for (index in 0 until view.childCount) {
            if (scanRecommendationLeafRows(view.getChildAt(index), rootHeight, depth + 1)) {
                childHasRecommendationRow = true
            }
        }

        val texts = mutableListOf<String>()
        collectText(view, texts, 0, 36)
        if (looksLikeChannelsBrowserRow(texts)) {
            markRootScope(view, ScreenScope.CHANNELS_BROWSER)
            return true
        }
        val isRow = looksLikeRecommendationRow(texts) && isRecommendationRowContainer(texts)
        if (!isRow) return childHasRecommendationRow
        if (childHasRecommendationRow) return true
        if (!isSafeToHide(view, rootHeight)) return true

        hideRecommendationView(view, "leaf row", texts)
        return true
    }

    private fun scanStartChattingNodes(view: View, rootHeight: Int, depth: Int): Boolean {
        if (depth > 18 || view.visibility != View.VISIBLE || view in hiddenViews) return false
        val chatsHome = isChatsHomeRoot(view)

        if (chatsHome && view is ProgressBar && isLikelyStartChattingLoader(view, rootHeight)) {
            hideRecommendationView(view, "start chatting loader", listOf(view.javaClass.name))
            return true
        }

        if (view is TextView) {
            val normalized = view.text?.toString()?.trim().orEmpty().normalizeUiText()
            if (isStartChattingTriggerText(normalized)) {
                hideStartChattingAround(view)
                return true
            }
            if (chatsHome && normalized == "see all" && isBelowChatsListIntro(view, rootHeight)) {
                hideStartChattingSeeAllAround(view)
                return true
            }
        }

        if (view !is ViewGroup) return false

        var childHidden = false
        for (index in 0 until view.childCount) {
            if (scanStartChattingNodes(view.getChildAt(index), rootHeight, depth + 1)) {
                childHidden = true
            }
        }

        val texts = mutableListOf<String>()
        collectText(view, texts, 0, 48)
        if (chatsHome && looksLikeStartChattingLoaderContainer(view, texts, rootHeight)) {
            hideRecommendationView(view, "start chatting loader container", texts.ifEmpty { listOf(view.javaClass.name) })
            return true
        }

        if (chatsHome && looksLikeStartChattingSeeAll(texts) && isBelowChatsListIntro(view, rootHeight) && isSafeToHide(view, rootHeight)) {
            hideRecommendationView(view, "start chatting see all", texts)
            return true
        }

        if (chatsHome && looksLikeStandaloneStartChattingSeeAll(view, texts, rootHeight) && isSafeToHide(view, rootHeight)) {
            hideRecommendationView(view, "start chatting see all standalone", texts)
            return true
        }

        if (looksLikeStartChattingSuggestionRow(texts) && isSafeToHide(view, rootHeight)) {
            hideRecommendationView(view, "start chatting row", texts)
            return true
        }

        if (looksLikeStartChattingHeaderRow(texts) && isSafeToHide(view, rootHeight)) {
            hideRecommendationView(view, "start chatting header", texts)
            return true
        }

        return childHidden
    }

    private fun scanNode(
        view: View,
        rootHeight: Int,
        depth: Int,
        updatesScreen: Boolean,
        hasRecommendationSurface: Boolean
    ) {
        if (depth > 16 || view.visibility != View.VISIBLE || view in hiddenViews) return

        if (view is TextView) {
            val contentDescription = view.contentDescription?.toString()?.trim().orEmpty()
            val text = view.text?.toString()?.trim().orEmpty()
            rememberChannelHeaderPosition(view, text.ifBlank { contentDescription }.normalizeUiText())
        }

        if (view is ViewGroup) {
            val texts = mutableListOf<String>()
            collectText(view, texts, 0, 40)
            if (
                texts.isNotEmpty() &&
                updatesScreen &&
                hasRecommendationSurface &&
                looksLikeRecommendationRow(texts) &&
                isSafeToHide(view, rootHeight)
            ) {
                hideRecommendationView(view, "container", texts)
                return
            }

            for (index in 0 until view.childCount) {
                scanNode(view.getChildAt(index), rootHeight, depth + 1, updatesScreen, hasRecommendationSurface)
            }
        }
    }

    private fun handleRecommendationTextBind(textView: TextView) {
        val text = textView.text?.toString()?.trim().orEmpty()
        if (text.isBlank()) return
        val normalized = text.normalizeUiText()
        rememberChannelHeaderPosition(textView, normalized)

        if (featureState.hideCommunitiesTab && isCommunitiesText(normalized)) {
            hideCommunitiesMenuItemIfNeeded(textView)
            textView.post { runSafe("TextView hide communities tab posted") { hideCommunitiesMenuItemIfNeeded(textView) } }
            return
        }

        if (featureState.hideStartChatting) {
            when {
                isStartChattingTriggerText(normalized) -> {
                    hideStartChattingAround(textView)
                    textView.post { runSafe("TextView hide start chatting posted") { hideStartChattingAround(textView) } }
                    textView.postDelayed({ runSafe("TextView hide start chatting delayed") { hideStartChattingAround(textView) } }, 80L)
                }
                normalized == "chat" -> {
                    hideStartChattingSuggestionRowAround(textView)
                    textView.post { runSafe("TextView hide start chatting row posted") { hideStartChattingSuggestionRowAround(textView) } }
                    textView.postDelayed({ runSafe("TextView hide start chatting row delayed") { hideStartChattingSuggestionRowAround(textView) } }, 80L)
                }
                normalized == "see all" && isChatsHomeRoot(textView) -> {
                    hideStartChattingSeeAllAround(textView)
                    textView.post { runSafe("TextView hide start chatting see all posted") { hideStartChattingSeeAllAround(textView) } }
                    textView.postDelayed({ runSafe("TextView hide start chatting see all delayed") { hideStartChattingSeeAllAround(textView) } }, 80L)
                }
            }
        }

        if (featureState.hideChannels) {
            when {
                isChannelsSectionTriggerText(normalized) -> {
                    hideChannelSectionChromeAround(textView)
                    textView.post { runSafe("TextView hide channels chrome posted") { hideChannelSectionChromeAround(textView) } }
                    textView.postDelayed({ runSafe("TextView hide channels chrome delayed") { hideChannelSectionChromeAround(textView) } }, 80L)
                }
                isRecommendationRowTriggerText(normalized) -> {
                    hideRecommendationRowAround(textView, guardChannelsBrowser = false)
                    textView.post { runSafe("TextView hide channels row posted") { hideRecommendationRowAround(textView, guardChannelsBrowser = false) } }
                    textView.postDelayed({ runSafe("TextView hide channels row delayed") { hideRecommendationRowAround(textView, guardChannelsBrowser = false) } }, 80L)
                }
            }
            return
        }

        if (!shouldHideRecommendationsNow()) return
        if (markChannelsBrowserFromLocalChrome(textView)) return
        if (isChannelsBrowserRoot(textView)) return

        when {
            isRecommendationRowTriggerText(normalized) -> {
                hideRecommendationRowAround(textView, guardChannelsBrowser = true)
                textView.post { runSafe("TextView recommendation row posted") { hideRecommendationRowAround(textView, guardChannelsBrowser = true) } }
                textView.postDelayed({ runSafe("TextView recommendation row delayed") { hideRecommendationRowAround(textView, guardChannelsBrowser = true) } }, 50L)
            }
        }
    }

    private fun suppressChannelTextBeforeBind(textView: TextView, incomingText: String): Boolean {
        val normalized = incomingText.normalizeUiText()
        val hideForChannels = featureState.hideChannels && isChannelsSectionTriggerText(normalized)
        val hideForStartChatting = featureState.hideStartChatting && normalized == "start chatting"
        val root = textView.rootView ?: textView
        val rootHeight = root.height.takeIf { it > 0 } ?: root.resources.displayMetrics.heightPixels
        val hideForStartChattingSeeAll = featureState.hideStartChatting &&
            normalized == "see all" &&
            isChatsHomeRoot(textView) &&
            isBelowChatsListIntro(textView, rootHeight)
        val hideForCommunitiesTab = featureState.hideCommunitiesTab &&
            isCommunitiesText(normalized) &&
            isLikelyBottomNavigationLabel(textView)
        if (!hideForChannels && !hideForStartChatting && !hideForStartChattingSeeAll && !hideForCommunitiesTab) return false

        if (hideForCommunitiesTab) {
            textView.post { runSafe("communities tab menu pre-bind") { hideCommunitiesMenuItemIfNeeded(textView) } }
            return false
        }

        textView.visibility = View.GONE
        textView.alpha = 0f
        textView.minimumHeight = 0
        textView.layoutParams?.apply {
            height = 0
            textView.layoutParams = this
        }
        if (hideForStartChatting) {
            textView.post { runSafe("start chatting pre-bind parent posted") { hideStartChattingAround(textView) } }
            textView.postDelayed({ runSafe("start chatting pre-bind parent delayed") { hideStartChattingAround(textView) } }, 40L)
        } else if (hideForStartChattingSeeAll) {
            textView.post { runSafe("start chatting see all pre-bind parent posted") { hideStartChattingSeeAllAround(textView) } }
            textView.postDelayed({ runSafe("start chatting see all pre-bind parent delayed") { hideStartChattingSeeAllAround(textView) } }, 40L)
        } else {
            textView.post { runSafe("channel chrome pre-bind parent posted") { hideChannelSectionChromeAround(textView, incomingText) } }
            textView.postDelayed({ runSafe("channel chrome pre-bind parent delayed") { hideChannelSectionChromeAround(textView, incomingText) } }, 40L)
        }
        return true
    }

    private fun hideStartChattingAround(textView: TextView): Boolean {
        val text = textView.text?.toString()?.trim().orEmpty()
        val root = textView.rootView ?: textView
        val rootHeight = root.height.takeIf { it > 0 } ?: root.resources.displayMetrics.heightPixels

        findAncestor(textView, 5) { candidate ->
            val group = candidate as? ViewGroup ?: return@findAncestor false
            val texts = mutableListOf<String>()
            collectText(group, texts, 0, 40)
            looksLikeStartChattingHeaderRow(texts) && isSafeToHide(group, rootHeight)
        }?.let { header ->
            val texts = mutableListOf<String>()
            collectText(header, texts, 0, 40)
            hideRecommendationView(header, "start chatting header", texts.ifEmpty { listOf(text) })
        } ?: hideRecommendationView(textView, "start chatting header", listOf(text))

        root.post { runSafe("start chatting rows posted") { scanStartChattingNodes(root, rootHeight, 0) } }
        root.postDelayed({ runSafe("start chatting rows delayed-240") { scanStartChattingNodes(root, rootHeight, 0) } }, 240L)
        root.postDelayed({ runSafe("start chatting rows delayed") { scanStartChattingNodes(root, rootHeight, 0) } }, 80L)
        return true
    }

    private fun hideStartChattingSuggestionRowAround(textView: TextView): Boolean {
        val root = textView.rootView ?: textView
        val rootHeight = root.height.takeIf { it > 0 } ?: root.resources.displayMetrics.heightPixels
        findAncestor(textView, 8) { candidate ->
            val group = candidate as? ViewGroup ?: return@findAncestor false
            val texts = mutableListOf<String>()
            collectText(group, texts, 0, 48)
            looksLikeStartChattingSuggestionRow(texts) && isSafeToHide(group, rootHeight)
        }?.let { row ->
            val texts = mutableListOf<String>()
            collectText(row, texts, 0, 48)
            hideRecommendationView(row, "start chatting row", texts)
            return true
        }
        return false
    }

    private fun hideStartChattingSeeAllAround(textView: TextView): Boolean {
        val root = textView.rootView ?: textView
        val rootHeight = root.height.takeIf { it > 0 } ?: root.resources.displayMetrics.heightPixels
        findAncestor(textView, 7) { candidate ->
            val group = candidate as? ViewGroup ?: return@findAncestor false
            val texts = mutableListOf<String>()
            collectText(group, texts, 0, 24)
            looksLikeStartChattingSeeAll(texts) && isSafeToHide(group, rootHeight)
        }?.let { button ->
            val texts = mutableListOf<String>()
            collectText(button, texts, 0, 24)
            hideRecommendationView(button, "start chatting see all", texts)
            return true
        }
        hideRecommendationView(textView, "start chatting see all text", listOf(textView.text?.toString().orEmpty()))
        return true
    }

    private fun hideCommunitiesMenuItemIfNeeded(view: View): Boolean {
        val nav = findBottomNavigationView(view) ?: return false
        if (nav in hiddenCommunitiesNavs) return false
        val menu = runCatching { nav.javaClass.getMethod("getMenu").invoke(nav) }.getOrNull() ?: return false
        val size = runCatching { menu.javaClass.getMethod("size").invoke(menu) as Int }.getOrNull() ?: return false
        for (index in 0 until size) {
            val item = runCatching { menu.javaClass.getMethod("getItem", Int::class.javaPrimitiveType).invoke(menu, index) }.getOrNull()
                ?: continue
            val title = runCatching { item.javaClass.getMethod("getTitle").invoke(item)?.toString().orEmpty() }.getOrNull().orEmpty()
            if (!isCommunitiesText(title.normalizeUiText())) continue
            val alreadyHidden = runCatching {
                item.javaClass.getMethod("isVisible").invoke(item) == false
            }.getOrDefault(false)
            if (alreadyHidden) {
                hiddenCommunitiesNavs += nav
                return false
            }
            runCatching { item.javaClass.getMethod("setVisible", Boolean::class.javaPrimitiveType).invoke(item, false) }
            hiddenCommunitiesNavs += nav
            logBlock("Hidden WhatsApp bottom navigation Communities menu item")
            return true
        }
        return false
    }

    private fun hideBoundStartChatting(
        adapter: RecyclerView.Adapter<*>,
        holder: RecyclerView.ViewHolder,
        position: Int
    ): Boolean {
        val view = holder.itemView
        if (view in hiddenViews || view.visibility == View.GONE) return false
        if (getCachedRootScope(view) == ScreenScope.UPDATES || getCachedRootScope(view) == ScreenScope.CHANNELS_BROWSER) {
            return false
        }

        val root = view.rootView ?: view
        val rootHeight = root.height.takeIf { it > 0 } ?: root.resources.displayMetrics.heightPixels
        val rootTexts = mutableListOf<String>()
        collectText(root, rootTexts, 0, 180)
        val chatsHome = looksLikeChatsHomeRoot(rootTexts) || getCachedRootScope(view) == ScreenScope.CHATS_HOME
        if (!chatsHome) return false
        markRootScope(root, ScreenScope.CHATS_HOME)

        val texts = mutableListOf<String>()
        collectText(view, texts, 0, 64)
        val itemViewType = runCatching { adapter.getItemViewType(position) }.getOrNull()
        val model = findAdapterItem(adapter, position)
        val modelText = model?.toString()?.takeIf { it.isNotBlank() }
        val allSignals = buildList {
            addAll(texts)
            modelText?.let { add(it) }
            itemViewType?.let { add("viewType=$it") }
        }

        val shouldHide = looksLikeStartChattingHeaderRow(allSignals) ||
            looksLikeStartChattingSuggestionRow(allSignals) ||
            (looksLikeStartChattingSeeAll(allSignals) && isBelowChatsListIntro(view, rootHeight)) ||
            looksLikeStartChattingModel(model)

        if (!shouldHide) {
            scanStartChattingNodes(view, rootHeight, 0)
            return false
        }

        hideRecommendationView(
            view,
            "bound start chatting${itemViewType?.let { " type=$it" }.orEmpty()}",
            allSignals.ifEmpty { listOf(view.javaClass.name) }
        )
        root.post { runSafe("bound start chatting posted") { scanStartChattingNodes(root, rootHeight, 0) } }
        return true
    }

    private fun hideChannelCopyAndToggleAround(textView: TextView, text: String): Boolean {
        findAncestor(textView, 6) { candidate ->
            val group = candidate as? ViewGroup ?: return@findAncestor false
            val texts = mutableListOf<String>()
            collectText(group, texts, 0, 32)
            val normalizedTexts = texts.map { it.normalizeUiText() }
            normalizedTexts.any { isChannelsCopyText(it) || it == "find channels to follow" } &&
                normalizedTexts.none { it == "updates" || it == "status" || it == "add status" || it == "recent updates" }
        }?.let { block ->
            val texts = mutableListOf<String>()
            collectText(block, texts, 0, 32)
            hideRecommendationView(block, "channels copy/toggle", texts.ifEmpty { listOf(text) })
            return true
        }
        hideRecommendationView(textView, "channels copy/toggle", listOf(text))
        return true
    }

    private fun hideChannelSectionChromeAround(textView: TextView) {
        val text = textView.text?.toString()?.trim().orEmpty()
        hideChannelSectionChromeAround(textView, text)
    }

    private fun hideChannelSectionChromeAround(textView: TextView, fallbackText: String) {
        val text = textView.text?.toString()?.trim().orEmpty().ifBlank { fallbackText }
        val normalized = text.normalizeUiText()
        rememberChannelHeaderPosition(textView, normalized)

        if (normalized == "explore more" || normalized == "create channel") {
            val root = textView.rootView ?: textView
            val rootHeight = root.height.takeIf { it > 0 } ?: root.resources.displayMetrics.heightPixels
            val rootWidth = root.width.takeIf { it > 0 } ?: root.resources.displayMetrics.widthPixels
            findAncestor(textView, 5) { candidate ->
                val group = candidate as? ViewGroup ?: return@findAncestor false
                val texts = mutableListOf<String>()
                collectText(group, texts, 0, 12)
                val normalizedTexts = texts.map { it.normalizeUiText() }.filter { it.isNotBlank() }
                val textMatch = normalizedTexts.isNotEmpty() &&
                    normalizedTexts.all { it == normalized || it == "explore more" || it == "create channel" }
                val buttonShapeMatch = group.width >= rootWidth * 0.55f &&
                    group.height in 1..(rootHeight * 0.16f).toInt()
                textMatch || buttonShapeMatch
            }?.let { button ->
                val texts = mutableListOf<String>()
                collectText(button, texts, 0, 12)
                hideRecommendationView(button, "channels chrome", texts)
                return
            }
        }

        if (isChannelsCopyText(normalized)) {
            if (hideChannelCopyAndToggleAround(textView, text)) return
        }

        hideRecommendationView(textView, "channels chrome", listOf(text))
    }

    private fun hideRecommendationCopyAround(textView: TextView, text: String) {
        if (markChannelsBrowserFromLocalChrome(textView)) return
        findAncestor(textView, 5) { candidate ->
            val group = candidate as? ViewGroup ?: return@findAncestor false
            val texts = mutableListOf<String>()
            collectText(group, texts, 0, 24)
            if (looksLikeChannelsBrowserLocal(texts)) {
                markRootScope(group, ScreenScope.CHANNELS_BROWSER)
                return@findAncestor false
            }
            val normalizedTexts = texts.map { it.normalizeUiText() }
            looksLikeRecommendationCopy(texts) &&
                normalizedTexts.none { it == "channels" || it == "explore more" || it == "create channel" } &&
                normalizedTexts.none { it == "status" || it == "add status" || it == "updates" }
        }?.let { block ->
            hideRecommendationView(block, "copy block", listOf(text))
            return
        }

        hideRecommendationView(textView, "copy text", listOf(text))
    }

    private fun hideRecommendationRowAround(textView: TextView, guardChannelsBrowser: Boolean) {
        if (guardChannelsBrowser && markChannelsBrowserFromLocalChrome(textView)) return
        findAncestor(textView, 8) { candidate ->
            val group = candidate as? ViewGroup ?: return@findAncestor false
            val texts = mutableListOf<String>()
            collectText(group, texts, 0, 48)
            if (guardChannelsBrowser && (looksLikeChannelsBrowserLocal(texts) || looksLikeChannelsBrowserRow(texts))) {
                markRootScope(group, ScreenScope.CHANNELS_BROWSER)
                return@findAncestor false
            }
            looksLikeRecommendationRow(texts) && isRecommendationRowContainer(texts)
        }?.let { row ->
            val texts = mutableListOf<String>()
            collectText(row, texts, 0, 48)
            hideRecommendationView(row, "text-bound row", texts)
        }
    }

    private fun findAncestor(view: View, maxDepth: Int, predicate: (View) -> Boolean): View? {
        var current = view.parent as? View
        repeat(maxDepth) {
            val candidate = current ?: return null
            if (candidate in hiddenViews || candidate.visibility == View.GONE) return null
            if (predicate(candidate)) return candidate
            current = candidate.parent as? View
        }
        return null
    }

    private fun isRecommendationRowContainer(texts: List<String>): Boolean {
        val normalized = texts.map { it.normalizeUiText() }
        if (normalized.any { it == "updates" || it == "status" || it == "recent updates" || it == "add status" }) return false
        if (normalized.any { it == "channels" || it == "explore more" || it == "create channel" || it == "find channels to follow" }) return false
        return true
    }

    private fun isRecommendationRowTriggerText(normalized: String): Boolean {
        return normalized == "follow" || normalized == "follow channel" || normalized.hasFollowerCountText()
    }

    private fun isStartChattingTriggerText(normalized: String): Boolean {
        return normalized == "start chatting"
    }

    private fun isCommunitiesText(normalized: String): Boolean {
        return normalized == "communities" || normalized == "community"
    }

    private fun looksLikeChatsHomeRoot(texts: List<String>): Boolean {
        val normalized = texts.map { it.normalizeUiText() }
        if (normalized.any { it == "status" || it == "channels" }) {
            return false
        }
        val hasChatsNav = normalized.any { it == "chats" }
        val hasChatsSearch = normalized.any { "ask meta ai or search" in it || it == "search" }
        val hasChatsFooter = normalized.any {
            it == "tap and hold on a chat for more options" ||
                "personal messages are end-to-end encrypted" in it ||
                "end-to-end encrypted" in it
        }
        return (("whatsapp" in normalized || hasChatsNav) && hasChatsSearch) ||
            (hasChatsNav && hasChatsFooter) ||
            (hasChatsSearch && hasChatsFooter)
    }

    private fun looksLikeChatsHomeWeak(texts: List<String>): Boolean {
        val normalized = texts.map { it.normalizeUiText() }
        if (normalized.any { it == "status" || it == "channels" }) {
            return false
        }
        val hasWhatsAppTitle = "whatsapp" in normalized
        val hasChatsSearch = normalized.any { "ask meta ai or search" in it || it == "search" }
        val hasChatsFooter = normalized.any {
            it == "tap and hold on a chat for more options" ||
                "personal messages are end-to-end encrypted" in it ||
                "end-to-end encrypted" in it
        }
        return hasWhatsAppTitle && (hasChatsSearch || hasChatsFooter)
    }

    private fun isChatsHomeRoot(view: View): Boolean {
        if (getCachedRootScope(view) == ScreenScope.CHATS_HOME) return true
        val root = view.rootView ?: view
        val texts = mutableListOf<String>()
        collectText(root, texts, 0, 180)
        if (!looksLikeChatsHomeRoot(texts)) return false
        markRootScope(root, ScreenScope.CHATS_HOME)
        return true
    }

    private fun looksLikeStartChattingHeaderRow(texts: List<String>): Boolean {
        val normalized = texts.map { it.normalizeUiText() }
        return normalized.any { it == "start chatting" || it.contains("startchatting") || it.contains("start_chatting") } &&
            normalized.none { it == "updates" || it == "status" || it == "channels" }
    }

    private fun looksLikeStartChattingSeeAll(texts: List<String>): Boolean {
        val normalized = texts.map { it.normalizeUiText() }
        return normalized.any { it == "see all" } &&
            normalized.none { it == "updates" || it == "status" || it == "channels" } &&
            normalized.none { it == "follow" || it == "follow channel" || it.hasFollowerCountText() }
    }

    private fun looksLikeStandaloneStartChattingSeeAll(view: View, texts: List<String>, rootHeight: Int): Boolean {
        val normalized = texts.map { it.normalizeUiText() }
        if (normalized.count { it == "see all" } !in 1..2) return false
        if (normalized.any { it != "see all" }) return false
        if (!isBelowChatsListIntro(view, rootHeight)) return false
        return view.width in 1..(view.resources.displayMetrics.widthPixels / 2)
    }

    private fun looksLikeStartChattingSuggestionRow(texts: List<String>): Boolean {
        val normalized = texts.map { it.normalizeUiText() }
        val hasChatAction = normalized.any { it == "chat" || it == "start chat" }
        val hasDismiss = normalized.any { it == "dismiss" || it == "remove" || it == "close" }
        val hasName = normalized.any { text ->
            text.isNotBlank() &&
                text != "chat" &&
                text != "start chat" &&
                text != "dismiss" &&
                text != "remove" &&
                text != "close"
        }
        return hasChatAction &&
            hasName &&
            normalized.none { it == "updates" || it == "status" || it == "channels" } &&
            (hasDismiss || normalized.size <= 6)
    }

    private fun looksLikeStartChattingModel(model: Any?): Boolean {
        val text = model?.toString()?.normalizeUiText().orEmpty()
        if (text.isBlank()) return false
        return ("start" in text && "chat" in text && ("suggest" in text || "recommend" in text || "contact" in text)) ||
            "startchatting" in text ||
            "start_chatting" in text ||
            ("suggest" in text && "contact" in text && "chat" in text)
    }

    private fun looksLikeStartChattingLoaderContainer(view: View, texts: List<String>, rootHeight: Int): Boolean {
        if (view !is ViewGroup || !isSafeToHide(view, rootHeight)) return false
        val normalized = texts.map { it.normalizeUiText() }
        if (normalized.any { it == "updates" || it == "status" || it == "channels" }) return false
        if (normalized.any { it == "tap and hold on a chat for more options" || "end-to-end encrypted" in it }) return false
        return containsProgressBar(view, 0)
    }

    private fun isLikelyStartChattingLoader(view: View, rootHeight: Int): Boolean {
        if (!isSafeToHide(view, rootHeight)) return false
        val location = IntArray(2)
        runCatching { view.getLocationOnScreen(location) }
        val top = location[1]
        return top > rootHeight * 0.24f && top < rootHeight * 0.72f
    }

    private fun isLikelyStartChattingLoaderVisual(view: View, rootHeight: Int): Boolean {
        if (!isSafeToHide(view, rootHeight)) return false
        val className = view.javaClass.name.lowercase()
        val resourceName = resourceEntryName(view).orEmpty().lowercase()
        val looksLoader = view is ProgressBar ||
            "progress" in className ||
            "spinner" in className ||
            "loading" in className ||
            "progress" in resourceName ||
            "spinner" in resourceName ||
            "loading" in resourceName
        if (!looksLoader) return false
        val location = IntArray(2)
        runCatching { view.getLocationOnScreen(location) }
        val top = location[1]
        val width = view.width
        val height = view.height
        if (width > view.resources.displayMetrics.widthPixels * 0.5f || height > rootHeight * 0.18f) return false
        return top > rootHeight * 0.24f && top < rootHeight * 0.72f
    }

    private fun isLikelyStartChattingFloatingLoader(view: View): Boolean {
        val root = view.rootView ?: view
        val rootHeight = root.height.takeIf { it > 0 } ?: root.resources.displayMetrics.heightPixels
        val rootWidth = root.width.takeIf { it > 0 } ?: root.resources.displayMetrics.widthPixels
        val width = view.width
        val height = view.height
        if (width !in 8..220 || height !in 8..220) return false
        if (width > rootWidth * 0.35f || height > rootHeight * 0.16f) return false
        if (view is ViewGroup) {
            val texts = mutableListOf<String>()
            collectText(view, texts, 0, 12)
            if (texts.any { it.isNotBlank() }) return false
        }

        val rootLocation = IntArray(2)
        val viewLocation = IntArray(2)
        runCatching { root.getLocationOnScreen(rootLocation) }
        runCatching { view.getLocationOnScreen(viewLocation) }
        val centerX = viewLocation[0] - rootLocation[0] + width / 2
        val centerY = viewLocation[1] - rootLocation[1] + height / 2
        if (centerX < rootWidth * 0.35f || centerX > rootWidth * 0.65f) return false
        return centerY > rootHeight * 0.24f && centerY < rootHeight * 0.50f
    }

    private fun isBelowChatsListIntro(view: View, rootHeight: Int): Boolean {
        val location = IntArray(2)
        runCatching { view.getLocationOnScreen(location) }
        val top = location[1]
        return top > rootHeight * 0.24f && top < rootHeight * 0.84f
    }

    private fun isLikelyBottomNavigationLabel(view: View): Boolean {
        val root = view.rootView ?: view
        val rootHeight = root.height.takeIf { it > 0 } ?: root.resources.displayMetrics.heightPixels
        return findAncestor(view, 8) { candidate ->
            isLikelyBottomNavigationItem(candidate, rootHeight)
        } != null
    }

    private fun isLikelyBottomNavigationItem(view: View, rootHeight: Int): Boolean {
        if (view.parent !is ViewGroup || view.width <= 0 || view.height <= 0) return false
        val location = IntArray(2)
        runCatching { view.getLocationOnScreen(location) }
        val top = location[1]
        if (top < rootHeight * 0.72f) return false

        val texts = mutableListOf<String>()
        collectText(view, texts, 0, 24)
        val normalized = texts.map { it.normalizeUiText() }
        if (!normalized.any(::isCommunitiesText)) return false

        val resourceSignals = listOf(
            "navigation_bar_item_icon_view",
            "navigation_bar_item_labels_group",
            "navigation_bar_item_large_label_view",
            "navigation_bar_item_small_label_view"
        )
        if (resourceSignals.any { hasDescendantResourceName(view, it, 0) }) return true

        return view.height < rootHeight * 0.22f && normalized.any {
            it == "chats" || it == "updates" || it == "calls" || isCommunitiesText(it)
        }
    }

    private fun hasDescendantResourceName(view: View, resourceName: String, depth: Int): Boolean {
        if (depth > 5) return false
        if (resourceEntryName(view) == resourceName) return true
        if (view !is ViewGroup) return false
        for (index in 0 until view.childCount) {
            if (hasDescendantResourceName(view.getChildAt(index), resourceName, depth + 1)) return true
        }
        return false
    }

    private fun resourceEntryName(view: View): String? {
        val id = view.id
        if (id == View.NO_ID) return null
        return runCatching { view.resources.getResourceEntryName(id) }.getOrNull()
    }

    private fun findBottomNavigationView(view: View): View? {
        val root = view.rootView ?: view
        if (isBottomNavigationView(root)) return root
        if (view !== root) {
            var current: View? = view
            repeat(10) {
                val candidate = current ?: return@repeat
                if (isBottomNavigationView(candidate)) return candidate
                current = candidate.parent as? View
            }
        }
        return findBottomNavigationViewInTree(root, 0)
    }

    private fun findBottomNavigationViewInTree(view: View, depth: Int): View? {
        if (depth > 8 || view.visibility != View.VISIBLE) return null
        if (isBottomNavigationView(view)) return view
        if (view !is ViewGroup) return null
        for (index in 0 until view.childCount) {
            findBottomNavigationViewInTree(view.getChildAt(index), depth + 1)?.let { return it }
        }
        return null
    }

    private fun isBottomNavigationView(view: View): Boolean {
        val name = view.javaClass.name
        return name.contains("BottomNavigationView") ||
            name == "com.whatsapp.ui.wds.components.bottombar.WDSBottomBar" ||
            name.contains("WDSBottomBar")
    }

    private fun containsProgressBar(view: View, depth: Int): Boolean {
        if (depth > 4) return false
        if (view is ProgressBar) return true
        if (view !is ViewGroup) return false
        for (index in 0 until view.childCount) {
            if (containsProgressBar(view.getChildAt(index), depth + 1)) return true
        }
        return false
    }

    private fun isRecommendationCopyText(normalized: String): Boolean {
        return normalized == "find channels to follow" ||
            isChannelsEmptyCopyText(normalized) ||
            "channels to follow below" in normalized ||
            ("stay updated on topics" in normalized && ("find channels" in normalized || "follow below" in normalized))
    }

    private fun isChannelsCopyText(normalized: String): Boolean {
        return normalized == "find channels to follow" ||
            isChannelsEmptyCopyText(normalized) ||
            "find channels to follow below" in normalized ||
            ("stay updated on topics" in normalized && "find channels" in normalized)
    }

    private fun isChannelsEmptyCopyText(normalized: String): Boolean {
        return "channels you follow will appear here" in normalized ||
            ("stay updated on topics" in normalized && "appear here" in normalized && "channel" in normalized)
    }

    private fun isChannelsSectionTriggerText(normalized: String): Boolean {
        return normalized == "channels" ||
            normalized == "explore more" ||
            normalized == "create channel" ||
            isChannelCollapseToggleText(normalized) ||
            isChannelsCopyText(normalized)
    }

    private fun isChannelCollapseToggleText(normalized: String): Boolean {
        return normalized == "collapse" ||
            normalized == "expand" ||
            normalized == "show less" ||
            normalized == "show more"
    }

    private fun isChannelCollapseToggleShape(view: View, rootHeight: Int): Boolean {
        if (!isPotentialChannelCollapseToggleShape(view, requireChannelAnchor = true)) return false
        val normalizedContentDescription = view.contentDescription?.toString()?.trim().orEmpty().normalizeUiText()
        if (normalizedContentDescription.isNotBlank()) {
            return isChannelCollapseToggleText(normalizedContentDescription) ||
                "collapse" in normalizedContentDescription ||
                "expand" in normalizedContentDescription ||
                "show less" in normalizedContentDescription ||
                "show more" in normalizedContentDescription ||
                normalizedContentDescription == "dismiss"
        }

        if (view !is ViewGroup) return true
        val texts = mutableListOf<String>()
        collectText(view, texts, 0, 8)
        val normalizedTexts = texts.map { it.normalizeUiText() }
        return normalizedTexts.isEmpty() ||
            normalizedTexts.any {
                isChannelCollapseToggleText(it) ||
                    "collapse" in it ||
                    "expand" in it ||
                    "show less" in it ||
                    "show more" in it
            }
    }

    private fun isPotentialChannelCollapseToggleShape(view: View): Boolean {
        return isPotentialChannelCollapseToggleShape(view, requireChannelAnchor = false)
    }

    private fun isPotentialChannelCollapseToggleShape(view: View, requireChannelAnchor: Boolean): Boolean {
        val root = view.rootView ?: view
        val rootWidth = root.width.takeIf { it > 0 } ?: root.resources.displayMetrics.widthPixels
        val rootHeight = root.height.takeIf { it > 0 } ?: root.resources.displayMetrics.heightPixels
        val width = view.width
        val height = view.height
        if (width !in 28..180 || height !in 28..180) return false
        if (kotlin.math.abs(width - height) > 48) return false

        val rootLocation = IntArray(2)
        val viewLocation = IntArray(2)
        root.getLocationOnScreen(rootLocation)
        view.getLocationOnScreen(viewLocation)
        val absoluteCenterX = viewLocation[0] - rootLocation[0] + width / 2
        val absoluteCenterY = viewLocation[1] - rootLocation[1] + height / 2
        if (absoluteCenterX < rootWidth * 0.68f) return false
        if (absoluteCenterY < rootHeight * 0.18f || absoluteCenterY > rootHeight * 0.58f) return false
        val headerTop = synchronized(channelHeaderTops) { channelHeaderTops[root] }
        if (headerTop != null) {
            if (absoluteCenterY < headerTop + 24 || absoluteCenterY > headerTop + rootHeight * 0.28f) return false
        } else if (requireChannelAnchor) {
            return false
        }
        return true
    }

    private fun hidePotentialChannelToggleImmediately(view: View): Boolean {
        val root = view.rootView ?: view
        val rootHeight = root.height.takeIf { it > 0 } ?: root.resources.displayMetrics.heightPixels
        val normalizedContentDescription = view.contentDescription?.toString()?.trim().orEmpty().normalizeUiText()
        val isExplicitToggle = isChannelCollapseToggleText(normalizedContentDescription) ||
            "collapse" in normalizedContentDescription ||
            "expand" in normalizedContentDescription ||
            "show less" in normalizedContentDescription ||
            "show more" in normalizedContentDescription
        if (!isExplicitToggle && !isChannelCollapseToggleShape(view, rootHeight)) return false
        if (!featureState.hideChannels && !shouldHideRecommendationsNow()) return false
        if (!featureState.hideChannels && !isInUpdatesRoot(view)) return false
        hideRecommendationView(
            view,
            "copy toggle immediate",
            listOf(view.contentDescription?.toString()?.trim().orEmpty().ifBlank { view.javaClass.name })
        )
        return true
    }

    private fun isInUpdatesRoot(view: View): Boolean {
        val root = view.rootView ?: view
        val cached = synchronized(rootScopes) { rootScopes[root] }
        if (cached == ScreenScope.UPDATES) return true
        if (cached == ScreenScope.CHANNELS_BROWSER) return false
        val texts = mutableListOf<String>()
        collectText(root, texts, 0, 120)
        return looksLikeUpdatesTabRoot(texts) || looksLikeUpdatesSurface(texts)
    }

    private fun rememberChannelHeaderPosition(view: View, normalized: String) {
        if (normalized != "channels") return
        val root = view.rootView ?: view
        val rootLocation = IntArray(2)
        val viewLocation = IntArray(2)
        root.getLocationOnScreen(rootLocation)
        view.getLocationOnScreen(viewLocation)
        val top = viewLocation[1] - rootLocation[1]
        if (top <= 0) return
        synchronized(channelHeaderTops) { channelHeaderTops[root] = top }
    }

    private fun shouldHideRecommendationsNow(): Boolean {
        val state = featureState
        return state.hideChannelRecommendations || state.source == "unavailable"
    }

    private fun shouldHandleChannelUiNow(): Boolean {
        val state = featureState
        return state.hideChannels ||
            state.hideChannelRecommendations ||
            state.hideCommunitiesTab ||
            state.hideStartChatting ||
            state.source == "unavailable"
    }

    private fun hideBoundRecommendation(
        adapter: RecyclerView.Adapter<*>,
        holder: RecyclerView.ViewHolder,
        position: Int
    ) {
        val view = holder.itemView
        if (view in hiddenViews || view.visibility == View.GONE) return
        if (getCachedRootScope(view) == ScreenScope.CHANNELS_BROWSER) return

        val texts = mutableListOf<String>()
        collectText(view, texts, 0, 60)
        if (looksLikeChannelsBrowserLocal(texts) || looksLikeChannelsBrowserRow(texts)) {
            markRootScope(view, ScreenScope.CHANNELS_BROWSER)
            return
        }

        val itemViewType = runCatching { adapter.getItemViewType(position) }.getOrNull()
        val model = findAdapterItem(adapter, position)
        val modelText = model?.toString()?.takeIf { it.isNotBlank() }
        val allSignals = buildList {
            addAll(texts)
            modelText?.let { add(it) }
            itemViewType?.let { add("viewType=$it") }
        }

        val shouldHide =
            looksLikeRecommendationBlock(allSignals) ||
                looksLikeRecommendationRow(allSignals) ||
                looksLikeRecommendationModel(model)

        if (!shouldHide) return

        hideRecommendationView(
            view,
            "bound row${itemViewType?.let { " type=$it" }.orEmpty()}",
            allSignals.ifEmpty { listOf(view.javaClass.name) }
        )
    }

    private fun installRuntimeUpdatesAdapterHooks(adapterClass: Class<*>) {
        val className = adapterClass.name
        if (!hookedUpdatesAdapterClasses.add(className)) return

        var hookedMethods = 0
        adapterClass.declaredMethods.forEach { method ->
            if (!Collection::class.java.isAssignableFrom(method.returnType)) return@forEach

            runCatching {
                XposedBridge.hookMethod(
                    method,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam<*>) {
                            runSafe("${adapterClass.name}.${method.name}") {
                                val collection = param.result as? MutableCollection<*> ?: return@runSafe
                                filterRecommendationModels(collection, "${adapterClass.name}.${method.name}")
                            }
                        }
                    }
                )
                hookedMethods++
            }.onFailure { throwable ->
                logError("Failed to hook WhatsApp updates adapter data method ${adapterClass.name}.${method.name}", throwable)
            }
        }

        logInfo("Installed direct WhatsApp updates adapter hooks on $className methods=$hookedMethods")
    }

    private fun filterRecommendationModels(collection: MutableCollection<*>, source: String) {
        if (collection.isEmpty()) return
        val hasRecommendationRows = collection.any { looksLikeRecommendationModel(it) }
        if (!hasRecommendationRows) return

        @Suppress("UNCHECKED_CAST")
        val mutableCollection = collection as? MutableCollection<Any?> ?: return
        val before = mutableCollection.size
        mutableCollection.removeAll { item ->
            looksLikeRecommendationModel(item)
        }
        val removed = before - mutableCollection.size
        if (removed > 0) {
            logBlock("Filtered WhatsApp channel recommendation models from $source removed=$removed")
        }
    }

    private fun isLikelyUpdatesAdapter(adapter: RecyclerView.Adapter<*>, itemView: View?): Boolean {
        if (itemView != null && getCachedRootScope(itemView) == ScreenScope.CHANNELS_BROWSER) return false
        if (adapter.javaClass.name.contains("status.updates", ignoreCase = true) ||
            hasUpdatesFragmentField(adapter)
        ) {
            if (itemView != null && getCachedRootScope(itemView) == ScreenScope.CHANNELS_BROWSER) return false
            itemView?.let { markRootScope(it, ScreenScope.UPDATES) }
            return true
        }

        if (itemView != null && getCachedRootScope(itemView) != ScreenScope.CHANNELS_BROWSER) return true

        return false
    }

    private fun isChannelsBrowserRoot(view: View): Boolean {
        val root = view.rootView ?: view
        return updateRootScope(root) == ScreenScope.CHANNELS_BROWSER
    }

    private fun getCachedRootScope(view: View): ScreenScope? {
        val root = view.rootView ?: view
        return synchronized(rootScopes) { rootScopes[root] }
    }

    private fun markRootScope(view: View, scope: ScreenScope) {
        val root = view.rootView ?: view
        synchronized(rootScopes) { rootScopes[root] = scope }
    }

    private fun updateRootScope(root: View, knownTexts: List<String>? = null): ScreenScope? {
        val texts = mutableListOf<String>()
        if (knownTexts == null) {
            collectText(root, texts, 0, 240)
        } else {
            texts += knownTexts
        }

        val nextScope = when {
            looksLikeUpdatesTabRoot(texts) || looksLikeUpdatesSurface(texts) -> ScreenScope.UPDATES
            looksLikeChannelsBrowser(texts) -> ScreenScope.CHANNELS_BROWSER
            looksLikeChatsHomeRoot(texts) -> ScreenScope.CHATS_HOME
            else -> null
        }
        if (nextScope != null) {
            synchronized(rootScopes) { rootScopes[root] = nextScope }
            return nextScope
        }
        return synchronized(rootScopes) { rootScopes[root] }
    }

    private fun hasUpdatesFragmentField(adapter: RecyclerView.Adapter<*>): Boolean {
        var current: Class<*>? = adapter.javaClass
        var inspected = 0
        while (current != null && current != RecyclerView.Adapter::class.java && inspected < 4) {
            current.declaredFields.forEach { field ->
                val typeName = field.type.name
                if (typeName == "com.whatsapp.status.updates.ui.UpdatesFragment" ||
                    typeName.contains(".status.updates.", ignoreCase = true)
                ) {
                    return true
                }

                val value = runCatching {
                    field.isAccessible = true
                    field.get(adapter)
                }.getOrNull()
                val valueName = value?.javaClass?.name.orEmpty()
                if (valueName == "com.whatsapp.status.updates.ui.UpdatesFragment" ||
                    valueName.contains(".status.updates.", ignoreCase = true)
                ) {
                    return true
                }
            }
            current = current.superclass
            inspected++
        }
        return false
    }

    private fun findAdapterItem(adapter: RecyclerView.Adapter<*>, position: Int): Any? {
        if (position < 0) return null

        fun itemFrom(value: Any?): Any? {
            if (value is List<*> && position < value.size) return value[position]
            val resolved = runCatching {
                val method = value?.javaClass?.methods?.firstOrNull {
                    it.name == "getValue" && it.parameterTypes.isEmpty()
                } ?: return@runCatching null
                method.invoke(value)
            }.getOrNull()
            if (resolved is List<*> && position < resolved.size) return resolved[position]
            return null
        }

        var current: Class<*>? = adapter.javaClass
        var inspected = 0
        while (current != null && current != RecyclerView.Adapter::class.java && inspected < 4) {
            current.declaredFields.forEach { field ->
                val candidate = runCatching {
                    field.isAccessible = true
                    itemFrom(field.get(adapter))
                }.getOrNull()
                if (candidate != null) return candidate
            }
            current = current.superclass
            inspected++
        }

        return null
    }

    private fun collectText(view: View, out: MutableList<String>, depth: Int, maxItems: Int) {
        if (depth > 5 || out.size >= maxItems || view.visibility != View.VISIBLE) return
        val contentDescription = view.contentDescription?.toString()?.trim()
        if (!contentDescription.isNullOrBlank()) out += contentDescription
        if (view is TextView) {
            val text = view.text?.toString()?.trim()
            if (!text.isNullOrBlank()) out += text
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                collectText(view.getChildAt(index), out, depth + 1, maxItems)
                if (out.size >= maxItems) return
            }
        }
    }

    private fun looksLikeUpdatesScreen(texts: List<String>): Boolean {
        if (looksLikeChannelsBrowser(texts)) return false
        return looksLikeUpdatesTabRoot(texts)
    }

    private fun looksLikeUpdatesTabRoot(texts: List<String>): Boolean {
        val normalized = texts.map { it.normalizeUiText() }
        return "updates" in normalized &&
            ("status" in normalized || "recent updates" in normalized || "channels" in normalized)
    }

    private fun looksLikeUpdatesSurface(texts: List<String>): Boolean {
        if (looksLikeChannelsBrowser(texts)) return false
        val normalized = texts.map { it.normalizeUiText() }
        return "updates" in normalized ||
            normalized.any { it == "status" || it == "add status" || it == "recent updates" } ||
            normalized.any { it == "find channels to follow" || "channels to follow below" in it }
    }

    private fun hasUpdatesAnchor(texts: List<String>): Boolean {
        val normalized = texts.map { it.normalizeUiText() }
        return normalized.any {
            it == "updates" ||
                it == "status" ||
                it == "add status" ||
                it == "recent updates" ||
                it == "find channels to follow" ||
                "find channels to follow below" in it
        }
    }

    private fun hideUpdatesChannelsSectionAround(view: View): Boolean {
        val root = view.rootView ?: view
        val rootHeight = root.height.takeIf { it > 0 } ?: root.resources.displayMetrics.heightPixels
        findAncestor(view, 12) { candidate ->
            val group = candidate as? ViewGroup ?: return@findAncestor false
            val texts = mutableListOf<String>()
            collectText(group, texts, 0, 96)
            looksLikeUpdatesChannelsSection(texts) && isSafeToHideChannelSection(group, rootHeight)
        }?.let { section ->
            val texts = mutableListOf<String>()
            collectText(section, texts, 0, 96)
            hideRecommendationView(section, "channels section", texts)
            return true
        }

        return hideUpdatesChannelsSection(root, rootHeight)
    }

    private fun looksLikeChannelsBrowser(texts: List<String>): Boolean {
        val normalized = texts.map { it.normalizeUiText() }
        if (hasUpdatesAnchor(texts)) return false
        val hasChannelsTitle = normalized.any { it == "channels" }
        val hasCategoryBrowser = normalized.count { it in CHANNEL_BROWSER_CATEGORY_TERMS } >= 2
        val hasBrowserCopy = normalized.any {
            it == "explore more channels" ||
                it == "across all categories" ||
                "channels you follow will appear here" in it
        }
        val hasExploreChannelsHeader = "explore channels" in normalized && "see all" in normalized
        val hasChannelsActions = normalized.any {
            it == "see all" ||
                it == "search" ||
                it == "filter" ||
                it == "filters"
        }
        val hasRecommendationRows = hasVisibleRecommendationSurface(texts)
        return hasExploreChannelsHeader ||
            (hasChannelsTitle && (hasCategoryBrowser || hasBrowserCopy || hasChannelsActions || hasRecommendationRows))
    }

    private fun looksLikeChannelsBrowserLocal(texts: List<String>): Boolean {
        if (hasUpdatesAnchor(texts)) return false
        val normalized = texts.map { it.normalizeUiText() }
        val hasExploreChannelsHeader = "explore channels" in normalized
        val hasChannelsEmptyCopy = normalized.any { "channels you follow will appear here" in it }
        val hasCategoryBrowser = normalized.any { it in CHANNEL_BROWSER_CATEGORY_TERMS }
        val hasBrowserCopy = normalized.any { it == "explore more channels" || it == "across all categories" }
        return hasExploreChannelsHeader || hasChannelsEmptyCopy || hasCategoryBrowser || hasBrowserCopy
    }

    private fun looksLikeChannelsBrowserRow(texts: List<String>): Boolean {
        if (hasUpdatesAnchor(texts)) return false
        val normalized = texts.map { it.normalizeUiText() }
        val hasFollowButton = normalized.any { it == "follow" || it == "follow channel" }
        val hasFollowerCount = normalized.any { it.hasFollowerCountText() }
        if (!hasFollowButton || !hasFollowerCount) return false

        val repeatedRealTitle = normalized
            .filter { text ->
                text.isNotBlank() &&
                    text != "follow" &&
                    text != "follow channel" &&
                    !text.hasFollowerCountText() &&
                    text !in setOf("channels", "explore more", "create channel")
            }
            .groupingBy { it }
            .eachCount()
            .any { (_, count) -> count >= 2 }

        return repeatedRealTitle
    }

    private fun looksLikeUpdatesChannelsSection(texts: List<String>): Boolean {
        val normalized = texts.map { it.normalizeUiText() }
        if ("updates" in normalized || "status" in normalized || "add status" in normalized || "recent updates" in normalized) {
            return false
        }
        if (looksLikeChannelsBrowserLocal(texts) || looksLikeChannelsBrowserRow(texts)) return false

        val hasChannelsHeader = "channels" in normalized
        val hasUpdatesChannelCopy = normalized.any {
            it == "find channels to follow" ||
                "find channels to follow below" in it ||
                ("stay updated on topics" in it && "find channels" in it)
        }
        val hasUpdatesChannelActions = normalized.any { it == "explore more" || it == "create channel" }
        val hasRecommendationRows = hasVisibleRecommendationSurface(texts)

        return hasChannelsHeader && (hasUpdatesChannelCopy || hasUpdatesChannelActions || hasRecommendationRows)
    }

    private fun markChannelsBrowserFromLocalChrome(view: View): Boolean {
        val root = view.rootView ?: view
        val rootTexts = mutableListOf<String>()
        collectText(root, rootTexts, 0, 240)
        if (looksLikeUpdatesTabRoot(rootTexts) || looksLikeUpdatesSurface(rootTexts)) {
            markRootScope(root, ScreenScope.UPDATES)
            return false
        }

        val localTexts = mutableListOf<String>()
        collectText(view, localTexts, 0, 64)
        if (looksLikeChannelsBrowserLocal(localTexts)) {
            markRootScope(view, ScreenScope.CHANNELS_BROWSER)
            return true
        }

        var current = view.parent as? View
        repeat(4) {
            val candidate = current ?: return@repeat
            val texts = mutableListOf<String>()
            collectText(candidate, texts, 0, 80)
            if (looksLikeChannelsBrowserLocal(texts)) {
                markRootScope(candidate, ScreenScope.CHANNELS_BROWSER)
                return true
            }
            current = candidate.parent as? View
        }

        return false
    }

    private fun hasVisibleRecommendationSurface(texts: List<String>): Boolean {
        val normalized = texts.map { it.normalizeUiText() }
        val hasFollowButton = normalized.any { it == "follow" || it == "follow channel" }
        val hasFollowerCount = normalized.any { it.hasFollowerCountText() }
        val hasOwnedSignal = normalized.any { it == "following" || it == "unfollow" || "your channel" in it || "my channel" in it }
        return hasFollowButton && hasFollowerCount && !hasOwnedSignal
    }

    private fun looksLikeRecommendationBlock(texts: List<String>): Boolean {
        val joined = texts.joinToString(" ").normalizeUiText()
        val hasChannelTerm = "channel" in joined || "channels" in joined || "newsletter" in joined
        if (!hasChannelTerm) return false

        val hasRecommendationTerm = listOf(
            "recommend",
            "recommended",
            "recommendations",
            "suggested",
            "suggestion",
            "discover",
            "find channels",
            "channels to follow",
            "popular channels",
            "follow channels"
        ).any { it in joined }

        val looksOwnedOrFollowed = listOf(
            "your channel",
            "my channel",
            "unfollow",
            "following",
            "mute channel"
        ).any { it in joined }

        return hasRecommendationTerm && !looksOwnedOrFollowed
    }

    private fun looksLikeNewRecommendationSurface(texts: List<String>): Boolean {
        val normalized = texts.map { it.normalizeUiText() }
        if (normalized.any { it == "updates" || it == "status" || it == "recent updates" || it == "add status" }) {
            return false
        }
        val hasChannelsHeader = normalized.any { it == "channels" }
        val hasFollowButton = normalized.any { it == "follow" || it == "follow channel" }
        val hasFollowerCount = normalized.any { it.hasFollowerCountText() }
        val hasRecommendationAction = normalized.any { it == "explore more" || it == "create channel" || "find channels" in it }
        val hasOwnedSignal = normalized.any { it == "following" || it == "unfollow" || "your channel" in it || "my channel" in it }
        return (hasChannelsHeader && hasFollowButton && hasFollowerCount && !hasOwnedSignal) ||
            (hasRecommendationAction && hasFollowButton && hasFollowerCount && !hasOwnedSignal)
    }

    private fun looksLikeRecommendationRow(texts: List<String>): Boolean {
        val normalized = texts.map { it.normalizeUiText() }
        val hasFollowButton = normalized.any { it == "follow" || it == "follow channel" }
        val hasFollowerCount = normalized.any { it.hasFollowerCountText() }
        val hasOwnedSignal = normalized.any { it == "following" || it == "unfollow" || "your channel" in it || "my channel" in it }
        val hasRealTitle = normalized.any { text ->
            text.isNotBlank() &&
                text != "follow" &&
                !text.hasFollowerCountText() &&
                text !in setOf("channels", "explore more", "create channel")
        }
        return hasFollowButton && hasFollowerCount && hasRealTitle && !hasOwnedSignal
    }

    private fun looksLikeRecommendationAction(texts: List<String>): Boolean {
        val normalized = texts.map { it.normalizeUiText() }
        return normalized.any {
            it == "explore more" ||
                it == "create channel" ||
                it == "find channels" ||
                it == "find channels to follow"
        }
    }

    private fun looksLikeRecommendationCopy(texts: List<String>): Boolean {
        val normalized = texts.map { it.normalizeUiText() }
        return normalized.any(::isRecommendationCopyText)
    }

    private fun looksLikeRecommendationStandaloneText(texts: List<String>): Boolean {
        val normalized = texts.map { it.normalizeUiText() }
        return normalized.any { it == "channels" || it == "explore more" || it == "create channel" }
    }

    private fun looksLikeRecommendationModel(model: Any?): Boolean {
        val text = model?.toString()?.normalizeUiText().orEmpty()
        if (text.isBlank()) return false
        return ("recounit" in text && ("newsletter" in text || "channel" in text)) ||
            ("recommended" in text && ("newsletter" in text || "channel" in text)) ||
            text.startsWith("reconewsletter") ||
            text.startsWith("recocommunity")
    }

    private fun isSafeToHide(view: View, rootHeight: Int): Boolean {
        if (view.parent !is ViewGroup) return false
        if (view.width <= 0 || view.height <= 0) return false
        if (view.height > rootHeight * 0.55f) return false
        return true
    }

    private fun isSafeToHideChannelSection(view: View, rootHeight: Int): Boolean {
        if (view.parent !is ViewGroup) return false
        if (view.width <= 0 || view.height <= 0) return false
        if (view.height > rootHeight * 0.9f) return false
        return true
    }

    private fun hideRecommendationView(view: View, reason: String, texts: List<String>) {
        hiddenViews += view
        view.visibility = View.GONE
        view.alpha = 0f
        view.minimumHeight = 0
        view.layoutParams?.apply {
            height = 0
            view.layoutParams = this
        }
        val sample = texts.joinToString(" | ").take(180)
        logBlock("Hidden WhatsApp channel recommendation $reason: $sample")
    }

    private fun String.normalizeUiText(): String {
        return trim()
            .replace(Regex("\\s+"), " ")
            .lowercase()
    }

    private fun String.hasFollowerCountText(): Boolean {
        return contains("followers") ||
            contains("follower") ||
            contains("subscribers") ||
            contains("subscriber")
    }

    private fun logBlock(message: String) {
        val count = hiddenRecommendations.incrementAndGet()
        if (count <= 20 || count % 25 == 0) {
            logInfo("$message (count=$count)")
        }
    }

    private fun logInfo(message: String) {
        XposedBridge.log("[$TAG] $message")
        CoreLogger.xposedLog(message, TAG)
        WhatsAppAppLogWriter.info(androidContext, TAG, message)
    }

    private fun logError(message: String, throwable: Throwable) {
        val fullMessage = "$message: ${throwable.stackTraceToString()}"
        XposedBridge.log("[$TAG] $fullMessage")
        CoreLogger.xposedLog(fullMessage, TAG)
        WhatsAppAppLogWriter.error(androidContext, TAG, fullMessage)
    }

    private fun runSafe(source: String, block: () -> Unit) {
        runCatching(block).onFailure { throwable ->
            logError("WhatsApp hook callback failed in $source", throwable)
        }
    }
}
