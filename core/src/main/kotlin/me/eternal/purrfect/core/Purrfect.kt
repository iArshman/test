package me.eternal.purrfect.core

import me.eternal.purrfect.common.scripting.JSModule
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.runtime.Composable
import java.lang.reflect.Method
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.eternal.purrfect.bridge.ConfigStateListener
import me.eternal.purrfect.bridge.SyncCallback
import me.eternal.purrfect.common.Constants
import me.eternal.purrfect.common.ReceiversConfig
import me.eternal.purrfect.common.action.EnumAction
import me.eternal.purrfect.common.data.FriendLinkType
import me.eternal.purrfect.common.bridge.FileHandleScope
import me.eternal.purrfect.common.bridge.InternalFileHandleType
import me.eternal.purrfect.common.bridge.toWrapper
import me.eternal.purrfect.common.database.impl.FriendFeedEntry
import me.eternal.purrfect.common.database.impl.FriendInfo
import me.eternal.purrfect.common.data.FriendStreaks
import me.eternal.purrfect.common.data.MessagingFriendInfo
import me.eternal.purrfect.common.data.MessagingGroupInfo
import me.eternal.purrfect.common.util.toSerialized
import me.eternal.purrfect.core.bridge.BridgeClient
import me.eternal.purrfect.core.data.SnapClassCache
import me.eternal.purrfect.core.event.events.impl.NativeUnaryCallEvent
import me.eternal.purrfect.core.event.events.impl.SnapWidgetBroadcastReceiveEvent
import me.eternal.purrfect.core.ui.InAppOverlay
import me.eternal.purrfect.core.ui.CustomComposable
import me.eternal.purrfect.core.util.LSPatchUpdater
import me.eternal.purrfect.core.util.hook.HookAdapter
import me.eternal.purrfect.core.util.hook.HookStage
import me.eternal.purrfect.core.util.hook.findRestrictedMethod
import me.eternal.purrfect.core.util.hook.hook
import me.eternal.purrfect.mapper.impl.PlatformClientAttestationMapper
import me.eternal.purrfect.common.ui.components.AphelionFriendMutationToast
import kotlin.reflect.KClass
import kotlin.system.exitProcess
import kotlin.system.measureTimeMillis

private const val MAPPINGS_GENERATION_STATE_TTL_MS = 10 * 60 * 1000L

class Purrfect {
    companion object {
        lateinit var classLoader: ClassLoader
            private set
        val classCache by lazy {
            SnapClassCache(classLoader)
        }
    }
    private lateinit var appContext: ModContext
    private var isBridgeInitialized = false
    private var android9ValdiBindDisabled = false
    private var android9ValdiBindDisableLogged = false
    private val nativeLateInitTriggered = java.util.concurrent.atomic.AtomicBoolean(false)
    private val earlyMappingsActivityHookInstalled = java.util.concurrent.atomic.AtomicBoolean(false)
    private val mappingsBlockHandled = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile
    private var pendingMappingsBlockReason: String? = null
    private var syncCallback: SyncCallback? = null

    private fun FriendInfo.isCurrentSocialFriend(): Boolean {
        return !userId.isNullOrBlank() &&
            FriendLinkType.fromValue(friendLinkType) == FriendLinkType.MUTUAL &&
            addedTimestamp > 0L
    }

    private fun FriendFeedEntry.toMessagingGroupInfo(): MessagingGroupInfo? {
        if (conversationType != 1 || participantsSize <= 0) return null
        val conversationId = key?.takeIf { it.isNotBlank() } ?: return null
        return MessagingGroupInfo(
            conversationId = conversationId,
            name = feedDisplayName ?: "",
            participantsCount = participantsSize
        )
    }

