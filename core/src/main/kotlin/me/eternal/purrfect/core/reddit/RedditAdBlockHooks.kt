package me.eternal.purrfect.core.reddit

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.KeyEvent
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import dalvik.system.DexFile
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XC_MethodHook
import me.eternal.purrfect.common.Constants
import me.eternal.purrfect.core.logger.CoreLogger
import me.eternal.purrfect.core.ModContext
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

class RedditAdBlockHooks(
    private val context: ModContext,
    private val appClassLoader: ClassLoader = context.androidContext.classLoader
) {
    companion object {
        const val TAG = "PurrfectReddit"
        private const val PLAY_STORE_PACKAGE_NAME = "com.android.vending"
        private val trackingQueryParams = setOf(
            "utm_source",
            "utm_medium",
            "utm_campaign",
            "utm_term",
            "utm_content",
            "utm_name",
            "share_id",
            "share_app_name",
            "context",
            "ref",
            "ref_source",
            "source"
        )
    }

    private val classLoader get() = appClassLoader
    private val promotedPostBlocks = AtomicInteger(0)
    private val commentAdBlocks = AtomicInteger(0)
    private val premiumUnlocks = AtomicInteger(0)
    private val externalBrowserRedirects = AtomicInteger(0)
    private val hiddenUiElements = AtomicInteger(0)
    private val dismissedDialogs = AtomicInteger(0)
    private val sanitizedShares = AtomicInteger(0)
    private val installerSpoofs = AtomicInteger(0)
    private val coloredThreadLines = AtomicInteger(0)
    private val composeScrolls = AtomicInteger(0)
    private val promotedPostHooked = AtomicBoolean(false)
    private val commentAdHooked = AtomicBoolean(false)
    private val platformHooksInstalled = AtomicBoolean(false)
    private val hookedDirectClasses = Collections.synchronizedSet(mutableSetOf<String>())
    private val structurallyHookedClasses = Collections.synchronizedSet(mutableSetOf<String>())
    private val activitiesWithTopButton = Collections.newSetFromMap(WeakHashMap<Activity, Boolean>())
    private val composeLazyStates = Collections.newSetFromMap(WeakHashMap<Any, Boolean>())
    private val screenshotPromptScanTimes = Collections.synchronizedMap(WeakHashMap<View, Long>())
    private val layoutSuppressionScanTimes = Collections.synchronizedMap(WeakHashMap<View, Long>())
    private val launchingExternalBrowser = ThreadLocal.withInitial { false }
    private val forceNextGraphQlBooleanFalse = ThreadLocal.withInitial { false }
    private val featureState get() = RedditFeatureStateStore.current

    fun init() {
        logInfo(
            "Reddit feature state: blockPromotedPosts=${featureState.blockPromotedPosts}, " +
                "blockCommentAds=${featureState.blockCommentAds}, " +
                "unlockRedditPremium=${featureState.unlockRedditPremium}, " +
                "openLinksInExternalBrowser=${featureState.openLinksInExternalBrowser}, " +
                "disableScreenshotPopup=${featureState.disableScreenshotPopup}, " +
                "hideCreateButton=${featureState.hideCreateButton}, " +
                "hideTrendingTodayShelf=${featureState.hideTrendingTodayShelf}, " +
                "addScrollToTopButton=${featureState.addScrollToTopButton}, " +
                "loadedFromXposedPrefs=${featureState.loadedFromXposedPrefs}, source=${featureState.source}"
        )

        installPlatformHooks()
        installStartupHooks()
    }

    private fun installPlatformHooks() {
        if (!platformHooksInstalled.compareAndSet(false, true)) return

        runCatching {
            XposedBridge.hookAllMethods(
                View::class.java,
                "onAttachedToWindow",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam<*>) {
                        val view = param.thisObject as? View ?: return
                        if (featureState.hasAnyLayoutHook()) scheduleLayoutSuppressionScan(view)
                        if (featureState.disableScreenshotPopup) scheduleScreenshotSharePromptDismiss(view)
                        if (featureState.addScrollToTopButton) installScrollToTopButton(view)
                    }
                }
            )
            logInfo("Installed Reddit generic layout suppression hook")
        }.onFailure { logError("Failed to install Reddit layout suppression hook", it) }

        runCatching {
            XposedBridge.hookAllMethods(
                View::class.java,
                "onLayout",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam<*>) {
                        val view = param.thisObject as? View ?: return
                        if (featureState.hasAnyLayoutHook()) scheduleLayoutSuppressionScan(view)
                        if (featureState.disableScreenshotPopup) scheduleScreenshotSharePromptDismiss(view)
                    }
                }
            )
            logInfo("Installed Reddit layout rescan hook")
        }.onFailure { logError("Failed to install Reddit screenshot share prompt layout scanner", it) }

        runCatching {
            XposedBridge.hookAllMethods(
                Dialog::class.java,
                "show",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam<*>) {
                        handleMatchingDialog(param.thisObject as? Dialog ?: return)
                    }
                }
            )
            logInfo("Installed Reddit targeted dialog action hook")
        }.onFailure { logError("Failed to install Reddit targeted dialog action hook", it) }

        runCatching {
            XposedBridge.hookAllMethods(
                Resources::class.java,
                "getText",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam<*>) {
                        if (!featureState.disableScreenshotPopup) return
                        val resources = param.thisObject as? Resources ?: return
                        val id = param.args.firstOrNull() as? Int ?: return
                        if (isScreenshotShareBannerResource(resources, id)) {
                            param.result = ""
                            logBlock(dismissedDialogs, "Suppressed Reddit screenshot share prompt resource text")
                        }
                    }
                }
            )
            XposedBridge.hookAllMethods(
                Resources::class.java,
                "getString",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam<*>) {
                        if (!featureState.disableScreenshotPopup) return
                        val resources = param.thisObject as? Resources ?: return
                        val id = param.args.firstOrNull() as? Int ?: return
                        if (isScreenshotShareBannerResource(resources, id)) {
                            param.result = ""
                            logBlock(dismissedDialogs, "Suppressed Reddit screenshot share prompt resource string")
                        }
                    }
                }
            )
            logInfo("Installed Reddit screenshot share prompt resource suppression hooks")
        }.onFailure { logError("Failed to install Reddit screenshot share prompt resource suppression hooks", it) }

        runCatching {
            XposedBridge.hookAllMethods(
                Window::class.java,
                "addFlags",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam<*>) {
                        if (!featureState.disableScreenshotPopup) return
                        val flags = param.args.firstOrNull() as? Int ?: return
                        if ((flags and WindowManager.LayoutParams.FLAG_SECURE) != 0) {
                            param.result = null
                            logBlock(dismissedDialogs, "Blocked Reddit secure screenshot flag")
                        }
                    }
                }
            )
            XposedBridge.hookAllMethods(
                Window::class.java,
                "setFlags",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam<*>) {
                        if (!featureState.disableScreenshotPopup) return
                        val flags = param.args.getOrNull(0) as? Int ?: return
                        val mask = param.args.getOrNull(1) as? Int ?: return
                        if ((mask and WindowManager.LayoutParams.FLAG_SECURE) != 0) {
                            param.args[0] = flags and WindowManager.LayoutParams.FLAG_SECURE.inv()
                            param.args[1] = mask and WindowManager.LayoutParams.FLAG_SECURE.inv()
                            logBlock(dismissedDialogs, "Stripped Reddit secure screenshot flag")
                        }
                    }
                }
            )
            logInfo("Installed Reddit secure screenshot flag hooks")
        }.onFailure { logError("Failed to install Reddit secure screenshot flag hooks", it) }

        runCatching {
            XposedBridge.hookAllMethods(
                Activity::class.java,
                "onResume",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam<*>) {
                        if (!featureState.disableScreenshotPopup) return
                        val activity = param.thisObject as? Activity ?: return
                        activity.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    }
                }
            )
            logInfo("Installed Reddit screenshot secure-flag clearing hook")
        }.onFailure { logError("Failed to install Reddit screenshot secure-flag hook", it) }

        runCatching {
            XposedBridge.hookAllMethods(
                Toast::class.java,
                "makeText",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam<*>) {
                        if (!featureState.disableScreenshotPopup) return
                        val context = param.args.firstOrNull() as? Context ?: return
                        val text = when (val value = param.args.getOrNull(1)) {
                            is CharSequence -> value.toString()
                            is Int -> runCatching { context.getString(value) }.getOrDefault("")
                            else -> ""
                        }.lowercase()
                        if (text.contains("screenshot")) {
                            param.result = Toast(context)
                            logBlock(dismissedDialogs, "Suppressed Reddit screenshot toast")
                        }
                    }
                }
            )
            XposedBridge.hookAllMethods(
                Toast::class.java,
                "show",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam<*>) {
                        if (!featureState.disableScreenshotPopup) return
                        val toast = param.thisObject as? Toast ?: return
                        val text = viewText(toast.view).lowercase()
                        if (text.contains("screenshot")) {
                            param.result = null
                            logBlock(dismissedDialogs, "Suppressed Reddit screenshot toast")
                        }
                    }
                }
            )
            logInfo("Installed Reddit screenshot toast suppression hooks")
        }.onFailure { logError("Failed to install Reddit screenshot toast suppression hook", it) }

        hookActivityLaunch("startActivity")
        hookActivityLaunch("startActivityForResult")
        installPlayInstallerSpoofHooks()
    }

    private fun installPlayInstallerSpoofHooks() {
        runCatching {
            val appPackageManagerClass = Class.forName("android.app.ApplicationPackageManager")
            XposedBridge.hookAllMethods(
                appPackageManagerClass,
                "getInstallerPackageName",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam<*>) {
                        if (!isRedditPackageName(param.args.firstOrNull())) return
                        param.result = PLAY_STORE_PACKAGE_NAME
                        logBlock(installerSpoofs, "Spoofed Reddit installer package as Play Store")
                    }
                }
            )
            logInfo("Installed Reddit Play installer package spoof hook")
        }.onFailure { logError("Failed to install Reddit installer package spoof hook", it) }

        runCatching {
            val installSourceInfoClass = Class.forName("android.content.pm.InstallSourceInfo")
            listOf(
                "getInitiatingPackageName",
                "getInstallingPackageName",
                "getOriginatingPackageName",
                "getUpdateOwnerPackageName"
            ).forEach { methodName ->
                runCatching {
                    XposedBridge.hookAllMethods(
                        installSourceInfoClass,
                        methodName,
                        object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam<*>) {
                                param.result = PLAY_STORE_PACKAGE_NAME
                                logBlock(installerSpoofs, "Spoofed Reddit install source via $methodName")
                            }
                        }
                    )
                }
            }
            logInfo("Installed Reddit Play install-source spoof hooks")
        }.onFailure { logError("Failed to install Reddit install-source spoof hooks", it) }

        runCatching {
            val sessionInfoClass = Class.forName("android.content.pm.PackageInstaller\$SessionInfo")
            XposedBridge.hookAllMethods(
                sessionInfoClass,
                "getInstallerPackageName",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam<*>) {
                        param.result = PLAY_STORE_PACKAGE_NAME
                        logBlock(installerSpoofs, "Spoofed Reddit package-installer session source")
                    }
                }
            )
            logInfo("Installed Reddit package-installer session spoof hook")
        }.onFailure { logError("Failed to install Reddit package-installer session spoof hook", it) }
    }

    private fun isRedditPackageName(value: Any?): Boolean {
        return value == Constants.REDDIT_PACKAGE_NAME || value == context.androidContext.packageName
    }

    private fun hookActivityLaunch(methodName: String) {
        runCatching {
            XposedBridge.hookAllMethods(
                Activity::class.java,
                methodName,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam<*>) {
                        if (launchingExternalBrowser.get() == true) return
                        val activity = param.thisObject as? Activity ?: return
                        val intentIndex = param.args.indexOfFirst { it is Intent }
                        if (intentIndex < 0) return
                        val intent = param.args[intentIndex] as? Intent ?: return
                        sanitizeShareIntent(intent)
                        if (openExternallyIfNeeded(activity, intent)) {
                            param.result = null
                        }
                    }
                }
            )
            logInfo("Installed Reddit activity launch hook for $methodName")
        }.onFailure { logError("Failed to install Reddit activity launch hook for $methodName", it) }
    }

    private fun installStartupHooks() {
        val directHooks = linkedMapOf<String, (Class<*>) -> Unit>(
            "com.reddit.ads.impl.feeds.composables.c" to ::hookPromotedPosts,
            "com.reddit.ads.impl.feeds.converters.a" to ::hookFeedAdConverter,
            "com.reddit.ads.impl.feeds.converters.b" to ::hookFeedAdConverter,
            "com.reddit.adsdata.common.Post" to ::hookPromotedPostModel,
            "com.reddit.ads.postdetail.a" to ::hookCommentAds,
            "com.reddit.ads.impl.postdetail.a" to ::hookPostDetailAdSource,
            "com.reddit.ads.impl.commentspage.a" to ::hookCommentAdMapper,
            "com.reddit.domain.model.MyAccount" to ::hookPremiumAccountModel,
            "com.reddit.domain.model.Account" to ::hookPremiumAccountModel,
            "com.reddit.data.common.client.user.User" to ::hookPremiumUserModel,
            "com.reddit.domain.premium.usecase.a" to ::hookPremiumExpirationUseCase,
            "com.reddit.account.repository.b" to ::hookOpenLinksInExternalBrowser,
            "com.reddit.sharing.screenshot.b" to ::hookScreenshotTriggerSharingListener,
            "com.reddit.postdetail.refactor.events.PostDetailScreenshotEvents\$ScreenshotBannerVisibilityEvent" to ::hookPostDetailScreenshotBannerVisibilityEvent,
            "com.reddit.agegating.impl.nsfw.NsfwBottomSheet" to ::hookNsfwBottomSheet,
            "com.reddit.auth.login.screen.nsfw.AuthNsfwBottomSheet" to ::hookNsfwBottomSheet,
            "com.reddit.launch.bottomnav.BottomNavScreen" to ::hookBottomNavScreen,
            "com.reddit.screens.drawer.community.c" to ::hookCommunityDrawerPresenter,
            "com.reddit.screens.drawer.community.CommunityDrawerPresenter\$bindListOnView\$2" to ::hookCommunityDrawerBindList,
            "com.reddit.typeahead.data.a" to ::hookTypeaheadRepository,
            "com.reddit.search.combined.data.a" to ::hookTypeaheadFeedDataSource,
            "com.reddit.search.combined.ui.composables.f" to ::hookSearchTypeaheadSection,
            "androidx.compose.foundation.lazy.b" to ::hookComposeLazyState,
            "androidx.compose.foundation.lazy.grid.b" to ::hookComposeLazyState,
            "androidx.compose.foundation.lazy.staggeredgrid.b" to ::hookComposeLazyState
        )

        directHooks.forEach { (className, hooker) -> installDirectHook(className, hooker) }
        installStartupStructuralHooks(directHooks.keys)
    }

    private fun installDirectHook(className: String, hooker: (Class<*>) -> Unit) {
        val clazz = findHookClass(className)
        if (clazz == null) {
            logInfo("Reddit startup hook class not present: $className")
            return
        }
        if (!hookedDirectClasses.add(className)) return
        logInfo("Installing startup Reddit hook for $className")
        hooker(clazz)
    }

    private fun findHookClass(className: String): Class<*>? {
        val loaders = buildList {
            add(classLoader)
            Thread.currentThread().contextClassLoader?.let(::add)
        }.distinct()
        loaders.forEach { loader ->
            runCatching { Class.forName(className, false, loader) }
                .getOrNull()
                ?.let { clazz ->
                    return clazz
                }
        }
        return null
    }

    private fun installStartupStructuralHooks(exactHooks: Set<String>) {
        val candidateNames = enumerateRedditDexClassNames()
            .asSequence()
            .filterNot { it in exactHooks }
            .filter(::isStartupStructuralCandidateName)
            .toList()

        var inspected = 0
        var installed = 0
        candidateNames.forEach { className ->
            val clazz = findHookClass(className) ?: return@forEach
            inspected++
            if (installStructuralRedditHook(clazz)) installed++
        }
        logInfo("Reddit startup structural scan inspected=$inspected installed=$installed candidates=${candidateNames.size}")
    }

    private fun enumerateRedditDexClassNames(): List<String> {
        val appInfo = context.androidContext.applicationInfo
        val dexPaths = buildList {
            appInfo.sourceDir?.let(::add)
            appInfo.splitSourceDirs?.forEach(::add)
        }.distinct()
        val names = LinkedHashSet<String>()
        dexPaths.forEach { path ->
            runCatching {
                @Suppress("DEPRECATION")
                val dex = DexFile(path)
                try {
                    val entries = dex.entries()
                    while (entries.hasMoreElements()) {
                        names.add(entries.nextElement())
                    }
                } finally {
                    runCatching { dex.close() }
                }
            }.onFailure { throwable ->
                logError("Failed to scan Reddit dex classes from $path", throwable)
            }
        }
        return names.toList()
    }

    private fun isStartupStructuralCandidateName(name: String): Boolean {
        return name.startsWith("com.reddit.ads.") ||
            name.startsWith("com.reddit.adsdata.") ||
            name.startsWith("com.reddit.search.") ||
            name.startsWith("com.reddit.typeahead.") ||
            name.startsWith("com.reddit.launch.bottomnav.") ||
            name.startsWith("com.reddit.screens.drawer.community.") ||
            name.startsWith("com.reddit.agegating.") ||
            name.startsWith("com.reddit.auth.login.screen.nsfw.") ||
            name.startsWith("com.reddit.sharing.screenshot.") ||
            name.startsWith("com.reddit.postdetail.refactor.events.") ||
            name.startsWith("androidx.compose.foundation.lazy.")
    }

    private fun installStructuralRedditHook(clazz: Class<*>): Boolean {
        val name = clazz.name
        if (!structurallyHookedClasses.add(name)) return false
        var installed = false

        if (name.startsWith("com.reddit.ads.") || name.startsWith("com.reddit.adsdata.")) {
            if (name.contains("feeds", ignoreCase = true) &&
                looksLikeFeedElementConverterClass(clazz)
            ) {
                hookFeedAdConverter(clazz)
                installed = true
            }
            if (clazz.declaredMethods.any { method ->
                    (method.name == "getPromoted" || method.name == "hasPromoted") &&
                        method.returnType == Boolean::class.javaPrimitiveType &&
                        method.parameterTypes.isEmpty()
                }
            ) {
                hookPromotedPostModel(clazz)
                installed = true
            }
        }

        if (name.startsWith("com.reddit.search.") || name.startsWith("com.reddit.typeahead.")) {
            val methods = clazz.declaredMethods
            if (methods.any { it.parameterTypes.any { type -> List::class.java.isAssignableFrom(type) } }) {
                hookTypeaheadRepository(clazz)
                hookTypeaheadFeedDataSource(clazz)
                hookSearchTypeaheadSection(clazz)
                installed = true
            }
        }

        if (looksLikeBottomNavClass(clazz)) {
            hookBottomNavScreen(clazz)
            installed = true
        }

        if (looksLikeCommunityDrawerPresenterClass(clazz)) {
            hookCommunityDrawerPresenter(clazz)
            installed = true
        }

        if (looksLikeCommunityDrawerBindClass(clazz)) {
            hookCommunityDrawerBindList(clazz)
            installed = true
        }

        return installed
    }

    private fun looksLikeBottomNavClass(clazz: Class<*>): Boolean {
        if (!clazz.name.startsWith("com.reddit.launch.bottomnav.")) return false
        if (clazz.name.contains("BottomNav", ignoreCase = true)) return true
        return clazz.declaredMethods.any { method ->
            method.parameterTypes.isEmpty() &&
                method.returnType != Void.TYPE &&
                isCollectionLikeType(method.returnType) &&
                !Modifier.isAbstract(method.modifiers) &&
                !Modifier.isNative(method.modifiers)
        } && clazz.declaredMethods.any { method ->
            method.parameterTypes.any(::isCollectionLikeType) &&
                !Modifier.isAbstract(method.modifiers) &&
                !Modifier.isNative(method.modifiers)
        }
    }

    private fun looksLikeCommunityDrawerPresenterClass(clazz: Class<*>): Boolean {
        if (!clazz.name.startsWith("com.reddit.screens.drawer.community.")) return false
        if (clazz.name.contains("CommunityDrawerPresenter", ignoreCase = true)) return true
        return clazz.declaredFields.any { field -> List::class.java.isAssignableFrom(field.type) } &&
            clazz.declaredMethods.any { method ->
                method.returnType == Void.TYPE &&
                    method.parameterTypes.isEmpty() &&
                    !Modifier.isAbstract(method.modifiers) &&
                    !Modifier.isNative(method.modifiers)
            }
    }

    private fun looksLikeCommunityDrawerBindClass(clazz: Class<*>): Boolean {
        if (!clazz.name.startsWith("com.reddit.screens.drawer.community.")) return false
        return clazz.declaredFields.any { it.name == "this\$0" } &&
            clazz.declaredMethods.any { method ->
                method.name == "invokeSuspend" &&
                    method.parameterTypes.size == 1 &&
                    !Modifier.isAbstract(method.modifiers) &&
                    !Modifier.isNative(method.modifiers)
            }
    }

    private fun looksLikeCommunityRecommendationSectionClass(clazz: Class<*>): Boolean {
        return clazz.name.startsWith("com.reddit.onboardingfeedscomponents.communityrecommendation.impl.") &&
            clazz.declaredMethods.any { method ->
                method.returnType == Void.TYPE &&
                    !Modifier.isAbstract(method.modifiers) &&
                    !Modifier.isNative(method.modifiers)
            }
    }

    private fun hookCommunityRecommendationSection(sectionClass: Class<*>) {
        runCatching {
            val methods = sectionClass.declaredMethods.filter { method ->
                method.returnType == Void.TYPE &&
                    !Modifier.isAbstract(method.modifiers) &&
                    !Modifier.isNative(method.modifiers)
            }

            methods.forEach { method ->
                hookMethod(method) { hookParam ->
                    if (!featureState.hideRecommendedCommunities) return@hookMethod
                    hookParam.setResult(null)
                    logBlock(hiddenUiElements, "Hid Reddit community recommendation section via ${signature(method)}")
                }
            }

            logInfo("Hooked Reddit community recommendation section ${sectionClass.name} (${methods.size} method(s))")
        }.onFailure { throwable ->
            logError("Failed to hook Reddit community recommendation section ${sectionClass.name}", throwable)
        }
    }

    private fun looksLikeFeedElementConverterClass(clazz: Class<*>): Boolean {
        return clazz.declaredMethods.any { method ->
            method.parameterTypes.size == 2 &&
                method.returnType != Void.TYPE &&
                !Modifier.isAbstract(method.modifiers) &&
                !Modifier.isNative(method.modifiers)
        } && clazz.declaredMethods.any { method ->
            method.name == "getInputType" && method.parameterTypes.isEmpty()
        }
    }

    private fun hookRelatedCommunitiesStructuralConverter(converterClass: Class<*>) {
        runCatching {
            converterClass.declaredMethods
                .filter { method ->
                    method.parameterTypes.size == 2 &&
                        method.returnType != Void.TYPE &&
                        !Modifier.isAbstract(method.modifiers) &&
                        !Modifier.isNative(method.modifiers)
                }
                .forEach { method ->
                    hookMethod(method) { hookParam ->
                        if (!featureState.hideRecommendedCommunities) return@hookMethod
                        val input = hookParam.args.firstOrNull(::looksLikeRelatedCommunityItem)
                            ?: hookParam.args.getOrNull(1)?.takeIf(::looksLikeRelatedCommunityItem)
                            ?: return@hookMethod
                        hookParam.setResult(null)
                        logBlock(
                            hiddenUiElements,
                            "Dropped Reddit related communities model structurally via ${signature(method)} (${input.javaClass.name})"
                        )
                    }
                }
            logInfo("Installed structural Reddit related-community converter hook on ${converterClass.name}")
        }.onFailure { throwable ->
            logError("Failed structural Reddit related-community converter hook on ${converterClass.name}", throwable)
        }
    }

    private fun looksLikeCommentViewStateClass(clazz: Class<*>): Boolean {
        return clazz.declaredConstructors.any { ctor ->
            ctor.parameterTypes.size >= 10 &&
                ctor.parameterTypes.any(::looksLikeCommentDecorationClass) &&
                ctor.parameterTypes.any { it == Int::class.javaPrimitiveType || it == Integer::class.java }
        }
    }

    private fun looksLikeCommentDecorationClass(clazz: Class<*>?): Boolean {
        if (clazz == null) return false
        return clazz.declaredConstructors.any { ctor ->
            val types = ctor.parameterTypes
            types.size == 3 &&
                types[0] == types[1] &&
                (types[2] == Int::class.javaPrimitiveType || types[2] == Integer::class.java) &&
                (types[0].name.contains("CommentColor", ignoreCase = true) ||
                    types[0].declaredMethods.any { it.name == "values" && it.parameterTypes.isEmpty() })
        }
    }

    private fun hookStructuralCommentViewState(viewStateClass: Class<*>) {
        runCatching {
            XposedBridge.hookAllConstructors(
                viewStateClass,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam<*>) {
                        if (!featureState.colorCodedCommentThreads) return
                        val parameterTypes = param.methodParameterTypesOrNull() ?: return
                        val decorationIndex = parameterTypes.indexOfFirst(::looksLikeCommentDecorationClass)
                        if (decorationIndex !in param.args.indices) return
                        val decorationClass = parameterTypes[decorationIndex]
                        val depth = findLikelyCommentDepth(param.args) ?: return
                        val decoration = newCommentThreadDecoration(decorationClass, depth) ?: return
                        param.args[decorationIndex] = decoration
                        logBlock(
                            coloredThreadLines,
                            "Applied Reddit structural comment thread color at depth $depth via ${viewStateClass.name}"
                        )
                    }
                }
            )
            logInfo("Installed structural Reddit comment view-state decoration hook on ${viewStateClass.name}")
        }.onFailure { throwable ->
            logError("Failed structural Reddit comment view-state decoration hook on ${viewStateClass.name}", throwable)
        }
    }

    private fun XC_MethodHook.MethodHookParam<*>.methodParameterTypesOrNull(): Array<Class<*>>? {
        return when (val executable = method) {
            is java.lang.reflect.Constructor<*> -> executable.parameterTypes
            is Method -> executable.parameterTypes
            else -> null
        }
    }

    private fun findLikelyCommentDepth(args: Array<Any?>): Int? {
        args.take(4).forEach { value ->
            val depth = value as? Int ?: return@forEach
            if (depth in 0..20) return depth
        }
        return null
    }

    private fun newCommentThreadDecoration(decorationClass: Class<*>, depth: Int): Any? {
        val ctor = decorationClass.declaredConstructors.firstOrNull { ctor ->
            val types = ctor.parameterTypes
            types.size == 3 &&
                types[0] == types[1] &&
                (types[2] == Int::class.javaPrimitiveType || types[2] == Integer::class.java)
        } ?: return null
        ctor.isAccessible = true
        val colorClass = ctor.parameterTypes[0]
        val values = runCatching { colorClass.getDeclaredMethod("values").invoke(null) as Array<*> }
            .getOrNull()
            ?.filterNotNull()
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        val color = values[Math.floorMod(depth, values.size)]
        return runCatching { ctor.newInstance(color, color, 8) }.getOrNull()
    }

    private fun hookPromotedPosts(adPostSectionClass: Class<*>) {
        if (!promotedPostHooked.compareAndSet(false, true)) return
        runCatching {
            val methods = adPostSectionClass.declaredMethods.filter { method ->
                method.name == "a" &&
                    method.returnType == Void.TYPE &&
                    method.parameterTypes.size == 3 &&
                    !Modifier.isAbstract(method.modifiers)
            }

            require(methods.isNotEmpty()) {
                "No AdPostSection compose methods matched com.reddit.ads.impl.feeds.composables.c#a(upg, spa, int)"
            }

            methods.forEach { method ->
                hookMethod(method) { hookParam ->
                    if (!featureState.blockPromotedPosts) return@hookMethod
                    hookParam.setResult(null)
                    logBlock(promotedPostBlocks, "Blocked promoted feed post via ${signature(method)}")
                }
            }

            logInfo("Hooked ${methods.size} Reddit promoted post renderer(s)")
        }.onFailure { throwable ->
            promotedPostHooked.set(false)
            logError("Failed to hook Reddit promoted posts", throwable)
        }
    }

    private fun hookPromotedPostModel(postClass: Class<*>) {
        runCatching {
            val methods = postClass.declaredMethods.filter { method ->
                (method.name == "getPromoted" || method.name == "hasPromoted") &&
                    method.returnType == Boolean::class.javaPrimitiveType &&
                    method.parameterTypes.isEmpty() &&
                    !Modifier.isAbstract(method.modifiers)
            }

            methods.forEach { method ->
                hookMethod(method) { hookParam ->
                    if (!featureState.blockPromotedPosts) return@hookMethod
                    hookParam.setResult(false)
                    logBlock(promotedPostBlocks, "Forced Reddit promoted flag false via ${signature(method)}")
                }
            }

            if (methods.isNotEmpty()) logInfo("Hooked ${methods.size} Reddit promoted post model accessor(s)")
        }.onFailure { throwable ->
            logError("Failed to hook Reddit promoted post model", throwable)
        }
    }

    private fun hookFeedAdConverter(converterClass: Class<*>) {
        hookNullableReturnMethods(
            clazz = converterClass,
            reason = "feed ad converter",
            counter = promotedPostBlocks,
            replacement = { null },
            enabled = { featureState.blockPromotedPosts },
            include = { method -> method.name == "a" && method.parameterTypes.isNotEmpty() }
        )
    }

    private fun hookCommentAds(postDetailAdLoaderClass: Class<*>) {
        if (!commentAdHooked.compareAndSet(false, true)) return
        runCatching {
            val emptyFlow = emptyFlowOrNull()
            if (emptyFlow == null) {
                logInfo("Skipping legacy Reddit comment ad loader hook; empty flow singleton is not available yet")
                return@runCatching
            }
            val methods = postDetailAdLoaderClass.declaredMethods.filter { method ->
                method.name == "c" &&
                    method.parameterTypes.size == 1 &&
                    !Modifier.isAbstract(method.modifiers)
            }

            require(methods.isNotEmpty()) {
                "No RedditPostDetailAdLoader load methods matched com.reddit.ads.postdetail.a#c(...)"
            }

            methods.forEach { method ->
                hookMethod(method) { hookParam ->
                    if (!featureState.blockCommentAds) return@hookMethod
                    hookParam.setResult(emptyFlow)
                    logBlock(commentAdBlocks, "Blocked comment ad load via ${signature(method)}")
                }
            }

            logInfo("Hooked ${methods.size} Reddit comment ad loader(s)")
        }.onFailure { throwable ->
            commentAdHooked.set(false)
            logError("Failed to hook Reddit comment ads", throwable)
        }
    }

    private fun hookPremiumAccountModel(accountClass: Class<*>) {
        val futurePremiumExpiration = 4102444800L // 2100-01-01T00:00:00Z
        val premiumSince = 1704067200L // 2024-01-01T00:00:00Z
        hookReturnMethods(
            clazz = accountClass,
            reason = "premium account boolean",
            counter = premiumUnlocks,
            replacement = true,
            enabled = { featureState.unlockRedditPremium },
            include = { method ->
                method.returnType == Boolean::class.javaPrimitiveType &&
                    method.parameterTypes.isEmpty() &&
                    method.name in setOf(
                        "getHasPremium",
                        "getIsPremiumSubscriber",
                        "isPremiumSubscriber",
                        "getHasPremiumAvatarTreatment",
                        "getHideAds"
                    )
            }
        )
        hookReturnMethods(
            clazz = accountClass,
            reason = "premium account timestamp",
            counter = premiumUnlocks,
            replacement = futurePremiumExpiration,
            enabled = { featureState.unlockRedditPremium },
            include = { method ->
                method.returnType == java.lang.Long::class.java &&
                    method.parameterTypes.isEmpty() &&
                    method.name == "getPremiumExpirationUtcSeconds"
            }
        )
        hookReturnMethods(
            clazz = accountClass,
            reason = "premium account timestamp",
            counter = premiumUnlocks,
            replacement = premiumSince,
            enabled = { featureState.unlockRedditPremium },
            include = { method ->
                method.returnType == java.lang.Long::class.java &&
                    method.parameterTypes.isEmpty() &&
                    method.name == "getPremiumSinceUtcSeconds"
            }
        )
    }

    private fun hookPremiumUserModel(userClass: Class<*>) {
        hookReturnMethods(
            clazz = userClass,
            reason = "premium user boolean",
            counter = premiumUnlocks,
            replacement = true,
            enabled = { featureState.unlockRedditPremium },
            include = { method ->
                method.returnType == Boolean::class.javaPrimitiveType &&
                    method.parameterTypes.isEmpty() &&
                    method.name in setOf(
                        "getHasPremium",
                        "getIsPremiumSubscriber",
                        "hasHasPremium",
                        "hasIsPremiumSubscriber"
                    )
            }
        )
    }

    private fun hookPremiumExpirationUseCase(useCaseClass: Class<*>) {
        hookReturnMethods(
            clazz = useCaseClass,
            reason = "premium expiration use case",
            counter = premiumUnlocks,
            replacement = 4102444800L,
            enabled = { featureState.unlockRedditPremium },
            include = { method ->
                method.name == "a" &&
                    method.parameterTypes.isNotEmpty() &&
                    method.returnType == Any::class.java
            }
        )
    }

    private fun hookOpenLinksInExternalBrowser(preferenceRepositoryClass: Class<*>) {
        hookReturnMethods(
            clazz = preferenceRepositoryClass,
            reason = "open links externally preference",
            counter = externalBrowserRedirects,
            replacement = true,
            enabled = { featureState.openLinksInExternalBrowser },
            include = { method ->
                method.name == "D" &&
                    method.returnType == Boolean::class.javaPrimitiveType &&
                    method.parameterTypes.isEmpty()
            }
        )
    }

    private fun hookScreenshotTriggerSharingListener(screenshotListenerClass: Class<*>) {
        runCatching {
            var hooked = 0
            screenshotListenerClass.declaredMethods
                .filter { method ->
                    !Modifier.isAbstract(method.modifiers) &&
                        !Modifier.isNative(method.modifiers) &&
                        method.name in setOf("a", "b", "c", "e")
                }
                .forEach { method ->
                    hookMethod(method) { hookParam ->
                        if (!featureState.disableScreenshotPopup) return@hookMethod
                        if (method.name == "b") {
                            hookParam.setResult(true)
                            logBlock(dismissedDialogs, "Forced Reddit screenshot banner suppressor via ${signature(method)}")
                        } else {
                            hookParam.setResult(null)
                            logBlock(dismissedDialogs, "Blocked Reddit screenshot sharing trigger via ${signature(method)}")
                        }
                    }
                    hooked++
                }
            logInfo("Hooked $hooked Reddit screenshot sharing trigger method(s) in ${screenshotListenerClass.name}")
        }.onFailure { throwable ->
            logError("Failed to hook Reddit screenshot sharing trigger ${screenshotListenerClass.name}", throwable)
        }
    }

    private fun hookPostDetailScreenshotBannerVisibilityEvent(eventClass: Class<*>) {
        runCatching {
            XposedBridge.hookAllConstructors(
                eventClass,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam<*>) {
                        if (!featureState.disableScreenshotPopup) return
                        if (param.args.firstOrNull() == true) {
                            param.args[0] = false
                            logBlock(dismissedDialogs, "Forced Reddit post detail screenshot banner invisible")
                        }
                    }
                }
            )
            logInfo("Hooked Reddit post detail screenshot banner visibility event")
        }.onFailure { throwable ->
            logError("Failed to hook Reddit post detail screenshot banner visibility event ${eventClass.name}", throwable)
        }
    }

    private fun hookNsfwBottomSheet(bottomSheetClass: Class<*>) {
        runCatching {
            val methods = bottomSheetClass.declaredMethods.filter { method ->
                method.name == "j5" &&
                    method.returnType == Void.TYPE &&
                    method.parameterTypes.isEmpty() &&
                    !Modifier.isAbstract(method.modifiers)
            }

            require(methods.isNotEmpty()) {
                "No Reddit NSFW bottom sheet initialize method matched ${bottomSheetClass.name}#j5()"
            }

            methods.forEach { method ->
                hookMethodAfter(method) { hookParam ->
                    if (!featureState.removeNsfwWarningDialog) return@hookMethodAfter
                    val screen = hookParam.thisObject ?: return@hookMethodAfter
                    if (invokeNoArg(screen, "h") || invokeNoArg(screen, "l5")) {
                        logBlock(dismissedDialogs, "Skipped Reddit NSFW warning via ${bottomSheetClass.name}")
                    }
                }
            }

            logInfo("Hooked Reddit NSFW bottom sheet ${bottomSheetClass.name}")
        }.onFailure { throwable ->
            logError("Failed to hook Reddit NSFW bottom sheet ${bottomSheetClass.name}", throwable)
        }
    }

    private fun hookCommunityDrawerPresenter(presenterClass: Class<*>) {
        runCatching {
            val methods = presenterClass.declaredMethods.filter { method ->
                method.returnType == Void.TYPE &&
                    method.parameterTypes.isEmpty() &&
                    !Modifier.isAbstract(method.modifiers) &&
                    !Modifier.isNative(method.modifiers)
            }

            methods.forEach { method ->
                hookMethodAfter(method) { hookParam ->
                    filterCommunityDrawerList(hookParam.thisObject ?: return@hookMethodAfter)
                }
            }

            if (methods.isNotEmpty()) logInfo("Hooked Reddit community drawer presenter list builder")
        }.onFailure { throwable ->
            logError("Failed to hook Reddit community drawer presenter", throwable)
        }
    }

    private fun hookCommunityDrawerBindList(bindClass: Class<*>) {
        runCatching {
            val methods = bindClass.declaredMethods.filter { method ->
                method.name == "invokeSuspend" &&
                    method.parameterTypes.size == 1 &&
                    !Modifier.isAbstract(method.modifiers)
            }

            methods.forEach { method ->
                hookMethod(method) { hookParam ->
                    val presenter = getFieldValue(hookParam.thisObject ?: return@hookMethod, "this$0") ?: return@hookMethod
                    filterCommunityDrawerList(presenter)
                }
            }

            if (methods.isNotEmpty()) logInfo("Hooked Reddit community drawer bind list")
        }.onFailure { throwable ->
            logError("Failed to hook Reddit community drawer bind list", throwable)
        }
    }

    private fun hookBottomNavScreen(screenClass: Class<*>) {
        runCatching {
            screenClass.declaredMethods
                .filter { method ->
                    method.parameterTypes.isEmpty() &&
                        method.returnType != Void.TYPE &&
                        isCollectionLikeType(method.returnType) &&
                        looksLikeBottomNavActionBuilder(method) &&
                        !Modifier.isAbstract(method.modifiers) &&
                        !Modifier.isNative(method.modifiers)
                }
                .forEach { method ->
                    hookMethodAfter(method) { hookParam ->
                        if (!featureState.hideCreateButton) return@hookMethodAfter
                        val filtered = filterPersistentCollection(hookParam.result, ::looksLikeCreateBottomNavItem)
                            ?: return@hookMethodAfter
                        val result = coercePersistentCollection(filtered, method.returnType)
                        if (result == null) {
                            logInfo(
                                "Skipped Reddit Create bottom-nav action rewrite for ${signature(method)}: " +
                                    "expected=${method.returnType.name}, actual=${filtered.javaClass.name}"
                            )
                            return@hookMethodAfter
                        }
                        hookParam.setResult(result)
                        logBlock(hiddenUiElements, "Removed Reddit Create bottom-nav action via ${signature(method)}")
                    }
                }

            screenClass.declaredMethods
                .filter { method ->
                    method.parameterTypes.size == 1 &&
                        isCollectionLikeType(method.parameterTypes[0]) &&
                        method.returnType != Void.TYPE &&
                        isCollectionLikeType(method.returnType) &&
                        looksLikeBottomNavTabTransformer(method) &&
                        !Modifier.isAbstract(method.modifiers) &&
                        !Modifier.isNative(method.modifiers)
                }
                .forEach { method ->
                    hookMethodAfter(method) { hookParam ->
                        if (!featureState.hideCreateButton) return@hookMethodAfter
                        val filtered = filterPersistentCollection(hookParam.result, ::looksLikeCreateBottomNavItem)
                            ?: return@hookMethodAfter
                        val result = coercePersistentCollection(filtered, method.returnType)
                        if (result == null) {
                            logInfo(
                                "Skipped Reddit Create bottom-nav tab rewrite for ${signature(method)}: " +
                                    "expected=${method.returnType.name}, actual=${filtered.javaClass.name}"
                            )
                            return@hookMethodAfter
                        }
                        hookParam.setResult(result)
                        logBlock(hiddenUiElements, "Removed Reddit Create bottom-nav tab via ${signature(method)}")
                    }
                }

            logInfo("Hooked Reddit bottom navigation filtering")
        }.onFailure { throwable ->
            logError("Failed to hook Reddit bottom navigation", throwable)
        }
    }

    private fun looksLikeBottomNavActionBuilder(method: Method): Boolean {
        if (method.name == "G5") return true
        return method.declaringClass.name.contains("BottomNavScreen", ignoreCase = true) &&
            method.returnType.interfaces.any { it.name.endsWith(".l5n") || it.name.endsWith(".n0y") }
    }

    private fun looksLikeBottomNavTabTransformer(method: Method): Boolean {
        if (method.name == "I5") return true
        return method.declaringClass.name.contains("BottomNavScreen", ignoreCase = true) &&
            method.parameterTypes.firstOrNull()?.name?.endsWith(".l5n") == true
    }

    private fun looksLikeCreateBottomNavItem(item: Any?): Boolean {
        if (item == null) return false
        val label = buildString {
            append(fieldString(item, "a"))
            append(' ')
            append(fieldString(item, "b"))
            append(' ')
            append(item.toString())
        }
        return label.equals("Create", ignoreCase = true) ||
            label.equals("Post", ignoreCase = true) ||
            label.contains("Create", ignoreCase = true) ||
            label.contains("BottomNav", ignoreCase = true) && label.contains("Post", ignoreCase = true) ||
            label.contains("create_post", ignoreCase = true) ||
            label.contains("CREATE", ignoreCase = true)
    }

    private fun hookSearchTypeaheadSection(sectionClass: Class<*>) {
        runCatching {
            val methods = sectionClass.declaredMethods.filter { method ->
                method.returnType == Void.TYPE &&
                    method.parameterTypes.isNotEmpty() &&
                    !Modifier.isAbstract(method.modifiers) &&
                    !Modifier.isNative(method.modifiers)
            }

            methods.forEach { method ->
                hookMethod(method) { hookParam ->
                    if (!featureState.hideTrendingTodayShelf) return@hookMethod
                    val section = hookParam.args.firstOrNull { arg ->
                        val text = arg?.toString().orEmpty()
                        text.contains("Typeahead", ignoreCase = true) ||
                            text.contains("Trending", ignoreCase = true) ||
                            fieldString(arg, "b").isNotBlank()
                    } ?: return@hookMethod
                    val title = fieldString(section, "b")
                    val id = fieldString(section, "a")
                    val text = section.toString()
                    if (title.equals("Trending", ignoreCase = true) ||
                        title.contains("trending", ignoreCase = true) ||
                        id.contains("trending", ignoreCase = true) ||
                        text.contains("title=Trending", ignoreCase = true) ||
                        text.contains("Based on your interests", ignoreCase = true)
                    ) {
                        hookParam.setResult(null)
                        logBlock(hiddenUiElements, "Hid Reddit search trending section via ${signature(method)}")
                    }
                }
            }

            logInfo("Hooked ${methods.size} Reddit search typeahead section method(s)")
        }.onFailure { throwable ->
            logError("Failed to hook Reddit search typeahead section", throwable)
        }
    }

    private fun hookTypeaheadRepository(repositoryClass: Class<*>) {
        runCatching {
            repositoryClass.declaredMethods
                .filter { method ->
                    method.name == "a" &&
                        method.parameterTypes.size == 2 &&
                        List::class.java.isAssignableFrom(method.parameterTypes[1]) &&
                        !Modifier.isAbstract(method.modifiers)
                }
                .forEach { method ->
                    hookMethod(method) { hookParam ->
                        if (!featureState.hideTrendingTodayShelf) return@hookMethod
                        val query = hookParam.args.firstOrNull() as? String ?: return@hookMethod
                        if (query.isNotEmpty()) return@hookMethod
                        val original = hookParam.args.getOrNull(1) as? List<*> ?: return@hookMethod
                        val filtered = original.filterNot(::looksLikeTrendingSearchItem)
                        if (filtered.size == original.size) return@hookMethod
                        hookParam.args[1] = filtered
                        logBlock(hiddenUiElements, "Filtered Reddit typeahead trending items (${original.size - filtered.size} removed)")
                    }
                }
            logInfo("Hooked Reddit typeahead repository filtering")
        }.onFailure { throwable ->
            logError("Failed to hook Reddit typeahead repository", throwable)
        }
    }

    private fun hookTypeaheadFeedDataSource(dataSourceClass: Class<*>) {
        runCatching {
            dataSourceClass.declaredMethods
                .filter { method ->
                    method.name == "l" &&
                        method.parameterTypes.size == 1 &&
                        List::class.java.isAssignableFrom(method.parameterTypes[0]) &&
                        List::class.java.isAssignableFrom(method.returnType) &&
                        !Modifier.isAbstract(method.modifiers)
                }
                .forEach { method ->
                    hookMethodAfter(method) { hookParam ->
                        if (!featureState.hideTrendingTodayShelf) return@hookMethodAfter
                        val original = hookParam.result as? MutableList<Any?> ?: return@hookMethodAfter
                        val before = original.size
                        original.removeAll { item -> looksLikeTrendingSearchItem(item) }
                        val removed = before - original.size
                        if (removed > 0) {
                            logBlock(hiddenUiElements, "Filtered Reddit typeahead feed trending items ($removed removed)")
                        }
                    }
                }
            logInfo("Hooked Reddit typeahead feed data source filtering")
        }.onFailure { throwable ->
            logError("Failed to hook Reddit typeahead feed data source", throwable)
        }
    }

    private fun hookOkHttpRequestBody(requestBodyClass: Class<*>) {
        runCatching {
            val bufferClass = classLoader.loadClass("okio.Buffer")
            requestBodyClass.declaredMethods
                .filter { method ->
                    method.name == "writeTo" &&
                        method.parameterTypes.size == 1 &&
                        !Modifier.isAbstract(method.modifiers) &&
                        !Modifier.isNative(method.modifiers)
                }
                .forEach { method ->
                    method.isAccessible = true
                    XposedBridge.hookMethod(
                        method,
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam<*>) {
                                if (!featureState.hideRecommendedCommunities) return
                                val sink = param.args.firstOrNull() ?: return
                                val buffer = runCatching { bufferClass.getDeclaredConstructor().newInstance() }.getOrNull() ?: return
                                val original = runCatching {
                                    XposedBridge.invokeOriginalMethod(method, param.thisObject, arrayOf(buffer))
                                    bufferClass.getMethod("readUtf8").invoke(buffer) as? String
                                }.getOrNull() ?: return
                                val rewritten = suppressCommunityRecommendationGraphQl(original)
                                if (rewritten == original) return
                                runCatching {
                                    sink.javaClass.methods.firstOrNull { candidate ->
                                        candidate.name == "writeUtf8" &&
                                            candidate.parameterTypes.size == 1 &&
                                            candidate.parameterTypes[0] == String::class.java
                                    }?.invoke(sink, rewritten)
                                }.onFailure { throwable ->
                                    logError("Failed to write rewritten Reddit GraphQL body", throwable)
                                    return
                                }
                                param.result = null
                                logBlock(hiddenUiElements, "Rewrote Reddit GraphQL community recommendation request body")
                            }
                        }
                    )
                }
            logInfo("Hooked OkHttp request body GraphQL recommendation suppression")
        }.onFailure { throwable ->
            logError("Failed to hook OkHttp request body GraphQL recommendation suppression", throwable)
        }
    }

    private fun hookGraphQlWriter(writerClass: Class<*>) {
        runCatching {
            writerClass.declaredMethods
                .filter { method ->
                    method.name == "U" &&
                        method.parameterTypes.size == 1 &&
                        method.parameterTypes[0] == String::class.java &&
                        !Modifier.isAbstract(method.modifiers)
                }
                .forEach { method ->
                    hookMethod(method) { hookParam ->
                        if (!featureState.hideRecommendedCommunities) return@hookMethod
                        val fieldName = hookParam.args.firstOrNull() as? String ?: return@hookMethod
                        if (isCommunityRecommendationGraphQlFlag(fieldName)) {
                            forceNextGraphQlBooleanFalse.set(true)
                        }
                    }
                }

            writerClass.declaredMethods
                .filter { method ->
                    method.name == "J" &&
                        method.parameterTypes.size == 1 &&
                        method.parameterTypes[0] == Boolean::class.javaPrimitiveType &&
                        !Modifier.isAbstract(method.modifiers)
                }
                .forEach { method ->
                    hookMethod(method) { hookParam ->
                        if (!featureState.hideRecommendedCommunities) {
                            forceNextGraphQlBooleanFalse.set(false)
                            return@hookMethod
                        }
                        if (forceNextGraphQlBooleanFalse.get()) {
                            forceNextGraphQlBooleanFalse.set(false)
                            hookParam.args[0] = false
                            logBlock(hiddenUiElements, "Forced Reddit community recommendation GraphQL include flag off")
                        }
                    }
                }

            writerClass.declaredMethods
                .filter { method ->
                    method.name == "D0" &&
                        method.parameterTypes.isEmpty() &&
                        !Modifier.isAbstract(method.modifiers)
                }
                .forEach { method ->
                    hookMethod(method) { _ -> forceNextGraphQlBooleanFalse.set(false) }
                }

            logInfo("Hooked Reddit GraphQL writer semantic recommendation flags")
        }.onFailure { throwable ->
            logError("Failed to hook Reddit GraphQL writer semantic recommendation flags", throwable)
        }
    }

    private fun hookRelatedCommunitiesFeedElement(feedElementClass: Class<*>) {
        runCatching {
            feedElementClass.declaredMethods
                .filter { method ->
                    method.name == "a" &&
                        method.returnType == Void.TYPE &&
                        method.parameterTypes.isNotEmpty() &&
                        !Modifier.isAbstract(method.modifiers)
                }
                .forEach { method ->
                    hookMethod(method) { hookParam ->
                        if (!featureState.hideRecommendedCommunities) return@hookMethod
                        hookParam.setResult(null)
                        logBlock(hiddenUiElements, "Hid Reddit related communities feed element via ${signature(method)}")
                    }
                }
            logInfo("Hooked Reddit related communities feed element")
        }.onFailure { throwable ->
            logError("Failed to hook Reddit related communities feed element", throwable)
        }
    }

    private fun hookSubredditNavigationRenderer(rendererClass: Class<*>) {
        runCatching {
            rendererClass.declaredMethods
                .filter { method ->
                    method.returnType == Void.TYPE &&
                        method.parameterTypes.isNotEmpty() &&
                        !Modifier.isAbstract(method.modifiers) &&
                        !Modifier.isNative(method.modifiers)
                }
                .forEach { method ->
                    hookMethod(method) { hookParam ->
                        if (!featureState.hideRecommendedCommunities) return@hookMethod
                        hookParam.setResult(null)
                        logBlock(hiddenUiElements, "Hid Reddit same-community navigation renderer via ${signature(method)}")
                    }
                }
            logInfo("Hooked Reddit same-community navigation renderer")
        }.onFailure { throwable ->
            logError("Failed to hook Reddit same-community navigation renderer", throwable)
        }
    }

    private fun hookSubredditNavigationViewModel(viewModelClass: Class<*>) {
        runCatching {
            val hiddenState = runCatching { classLoader.loadClass("defpackage.foc0") }
                .recoverCatching { classLoader.loadClass("foc0") }
                .getOrThrow()
                .getDeclaredField("a")
                .apply { isAccessible = true }
                .get(null)

            viewModelClass.declaredMethods
                .filter { method ->
                    method.name == "z" &&
                        method.parameterTypes.size == 1 &&
                        method.returnType != Void.TYPE &&
                        !Modifier.isAbstract(method.modifiers)
                }
                .forEach { method ->
                    hookMethod(method) { hookParam ->
                        if (!featureState.hideRecommendedCommunities) return@hookMethod
                        hookParam.setResult(hiddenState)
                        logBlock(hiddenUiElements, "Hid Reddit same-community navigation state via ${signature(method)}")
                    }
                }
            logInfo("Hooked Reddit same-community navigation ViewModel")
        }.onFailure { throwable ->
            logError("Failed to hook Reddit same-community navigation ViewModel", throwable)
        }
    }

    private fun hookRelatedCommunitiesRenderer(rendererClass: Class<*>) {
        runCatching {
            val methods = rendererClass.declaredMethods.filter { method ->
                method.returnType == Void.TYPE &&
                    method.name in setOf("a", "b", "c") &&
                    !Modifier.isAbstract(method.modifiers)
            }

            methods.forEach { method ->
                hookMethod(method) { hookParam ->
                    if (!featureState.hideRecommendedCommunities) return@hookMethod
                    hookParam.setResult(null)
                    logBlock(hiddenUiElements, "Hid Reddit related communities renderer via ${signature(method)}")
                }
            }

            logInfo("Hooked ${methods.size} Reddit related communities renderer method(s)")
        }.onFailure { throwable ->
            logError("Failed to hook Reddit related communities renderer", throwable)
        }
    }

    private fun hookRelatedCommunitiesViewModel(viewModelClass: Class<*>) {
        runCatching {
            val hiddenState = runCatching { classLoader.loadClass("defpackage.n150") }
                .recoverCatching { classLoader.loadClass("n150") }
                .getOrThrow()
                .getDeclaredField("a")
                .apply { isAccessible = true }
                .get(null)

            val methods = viewModelClass.declaredMethods.filter { method ->
                method.name == "z" &&
                    method.parameterTypes.size == 1 &&
                    method.returnType != Void.TYPE &&
                    !Modifier.isAbstract(method.modifiers)
            }

            methods.forEach { method ->
                hookMethod(method) { hookParam ->
                    if (!featureState.hideRecommendedCommunities) return@hookMethod
                    hookParam.setResult(hiddenState)
                    logBlock(hiddenUiElements, "Hid Reddit related communities state via ${signature(method)}")
                }
            }

            if (methods.isNotEmpty()) logInfo("Hooked Reddit related communities ViewModel")
        }.onFailure { throwable ->
            logError("Failed to hook Reddit related communities ViewModel", throwable)
        }
    }

    private fun hookRelatedCommunitiesRepository(repositoryClass: Class<*>) {
        runCatching {
            val loader = repositoryClass.classLoader ?: classLoader
            val failureClass = runCatching { loader.loadClass("defpackage.slg") }
                .recoverCatching { loader.loadClass("slg") }
                .getOrThrow()
            val failureCtor = failureClass.constructors.firstOrNull { it.parameterTypes.size == 1 }
                ?: failureClass.declaredConstructors.first { it.parameterTypes.size == 1 }
            failureCtor.isAccessible = true

            repositoryClass.declaredMethods
                .filter { method ->
                    method.name == "a" &&
                        method.returnType == Any::class.java &&
                        method.parameterTypes.size == 2 &&
                        method.parameterTypes[0] == String::class.java &&
                        !Modifier.isAbstract(method.modifiers) &&
                        !Modifier.isNative(method.modifiers)
                }
                .forEach { method ->
                    hookMethod(method) { hookParam ->
                        if (!featureState.hideRecommendedCommunities) return@hookMethod
                        hookParam.setResult(failureCtor.newInstance(IllegalStateException("Purrfect hid related communities")))
                        logBlock(hiddenUiElements, "Blocked Reddit related communities repository via ${signature(method)}")
                    }
                }
            logInfo("Hooked Reddit related communities repository")
        }.onFailure { throwable ->
            logError("Failed to hook Reddit related communities repository", throwable)
        }
    }

    private fun hookFeedElementConverter(converterClass: Class<*>) {
        runCatching {
            val typeField = findField(converterClass, "a")?.apply { isAccessible = true }
            converterClass.declaredMethods
                .filter { method ->
                    method.name == "a" &&
                        method.parameterTypes.size == 2 &&
                        method.returnType != Void.TYPE &&
                        !Modifier.isAbstract(method.modifiers) &&
                        !Modifier.isNative(method.modifiers)
                }
                .forEach { method ->
                    hookMethod(method) { hookParam ->
                        if (!featureState.hideRecommendedCommunities) return@hookMethod
                        val converterType = runCatching { typeField?.getInt(hookParam.thisObject) }.getOrNull()
                        val model = hookParam.args.getOrNull(1)
                        if (converterType == 9 || model?.toString()?.contains("RelatedCommunitiesLazyListElement", ignoreCase = true) == true) {
                            hookParam.setResult(null)
                            logBlock(hiddenUiElements, "Dropped Reddit related communities lazy-list converter via ${signature(method)}")
                        }
                    }
                }
            logInfo("Hooked Reddit feed element converter related-community path")
        }.onFailure { throwable ->
            logError("Failed to hook Reddit feed element converter related-community path", throwable)
        }
    }

    private fun hookRelatedCommunitiesLazyListElement(elementClass: Class<*>) {
        runCatching {
            XposedBridge.hookAllConstructors(
                elementClass,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam<*>) {
                        if (!featureState.hideRecommendedCommunities) return
                        logBlock(hiddenUiElements, "Observed Reddit related communities lazy-list model before conversion")
                    }
                }
            )
            logInfo("Hooked Reddit related communities lazy-list model")
        }.onFailure { throwable ->
            logError("Failed to hook Reddit related communities lazy-list model", throwable)
        }
    }

    private fun hookRelatedCommunitiesSuggestionsProvider(providerClass: Class<*>) {
        runCatching {
            providerClass.declaredMethods
                .filter { method ->
                    method.name in setOf("a", "b") &&
                        method.returnType == Void.TYPE &&
                        !Modifier.isAbstract(method.modifiers) &&
                        !Modifier.isNative(method.modifiers)
                }
                .forEach { method ->
                    hookMethod(method) { hookParam ->
                        if (!featureState.hideRecommendedCommunities) return@hookMethod
                        hookParam.setResult(null)
                        logBlock(hiddenUiElements, "Hid Reddit related communities suggestions provider via ${signature(method)}")
                    }
                }
            logInfo("Hooked Reddit related communities suggestions provider")
        }.onFailure { throwable ->
            logError("Failed to hook Reddit related communities suggestions provider", throwable)
        }
    }

    private fun hookRelatedCommunitiesLazyProvider(providerClass: Class<*>) {
        runCatching {
            providerClass.declaredMethods
                .filter { method ->
                    method.name == "b" &&
                        method.returnType == Void.TYPE &&
                        method.parameterTypes.size == 2 &&
                        !Modifier.isAbstract(method.modifiers)
                }
                .forEach { method ->
                    hookMethod(method) { hookParam ->
                        if (!featureState.hideRecommendedCommunities) return@hookMethod
                        hookParam.setResult(null)
                        logBlock(hiddenUiElements, "Hid Reddit related communities lazy provider via ${signature(method)}")
                    }
                }
            logInfo("Hooked Reddit related communities lazy provider")
        }.onFailure { throwable ->
            logError("Failed to hook Reddit related communities lazy provider", throwable)
        }
    }

    private fun hookRelatedCommunitiesGraphQlComponents(componentsClass: Class<*>) {
        runCatching {
            XposedBridge.hookAllConstructors(
                componentsClass,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam<*>) {
                        if (!featureState.hideRecommendedCommunities) return
                        val edges = param.args.firstOrNull() as? ArrayList<*> ?: return
                        val before = edges.size
                        val filtered = edges.filterNot { edge -> edge != null && looksLikeRelatedCommunityItem(edge) }
                        if (filtered.size == edges.size) return
                        @Suppress("UNCHECKED_CAST")
                        (edges as ArrayList<Any?>).apply {
                            clear()
                            addAll(filtered)
                        }
                        logBlock(hiddenUiElements, "Filtered Reddit related communities GraphQL components (${before - filtered.size} removed)")
                    }
                }
            )
            logInfo("Hooked Reddit related communities GraphQL component filtering")
        }.onFailure { throwable ->
            logError("Failed to hook Reddit related communities GraphQL component filtering", throwable)
        }
    }

    private fun hookRelatedCommunitiesGraphQlComponentsParser(parserClass: Class<*>) {
        runCatching {
            parserClass.declaredMethods
                .filter { method ->
                    method.name == "z" &&
                        method.parameterTypes.size == 2 &&
                        method.returnType != Void.TYPE &&
                        !Modifier.isAbstract(method.modifiers) &&
                        !Modifier.isNative(method.modifiers)
                }
                .forEach { method ->
                    hookMethodAfter(method) { hookParam ->
                        if (!featureState.hideRecommendedCommunities) return@hookMethodAfter
                        val components = hookParam.result ?: return@hookMethodAfter
                        val field = findField(components.javaClass, "a") ?: return@hookMethodAfter
                        val edges = field.get(components) as? ArrayList<*> ?: return@hookMethodAfter
                        val before = edges.size
                        val filtered = edges.filterNot { edge -> edge != null && looksLikeRelatedCommunityItem(edge) }
                        if (filtered.size == before) return@hookMethodAfter
                        @Suppress("UNCHECKED_CAST")
                        (edges as ArrayList<Any?>).apply {
                            clear()
                            addAll(filtered)
                        }
                        logBlock(hiddenUiElements, "Filtered Reddit related communities GraphQL parser result (${before - filtered.size} removed)")
                    }
                }
            logInfo("Hooked Reddit related communities GraphQL parser filtering")
        }.onFailure { throwable ->
            logError("Failed to hook Reddit related communities GraphQL parser filtering", throwable)
        }
    }

    private fun hookFeedStateFiltering(feedStateClass: Class<*>) {
        runCatching {
            XposedBridge.hookAllConstructors(
                feedStateClass,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam<*>) {
                        if (!featureState.hideRecommendedCommunities) return
                        val original = param.args.firstOrNull() ?: return
                        val filtered = filterPersistentCollection(original, ::looksLikeRelatedCommunityItem) ?: return
                        param.args[0] = filtered
                        val before = (original as? Iterable<*>)?.count() ?: -1
                        val after = (filtered as? Iterable<*>)?.count() ?: -1
                        logBlock(hiddenUiElements, "Filtered Reddit feed state related sections (${before - after} removed)")
                    }
                }
            )
            logInfo("Hooked Reddit feed state related-community filtering")
        }.onFailure { throwable ->
            logError("Failed to hook Reddit feed state related-community filtering", throwable)
        }
    }

    private fun hookFeedPagerFiltering(pagerClass: Class<*>) {
        runCatching {
            pagerClass.declaredMethods
                .filter { method ->
                    method.returnType != Void.TYPE &&
                        !Modifier.isAbstract(method.modifiers) &&
                        !Modifier.isNative(method.modifiers)
                }
                .forEach { method ->
                    hookMethodAfter(method) { hookParam ->
                        if (!featureState.hideRecommendedCommunities) return@hookMethodAfter
                        val original = hookParam.result ?: return@hookMethodAfter
                        val filtered = filterRelatedCommunityResult(original) ?: return@hookMethodAfter
                        hookParam.setResult(filtered.value)
                        logBlock(hiddenUiElements, "Filtered Reddit related communities feed result (${filtered.removed} removed) via ${signature(method)}")
                    }
                }
            logInfo("Hooked Reddit feed pager related-community filtering in ${pagerClass.name}")
        }.onFailure { throwable ->
            logError("Failed to hook Reddit feed pager related-community filtering in ${pagerClass.name}", throwable)
        }
    }

    private fun hookCommentIndentView(indentClass: Class<*>) {
        runCatching {
            XposedBridge.hookAllConstructors(
                indentClass,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam<*>) {
                        val indentView = param.thisObject ?: return
                        applyCommentThreadColors(indentView)
                        (indentView as? View)?.let { view ->
                            listOf(250L, 750L, 1_500L, 3_000L).forEach { delay ->
                                view.postDelayed({ applyCommentThreadColors(indentView) }, delay)
                            }
                        }
                    }
                }
            )

            indentClass.declaredMethods
                .filter { method ->
                    method.name in setOf("setIndentLevel", "setLineColors") &&
                        !Modifier.isAbstract(method.modifiers)
                }
                .forEach { method ->
                    hookMethod(method) { hookParam ->
                        if (!featureState.colorCodedCommentThreads) return@hookMethod
                        if (method.name == "setLineColors" && hookParam.args.isNotEmpty()) {
                            hookParam.args[0] = redditCommentThreadColors()
                            logBlock(coloredThreadLines, "Replaced Reddit comment indent palette argument")
                        }
                    }
                    hookMethodAfter(method) { hookParam ->
                        applyCommentThreadColors(hookParam.thisObject ?: return@hookMethodAfter)
                    }
                }

            indentClass.declaredMethods
                .filter { method ->
                    method.name == "onDraw" &&
                        method.parameterTypes.size == 1 &&
                        method.parameterTypes[0] == Canvas::class.java &&
                        !Modifier.isAbstract(method.modifiers)
                }
                .forEach { method ->
                    hookMethod(method) { hookParam ->
                        applyCommentThreadColors(hookParam.thisObject ?: return@hookMethod)
                    }
                }

            logInfo("Hooked Reddit comment indent colors")
        }.onFailure { throwable ->
            logError("Failed to hook Reddit comment indent colors", throwable)
        }
    }

    private fun hookUserCommentViewModelColors(viewModelClass: Class<*>) {
        runCatching {
            viewModelClass.declaredMethods
                .filter { method ->
                    method.name == "z" &&
                        method.parameterTypes.size == 1 &&
                        method.returnType != Void.TYPE &&
                        !Modifier.isAbstract(method.modifiers) &&
                        !Modifier.isNative(method.modifiers)
                }
                .forEach { method ->
                    hookMethodAfter(method) { hookParam ->
                        if (!featureState.colorCodedCommentThreads) return@hookMethodAfter
                        val changed = colorUserCommentViewStateGraph(hookParam.result)
                        if (changed > 0) {
                            logBlock(coloredThreadLines, "Applied Reddit comment thread colors to $changed view state(s)")
                        }
                    }
                }
            logInfo("Hooked Reddit stable user comment ViewModel colors ${viewModelClass.name}")
        }.onFailure { throwable ->
            logError("Failed to hook Reddit stable user comment ViewModel colors ${viewModelClass.name}", throwable)
        }
    }

    private fun hookCommentModelThreadDecoration(commentClass: Class<*>) {
        runCatching {
            val decorationClass = runCatching { classLoader.loadClass("defpackage.k59") }
                .recoverCatching { classLoader.loadClass("k59") }
                .getOrThrow()
            val commentColorClass = classLoader.loadClass("com.reddit.comments.presentation.CommentColor")
            val colorValues = commentColorClass.getDeclaredMethod("values").invoke(null) as Array<*>
            val decorationCtor = decorationClass.declaredConstructors.first { it.parameterTypes.size == 3 }
                .apply { isAccessible = true }
            XposedBridge.hookAllConstructors(
                commentClass,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam<*>) {
                        if (!featureState.colorCodedCommentThreads || param.args.size <= 21) return
                        val depth = param.args.getOrNull(1) as? Int ?: return
                        val lineColor = colorValues[Math.floorMod(depth, colorValues.size)] ?: return
                        param.args[21] = decorationCtor.newInstance(lineColor, lineColor, 8)
                        logBlock(coloredThreadLines, "Applied Reddit comment model thread color at depth $depth")
                    }
                }
            )
            logInfo("Hooked Reddit comment model thread decoration colors")
        }.onFailure { throwable ->
            logError("Failed to hook Reddit comment model thread decoration colors", throwable)
        }
    }

    private fun hookComposeLazyState(lazyStateClass: Class<*>) {
        runCatching {
            XposedBridge.hookAllConstructors(
                lazyStateClass,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam<*>) {
                        val state = param.thisObject ?: return
                        composeLazyStates.add(state)
                        val count = composeLazyStates.size
                        if (count <= 5 || count % 25 == 0) {
                            logInfo("Captured Reddit Compose scroll state ${state.javaClass.name} (tracked=$count)")
                        }
                    }
                }
            )
            logInfo("Hooked Reddit Compose lazy scroll state ${lazyStateClass.name}")
        }.onFailure { throwable ->
            logError("Failed to hook Reddit Compose lazy scroll state ${lazyStateClass.name}", throwable)
        }
    }

    private fun hookCommentAdMapper(commentMapperClass: Class<*>) {
        hookNullableReturnMethods(
            clazz = commentMapperClass,
            reason = "comment page ad mapper",
            counter = commentAdBlocks,
            replacement = { null },
            enabled = { featureState.blockCommentAds },
            include = { method -> method.name == "c" && method.parameterTypes.isNotEmpty() }
        )
    }

    private fun hookPostDetailAdSource(postDetailSourceClass: Class<*>) {
        hookNullableReturnMethods(
            clazz = postDetailSourceClass,
            reason = "post detail ad source",
            counter = commentAdBlocks,
            replacement = { null },
            enabled = { featureState.blockCommentAds },
            include = { method -> method.parameterTypes.isNotEmpty() }
        )
    }

    private fun hookNullableReturnMethods(
        clazz: Class<*>,
        reason: String,
        counter: AtomicInteger,
        replacement: () -> Any?,
        enabled: () -> Boolean,
        include: (Method) -> Boolean = { true }
    ) {
        runCatching {
            val methods = clazz.declaredMethods.filter { method ->
                method.returnType != Void.TYPE &&
                    !method.returnType.isPrimitive &&
                    !Modifier.isAbstract(method.modifiers) &&
                    !Modifier.isNative(method.modifiers) &&
                    include(method)
            }

            methods.forEach { method ->
                hookMethod(method) { hookParam ->
                    if (!enabled()) return@hookMethod
                    hookParam.setResult(replacement())
                    logBlock(counter, "Blocked Reddit $reason via ${signature(method)}")
                }
            }

            if (methods.isNotEmpty()) logInfo("Hooked ${methods.size} Reddit $reason method(s) in ${clazz.name}")
        }.onFailure { throwable ->
            logError("Failed to hook Reddit $reason methods in ${clazz.name}", throwable)
        }
    }

    private fun hookReturnMethods(
        clazz: Class<*>,
        reason: String,
        counter: AtomicInteger,
        replacement: Any?,
        enabled: () -> Boolean,
        include: (Method) -> Boolean
    ) {
        runCatching {
            val methods = clazz.declaredMethods.filter { method ->
                !Modifier.isAbstract(method.modifiers) &&
                    !Modifier.isNative(method.modifiers) &&
                    include(method)
            }

            methods.forEach { method ->
                hookMethod(method) { hookParam ->
                    if (!enabled()) return@hookMethod
                    hookParam.setResult(replacement)
                    logBlock(counter, "Forced Reddit $reason via ${signature(method)}")
                }
            }

            if (methods.isNotEmpty()) logInfo("Hooked ${methods.size} Reddit $reason method(s) in ${clazz.name}")
        }.onFailure { throwable ->
            logError("Failed to hook Reddit $reason methods in ${clazz.name}", throwable)
        }
    }

    private fun emptyFlowOrNull(): Any? {
        return runCatching {
            classLoader.loadClass("defpackage.pqf")
                .getDeclaredField("a")
                .apply { isAccessible = true }
                .get(null)
        }.getOrNull()
    }

    private fun hideMatchingUi(view: View) {
        val state = featureState
        if (!state.hasAnyLayoutHook() || view.visibility == View.GONE) return

        val label = singleViewLabel(view).trim()
        if (label.isBlank()) return
        val normalized = label.lowercase()

        val reason = when {
            state.hideCreateButton && looksLikeVisibleCreateTab(view, normalized) -> "Create button"
            state.hideDiscoverCommunitiesButton && (normalized == "discover" || normalized == "communities") -> "Discover/Communities button"
            state.hideRecentlyVisitedShelf && normalized.contains("recently visited") -> "Recently Visited shelf"
            state.hideGamesOnRedditShelf && (
                normalized.contains("games on reddit") ||
                    normalized.contains("discover games") ||
                    normalized.contains("discover more games") ||
                    normalized.contains("bubble shooter") ||
                    normalized.contains("burst bubbles")
                ) -> "Games on Reddit shelf"
            state.hideRedditProShelf && normalized.contains("reddit pro") -> "Reddit Pro shelf"
            state.hideAboutShelf && normalized == "about" -> "About shelf"
            state.hideResourcesShelf && normalized == "resources" -> "Resources shelf"
            state.hideRecommendedCommunities && (normalized.contains("recommended communities") || normalized.contains("related communities")) -> "Related communities shelf"
            state.hideTrendingTodayShelf && (normalized == "trending" || normalized.contains("trending today") || normalized.contains("based on your interests")) -> "Trending Today shelf"
            else -> null
        } ?: return

        val target = if (reason == "Create button") bestBottomNavHideTarget(view) else bestHideTarget(view, normalized)
        if (isUnsafeHideTarget(target)) {
            logInfo("Skipped unsafe Reddit UI hide target ${target.javaClass.name} for $reason")
            return
        }
        target.visibility = View.GONE
        logBlock(hiddenUiElements, "Hid Reddit $reason via ${target.javaClass.name}")
    }

    private fun scheduleLayoutSuppressionScan(view: View) {
        if (view.visibility == View.GONE) return
        val root = view.rootView ?: view
        val now = System.currentTimeMillis()
        val lastScan = layoutSuppressionScanTimes[root] ?: 0L
        if (now - lastScan < 120L) return
        layoutSuppressionScanTimes[root] = now
        val delays = longArrayOf(0L, 50L, 140L, 320L)
        delays.forEach { delay ->
            root.postDelayed({
                if (featureState.hasAnyLayoutHook()) scanAndHideMatchingUi(root)
            }, delay)
        }
    }

    private fun scanAndHideMatchingUi(view: View, depth: Int = 0) {
        if (depth > 14 || view.visibility == View.GONE) return
        hideMatchingUi(view)
        hideEmptyDrawerGamesSpacer(view)
        if (view !is ViewGroup) return
        val count = view.childCount.coerceAtMost(80)
        repeat(count) { index ->
            scanAndHideMatchingUi(view.getChildAt(index), depth + 1)
        }
    }

    private fun scheduleScreenshotSharePromptDismiss(view: View) {
        if (view.visibility == View.GONE) return
        val root = view.rootView ?: view
        val now = System.currentTimeMillis()
        val lastScan = screenshotPromptScanTimes[root] ?: 0L
        if (now - lastScan < 300L) return
        screenshotPromptScanTimes[root] = now
        root.post {
            if (featureState.disableScreenshotPopup) dismissScreenshotSharePrompt(root)
        }
    }

    private fun dismissScreenshotSharePrompt(root: View) {
        if (root.visibility == View.GONE) return
        val text = accessibilityText(root, maxNodes = 260).lowercase()
        if (!looksLikeScreenshotSharePrompt(text)) return

        if (clickScreenshotPromptClose(root)) {
            logBlock(dismissedDialogs, "Dismissed Reddit screenshot share prompt")
            return
        }

        val target = bestScreenshotPromptHideTarget(root)
        if (isUnsafeHideTarget(target)) {
            logInfo("Detected Reddit screenshot share prompt but could not find a safe close/hide target")
            return
        }
        target.visibility = View.GONE
        logBlock(hiddenUiElements, "Hid Reddit screenshot share prompt via ${target.javaClass.name}")
    }

    private fun looksLikeScreenshotSharePrompt(text: String): Boolean {
        return text.contains("sending this post to someone") &&
            text.contains("looks better when you share it")
    }

    private fun isScreenshotShareBannerResource(resources: Resources, id: Int): Boolean {
        return runCatching {
            resources.getResourceEntryName(id) == "screenshot_share_banner_title"
        }.getOrDefault(false)
    }

    private fun clickScreenshotPromptClose(root: View): Boolean {
        val rootNode = runCatching { root.createAccessibilityNodeInfo() }.getOrNull() ?: return false
        val rootWidth = root.width.takeIf { it > 0 } ?: root.resources.displayMetrics.widthPixels
        val rootHeight = root.height.takeIf { it > 0 } ?: root.resources.displayMetrics.heightPixels
        val maxCloseTargetSize = dp(root.context, 112)

        fun nodeLabel(node: AccessibilityNodeInfo): String {
            return listOfNotNull(
                node.text?.toString(),
                node.contentDescription?.toString()
            ).joinToString(" ").replace(Regex("\\s+"), " ").trim().lowercase()
        }

        fun clickNodeOrClickableParent(node: AccessibilityNodeInfo): Boolean {
            if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            val parent = runCatching { node.parent }.getOrNull() ?: return false
            return try {
                parent.isClickable && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            } finally {
                runCatching { parent.recycle() }
            }
        }

        fun visit(node: AccessibilityNodeInfo?): Boolean {
            if (node == null) return false
            val label = nodeLabel(node)
            if (label == "close" || label == "dismiss" || label == "close button" || label == "dismiss button") {
                if (clickNodeOrClickableParent(node)) return true
            }
            if (node.isClickable && label.isBlank()) {
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                val likelyTopRightClose =
                    bounds.width() in 1..maxCloseTargetSize &&
                        bounds.height() in 1..maxCloseTargetSize &&
                        bounds.centerX() >= (rootWidth * 0.72f) &&
                        bounds.top <= (rootHeight * 0.24f)
                if (likelyTopRightClose && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return true
                }
            }
            for (index in 0 until node.childCount) {
                val child = runCatching { node.getChild(index) }.getOrNull()
                try {
                    if (visit(child)) return true
                } finally {
                    runCatching { child?.recycle() }
                }
            }
            return false
        }

        return try {
            visit(rootNode)
        } finally {
            runCatching { rootNode.recycle() }
        }
    }

    private fun bestScreenshotPromptHideTarget(view: View): View {
        var target = view
        var parent = view.parent
        var depth = 0
        while (parent is ViewGroup && depth < 5) {
            if (isUnsafeHideTarget(parent)) break
            val text = viewText(parent).lowercase()
            if (text.contains("sending this post to someone") &&
                text.contains("looks better when you share it") &&
                (text.contains("share") || text.contains("close"))
            ) {
                target = parent
            }
            parent = parent.parent
            depth++
        }
        return target
    }

    private fun singleViewLabel(view: View): String {
        val text = when (view) {
            is TextView -> view.text?.toString()
            else -> null
        }.orEmpty()
        val contentDescription = view.contentDescription?.toString().orEmpty()
        return listOf(text, contentDescription)
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" ")
            .replace(Regex("\\s+"), " ")
    }

    private fun viewText(view: View?): String {
        if (view == null) return ""
        val own = singleViewLabel(view)
        if (view !is ViewGroup) return own
        val childText = buildString {
            repeat(view.childCount) { index ->
                append(' ')
                append(viewText(view.getChildAt(index)))
            }
        }
        return "$own $childText".replace(Regex("\\s+"), " ").trim()
    }

    private fun bestHideTarget(view: View, normalized: String): View {
        var target = view
        var parent = view.parent
        var depth = 0
        while (parent is ViewGroup && depth < 4) {
            if (isUnsafeHideTarget(parent)) break
            val groupText = viewText(parent).lowercase()
            val childCount = parent.childCount
            if (childCount <= 6 && groupText.contains(normalized)) {
                target = parent
            }
            parent = parent.parent
            depth++
        }
        return target
    }

    private fun looksLikeVisibleCreateTab(view: View, normalized: String): Boolean {
        if (normalized == "create" || normalized == "+") return true
        if (!normalized.contains("create") && !normalized.contains("post")) return false
        val root = view.rootView ?: return false
        if (root.height <= 0) return false
        val rect = Rect()
        if (!view.getGlobalVisibleRect(rect)) return false
        val lowerScreen = rect.centerY() > root.height * 0.72f
        val compactTab = rect.height() <= root.height * 0.18f && rect.width() <= root.width * 0.38f
        return lowerScreen && compactTab
    }

    private fun bestBottomNavHideTarget(view: View): View {
        val root = view.rootView ?: return view
        var target = view
        var parent = view.parent
        var depth = 0
        while (parent is ViewGroup && depth < 5) {
            if (isUnsafeHideTarget(parent)) break
            val rect = Rect()
            if (!parent.getGlobalVisibleRect(rect)) break
            val lowerScreen = rect.centerY() > root.height * 0.70f
            val compactTab = rect.height() <= root.height * 0.22f && rect.width() <= root.width * 0.45f
            val text = viewText(parent).lowercase()
            if (lowerScreen && compactTab && (text.contains("create") || text.contains("post") || text.contains("+"))) {
                target = parent
            }
            parent = parent.parent
            depth++
        }
        return target
    }

    private fun hideEmptyDrawerGamesSpacer(view: View) {
        val state = featureState
        if (!state.hideGamesOnRedditShelf || view !is ViewGroup || view.visibility == View.GONE) return
        if (isUnsafeHideTarget(view)) return
        val root = view.rootView ?: return
        if (root.width <= 0 || root.height <= 0 || view.width <= 0 || view.height <= 0) return
        if (viewText(view).isNotBlank()) return
        val rect = Rect()
        if (!view.getGlobalVisibleRect(rect)) return
        val inDrawerColumn = rect.left <= root.width * 0.08f && rect.right <= root.width * 0.62f
        val spacerSized = rect.height() in dp(view.context, 36)..dp(view.context, 150)
        val mostlyDrawerWidth = rect.width() >= root.width * 0.42f
        if (!inDrawerColumn || !spacerSized || !mostlyDrawerWidth) return
        val rootText = accessibilityText(root, maxNodes = 120).lowercase()
        if (!rootText.contains("popular") || !rootText.contains("latest") || !rootText.contains("your communities")) return
        view.visibility = View.GONE
        logBlock(hiddenUiElements, "Removed empty Reddit Games drawer spacer via ${view.javaClass.name}")
    }

    private fun sanitizeShareIntent(intent: Intent) {
        if (!featureState.sanitizeSharingLinks) return

        if (Intent.ACTION_CHOOSER == intent.action) {
            val inner = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            if (inner != null) sanitizeShareIntent(inner)
            return
        }

        if (Intent.ACTION_SEND != intent.action && Intent.ACTION_SEND_MULTIPLE != intent.action) return
        val original = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        val sanitized = sanitizeLinks(original)
        if (sanitized != original) {
            intent.putExtra(Intent.EXTRA_TEXT, sanitized)
            logBlock(sanitizedShares, "Sanitized Reddit sharing link")
        }
    }

    private fun sanitizeLinks(text: String): String {
        val urlRegex = Regex("""https?://[^\s)>\]"}]+""")
        return urlRegex.replace(text) { match ->
            sanitizeUri(match.value)
        }
    }

    private fun sanitizeUri(rawUrl: String): String {
        return runCatching {
            val uri = Uri.parse(rawUrl)
            val builder = uri.buildUpon().clearQuery()
            uri.queryParameterNames
                .filterNot { it.startsWith("utm_", ignoreCase = true) || it in trackingQueryParams }
                .forEach { name ->
                    uri.getQueryParameters(name).forEach { value ->
                        builder.appendQueryParameter(name, value)
                    }
                }
            builder.build().toString()
        }.getOrDefault(rawUrl)
    }

    private fun openExternallyIfNeeded(activity: Activity, intent: Intent): Boolean {
        if (!featureState.openLinksInExternalBrowser) return false
        if (Intent.ACTION_CHOOSER == intent.action || Intent.ACTION_SEND == intent.action || Intent.ACTION_SEND_MULTIPLE == intent.action) return false

        val uri = externalBrowserUri(intent) ?: return false
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return false

        val pointsAtReddit = intent.component?.packageName == context.androidContext.packageName ||
            intent.`package` == context.androidContext.packageName ||
            intent.component?.packageName == "com.reddit.frontpage" ||
            intent.`package` == "com.reddit.frontpage"

        if (!pointsAtReddit) return false
        if (uri.host.orEmpty().contains("reddit.com", ignoreCase = true) ||
            uri.host.orEmpty().contains("redd.it", ignoreCase = true)
        ) {
            return false
        }

        val externalIntent = Intent(Intent.ACTION_VIEW, uri).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            `package` = null
            component = null
        }

        return runCatching {
            launchingExternalBrowser.set(true)
            try {
                activity.startActivity(externalIntent)
            } finally {
                launchingExternalBrowser.set(false)
            }
            logBlock(externalBrowserRedirects, "Opened Reddit post link externally: ${uri.host.orEmpty()}")
            true
        }.getOrElse { throwable ->
            logError("Failed to open Reddit post link externally", throwable)
            false
        }
    }

    private fun externalBrowserUri(intent: Intent): Uri? {
        intent.data?.let { return it }
        return findUrlInExtras(intent.extras)?.let(Uri::parse)
    }

    private fun findUrlInExtras(bundle: Bundle?): String? {
        if (bundle == null) return null
        for (key in bundle.keySet()) {
            val value = runCatching { bundle.get(key) }.getOrNull()
            when (value) {
                is String -> {
                    val match = Regex("""https?://[^\s]+""").find(value)?.value
                    if (match != null && keyLooksLikeUrl(key, value)) return match
                }
                is Uri -> return value.toString()
                is Bundle -> findUrlInExtras(value)?.let { return it }
                is Intent -> {
                    value.data?.let { return it.toString() }
                    findUrlInExtras(value.extras)?.let { return it }
                }
            }
        }
        return null
    }

    private fun keyLooksLikeUrl(key: String, value: String): Boolean {
        val normalizedKey = key.lowercase()
        return normalizedKey.contains("url") ||
            normalizedKey.contains("uri") ||
            normalizedKey.contains("link") ||
            normalizedKey.contains("outbound") ||
            value.startsWith("http://") ||
            value.startsWith("https://")
    }

    private fun handleMatchingDialog(dialog: Dialog) {
        val state = featureState
        if (!state.disableScreenshotPopup && !state.removeNsfwWarningDialog && !state.removeNotificationSuggestionDialog) return
        val root = dialog.window?.decorView ?: return
        val text = viewText(root).lowercase()
        val action = when {
            state.disableScreenshotPopup &&
                text.contains("screenshot") -> {
                null to "screenshot dialog"
            }
            state.removeNsfwWarningDialog &&
                text.contains("nsfw") &&
                (text.contains("continue") || text.contains("mature content") || text.contains("not safe for work")) -> {
                "Continue" to "NSFW warning dialog"
            }
            state.removeNotificationSuggestionDialog &&
                text.contains("notification") &&
                (text.contains("turn on") || text.contains("notify") || text.contains("updates")) -> {
                "Cancel" to "notification suggestion dialog"
            }
            else -> null
        } ?: return

        root.post {
            val label = action.first
            val button = label?.let { findClickableText(root, it) }
            if (button != null && button.performClick()) {
                logBlock(dismissedDialogs, "Pressed Reddit $label on ${action.second}")
            } else if (label == null) {
                dialog.dismiss()
                logBlock(dismissedDialogs, "Dismissed Reddit ${action.second}")
            } else {
                logInfo("Could not find Reddit $label button on ${action.second}")
            }
        }
    }

    private fun installScrollToTopButton(view: View) {
        val activity = view.context.findActivity() ?: return
        if (!activitiesWithTopButton.add(activity)) return
        val decor = activity.window?.decorView as? FrameLayout ?: return

        val button = TextView(activity).apply {
            text = "⇈"
            textSize = 28f
            gravity = Gravity.CENTER
            setTextColor(Color.BLACK)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.WHITE)
                cornerRadius = dp(activity, 14).toFloat()
            }
            elevation = dp(activity, 8).toFloat()
            visibility = View.GONE
            setOnClickListener {
                val targetCount = scrollToTop(decor)
                visibility = View.GONE
                logBlock(hiddenUiElements, "Scrolled Reddit view to top using $targetCount target(s)")
            }
        }

        val size = dp(activity, 56)
        val margin = dp(activity, 18)
        decor.addView(
            button,
            FrameLayout.LayoutParams(size, size).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                rightMargin = margin
                bottomMargin = dp(activity, 96)
            }
        )

        decor.viewTreeObserver.addOnScrollChangedListener {
            val show = hasScrollableContentAbove(decor)
            button.visibility = if (show) View.VISIBLE else View.GONE
        }
        logInfo("Installed Reddit scroll-to-top button")
    }

    private fun filterCommunityDrawerList(presenter: Any) {
        val state = featureState
        if (!state.hasAnyDrawerHook()) return

        val hiddenRefs = drawerHiddenReferences(presenter)
        val fields = drawerListFields(presenter)
        var removed = 0

        fields.forEach { field ->
            val original = runCatching { field.get(presenter) as? List<*> }.getOrNull() ?: return@forEach
            if (original.isEmpty()) return@forEach

            val filtered = collapseDrawerDividers(original.filterNot { item ->
                item != null && shouldHideDrawerItem(item, hiddenRefs)
            })

            if (filtered.size == original.size) return@forEach
            runCatching {
                field.set(presenter, filtered)
                removed += original.size - filtered.size
            }
        }

        if (removed > 0) {
            logBlock(hiddenUiElements, "Filtered Reddit community drawer items ($removed removed)")
        }
    }

    private fun drawerListFields(presenter: Any): List<Field> {
        val declared = mutableListOf<Field>()
        var current: Class<*>? = presenter.javaClass
        while (current != null) {
            current.declaredFields
                .filter { List::class.java.isAssignableFrom(it.type) }
                .forEach { field ->
                    field.isAccessible = true
                    declared.add(field)
                }
            current = current.superclass
        }

        val primary = declared.firstOrNull { it.name == "A0" }
        return buildList {
            primary?.let(::add)
            declared.filterNot { it === primary }.forEach(::add)
        }
    }

    private fun drawerHiddenReferences(presenter: Any): List<Any> {
        val state = featureState
        val refs = mutableListOf<Any>()

        fun addField(name: String) {
            getFieldValue(presenter, name)?.let(refs::add)
        }

        fun addListField(name: String) {
            (getFieldValue(presenter, name) as? Iterable<*>)?.forEach { item ->
                if (item != null) refs.add(item)
            }
        }

        if (state.hideRecentlyVisitedShelf) {
            addField("Y0")
            addListField("Z0")
        }
        if (state.hideGamesOnRedditShelf) {
            addField("F0")
            addField("G0")
            addField("K0")
            addField("L0")
            addField("r1")
            addField("s1")
            getFieldValue(presenter, "J0")?.let { stateHolder ->
                invokeNoArgResult(stateHolder, "getValue")?.let(refs::add)
            }
        }
        if (state.hideRedditProShelf) {
            addField("d1")
            addField("e1")
            addField("f1")
        }
        if (state.hideResourcesShelf) {
            addField("N0")
            addListField("O0")
        }
        if (state.hideAboutShelf) {
            addField("t1")
            addListField("E1")
        }

        return refs
    }

    private fun shouldHideDrawerItem(item: Any, hiddenRefs: List<Any>): Boolean {
        val state = featureState
        if (hiddenRefs.any { it === item }) return true

        val label = item.toString()
        val normalized = label
            .replace("_", " ")
            .replace("-", " ")
            .lowercase()
        return when {
            state.hideRecentlyVisitedShelf &&
                (label.contains("RECENTLY_VISITED") || normalized.contains("recently visited")) -> true
            state.hideGamesOnRedditShelf &&
                (label.contains("GAMES_ON_REDDIT") ||
                    label.contains("GamesCoachMark") ||
                    label.contains("games_feed_item") ||
                    normalized.contains("games on reddit") ||
                    normalized.contains("discover more games") ||
                    normalized.contains("bubble shooter") ||
                    normalized.contains("burst bubbles") ||
                    normalized.contains("games")) -> true
            state.hideRedditProShelf && (label.contains("REDDIT_PRO") || normalized.contains("reddit pro")) -> true
            state.hideResourcesShelf && (label.contains("RESOURCES") || normalized.contains("resources")) -> true
            state.hideAboutShelf && (label.contains("ABOUT") || normalized.contains("about")) -> true
            else -> false
        }
    }

    private fun collapseDrawerDividers(items: List<*>): List<*> {
        val result = mutableListOf<Any?>()
        items.forEach { item ->
            val divider = isDrawerDivider(item)
            if (divider && result.lastOrNull()?.let(::isDrawerDivider) == true) return@forEach
            result.add(item)
        }
        while (result.firstOrNull()?.let(::isDrawerDivider) == true) result.removeAt(0)
        while (result.lastOrNull()?.let(::isDrawerDivider) == true) result.removeAt(result.lastIndex)
        return result
    }

    private fun isDrawerDivider(item: Any?): Boolean {
        return item?.javaClass?.name == "defpackage.bke" || item?.toString()?.contains("DividerItemUiModel") == true
    }

    private fun findClickableText(view: View?, expected: String): View? {
        if (view == null) return null
        val normalized = expected.lowercase()
        val label = singleViewLabel(view).lowercase()
        if (label == normalized || label.contains(normalized)) {
            if (view.isClickable || view is TextView) return view
        }
        if (view is ViewGroup) {
            repeat(view.childCount) { index ->
                val child = findClickableText(view.getChildAt(index), expected)
                if (child != null) return child
            }
        }
        return null
    }

    private fun looksLikeTrendingSearchItem(item: Any?): Boolean {
        val text = item?.toString().orEmpty()
        if (text.isBlank()) return false
        return text.contains("trending", ignoreCase = true) ||
            text.contains("Based on your interests", ignoreCase = true) ||
            text.contains("SearchStructureType.TRENDING", ignoreCase = true) ||
            text.contains("structureType=TRENDING", ignoreCase = true) ||
            text.contains("searchStructureType=TRENDING", ignoreCase = true) ||
            text.contains("Noun.Trending", ignoreCase = true) ||
            text.contains("TRENDING_SEARCH", ignoreCase = true) ||
            text.contains("typeahead_trending", ignoreCase = true)
    }

    private fun isCommunityRecommendationGraphQlFlag(fieldName: String): Boolean {
        return fieldName == "includeCarouselRecommendations" ||
            fieldName == "includeListStyleRecommendations" ||
            fieldName == "includeCompactPostStyleRecommendations" ||
            fieldName == "includeCardPostStyleRecommendations" ||
            fieldName == "includeRankedCommunityFeedElement" ||
            fieldName == "includeNewInCommunitiesCarousel" ||
            fieldName == "includeTaxonomyTopicsFeedElement" ||
            fieldName == "includeExploreFeaturedItemsFeedElement" ||
            fieldName == "includeTopicGroupFeedElement"
    }

    private fun suppressCommunityRecommendationGraphQl(body: String): String {
        if (!body.contains("CommunityRecommendations", ignoreCase = true) &&
            !body.contains("communityRecommendations", ignoreCase = true) &&
            !body.contains("relatedCommunityRecommendations", ignoreCase = true)
        ) return body

        var rewritten = body
        communityRecommendationGraphQlFlags().forEach { flag ->
            rewritten = rewritten.replace(Regex("(\"$flag\"\\s*:\\s*)true"), "\$1false")
        }
        rewritten = rewritten.replace(
            Regex("""(@include\(if:\s*${'$'}include(?:Carousel|ListStyle|CompactPostStyle|CardPostStyle|RankedCommunity|NewInCommunities|TaxonomyTopics|ExploreFeaturedItems|TopicGroup)[A-Za-z0-9_]*\))"""),
            "@include(if: false)"
        )
        if (rewritten.contains("relatedCommunityRecommendations", ignoreCase = true)) {
            rewritten = rewritten.replace(Regex("(\"useCase\"\\s*:\\s*\")([^\"]*)(\")"), "\$1purrfect_hidden\$3")
        }
        return rewritten
    }

    private fun communityRecommendationGraphQlFlags(): Array<String> {
        return arrayOf(
            "includeCarouselRecommendations",
            "includeListStyleRecommendations",
            "includeCompactPostStyleRecommendations",
            "includeCardPostStyleRecommendations",
            "includeRankedCommunityFeedElement",
            "includeNewInCommunitiesCarousel",
            "includeTaxonomyTopicsFeedElement",
            "includeExploreFeaturedItemsFeedElement",
            "includeTopicGroupFeedElement"
        )
    }

    private fun looksLikeRelatedCommunityItem(item: Any?): Boolean {
        if (item == null) return false
        val className = item.javaClass.name
        val text = item.toString()
        val id = runCatching {
            item.javaClass.methods.firstOrNull { method ->
                method.name == "getLinkId" && method.parameterTypes.isEmpty()
            }?.invoke(item)?.toString()
                ?: item.javaClass.methods.firstOrNull { method ->
                    method.name == "a" && method.parameterTypes.isEmpty()
                }?.invoke(item)?.toString()
        }.getOrNull().orEmpty()
        val feedId = runCatching {
            item.javaClass.methods.firstOrNull { method ->
                method.name == "c" && method.parameterTypes.isEmpty() && method.returnType == String::class.java
            }?.invoke(item)?.toString()
        }.getOrNull().orEmpty()

        return className.contains("relatedcommunities", ignoreCase = true) ||
            className.contains("chatactivation", ignoreCase = true) ||
            className.contains("subredditnavigation", ignoreCase = true) ||
            id.contains("related_community", ignoreCase = true) ||
            id.contains("related_communities", ignoreCase = true) ||
            id.contains("same_community", ignoreCase = true) ||
            feedId.contains("related_community", ignoreCase = true) ||
            feedId.contains("related_communities", ignoreCase = true) ||
            feedId.contains("same_community", ignoreCase = true) ||
            text.contains("related_community", ignoreCase = true) ||
            text.contains("related communities", ignoreCase = true) ||
            text.contains("RelatedCommunities", ignoreCase = true) ||
            text.contains("join_related_community", ignoreCase = true) ||
            text.contains("RelatedCommunityRecommendations", ignoreCase = true) ||
            text.contains("communityRecommendations", ignoreCase = true) ||
            text.contains("contentRecommendations", ignoreCase = true) ||
            text.contains("same_community", ignoreCase = true) ||
            text.contains("RelatedCommunitiesFeedUnit", ignoreCase = true) ||
            text.contains("CarouselCommunityRecommendationsFeedUnit", ignoreCase = true) ||
            text.contains("CarouselCommunityRecommendationsFragment", ignoreCase = true) ||
            text.contains("ListStyleCommunityRecommendationsFeedUnit", ignoreCase = true) ||
            text.contains("ListStyleCommunityRecommendationsFragment", ignoreCase = true) ||
            text.contains("CompactPostCommunityRecommendationsFeedUnit", ignoreCase = true) ||
            text.contains("CompactPostCommunityRecommendationsFragment", ignoreCase = true) ||
            text.contains("CardPostCommunityRecommendationsFeedUnit", ignoreCase = true) ||
            text.contains("CardPostCommunityRecommendationsFragment", ignoreCase = true) ||
            text.contains("RankedCommunityFeedElement", ignoreCase = true) ||
            text.contains("RankedCommunityFragment", ignoreCase = true) ||
            text.contains("PostCarousel", ignoreCase = true) && text.contains("community", ignoreCase = true) ||
            text.contains("NewInCommunitiesCarousel", ignoreCase = true) ||
            shallowObjectTextContains(
                item,
                "communityRecommendations",
                "RelatedCommunitiesFeedUnit",
                "CarouselCommunityRecommendations",
                "ListStyleCommunityRecommendations",
                "CompactPostCommunityRecommendations",
                "CardPostCommunityRecommendations",
                "RankedCommunityFeedElement",
                "NewInCommunitiesCarousel",
                "same_community"
            )
    }

    private fun shallowObjectTextContains(item: Any, vararg needles: String): Boolean {
        val seen = Collections.newSetFromMap(WeakHashMap<Any, Boolean>())
        fun scan(value: Any?, depth: Int): Boolean {
            if (value == null || depth > 2) return false
            if (value is String) return needles.any { needle -> value.contains(needle, ignoreCase = true) }
            if (value.javaClass.isPrimitive || value is Number || value is Boolean || value is CharSequence) {
                val text = value.toString()
                return needles.any { needle -> text.contains(needle, ignoreCase = true) }
            }
            if (!seen.add(value)) return false
            val text = runCatching { value.toString() }.getOrNull().orEmpty()
            if (needles.any { needle -> text.contains(needle, ignoreCase = true) }) return true
            if (value is Iterable<*>) return value.take(8).any { scan(it, depth + 1) }
            if (value is Map<*, *>) {
                return value.entries.take(8).any { entry -> scan(entry.key, depth + 1) || scan(entry.value, depth + 1) }
            }
            value.javaClass.declaredFields
                .asSequence()
                .filterNot { Modifier.isStatic(it.modifiers) }
                .take(16)
                .forEach { field ->
                    if (needles.any { needle -> field.name.contains(needle, ignoreCase = true) }) return true
                    val fieldValue = runCatching {
                        field.isAccessible = true
                        field.get(value)
                    }.getOrNull()
                    if (scan(fieldValue, depth + 1)) return true
                }
            return false
        }
        return scan(item, 0)
    }

    private data class FilteredResult(val value: Any, val removed: Int)

    private fun filterRelatedCommunityResult(original: Any): FilteredResult? {
        if (original is List<*>) {
            val filtered = original.filterNot(::looksLikeRelatedCommunityItem)
            val removed = original.size - filtered.size
            return if (removed > 0) FilteredResult(filtered, removed) else null
        }

        val filteredCollection = filterPersistentCollection(original, ::looksLikeRelatedCommunityItem)
            ?: return null
        val before = (original as? Iterable<*>)?.count() ?: -1
        val after = (filteredCollection as? Iterable<*>)?.count() ?: -1
        val removed = if (before >= 0 && after >= 0) before - after else 1
        return if (removed > 0) FilteredResult(filteredCollection, removed) else null
    }

    private fun applyCommentThreadColors(indentView: Any) {
        if (!featureState.colorCodedCommentThreads) return
        val view = indentView as? View ?: return
        val colors = redditCommentThreadColors()
        val lineWidth = dp(view.context, 2)
        val existingColors = runCatching {
            indentView.javaClass.methods.firstOrNull { it.name == "getLineColors" && it.parameterTypes.isEmpty() }
                ?.invoke(indentView) as? IntArray
        }.getOrNull()
        val existingWidth = runCatching {
            indentView.javaClass.methods.firstOrNull { it.name == "getLineWidth" && it.parameterTypes.isEmpty() }
                ?.invoke(indentView) as? Int
        }.getOrNull()
        if (existingColors?.contentEquals(colors) == true && existingWidth == lineWidth) return
        invokeOneArg(indentView, "setLineColors", IntArray::class.java, colors)
        invokeOneArg(indentView, "setLineWidth", Int::class.javaPrimitiveType, lineWidth)
        view.invalidate()
        logBlock(coloredThreadLines, "Applied Reddit comment indent palette via ${indentView.javaClass.name}")
    }

    private fun colorUserCommentViewStateGraph(root: Any?): Int {
        if (root == null) return 0
        val seen = Collections.newSetFromMap(WeakHashMap<Any, Boolean>())
        var changed = 0

        fun visit(value: Any?, depth: Int) {
            if (value == null || depth > 5 || changed > 80) return
            if (value is String || value is Number || value is Boolean || value is CharSequence) return
            if (!seen.add(value)) return

            val text = runCatching { value.toString() }.getOrNull().orEmpty()
            if (text.contains("UserCommentViewState(depth=", ignoreCase = true)) {
                if (applyStructuralCommentDecoration(value)) changed++
                return
            }

            when (value) {
                is Iterable<*> -> {
                    value.take(80).forEach { visit(it, depth + 1) }
                    return
                }
                is Map<*, *> -> {
                    value.entries.take(80).forEach { entry ->
                        visit(entry.key, depth + 1)
                        visit(entry.value, depth + 1)
                    }
                    return
                }
            }

            if (value.javaClass.isArray) {
                val size = java.lang.reflect.Array.getLength(value).coerceAtMost(80)
                for (index in 0 until size) visit(java.lang.reflect.Array.get(value, index), depth + 1)
                return
            }

            value.javaClass.declaredFields
                .asSequence()
                .filterNot { Modifier.isStatic(it.modifiers) }
                .take(32)
                .forEach { field ->
                    val fieldValue = runCatching {
                        field.isAccessible = true
                        field.get(value)
                    }.getOrNull()
                    visit(fieldValue, depth + 1)
                }
        }

        visit(root, 0)
        return changed
    }

    private fun applyStructuralCommentDecoration(commentState: Any): Boolean {
        val depth = commentState.javaClass.declaredFields
            .asSequence()
            .filter { it.type == Int::class.javaPrimitiveType }
            .mapNotNull { field ->
                runCatching {
                    field.isAccessible = true
                    field.getInt(commentState)
                }.getOrNull()
            }
            .firstOrNull { it >= 0 }
            ?: return false

        val decorationField = commentState.javaClass.declaredFields.firstOrNull { field ->
            !Modifier.isStatic(field.modifiers) && looksLikeCommentDecorationType(field.type)
        } ?: return false

        val decoration = newStructuralCommentDecoration(decorationField.type, depth) ?: return false
        return runCatching {
            decorationField.isAccessible = true
            val current = decorationField.get(commentState)
            if (current?.toString()?.contains(decoration.toString(), ignoreCase = true) == true) return false
            decorationField.set(commentState, decoration)
            true
        }.getOrDefault(false)
    }

    private fun looksLikeCommentDecorationType(type: Class<*>): Boolean {
        return type.declaredFields.count { field -> field.type.isEnum } >= 2 &&
            type.declaredFields.any { field -> field.type == Float::class.javaPrimitiveType } &&
            type.declaredFields.any { field -> field.type == Int::class.javaPrimitiveType } &&
            type.declaredConstructors.any { ctor ->
                ctor.parameterTypes.size == 3 &&
                    ctor.parameterTypes[0].isEnum &&
                    ctor.parameterTypes[0] == ctor.parameterTypes[1] &&
                    ctor.parameterTypes[2] == Int::class.javaPrimitiveType
            }
    }

    private fun newStructuralCommentDecoration(decorationType: Class<*>, depth: Int): Any? {
        val ctor = decorationType.declaredConstructors.firstOrNull { candidate ->
            candidate.parameterTypes.size == 3 &&
                candidate.parameterTypes[0].isEnum &&
                candidate.parameterTypes[0] == candidate.parameterTypes[1] &&
                candidate.parameterTypes[2] == Int::class.javaPrimitiveType
        } ?: return null
        val enumValues = ctor.parameterTypes[0].enumConstants ?: return null
        if (enumValues.isEmpty()) return null
        val color = enumValues[Math.floorMod(depth, enumValues.size)]
        return runCatching {
            ctor.isAccessible = true
            ctor.newInstance(color, color, 8)
        }.getOrNull()
    }

    private fun colorCommentCanvasLine(args: Array<Any?>) {
        val x1 = args[0] as? Float ?: return
        val y1 = args[1] as? Float ?: return
        val x2 = args[2] as? Float ?: return
        val y2 = args[3] as? Float ?: return
        val paint = args[4] as? Paint ?: return
        if (abs(x1 - x2) > 3f) return
        if (abs(y2 - y1) < 24f) return
        if (x1 < 0f || x1 > 220f) return
        paint.color = commentThreadColorForX(x1)
        logBlock(coloredThreadLines, "Colored Reddit comment Canvas thread line")
    }

    private fun colorCommentCanvasRect(args: Array<Any?>) {
        val paint = args.lastOrNull() as? Paint ?: return
        val rect = canvasRectBounds(args) ?: return
        val left = rect[0]
        val top = rect[1]
        val right = rect[2]
        val bottom = rect[3]
        val width = abs(right - left)
        val height = abs(bottom - top)
        if (width > 7f) return
        if (height < 24f) return
        if (left < 0f || left > 220f) return
        paint.color = commentThreadColorForX(left)
        logBlock(coloredThreadLines, "Colored Reddit comment Canvas thread rect")
    }

    private fun colorCommentCanvasPath(args: Array<Any?>) {
        val path = args.firstOrNull() as? Path ?: return
        val paint = args.lastOrNull() as? Paint ?: return
        val bounds = RectF()
        path.computeBounds(bounds, true)
        val width = abs(bounds.right - bounds.left)
        val height = abs(bounds.bottom - bounds.top)
        if (width > 16f) return
        if (height < 24f) return
        if (bounds.left < 0f || bounds.left > 220f) return
        paint.color = commentThreadColorForX(bounds.left)
        logBlock(coloredThreadLines, "Colored Reddit comment Canvas thread path")
    }

    private fun canvasRectBounds(args: Array<Any?>): FloatArray? {
        if (args.size >= 5 &&
            args[0] is Float &&
            args[1] is Float &&
            args[2] is Float &&
            args[3] is Float
        ) {
            return floatArrayOf(args[0] as Float, args[1] as Float, args[2] as Float, args[3] as Float)
        }

        val rect = args.firstOrNull { it?.javaClass?.name == "android.graphics.RectF" } ?: return null
        return runCatching {
            floatArrayOf(
                rect.javaClass.getField("left").getFloat(rect),
                rect.javaClass.getField("top").getFloat(rect),
                rect.javaClass.getField("right").getFloat(rect),
                rect.javaClass.getField("bottom").getFloat(rect)
            )
        }.getOrNull()
    }

    private fun commentThreadColorForX(x: Float): Int {
        val colors = redditCommentThreadColors()
        return colors[abs((x / 18f).toInt()) % colors.size]
    }

    private fun redditCommentThreadColors(): IntArray {
        return intArrayOf(
            Color.rgb(0, 200, 110),
            Color.rgb(255, 190, 0),
            Color.rgb(0, 180, 255),
            Color.rgb(255, 70, 110),
            Color.rgb(190, 110, 255),
            Color.rgb(255, 120, 45)
        )
    }

    private fun colorPossibleCommentThreadLine(view: View) {
        if (view.visibility != View.VISIBLE) return
        if (view.javaClass.name.contains("CommentIndentView")) {
            applyCommentThreadColors(view)
        }
    }

    private fun isLikelyRedditCommentThreadScreen(root: View): Boolean {
        val text = accessibilityText(root).lowercase()
        if (text.isBlank()) return false
        val replySignals = Regex("\\breply\\b").findAll(text).take(4).count()
        return text.contains("join the conversation") ||
            text.contains("add a comment") ||
            text.contains("view all comments") ||
            (replySignals >= 2 && (text.contains("upvote") || text.contains("downvote") || text.contains("comments")))
    }

    private fun accessibilityText(view: View, maxNodes: Int = 160): String {
        val rootNode = runCatching { view.createAccessibilityNodeInfo() }.getOrNull()
            ?: return viewText(view)
        val parts = ArrayList<String>()
        var visited = 0

        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null || visited >= maxNodes) return
            visited++
            node.text?.toString()?.takeIf { it.isNotBlank() }?.let(parts::add)
            node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let(parts::add)
            for (index in 0 until node.childCount) {
                val child = runCatching { node.getChild(index) }.getOrNull()
                visit(child)
                runCatching { child?.recycle() }
            }
        }

        visit(rootNode)
        runCatching { rootNode.recycle() }
        val accessibility = parts.joinToString(" ").replace(Regex("\\s+"), " ").trim()
        return if (accessibility.isNotBlank()) accessibility else viewText(view)
    }

    private fun hasScrollableContentAbove(view: View): Boolean {
        if (view.canScrollVertically(-1)) return true
        if (view is ViewGroup) {
            repeat(view.childCount) { index ->
                if (hasScrollableContentAbove(view.getChildAt(index))) return true
            }
        }
        return false
    }

    private fun scrollToTop(view: View): Int {
        val targets = mutableListOf<View>()
        val composeTargets = scrollComposeLazyStatesToTop()
        collectScrollableViews(view, targets)
        targets
            .distinct()
            .sortedByDescending { it.height }
            .forEach { target ->
                if (!invokeOneArg(target, "scrollToPosition", Int::class.javaPrimitiveType, 0)) {
                    invokeOneArg(target, "smoothScrollToPosition", Int::class.javaPrimitiveType, 0)
                }
                runCatching {
                    val args = Bundle().apply {
                        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_ROW_INT, 0)
                        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_COLUMN_INT, 0)
                    }
                    target.performAccessibilityAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_TO_POSITION.id, args)
                }
                runCatching {
                    target.requestFocus()
                    target.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MOVE_HOME))
                    target.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MOVE_HOME))
                }
                repeat(28) {
                    runCatching {
                        target.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, null)
                    }
                    runCatching {
                        target.performAccessibilityAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id, null)
                    }
                }
                runCatching { target.scrollTo(0, 0) }
                target.postDelayed({
                    if (target.canScrollVertically(-1)) {
                        invokeOneArg(target, "scrollToPosition", Int::class.javaPrimitiveType, 0)
                        repeat(18) {
                            runCatching { target.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, null) }
                            runCatching { target.performAccessibilityAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id, null) }
                        }
                        runCatching { target.scrollTo(0, 0) }
                    }
                }, 80L)
            }
        return targets.size + composeTargets
    }

    private fun scrollComposeLazyStatesToTop(): Int {
        var scrolled = 0
        composeLazyStates.toList().forEach { state ->
            val directMethods = state.javaClass.methods.asSequence()
                .plus(state.javaClass.declaredMethods.asSequence())
                .filter { method ->
                    method.parameterTypes.size == 2 &&
                        method.parameterTypes[0] == Int::class.javaPrimitiveType &&
                        method.parameterTypes[1] == Int::class.javaPrimitiveType &&
                        method.returnType == Void.TYPE &&
                        !Modifier.isAbstract(method.modifiers)
                }
                .distinctBy { it.name }
                .toList()
            directMethods.forEach { method ->
                runCatching {
                    method.isAccessible = true
                    method.invoke(state, 0, 0)
                    scrolled++
                }.onFailure { throwable ->
                    logError("Failed to request Reddit Compose scroll-to-top via ${state.javaClass.name}.${method.name}", throwable)
                }
            }
        }
        if (scrolled > 0) {
            val count = composeScrolls.incrementAndGet()
            if (count <= 5 || count % 25 == 0) {
                logInfo("Requested Reddit Compose scroll-to-top via $scrolled lazy state method(s)")
            }
        }
        return scrolled
    }

    private fun collectScrollableViews(view: View, out: MutableList<View>) {
        if (view.canScrollVertically(-1)) out.add(view)
        if (view is ViewGroup) {
            repeat(view.childCount) { index -> collectScrollableViews(view.getChildAt(index), out) }
        }
    }

    private fun isUnsafeHideTarget(view: View): Boolean {
        val name = view.javaClass.name
        if (name.contains("ScreenContainerView") ||
            name.contains("AndroidComposeView") ||
            name.contains("ComposeView") ||
            name.contains("DrawerLayout") ||
            name.contains("CoordinatorLayout")
        ) {
            return true
        }

        val root = view.rootView ?: return false
        if (view === root) return true
        val rootWidth = root.width
        val rootHeight = root.height
        if (rootWidth <= 0 || rootHeight <= 0 || view.width <= 0 || view.height <= 0) return false

        val coversMostWidth = view.width >= (rootWidth * 0.92f)
        val coversLargeHeight = view.height >= (rootHeight * 0.30f)
        val coversVisibleSheet = view.width >= (rootWidth * 0.55f) && view.height >= (rootHeight * 0.45f)
        return coversMostWidth && coversLargeHeight || coversVisibleSheet
    }

    private fun Context.findActivity(): Activity? {
        var current: Context? = this
        while (current is ContextWrapper) {
            if (current is Activity) return current
            current = current.baseContext
        }
        return current as? Activity
    }

    private fun dp(context: Context, value: Int): Int {
        return (value * context.resources.displayMetrics.density).toInt()
    }

    private fun findField(clazz: Class<*>, name: String): java.lang.reflect.Field? {
        var current: Class<*>? = clazz
        while (current != null) {
            val field = runCatching { current.getDeclaredField(name) }.getOrNull()
            if (field != null) {
                field.isAccessible = true
                return field
            }
            current = current.superclass
        }
        return null
    }

    private fun getFieldValue(instance: Any, name: String): Any? {
        return runCatching { findField(instance.javaClass, name)?.get(instance) }.getOrNull()
    }

    private fun fieldString(instance: Any?, name: String): String {
        return (instance?.let { getFieldValue(it, name) } ?: "").toString()
    }

    private fun isCollectionLikeType(type: Class<*>): Boolean {
        return Iterable::class.java.isAssignableFrom(type) ||
            Collection::class.java.isAssignableFrom(type) ||
            List::class.java.isAssignableFrom(type) ||
            type.name.contains("Persistent", ignoreCase = true) ||
            type.name.contains("Immutable", ignoreCase = true)
    }

    private fun filterPersistentCollection(collection: Any?, remove: (Any?) -> Boolean): Any? {
        if (collection !is Iterable<*>) return null
        val builder = invokeNoArgResult(collection, "builder") as? MutableCollection<Any?> ?: return null
        var changed = false
        val iterator = builder.iterator()
        while (iterator.hasNext()) {
            if (remove(iterator.next())) {
                iterator.remove()
                changed = true
            }
        }
        if (!changed) return null
        return invokeNoArgResult(builder, "c") ?: builder
    }

    private fun coercePersistentCollection(collection: Any, expectedType: Class<*>): Any? {
        if (expectedType.isInstance(collection)) return collection
        if (collection !is Iterable<*>) return null
        return runCatching {
            val converterClass = classLoader.loadClass("defpackage.lqf")
            val converter = converterClass.methods.firstOrNull { method ->
                Modifier.isStatic(method.modifiers) &&
                    method.parameterTypes.size == 1 &&
                    Iterable::class.java.isAssignableFrom(method.parameterTypes[0]) &&
                    expectedType.isAssignableFrom(method.returnType)
            } ?: return@runCatching null
            converter.invoke(null, collection)?.takeIf { expectedType.isInstance(it) }
        }.getOrNull()
    }

    private fun invokeNoArg(instance: Any, name: String): Boolean {
        return runCatching {
            val method = instance.javaClass.methods.firstOrNull { it.name == name && it.parameterTypes.isEmpty() }
                ?: instance.javaClass.declaredMethods.firstOrNull { it.name == name && it.parameterTypes.isEmpty() }
                ?: return false
            method.isAccessible = true
            method.invoke(instance)
            true
        }.getOrElse { throwable ->
            logError("Failed to invoke Reddit no-arg method ${instance.javaClass.name}#$name", throwable)
            false
        }
    }

    private fun invokeNoArgResult(instance: Any, name: String): Any? {
        return runCatching {
            val method = instance.javaClass.methods.firstOrNull { it.name == name && it.parameterTypes.isEmpty() }
                ?: instance.javaClass.declaredMethods.firstOrNull { it.name == name && it.parameterTypes.isEmpty() }
                ?: return null
            method.isAccessible = true
            method.invoke(instance)
        }.getOrNull()
    }

    private fun invokeOneArg(instance: Any, name: String, type: Class<*>?, value: Any): Boolean {
        return runCatching {
            val method = instance.javaClass.methods.firstOrNull {
                it.name == name && it.parameterTypes.size == 1 && (type == null || it.parameterTypes[0] == type)
            } ?: instance.javaClass.declaredMethods.firstOrNull {
                it.name == name && it.parameterTypes.size == 1 && (type == null || it.parameterTypes[0] == type)
            } ?: return false
            method.isAccessible = true
            method.invoke(instance, value)
            true
        }.getOrDefault(false)
    }

    private fun hookMethod(method: Method, before: (XC_MethodHook.MethodHookParam<*>) -> Unit) {
        method.isAccessible = true
        XposedBridge.hookMethod(
            method,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(hookParam: MethodHookParam<*>) {
                    runCatching {
                        before(hookParam)
                    }.onFailure { throwable ->
                        logError("Failed inside Reddit hook ${signature(method)}", throwable)
                    }
                }
            }
        )
    }

    private fun hookMethodAfter(method: Method, after: (XC_MethodHook.MethodHookParam<*>) -> Unit) {
        method.isAccessible = true
        XposedBridge.hookMethod(
            method,
            object : XC_MethodHook() {
                override fun afterHookedMethod(hookParam: MethodHookParam<*>) {
                    runCatching {
                        after(hookParam)
                    }.onFailure { throwable ->
                        logError("Failed inside Reddit hook ${signature(method)}", throwable)
                    }
                }
            }
        )
    }

    private fun logBlock(counter: AtomicInteger, message: String) {
        val count = counter.incrementAndGet()
        if (count <= 5 || count % 25 == 0) {
            logInfo("$message (count=$count)")
        }
    }

    private fun signature(method: Method): String {
        return "${method.declaringClass.name}.${method.name}(${method.parameterTypes.joinToString { it.simpleName }})"
    }

    private fun logInfo(message: String) {
        XposedBridge.log("[$TAG] $message")
        CoreLogger.xposedLog(message, TAG)
        RedditAppLogWriter.info(context.androidContext, TAG, message)
    }

    private fun logError(message: String, throwable: Throwable) {
        XposedBridge.log("[$TAG] $message: ${throwable.stackTraceToString()}")
        CoreLogger.xposedLog(message, throwable, TAG)
        RedditAppLogWriter.error(context.androidContext, TAG, "$message: ${throwable.message}")
    }

}
