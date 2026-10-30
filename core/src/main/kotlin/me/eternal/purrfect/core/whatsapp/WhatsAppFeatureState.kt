package me.eternal.purrfect.core.whatsapp

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.IBinder
import android.os.SystemClock
import de.robv.android.xposed.XposedBridge
import me.eternal.purrfect.bridge.BridgeInterface
import me.eternal.purrfect.common.BuildConfig
import me.eternal.purrfect.common.Constants
import me.eternal.purrfect.core.logger.CoreLogger
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class WhatsAppFeatureState(
    val hideChannels: Boolean = false,
    val hideChannelRecommendations: Boolean = false,
    val hideCommunitiesTab: Boolean = false,
    val hideTypingIndicators: Boolean = false,
    val hideRecordingAudio: Boolean = false,
    val hideDelivered: Boolean = false,
    val hideAudioSeen: Boolean = false,
    val hideStatusView: Boolean = false,
    val hideStartChatting: Boolean = false,
    val unlimitedViewOnce: Boolean = false,
    val hideBlueTicks: Boolean = false,
    val showDeletedMessages: Boolean = false,
    val hideUiElements: Boolean = false,
    val captureUiElements: Boolean = false,
    val liquidClass: Boolean = false,
    val hiddenUiElementIds: String = "",
    val hiddenUiElementSelectors: String = "",
    val source: String = "unavailable"
) {
    companion object {
        private const val TAG = WhatsAppChannelHooks.TAG
        private const val PREFS_NAME = "whatsapp_features"
        private const val KEY_HIDE_CHANNELS = "hide_channels"
        private const val KEY_HIDE_CHANNEL_RECOMMENDATIONS = "hide_channel_recommendations"
        private const val KEY_HIDE_COMMUNITIES_TAB = "hide_communities_tab"
        private const val KEY_HIDE_TYPING_INDICATORS = "hide_typing_indicators"
        private const val KEY_HIDE_RECORDING_AUDIO = "hide_recording_audio"
        private const val KEY_HIDE_DELIVERED = "hide_delivered"
        private const val KEY_HIDE_AUDIO_SEEN = "hide_audio_seen"
        private const val KEY_HIDE_STATUS_VIEW = "hide_status_view"
        private const val KEY_HIDE_START_CHATTING = "hide_start_chatting"
        private const val KEY_UNLIMITED_VIEW_ONCE = "unlimited_view_once"
        private const val KEY_HIDE_BLUE_TICKS = "hide_blue_ticks"
        private const val KEY_SHOW_DELETED_MESSAGES = "show_deleted_messages"
        private const val KEY_HIDE_UI_ELEMENTS = "hide_ui_elements"
        private const val KEY_CAPTURE_UI_ELEMENTS = "capture_ui_elements"
        private const val KEY_LIQUID_CLASS = "liquid_class"
        private const val KEY_HIDDEN_UI_ELEMENT_IDS = "hidden_ui_element_ids"
        private const val KEY_HIDDEN_UI_ELEMENT_SELECTORS = "hidden_ui_element_selectors"
        private const val PROVIDER_AUTHORITY = "me.eternal.purrfect.whatsapp.config"
        private const val PROVIDER_METHOD_GET_FEATURES = "getWhatsAppFeatures"
        private const val WHATSAPP_FEATURES_FILE = "files/whatsapp_features.json"
        private const val WHATSAPP_EXTERNAL_FEATURES_FILE = "whatsapp_features.json"
        private const val WHATSAPP_PROCESS_CACHE_FILE = "purrfect_whatsapp_features_cache.json"
        private val unavailableLogged = AtomicBoolean(false)

        fun load(androidContext: Context): WhatsAppFeatureState {
            WhatsAppFeatureStateStore.current.takeIf { it.source != "unavailable" }?.let { return it }
            return loadFromWhatsAppProcessCache(androidContext)
                ?: loadFromExternalJson()
                ?: loadFromModuleJson(androidContext)
                ?: loadFromRootJson()
                ?: loadFromBridge(androidContext)
                ?: loadFromProvider(androidContext)
                ?: loadFromXposedPrefs()
                ?: loadFromWorldReadablePrefs()
                ?: WhatsAppFeatureState().also {
                    if (unavailableLogged.compareAndSet(false, true)) {
                        log("WhatsApp feature state unavailable; waiting for broadcast config")
                    }
                }
        }

        fun loadForEarlyHooks(androidContext: Context? = null): WhatsAppFeatureState? {
            WhatsAppFeatureStateStore.current.takeIf { it.source != "unavailable" }?.let { return it }
            return androidContext?.let { loadFromWhatsAppProcessCache(it) }
                ?: loadFromExternalJson()
                ?: loadFromRootJson()
                ?: loadFromXposedPrefs()
                ?: loadFromWorldReadablePrefs()
        }

        fun cacheInWhatsAppProcess(androidContext: Context, json: String) {
            runCatching {
                File(androidContext.filesDir, WHATSAPP_PROCESS_CACHE_FILE).apply {
                    parentFile?.mkdirs()
                    writeText(json, Charsets.UTF_8)
                }
            }
        }

        fun loadAsync(androidContext: Context) {
            Thread {
                repeat(8) { attempt ->
                    if (WhatsAppFeatureStateStore.current.source != "unavailable") return@Thread
                    val state = load(androidContext)
                    if (state.source != "unavailable") {
                        WhatsAppFeatureStateStore.update(state)
                        return@Thread
                    }
                    if (WhatsAppFeatureStateStore.current.source == "unavailable") {
                        WhatsAppFeatureStateStore.update(state)
                    }
                    SystemClock.sleep(1_500L + attempt * 500L)
                }
            }.apply {
                name = "PurrfectWhatsAppConfig"
                isDaemon = true
                start()
            }
        }

        private fun loadFromProvider(androidContext: Context): WhatsAppFeatureState? {
            return runCatching {
                val resolverContext = moduleContext(androidContext) ?: androidContext
                val result = resolverContext.contentResolver.call(
                    Uri.parse("content://$PROVIDER_AUTHORITY"),
                    PROVIDER_METHOD_GET_FEATURES,
                    null,
                    null
                ) ?: return null

                val source = "provider:${result.getString("source", "unknown")}"
                val json = result.getString("json")
                if (!json.isNullOrBlank()) {
                    fromJson(json, source).also { logLoadedState(it) }
                } else {
                    fromBooleanLookup(source) { key, defaultValue ->
                        result.getBoolean(key, defaultValue)
                    }.copy(
                        hiddenUiElementIds = normalizeUiElementIds(result.getString(KEY_HIDDEN_UI_ELEMENT_IDS).orEmpty()),
                        hiddenUiElementSelectors = normalizeUiElementSelectors(result.getString(KEY_HIDDEN_UI_ELEMENT_SELECTORS).orEmpty())
                    ).also { logLoadedState(it) }
                }
            }.onFailure { throwable ->
                if (!throwable.message.orEmpty().contains("Unknown authority")) {
                    log(
                        "WhatsApp config provider unavailable: " +
                            "${throwable.javaClass.simpleName}: ${throwable.message}"
                    )
                }
            }.getOrNull()
        }

        private fun loadFromBridge(androidContext: Context): WhatsAppFeatureState? {
            val bridgeContext = moduleContext(androidContext) ?: androidContext
            var service: BridgeInterface? = null
            val latch = CountDownLatch(1)
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                    service = BridgeInterface.Stub.asInterface(binder)
                    latch.countDown()
                }

                override fun onServiceDisconnected(name: ComponentName) {
                    service = null
                }
            }

            return runCatching {
                runCatching {
                    bridgeContext.startActivity(
                        Intent()
                            .setClassName(Constants.MODULE_PACKAGE_NAME, "me.eternal.purrfect.bridge.ForceStartActivity")
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                    )
                }.onFailure { throwable ->
                    log("Failed to nudge Purrfect bridge process for WhatsApp config: ${throwable.message}")
                }

                val intent = Intent()
                    .setClassName(Constants.MODULE_PACKAGE_NAME, "me.eternal.purrfect.bridge.BridgeService")
                    .setPackage(Constants.MODULE_PACKAGE_NAME)
                val bound = bridgeContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
                if (!bound) return null

                try {
                    if (!latch.await(900L, TimeUnit.MILLISECONDS)) return null
                    val json = service?.whatsAppFeaturesJson ?: return null
                    fromJson(json, "bridge").also { logLoadedState(it) }
                } finally {
                    runCatching { bridgeContext.unbindService(connection) }
                }
            }.onFailure { throwable ->
                log("Failed to load WhatsApp feature state from bridge: ${throwable.message}")
                runCatching { bridgeContext.unbindService(connection) }
            }.getOrNull()
        }

        private fun loadFromModuleJson(androidContext: Context): WhatsAppFeatureState? {
            val moduleContext = moduleContext(androidContext) ?: return null
            val featureFile = File(moduleContext.filesDir, WHATSAPP_EXTERNAL_FEATURES_FILE)
            readFeatureJsonFile(featureFile, "module-json:${featureFile.absolutePath}")?.let { return it }

            val configFile = File(moduleContext.filesDir, "config.json")
            return readMainConfigJsonFile(configFile, "module-config:${configFile.absolutePath}")
        }

        private fun loadFromWhatsAppProcessCache(androidContext: Context): WhatsAppFeatureState? {
            return readFeatureJsonFile(
                File(androidContext.filesDir, WHATSAPP_PROCESS_CACHE_FILE),
                "whatsapp-process-cache"
            )
        }

        private fun loadFromRootJson(): WhatsAppFeatureState? {
            val featurePaths = listOf(
                "/data/user/0/${BuildConfig.APPLICATION_ID}/$WHATSAPP_FEATURES_FILE",
                "/data/data/${BuildConfig.APPLICATION_ID}/$WHATSAPP_FEATURES_FILE"
            ).distinct()
            featurePaths.forEach { path ->
                readRootFile(path)
                    ?.let { json -> fromJson(json, "root-json:$path").also { logLoadedState(it) } }
                    ?.let { return it }
            }

            val configPaths = listOf(
                "/data/user/0/${BuildConfig.APPLICATION_ID}/files/config.json",
                "/data/data/${BuildConfig.APPLICATION_ID}/files/config.json"
            ).distinct()
            configPaths.forEach { path ->
                readRootFile(path)
                    ?.let { json -> fromMainConfigJson(json, "root-config:$path").also { logLoadedState(it) } }
                    ?.let { return it }
            }

            return null
        }

        private fun readRootFile(path: String): String? {
            return runCatching {
                val process = ProcessBuilder("su", "-c", "cat '$path'")
                    .redirectErrorStream(true)
                    .start()
                val finished = process.waitFor(700L, TimeUnit.MILLISECONDS)
                if (!finished) {
                    process.destroyForcibly()
                    return@runCatching null
                }
                val output = process.inputStream.bufferedReader().use { it.readText().trim() }
                if (process.exitValue() != 0 || output.isBlank() || !output.startsWith("{")) {
                    return@runCatching null
                }
                output
            }.getOrNull()
        }

        private fun loadFromExternalJson(): WhatsAppFeatureState? {
            val candidates = listOf(
                File("/storage/emulated/0/Android/media/${BuildConfig.APPLICATION_ID}/$WHATSAPP_EXTERNAL_FEATURES_FILE"),
                File("/sdcard/Android/media/${BuildConfig.APPLICATION_ID}/$WHATSAPP_EXTERNAL_FEATURES_FILE")
            ).distinctBy { it.absolutePath }

            candidates.forEach { file ->
                readFeatureJsonFile(file, "external-json:${file.absolutePath}")?.let { return it }
            }

            return null
        }

        private fun readFeatureJsonFile(file: File, source: String): WhatsAppFeatureState? {
            return runCatching {
                if (!file.exists()) return@runCatching null
                val json = file.readText(Charsets.UTF_8).trim()
                if (!json.startsWith("{")) return@runCatching null
                fromJson(json, source).also { logLoadedState(it) }
            }.getOrNull()
        }

        private fun readMainConfigJsonFile(file: File, source: String): WhatsAppFeatureState? {
            return runCatching {
                if (!file.exists()) return@runCatching null
                val json = file.readText(Charsets.UTF_8).trim()
                if (!json.startsWith("{")) return@runCatching null
                fromMainConfigJson(json, source).also { logLoadedState(it) }
            }.getOrNull()
        }

        private fun moduleContext(androidContext: Context): Context? {
            return runCatching {
                androidContext.createPackageContext(
                    Constants.MODULE_PACKAGE_NAME,
                    Context.CONTEXT_IGNORE_SECURITY
                )
            }.getOrNull()
        }

        private fun loadFromXposedPrefs(): WhatsAppFeatureState? {
            return runCatching {
                val prefsClass = Class.forName("de.robv.android.xposed.XSharedPreferences")
                val prefs = prefsClass
                    .getConstructor(String::class.java, String::class.java)
                    .newInstance(BuildConfig.APPLICATION_ID, PREFS_NAME)

                runCatching { prefsClass.getMethod("makeWorldReadable").invoke(prefs) }
                runCatching { prefsClass.getMethod("reload").invoke(prefs) }

                val fileMethod = prefsClass.methods.firstOrNull { it.name == "getFile" && it.parameterTypes.isEmpty() }
                val file = fileMethod?.invoke(prefs)
                val exists = file?.javaClass?.getMethod("exists")?.invoke(file) as? Boolean
                if (exists == false) return null

                val getBoolean = prefsClass.getMethod("getBoolean", String::class.java, Boolean::class.javaPrimitiveType)
                val getString = prefsClass.getMethod("getString", String::class.java, String::class.java)
                fromBooleanLookup("XSharedPreferences") { key, defaultValue ->
                    getBoolean.invoke(prefs, key, defaultValue) as Boolean
                }.copy(
                    hiddenUiElementIds = normalizeUiElementIds(getString.invoke(prefs, KEY_HIDDEN_UI_ELEMENT_IDS, "") as? String),
                    hiddenUiElementSelectors = normalizeUiElementSelectors(getString.invoke(prefs, KEY_HIDDEN_UI_ELEMENT_SELECTORS, "") as? String)
                ).also { logLoadedState(it) }
            }.getOrNull()
        }

        private fun loadFromWorldReadablePrefs(): WhatsAppFeatureState? {
            val candidates = listOf(
                File("/data/user/0/${BuildConfig.APPLICATION_ID}/shared_prefs/$PREFS_NAME.xml"),
                File("/data/data/${BuildConfig.APPLICATION_ID}/shared_prefs/$PREFS_NAME.xml")
            ).distinctBy { it.absolutePath }

            candidates.forEach { file ->
                val state = runCatching {
                    if (!file.exists()) return@runCatching null
                    val values = parsePrefs(file)
                    fromBooleanLookup("world-readable:${file.absolutePath}") { key, defaultValue ->
                        values.booleans[key] ?: defaultValue
                    }.copy(
                        hiddenUiElementIds = normalizeUiElementIds(values.strings[KEY_HIDDEN_UI_ELEMENT_IDS]),
                        hiddenUiElementSelectors = normalizeUiElementSelectors(values.strings[KEY_HIDDEN_UI_ELEMENT_SELECTORS])
                    ).also { logLoadedState(it) }
                }.getOrNull()

                if (state != null) return state
            }

            return null
        }

        private data class ParsedPrefs(
            val booleans: Map<String, Boolean>,
            val strings: Map<String, String>
        )

        private fun parsePrefs(file: File): ParsedPrefs {
            val booleans = mutableMapOf<String, Boolean>()
            val strings = mutableMapOf<String, String>()
            file.inputStream().use { input ->
                val parser = XmlPullParserFactory.newInstance().newPullParser()
                parser.setInput(input, "utf-8")

                var event = parser.eventType
                while (event != XmlPullParser.END_DOCUMENT) {
                    if (event == XmlPullParser.START_TAG) {
                        when (parser.name) {
                            "boolean" -> {
                                val name = parser.getAttributeValue(null, "name")
                                val value = parser.getAttributeValue(null, "value")
                                if (name != null && value != null) {
                                    booleans[name] = value.toBooleanStrictOrNull() ?: false
                                }
                            }
                            "string" -> {
                                val name = parser.getAttributeValue(null, "name")
                                if (name != null) {
                                    strings[name] = parser.nextText().orEmpty()
                                }
                            }
                        }
                    }
                    event = parser.next()
                }
            }
            return ParsedPrefs(booleans, strings)
        }

        fun fromJson(json: String, source: String): WhatsAppFeatureState {
            val obj = JSONObject(json)
            return fromBooleanLookup(source) { key, defaultValue ->
                obj.optBoolean(key, defaultValue)
            }.copy(
                hiddenUiElementIds = normalizeUiElementIds(obj.optString(KEY_HIDDEN_UI_ELEMENT_IDS, "")),
                hiddenUiElementSelectors = normalizeUiElementSelectors(obj.optString(KEY_HIDDEN_UI_ELEMENT_SELECTORS, ""))
            )
        }

        private fun fromMainConfigJson(json: String, source: String): WhatsAppFeatureState {
            val whatsapp = JSONObject(json).optJSONObject("whatsapp") ?: JSONObject()
            val properties = whatsapp.optJSONObject("properties") ?: whatsapp
            return fromBooleanLookup(source) { key, defaultValue ->
                readBooleanProperty(properties, key, defaultValue)
            }.copy(
                hiddenUiElementIds = normalizeUiElementIds(readStringProperty(properties, KEY_HIDDEN_UI_ELEMENT_IDS, "")),
                hiddenUiElementSelectors = normalizeUiElementSelectors(readStringProperty(properties, KEY_HIDDEN_UI_ELEMENT_SELECTORS, ""))
            )
        }

        private fun readBooleanProperty(properties: JSONObject, key: String, defaultValue: Boolean): Boolean {
            if (properties.has(key)) return properties.optBoolean(key, defaultValue)
            FEATURE_GROUPS.forEach { group ->
                val nested = properties.optJSONObject(group)
                    ?.optJSONObject("properties")
                    ?.takeIf { it.has(key) }
                    ?.optBoolean(key, defaultValue)
                if (nested != null) return nested
            }
            return defaultValue
        }

        private fun readStringProperty(properties: JSONObject, key: String, defaultValue: String): String {
            if (properties.has(key)) return properties.optString(key, defaultValue)
            FEATURE_GROUPS.forEach { group ->
                val nested = properties.optJSONObject(group)
                    ?.optJSONObject("properties")
                    ?.takeIf { it.has(key) }
                    ?.optString(key, defaultValue)
                if (nested != null) return nested
            }
            return defaultValue
        }

        private fun fromBooleanLookup(
            source: String,
            getBoolean: (key: String, defaultValue: Boolean) -> Boolean
        ): WhatsAppFeatureState {
            return WhatsAppFeatureState(
                hideChannels = getBoolean(KEY_HIDE_CHANNELS, false),
                hideChannelRecommendations = getBoolean(KEY_HIDE_CHANNEL_RECOMMENDATIONS, false),
                hideCommunitiesTab = getBoolean(KEY_HIDE_COMMUNITIES_TAB, false),
                hideTypingIndicators = getBoolean(KEY_HIDE_TYPING_INDICATORS, false),
                hideRecordingAudio = getBoolean(KEY_HIDE_RECORDING_AUDIO, false),
                hideDelivered = getBoolean(KEY_HIDE_DELIVERED, false),
                hideAudioSeen = getBoolean(KEY_HIDE_AUDIO_SEEN, false),
                hideStatusView = getBoolean(KEY_HIDE_STATUS_VIEW, false),
                hideStartChatting = getBoolean(KEY_HIDE_START_CHATTING, false),
                unlimitedViewOnce = getBoolean(KEY_UNLIMITED_VIEW_ONCE, false),
                hideBlueTicks = getBoolean(KEY_HIDE_BLUE_TICKS, false),
                showDeletedMessages = getBoolean(KEY_SHOW_DELETED_MESSAGES, false),
                hideUiElements = getBoolean(KEY_HIDE_UI_ELEMENTS, false),
                captureUiElements = getBoolean(KEY_CAPTURE_UI_ELEMENTS, false),
                liquidClass = getBoolean(KEY_LIQUID_CLASS, false),
                source = source
            )
        }

        private fun normalizeUiElementIds(rawIds: String?): String {
            if (rawIds.isNullOrBlank()) return ""
            return rawIds.lineSequence()
                .map { normalizeUiElementId(it) }
                .filter { it.isNotEmpty() }
                .distinct()
                .joinToString("\n")
        }

        private fun normalizeUiElementId(rawId: String?): String {
            var clean = rawId?.trim().orEmpty()
            if (clean.isEmpty()) return ""
            clean = clean.substringBefore('\t').trim()
            clean = clean.substringBefore(' ').trim()
            val slashIndex = clean.lastIndexOf('/')
            if (slashIndex >= 0 && slashIndex < clean.length - 1) {
                clean = clean.substring(slashIndex + 1).trim()
            }
            val dotIndex = clean.lastIndexOf(".id.")
            if (dotIndex >= 0 && dotIndex + 4 < clean.length) {
                clean = clean.substring(dotIndex + 4).trim()
            }
            return clean
        }

        private fun normalizeUiElementSelectors(rawSelectors: String?): String {
            if (rawSelectors.isNullOrBlank()) return ""
            return rawSelectors.lineSequence()
                .map { it.trim() }
                .filter { it.startsWith("selector:v1|") }
                .distinct()
                .joinToString("\n")
        }

        private fun logLoadedState(state: WhatsAppFeatureState) {
            log(
                "WhatsApp feature state loaded from ${state.source}: " +
                    "hideChannels=${state.hideChannels}, " +
                    "hideChannelRecommendations=${state.hideChannelRecommendations}, " +
                    "hideCommunitiesTab=${state.hideCommunitiesTab}, " +
                    "hideTypingIndicators=${state.hideTypingIndicators}, " +
                    "hideRecordingAudio=${state.hideRecordingAudio}, " +
                    "hideDelivered=${state.hideDelivered}, " +
                    "hideAudioSeen=${state.hideAudioSeen}, " +
                    "hideStatusView=${state.hideStatusView}, " +
                    "hideStartChatting=${state.hideStartChatting}, " +
                    "unlimitedViewOnce=${state.unlimitedViewOnce}, " +
                    "hideBlueTicks=${state.hideBlueTicks}, " +
                    "showDeletedMessages=${state.showDeletedMessages}, " +
                    "hideUiElements=${state.hideUiElements}, " +
                    "captureUiElements=${state.captureUiElements}, " +
                    "liquidClass=${state.liquidClass}, " +
                    "hiddenUiElementIds=${state.hiddenUiElementIds.lineSequence().count { it.isNotBlank() }}, " +
                    "hiddenUiElementSelectors=${state.hiddenUiElementSelectors.lineSequence().count { it.isNotBlank() }}"
            )
        }

        private fun log(message: String) {
            XposedBridge.log("[$TAG] $message")
            CoreLogger.xposedLog(message, TAG)
            WhatsAppAppLogWriter.info(null, TAG, message)
        }

        private val FEATURE_GROUPS = listOf("channels", "privacy", "messages", "ui_elements")
    }
}

object WhatsAppFeatureStateStore {
    @Volatile
    var current: WhatsAppFeatureState = WhatsAppFeatureState()
        private set

    fun update(state: WhatsAppFeatureState) {
        current = state
    }
}