    private fun hookMainActivity(methodName: String, stage: HookStage = HookStage.AFTER, block: Activity.(param: HookAdapter) -> Unit) {
        Activity::class.java.hook(methodName, stage, { isBridgeInitialized }) { param ->
            val activity = param.thisObject() as Activity
            if (!activity.packageName.equals(Constants.SNAPCHAT_PACKAGE_NAME)) return@hook
            block(activity, param)
        }
    }

    fun init(context: Context) {
        appContext = ModContext(
            androidContext = context.also { classLoader = it.classLoader },
            purrfect = this
        )
        appContext.apply {
            bridgeClient = BridgeClient(this)
            initConfigListener()
            bridgeClient.addOnConnectedCallback {
                bridgeClient.registerMessagingBridge(messagingBridge)
                coroutineScope.launch {
                    runCatching {
                        syncRemote()
                    }.onFailure {
                        log.error("Failed to sync remote", it)
                    }
                }
            }
        }

        runBlocking {
            var throwable: Throwable? = null
            val canLoad = appContext.bridgeClient.connect { throwable = it }
            if (canLoad == null) {
                InAppOverlay.showCrashOverlay(
                    buildString {
                        append("Snapchat timed out while trying to connect to Purrfect\n\n")
                        append("Make sure you:\n")
                        append(" - Have installed the latest Purrfect version (https://www.purrfectgit.com/r/particle-box/purrfect)\n")
                        append(" - Disabled battery optimizations\n")
                        append(" - Excluded Purrfect and Snapchat in HideMyApplist")
                    },
                    throwable
                )
                appContext.logCritical("Cannot connect to the Purrfect app")
                return@runBlocking
            }
            if (!canLoad) exitProcess(1)
            var mappingsBlockedStartup = false
            runCatching {
                installEarlyMappingsBlockActivityHook()
                mappingsBlockedStartup = checkMappingsBeforeFullInit()
                if (!mappingsBlockedStartup) {
                    LSPatchUpdater.onBridgeConnected(appContext)
                }
            }.onFailure {
                appContext.log.error("Failed to init LSPatchUpdater", it)
            }
            if (mappingsBlockedStartup) {
                return@runBlocking
            }
            jetpackComposeResourceHook()
            runCatching {
                measureTimeMillis {
                    init(this)
                }.also {
                    appContext.log.verbose("init took ${it}ms")
                }

                hookMainActivity("onPostCreate") {
                    appContext.mainActivity = this
                    pendingMappingsBlockReason?.let { reason ->
                        handleVisibleMappingsBlock(this, reason)
                        return@hookMainActivity
                    }
                    if (!appContext.mappings.isMappingsLoaded) return@hookMainActivity
                    if (appContext.mappings.isMappingsOutdated()) return@hookMainActivity
                    appContext.isMainActivityPaused = false
                    onActivityCreate(this)
                    appContext.actionManager.onNewIntent(intent)
                }

                hookMainActivity("onPause") {
                    appContext.bridgeClient.closeOverlay()
                    appContext.isMainActivityPaused = true
                }

                hookMainActivity("onNewIntent") { param ->
                    appContext.actionManager.onNewIntent(param.argNullable(0))
                }

                hookMainActivity("onResume") {
                    appContext.mainActivity = this
                    if (appContext.isMainActivityPaused.also {
                        appContext.isMainActivityPaused = false
                    }) {
                        appContext.reloadConfig()
                        appContext.executeAsync {
                            syncRemote()
                        }
                    }
                }
            }.onSuccess {
                isBridgeInitialized = true
            }.onFailure {
                appContext.logCritical("Failed to initialize bridge", it)
                InAppOverlay.showCrashOverlay("Purrfect failed to initialize. Please check logs for more details.", it)
            }
        }
    }

