package me.eternal.purrfect.core.whatsapp

import android.app.Activity
import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.CursorWrapper
import android.database.MatrixCursor
import android.os.Bundle
import android.database.sqlite.SQLiteDatabase
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import me.eternal.purrfect.core.logger.CoreLogger
import java.io.File
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.IdentityHashMap
import java.util.LinkedHashSet
import java.util.Locale
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class WhatsAppPrivacyHooks(
    private val androidContext: Context,
    private val appClassLoader: ClassLoader = androidContext.classLoader
) {
    private val featureState get() = WhatsAppFeatureStateStore.current
    private val viewOnceMessageIds = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val viewOnceMessageRowIds = Collections.newSetFromMap(ConcurrentHashMap<Long, Boolean>())
    private val hookedViewOnceStateModelClasses = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val hookedViewOnceStateMethods = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val viewOnceVisibleRowAccessorCache = ConcurrentHashMap<Class<*>, Boolean>()
    private val revokedMessageClassCache = ConcurrentHashMap<Class<*>, Boolean>()
    private val deletedMessageIds = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val deletedMessageRowIds = Collections.newSetFromMap(ConcurrentHashMap<Long, Boolean>())
    private val pendingDeletedTombstones = ConcurrentHashMap<String, DeletedTombstone>()
    private val deletedVisibleTimestampTargets = ConcurrentHashMap<String, DeletedTimestampTarget>()
    private val deletedVisibleRowAccessorCache = ConcurrentHashMap<Class<*>, Boolean>()
    private val deletedTimestampMessageFieldCache = ConcurrentHashMap<Class<*>, String>()
    private val hookedDeletedRowBinderClasses = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val releasedDeletedMessageIds = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val releasedDeletedMessageRowIds = Collections.newSetFromMap(ConcurrentHashMap<Long, Boolean>())
    private val deletedMessageMarkerLock = Any()
    private var lastWhatsAppDatabase = WeakReference<SQLiteDatabase>(null)
    private val whatsAppMessageDatabases = Collections.synchronizedList(mutableListOf<WeakReference<SQLiteDatabase>>())
    private val uiHandler = Handler(Looper.getMainLooper())
    private val activeDecorViews = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap<View, Boolean>())
    )

    fun init() {
        if (!installed.compareAndSet(false, true)) return
        activeHooks.add(this)

        loadDeletedMessageMarkers()
        registerDeletedMessageActivityTracker()
        hookConcreteReadReceipts()
        hookPlayedReceiptBatcher()
        hookConcretePlayedReceipts()
        hookConcreteChatStateSender()
        hookReadReceiptManager()
        hookMessageClientProtocolSends()
        hookConnectionTransport()
        hookWriterTransport()
        hookAndroidMessagePrivacySurface()
        hookRevokeStore()
        hookRevokedMessageStore()
        hookRevokedMessageInfoStore()
        hookRevokedMessageClassifier()
        hookRevokeEditProcessors()
        hookRevokedMessageBuilders()
        hookRevokedMessageModelBuilders()
        hookDeletedMessageTimestampDecorator()
        hookDeletedMessageRowBindDecorators()
        hookWhatsAppDbWrapperMutations()
        hookSQLiteMutationSurface()
        hookSQLiteQuerySurface()
        hookViewOnceDbReads()
        hookViewOnceStateStore()
        hookViewOnceMessageStateModels()
        hookViewOnceStateModelsByShape()
        hookViewOnceOpenMarker()
        hookViewOnceDelayedOpenMarkers()
        hookViewOnceMessageStoreMarker()
        hookViewOnceCoreStoreWorkers()
        hookViewOnceRowStateRefresh()
        hookConversationRowMessageAccess()
        hookViewOnceConversationRows()
        hookViewOnceViewerLifecycle()

        logLifecycle("Installed WhatsApp privacy hooks")
    }

    private fun hookViewOnceStateStore() {
        listOf("X.1yG", "X.C46781yG").forEach { className ->
            val cls = appClassLoader.findClassOrNull(className) ?: return@forEach
            runCatching {
                XposedBridge.hookAllMethods(
                    cls,
                    "A00",
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam<*>) {
                            if (!featureState.unlimitedViewOnce) return
                            param.result = null
                            val state = param.args.getOrNull(1)?.toString().orEmpty()
                            logPrivacy("unlimited_view_once_store", "suppressed_state_update", "state=$state")
                        }
                    }
                )
                XposedBridge.hookAllMethods(
                    cls,
                    "A01",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam<*>) {
                            if (!featureState.unlimitedViewOnce) return
                            forceViewOnceMessageStateOpenable(param.args.firstOrNull(), "$className.A01")
                        }
                    }
                )
                XposedBridge.hookAllMethods(
                    cls,
                    "A02",
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam<*>) {
                            if (!featureState.unlimitedViewOnce) return
                            forceViewOnceMessageStateOpenable(param.args.firstOrNull(), "$className.A02")
                        }
                    }
                )
                XposedBridge.hookAllMethods(
                    cls,
                    "A03",
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam<*>) {
                            if (!featureState.unlimitedViewOnce) return
                            forceViewOnceMessageStateOpenable(param.args.firstOrNull(), "$className.A03")
                            param.result = null
                            logPrivacy("unlimited_view_once_store", "suppressed_opened_marker", className)
                        }
                    }
                )
                logLifecycle("Hooked WhatsApp view-once state store $className")
            }.onFailure { logError("Failed to hook WhatsApp view-once state store $className", it) }
        }
    }

    private fun hookViewOnceRowStateRefresh() {
        listOf(
            "X.7bz",
            "X.AbstractC165957bz",
            "X.7ce",
            "X.AbstractC166367ce"
        ).forEach { className ->
            val cls = appClassLoader.findClassOrNull(className) ?: return@forEach
            runCatching {
                hookAllIfPresent(cls, "A2f") { param ->
                    if (!featureState.unlimitedViewOnce) return@hookAllIfPresent
                    val message = param.args.firstOrNull()
                    if (!isViewOnceStateModel(message)) return@hookAllIfPresent
                    forceViewOnceMessageStateOpenable(message, "$className.A2f")
                    param.result = null
                    logPrivacy("unlimited_view_once_memory", "suppressed_row_state_refresh", className)
                }
                hookAllIfPresent(cls, "A2e") { param ->
                    if (!featureState.unlimitedViewOnce) return@hookAllIfPresent
                    val message = param.args.firstOrNull()
                    if (!isViewOnceStateModel(message)) return@hookAllIfPresent
                    forceViewOnceMessageStateOpenable(message, "$className.A2e")
                }
                logLifecycle("Hooked WhatsApp view-once row state refresh $className")
            }.onFailure { logError("Failed to hook WhatsApp view-once row state refresh $className", it) }
        }
    }

    private fun hookViewOnceMessageStateModels() {
        listOf(
            "X.1Jh",
            "X.C27821Jh",
            "X.1Jk",
            "X.C27851Jk",
            "X.1Jn",
            "X.C27881Jn",
            "X.1Js",
            "X.C27931Js"
        ).forEach { className ->
            val cls = appClassLoader.findClassOrNull(className) ?: return@forEach
            hookViewOnceStateModelClass(cls, className)
        }
    }

    private fun hookViewOnceStateModelsByShape() {
        Thread(
            {
                runCatching {
                    val sourceDir = androidContext.applicationInfo?.sourceDir ?: return@runCatching
                    val dexFileClass = Class.forName("dalvik.system.DexFile")
                    val dexFile = dexFileClass.getConstructor(String::class.java).newInstance(sourceDir)
                    val entries = dexFileClass.getMethod("entries").invoke(dexFile) as? java.util.Enumeration<*>
                        ?: return@runCatching
                    var scanned = 0
                    var hooked = 0
                    while (entries.hasMoreElements()) {
                        val className = entries.nextElement()?.toString() ?: continue
                        if (!className.startsWith("X.") && !className.startsWith("com.whatsapp.")) continue
                        if (hookedViewOnceStateModelClasses.contains(className)) continue
                        val cls = appClassLoader.findClassOrNull(className) ?: continue
                        scanned++
                        if (!cls.hasViewOnceStateShape()) continue
                        if (hookViewOnceStateModelClass(cls, "shape:$className")) hooked++
                    }
                    runCatching { dexFileClass.getMethod("close").invoke(dexFile) }
                    logLifecycle("Hooked WhatsApp view-once state models by shape hooked=$hooked scanned=$scanned")
                }.onFailure { logError("Failed to hook WhatsApp view-once state models by shape", it) }
            },
            "PurrfectWAViewOnceShapeHooks"
        ).apply { priority = Thread.MIN_PRIORITY }.start()
    }

    private fun hookViewOnceStateModelClass(cls: Class<*>, source: String): Boolean {
        if (!hookedViewOnceStateModelClasses.add(cls.name)) return false
        return runCatching {
            hookViewOnceStateAccessorMethods(cls, source)
            XposedBridge.hookAllMethods(
                cls,
                "CDn",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam<*>) {
                        if (!featureState.unlimitedViewOnce) return
                        rememberViewOnceMessage(param.thisObject, "$source.CDn")
                        val state = (param.args.getOrNull(0) as? Number)?.toInt() ?: return
                        if (state == 1) {
                            param.args[0] = 0
                            setViewOnceStateFieldOpenable(param.thisObject)
                            logPrivacy("unlimited_view_once_memory", "suppressed_opened_setter", source)
                        }
                    }

                    override fun afterHookedMethod(param: MethodHookParam<*>) {
                        if (!featureState.unlimitedViewOnce) return
                        setViewOnceStateFieldOpenable(param.thisObject)
                    }
                }
            )
            XposedBridge.hookAllMethods(
                cls,
                "AzF",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam<*>) {
                        if (!featureState.unlimitedViewOnce) return
                        rememberViewOnceMessage(param.thisObject, "$source.AzF")
                        setViewOnceStateFieldOpenable(param.thisObject)
                        param.result = 0
                    }

                    override fun afterHookedMethod(param: MethodHookParam<*>) {
                        if (!featureState.unlimitedViewOnce) return
                        if (param.result == 1) {
                            param.result = 0
                            logPrivacy("unlimited_view_once_memory", "coerced_opened_getter", source)
                        }
                    }
                }
            )
            logLifecycle("Hooked WhatsApp view-once message state model $source")
            true
        }.onFailure {
            hookedViewOnceStateModelClasses.remove(cls.name)
            logError("Failed to hook WhatsApp view-once message state model $source", it)
        }.getOrDefault(false)
    }

    private fun hookViewOnceStateAccessorMethods(cls: Class<*>, source: String): Boolean {
        var hooked = false
        cls.methods.forEach { method ->
            when {
                method.name == "CDn" &&
                    method.parameterTypes.size == 1 &&
                    method.parameterTypes[0] == Integer.TYPE -> {
                    hooked = hookViewOnceStateAccessorMethod(method, source) || hooked
                }
                method.name == "AzF" &&
                    method.parameterTypes.isEmpty() &&
                    method.returnType == Integer.TYPE -> {
                    hooked = hookViewOnceStateAccessorMethod(method, source) || hooked
                }
            }
        }
        if (hooked) logLifecycle("Hooked WhatsApp view-once runtime state accessors $source")
        return hooked
    }

    private fun hookViewOnceStateAccessorMethod(method: Method, source: String): Boolean {
        val signature = method.toGenericString()
        if (!hookedViewOnceStateMethods.add(signature)) return false
        return runCatching {
            XposedBridge.hookMethod(
                method,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam<*>) {
                        if (!featureState.unlimitedViewOnce) return
                        val owner = param.thisObject ?: return
                        when (method.name) {
                            "CDn" -> {
                                rememberViewOnceMessage(owner, "$source.${method.name}")
                                val state = (param.args.getOrNull(0) as? Number)?.toInt() ?: return
                                if (state != 0) {
                                    param.args[0] = 0
                                    setViewOnceStateFieldOpenable(owner)
                                    scheduleVisibleViewOnceRowReset(owner, "$source.${method.name}")
                                    logPrivacy(
                                        "unlimited_view_once_memory",
                                        "suppressed_runtime_setter",
                                        "${method.declaringClass.name}.${method.name} state=$state"
                                    )
                                }
                            }
                            "AzF" -> {
                                rememberViewOnceMessage(owner, "$source.${method.name}")
                                setViewOnceStateFieldOpenable(owner)
                                param.result = 0
                            }
                        }
                    }

                    override fun afterHookedMethod(param: MethodHookParam<*>) {
                        if (!featureState.unlimitedViewOnce) return
                        val owner = param.thisObject ?: return
                        setViewOnceStateFieldOpenable(owner)
                        if (method.name == "AzF" && (param.result as? Number)?.toInt() != 0) {
                            param.result = 0
                            logPrivacy(
                                "unlimited_view_once_memory",
                                "coerced_runtime_getter",
                                "${method.declaringClass.name}.${method.name}"
                            )
                        }
                    }
                }
            )
            true
        }.onFailure {
            hookedViewOnceStateMethods.remove(signature)
            logError("Failed to hook WhatsApp view-once state accessor $signature from $source", it)
        }.getOrDefault(false)
    }

    private fun hookViewOnceOpenMarker() {
        listOf("X.1pM", "X.C41321pM").forEach { className ->
            val cls = appClassLoader.findClassOrNull(className) ?: return@forEach
            runCatching {
                XposedBridge.hookAllMethods(
                    cls,
                    "A01",
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam<*>) {
                            if (!featureState.unlimitedViewOnce) return
                            forceViewOnceMessageStateOpenable(param.args.firstOrNull(), "$className.A01")
                            param.result = null
                            logPrivacy(
                                "unlimited_view_once_memory",
                                "suppressed_viewer_open_marker",
                                className
                            )
                        }
                    }
                )
                XposedBridge.hookAllMethods(
                    cls,
                    "A00",
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam<*>) {
                            if (!featureState.unlimitedViewOnce) return
                            val message = param.args.firstOrNull()
                            if (!isViewOnceStateModel(message)) return
                            forceViewOnceMessageStateOpenable(message, "$className.A00")
                            param.result = null
                            logPrivacy(
                                "unlimited_view_once_memory",
                                "suppressed_message_store_open_marker",
                                className
                            )
                        }
                    }
                )
                logLifecycle("Hooked WhatsApp view-once open marker $className")
            }.onFailure { logError("Failed to hook WhatsApp view-once open marker $className", it) }
        }
    }

    private fun hookViewOnceDelayedOpenMarkers() {
        listOf(
            "X.9yQ",
            "X.RunnableC225459yQ",
            "X.3Sr",
            "X.RunnableC76513Sr"
        ).forEach { className ->
            val cls = appClassLoader.findClassOrNull(className) ?: return@forEach
            runCatching {
                XposedBridge.hookAllMethods(
                    cls,
                    "run",
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam<*>) {
                            if (!featureState.unlimitedViewOnce) return
                            val runnable = param.thisObject ?: return
                            val messages = runnable.allFieldValues()
                                .filter { isViewOnceCandidate(it) || isViewOnceStateModel(it) }
                            if (messages.isEmpty()) return
                            val caseId = (runnable.fieldValue("\$t") as? Number)?.toInt()
                                ?: runnable.firstIntFieldValue()
                            val owner = runnable.fieldValue("A00")
                            val looksLikeConversationRow = owner?.javaClass?.methods?.any {
                                it.name == "getFMessage" && it.parameterTypes.isEmpty()
                            } == true
                            val looksLikeStoreMarker = owner?.safeClassName()?.let {
                                it == "X.C41321pM" || it == "X.1pM"
                            } == true
                            if (
                                caseId !in VIEW_ONCE_DELAYED_OPEN_MARKER_CASES &&
                                caseId != VIEW_ONCE_STORE_MARKER_CASE &&
                                !looksLikeConversationRow &&
                                !looksLikeStoreMarker
                            ) return
                            messages.forEach { forceViewOnceMessageStateOpenable(it, "$className.run/$caseId") }
                            param.result = null
                            logPrivacy(
                                "unlimited_view_once_memory",
                                "suppressed_delayed_open_marker",
                                "$className case=${caseId ?: -1} messages=${messages.size}"
                            )
                        }
                    }
                )
                logLifecycle("Hooked WhatsApp view-once delayed open marker $className")
            }.onFailure { logError("Failed to hook WhatsApp view-once delayed open marker $className", it) }
        }
    }

    private fun hookViewOnceMessageStoreMarker() {
        listOf("X.0og", "X.C16400og").forEach { className ->
            val cls = appClassLoader.findClassOrNull(className) ?: return@forEach
            runCatching {
                XposedBridge.hookAllMethods(
                    cls,
                    "A0U",
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam<*>) {
                            if (!featureState.unlimitedViewOnce) return
                            val message = param.args.getOrNull(0)
                            val state = param.args.getOrNull(1) as? Int ?: return
                            if (state != 1 || !isViewOnceCandidate(message)) return
                            forceViewOnceMessageStateOpenable(message, "$className.A0U")
                            param.result = null
                            logPrivacy(
                                "unlimited_view_once_memory",
                                "suppressed_message_store_state_update",
                                className
                            )
                        }
                    }
                )
                XposedBridge.hookAllMethods(
                    cls,
                    "A05",
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam<*>) {
                            if (!featureState.unlimitedViewOnce) return
                            val message = param.args.getOrNull(1)
                            val state = (param.args.getOrNull(2) as? Number)?.toInt() ?: return
                            if (state != 1 || !isViewOnceCandidate(message)) return
                            forceViewOnceMessageStateOpenable(message, "$className.A05")
                            param.result = null
                            logPrivacy(
                                "unlimited_view_once_memory",
                                "suppressed_core_store_open_worker",
                                className
                            )
                        }
                    }
                )
                listOf("A0R", "A0S", "A0V", "A0W").forEach { methodName ->
                    hookAllIfPresent(cls, methodName) { param ->
                        if (!featureState.unlimitedViewOnce) return@hookAllIfPresent
                        val message = param.args.firstOrNull()
                        val state = param.args.firstOrNull { it is Number } as? Number ?: return@hookAllIfPresent
                        if (state.toInt() != 1 || !isViewOnceCandidate(message)) return@hookAllIfPresent
                        forceViewOnceMessageStateOpenable(message, "$className.$methodName")
                        param.result = null
                        logPrivacy(
                            "unlimited_view_once_memory",
                            "suppressed_structural_store_state_update",
                            "$className.$methodName"
                        )
                    }
                }
                logLifecycle("Hooked WhatsApp view-once message store marker $className")
            }.onFailure { logError("Failed to hook WhatsApp view-once message store marker $className", it) }
        }
        listOf("X.1Jg", "X.InterfaceC27811Jg").forEach { className ->
            val cls = appClassLoader.findClassOrNull(className) ?: return@forEach
            runCatching {
                XposedBridge.hookAllMethods(
                    cls,
                    "CDn",
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam<*>) {
                            if (!featureState.unlimitedViewOnce) return
                            rememberViewOnceMessage(param.thisObject, "$className.CDn")
                            if ((param.args.getOrNull(0) as? Number)?.toInt() == 1) {
                                param.args[0] = 0
                                setViewOnceStateFieldOpenable(param.thisObject)
                            }
                        }

                        override fun afterHookedMethod(param: MethodHookParam<*>) {
                            if (!featureState.unlimitedViewOnce) return
                            setViewOnceStateFieldOpenable(param.thisObject)
                        }
                    }
                )
                XposedBridge.hookAllMethods(
                    cls,
                    "AzF",
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam<*>) {
                            if (!featureState.unlimitedViewOnce) return
                            rememberViewOnceMessage(param.thisObject, "$className.AzF")
                            setViewOnceStateFieldOpenable(param.thisObject)
                            param.result = 0
                        }

                        override fun afterHookedMethod(param: MethodHookParam<*>) {
                            if (featureState.unlimitedViewOnce && param.result == 1) {
                                param.result = 0
                            }
                        }
                    }
                )
                logLifecycle("Hooked WhatsApp view-once interface marker $className")
            }.onFailure { logError("Failed to hook WhatsApp view-once interface marker $className", it) }
        }
    }

    private fun hookViewOnceCoreStoreWorkers() {
        listOf("X.3Rm", "X.RunnableC76203Rm").forEach { className ->
            val cls = appClassLoader.findClassOrNull(className) ?: return@forEach
            runCatching {
                XposedBridge.hookAllMethods(
                    cls,
                    "run",
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam<*>) {
                            if (!featureState.unlimitedViewOnce) return
                            val runnable = param.thisObject ?: return
                            val caseId = (runnable.fieldValue("\$t") as? Number)?.toInt()
                                ?: runnable.firstIntFieldValue()
                            val state = (runnable.fieldValue("A00") as? Number)?.toInt()
                            val message = runnable.fieldValue("A02")
                            if (caseId != 6 || state != 1 || !isViewOnceCandidate(message)) return
                            forceViewOnceMessageStateOpenable(message, "$className.run")
                            param.result = null
                            logPrivacy(
                                "unlimited_view_once_memory",
                                "suppressed_core_store_open_runnable",
                                className
                            )
                        }
                    }
                )
                logLifecycle("Hooked WhatsApp view-once core store worker $className")
            }.onFailure { logError("Failed to hook WhatsApp view-once core store worker $className", it) }
        }
    }

    private fun hookViewOnceConversationRows() {
        listOf("X.854", "X.AnonymousClass854").forEach { className ->
            val cls = appClassLoader.findClassOrNull(className) ?: return@forEach
            runCatching {
                listOf(
                    "A05",
                    "A2N",
                    "A2h",
                    "getDateView",
                    "getDateWrapper",
                    "getViewStateDescription",
                    "getFMessage"
                ).forEach { methodName ->
                    hookAllIfPresent(cls, methodName) { param ->
                        if (!featureState.unlimitedViewOnce) return@hookAllIfPresent
                        resetViewOnceRowMessage(param.thisObject, "$className.$methodName")
                    }
                }
                logLifecycle("Hooked WhatsApp view-once photo row $className")
            }.onFailure { logError("Failed to hook WhatsApp view-once photo row $className", it) }
        }

        listOf("X.857", "X.AnonymousClass857").forEach { className ->
            val cls = appClassLoader.findClassOrNull(className) ?: return@forEach
            runCatching {
                listOf(
                    "A3M",
                    "A3N",
                    "A3O",
                    "A3P",
                    "getMediaTypeDescriptionString",
                    "getMediaTypeString",
                    "getFMessage"
                ).forEach { methodName ->
                    hookAllIfPresent(cls, methodName) { param ->
                        if (!featureState.unlimitedViewOnce) return@hookAllIfPresent
                        resetViewOnceRowMessage(param.thisObject, "$className.$methodName")
                    }
                }
                hookAllIfPresent(cls, "A06") { param ->
                    if (!featureState.unlimitedViewOnce) return@hookAllIfPresent
                    resetViewOnceRowMessage(param.args.firstOrNull(), "$className.A06")
                    param.result = 0
                }
                logLifecycle("Hooked WhatsApp generic view-once row $className")
            }.onFailure { logError("Failed to hook WhatsApp generic view-once row $className", it) }
        }

        listOf(
            "X.82n",
            "X.C1802182n",
            "X.82m",
            "X.C1802082m"
        ).forEach { className ->
            val cls = appClassLoader.findClassOrNull(className) ?: return@forEach
            runCatching {
                listOf(
                    "A2N",
                    "A2L",
                    "A3M",
                    "A3N",
                    "A3O",
                    "A3P",
                    "getDateView",
                    "getDateWrapper",
                    "getFMessage"
                ).forEach { methodName ->
                    hookAllIfPresent(cls, methodName) { param ->
                        if (!featureState.unlimitedViewOnce) return@hookAllIfPresent
                        resetViewOnceRowMessage(param.thisObject, "$className.$methodName")
                    }
                }
                logLifecycle("Hooked WhatsApp concrete view-once row $className")
            }.onFailure { logError("Failed to hook WhatsApp concrete view-once row $className", it) }
        }
    }

    private fun hookViewOnceViewerLifecycle() {
        listOf(
            "com.whatsapp.viewonce.ui.messaging.BaseViewOnceMessageViewerFragment",
            "com.whatsapp.viewonce.ui.messaging.ViewOnceAudioFragment",
            "com.whatsapp.viewonce.ui.messaging.ViewOnceTextFragment"
        ).forEach { className ->
            val cls = appClassLoader.findClassOrNull(className) ?: return@forEach
            runCatching {
                hookAllIfPresent(cls, "A1y", before = false) { param ->
                    if (!featureState.unlimitedViewOnce) return@hookAllIfPresent
                    normalizeViewOnceViewerObject(param.thisObject, "$className.A1y")
                }
                hookAllIfPresent(cls, "A20") { param ->
                    if (!featureState.unlimitedViewOnce) return@hookAllIfPresent
                    normalizeViewOnceViewerObject(param.thisObject, "$className.A20")
                }
                hookAllIfPresent(cls, "A1g") {
                    if (!featureState.unlimitedViewOnce) return@hookAllIfPresent
                    normalizeViewOnceViewerObject(it.thisObject, "$className.A1g")
                }
                logLifecycle("Hooked WhatsApp view-once viewer fragment $className")
            }.onFailure { logError("Failed to hook WhatsApp view-once viewer fragment $className", it) }
        }

        val activityCls = appClassLoader.findClassOrNull("com.whatsapp.viewonce.ui.messaging.ViewOnceViewerActivity")
            ?: return
        runCatching {
            listOf("onCreate", "onPrepareOptionsMenu", "onOptionsItemSelected", "onPause", "onStop", "finish").forEach { methodName ->
                hookAllIfPresent(activityCls, methodName) { param ->
                    if (!featureState.unlimitedViewOnce) return@hookAllIfPresent
                    normalizeViewOnceViewerObject(param.thisObject, "ViewOnceViewerActivity.$methodName")
                }
            }
            logLifecycle("Hooked WhatsApp view-once viewer activity")
        }.onFailure { logError("Failed to hook WhatsApp view-once viewer activity", it) }
    }

    private fun hookConversationRowMessageAccess() {
        listOf(
            "X.7bU",
            "X.AbstractC165647bU",
            "X.7bz",
            "X.AbstractC165957bz",
            "X.7ce",
            "X.AbstractC166367ce"
        ).forEach { className ->
            val cls = appClassLoader.findClassOrNull(className) ?: return@forEach
            runCatching {
                hookAllIfPresent(cls, "getFMessage", before = false) { param ->
                    if (!featureState.unlimitedViewOnce) return@hookAllIfPresent
                    normalizeViewOnceMessageObject(param.result, "$className.getFMessage")
                }
                hookAllIfPresent(cls, "setFMessage") { param ->
                    if (!featureState.unlimitedViewOnce) return@hookAllIfPresent
                    normalizeViewOnceMessageObject(param.args.firstOrNull(), "$className.setFMessage")
                }
                logLifecycle("Hooked WhatsApp conversation row message access $className")
            }.onFailure { logError("Failed to hook WhatsApp conversation row message access $className", it) }
        }
    }

    private fun forceViewOnceMessageStateOpenable(message: Any?, source: String, scheduleScan: Boolean = true) {
        if (message == null) return
        runCatching {
            rememberViewOnceMessage(message, source)
            hookViewOnceStateAccessorMethods(message.javaClass, "runtime:$source")
            val method = message.javaClass.methods.firstOrNull {
                it.name == "CDn" &&
                    it.parameterTypes.size == 1 &&
                    it.parameterTypes[0] == Integer.TYPE
            } ?: return
            setViewOnceStateFieldOpenable(message)
            method.invoke(message, 0)
            setViewOnceStateFieldOpenable(message)
            logPrivacy("unlimited_view_once_memory", "reset_after_store_read", source)
            if (scheduleScan) scheduleVisibleViewOnceRowReset(message, source)
        }
    }

    private fun setViewOnceStateFieldOpenable(message: Any?): Boolean {
        if (message == null) return false
        var cls: Class<*>? = message.javaClass
        while (cls != null) {
            val field = runCatching { cls.getDeclaredField("A00") }.getOrNull()
            if (field != null && (field.type == Integer.TYPE || field.type == Int::class.javaObjectType)) {
                return runCatching {
                    field.isAccessible = true
                    val oldState = (field.get(message) as? Number)?.toInt()
                    if (oldState != 0) {
                        field.set(message, 0)
                        true
                    } else {
                        false
                    }
                }.getOrDefault(false)
            }
            cls = cls.superclass
        }
        return false
    }

    private fun resetViewOnceRowMessage(row: Any?, source: String) {
        if (row == null) return
        val message = runCatching {
            row.javaClass.methods.firstOrNull {
                it.name == "getFMessage" && it.parameterTypes.isEmpty()
            }?.invoke(row)
        }.getOrNull() ?: row
        normalizeViewOnceMessageObject(message, source)
    }

    private fun normalizeViewOnceMessageObject(message: Any?, source: String) {
        if (!isViewOnceMessage(message)) return
        rememberViewOnceMessage(message, source)
        setViewOnceStateFieldOpenable(message)
        logPrivacy("unlimited_view_once_memory", "normalized_message_object", source)
    }

    private fun normalizeViewOnceViewerObject(owner: Any?, source: String) {
        if (owner == null) return
        normalizeViewOnceMessageObject(owner.fieldValue("A01"), source)
        normalizeViewOnceMessageObject(owner.fieldValue("A0P"), source)
        owner.javaClass.declaredFields
            .asSequence()
            .filter { field ->
                field.name.length <= 3 && !field.type.isPrimitive && !field.type.name.startsWith("android.")
            }
            .take(12)
            .forEach { field ->
                runCatching {
                    field.isAccessible = true
                    normalizeViewOnceMessageObject(field.get(owner), "$source.${field.name}")
                }
            }
        scheduleVisibleViewOnceRowReset(null, source)
    }

    private fun rememberViewOnceMessage(message: Any?, source: String) {
        if (message == null) return
        if (isViewOnceStateModel(message)) hookViewOnceStateAccessorMethods(message.javaClass, "runtime:$source")
        val key = message.fieldValue("A0i")
        val messageId = key?.fieldValue("A01")?.toString()?.takeIf { it.isNotBlank() }
        val rowId = (message.fieldValue("A0j") as? Number)?.toLong()
        if (messageId != null) viewOnceMessageIds.add(messageId)
        if (rowId != null && rowId > 0L) viewOnceMessageRowIds.add(rowId)
        if (messageId != null || rowId != null) {
            logPrivacy(
                "unlimited_view_once_identity",
                "remembered",
                "source=$source id=${messageId?.take(12) ?: "-"} row=${rowId ?: -1}"
            )
        }
    }

    private fun scheduleVisibleViewOnceRowReset(message: Any?, source: String) {
        if (!featureState.unlimitedViewOnce) return
        val markerKeys = if (message != null && isViewOnceCandidate(message)) {
            rememberViewOnceMessage(message, source)
            viewOnceMarkerKeys(message)
        } else {
            emptyList()
        }.toSet()

        if (markerKeys.isEmpty() && viewOnceMessageIds.isEmpty() && viewOnceMessageRowIds.isEmpty()) return

        val task = Runnable {
            runSafe("view-once visible row reset") {
                scanVisibleViewOnceRows(markerKeys, source)
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            task.run()
        } else {
            uiHandler.post(task)
        }
        VIEW_ONCE_VISIBLE_ROW_SCAN_DELAYS_MS.forEach { delay ->
            uiHandler.postDelayed(task, delay)
        }
    }

    private fun scanVisibleViewOnceRows(markerKeys: Set<String>, source: String) {
        val roots = synchronized(activeDecorViews) { activeDecorViews.toList() }
        if (roots.isEmpty()) {
            logPrivacy("unlimited_view_once_ui", "visible_scan_miss", "$source roots=0")
            return
        }

        val seen = Collections.newSetFromMap(IdentityHashMap<View, Boolean>())
        val stats = IntArray(3)
        roots.forEach { root ->
            root.resetViewOnceRowsInTree(markerKeys, source, seen, stats)
        }
        if (stats[1] > 0) {
            logPrivacy(
                "unlimited_view_once_ui",
                "visible_scan_reset",
                "$source rows=${stats[1]} changed=${stats[2]} views=${stats[0]} roots=${roots.size}"
            )
        } else {
            logPrivacy(
                "unlimited_view_once_ui",
                "visible_scan_miss",
                "$source keys=${markerKeys.size} views=${stats[0]} roots=${roots.size}"
            )
        }
    }

    private fun View.resetViewOnceRowsInTree(
        markerKeys: Set<String>,
        source: String,
        seen: MutableSet<View>,
        stats: IntArray
    ) {
        if (!seen.add(this)) return
        stats[0]++

        val message = visibleViewOnceRowMessage()
        if (message != null && visibleViewOnceRowMatches(message, markerKeys)) {
            if (resetVisibleViewOnceRow(this, message, "$source.scan")) stats[2]++
            stats[1]++
        }

        if (this !is ViewGroup) return
        for (index in 0 until childCount) {
            getChildAt(index)?.resetViewOnceRowsInTree(markerKeys, source, seen, stats)
        }
    }

    private fun View.visibleViewOnceRowMessage(): Any? {
        val hasMessageAccessor = viewOnceVisibleRowAccessorCache.getOrPut(javaClass) {
            javaClass.methods.any { method ->
                method.name == "getFMessage" && method.parameterTypes.isEmpty()
            }
        }
        if (!hasMessageAccessor) return null
        return invokeNoArg("getFMessage")?.takeIf { isViewOnceCandidate(it) || isViewOnceStateModel(it) }
    }

    private fun visibleViewOnceRowMatches(message: Any, markerKeys: Set<String>): Boolean {
        if (!isViewOnceCandidate(message) && !isViewOnceStateModel(message)) return false
        if (markerKeys.isNotEmpty() && viewOnceMarkerKeys(message).any(markerKeys::contains)) return true
        val rowId = (message.fieldValue("A0j") as? Number)?.toLong()
        if (rowId != null && rowId > 0L && rowId in viewOnceMessageRowIds) return true
        val id = message.fieldValue("A0i")?.fieldValue("A01")?.toString()
        if (id != null && id in viewOnceMessageIds) return true
        return markerKeys.isEmpty()
    }

    private fun resetVisibleViewOnceRow(row: Any, message: Any, source: String): Boolean {
        val oldState = message.viewOnceRawState()
        forceViewOnceMessageStateOpenable(message, source, scheduleScan = false)
        val changed = oldState != null && oldState != 0
        val directRefresh = row.invokeNoArgIfPresent("A05") || row.invokeNoArgIfPresent("A3N")
        setViewOnceStateFieldOpenable(message)
        val rebound = if (directRefresh) false else row.invokeMessageBindIfPresent(message)
        setViewOnceStateFieldOpenable(message)
        val finalDirectRefresh = row.invokeNoArgIfPresent("A05") || row.invokeNoArgIfPresent("A3N")
        setViewOnceStateFieldOpenable(message)
        val refreshed = directRefresh || rebound || finalDirectRefresh
        logPrivacy(
            "unlimited_view_once_ui",
            if (changed) "row_reset" else "row_confirmed_openable",
            "$source old=${oldState ?: -1} refreshed=$refreshed ${describeMessageIdentity(message)}"
        )
        return changed
    }

    private fun viewOnceMarkerKeys(message: Any): List<String> {
        val key = message.fieldValue("A0i")
        val id = key?.fieldValue("A01")?.toString()?.takeIf { it.isNotBlank() }
        val rowId = (message.fieldValue("A0j") as? Number)?.toLong()
        return viewOnceMarkerKeys(id, rowId)
    }

    private fun viewOnceMarkerKeys(id: String?, rowId: Long?): List<String> {
        val markers = ArrayList<String>(2)
        if (!id.isNullOrBlank()) markers += "id:$id"
        if (rowId != null && rowId > 0L) markers += "row:$rowId"
        return markers
    }

    private fun Any.viewOnceRawState(): Int? {
        var cls: Class<*>? = javaClass
        while (cls != null) {
            val field = runCatching { cls.getDeclaredField("A00") }.getOrNull()
            if (field != null && (field.type == Integer.TYPE || field.type == Int::class.javaObjectType)) {
                return runCatching {
                    field.isAccessible = true
                    (field.get(this) as? Number)?.toInt()
                }.getOrNull()
            }
            cls = cls.superclass
        }
        return null
    }

    private fun isViewOnceMessage(message: Any?): Boolean {
        if (message == null) return false
        if (isViewOnceStateModel(message)) return true
        val mediaType = runCatching {
            message.javaClass.methods.firstOrNull {
                it.name == "AuC" && it.parameterTypes.isEmpty()
            }?.invoke(message) as? Int
        }.getOrNull() ?: return false
        return mediaType in VIEW_ONCE_MEDIA_TYPES &&
            message.javaClass.methods.any { it.name == "AzF" && it.parameterTypes.isEmpty() } &&
            message.javaClass.methods.any {
                it.name == "CDn" &&
                    it.parameterTypes.size == 1 &&
                    it.parameterTypes[0] == Integer.TYPE
            }
    }

    private fun isViewOnceCandidate(message: Any?): Boolean {
        if (message == null) return false
        if (isViewOnceStateModel(message)) return true
        val rowId = (message.fieldValue("A0j") as? Number)?.toLong()
        if (rowId != null && rowId > 0L && rowId in viewOnceMessageRowIds) return true
        val mediaType = runCatching {
            message.javaClass.methods.firstOrNull {
                it.name == "AuC" && it.parameterTypes.isEmpty()
            }?.invoke(message) as? Int
        }.getOrNull()
        return mediaType in VIEW_ONCE_MEDIA_TYPES
    }

    private fun isViewOnceStateModel(message: Any?): Boolean {
        if (message == null) return false
        val className = message.javaClass.name
        if (className in VIEW_ONCE_STATE_MODEL_CLASS_NAMES) return true
        return message.javaClass.methods.any { it.name == "AzF" && it.parameterTypes.isEmpty() } &&
            message.javaClass.methods.any {
                it.name == "CDn" &&
                    it.parameterTypes.size == 1 &&
                    it.parameterTypes[0] == Integer.TYPE
            }
    }

    private fun hookConcreteReadReceipts() {
        val cls = appClassLoader.findClassOrNull("com.whatsapp.messaging.receipts.jobqueue.job.SendReadReceiptJob")
            ?: return
        runCatching {
            XposedBridge.hookAllMethods(
                cls,
                "A0F",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam<*>) {
                        runSafe("SendReadReceiptJob.A0F") {
                            val job = param.thisObject ?: return@runSafe
                            val receiptClass = job.fieldValue("receiptClass")?.toString().orEmpty()
                            val jid = job.fieldValue("jid")?.toString().orEmpty()
                            if (featureState.hideStatusView && isStatusReceiptTarget(jid)) {
                                param.result = null
                                logPrivacy(
                                    "hide_status_view_job",
                                    "suppressed",
                                    "receiptClass=$receiptClass jid=${redactJid(jid)}"
                                )
                                return@runSafe
                            }
                            if (shouldSuppressReadReceipt(receiptClass, jid)) {
                                param.result = null
                                logPrivacy(
                                    "hide_read_receipt_job",
                                    "suppressed",
                                    "receiptClass=$receiptClass jid=${redactJid(jid)}"
                                )
                            }
                        }
                    }
                }
            )
            logLifecycle("Hooked WhatsApp read receipt job")
        }.onFailure { logError("Failed to hook read receipt job", it) }
    }

    private fun hookPlayedReceiptBatcher() {
        listOf("X.C13610k2", "X.0k2").forEach { className ->
            val cls = appClassLoader.findClassOrNull(className) ?: return@forEach
            runCatching {
                XposedBridge.hookAllMethods(
                    cls,
                    "A06",
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam<*>) {
                            runSafe("$className.A06") {
                                if (!featureState.unlimitedViewOnce) return@runSafe
                                val messages = param.args.firstOrNull() as? Set<*> ?: return@runSafe
                                val kept = LinkedHashSet<Any?>()
                                var removed = 0
                                messages.forEach { message ->
                                    if (isViewOnceMessage(message)) {
                                        rememberViewOnceMessage(message, "$className.A06")
                                        forceViewOnceMessageStateOpenable(message, "$className.A06")
                                        removed++
                                    } else {
                                        kept.add(message)
                                    }
                                }
                                if (removed == 0) return@runSafe
                                if (kept.isEmpty()) {
                                    param.result = null
                                } else {
                                    param.args[0] = kept
                                }
                                logPrivacy(
                                    "unlimited_view_once_played_receipt",
                                    "filtered_batch",
                                    "removed=$removed kept=${kept.size}"
                                )
                            }
                        }
                    }
                )
                logLifecycle("Hooked WhatsApp played receipt batcher $className")
            }.onFailure { logError("Failed to hook WhatsApp played receipt batcher $className", it) }
        }
    }

    private fun hookConcretePlayedReceipts() {
        val cls = appClassLoader.findClassOrNull("com.whatsapp.messaging.receipts.jobqueue.job.SendPlayedReceiptJobV2")
            ?: return
        runCatching {
            XposedBridge.hookAllMethods(
                cls,
                "A0F",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam<*>) {
                        runSafe("SendPlayedReceiptJobV2.A0F") {
                            val job = param.thisObject ?: return@runSafe
                            val viewOnceDecision = viewOncePlayedReceiptDecision(job)
                            if (viewOnceDecision != null) {
                                param.result = null
                                logPrivacy(viewOnceDecision.type, "suppressed_job", viewOnceDecision.detail)
                                return@runSafe
                            }
                            if (!featureState.hideAudioSeen) return@runSafe
                            val jid = job.fieldValue("toRawJid")?.toString().orEmpty()
                            param.result = null
                            logPrivacy("hide_audio_seen_job", "suppressed", "jid=${redactJid(jid)}")
                        }
                    }
                }
            )
            logLifecycle("Hooked WhatsApp played receipt job")
        }.onFailure { logError("Failed to hook played receipt job", it) }
    }

    private fun hookConcreteChatStateSender() {
        val cls = appClassLoader.findClassOrNull("X.C165927bw")
            ?: appClassLoader.findClassOrNull("X.7bw")
            ?: return
        runCatching {
            XposedBridge.hookAllMethods(
                cls,
                "A01",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam<*>) {
                        runSafe("HandleMeComposing.A01") {
                            val mediaArg = param.args.getOrNull(2) as? Int ?: return@runSafe
                            val isAudio = mediaArg == 1
                            val shouldSuppress = if (isAudio) {
                                featureState.hideRecordingAudio
                            } else {
                                featureState.hideTypingIndicators
                            }
                            if (!shouldSuppress) return@runSafe

                            param.result = null
                            logPrivacy(
                                if (isAudio) "hide_recording_audio_chatstate" else "hide_typing_chatstate",
                                "suppressed",
                                "media=$mediaArg"
                            )
                        }
                    }
                }
            )
            logLifecycle("Hooked WhatsApp composing or recording sender")
        }.onFailure { logError("Failed to hook composing sender", it) }
    }

    private fun hookMessageClientProtocolSends() {
        val cls = appClassLoader.findClassOrNull("X.C05010My")
            ?: appClassLoader.findClassOrNull("X.0My")
            ?: return
        runCatching {
            val methods = (cls.declaredMethods.asSequence() + cls.methods.asSequence())
                .distinctBy { method ->
                    method.name + method.parameterTypes.joinToString(",", prefix = "(") { it.name } + "):${method.returnType.name}"
                }
                .filter { method -> method.declaringClass != Any::class.java }
                .filterNot { method -> Modifier.isAbstract(method.modifiers) || Modifier.isNative(method.modifiers) }
                .toList()
            val candidates = methods.filter { it.isMessageClientSendCandidate() }
            val targets = candidates.ifEmpty {
                methods.filter { method ->
                    method.parameterTypes.isNotEmpty() ||
                        method.returnType == java.lang.Boolean.TYPE ||
                        method.returnType.name.contains("FutureC23163AMr")
                }
            }
            var hooked = 0
            targets.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(
                    method,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam<*>) {
                            runSafe("MessageClient.${method.name}") {
                                val decision = suppressionForOutgoingArgs(param.args) ?: return@runSafe
                                suppressReturn(param, decision, "suppressed_message_client_${method.name}")
                            }
                        }
                    }
                )
                hooked++
            }
            logLifecycle(
                "Hooked WhatsApp protocol send surface methods=$hooked candidates=${candidates.size} total=${methods.size}"
            )
        }.onFailure { logError("Failed to hook protocol send surface", it) }
    }

    private fun hookReadReceiptManager() {
        val cls = appClassLoader.findClassOrNull("X.C16570ox")
            ?: appClassLoader.findClassOrNull("X.0ox")
            ?: return
        runCatching {
            hookAllIfPresent(cls, "A0E") { param ->
                if (!shouldSuppressReadManagerSideEffect(param.args.firstOrNull())) return@hookAllIfPresent
                param.result = null
                logPrivacy("hide_read_receipt_manager", "suppressed_prepare", describeMessageIdentity(param.args.firstOrNull()))
            }
            hookAllIfPresent(cls, "A0F") { param ->
                if (!shouldSuppressReadManagerSideEffect(param.args.firstOrNull())) return@hookAllIfPresent
                param.result = null
                logPrivacy("hide_read_receipt_manager", "suppressed_send", describeMessageIdentity(param.args.firstOrNull()))
            }
            hookAllIfPresent(cls, "A0R") { param ->
                if (!shouldSuppressReadManagerSideEffect(param.args.firstOrNull())) return@hookAllIfPresent
                param.result = true
                logPrivacy("hide_read_receipt_manager", "suppressed_retry", describeMessageIdentity(param.args.firstOrNull()))
            }
            hookAllIfPresent(cls, "A0P") { param ->
                if (!featureState.unlimitedViewOnce) return@hookAllIfPresent
                val messages = param.args.firstOrNull() as? Collection<*> ?: return@hookAllIfPresent
                if (!messages.any { isViewOnceCandidate(it) }) return@hookAllIfPresent
                if (messages.all { isViewOnceCandidate(it) }) {
                    param.result = null
                    logPrivacy("hide_read_receipt_manager", "suppressed_collection", "size=${messages.size}")
                }
            }
            hookAllIfPresent(cls, "A0Q") { param ->
                if (!featureState.hideBlueTicks && !featureState.unlimitedViewOnce) {
                    return@hookAllIfPresent
                }
                param.result = null
                logPrivacy("hide_read_receipt_manager", "suppressed_grouped_receipts", describeOutgoingArgs(param.args))
            }
            hookAllIfPresent(cls, "A06") { param ->
                val decision = suppressionForOutgoingArgs(param.args) ?: return@hookAllIfPresent
                param.result = null
                logPrivacy(decision.type, "suppressed_read_manager_send", decision.detail)
            }
            listOf("A0L", "A0M", "A0N", "A0O").forEach { methodName ->
                hookAllIfPresent(cls, methodName) { param ->
                    if (!featureState.hideDelivered) return@hookAllIfPresent
                    param.result = null
                    logPrivacy("hide_delivered_manager", "suppressed_$methodName", describeOutgoingArgs(param.args))
                }
            }
            logLifecycle("Hooked WhatsApp read and delivery receipt manager")
        }.onFailure { logError("Failed to hook read and delivery receipt manager", it) }
    }

    private fun hookConnectionTransport() {
        val cls = appClassLoader.findClassOrNull("X.HandlerC242915e")
            ?: appClassLoader.findClassOrNull("X.15e")
            ?: return
        runCatching {
            hookAllIfPresent(cls, "C7c") { param ->
                val decision = suppressionForOutgoingArgs(param.args) ?: return@hookAllIfPresent
                param.result = null
                logPrivacy(decision.type, "suppressed_connection_queue", decision.detail)
            }
            hookAllIfPresent(cls, "handleMessage") { param ->
                val message = param.args.firstOrNull() as? Message ?: return@hookAllIfPresent
                if (message.what != CONNECTION_SEND_WHAT) return@hookAllIfPresent
                val decision = suppressionForOutgoingArgs(arrayOf<Any?>(message)) ?: return@hookAllIfPresent
                param.result = null
                logPrivacy(decision.type, "suppressed_connection_handle", decision.detail)
            }
            logLifecycle("Hooked WhatsApp connection transport send surface")
        }.onFailure { logError("Failed to hook connection transport", it) }
    }

    private fun hookWriterTransport() {
        listOf("X.HandlerC18310rp", "X.0rp").forEach { className ->
            val cls = appClassLoader.findClassOrNull(className) ?: return@forEach
            runCatching {
                hookAllIfPresent(cls, "C7c") { param ->
                    val decision = suppressionForOutgoingArgs(param.args) ?: return@hookAllIfPresent
                    param.result = null
                    logPrivacy(decision.type, "suppressed_writer_queue", decision.detail)
                }
                hookAllIfPresent(cls, "handleMessage") { param ->
                    val message = param.args.firstOrNull() as? Message ?: return@hookAllIfPresent
                    if (message.what !in NETWORK_MESSAGE_WHATS) return@hookAllIfPresent
                    val decision = suppressionForOutgoingArgs(arrayOf<Any?>(message)) ?: return@hookAllIfPresent
                    param.result = null
                    logPrivacy(decision.type, "suppressed_writer_handle", decision.detail)
                }
                logLifecycle("Hooked WhatsApp writer transport send surface $className")
            }.onFailure { logError("Failed to hook WhatsApp writer transport $className", it) }
        }
    }

    private fun hookAndroidMessagePrivacySurface() {
        runCatching {
            var hooked = 0
            XposedBridge.hookAllMethods(
                Handler::class.java,
                "sendMessageAtTime",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam<*>) {
                        runSafe("Handler.sendMessageAtTime") {
                            val message = param.args.firstOrNull() as? Message ?: return@runSafe
                            val decision = suppressionForQueuedMessage(message) ?: return@runSafe
                            param.result = true
                            logPrivacy(decision.type, "suppressed_handler_enqueue", decision.detail)
                        }
                    }
                }
            )
            hooked++
            XposedBridge.hookAllMethods(
                Handler::class.java,
                "dispatchMessage",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam<*>) {
                        runSafe("Handler.dispatchMessage") {
                            val message = param.args.firstOrNull() as? Message ?: return@runSafe
                            val decision = suppressionForQueuedMessage(message) ?: return@runSafe
                            param.result = null
                            logPrivacy(decision.type, "suppressed_handler_dispatch", decision.detail)
                        }
                    }
                }
            )
            hooked++
            val messageQueueClass = Class.forName("android.os.MessageQueue")
            messageQueueClass.declaredMethods
                .filter { method ->
                    method.name == "enqueueMessage" &&
                        method.parameterTypes.firstOrNull() == Message::class.java
                }
                .forEach { method ->
                    method.isAccessible = true
                    XposedBridge.hookMethod(
                        method,
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam<*>) {
                                runSafe("MessageQueue.enqueueMessage") {
                                    val message = param.args.firstOrNull() as? Message ?: return@runSafe
                                    val decision = suppressionForQueuedMessage(message) ?: return@runSafe
                                    param.result = true
                                    logPrivacy(decision.type, "suppressed_message_queue", decision.detail)
                                }
                            }
                        }
                    )
                    hooked++
                }
            logLifecycle("Hooked Android handler/message-queue privacy surface methods=$hooked")
        }.onFailure { logError("Failed to hook Android handler/message-queue privacy surface", it) }
    }

    private fun hookRevokeStore() {
        val cls = appClassLoader.findClassOrNull("X.C235211z")
            ?: appClassLoader.findClassOrNull("X.11z")
            ?: return
        runCatching {
            listOf("A00", "A02", "A03", "A04").forEach { methodName ->
                hookAllIfPresent(cls, methodName) { param ->
                    suppressIncomingRevoke(param, "RevokeStore.$methodName")
                }
            }
            logLifecycle("Hooked WhatsApp revoke store")
        }.onFailure { logError("Failed to hook revoke store", it) }
    }

    private fun hookRevokedMessageStore() {
        listOf("X.C3MS", "X.3MS").forEach { className ->
            val cls = appClassLoader.findClassOrNull(className) ?: return@forEach
            runCatching {
                hookAllIfPresent(cls, "A00") { param ->
                    if (!featureState.showDeletedMessages) return@hookAllIfPresent
                    param.args.firstOrNull()?.let { rememberDeletedMessage(it, "$className.A00") }
                    param.result = null
                    logPrivacy("show_deleted_messages_revoke_store", "suppressed_revoked_store", className)
                }
                logLifecycle("Hooked WhatsApp revoked-message store $className")
            }.onFailure { logError("Failed to hook WhatsApp revoked-message store $className", it) }
        }
    }

    private fun hookRevokedMessageInfoStore() {
        listOf("X.C74023Jc", "X.3Jc").forEach { className ->
            val cls = appClassLoader.findClassOrNull(className) ?: return@forEach
            runCatching {
                listOf("ANt", "B6K", "CMU").forEach { methodName ->
                    hookAllIfPresent(cls, methodName) { param ->
                        if (!featureState.showDeletedMessages) return@hookAllIfPresent
                        rememberDeletedMessage(param.args.firstOrNull(), "$className.$methodName")
                        param.result = null
                        logPrivacy("show_deleted_messages_revoke_store", "suppressed_revoked_info_$methodName", className)
                    }
                }
                logLifecycle("Hooked WhatsApp revoked-message info store $className")
            }.onFailure { logError("Failed to hook WhatsApp revoked-message info store $className", it) }
        }
    }

    private fun hookRevokedMessageClassifier() {
        listOf("X.C1GD", "X.1GD").forEach { className ->
            val cls = appClassLoader.findClassOrNull(className) ?: return@forEach
            runCatching {
                hookAllIfPresent(cls, "A14") { param ->
                    if (!featureState.showDeletedMessages) return@hookAllIfPresent
                    val message = param.args.firstOrNull() ?: return@hookAllIfPresent
                    if (message.messageTypeCode() !in REVOKED_MESSAGE_TYPES && !message.isRevokedMessageObject()) {
                        return@hookAllIfPresent
                    }
                    param.result = false
                    logPrivacy("show_deleted_messages_classifier", "forced_not_revoked", message.safeClassName())
                }
                logLifecycle("Hooked WhatsApp revoked-message classifier $className")
            }.onFailure { logError("Failed to hook WhatsApp revoked-message classifier $className", it) }
        }
    }

    private fun hookRevokeEditProcessors() {
        listOf("X.3Ku", "X.C74463Ku").forEach { className ->
            val cls = appClassLoader.findClassOrNull(className) ?: return@forEach
            runCatching {
                hookAllIfPresent(cls, "BzT") { param ->
                    if (!featureState.showDeletedMessages) return@hookAllIfPresent
                    val message = param.args.firstOrNull() ?: return@hookAllIfPresent
                    if (message.messageTypeCode() !in REVOKED_MESSAGE_TYPES && !message.isRevokedMessageObject()) {
                        return@hookAllIfPresent
                    }
                    val stop = processorStopResult() ?: return@hookAllIfPresent
                    rememberDeletedMessage(message, "$className.BzT")
                    param.result = stop
                    logPrivacy("show_deleted_messages_processor", "suppressed_revoke_processor", message.safeClassName())
                }
                logLifecycle("Hooked WhatsApp revoke/edit processor $className")
            }.onFailure { logError("Failed to hook WhatsApp revoke/edit processor $className", it) }
        }
    }

    private fun hookRevokedMessageBuilders() {
        listOf("X.C9D3", "X.9D3").forEach { className ->
            val cls = appClassLoader.findClassOrNull(className) ?: return@forEach
            runCatching {
                hookAllIfPresent(cls, "A01", before = false) { param ->
                    if (!featureState.showDeletedMessages) return@hookAllIfPresent
                    val result = param.result ?: return@hookAllIfPresent
                    if (!result.isRevokedMessageObject()) return@hookAllIfPresent
                    val original = param.args.firstOrNull {
                        val candidate = it ?: return@firstOrNull false
                        candidate.looksLikeWhatsAppMessage() && !candidate.isRevokedMessageObject()
                    } ?: return@hookAllIfPresent
                    rememberDeletedMessage(original, "$className.A01")
                    param.result = original
                    logPrivacy(
                        "show_deleted_messages_builder",
                        "restored_original_after_revoke",
                        "$className ${describeMessageIdentity(original)}"
                    )
                }
                logLifecycle("Hooked WhatsApp revoked-message builder $className")
            }.onFailure { logError("Failed to hook WhatsApp revoked-message builder $className", it) }
        }

        listOf("X.C8H9", "X.8H9").forEach { className ->
            val cls = appClassLoader.findClassOrNull(className) ?: return@forEach
            runCatching {
                hookAllIfPresent(cls, "A08") { param ->
                    if (!featureState.showDeletedMessages) return@hookAllIfPresent
                    if (param.args.any { it?.isRevokedMessageObject() == true }) {
                        param.result = null
                        logPrivacy("show_deleted_messages_builder", "suppressed_revoked_event", className)
                    }
                }
                logLifecycle("Hooked WhatsApp revoked-message event factory $className")
            }.onFailure { logError("Failed to hook WhatsApp revoked-message event factory $className", it) }
        }
    }

    private fun hookRevokedMessageModelBuilders() {
        DELETED_MESSAGE_MODEL_BUILDER_CLASS_NAMES.forEach { className ->
            val cls = appClassLoader.findClassOrNull(className) ?: return@forEach
            runCatching {
                listOf("Bx6", "Bx0", "AEg", "A00").forEach { methodName ->
                    hookAllIfPresent(cls, methodName, before = false) { param ->
                        if (!featureState.showDeletedMessages) return@hookAllIfPresent
                        val revoked = param.result?.takeIf { it.isRevokedMessageObject() }
                            ?: param.args.firstOrNull { it?.isRevokedMessageObject() == true }
                            ?: return@hookAllIfPresent
                        rememberDeletedMessage(revoked, "$className.$methodName")
                    }
                }
                logLifecycle("Hooked WhatsApp revoked-message model builder $className")
            }.onFailure { logError("Failed to hook WhatsApp revoked-message model builder $className", it) }
        }
    }

    private fun hookDeletedMessageTimestampDecorator() {
        listOf("X.C165667bW", "X.7bW").forEach { className ->
            val cls = appClassLoader.findClassOrNull(className) ?: return@forEach
            runCatching {
                hookAllIfPresent(cls, "A01", before = false) { param ->
                    if (!featureState.showDeletedMessages) return@hookAllIfPresent
                    val wrapper = param.args.getOrNull(1) as? ViewGroup
                    val dateView = param.args.getOrNull(2) as? TextView
                        ?: param.thisObject?.fieldValue("A0C") as? TextView
                        ?: return@hookAllIfPresent
                    val message = deletedTimestampMessage(param.args.getOrNull(3)) ?: return@hookAllIfPresent
                    decorateDeletedMessageTimestamps(dateView, wrapper, message, "$className.A01")
                }
                logLifecycle("Hooked WhatsApp deleted-message timestamp decorator $className")
            }.onFailure { logError("Failed to hook WhatsApp deleted-message timestamp decorator $className", it) }
        }
    }

    private fun hookDeletedMessageRowBindDecorators() {
        listOf("X.C7bR", "X.7bR").forEach { className ->
            val cls = appClassLoader.findClassOrNull(className) ?: return@forEach
            runCatching {
                hookAllIfPresent(cls, "A05", before = false) { param ->
                    if (!featureState.showDeletedMessages) return@hookAllIfPresent
                    val wrapper = param.args.getOrNull(0) as? ViewGroup
                    val dateView = param.args.getOrNull(1) as? TextView
                    val message = param.args.getOrNull(2)?.takeIf { it.looksLikeWhatsAppMessage() }
                        ?: return@hookAllIfPresent
                    decorateDeletedMessageTimestamps(dateView, wrapper, message, "$className.A05")
                }
                logLifecycle("Hooked WhatsApp deleted-message row date binder $className")
            }.onFailure { logError("Failed to hook WhatsApp deleted-message row date binder $className", it) }
        }

        DELETED_MESSAGE_ROW_BINDER_CLASS_NAMES.forEach { className ->
            val cls = appClassLoader.findClassOrNull(className) ?: return@forEach
            hookDeletedMessageRowBinderClass(cls, className)
        }
        hookDeletedMessageRowBindDecoratorsByShape()
    }

    private fun hookDeletedMessageRowBindDecoratorsByShape() {
        Thread(
            {
                runCatching {
                    val sourceDir = androidContext.applicationInfo?.sourceDir ?: return@runCatching
                    val dexFileClass = Class.forName("dalvik.system.DexFile")
                    val dexFile = dexFileClass.getConstructor(String::class.java).newInstance(sourceDir)
                    val entries = dexFileClass.getMethod("entries").invoke(dexFile) as? java.util.Enumeration<*>
                        ?: return@runCatching
                    var scanned = 0
                    var hooked = 0
                    while (entries.hasMoreElements()) {
                        val className = entries.nextElement()?.toString() ?: continue
                        if (!className.startsWith("X.") && !className.startsWith("com.whatsapp.")) continue
                        if (className in hookedDeletedRowBinderClasses) continue
                        val cls = appClassLoader.findClassOrNull(className) ?: continue
                        scanned++
                        if (!cls.hasDeletedMessageRowBinderShape()) continue
                        if (hookDeletedMessageRowBinderClass(cls, "shape:$className")) hooked++
                    }
                    runCatching { dexFileClass.getMethod("close").invoke(dexFile) }
                    logLifecycle("Hooked WhatsApp deleted-message row bind decorators by shape hooked=$hooked scanned=$scanned")
                }.onFailure { logError("Failed to hook WhatsApp deleted-message row bind decorators by shape", it) }
            },
            "PurrfectWADeletedRowHooks"
        ).apply { priority = Thread.MIN_PRIORITY }.start()
    }

    private fun hookDeletedMessageRowBinderClass(cls: Class<*>, source: String): Boolean {
        if (!hookedDeletedRowBinderClasses.add(cls.name)) return false
        return runCatching {
            hookAllIfPresent(cls, "A2h", before = false) { param ->
                val row = param.thisObject ?: return@hookAllIfPresent
                val message = param.args.firstOrNull { it?.looksLikeWhatsAppMessage() == true }
                    ?: row.invokeNoArg("getFMessage")?.takeIf { it.looksLikeWhatsAppMessage() }
                    ?: return@hookAllIfPresent
                val rowView = row as? View
                if (!featureState.showDeletedMessages) {
                    releaseDeletedMessages("$source.A2h")
                    if (rowView != null && isReleasedDeletedMessage(message)) {
                        restoreReleasedDeletedRow(rowView, message, "$source.A2h")
                        return@hookAllIfPresent
                    }
                }
                if (!featureState.showDeletedMessages) return@hookAllIfPresent
                val dateView = row.invokeNoArg("getDateView") as? TextView
                val wrapper = row.invokeNoArg("getDateWrapper") as? ViewGroup
                decorateDeletedMessageTimestamps(dateView, wrapper, message, "$source.A2h")
            }
            logLifecycle("Hooked WhatsApp deleted-message row bind decorator $source")
            true
        }.onFailure {
            hookedDeletedRowBinderClasses.remove(cls.name)
            logError("Failed to hook WhatsApp deleted-message row bind decorator $source", it)
        }.getOrDefault(false)
    }

    private fun registerDeletedMessageActivityTracker() {
        runCatching {
            XposedBridge.hookAllMethods(
                Activity::class.java,
                "onCreate",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam<*>) {
                        val activity = param.thisObject as? Activity ?: return
                        rememberDeletedMessageActivityRoot(activity)
                    }
                }
            )

            XposedBridge.hookAllMethods(
                Activity::class.java,
                "onStart",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam<*>) {
                        val activity = param.thisObject as? Activity ?: return
                        rememberDeletedMessageActivityRoot(activity)
                    }
                }
            )

            XposedBridge.hookAllMethods(
                Activity::class.java,
                "onResume",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam<*>) {
                        val activity = param.thisObject as? Activity ?: return
                        rememberDeletedMessageActivityRoot(activity)
                        scheduleVisibleDeletedRowScan(emptyList(), null, "activity_resumed")
                        scheduleVisibleViewOnceRowReset(null, "activity_resumed")
                    }
                }
            )

            XposedBridge.hookAllMethods(
                Activity::class.java,
                "onWindowFocusChanged",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam<*>) {
                        val activity = param.thisObject as? Activity ?: return
                        rememberDeletedMessageActivityRoot(activity)
                        val hasFocus = param.args.firstOrNull() as? Boolean ?: false
                        if (hasFocus) {
                            scheduleVisibleDeletedRowScan(emptyList(), null, "activity_focus")
                            scheduleVisibleViewOnceRowReset(null, "activity_focus")
                        }
                    }
                }
            )

            XposedBridge.hookAllMethods(
                Activity::class.java,
                "onDestroy",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam<*>) {
                        val activity = param.thisObject as? Activity ?: return
                        runCatching { activity.window?.decorView?.let(activeDecorViews::remove) }
                    }
                }
            )

            val application = androidContext.applicationContext as? Application
            if (application != null) {
                application.registerActivityLifecycleCallbacks(
                    object : Application.ActivityLifecycleCallbacks {
                        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                            rememberDeletedMessageActivityRoot(activity)
                        }

                        override fun onActivityStarted(activity: Activity) {
                            rememberDeletedMessageActivityRoot(activity)
                        }

                        override fun onActivityResumed(activity: Activity) {
                            rememberDeletedMessageActivityRoot(activity)
                            scheduleVisibleDeletedRowScan(emptyList(), null, "activity_resumed_callback")
                            scheduleVisibleViewOnceRowReset(null, "activity_resumed_callback")
                        }

                        override fun onActivityPaused(activity: Activity) = Unit

                        override fun onActivityStopped(activity: Activity) = Unit

                        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

                        override fun onActivityDestroyed(activity: Activity) {
                            runCatching { activity.window?.decorView?.let(activeDecorViews::remove) }
                        }
                    }
                )
            }

            logLifecycle("Registered WhatsApp deleted-message activity tracker app=${application != null} ctx=${androidContext.safeClassName()}")
        }.onFailure { logError("Failed to register WhatsApp deleted-message activity tracker", it) }
    }

    private fun rememberDeletedMessageActivityRoot(activity: Activity) {
        runCatching { activity.window?.decorView?.let(activeDecorViews::add) }
    }

    private fun hookWhatsAppDbWrapperMutations() {
        val cls = appClassLoader.findClassOrNull("X.C03630Hm")
            ?: appClassLoader.findClassOrNull("X.0Hm")
            ?: return
        runCatching {
            val mutationNames = setOf("A02", "A03", "A04", "A05", "A06", "A07", "A08", "A09", "A0H", "A0I")
            cls.declaredMethods
                .filter { it.name in mutationNames }
                .forEach { method ->
                    method.isAccessible = true
                    XposedBridge.hookMethod(
                        method,
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam<*>) {
                                runSafe("${cls.name}.${method.name}") {
                                    handleWhatsAppDbWrapperMutation(param, method)
                                }
                            }
                        }
                    )
                }
            logLifecycle("Hooked WhatsApp DB wrapper mutation surface ${cls.name}")
        }.onFailure { logError("Failed to hook WhatsApp DB wrapper mutation surface", it) }
    }

    private fun hookSQLiteMutationSurface() {
        runCatching {
            XposedBridge.hookAllMethods(
                SQLiteDatabase::class.java,
                "insert",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam<*>) {
                        handleInsertLike(param)
                    }
                }
            )
            XposedBridge.hookAllMethods(
                SQLiteDatabase::class.java,
                "insertWithOnConflict",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam<*>) {
                        handleInsertLike(param)
                    }
                }
            )
            XposedBridge.hookAllMethods(
                SQLiteDatabase::class.java,
                "update",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam<*>) {
                        handleUpdateLike(param)
                    }
                }
            )
            XposedBridge.hookAllMethods(
                SQLiteDatabase::class.java,
                "updateWithOnConflict",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam<*>) {
                        handleUpdateLike(param)
                    }
                }
            )
            XposedBridge.hookAllMethods(
                SQLiteDatabase::class.java,
                "delete",
                object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam<*>) {
                        runSafe("SQLiteDatabase.delete") {
                            val table = param.args.firstOrNull()?.toString().orEmpty()
                            rememberMutationDatabase(param, table)
                            if (shouldSuppressViewOnceTableMutation(table)) {
                                param.result = 0
                                logPrivacy("unlimited_view_once_db", "suppressed_delete", table)
                                return@runSafe
                            }
                            if (!featureState.showDeletedMessages) return@runSafe
                            if (!isRevokeTable(table)) return@runSafe
                            param.result = 0
                            logPrivacy("show_deleted_messages_revoke_table", "suppressed_delete", table)
                        }
                    }
                }
            )
            XposedBridge.hookAllMethods(
                SQLiteDatabase::class.java,
                "execSQL",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam<*>) {
                        runSafe("SQLiteDatabase.execSQL") {
                            val sql = param.args.firstOrNull()?.toString().orEmpty()
                            val lower = sql.lowercase(Locale.ROOT)
                            rememberMutationDatabase(param, lower)
                            if (shouldSuppressViewOnceSqlMutation(lower)) {
                                param.result = null
                                logPrivacy("unlimited_view_once_db", "suppressed_exec", sql.take(160))
                                return@runSafe
                            }
                            if (shouldSuppressRevokeSqlMutation(lower)) {
                                param.result = null
                                logPrivacy("show_deleted_messages_revoke_sql", "suppressed_exec", sql.take(120))
                                return@runSafe
                            }
                            if (shouldSuppressRevokedMessageSqlMutation(lower)) {
                                param.result = null
                                logPrivacy("show_deleted_messages_message_sql", "suppressed_tombstone_exec", sql.take(120))
                            }
                        }
                    }
                }
            )
            logLifecycle("Hooked WhatsApp SQLite privacy mutation surface")
        }.onFailure { logError("Failed to hook SQLite mutation surface", it) }
    }

    private fun hookSQLiteQuerySurface() {
        runCatching {
            listOf("rawQuery", "rawQueryWithFactory", "query").forEach { methodName ->
                XposedBridge.hookAllMethods(
                    SQLiteDatabase::class.java,
                    methodName,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam<*>) {
                            runSafe("SQLiteDatabase.$methodName") {
                                if (suppressRevokedReadQuery(param, "sqlite_$methodName")) return@runSafe
                                rewriteRevokedReadSqlArg(param, "sqlite_$methodName")
                                rewriteViewOnceSqlArg(param, "sqlite_$methodName")
                            }
                        }

                        override fun afterHookedMethod(param: MethodHookParam<*>) {
                            runSafe("SQLiteDatabase.$methodName.after") {
                                if (!featureState.unlimitedViewOnce) return@runSafe
                                if (!param.args.any { it?.toString()?.contains("message_view_once_media", ignoreCase = true) == true }) {
                                    return@runSafe
                                }
                                val cursor = param.result as? Cursor ?: return@runSafe
                                if (cursor is ViewOnceStateCursor) return@runSafe
                                param.result = openableViewOnceCursor(cursor, "sqlite_$methodName", param.args)
                                logPrivacy("unlimited_view_once_db", "wrapped_sqlite_cursor", methodName)
                            }
                        }
                    }
                )
            }
            logLifecycle("Hooked WhatsApp SQLite query surface")
        }.onFailure { logError("Failed to hook SQLite query surface", it) }
    }

    private fun hookViewOnceDbReads() {
        val cls = appClassLoader.findClassOrNull("X.C03630Hm")
            ?: appClassLoader.findClassOrNull("X.0Hm")
            ?: return
        runCatching {
            XposedBridge.hookAllMethods(
                cls,
                "A0A",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam<*>) {
                        runSafe("${cls.name}.A0A") {
                            if (suppressRevokedReadQuery(param, cls.name)) return@runSafe
                            rewriteRevokedReadSqlArg(param, cls.name)
                            if (!featureState.unlimitedViewOnce) return@runSafe
                            val sql = param.args.getOrNull(0)?.toString().orEmpty()
                            val queryName = param.args.getOrNull(1)?.toString().orEmpty()
                            rewriteViewOnceSqlArg(param, queryName.ifBlank { cls.name })
                            if (
                                !sql.contains("message_view_once_media", ignoreCase = true) &&
                                !queryName.contains("VIEW_ONCE", ignoreCase = true)
                            ) return@runSafe
                            val args = param.args.getOrNull(2) as? Array<*> ?: return@runSafe
                            val rowId = args.firstOrNull()?.toString()?.toLongOrNull() ?: return@runSafe
                            if (rowId > 0L) {
                                viewOnceMessageRowIds.add(rowId)
                                logPrivacy(
                                    "unlimited_view_once_identity",
                                    "remembered_db_read",
                                    "query=$queryName row=$rowId"
                                )
                            }
                        }
                    }

                    override fun afterHookedMethod(param: MethodHookParam<*>) {
                        runSafe("${cls.name}.A0A.after") {
                            if (!featureState.unlimitedViewOnce) return@runSafe
                            val sql = param.args.getOrNull(0)?.toString().orEmpty()
                            val queryName = param.args.getOrNull(1)?.toString().orEmpty()
                            if (
                                !sql.contains("message_view_once_media", ignoreCase = true) &&
                                !queryName.contains("VIEW_ONCE", ignoreCase = true)
                            ) return@runSafe
                            val cursor = param.result as? Cursor ?: return@runSafe
                            if (cursor is ViewOnceStateCursor) return@runSafe
                            param.result = openableViewOnceCursor(cursor, queryName.ifBlank { cls.name }, param.args)
                            logPrivacy("unlimited_view_once_db", "wrapped_view_once_cursor", queryName)
                        }
                    }
                }
            )
            logLifecycle("Hooked WhatsApp view-once DB reads ${cls.name}")
        }.onFailure { logError("Failed to hook WhatsApp view-once DB reads", it) }
    }

    private fun handleWhatsAppDbWrapperMutation(param: XC_MethodHook.MethodHookParam<*>, method: Method) {
        val table = dbWrapperTableName(param.args)
        val values = param.args.firstInstance<ContentValues>()
        var detail: String? = null
        fun detailValue(): String {
            val current = detail ?: dbWrapperDetail(param.args)
            detail = current
            return current
        }

        fun lowerDetailValue(): String = detailValue().lowercase(Locale.ROOT)

        if (values != null && shouldSuppressRevokedMessageTombstone(table, values)) {
            rememberSuppressedDeletedTombstone(table, values, param.args, "wrapper:${method.name}")
            param.result = neutralDbMutationResult(
                method.returnType,
                method.name.isInsertLikeDbWrapper(),
                suppressedInsertRowId(table, values)
            )
            logPrivacy("show_deleted_messages_message_db", "suppressed_tombstone_values", describeValues(table, values))
            return
        }

        if (
            values != null &&
            featureState.showDeletedMessages &&
            (shouldSuppressRevokeMutation(table, values) || shouldSuppressRevokeSqlMutation(lowerDetailValue()))
        ) {
            rememberSuppressedRevokeTombstone(table, values, param.args, "wrapper:${method.name}")
            param.result = neutralDbMutationResult(
                method.returnType,
                method.name.isInsertLikeDbWrapper(),
                suppressedInsertRowId(table, values)
            )
            logPrivacy("show_deleted_messages_db_wrapper", "suppressed_values", describeValues(table, values))
            return
        }

        if (values != null && normalizeViewOnceOpenedStateMutation(table, values, "wrapper:${method.name}")) {
            logPrivacy("unlimited_view_once_db", "normalized_view_once_opened_state", describeValues(table, values))
            return
        }

        if (featureState.showDeletedMessages && values == null && isRevokeTable(table)) {
            param.result = neutralDbMutationResult(method.returnType)
            logPrivacy("show_deleted_messages_db_wrapper", "suppressed_revoke_table", table)
            return
        }

        if (featureState.showDeletedMessages && shouldSuppressRevokeSqlMutation(lowerDetailValue())) {
            param.result = neutralDbMutationResult(method.returnType, method.name.isInsertLikeDbWrapper())
            logPrivacy("show_deleted_messages_db_wrapper", "suppressed_sql", detailValue().take(160))
            return
        }

        if (featureState.showDeletedMessages && shouldSuppressRevokedMessageSqlMutation(lowerDetailValue())) {
            param.result = neutralDbMutationResult(method.returnType, method.name.isInsertLikeDbWrapper())
            logPrivacy("show_deleted_messages_message_db", "suppressed_tombstone_sql", detailValue().take(160))
            return
        }

        if (values != null) {
            normalizeViewOnceMutationValues(table, values)
        }

        if (featureState.unlimitedViewOnce && values == null && isViewOnceMediaTable(table) && method.name == "A04") {
            param.result = neutralDbMutationResult(method.returnType)
            logPrivacy("unlimited_view_once_db", "suppressed_wrapper_delete", table)
            return
        }

        if (featureState.unlimitedViewOnce && (method.name == "A0H" || method.name == "A0I")) {
            val sql = param.args.firstOrNull()?.toString().orEmpty()
            val lower = sql.lowercase(Locale.ROOT)
            if (shouldSuppressViewOnceSqlMutation(lower)) {
                param.result = neutralDbMutationResult(method.returnType)
                logPrivacy("unlimited_view_once_db", "suppressed_wrapper_exec", sql.take(160))
            }
        }
    }

    private fun handleInsertLike(param: XC_MethodHook.MethodHookParam<*>) {
        runSafe("SQLiteDatabase.insert") {
            val table = param.args.firstOrNull()?.toString().orEmpty()
            rememberMutationDatabase(param, table)
            val values = param.args.firstInstance<ContentValues>() ?: return@runSafe
            if (normalizeViewOnceOpenedStateMutation(table, values, "sqlite_insert")) {
                logPrivacy("unlimited_view_once_db", "normalized_insert_opened_state", describeValues(table, values))
                return@runSafe
            }
            if (shouldSuppressRevokedMessageTombstone(table, values)) {
                rememberSuppressedDeletedTombstone(table, values, param.args, "sqlite_insert")
                param.result = suppressedInsertRowId(table, values)
                logPrivacy("show_deleted_messages_message_db", "suppressed_insert_tombstone", describeValues(table, values))
                return@runSafe
            }
            if (featureState.showDeletedMessages && shouldSuppressRevokeMutation(table, values)) {
                rememberSuppressedRevokeTombstone(table, values, param.args, "sqlite_insert")
                param.result = suppressedInsertRowId(table, values)
                logPrivacy("show_deleted_messages_db", "suppressed_insert", describeValues(table, values))
                return@runSafe
            }
            normalizeViewOnceMutationValues(table, values)
        }
    }

    private fun handleUpdateLike(param: XC_MethodHook.MethodHookParam<*>) {
        runSafe("SQLiteDatabase.update") {
            val table = param.args.firstOrNull()?.toString().orEmpty()
            rememberMutationDatabase(param, table)
            val values = param.args.firstInstance<ContentValues>() ?: return@runSafe
            if (shouldSuppressRevokedMessageTombstone(table, values)) {
                rememberSuppressedDeletedTombstone(table, values, param.args, "sqlite_update")
                param.result = 0
                logPrivacy("show_deleted_messages_message_db", "suppressed_update_tombstone", describeValues(table, values))
                return@runSafe
            }
            if (featureState.showDeletedMessages && shouldSuppressRevokeMutation(table, values)) {
                rememberSuppressedRevokeTombstone(table, values, param.args, "sqlite_update")
                param.result = 0
                logPrivacy("show_deleted_messages_db", "suppressed_update", describeValues(table, values))
                return@runSafe
            }
            if (normalizeViewOnceOpenedStateMutation(table, values, "sqlite_update")) {
                logPrivacy("unlimited_view_once_db", "normalized_update_opened_state", describeValues(table, values))
                return@runSafe
            }
            normalizeViewOnceMutationValues(table, values)
        }
    }

    private fun shouldSuppressReadReceipt(receiptClass: String, jid: String): Boolean {
        if (isStatusReceiptTarget(jid)) return false
        val type = receiptClass.lowercase(Locale.ROOT)
        val isViewOnceRead = type.contains("view_once") || type.contains("view-once")
        val looksLikeRead = type.isBlank() || type == "null" || type.contains("read") || isViewOnceRead
        if (!looksLikeRead) return false
        if (featureState.unlimitedViewOnce && isViewOnceRead) return true
        return featureState.hideBlueTicks
    }

    private fun isStatusReceiptTarget(jid: String): Boolean {
        return jid.lowercase(Locale.ROOT).contains(STATUS_BROADCAST_JID)
    }

    private fun viewOncePlayedReceiptDecision(job: Any): SuppressionDecision? {
        if (!featureState.unlimitedViewOnce) return null
        val rowIds = job.fieldArrayValues("messageRowIds")
            .mapNotNull { (it as? Number)?.toLong() ?: it?.toString()?.toLongOrNull() }
        val ids = job.fieldArrayValues("messageIds")
            .mapNotNull { it?.toString()?.takeIf(String::isNotBlank) }
        val matchesRow = rowIds.any { it in viewOnceMessageRowIds || isViewOnceRowIdInStore(job, it) }
        val matchesId = ids.any { it in viewOnceMessageIds }
        if (!matchesRow && !matchesId) return null
        rowIds.filter { it > 0L }.forEach(viewOnceMessageRowIds::add)
        ids.forEach(viewOnceMessageIds::add)
        return SuppressionDecision(
            "unlimited_view_once_played_receipt",
            "rows=${rowIds.joinToString(",").take(80)} ids=${ids.joinToString(",") { it.take(12) }.take(80)}"
        )
    }

    private fun isViewOnceRowIdInStore(job: Any, rowId: Long): Boolean {
        if (rowId <= 0L) return false
        return runCatching {
            val playedReceiptStore = job.fieldValue("A02") ?: return false
            val provider = playedReceiptStore.fieldValue("A01") ?: return false
            val dbHolder = provider.javaClass.methods.firstOrNull {
                it.name == "get" && it.parameterTypes.isEmpty()
            }?.invoke(provider) ?: return false
            val db = dbHolder.fieldValue("A02") ?: return false
            val query = db.javaClass.methods.firstOrNull {
                it.name == "A0A" && it.parameterTypes.size == 3
            } ?: return false
            val cursor = query.invoke(
                db,
                "SELECT 1 FROM message_view_once_media WHERE message_row_id = ? LIMIT 1",
                "PURRFECT_IS_VIEW_ONCE_ROW_ID",
                arrayOf(rowId.toString())
            ) as? Cursor ?: return false
            cursor.use {
                val found = it.moveToFirst()
                if (found) viewOnceMessageRowIds.add(rowId)
                found
            }
        }.getOrDefault(false)
    }

    private fun suppressionForProtocolText(raw: String, keyHint: String?): SuppressionDecision? {
        val lower = raw.lowercase(Locale.ROOT)
        if (lower.isBlank()) return null

        if (lower.contains("chatstate") && lower.contains("composing")) {
            val isAudio = lower.contains("media='audio'") ||
                lower.contains("media=\"audio\"") ||
                lower.contains(" media=audio") ||
                lower.contains("=audio") ||
                lower.contains("audio")
            if (isAudio && featureState.hideRecordingAudio) {
                return SuppressionDecision("hide_recording_audio_protocol", redactProtocol(raw))
            }
            if (!isAudio && featureState.hideTypingIndicators) {
                return SuppressionDecision("hide_typing_protocol", redactProtocol(raw))
            }
        }

        val isReceipt = lower.contains("<receipt") ||
            lower.contains("cls=receipt") ||
            lower.contains("=receipt") ||
            lower.contains("a06=receipt") ||
            lower.contains(" receipt") ||
            lower.contains("receipt,") ||
            lower.contains("receipt)") ||
            lower.contains("read-receipt")
        if (!isReceipt) return null

        if (featureState.hideDelivered && lower.hasAnyType("delivery", "received")) {
            return SuppressionDecision("hide_delivered_protocol", redactProtocol(raw))
        }

        if (featureState.hideAudioSeen && lower.hasAnyType("played", "played-self")) {
            return SuppressionDecision("hide_audio_seen_protocol", redactProtocol(raw))
        }

        if (
            featureState.unlimitedViewOnce &&
            (lower.contains("view_once_read") || (lower.contains("view_once") && lower.hasAnyType("read", "read-self")))
        ) {
            return SuppressionDecision("unlimited_view_once_read_protocol", redactProtocol(raw))
        }

        val source = (keyHint ?: raw).lowercase(Locale.ROOT)
        val isStatusReceipt = lower.contains(STATUS_BROADCAST_JID) || source.contains(STATUS_BROADCAST_JID)
        if (isStatusReceipt && (lower.hasAnyType("read", "read-self") || lower.hasReadReceiptMarker())) {
            return if (featureState.hideStatusView) {
                SuppressionDecision("hide_status_view_protocol", redactProtocol(raw))
            } else {
                null
            }
        }

        if (lower.hasAnyType("read", "read-self")) {
            val sourceRaw = keyHint ?: raw
            val hasJid = sourceRaw.contains("@") || sourceRaw.contains("remotejid=", ignoreCase = true)
            val suppress = when {
                !hasJid -> featureState.hideBlueTicks
                else -> featureState.hideBlueTicks
            }
            if (suppress) return SuppressionDecision("hide_read_receipt_protocol", redactProtocol(raw))
        }

        if (lower.hasReadReceiptMarker()) {
            if (featureState.hideBlueTicks) return SuppressionDecision("hide_read_receipt_protocol", redactProtocol(raw))
        }

        return null
    }

    private fun shouldSuppressRevokeMutation(table: String, values: ContentValues): Boolean {
        val suppress = isRevokeTable(table) || values.keySet().any { key ->
            val lower = key.lowercase(Locale.ROOT)
            lower.contains("revoked") || lower.contains("revoke")
        }
        if (suppress) rememberDeletedMessage(values, "values:$table")
        return suppress
    }

    private fun shouldSuppressRevokedMessageTombstone(table: String, values: ContentValues): Boolean {
        if (!featureState.showDeletedMessages || !isMessageTable(table)) return false
        val messageType = values.intValue("message_type") ?: return false
        if (messageType !in REVOKED_MESSAGE_TYPES) return false
        val fromMe = values.intValue("from_me")
        val suppress = fromMe != 1
        if (suppress) rememberDeletedMessage(values, "tombstone:$table")
        return suppress
    }

    private fun rememberSuppressedDeletedTombstone(
        table: String,
        values: ContentValues,
        args: Array<Any?>,
        source: String
    ) {
        val rowId = mutationRowId(values, args)
        val keyIds = values.deletedMessageKeyCandidates()
        val tombstone = DeletedTombstone(
            rowId = rowId,
            keyIds = keyIds,
            messageType = values.intValue("message_type") ?: DEFAULT_REVOKED_MESSAGE_TYPE,
            revokedKeyId = values.getAsString("revoked_key_id") ?: values.getAsString("key_id"),
            adminJidRowId = values.longValue("admin_jid_row_id"),
            revokeTimestamp = values.longValue("revoke_timestamp") ?: System.currentTimeMillis()
        )
        val markers = deletedMessageMarkerKeys(keyIds, rowId)
        markers.forEach { marker -> pendingDeletedTombstones[marker] = tombstone }
        if (markers.isNotEmpty()) {
            logPrivacy(
                "show_deleted_messages_release",
                "remembered_tombstone",
                "source=$source table=$table row=${rowId ?: -1} keys=${keyIds.joinToString(",") { it.take(12) }}"
            )
        }
    }

    private fun rememberSuppressedRevokeTombstone(
        table: String,
        values: ContentValues,
        args: Array<Any?>,
        source: String
    ) {
        if (!featureState.showDeletedMessages) return
        if (!isRevokeTable(table) && values.keySet().none { it.contains("revoke", ignoreCase = true) }) return
        val rowId = mutationRowId(values, args)
        val keyIds = values.deletedMessageKeyCandidates()
        if (rowId == null && keyIds.isEmpty()) return
        val prior = tombstoneFor(rowId, keyIds)
        val mergedIds = LinkedHashSet<String>()
        prior?.keyIds?.let(mergedIds::addAll)
        mergedIds.addAll(keyIds)
        val tombstone = DeletedTombstone(
            rowId = rowId ?: prior?.rowId,
            keyIds = mergedIds.toList(),
            messageType = prior?.messageType ?: DEFAULT_REVOKED_MESSAGE_TYPE,
            revokedKeyId = values.getAsString("revoked_key_id")
                ?: values.getAsString("key_id")
                ?: prior?.revokedKeyId
                ?: mergedIds.firstOrNull(),
            adminJidRowId = values.longValue("admin_jid_row_id") ?: prior?.adminJidRowId,
            revokeTimestamp = values.longValue("revoke_timestamp") ?: prior?.revokeTimestamp ?: System.currentTimeMillis()
        )
        val markers = deletedMessageMarkerKeys(tombstone.keyIds, tombstone.rowId)
        markers.forEach { marker -> pendingDeletedTombstones[marker] = tombstone }
        if (markers.isNotEmpty()) {
            logPrivacy(
                "show_deleted_messages_release",
                "remembered_revoke_tombstone",
                "source=$source table=$table row=${tombstone.rowId ?: -1} keys=${tombstone.keyIds.joinToString(",") { it.take(12) }}"
            )
        }
    }

    private fun mutationRowId(values: ContentValues, args: Array<Any?>): Long? {
        values.longValue("message_row_id")
            ?.takeIf { it > 0L }
            ?.let { return it }
        values.longValue("_id")
            ?.takeIf { it > 0L }
            ?.let { return it }
        values.longValue("row_id")
            ?.takeIf { it > 0L }
            ?.let { return it }

        val where = args.getOrNull(2)?.toString()?.lowercase(Locale.ROOT).orEmpty()
        val whereArgs = args.getOrNull(3) as? Array<*>
        if (where.contains("_id") || where.contains("message_row_id")) {
            whereArgs?.firstNotNullOfOrNull { it?.toString()?.toLongOrNull() }
                ?.takeIf { it > 0L }
                ?.let { return it }
        }
        return null
    }

    private fun rememberMutationDatabase(param: XC_MethodHook.MethodHookParam<*>, hint: String) {
        if (!hint.hasWhatsAppMessageDatabaseHint()) return
        val db = param.thisObject as? SQLiteDatabase ?: return
        if (!runCatching { db.isOpen }.getOrDefault(false)) return
        lastWhatsAppDatabase = WeakReference(db)
        synchronized(whatsAppMessageDatabases) {
            val iterator = whatsAppMessageDatabases.iterator()
            while (iterator.hasNext()) {
                val candidate = iterator.next().get()
                if (candidate == null || !runCatching { candidate.isOpen }.getOrDefault(false)) {
                    iterator.remove()
                } else if (candidate === db) {
                    return
                }
            }
            whatsAppMessageDatabases.add(0, WeakReference(db))
            while (whatsAppMessageDatabases.size > MAX_REMEMBERED_MESSAGE_DATABASES) {
                whatsAppMessageDatabases.removeAt(whatsAppMessageDatabases.lastIndex)
            }
        }
    }

    private fun normalizeViewOnceMutationValues(table: String, values: ContentValues) {
        if (!shouldNormalizeViewOnce()) return
        if (values.size() > VIEW_ONCE_UPDATE_VALUE_LIMIT) return

        if (isViewOnceMediaTable(table) && values.containsKey("state")) {
            val state = values.getAsInteger("state")
                ?: values.getAsLong("state")?.toInt()
                ?: values.getAsString("state")?.toIntOrNull()
                ?: return
            if (state != 0) {
                values.put("state", 0)
                logPrivacy("unlimited_view_once_db", "normalized_view_once_media_state", "table=$table old=$state")
            }
            return
        }

        if (!isMessageTable(table) || !values.containsKey("view_mode")) return
        val viewMode = values.getAsInteger("view_mode")
            ?: values.getAsLong("view_mode")?.toInt()
            ?: values.getAsString("view_mode")?.toIntOrNull()
            ?: return
        if (viewMode == 0) return

        values.put("view_mode", 0)
        logPrivacy("unlimited_view_once_db", "normalized_view_mode_update", "table=$table old=$viewMode")
    }

    private fun normalizeViewOnceOpenedStateMutation(table: String, values: ContentValues, source: String): Boolean {
        if (!shouldSuppressViewOnceOpenedStateMutation(table, values)) return false
        rememberViewOnceMutation(values, source)
        values.put("state", 0)
        return true
    }

    private fun shouldNormalizeViewOnce(): Boolean {
        return featureState.unlimitedViewOnce
    }

    private fun shouldSuppressViewOnceTableMutation(table: String): Boolean {
        return featureState.unlimitedViewOnce && isViewOnceMediaTable(table)
    }

    private fun shouldSuppressViewOnceSqlMutation(lowerSql: String): Boolean {
        if (!featureState.unlimitedViewOnce) return false
        if (!lowerSql.contains("message_view_once_media")) return false
        return lowerSql.contains("delete") ||
            (lowerSql.contains("update") && lowerSql.contains("state"))
    }

    private fun shouldSuppressRevokeSqlMutation(lowerSql: String): Boolean {
        if (!featureState.showDeletedMessages) return false
        if (
            !lowerSql.contains("message_revoked") &&
            !lowerSql.contains("message_orphaned_edit") &&
            !lowerSql.contains("revoke")
        ) return false
        return lowerSql.contains("insert") ||
            lowerSql.contains("replace") ||
            lowerSql.contains("delete") ||
            lowerSql.contains("update")
    }

    private fun shouldSuppressRevokedMessageSqlMutation(lowerSql: String): Boolean {
        if (!featureState.showDeletedMessages) return false
        if (!lowerSql.contains("message_type")) return false
        if (!lowerSql.contains("message")) return false
        if (
            !lowerSql.contains("insert") &&
            !lowerSql.contains("replace") &&
            !lowerSql.contains("update")
        ) return false
        val setsRevokedType = REVOKED_MESSAGE_TYPES.any { type ->
            Regex("""\bmessage_type\b\s*(?:=|,|\))\s*'?${type}'?""").containsMatchIn(lowerSql) ||
                Regex("""\b${type}\b[^;]{0,80}\bmessage_type\b""").containsMatchIn(lowerSql)
        }
        if (!setsRevokedType) return false
        return !Regex("""\bfrom_me\b\s*=\s*'?1'?""").containsMatchIn(lowerSql)
    }

    private fun shouldSuppressViewOnceOpenedStateMutation(table: String, values: ContentValues): Boolean {
        if (!featureState.unlimitedViewOnce) return false
        if (!isViewOnceMediaTable(table) || !values.containsKey("state")) return false
        val state = values.getAsInteger("state")
            ?: values.getAsLong("state")?.toInt()
            ?: values.getAsString("state")?.toIntOrNull()
            ?: return false
        return state != 0
    }

    private fun rememberViewOnceMutation(values: ContentValues, source: String) {
        val rowId = values.longValue("message_row_id")
            ?: values.longValue("_id")
            ?: values.longValue("row_id")
        if (rowId == null || rowId <= 0L) return
        viewOnceMessageRowIds.add(rowId)
        logPrivacy("unlimited_view_once_identity", "remembered_mutation", "source=$source row=$rowId")
        scheduleVisibleViewOnceRowReset(null, source)
    }

    private fun isViewOnceMediaTable(table: String): Boolean {
        return table.lowercase(Locale.ROOT).contains("message_view_once_media")
    }

    private fun rewriteViewOnceSqlArg(param: XC_MethodHook.MethodHookParam<*>, source: String) {
        if (!featureState.unlimitedViewOnce) return
        val index = param.args.indexOfFirst {
            it is String && it.contains("message_view_once_media", ignoreCase = true)
        }
        if (index < 0) return
        val sql = param.args[index]?.toString().orEmpty()
        val rewritten = rewriteViewOnceReadSql(sql)
        if (rewritten != sql) {
            param.args[index] = rewritten
            logPrivacy("unlimited_view_once_db", "rewrote_view_once_read_query", source)
            return
        }

        val selectionIndex = index + 2
        val selection = param.args.getOrNull(selectionIndex) as? String ?: return
        val rewrittenSelection = VIEW_ONCE_STATE_PREDICATES.fold(selection) { current, pattern ->
            pattern.replace(current, "1 = 1")
        }
        if (rewrittenSelection == selection) return
        param.args[selectionIndex] = rewrittenSelection
        logPrivacy("unlimited_view_once_db", "rewrote_view_once_query_selection", source)
    }

    private fun rewriteViewOnceReadSql(sql: String): String {
        if (!sql.contains("message_view_once_media", ignoreCase = true)) return sql
        return VIEW_ONCE_STATE_PREDICATES.fold(sql) { rewritten, pattern ->
            pattern.replace(rewritten, "1 = 1")
        }
    }

    private fun openableViewOnceCursor(cursor: Cursor, source: String, args: Array<Any?>): Cursor {
        if (!isViewOnceStateLookup(source, args)) return ViewOnceStateCursor(cursor)
        val hasRow = runCatching {
            val position = cursor.position
            val found = cursor.moveToFirst()
            cursor.moveToPosition(position)
            found
        }.getOrDefault(true)
        if (hasRow) return ViewOnceStateCursor(cursor)

        runCatching { cursor.close() }
        logPrivacy("unlimited_view_once_db", "synthetic_openable_state", source)
        return MatrixCursor(arrayOf("state")).apply {
            addRow(arrayOf(0))
        }
    }

    private fun isViewOnceStateLookup(source: String, args: Array<Any?>): Boolean {
        if (source.contains("GET_VIEW_ONCE_STATE_BY_MESSAGE_ROW_ID", ignoreCase = true)) return true
        val sql = args.firstOrNull { it is String && it.contains("message_view_once_media", ignoreCase = true) }
            ?.toString()
            ?: return false
        val lower = sql.lowercase(Locale.ROOT)
        return lower.contains("select") &&
            lower.contains("state") &&
            lower.contains("message_view_once_media") &&
            lower.contains("message_row_id") &&
            !lower.contains(" join ")
    }

    private fun suppressRevokedReadQuery(param: XC_MethodHook.MethodHookParam<*>, source: String): Boolean {
        if (!featureState.showDeletedMessages) return false
        val lower = dbWrapperRevokedDetail(param.args) ?: return false
        val directRevokedTable = param.args.firstOrNull()
            ?.toString()
            ?.equals("message_revoked", ignoreCase = true) == true
        val revokeInfoRead = lower.contains("get_revoked_message_by_row_id") ||
            (
                lower.contains("message_revoked") &&
                    lower.contains("revoked_key_id") &&
                    lower.contains("revoke_timestamp")
                )
        if (!directRevokedTable && !revokeInfoRead) return false
        param.result = emptyRevokedCursor()
        logPrivacy("show_deleted_messages_revoke_db", "suppressed_read_query", source)
        return true
    }

    private fun rewriteRevokedReadSqlArg(param: XC_MethodHook.MethodHookParam<*>, source: String) {
        if (!featureState.showDeletedMessages) return
        val index = param.args.indexOfFirst { it is String && it.hasRevokedQueryHint() }
        if (index < 0) return
        val sql = param.args[index]?.toString().orEmpty()
        val rewritten = REVOKED_READ_FILTER_PREDICATES.fold(sql) { current, pattern ->
            pattern.replace(current, "1 = 1")
        }
        if (rewritten == sql) return
        param.args[index] = rewritten
        logPrivacy("show_deleted_messages_revoke_db", "rewrote_non_deleted_filter", source)
    }

    private fun emptyRevokedCursor(): Cursor {
        return MatrixCursor(
            arrayOf(
                "_id",
                "message_row_id",
                "revoked_key_id",
                "admin_jid_row_id",
                "revoke_timestamp",
                "server_message_id"
            )
        )
    }

    private fun neutralDbMutationResult(returnType: Class<*>, insertLike: Boolean = false, insertRowId: Long = -1L): Any? {
        return when {
            returnType == Void.TYPE -> null
            returnType == java.lang.Boolean.TYPE || returnType == Boolean::class.javaObjectType -> false
            returnType == java.lang.Integer.TYPE || returnType == Int::class.javaObjectType -> 0
            returnType == java.lang.Long.TYPE || returnType == Long::class.javaObjectType -> if (insertLike) insertRowId else 0L
            else -> null
        }
    }

    private fun suppressedInsertRowId(table: String, values: ContentValues): Long {
        if (isMessageTable(table)) return -1L
        return values.longValue("message_row_id")
            ?: values.longValue("_id")
            ?: values.longValue("row_id")
            ?: -1L
    }

    private fun String.isInsertLikeDbWrapper(): Boolean {
        return this in setOf("A05", "A06", "A07", "A08", "A09")
    }

    private fun dbWrapperTableName(args: Array<Any?>): String {
        val valuesIndex = args.indexOfFirst { it is ContentValues }
        return when {
            valuesIndex == 0 -> args.getOrNull(1)?.toString().orEmpty()
            valuesIndex > 0 -> args.firstOrNull()?.toString().orEmpty()
            else -> args.firstOrNull()?.toString().orEmpty()
        }
    }

    private fun dbWrapperDetail(args: Array<Any?>): String {
        return args.mapNotNull { it as? String }
            .joinToString("|")
            .take(1200)
    }

    private fun dbWrapperRevokedDetail(args: Array<Any?>): String? {
        val strings = ArrayList<String>(args.size)
        var matched = false
        args.forEach { arg ->
            val value = arg as? String ?: return@forEach
            strings += value
            if (value.hasRevokedQueryHint()) matched = true
        }
        if (!matched) return null
        return strings.joinToString("|").take(1200).lowercase(Locale.ROOT)
    }

    private fun String.hasRevokedQueryHint(): Boolean {
        return contains("message_revoked", ignoreCase = true) ||
            contains("message_orphaned_edit", ignoreCase = true) ||
            contains("get_revoked_message", ignoreCase = true) ||
            contains("revoked_key_id", ignoreCase = true) ||
            contains("revoke_timestamp", ignoreCase = true)
    }

    private fun suppressIncomingRevoke(param: XC_MethodHook.MethodHookParam<*>, source: String): Boolean {
        if (!featureState.showDeletedMessages) return false
        val message = param.args.firstOrNull { it?.looksLikeWhatsAppMessage() == true } ?: return false
        if (message.isFromMeMessage()) return false
        rememberDeletedMessage(message, source)
        val returnType = (param.method as? Method)?.returnType ?: Void.TYPE
        param.result = neutralDbMutationResult(returnType)
        logPrivacy("show_deleted_messages_revoke_store", "suppressed", "$source ${message.safeClassName()}")
        return true
    }

    private fun deletedTimestampMessage(state: Any?): Any? {
        if (state == null) return null
        state.fieldValue("A0F")?.takeIf { it.looksLikeWhatsAppMessage() }?.let { return it }
        val cachedField = deletedTimestampMessageFieldCache[state.javaClass]
        if (cachedField == NO_DELETED_TIMESTAMP_MESSAGE_FIELD) return null
        if (cachedField != null) {
            return state.fieldValue(cachedField)?.takeIf { it.looksLikeWhatsAppMessage() }
        }
        val discovered = state.firstWhatsAppMessageField()
        deletedTimestampMessageFieldCache[state.javaClass] =
            discovered?.first ?: NO_DELETED_TIMESTAMP_MESSAGE_FIELD
        if (discovered != null) {
            logPrivacy("show_deleted_messages_timestamp", "cached_message_field", "${state.safeClassName()}.${discovered.first}")
        }
        return discovered?.second
    }

    private fun Any.firstWhatsAppMessageField(): Pair<String, Any>? {
        var cls: Class<*>? = javaClass
        while (cls != null) {
            cls.declaredFields.forEach { field ->
                if (Modifier.isStatic(field.modifiers) || field.isSynthetic) return@forEach
                runCatching {
                    field.isAccessible = true
                    val value = field.get(this)
                    if (value?.looksLikeWhatsAppMessage() == true) return field.name to value
                }
            }
            cls = cls.superclass
        }
        return null
    }

    private fun decorateDeletedMessageTimestamps(
        dateView: TextView?,
        wrapper: ViewGroup?,
        message: Any,
        source: String
    ) {
        rememberVisibleDeletedTimestampTarget(dateView, wrapper, message)
        val marked = isDeletedMessageMarked(message)
        val targets = LinkedHashSet<TextView>()
        dateView?.let(targets::add)
        if (marked) wrapper?.collectTimestampTextViews(targets)
        if (targets.isEmpty()) {
            if (marked) logPrivacy("show_deleted_messages_timestamp", "no_timestamp_targets", source)
            return
        }

        if (!featureState.showDeletedMessages) {
            targets.forEach { decorateDeletedMessageTimestampNow(it, false) }
            return
        }

        var changed = false
        targets.forEach { target ->
            changed = decorateDeletedMessageTimestampNow(target, marked) || changed
        }
        if (marked && changed) {
            logPrivacy(
                "show_deleted_messages_timestamp",
                "decorated",
                "$source ${describeMessageIdentity(message)}"
            )
        }
        if (marked && !changed) {
            logPrivacy(
                "show_deleted_messages_timestamp",
                "marked_no_change",
                "$source texts=${targets.joinToString("|") { it.text?.toString().orEmpty().take(40) }}"
            )
        }
        if (!marked) return

        targets.forEach { target ->
            target.post {
                runSafe("deleted timestamp decorate post") {
                    decorateDeletedMessageTimestampNow(target, featureState.showDeletedMessages)
                }
            }
            target.postDelayed({
                runSafe("deleted timestamp decorate delayed") {
                    decorateDeletedMessageTimestampNow(target, featureState.showDeletedMessages)
                }
            }, DELETED_TIMESTAMP_REAPPLY_DELAY_MS)
        }
    }

    private fun restoreReleasedDeletedRow(row: View, message: Any, source: String) {
        row.visibility = View.VISIBLE
        row.layoutParams?.let { params ->
            if (params.height == 0) {
                params.height = ViewGroup.LayoutParams.WRAP_CONTENT
                row.layoutParams = params
            }
        }
        clearDeletedTimestampText(row)
        applyDeletedPlaceholderText(row)
        row.requestLayout()
        logPrivacy("show_deleted_messages_release", "restored_visible_row_text_only", "$source ${describeMessageIdentity(message)}")
    }

    private fun applyDeletedPlaceholderText(view: View): Boolean {
        val targets = LinkedHashSet<TextView>()
        view.collectNonTimestampTextViews(targets)
        val target = targets.firstOrNull { it.text?.toString()?.isNotBlank() == true } ?: return false
        val text = deletedPlaceholderText()
        return if (target.text?.toString() != text) {
            target.text = text
            true
        } else {
            false
        }
    }

    private fun clearDeletedTimestampText(view: View) {
        if (view is TextView) decorateDeletedMessageTimestampNow(view, false)
        if (view !is ViewGroup) return
        for (index in 0 until view.childCount) {
            view.getChildAt(index)?.let(::clearDeletedTimestampText)
        }
    }

    private fun refreshDeletedRowsForCurrentState(source: String) {
        if (!featureState.showDeletedMessages) releaseDeletedMessages("refresh_$source")
        scheduleVisibleDeletedRowScan(emptyList(), null, "refresh_$source")
    }

    private fun releaseDeletedMessages(source: String) {
        val ids = deletedMessageIds.toList()
        val rows = deletedMessageRowIds.toList()
        val hadState = ids.isNotEmpty() || rows.isNotEmpty() || deletedVisibleTimestampTargets.isNotEmpty()
        if (ids.isNotEmpty()) releasedDeletedMessageIds.addAll(ids)
        if (rows.isNotEmpty()) releasedDeletedMessageRowIds.addAll(rows)
        if (ids.isNotEmpty() || rows.isNotEmpty()) {
            replayReleasedDeletedTombstones(ids, rows, source)
        }
        deletedMessageIds.clear()
        deletedMessageRowIds.clear()
        deletedVisibleTimestampTargets.clear()
        runCatching {
            synchronized(deletedMessageMarkerLock) {
                File(androidContext.filesDir, DELETED_MESSAGE_MARKERS_FILE).delete()
            }
        }.onFailure { logError("Failed to delete released deleted-message markers", it) }

        if (hadState) {
            val roots = synchronized(activeDecorViews) { activeDecorViews.toList() }
            roots.forEach(::clearDeletedTimestampText)
            logPrivacy(
                "show_deleted_messages_release",
                "released",
                "source=$source ids=${ids.size} rows=${rows.size} roots=${roots.size}"
            )
        }
    }

    private fun replayReleasedDeletedTombstones(ids: Collection<String>, rows: Collection<Long>, source: String) {
        val databases = candidateMessageDatabases()
        if (databases.isEmpty()) {
            logPrivacy("show_deleted_messages_release", "missing_db_for_tombstone_replay", source)
            return
        }

        val restoredRows = LinkedHashSet<Long>()
        databases.forEach { db ->
            rows.filter { it > 0L }.forEach { rowId ->
                if (rowId !in restoredRows) {
                    restoreDeletedTombstoneInDatabase(db, rowId, tombstoneFor(rowId = rowId, ids = emptyList()), source)
                        .takeIf { it }?.let { restoredRows += rowId }
                }
            }
            ids.forEach { id ->
                val tombstone = tombstoneFor(rowId = null, ids = listOf(id))
                val rowId = tombstone?.rowId ?: findMessageRowId(db, id)
                if (rowId != null && rowId > 0L && rowId !in restoredRows) {
                    restoreDeletedTombstoneInDatabase(db, rowId, tombstone, source)
                        .takeIf { it }?.let { restoredRows += rowId }
                }
            }
        }

        if (restoredRows.isEmpty()) {
            logPrivacy(
                "show_deleted_messages_release",
                "tombstone_replay_missed",
                "source=$source ids=${ids.size} rows=${rows.size} dbs=${databases.size}"
            )
        } else {
            logPrivacy(
                "show_deleted_messages_release",
                "replayed_tombstones",
                "source=$source rows=${restoredRows.joinToString(",").take(120)} dbs=${databases.size}"
            )
        }
    }

    private fun candidateMessageDatabases(): List<SQLiteDatabase> {
        val candidates = LinkedHashSet<SQLiteDatabase>()
        lastWhatsAppDatabase.get()
            ?.takeIf { runCatching { it.isOpen }.getOrDefault(false) }
            ?.let(candidates::add)
        synchronized(whatsAppMessageDatabases) {
            val iterator = whatsAppMessageDatabases.iterator()
            while (iterator.hasNext()) {
                val db = iterator.next().get()
                if (db == null || !runCatching { db.isOpen }.getOrDefault(false)) {
                    iterator.remove()
                } else {
                    candidates += db
                }
            }
        }
        return candidates.toList()
    }

    private fun hasTableMissingFailure(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            val message = current.message.orEmpty().lowercase(Locale.ROOT)
            if (message.contains("no such table") || message.contains("no such column")) return true
            current = current.cause
        }
        return false
    }

    private fun restoreDeletedTombstoneInDatabase(
        db: SQLiteDatabase,
        rowId: Long,
        tombstone: DeletedTombstone?,
        source: String
    ): Boolean {
        return runCatching {
            val messageType = tombstone?.messageType?.takeIf { it in REVOKED_MESSAGE_TYPES } ?: DEFAULT_REVOKED_MESSAGE_TYPE
            val updateValues = ContentValues()
            updateValues.put("message_type", messageType)
            updateValues.putNull("text_data")
            updateValues.putNull("translated_text")
            updateValues.put("view_mode", 0)
            val updated = db.update("message", updateValues, "_id = ?", arrayOf(rowId.toString()))
            if (updated <= 0) return@runCatching false

            val revokeValues = ContentValues()
            revokeValues.put("message_row_id", rowId)
            val revokedKeyId = tombstone?.revokedKeyId ?: tombstone?.keyIds?.firstOrNull()
            if (revokedKeyId.isNullOrBlank()) revokeValues.putNull("revoked_key_id") else revokeValues.put("revoked_key_id", revokedKeyId)
            val adminJidRowId = tombstone?.adminJidRowId
            if (adminJidRowId != null) revokeValues.put("admin_jid_row_id", adminJidRowId) else revokeValues.putNull("admin_jid_row_id")
            revokeValues.put("revoke_timestamp", tombstone?.revokeTimestamp ?: System.currentTimeMillis())
            runCatching {
                db.insertWithOnConflict("message_revoked", null, revokeValues, SQLiteDatabase.CONFLICT_REPLACE)
            }.onFailure {
                if (hasTableMissingFailure(it)) {
                    logPrivacy("show_deleted_messages_release", "revoke_table_replay_skipped", "source=$source row=$rowId")
                } else {
                    throw it
                }
            }
            true
        }.onFailure {
            if (hasTableMissingFailure(it)) {
                logPrivacy("show_deleted_messages_release", "skipped_non_message_db", "source=$source row=$rowId")
            } else {
                logError("Failed to replay WhatsApp deleted-message tombstone from $source row=$rowId", it)
            }
        }.getOrDefault(false)
    }

    private fun tombstoneFor(rowId: Long?, ids: Collection<String>): DeletedTombstone? {
        if (rowId != null && rowId > 0L) {
            pendingDeletedTombstones["row:$rowId"]?.let { return it }
        }
        ids.forEach { id -> pendingDeletedTombstones["id:$id"]?.let { return it } }
        return null
    }

    private fun findMessageRowId(db: SQLiteDatabase, keyId: String): Long? {
        if (keyId.isBlank()) return null
        return runCatching {
            db.rawQuery("SELECT _id FROM message WHERE key_id = ? LIMIT 1", arrayOf(keyId)).use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) else null
            }
        }.onFailure {
            if (!hasTableMissingFailure(it)) logError("Failed to find WhatsApp message row for deleted-message release", it)
        }.getOrNull()
    }

    private fun releasedDeletedMessageMarkerKeys(): Set<String> {
        val markers = LinkedHashSet<String>()
        releasedDeletedMessageIds.forEach { id -> markers += "id:$id" }
        releasedDeletedMessageRowIds.forEach { rowId -> markers += "row:$rowId" }
        return markers
    }

    private fun clearReleasedDeletedMessageMarkers() {
        releasedDeletedMessageIds.clear()
        releasedDeletedMessageRowIds.clear()
    }

    private fun saveDeletedMessageMarker(type: String, value: String) {
        if (value.isBlank()) return
        runCatching {
            synchronized(deletedMessageMarkerLock) {
                val file = File(androidContext.filesDir, DELETED_MESSAGE_MARKERS_FILE)
                if (file.exists() && file.length() > DELETED_MESSAGE_MARKER_LIMIT * 64L) {
                    file.writeText("", Charsets.UTF_8)
                }
                File(androidContext.filesDir, DELETED_MESSAGE_MARKERS_FILE).appendText("$type:$value\n", Charsets.UTF_8)
            }
        }.onFailure { logError("Failed to persist deleted-message marker", it) }
    }

    private fun rememberVisibleDeletedTimestampTarget(dateView: TextView?, wrapper: ViewGroup?, message: Any) {
        if (dateView == null && wrapper == null) return
        val markerKeys = deletedMessageMarkerKeys(message)
        if (markerKeys.isEmpty()) return
        val target = DeletedTimestampTarget(
            WeakReference(message),
            dateView?.let(::WeakReference),
            wrapper?.let(::WeakReference)
        )
        markerKeys.forEach { deletedVisibleTimestampTargets[it] = target }
        trimVisibleDeletedTimestampTargets()
    }

    private fun decorateVisibleDeletedTimestampTarget(markerKey: String, source: String) {
        val target = deletedVisibleTimestampTargets[markerKey]
        if (target == null) {
            logPrivacy("show_deleted_messages_timestamp", "visible_target_missing", "$source $markerKey")
            return
        }
        val message = target.message.get()
        val dateView = target.dateView?.get()
        val wrapper = target.wrapper?.get()
        if (message == null || (dateView == null && wrapper == null)) {
            deletedVisibleTimestampTargets.remove(markerKey)
            logPrivacy("show_deleted_messages_timestamp", "visible_target_stale", "$source $markerKey")
            return
        }
        decorateDeletedMessageTimestamps(dateView, wrapper, message, "$source.visible")
    }

    private fun scheduleVisibleDeletedRowScan(keys: Collection<String>, rowId: Long?, source: String) {
        if (!featureState.showDeletedMessages) releaseDeletedMessages(source)
        val markerKeys = if (featureState.showDeletedMessages) {
            deletedMessageMarkerKeys(keys, rowId).toSet()
        } else {
            releasedDeletedMessageMarkerKeys()
        }
        if (
            markerKeys.isEmpty() &&
            deletedMessageIds.isEmpty() &&
            deletedMessageRowIds.isEmpty() &&
            releasedDeletedMessageIds.isEmpty() &&
            releasedDeletedMessageRowIds.isEmpty()
        ) return

        val task = Runnable {
            runSafe("deleted visible row scan") {
                scanVisibleDeletedRows(markerKeys, source)
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            task.run()
        } else {
            uiHandler.post(task)
        }
        DELETED_VISIBLE_ROW_SCAN_DELAYS_MS.forEach { delay ->
            uiHandler.postDelayed(task, delay)
        }
    }

    private fun scanVisibleDeletedRows(markerKeys: Set<String>, source: String) {
        val roots = synchronized(activeDecorViews) { activeDecorViews.toList() }
        if (roots.isEmpty()) {
            logPrivacy("show_deleted_messages_timestamp", "visible_scan_miss", "$source roots=0")
            return
        }

        val seen = Collections.newSetFromMap(IdentityHashMap<View, Boolean>())
        val stats = IntArray(2)
        roots.forEach { root ->
            root.decorateDeletedRowsInTree(markerKeys, source, seen, stats)
        }
        if (stats[1] > 0) {
            logPrivacy(
                "show_deleted_messages_timestamp",
                "visible_scan_decorated",
                "$source rows=${stats[1]} views=${stats[0]} roots=${roots.size}"
            )
        } else {
            logPrivacy(
                "show_deleted_messages_timestamp",
                "visible_scan_miss",
                "$source keys=${markerKeys.size} views=${stats[0]} roots=${roots.size}"
            )
        }
    }

    private fun View.decorateDeletedRowsInTree(
        markerKeys: Set<String>,
        source: String,
        seen: MutableSet<View>,
        stats: IntArray
    ) {
        if (!seen.add(this)) return
        stats[0]++

        val message = visibleDeletedRowMessage()
        if (message != null) {
            val marked = visibleDeletedRowMatches(message, markerKeys)
            val released = isReleasedDeletedMessage(message) ||
                (!featureState.showDeletedMessages && marked)
            if (released && !featureState.showDeletedMessages) {
                restoreReleasedDeletedRow(this, message, "$source.scan")
                stats[1]++
            } else if (marked && featureState.showDeletedMessages) {
                val dateView = invokeNoArg("getDateView") as? TextView
                val wrapper = invokeNoArg("getDateWrapper") as? ViewGroup ?: this as? ViewGroup
                decorateDeletedMessageTimestamps(dateView, wrapper, message, "$source.scan")
                stats[1]++
            }
        }

        if (this !is ViewGroup) return
        for (index in 0 until childCount) {
            getChildAt(index)?.decorateDeletedRowsInTree(markerKeys, source, seen, stats)
        }
    }

    private fun View.visibleDeletedRowMessage(): Any? {
        val hasMessageAccessor = deletedVisibleRowAccessorCache.getOrPut(javaClass) {
            javaClass.methods.any { method ->
                method.name == "getFMessage" && method.parameterTypes.isEmpty()
            }
        }
        if (!hasMessageAccessor) return null
        return invokeNoArg("getFMessage")?.takeIf { it.looksLikeWhatsAppMessage() }
    }

    private fun visibleDeletedRowMatches(message: Any, markerKeys: Set<String>): Boolean {
        if (markerKeys.isNotEmpty() && deletedMessageMarkerKeys(message).any(markerKeys::contains)) return true
        return isDeletedMessageMarked(message)
    }

    private fun trimVisibleDeletedTimestampTargets() {
        if (deletedVisibleTimestampTargets.size <= DELETED_VISIBLE_TIMESTAMP_TARGET_LIMIT) return
        deletedVisibleTimestampTargets.entries.removeIf { (_, target) ->
            target.message.get() == null ||
                (target.dateView?.get() == null && target.wrapper?.get() == null)
        }
        if (deletedVisibleTimestampTargets.size <= DELETED_VISIBLE_TIMESTAMP_TARGET_LIMIT) return
        deletedVisibleTimestampTargets.keys
            .asSequence()
            .take(deletedVisibleTimestampTargets.size - DELETED_VISIBLE_TIMESTAMP_TARGET_LIMIT)
            .forEach(deletedVisibleTimestampTargets::remove)
    }

    private fun decorateDeletedMessageTimestampNow(dateView: TextView, marked: Boolean): Boolean {
        val text = dateView.text?.toString().orEmpty()
        if (!text.looksLikeTimestampText()) return false
        val cleanTime = text.removePrefix("$DELETED_TIMESTAMP_LABEL | ").trim()
        return if (marked) {
            val decorated = "$DELETED_TIMESTAMP_LABEL | $cleanTime"
            if (text != decorated) {
                dateView.text = decorated
                true
            } else {
                false
            }
        } else if (text.startsWith("$DELETED_TIMESTAMP_LABEL | ") && text != cleanTime) {
            dateView.text = cleanTime
            true
        } else {
            false
        }
    }

    private fun View.collectTimestampTextViews(targets: MutableSet<TextView>) {
        if (this is TextView && text?.toString()?.looksLikeTimestampText() == true) {
            targets += this
        }
        if (this !is ViewGroup) return
        for (index in 0 until childCount) {
            getChildAt(index)?.collectTimestampTextViews(targets)
        }
    }

    private fun View.collectNonTimestampTextViews(targets: MutableSet<TextView>) {
        if (this is TextView) {
            val current = text?.toString().orEmpty()
            if (current.isNotBlank() && !current.looksLikeTimestampText()) targets += this
        }
        if (this !is ViewGroup) return
        for (index in 0 until childCount) {
            getChildAt(index)?.collectNonTimestampTextViews(targets)
        }
    }

    private fun deletedPlaceholderText(): String {
        val res = androidContext.resources
        val packageName = androidContext.packageName
        return listOf("_name_removed__res_0x7f122f7f")
            .asSequence()
            .mapNotNull { name ->
                val id = res.getIdentifier(name, "string", packageName)
                if (id == 0) null else runCatching { res.getString(id) }.getOrNull()
            }
            .firstOrNull { it.isNotBlank() }
            ?: "This message was deleted"
    }

    private fun String.looksLikeTimestampText(): Boolean {
        if (isBlank()) return false
        val clean = removePrefix("$DELETED_TIMESTAMP_LABEL | ").trim()
        if (clean.length > 16) return false
        return DELETED_TIMESTAMP_TEXT_PATTERN.matches(clean)
    }

    private fun rememberDeletedMessage(message: Any?, source: String) {
        if (message == null) return
        val keys = messageKeyIds(message)
        val rowId = (message.fieldValue("A0j") as? Number)?.toLong()
        rememberDeletedMessageMarkers(keys, rowId, source)
    }

    private fun rememberDeletedMessage(values: ContentValues, source: String) {
        val keys = values.deletedMessageKeyCandidates()
        val rowId = values.longValue("message_row_id")
            ?: values.longValue("_id")
            ?: values.longValue("row_id")
        rememberDeletedMessageMarkers(keys, rowId, source)
    }

    private fun rememberDeletedMessageMarker(key: String?, rowId: Long?, source: String) {
        rememberDeletedMessageMarkers(listOfNotNull(key), rowId, source)
    }

    private fun rememberDeletedMessageMarkers(keys: Collection<String>, rowId: Long?, source: String) {
        val cleanKeys = keys.mapNotNull { it.trim().takeIf(String::isNotBlank) }.distinct()
        var changed = false
        cleanKeys.forEach { key ->
            if (!deletedMessageIds.add(key)) return@forEach
            appendDeletedMessageMarker("id", key)
            changed = true
        }
        if (rowId != null && rowId > 0L && deletedMessageRowIds.add(rowId)) {
            appendDeletedMessageMarker("row", rowId.toString())
            changed = true
        }
        if (changed) {
            logPrivacy(
                "show_deleted_messages_marker",
                "remembered",
                "source=$source ids=${cleanKeys.joinToString(",") { it.take(12) }.ifBlank { "-" }} row=${rowId ?: -1}"
            )
        }
        deletedMessageMarkerKeys(cleanKeys, rowId).forEach { decorateVisibleDeletedTimestampTarget(it, source) }
        scheduleVisibleDeletedRowScan(cleanKeys, rowId, source)
    }

    private fun isDeletedMessageMarked(message: Any): Boolean {
        if (deletedMessageRowIds.isNotEmpty()) {
            val rowId = (message.fieldValue("A0j") as? Number)?.toLong()
            if (rowId != null && rowId > 0L && rowId in deletedMessageRowIds) return true
        }
        if (deletedMessageIds.isEmpty()) return false
        return messageKeyIds(message).any { it in deletedMessageIds }
    }

    private fun isReleasedDeletedMessage(message: Any): Boolean {
        if (releasedDeletedMessageRowIds.isNotEmpty()) {
            val rowId = (message.fieldValue("A0j") as? Number)?.toLong()
            if (rowId != null && rowId > 0L && rowId in releasedDeletedMessageRowIds) return true
        }
        if (releasedDeletedMessageIds.isEmpty()) return false
        return messageKeyIds(message).any { it in releasedDeletedMessageIds }
    }

    private fun deletedMessageMarkerKeys(message: Any): List<String> {
        val keys = messageKeyIds(message)
        val rowId = (message.fieldValue("A0j") as? Number)?.toLong()
        return deletedMessageMarkerKeys(keys, rowId)
    }

    private fun deletedMessageMarkerKeys(key: String?, rowId: Long?): List<String> {
        return deletedMessageMarkerKeys(listOfNotNull(key), rowId)
    }

    private fun deletedMessageMarkerKeys(keys: Collection<String>, rowId: Long?): List<String> {
        val markers = ArrayList<String>(keys.size + 1)
        keys.forEach { key ->
            if (key.isNotBlank()) markers += "id:$key"
        }
        if (rowId != null && rowId > 0L) markers += "row:$rowId"
        return markers
    }

    private fun messageKeyId(message: Any): String? {
        return messageKeyIds(message).firstOrNull()
    }

    private fun messageKeyIds(message: Any): List<String> {
        val keys = LinkedHashSet<String>()
        if (message.isRevokedMessageObject() || message.messageTypeCode() in REVOKED_MESSAGE_TYPES) {
            (message.fieldValue("A01") as? String)?.takeIf { it.isNotBlank() }?.let(keys::add)
        }
        val key = message.fieldValue("A0i") ?: message.fieldValue("A05")
        key?.fieldValue("A01")?.toString()?.takeIf { it.isNotBlank() }?.let(keys::add)
        return keys.toList()
    }

    private fun loadDeletedMessageMarkers() {
        runCatching {
            val file = File(androidContext.filesDir, DELETED_MESSAGE_MARKERS_FILE)
            if (!file.exists()) return@runCatching
            file.useLines(Charsets.UTF_8) { lines ->
                lines.take(DELETED_MESSAGE_MARKER_LIMIT).forEach { line ->
                    when {
                        line.startsWith("id:") -> deletedMessageIds.add(line.removePrefix("id:"))
                        line.startsWith("row:") -> line.removePrefix("row:").toLongOrNull()?.let(deletedMessageRowIds::add)
                    }
                }
            }
        }.onFailure { logError("Failed to load deleted message markers", it) }
    }

    private fun appendDeletedMessageMarker(type: String, value: String) {
        if (deletedMessageIds.size + deletedMessageRowIds.size > DELETED_MESSAGE_MARKER_LIMIT) return
        runCatching {
            synchronized(deletedMessageMarkerLock) {
                File(androidContext.filesDir, DELETED_MESSAGE_MARKERS_FILE).appendText("$type:$value\n", Charsets.UTF_8)
            }
        }.onFailure { logError("Failed to persist deleted message marker", it) }
    }

    private fun Method.isMessageClientSendCandidate(): Boolean {
        if (name in MESSAGE_CLIENT_SEND_METHODS) return true
        if (parameterTypes.any { it == Message::class.java }) return true
        if (parameterTypes.any { it.isProtocolNodeType() || it.isStanzaKeyType() }) return true
        return returnType.name.contains("FutureC23163AMr")
    }

    private fun Class<*>.isProtocolNodeType(): Boolean {
        if (name in PROTOCOL_NODE_CLASS_NAMES) return true
        return hasDeclaredField("A00", String::class.java) &&
            hasDeclaredArrayField("A02") &&
            hasDeclaredArrayField("A03")
    }

    private fun Class<*>.isStanzaKeyType(): Boolean {
        if (name in STANZA_KEY_CLASS_NAMES) return true
        return hasDeclaredField("A06", String::class.java) &&
            hasDeclaredField("A09", String::class.java) &&
            hasDeclaredField("A08", String::class.java)
    }

    private fun Class<*>.hasDeclaredField(name: String, type: Class<*>? = null): Boolean {
        return runCatching {
            var cls: Class<*>? = this
            while (cls != null) {
                val field = cls.declaredFields.firstOrNull { it.name == name }
                if (field != null) return type == null || field.type == type
                cls = cls.superclass
            }
            false
        }.getOrDefault(false)
    }

    private fun Class<*>.hasDeclaredArrayField(name: String): Boolean {
        return runCatching {
            var cls: Class<*>? = this
            while (cls != null) {
                val field = cls.declaredFields.firstOrNull { it.name == name }
                if (field != null) return field.type.isArray
                cls = cls.superclass
            }
            false
        }.getOrDefault(false)
    }

    private fun completedFutureOrNull(): Any? {
        return runCatching {
            val futureClass = appClassLoader.findClassOrNull("X.FutureC23163AMr") ?: return null
            val future = futureClass.getDeclaredConstructor().newInstance()
            val complete = futureClass.methods.firstOrNull {
                it.name == "BTq" && it.parameterTypes.size == 1
            } ?: return null
            complete.invoke(future, null)
            future
        }.onFailure { logError("Failed to create completed WhatsApp future", it) }.getOrNull()
    }

    private fun processorStopResult(): Any? {
        return runCatching {
            val cls = appClassLoader.findClassOrNull("X.C3LQ")
                ?: appClassLoader.findClassOrNull("X.3LQ")
                ?: return null
            cls.getDeclaredField("A00").apply { isAccessible = true }.get(null)
        }.getOrNull()
    }

    private fun firstMessageEnvelopeDecision(args: Array<Any?>, raw: String): SuppressionDecision? {
        args.forEach { arg ->
            val message = arg as? Message ?: return@forEach
            suppressionForMessageEnvelope(message, raw)?.let { return it }
        }
        return null
    }

    private fun suppressionForMessageEnvelope(message: Message, raw: String): SuppressionDecision? {
        val detail = redactProtocol(raw)
        val arg1 = message.arg1
        val isDeliveryReceiptEnvelope = arg1 in DELIVERY_RECEIPT_MESSAGE_ARG1 &&
            (message.obj?.looksLikeWhatsAppMessage() == true || raw.contains("receipt", ignoreCase = true))
        val isOutgoingAckReceiptEnvelope = arg1 in OUTGOING_ACK_RECEIPT_MESSAGE_ARG1 &&
            message.obj.looksLikeOutgoingAckReceipt()
        if (featureState.hideDelivered && (isDeliveryReceiptEnvelope || isOutgoingAckReceiptEnvelope)) {
            return SuppressionDecision("hide_delivered_protocol", detail)
        }
        if (arg1 in READ_RECEIPT_MESSAGE_ARG1) {
            if (raw.contains(STATUS_BROADCAST_JID, ignoreCase = true)) {
                return if (featureState.hideStatusView) {
                    SuppressionDecision("hide_status_view_protocol", detail)
                } else {
                    null
                }
            }
            if (featureState.hideBlueTicks) return SuppressionDecision("hide_read_receipt_protocol", detail)
        }
        if (arg1 in PLAYED_RECEIPT_MESSAGE_ARG1) {
            if (featureState.hideAudioSeen) {
                return SuppressionDecision("hide_audio_seen_protocol", detail)
            }
            if (featureState.unlimitedViewOnce) {
                return SuppressionDecision("unlimited_view_once_read_protocol", detail)
            }
        }
        return null
    }

    private fun suppressionForQueuedMessage(message: Message): SuppressionDecision? {
        if (!hasAnyPrivacySendFeature()) return null
        if (!message.isPotentialWhatsAppOutgoingMessage()) return null
        val decision = suppressionForOutgoingArgs(arrayOf<Any?>(message)) ?: return null
        return decision.copy(detail = queuedMessageDetail(message, decision.detail))
    }

    private fun suppressionForOutgoingArgs(args: Array<Any?>): SuppressionDecision? {
        val raw = describeOutgoingArgs(args)
        suppressionForProtocolText(raw, raw)?.let { return it }

        val deep = describeOutgoingArgsDeep(args)
        firstMessageEnvelopeDecision(args, "$raw | $deep")?.let { return it }
        val decision = suppressionForProtocolText(deep, "$raw | $deep")
        if (decision == null) {
            logObservedPrivacyCandidate(raw)
            if (deep != raw) logObservedPrivacyCandidate(deep)
        }
        return decision
    }

    private fun suppressReturn(
        param: XC_MethodHook.MethodHookParam<*>,
        decision: SuppressionDecision,
        action: String
    ): Boolean {
        val returnType = (param.method as? Method)?.returnType ?: Void.TYPE
        val result = when {
            returnType == Void.TYPE -> null
            returnType == java.lang.Boolean.TYPE || returnType == Boolean::class.javaObjectType -> true
            returnType.name.contains("FutureC23163AMr") -> completedFutureOrNull() ?: return false
            else -> return false
        }
        param.result = result
        logPrivacy(decision.type, action, decision.detail)
        return true
    }

    private fun hookAllIfPresent(
        cls: Class<*>,
        methodName: String,
        before: Boolean = true,
        block: (XC_MethodHook.MethodHookParam<*>) -> Unit
    ) {
        if (cls.declaredMethods.none { it.name == methodName }) return
        XposedBridge.hookAllMethods(
            cls,
            methodName,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam<*>) {
                    if (before) runSafe("${cls.name}.$methodName") { block(param) }
                }

                override fun afterHookedMethod(param: MethodHookParam<*>) {
                    if (!before) runSafe("${cls.name}.$methodName") { block(param) }
                }
            }
        )
    }

    private fun shouldSuppressReadReceiptForMessage(message: Any?): Boolean {
        if (message == null) return featureState.hideBlueTicks
        val identity = describeMessageIdentity(message)
        if (
            featureState.unlimitedViewOnce &&
            (isViewOnceCandidate(message) || identity.contains("view_once", ignoreCase = true))
        ) return true
        val hasJid = identity.contains("@") || identity.contains("jid", ignoreCase = true)
        return when {
            !hasJid -> featureState.hideBlueTicks
            else -> featureState.hideBlueTicks
        }
    }

    private fun shouldSuppressReadManagerSideEffect(message: Any?): Boolean {
        if (!featureState.unlimitedViewOnce) return false
        if (message == null) return false
        return isViewOnceCandidate(message) || describeMessageIdentity(message).contains("view_once", ignoreCase = true)
    }

    private fun describeMessageIdentity(message: Any?): String {
        if (message == null) return ""
        val key = message.fieldValue("A0i")
        return listOfNotNull(
            key?.fieldValue("A00"),
            key?.fieldValue("A01"),
            key?.fieldValue("A02"),
            message.fieldValue("A0p"),
            message.fieldValue("A0h"),
            message.safeClassName(),
            message.toString()
        ).joinToString("|") { it.toString() }.take(500)
    }

    private fun describeOutgoingArgs(args: Array<Any?>): String {
        return args.joinToString(" | ") { describeOutgoingArg(it) }.take(2400)
    }

    private fun describeOutgoingArgsDeep(args: Array<Any?>): String {
        val seen = IdentityHashMap<Any, Boolean>()
        return args.joinToString(" | ") { describeOutgoingArgDeep(it, 0, seen) }.take(3600)
    }

    private fun describeOutgoingArg(arg: Any?): String {
        return when (arg) {
            null -> ""
            is Message -> {
                val data = runCatching { arg.peekData()?.toString().orEmpty() }.getOrDefault("")
                "Message{what=${arg.what},arg1=${arg.arg1},arg2=${arg.arg2},target=${arg.targetClassName()},data=$data,obj=${describeOutgoingArg(arg.obj)}}"
            }
            is ContentValues -> describeValues("", arg)
            is Array<*> -> arg.joinToString(prefix = "[", postfix = "]") { describeOutgoingArg(it).take(160) }
            is ByteArray -> runCatching { String(arg, Charsets.UTF_8) }.getOrDefault("bytes:${arg.size}").take(240)
            else -> when {
                arg.looksLikeProtocolNode() -> describeProtocolNode(arg)
                arg.looksLikeStanzaKey() -> describeStanzaKey(arg)
                arg.looksLikeReadReceiptPayload() -> describeReadReceiptPayload(arg)
                arg.looksLikePlayedReceiptPayload() -> describePlayedReceiptPayload(arg)
                else -> "${arg.safeClassName()}:$arg".take(1200)
            }
        }
    }

    private fun describeOutgoingArgDeep(arg: Any?, depth: Int, seen: IdentityHashMap<Any, Boolean>): String {
        if (arg == null) return ""
        if (depth > DEEP_DESCRIBE_MAX_DEPTH) return describeOutgoingArg(arg).take(180)
        return when (arg) {
            is String, is Number, is Boolean, is Char -> arg.toString()
            is Message -> {
                val data = runCatching { arg.peekData()?.toString().orEmpty() }.getOrDefault("")
                "Message{what=${arg.what},arg1=${arg.arg1},arg2=${arg.arg2},target=${arg.targetClassName()},data=$data,obj=${describeOutgoingArgDeep(arg.obj, depth + 1, seen)}}"
            }
            is ContentValues -> describeValues("", arg)
            is ByteArray -> runCatching { String(arg, Charsets.UTF_8) }.getOrDefault("bytes:${arg.size}").take(240)
            is Array<*> -> arg.take(DEEP_DESCRIBE_COLLECTION_LIMIT)
                .joinToString(prefix = "[", postfix = "]") { describeOutgoingArgDeep(it, depth + 1, seen).take(220) }
            is LongArray -> arg.take(DEEP_DESCRIBE_COLLECTION_LIMIT).joinToString(prefix = "[", postfix = "]")
            is IntArray -> arg.take(DEEP_DESCRIBE_COLLECTION_LIMIT).joinToString(prefix = "[", postfix = "]")
            is Collection<*> -> arg.take(DEEP_DESCRIBE_COLLECTION_LIMIT)
                .joinToString(prefix = "[", postfix = "]") { describeOutgoingArgDeep(it, depth + 1, seen).take(220) }
            is Map<*, *> -> arg.entries.take(DEEP_DESCRIBE_COLLECTION_LIMIT)
                .joinToString(prefix = "{", postfix = "}") { entry ->
                    "${describeOutgoingArgDeep(entry.key, depth + 1, seen).take(80)}=${describeOutgoingArgDeep(entry.value, depth + 1, seen).take(180)}"
                }
            else -> describeObjectDeep(arg, depth, seen)
        }.take(1800)
    }

    private fun describeObjectDeep(arg: Any, depth: Int, seen: IdentityHashMap<Any, Boolean>): String {
        if (arg.looksLikeProtocolNode()) return describeProtocolNode(arg)
        if (arg.looksLikeStanzaKey()) return describeStanzaKey(arg)
        if (arg.looksLikeReadReceiptPayload()) return describeReadReceiptPayload(arg)
        if (arg.looksLikePlayedReceiptPayload()) return describePlayedReceiptPayload(arg)
        val className = arg.safeClassName()
        val toStringValue = runCatching { arg.toString() }.getOrDefault("")
        if (!className.isWhatsAppRuntimeClass()) {
            return "$className:$toStringValue".take(500)
        }
        if (seen.put(arg, true) == true) return "$className:<seen>"
        val fields = mutableListOf<String>()
        var cls: Class<*>? = arg.javaClass
        while (cls != null && fields.size < DEEP_DESCRIBE_FIELD_LIMIT) {
            cls.declaredFields.forEach { field ->
                if (fields.size >= DEEP_DESCRIBE_FIELD_LIMIT) return@forEach
                if (Modifier.isStatic(field.modifiers) || field.isSynthetic) return@forEach
                runCatching {
                    field.isAccessible = true
                    val value = field.get(arg)
                    fields += "${field.name}=${describeOutgoingArgDeep(value, depth + 1, seen).take(220)}"
                }
            }
            cls = cls.superclass
        }
        return "$className:$toStringValue{${fields.joinToString(",")}}".take(1800)
    }

    private fun Any.looksLikeProtocolNode(): Boolean {
        return javaClass.isProtocolNodeType()
    }

    private fun Any.looksLikeStanzaKey(): Boolean {
        return javaClass.isStanzaKeyType()
    }

    private fun Any.looksLikeReadReceiptPayload(): Boolean {
        return fieldValue("A04") is Array<*> &&
            (fieldValue("A05") != null || fieldValue("A08") != null || fieldValue("A09") != null)
    }

    private fun Any.looksLikePlayedReceiptPayload(): Boolean {
        return fieldValue("A03") is Array<*> &&
            fieldValue("A02") is String &&
            (fieldValue("A00") != null || fieldValue("A01") != null)
    }

    private fun describeProtocolNode(node: Any): String {
        val tag = node.fieldValue("A00")?.toString().orEmpty()
        val attrs = node.fieldArrayValues("A03")
            .mapNotNull { attr ->
                val key = attr?.fieldValue("A02")?.toString() ?: return@mapNotNull null
                val value = attr.fieldValue("A03")?.toString().orEmpty()
                "$key=$value"
            }
            .joinToString(" ")
        val children = node.fieldArrayValues("A02")
            .take(8)
            .joinToString("") { child -> describeOutgoingArg(child).take(400) }
        val data = (node.fieldValue("A01") as? ByteArray)
            ?.let { runCatching { String(it, Charsets.UTF_8) }.getOrDefault("bytes:${it.size}") }
            .orEmpty()
        return "<$tag $attrs>${children.ifBlank { data }}</$tag>".take(1200)
    }

    private fun describeStanzaKey(key: Any): String {
        return listOf(
            "StanzaKey",
            "cls=${key.fieldValue("A06")}",
            "type=${key.fieldValue("A09")}",
            "id=${key.fieldValue("A08")}",
            "category=${key.fieldValue("A05")}",
            "remoteJid=${key.fieldValue("A02")}",
            "participant=${key.fieldValue("A01")}",
            "recipient=${key.fieldValue("A03")}",
            "node=${key.fieldValue("A04")?.let { describeOutgoingArg(it) }.orEmpty()}"
        ).joinToString(" ").take(1200)
    }

    private fun describeReadReceiptPayload(payload: Any): String {
        val ids = payload.fieldArrayValues("A04").joinToString(",") { it?.toString().orEmpty() }
        return listOf(
            "read_receipt_payload",
            "receiptClass=${payload.fieldValue("A03")}",
            "jid=${payload.fieldValue("A05")}",
            "participant=${payload.fieldValue("A07")}",
            "remoteSender=${payload.fieldValue("A09")}",
            "key=${payload.fieldValue("A08")}",
            "ids=$ids"
        ).joinToString(" ").take(1200)
    }

    private fun describePlayedReceiptPayload(payload: Any): String {
        val ids = payload.fieldArrayValues("A03").joinToString(",") { it?.toString().orEmpty() }
        return listOf(
            "played_receipt_payload",
            "type=${payload.fieldValue("A02")}",
            "jid=${payload.fieldValue("A01")}",
            "participant=${payload.fieldValue("A00")}",
            "ids=$ids"
        ).joinToString(" ").take(1200)
    }

    private fun logObservedPrivacyCandidate(raw: String) {
        if (!hasAnyPrivacySendFeature()) return
        val lower = raw.lowercase(Locale.ROOT)
        if (
            lower.contains("chatstate") ||
            lower.contains("composing") ||
            lower.contains("receipt") ||
            lower.contains("delivery") ||
            lower.contains("played") ||
            lower.contains("read-receipt")
        ) {
            logPrivacy("privacy_outgoing_candidate", "observed_unsuppressed", redactProtocol(raw))
        }
    }

    private fun hasAnyPrivacySendFeature(): Boolean {
        return featureState.hideTypingIndicators ||
            featureState.hideRecordingAudio ||
            featureState.hideDelivered ||
            featureState.hideAudioSeen ||
            featureState.hideStatusView ||
            featureState.unlimitedViewOnce ||
            featureState.hideBlueTicks
    }

    private fun isMessageTable(table: String): Boolean {
        return table.equals("message", ignoreCase = true) ||
            table.equals("available_message_view", ignoreCase = true)
    }

    private fun isRevokeTable(table: String): Boolean {
        return table.equals("message_revoked", ignoreCase = true) ||
            table.equals("message_orphaned_edit", ignoreCase = true)
    }

    private fun String.hasWhatsAppMessageDatabaseHint(): Boolean {
        val lower = lowercase(Locale.ROOT)
        return lower == "message" ||
            lower == "available_message_view" ||
            lower == "message_revoked" ||
            lower == "message_orphaned_edit" ||
            lower.contains(" message ") ||
            lower.contains("message.") ||
            lower.contains("message_") ||
            lower.contains("message_revoked") ||
            lower.contains("message_orphaned_edit") ||
            lower.contains("view_once") ||
            lower.contains("revoke")
    }

    private fun isGroupText(text: String): Boolean {
        val lower = text.lowercase(Locale.ROOT)
        return lower.contains("@g.us") || lower.contains("g.us") || lower.contains("groupjid")
    }

    private fun String.isWhatsAppRuntimeClass(): Boolean {
        return startsWith("X.") ||
            startsWith("com.whatsapp") ||
            startsWith("com.facebook.simplejni") ||
            startsWith("com.facebook.wamsys")
    }

    private fun String.hasAnyType(vararg types: String): Boolean {
        return types.any { type ->
            contains("type=$type") ||
                contains("type='$type'") ||
                contains("type=\"$type\"") ||
                contains("=$type") ||
                contains("='$type'") ||
                contains("=\"$type\"") ||
                contains(" $type ") ||
                contains(">$type<")
        }
    }

    private fun String.hasReadReceiptMarker(): Boolean {
        return contains("read-receipt") ||
            contains("read_receipt_payload") ||
            contains("receiptclass=read") ||
            contains("receiptclass='read'") ||
            contains("receiptclass=\"read\"") ||
            contains("receiptclass=null")
    }

    private fun Any?.looksLikeOutgoingAckReceipt(): Boolean {
        if (this == null) return false
        val name = safeClassName().lowercase(Locale.ROOT)
        val text = runCatching { toString().lowercase(Locale.ROOT) }.getOrDefault("")
        return text.contains("outgoingackreceipt") ||
            (text.contains("tag=receipt") && text.contains("loggablestanzaid")) ||
            (name.endsWith(".c34361dq") || name.endsWith(".1dq"))
    }

    private fun Any.isFromMeMessage(): Boolean {
        val key = fieldValue("A0i") ?: fieldValue("A05") ?: return false
        return key.fieldValue("A02") as? Boolean ?: false
    }

    private fun Any.isRevokedMessageObject(): Boolean {
        val cls = javaClass
        return revokedMessageClassCache.getOrPut(cls) {
            var current: Class<*>? = cls
            while (current != null) {
                if (current.name in REVOKED_MESSAGE_CLASS_NAMES) return@getOrPut true
                current = current.superclass
            }
            cls.name.contains("1N", ignoreCase = true) &&
                cls.hasDeclaredField("A01") &&
                cls.hasDeclaredField("A00")
        }
    }

    private fun Any.looksLikeWhatsAppMessage(): Boolean {
        val directKey = fieldValue("A0i")
        val orphanKey = fieldValue("A05")
        if (directKey != null || orphanKey != null) return true
        return javaClass.methods.any { it.name == "AuC" && it.parameterTypes.isEmpty() }
    }

    private fun Any.messageTypeCode(): Int? {
        return (fieldValue("A0h") as? Number)?.toInt()
    }

    private fun Class<*>.hasViewOnceStateShape(): Boolean {
        if (Modifier.isInterface(modifiers) || Modifier.isAbstract(modifiers)) return false
        val hasGetter = methods.any { method ->
            method.name == "AzF" &&
                method.parameterTypes.isEmpty() &&
                (method.returnType == Integer.TYPE || method.returnType == Int::class.javaObjectType)
        }
        if (!hasGetter) return false
        val hasSetter = methods.any { method ->
            method.name == "CDn" &&
                method.parameterTypes.size == 1 &&
                method.parameterTypes[0] == Integer.TYPE
        }
        if (!hasSetter) return false
        return methods.any { it.name == "AuC" && it.parameterTypes.isEmpty() } ||
            hasDeclaredField("A0i") ||
            hasDeclaredField("A0h") ||
            name.startsWith("X.")
    }

    private fun Class<*>.hasDeletedMessageRowBinderShape(): Boolean {
        if (Modifier.isInterface(modifiers) || Modifier.isAbstract(modifiers)) return false
        if (declaredMethods.none { it.name == "A2h" }) return false
        val hasDateView = methods.any { method ->
            method.name == "getDateView" &&
                method.parameterTypes.isEmpty() &&
                TextView::class.java.isAssignableFrom(method.returnType)
        }
        if (!hasDateView) return false
        return methods.any { method ->
            method.name == "getDateWrapper" &&
                method.parameterTypes.isEmpty() &&
                ViewGroup::class.java.isAssignableFrom(method.returnType)
        }
    }

    private fun Message.isPotentialWhatsAppOutgoingMessage(): Boolean {
        val targetName = targetClassName()
        val payload = obj
        val payloadName = payload?.safeClassName().orEmpty()
        val fromWhatsApp = targetName.isWhatsAppRuntimeClass() || payloadName.isWhatsAppRuntimeClass()
        if (!fromWhatsApp) return false
        if (what in NETWORK_MESSAGE_WHATS) return true
        if (arg1 in PRIVACY_XMPP_MESSAGE_ARG1) return true
        if (payload == null) return false
        if (
            payload.looksLikeProtocolNode() ||
            payload.looksLikeStanzaKey() ||
            payload.looksLikeReadReceiptPayload() ||
            payload.looksLikePlayedReceiptPayload()
        ) return true
        val shallow = describeOutgoingArg(payload).lowercase(Locale.ROOT)
        return shallow.contains("chatstate") ||
            shallow.contains("composing") ||
            shallow.contains("receipt") ||
            shallow.contains("delivery") ||
            shallow.contains("played") ||
            shallow.contains("read-receipt")
    }

    private fun Message.targetClassName(): String {
        return runCatching { target?.javaClass?.name.orEmpty() }.getOrDefault("")
    }

    private fun queuedMessageDetail(message: Message, detail: String): String {
        return "what=${message.what} arg1=${message.arg1} target=${message.targetClassName()} $detail".take(900)
    }

    private fun Any.fieldValue(name: String): Any? {
        var cls: Class<*>? = javaClass
        while (cls != null) {
            runCatching {
                val field = cls.getDeclaredField(name)
                field.isAccessible = true
                return field.get(this)
            }
            cls = cls.superclass
        }
        return null
    }

    private fun Any.invokeNoArg(name: String): Any? {
        var cls: Class<*>? = javaClass
        while (cls != null) {
            cls.declaredMethods
                .firstOrNull { it.name == name && it.parameterTypes.isEmpty() }
                ?.let { method ->
                    return runCatching {
                        method.isAccessible = true
                        method.invoke(this)
                    }.getOrNull()
                }
            cls = cls.superclass
        }
        return null
    }

    private fun Any.invokeNoArgIfPresent(name: String): Boolean {
        var cls: Class<*>? = javaClass
        while (cls != null) {
            cls.declaredMethods
                .firstOrNull { it.name == name && it.parameterTypes.isEmpty() }
                ?.let { method ->
                    return runCatching {
                        method.isAccessible = true
                        method.invoke(this)
                        true
                    }.getOrDefault(false)
                }
            cls = cls.superclass
        }
        return false
    }

    private fun Any.invokeMessageBindIfPresent(message: Any): Boolean {
        val method = javaClass.methods.firstOrNull { candidate ->
            candidate.name == "A2h" &&
                candidate.parameterTypes.size == 2 &&
                candidate.parameterTypes[0].isAssignableFrom(message.javaClass) &&
                candidate.parameterTypes[1] == java.lang.Boolean.TYPE
        } ?: return false
        return runCatching {
            method.isAccessible = true
            method.invoke(this, message, true)
            true
        }.getOrDefault(false)
    }

    private fun Any.firstIntFieldValue(): Int? {
        var cls: Class<*>? = javaClass
        while (cls != null) {
            cls.declaredFields.forEach { field ->
                runCatching {
                    if (field.type == Integer.TYPE || field.type == Int::class.javaObjectType) {
                        field.isAccessible = true
                        return (field.get(this) as? Number)?.toInt()
                    }
                }
            }
            cls = cls.superclass
        }
        return null
    }

    private fun Any.fieldArrayValues(name: String): List<Any?> {
        val value = fieldValue(name) ?: return emptyList()
        return when (value) {
            is Array<*> -> value.toList()
            is LongArray -> value.toList()
            is IntArray -> value.toList()
            else -> emptyList()
        }
    }

    private fun Any.allFieldValues(): List<Any?> {
        val values = mutableListOf<Any?>()
        var cls: Class<*>? = javaClass
        while (cls != null) {
            cls.declaredFields.forEach { field ->
                runCatching {
                    if (!Modifier.isStatic(field.modifiers)) {
                        field.isAccessible = true
                        values += field.get(this)
                    }
                }
            }
            cls = cls.superclass
        }
        return values
    }

    private inline fun <reified T> Array<Any?>.firstInstance(): T? {
        return firstOrNull { it is T } as? T
    }

    private fun ClassLoader.findClassOrNull(name: String): Class<*>? {
        return runCatching { loadClass(name) }.getOrNull()
    }

    private fun Any.safeClassName(): String = javaClass.name

    private fun ContentValues.intValue(key: String): Int? {
        return getAsInteger(key)
            ?: getAsLong(key)?.toInt()
            ?: getAsString(key)?.toIntOrNull()
    }

    private fun ContentValues.longValue(key: String): Long? {
        return getAsLong(key)
            ?: getAsInteger(key)?.toLong()
            ?: getAsString(key)?.toLongOrNull()
    }

    private fun ContentValues.deletedMessageKeyCandidates(): List<String> {
        val keys = LinkedHashSet<String>()
        DELETED_MESSAGE_KEY_COLUMNS.forEach { column ->
            getAsString(column)?.extractDeletedMessageKeyCandidates()?.let(keys::addAll)
        }
        return keys.toList()
    }

    private fun String.extractDeletedMessageKeyCandidates(): List<String> {
        val candidates = LinkedHashSet<String>()
        val clean = trim()
        if (
            clean.length in 8..128 &&
            clean.none(Char::isWhitespace) &&
            !clean.contains("@") &&
            !clean.equals("null", ignoreCase = true)
        ) {
            candidates += clean
        }
        DELETED_MESSAGE_KEY_PATTERN.findAll(this).forEach { match ->
            candidates += match.value
        }
        return candidates.toList()
    }

    private fun describeValues(table: String, values: ContentValues): String {
        return "table=$table keys=${values.keySet().joinToString(",").take(160)}"
    }

    private fun redactJid(jid: String): String {
        if (jid.isBlank()) return ""
        val suffix = jid.substringAfter('@', missingDelimiterValue = "")
        return if (suffix.isBlank()) "<jid>" else "<jid>@$suffix"
    }

    private fun redactProtocol(raw: String): String {
        return raw
            .replace(Regex("[0-9]{5,}(@[a-z.]+)?"), "<id>$1")
            .take(240)
    }

    private fun runSafe(area: String, block: () -> Unit) {
        runCatching(block).onFailure { logError("WhatsApp privacy hook failed in $area", it) }
    }

    private fun logLifecycle(message: String) {
        XposedBridge.log("[$TAG] $message")
        CoreLogger.xposedLog(message, TAG)
        WhatsAppAppLogWriter.info(androidContext, TAG, message)
    }

    private fun logPrivacy(type: String, action: String, detail: String) {
        val key = "$type|$action|$detail".take(512)
        val count = privacyEvents.getOrPut(key) { AtomicInteger(0) }.incrementAndGet()
        if (count > 3 && count != 10 && count != 25 && count % 100 != 0) return
        logLifecycle("WhatsApp privacy hook: type=$type action=$action detail=${detail.take(900)} count=$count")
    }

    private fun logError(message: String, throwable: Throwable) {
        XposedBridge.log("[$TAG] $message: ${throwable.stackTraceToString()}")
        CoreLogger.xposedLog("$message: ${throwable.message}", TAG)
        WhatsAppAppLogWriter.error(androidContext, TAG, "$message: ${throwable.stackTraceToString()}")
    }

    private data class SuppressionDecision(
        val type: String,
        val detail: String
    )

    private class DeletedTimestampTarget(
        val message: WeakReference<Any>,
        val dateView: WeakReference<TextView>?,
        val wrapper: WeakReference<ViewGroup>?
    )

    private class DeletedTombstone(
        val rowId: Long?,
        val keyIds: List<String>,
        val messageType: Int,
        val revokedKeyId: String?,
        val adminJidRowId: Long?,
        val revokeTimestamp: Long
    )

    private class ViewOnceStateCursor(cursor: Cursor) : CursorWrapper(cursor) {
        override fun getInt(columnIndex: Int): Int {
            return if (isStateColumn(columnIndex)) 0 else super.getInt(columnIndex)
        }

        override fun getLong(columnIndex: Int): Long {
            return if (isStateColumn(columnIndex)) 0L else super.getLong(columnIndex)
        }

        override fun getShort(columnIndex: Int): Short {
            return if (isStateColumn(columnIndex)) 0 else super.getShort(columnIndex)
        }

        override fun getString(columnIndex: Int): String? {
            return if (isStateColumn(columnIndex)) "0" else super.getString(columnIndex)
        }

        private fun isStateColumn(columnIndex: Int): Boolean {
            return runCatching { getColumnName(columnIndex).equals("state", ignoreCase = true) }
                .getOrDefault(false)
        }
    }

    companion object {
        private const val TAG = "PurrfectWA.Privacy"
        private val activeHooks = Collections.synchronizedSet(
            Collections.newSetFromMap(WeakHashMap<WhatsAppPrivacyHooks, Boolean>())
        )
        private const val DELETED_TIMESTAMP_LABEL = "Message Deleted"
        private const val DELETED_TIMESTAMP_REAPPLY_DELAY_MS = 120L
        private val DELETED_VISIBLE_ROW_SCAN_DELAYS_MS = longArrayOf(120L, 500L, 1500L)
        private const val DELETED_VISIBLE_TIMESTAMP_TARGET_LIMIT = 512
        private const val DELETED_MESSAGE_MARKERS_FILE = "purrfect_deleted_message_markers.txt"
        private const val DELETED_MESSAGE_MARKER_LIMIT = 4096
        private const val NO_DELETED_TIMESTAMP_MESSAGE_FIELD = "<none>"
        private const val DEFAULT_REVOKED_MESSAGE_TYPE = 15
        private const val MAX_REMEMBERED_MESSAGE_DATABASES = 8
        private const val CONNECTION_SEND_WHAT = 4
        private const val VIEW_ONCE_UPDATE_VALUE_LIMIT = 3
        private const val VIEW_ONCE_STORE_MARKER_CASE = 21
        private val VIEW_ONCE_VISIBLE_ROW_SCAN_DELAYS_MS = longArrayOf(120L, 500L, 1500L)
        private const val DEEP_DESCRIBE_MAX_DEPTH = 3
        private const val DEEP_DESCRIBE_FIELD_LIMIT = 18
        private const val DEEP_DESCRIBE_COLLECTION_LIMIT = 12
        private val VIEW_ONCE_DELAYED_OPEN_MARKER_CASES = setOf(39, 40)
        private val VIEW_ONCE_MEDIA_TYPES = setOf(8, 9, 10, 42, 78, 82)
        private val REVOKED_MESSAGE_CLASS_NAMES = setOf("X.C1N2", "X.1N2", "X.C1N3", "X.1N3", "X.C1N7", "X.1N7")
        private val DELETED_TIMESTAMP_TEXT_PATTERN =
            Regex("""\d{1,2}:\d{2}(?:\s?[AP]M)?""", RegexOption.IGNORE_CASE)
        private val DELETED_MESSAGE_KEY_PATTERN =
            Regex("""(?i)\b[0-9a-f]{10,}\b""")
        private val DELETED_MESSAGE_KEY_COLUMNS = setOf(
            "revoked_key_id",
            "message_key_id",
            "server_message_id",
            "key_id",
            "text_data",
            "translated_text"
        )
        private val DELETED_MESSAGE_MODEL_BUILDER_CLASS_NAMES = listOf(
            "X.C185838Sv",
            "X.8Sv",
            "X.C185848Sw",
            "X.8Sw",
            "X.C237312v",
            "X.12v",
            "X.AbstractC210789af",
            "X.9af",
            "X.AbstractC212119cs",
            "X.9cs"
        )
        private val DELETED_MESSAGE_ROW_BINDER_CLASS_NAMES = listOf(
            "X.AbstractC165957bz",
            "X.7bz",
            "X.C84P",
            "X.84P",
            "X.C85B",
            "X.85B",
            "X.C85I",
            "X.85I",
            "X.C85J",
            "X.85J",
            "X.C85L",
            "X.85L",
            "X.C85D",
            "X.85D",
            "X.C85Q",
            "X.85Q",
            "X.C85C",
            "X.85C",
            "X.C85K",
            "X.85K",
            "X.C84I",
            "X.84I",
            "X.C84G",
            "X.84G",
            "X.C84H",
            "X.84H"
        )
        private val NETWORK_MESSAGE_WHATS = setOf(2, 4)
        private val DELIVERY_RECEIPT_MESSAGE_ARG1 = setOf(9)
        private val OUTGOING_ACK_RECEIPT_MESSAGE_ARG1 = setOf(43)
        private val READ_RECEIPT_MESSAGE_ARG1 = setOf(89, 419)
        private val PLAYED_RECEIPT_MESSAGE_ARG1 = setOf(38)
        private val PRIVACY_XMPP_MESSAGE_ARG1 = DELIVERY_RECEIPT_MESSAGE_ARG1 +
            OUTGOING_ACK_RECEIPT_MESSAGE_ARG1 +
            READ_RECEIPT_MESSAGE_ARG1 +
            PLAYED_RECEIPT_MESSAGE_ARG1 +
            setOf(4, 6, 233, 343)
        private const val STATUS_BROADCAST_JID = "status@broadcast"
        private val REVOKED_MESSAGE_TYPES = setOf(15, 64)
        private val VIEW_ONCE_STATE_PREDICATES = listOf(
            Regex("""(?i)\b(?:\w+\.)?state\s*=\s*0\b"""),
            Regex("""(?i)\b0\s*=\s*(?:\w+\.)?state\b"""),
            Regex("""(?i)\b(?:\w+\.)?state\s+IN\s*\(\s*0\s*\)""")
        )
        private val REVOKED_READ_FILTER_PREDICATES = listOf(
            Regex("""(?i)\bmessage_revoked\.[A-Za-z0-9_]+\s+IS\s+NULL\b"""),
            Regex("""(?i)\bNOT\s+EXISTS\s*\([^)]*message_revoked[^)]*\)""")
        )
        private val PROTOCOL_NODE_CLASS_NAMES = setOf(
            "X.0ah",
            "X.C08210ah"
        )
        private val STANZA_KEY_CLASS_NAMES = setOf(
            "X.94V",
            "X.C94V"
        )
        private val VIEW_ONCE_STATE_MODEL_CLASS_NAMES = setOf(
            "X.1Jh",
            "X.C27821Jh",
            "X.1Jk",
            "X.C27851Jk",
            "X.1Jn",
            "X.C27881Jn",
            "X.1Js",
            "X.C27931Js"
        )
        private val MESSAGE_CLIENT_SEND_METHODS = setOf(
            "A04",
            "A05",
            "A06",
            "A08",
            "A09",
            "A0A",
            "A0B",
            "A0C",
            "A0D",
            "A0I",
            "A0J",
            "A0K",
            "A0L",
            "A0M",
            "A0N",
            "A0P",
            "A0Q"
        )
        private val installed = AtomicBoolean(false)
        private val privacyEvents = ConcurrentHashMap<String, AtomicInteger>()

        fun refreshActiveHooks(reason: String) {
            synchronized(activeHooks) {
                activeHooks.toList()
            }.forEach { hook ->
                hook.refreshDeletedRowsForCurrentState(reason)
            }
        }
    }
}