    private fun init(scope: CoroutineScope) {
        with(appContext) {
            Thread::class.java.hook("dispatchUncaughtException", HookStage.BEFORE) { param ->
                runCatching {
                    val throwable = param.argNullable(0) ?: Throwable()
                    logCritical(null, throwable)
                }
            }

            reloadConfig()
            installAndroid9ValdiBindGuard()
            initNative()
            initWidgetListener()
            scope.launch(Dispatchers.IO) {
                translation.userLocale = getConfigLocale()
                translation.load()
            }

            database.init()
            eventDispatcher.init()
            userInterface.init()
            
            // Check mappings status with detailed logging
            log.verbose("Checking mappings status before feature initialization...")
            log.verbose("Mappings loaded: ${mappings.isMappingsLoaded}")
            log.verbose("Mappings outdated: ${mappings.isMappingsOutdated()}")
            
            //if mappings aren't loaded, we can't initialize features
            if (!mappings.isMappingsLoaded) {
                log.warn("Mappings not loaded, skipping features initialization")
                log.warn("Mappings file exists: ${mappings.exists()}")
                log.warn("Generated build number: ${mappings.getGeneratedBuildNumber()}")
                blockSnapchatUntilMappingsRegenerated("missing")
                return
            }
            
            if (mappings.isMappingsOutdated()) {
                log.warn("Mappings are outdated, triggering auto-generation check")
                blockSnapchatUntilMappingsRegenerated("outdated")
                return
            }
            clearMappingsGenerationState("mappings-current")
            
            log.verbose("Initializing features...")
            runCatching {
                features.init()
                
                // Wire up the premium friend mutation toast provider
                features.get(me.eternal.purrfect.core.features.impl.FriendMutationObserver::class)?.let { observer ->
                    observer.aphelionToastProvider = { icon, text, bitmojiUrl, onDismiss ->
                        lateinit var composable: CustomComposable
                        composable = @Composable {
                            AphelionFriendMutationToast(
                                icon = icon,
                                text = text,
                                bitmojiUrl = bitmojiUrl,
                                onDismiss = {
                                    inAppOverlay.removeCustomComposable(composable)
                                    onDismiss()
                                }
                            )
                        }
                        inAppOverlay.addCustomComposable(composable)
                    }
                }
                
                log.verbose("Features initialized successfully")
            }.onFailure { throwable ->
                log.error("Failed to initialize features", throwable)
                longToast(appContext.translation["toast_init_features_failed"])
                // Continue with other initializations even if features fail
            }
            
            log.verbose("Initializing script runtime...")
            runCatching {
                scriptRuntime.init()
                scriptRuntime.eachModule { callFunction("module.onSnapApplicationLoad", androidContext) }
                log.verbose("Script runtime initialized successfully")
            }.onFailure { throwable ->
                log.error("Failed to initialize script runtime", throwable)
                longToast(appContext.translation["toast_init_script_runtime_failed"])
            }
        }
    }


    private fun installEarlyMappingsBlockActivityHook() {
        if (!earlyMappingsActivityHookInstalled.compareAndSet(false, true)) return
        Activity::class.java.hook("onCreate", HookStage.BEFORE) { param ->
            val activity = param.thisObject() as Activity
            if (!activity.packageName.equals(Constants.SNAPCHAT_PACKAGE_NAME)) return@hook
            pendingMappingsBlockReason?.let { reason ->
                appContext.mainActivity = activity
                appContext.log.warn(
                    "Early Snapchat activity creation intercepted for stale mappings; " +
                        "activity=${activity::class.java.name} reason=$reason"
                )
                handleVisibleMappingsBlock(activity, reason)
            }
        }
    }

    private fun checkMappingsBeforeFullInit(): Boolean {
        with(appContext) {
            log.verbose("Checking mappings status at early startup gate...")
            log.verbose("Mappings loaded: ${mappings.isMappingsLoaded}")
            log.verbose("Mappings outdated: ${mappings.isMappingsOutdated()}")
            if (!mappings.isMappingsLoaded) {
                log.warn("Mappings not loaded at early startup gate, blocking Snapchat before full init")
                log.warn("Mappings file exists: ${mappings.exists()}")
                log.warn("Generated build number: ${mappings.getGeneratedBuildNumber()}")
                blockSnapchatUntilMappingsRegenerated("missing")
                return true
            }
            if (mappings.isMappingsOutdated()) {
                log.warn("Mappings are outdated at early startup gate, blocking Snapchat before full init")
                blockSnapchatUntilMappingsRegenerated("outdated")
                return true
            }
            clearMappingsGenerationState("early-mappings-current")
            return false
        }
    }

    private fun blockSnapchatUntilMappingsRegenerated(reason: String) {
        if (isMappingsGenerationInProgress()) {
            appContext.log.warn(
                "Mappings generation already in progress; blocking duplicate Snapchat startup; reason=$reason"
            )
            terminateSnapchatForMappings("$reason generation-in-progress", 0L)
            return
        }
        pendingMappingsBlockReason = reason
        mappingsBlockHandled.set(false)
        appContext.log.warn(
            "Blocking Snapchat startup until mappings are regenerated; reason=$reason waitingForVisibleActivity=true"
        )
        Handler(Looper.getMainLooper()).postDelayed({
            if (pendingMappingsBlockReason == reason && mappingsBlockHandled.compareAndSet(false, true)) {
                val launchedGenerator = triggerMappingsGeneration(
                    reason = reason,
                    completionMode = Constants.MAPPINGS_COMPLETION_MODE_BACKGROUND
                )
                appContext.log.warn(
                    "No visible Snapchat activity before mapping block timeout; " +
                        "launched fallback generator=$launchedGenerator reason=$reason"
                )
                terminateSnapchatForMappings(reason, 25L)
            }
        }, 8000L)
    }

    private fun handleVisibleMappingsBlock(activity: Activity, reason: String) {
        if (!mappingsBlockHandled.compareAndSet(false, true)) return
        pendingMappingsBlockReason = null
        val launchedGenerator = triggerMappingsGeneration(
            reason = reason,
            activity = activity,
            completionMode = Constants.MAPPINGS_COMPLETION_MODE_FOREGROUND
        )
        appContext.log.warn(
            "Visible Snapchat activity reached; launched mapping generator=$launchedGenerator reason=$reason"
        )
        terminateSnapchatForMappings(reason, 0L)
    }

    private fun triggerMappingsGeneration(
        reason: String,
        activity: Activity? = null,
        completionMode: String
    ): Boolean {
        var markedInProgress = false
        return runCatching {
            val autoGen = appContext.bridgeClient.getDebugProp("auto_generate_mappings", "true")
            if (autoGen != "true") {
                appContext.log.verbose("Auto-generation disabled via debug prop; reason=$reason")
                return@runCatching false
            }

            if (!markMappingsGenerationInProgress(reason, completionMode)) {
                return@runCatching false
            }
            markedInProgress = true

            val intent = mappingGenerationIntent(reason, completionMode).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            val launched = if (activity != null) {
                activity.startActivity(intent)
                true
            } else {
                appContext.bridgeClient.openMappingsGenerator(reason, completionMode)
            }
            if (!launched) {
                clearMappingsGenerationState("mapping-generator-launch-failed")
            } else {
                appContext.log.verbose(
                    "Triggered mappings generation via MappingGenerationActivity; " +
                        "reason=$reason completionMode=$completionMode foreground=${activity != null}"
                )
            }
            launched
        }.onFailure {
            appContext.log.warn("Could not trigger mappings generation via MappingGenerationActivity: ${it.message}")
            if (markedInProgress) {
                clearMappingsGenerationState("mapping-generator-launch-exception")
            }
        }.getOrDefault(false)
    }

    private fun mappingGenerationIntent(reason: String, completionMode: String): Intent {
        return Intent(Constants.MAPPINGS_GENERATION_ACTION).apply {
            setClassName(Constants.MODULE_PACKAGE_NAME, "${Constants.MODULE_PACKAGE_NAME}.bridge.MappingGenerationActivity")
            putExtra(Constants.MAPPINGS_GENERATION_REASON_EXTRA, reason)
            putExtra(Constants.MAPPINGS_COMPLETION_MODE_EXTRA, completionMode)
        }
    }

    private fun mappingsGenerationStateFile() = appContext.fileHandlerManager
        .getFileHandle(FileHandleScope.INTERNAL.key, InternalFileHandleType.MAPPINGS_GENERATION_STATE.key)
        .toWrapper()

    private fun isMappingsGenerationInProgress(): Boolean {
        return runCatching {
            val file = mappingsGenerationStateFile()
            if (!file.exists()) return@runCatching false
            val state = file.readBytes().toString(Charsets.UTF_8)
            val startedAt = state.substringBefore('|').toLongOrNull()
            if (startedAt == null) {
                appContext.log.warn("Clearing invalid mappings generation state: $state")
                file.delete()
                return@runCatching false
            }
            val ageMs = System.currentTimeMillis() - startedAt
            if (ageMs in 0..MAPPINGS_GENERATION_STATE_TTL_MS) {
                appContext.log.warn("Mappings generation state is active; ageMs=$ageMs state=$state")
                true
            } else {
                appContext.log.warn("Clearing stale mappings generation state; ageMs=$ageMs state=$state")
                file.delete()
                false
            }
        }.onFailure {
            appContext.log.warn("Failed to read mappings generation state: ${it.message}")
        }.getOrDefault(false)
    }

    private fun markMappingsGenerationInProgress(reason: String, completionMode: String): Boolean {
        if (isMappingsGenerationInProgress()) return false
        return runCatching {
            val state = listOf(
                System.currentTimeMillis().toString(),
                completionMode,
                reason,
                android.os.Process.myPid().toString()
            ).joinToString(separator = "|")
            mappingsGenerationStateFile().writeBytes(state.toByteArray(Charsets.UTF_8))
            appContext.log.verbose(
                "Marked mappings generation in progress; reason=$reason completionMode=$completionMode state=$state"
            )
            true
        }.onFailure {
            appContext.log.warn("Failed to mark mappings generation in progress: ${it.message}")
        }.getOrDefault(false)
    }

    private fun clearMappingsGenerationState(reason: String) {
        runCatching {
            val file = mappingsGenerationStateFile()
            if (file.exists()) {
                val deleted = file.delete()
                appContext.log.verbose("Cleared mappings generation state; reason=$reason deleted=$deleted")
            }
        }.onFailure {
            appContext.log.warn("Failed to clear mappings generation state; reason=$reason error=${it.message}")
        }
    }

    private fun terminateSnapchatForMappings(reason: String, delayMs: Long = 75L) {
        val terminate = Runnable {
            appContext.log.warn("Terminating Snapchat process for stale mappings; reason=$reason")
            android.os.Process.killProcess(android.os.Process.myPid())
            exitProcess(0)
        }
        if (delayMs <= 0L) {
            terminate.run()
        } else {
            Handler(Looper.getMainLooper()).postDelayed(terminate, delayMs)
        }
    }

    private fun onActivityCreate(activity: Activity) {
        measureTimeMillis {
            with(appContext) {
                features.onActivityCreate(activity)
                inAppOverlay.onActivityCreate(activity)
                scriptRuntime.eachModule { callFunction("module.onSnapMainActivityCreate", activity) }
                actionManager.onActivityCreate()
            }
        }.also { time ->
            appContext.log.verbose("onActivityCreate took $time")
        }
    }

    private fun installAndroid9ValdiBindGuard() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return
        val valdiNativeBridge = runCatching {
            classLoader.loadClass("com.snapchat.client.valdi.NativeBridge")
        }.getOrNull()
        val targetClasses = listOf("AXj", "NZj", "MZj")
            .mapNotNull { className ->
                runCatching { classLoader.loadClass(className) }.getOrNull()
            }
            .distinct()
        if (targetClasses.isEmpty()) return
        fun defaultReturn(type: Class<*>): Any? = when (type) {
            Boolean::class.javaPrimitiveType -> false
            Byte::class.javaPrimitiveType -> 0.toByte()
            Short::class.javaPrimitiveType -> 0.toShort()
            Int::class.javaPrimitiveType -> 0
            Long::class.javaPrimitiveType -> 0L
            Float::class.javaPrimitiveType -> 0f
            Double::class.javaPrimitiveType -> 0.0
            Char::class.javaPrimitiveType -> 0.toChar()
            Void.TYPE -> null
            else -> null
        }

        fun installGuard(targetClass: Class<*>, methodName: String) {
            targetClass.hook(methodName, HookStage.BEFORE) { param ->
                val method = param.method() as? Method ?: return@hook
                if (android9ValdiBindDisabled) {
                    if (!android9ValdiBindDisableLogged) {
                        android9ValdiBindDisableLogged = true
                        appContext.log.warn("Skipping Valdi bind on Android 9 due to missing native impl")
                    }
                    param.setResult(defaultReturn(method.returnType))
                    return@hook
                }
                runCatching {
                    param.invokeOriginal()
                }.onSuccess { result ->
                    param.setResult(result)
                }.onFailure { throwable ->
                    val rootCause = throwable.cause ?: throwable
                    val message = rootCause.message ?: throwable.message
                    val isMissingNativeImpl = rootCause is UnsatisfiedLinkError &&
                        message?.contains("NativeBridge.createContext") == true
                    val isValdiContextNpe = rootCause is NullPointerException &&
                        message?.contains("ValdiContext") == true
                    if (isMissingNativeImpl || isValdiContextNpe) {
                        android9ValdiBindDisabled = true
                        if (!android9ValdiBindDisableLogged) {
                            android9ValdiBindDisableLogged = true
                            appContext.log.warn("Skipping Valdi bind on Android 9 due to missing native impl")
                            if (isMissingNativeImpl) {
                                appContext.log.error("Android 9 Valdi NativeBridge.createContext missing native impl", rootCause)
                            }
                        }
                        param.setResult(defaultReturn(method.returnType))
                        return@hook
                    }
                    appContext.log.error("Android 9 Valdi bind hook failed", throwable)
                    param.setResult(defaultReturn(method.returnType))
                }
            }
        }

        targetClasses.forEach { targetClass ->
            installGuard(targetClass, "f")
            installGuard(targetClass, "g2")
            installGuard(targetClass, "n2")
            installGuard(targetClass, "d")
        }

        valdiNativeBridge?.hook("createContext", HookStage.AFTER) { param ->
            val throwable = param.throwable() as? UnsatisfiedLinkError ?: return@hook
            android9ValdiBindDisabled = true
            if (!android9ValdiBindDisableLogged) {
                android9ValdiBindDisableLogged = true
                appContext.log.warn("Skipping Valdi bind on Android 9 due to missing native impl")
                appContext.log.error("Android 9 Valdi NativeBridge.createContext missing native impl", throwable)
            }
            param.setResult(null)
        }
    }

    private fun initNative() {
        val nativeSigCacheFileHandle = appContext.fileHandlerManager.getFileHandle(FileHandleScope.INTERNAL.key, InternalFileHandleType.NATIVE_SIG_CACHE.key).toWrapper()

        val oldSignatureCache = nativeSigCacheFileHandle.readBytes()
            .takeIf {
                it.isNotEmpty()
            }?.toString(Charsets.UTF_8)?.also {
                appContext.native.signatureCache = it
            }

        val lateInit = appContext.native.initOnce {
            nativeUnaryCallCallback = { request ->
                appContext.event.post(NativeUnaryCallEvent(request.uri, request.buffer)) {
                    request.buffer = buffer
                    request.canceled = canceled
                }
            }
            appContext.reloadNativeConfig()
        }.let { init ->
            {
                init()
                appContext.native.signatureCache.takeIf { it != oldSignatureCache }?.let {
                    appContext.log.verbose("new signature cache $it")
                    nativeSigCacheFileHandle.writeBytes(it.toByteArray(Charsets.UTF_8))
                }
            }
        }

        SecurityFeatures(appContext).init()

        Runtime::class.java.findRestrictedMethod {
            it.name == "loadLibrary0" && it.parameterTypes.contentEquals(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) arrayOf(Class::class.java, String::class.java)
                else arrayOf(ClassLoader::class.java, String::class.java)
            )
        }!!.apply {
            if (appContext.disablePlugin) {
                hook(HookStage.BEFORE) { param ->
                    if (param.arg<String>(1) != "scplugin") return@hook
                    param.setResult(null)
                    appContext.log.verbose("skipped scplugin load")

                    appContext.mappings.useMapper(PlatformClientAttestationMapper::class) {
                        pluginNativeClass.getAsClass()?.methods?.filter {
                            it.declaringClass == pluginNativeClass.getAsClass()
                        }?.forEach { method ->
                            method.hook(HookStage.BEFORE) {
                                appContext.log.error("Calling $method", Throwable())
                                it.setResult(null)
                                runCatching { exitProcess(139) }
                                runCatching { Thread.sleep(Long.MAX_VALUE) }
                            }
                        } ?: error("Failed to get pluginNativeClass class")
                    }
                }
            }

            lateinit var unhook: () -> Unit
            hook(HookStage.AFTER) { param ->
                if (param.arg<String>(1) != "client") return@hook
                if (!nativeLateInitTriggered.compareAndSet(false, true)) return@hook
                unhook()
                lateInit()
            }.also { unhook = { it.unhook() } }
        }
    }

    private fun initConfigListener() {
        val tasks = linkedSetOf<() -> Unit>()
        hookMainActivity("onResume") {
            tasks.forEach { it() }
        }

        fun runLater(task: () -> Unit) {
            if (appContext.isMainActivityPaused) {
                tasks.add(task)
            } else {
                task()
            }
        }

        appContext.bridgeClient.addOnConnectedCallback {
            appContext.bridgeClient.registerConfigStateListener(object: ConfigStateListener.Stub() {
                override fun onConfigChanged() {
                    appContext.log.verbose("onConfigChanged")
                    appContext.reloadConfig()
                }

                override fun onRestartRequired() {
                    appContext.log.verbose("onRestartRequired")
                    runLater {
                        appContext.log.verbose("softRestart")
                        appContext.softRestartApp(saveSettings = false)
                    }
                }

                override fun onCleanCacheRequired() {
                    appContext.log.verbose("onCleanCacheRequired")
                    tasks.clear()
                    runLater {
                        appContext.log.verbose("cleanCache")
                        appContext.actionManager.execute(EnumAction.CLEAN_CACHE)
                    }
                }
            })
        }
    }

    private fun initWidgetListener() {
        appContext.event.subscribe(SnapWidgetBroadcastReceiveEvent::class) { event ->
            if (event.action != ReceiversConfig.BRIDGE_SYNC_ACTION) return@subscribe
            event.canceled = true
            val feedEntries = appContext.database.getFeedEntries(Int.MAX_VALUE)

            val groups = feedEntries
                .asSequence()
                .mapNotNull { it.toMessagingGroupInfo() }
                .distinctBy { it.conversationId }
                .toList()

            val friends = appContext.database.getAllFriends()
                .asSequence()
                .filter { friend -> friend.isCurrentSocialFriend() }
                .mapNotNull { friend ->
                    val userId = friend.userId ?: return@mapNotNull null
                    MessagingFriendInfo(
                        userId = userId,
                        dmConversationId = appContext.database.getDMConversationId(userId),
                        displayName = friend.displayName,
                        mutableUsername = friend.mutableUsername ?: friend.usernameForSorting ?: return@mapNotNull null,
                        bitmojiId = friend.bitmojiAvatarId,
                        selfieId = friend.bitmojiSelfieId,
                        streaks = null
                    )
                }
                .toList()

            appContext.bridgeClient.passGroupsAndFriends(groups, friends)
        }
    }

    private fun syncRemote() {
        if (!appContext.isLoggedIn()) {
            syncCallback = null
            return
        }
        
        val myUserId = appContext.database.myUserId
        val streakEntries = appContext.database.getFeedEntries(Int.MAX_VALUE, whereClause = "streak_count IS NOT NULL AND streak_count > 0")
            .associateBy { entry -> (entry.friendUserId ?: entry.participants?.firstOrNull { it != myUserId }) }
            .filter { it.key != null }

        syncCallback = object : SyncCallback.Stub() {
            override fun syncFriend(uuid: String): String? {
                return appContext.database.getFriendInfo(uuid)?.let {
                    if (!it.isCurrentSocialFriend()) return@let null
                    MessagingFriendInfo(
                        userId = it.userId!!,
                        dmConversationId = appContext.database.getDMConversationId(it.userId!!),
                        displayName = it.displayName,
                        mutableUsername = it.mutableUsername!!,
                        bitmojiId = it.bitmojiAvatarId,
                        selfieId = it.bitmojiSelfieId,
                        streaks = if (it.streakLength > 0) {
                            FriendStreaks(
                                expirationTimestamp = it.streakExpirationTimestamp,
                                length = it.streakLength
                            )
                        } else streakEntries[it.userId]?.let {
                            FriendStreaks(
                                expirationTimestamp = it.streakExpirationTimestampMs ?: return@let null,
                                length = it.streakCount ?: return@let null
                            )
                        }
                    ).toSerialized()
                }
            }

            override fun syncGroup(uuid: String): String? {
                return appContext.database.getFeedEntryByConversationId(uuid)?.let {
                    MessagingGroupInfo(
                        it.key!!,
                        it.feedDisplayName ?: "",
                        it.participantsSize
                    ).toSerialized()
                }
            }
        }

        appContext.bridgeClient.sync(syncCallback!!)
    }

    private fun jetpackComposeResourceHook() {
        val stringResources = mutableMapOf<Int, String>()
        
        fun loadStrings(className: String) {
            runCatching {
                val clazz = classLoader.loadClass(className)
                clazz.fields.filter {
                    java.lang.reflect.Modifier.isStatic(it.modifiers) && it.type == Int::class.javaPrimitiveType
                }.forEach { field ->
                    stringResources[field.getInt(null)] = field.name
                }
            }
        }
        
        loadStrings("androidx.compose.material3.R\$string")
        loadStrings("androidx.compose.ui.R\$string")

        fun resolveComposeString(key: Int): String? {
            val name = stringResources[key]?.replaceFirst("m3c_", "") ?: return null
            return appContext.translation.getOrNull("material3_strings.${name}") ?: ""
        }

        fun resolveInvalidString(resources: Resources, key: Int): String? {
            val type = runCatching { resources.getResourceTypeName(key) }.getOrNull() ?: return ""
            return if (type == "string") null else ""
        }

        Resources::class.java.getMethod("getString", Int::class.javaPrimitiveType).hook(HookStage.BEFORE) { param ->
            val key = param.arg<Int>(0)
            resolveComposeString(key)?.let { param.setResult(it); return@hook }
            resolveInvalidString(param.thisObject() as Resources, key)?.let { param.setResult(it) }
        }

        Resources::class.java.getMethod("getText", Int::class.javaPrimitiveType).hook(HookStage.BEFORE) { param ->
            val key = param.arg<Int>(0)
            resolveComposeString(key)?.let { param.setResult(it); return@hook }
            resolveInvalidString(param.thisObject() as Resources, key)?.let { param.setResult(it) }
        }
    }
}
