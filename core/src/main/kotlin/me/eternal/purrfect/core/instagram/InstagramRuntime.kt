package me.eternal.purrfect.core.instagram

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import de.robv.android.xposed.XposedBridge
import me.eternal.purrfect.common.Constants
import me.eternal.purrfect.core.logger.CoreLogger
import java.io.File
import kotlin.system.exitProcess

class InstagramRuntime(
    private val sourceApkPath: String? = null,
    private val moduleSourcePath: String? = null
) {
    fun init(androidContext: Context, appClassLoader: ClassLoader = androidContext.classLoader) {
        InstagramAppLogWriter.bind(androidContext)
        InstagramDexKitCache.init(androidContext)
        InstagramFeatureStateStore.update(InstagramFeatureState.load(androidContext))
        log(androidContext, "PurrfectInsta runtime attached to ${androidContext.packageName}")
        registerConfigBroadcastReceiver(androidContext)
        registerDevConfigBroadcastReceiver(androidContext)
        registerForceStopReceiver(androidContext)
        requestConfigBroadcast(androidContext)
        InstagramFeatureState.loadAsync(androidContext)
        InstagramHooks(androidContext, appClassLoader, sourceApkPath, moduleSourcePath).init()
    }

    private fun registerConfigBroadcastReceiver(androidContext: Context) {
        val filter = IntentFilter(Constants.INSTAGRAM_CONFIG_UPDATE_ACTION)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val json = intent.getStringExtra(Constants.INSTAGRAM_CONFIG_JSON_EXTRA)
                if (json.isNullOrBlank()) {
                    log(androidContext, "Received empty Instagram feature config broadcast")
                    return
                }
                runCatching {
                    val previous = InstagramFeatureStateStore.current
                    val state = InstagramFeatureState.fromJson(json, "broadcast")
                    InstagramFeatureState.cacheInInstagramProcess(androidContext, json)
                    if (state.copy(source = previous.source) == previous) return
                    InstagramFeatureStateStore.update(state)
                    InstagramCustomEmojiFontHooks.syncAfterStateUpdate(androidContext, previous, state)
                    log(androidContext, "Instagram feature state loaded from broadcast")
                    InstagramHooks.refreshActiveHooks("broadcast")
                }.onFailure { throwable ->
                    log(androidContext, "Failed to parse Instagram feature config broadcast: ${throwable.stackTraceToString()}")
                }
            }
        }

        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                androidContext.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                androidContext.registerReceiver(receiver, filter)
            }
            log(androidContext, "Registered Instagram feature config broadcast receiver")
        }.onFailure { throwable ->
            log(androidContext, "Failed to register Instagram feature config broadcast receiver: ${throwable.stackTraceToString()}")
        }
    }

    private fun registerDevConfigBroadcastReceiver(androidContext: Context) {
        val filter = IntentFilter().apply {
            addAction(Constants.INSTAGRAM_CONFIG_JSON_IMPORT_ACTION)
            addAction(Constants.INSTAGRAM_CONFIG_JSON_EXPORT_ACTION)
            addAction(Constants.INSTAGRAM_DEV_CONFIG_IMPORT_ACTION)
            addAction(Constants.INSTAGRAM_DEV_CONFIG_EXPORT_REQUEST_ACTION)
            addAction(Constants.INSTAGRAM_DEV_CONFIG_RESET_ACTION)
            addAction(Constants.INSTAGRAM_SETTINGS_RESTORE_ACTION)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val requestId = intent.getStringExtra("request_id") ?: "-"
                log(
                    androidContext,
                    "Developer receiver got action=${intent.action} requestId=$requestId package=${androidContext.packageName} extras=${intent.extras?.keySet()?.joinToString().orEmpty()}"
                )
                when (intent.action) {
                    Constants.INSTAGRAM_CONFIG_JSON_IMPORT_ACTION,
                    Constants.INSTAGRAM_DEV_CONFIG_IMPORT_ACTION -> {
                        val json = (intent.getStringExtra(Constants.INSTAGRAM_DEV_CONFIG_JSON_EXTRA)
                            ?: intent.getStringExtra(Constants.INSTAGRAM_CONFIG_JSON_EXTRA)
                            ?: intent.getStringExtra("json_content"))
                            .orEmpty()
                            .trim()
                        importMobileConfigOverride(androidContext, json)
                    }
                    Constants.INSTAGRAM_CONFIG_JSON_EXPORT_ACTION,
                    Constants.INSTAGRAM_DEV_CONFIG_EXPORT_REQUEST_ACTION -> {
                        exportMobileConfigOverride(androidContext)
                    }
                    Constants.INSTAGRAM_DEV_CONFIG_RESET_ACTION -> {
                        resetMobileConfigOverride(androidContext)
                    }
                    Constants.INSTAGRAM_SETTINGS_RESTORE_ACTION -> {
                        val json = (intent.getStringExtra(Constants.INSTAGRAM_DEV_CONFIG_JSON_EXTRA)
                            ?: intent.getStringExtra("json_content"))
                            .orEmpty()
                            .trim()
                        restoreSettingsBackup(androidContext, json)
                    }
                }
            }
        }

        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                androidContext.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                androidContext.registerReceiver(receiver, filter)
            }
            log(androidContext, "Registered Instagram developer config broadcast receiver")
        }.onFailure { throwable ->
            log(androidContext, "Failed to register developer config receiver: ${throwable.stackTraceToString()}")
        }
    }

    private fun resetMobileConfigOverride(androidContext: Context) {
        Thread {
            runCatching {
                val source = File(androidContext.filesDir, "mobileconfig/mc_overrides.json")
                if (source.exists() && !source.delete()) error("Unable to delete mc_overrides.json")
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(androidContext.applicationContext, "Dev Config reset.", Toast.LENGTH_LONG).show()
                }
                log(androidContext, "Developer config reset; deleted mobileconfig/mc_overrides.json")
            }.onFailure { throwable ->
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(androidContext.applicationContext, "Reset failed: ${throwable.message}", Toast.LENGTH_LONG).show()
                }
                log(androidContext, "Developer config reset failed: ${throwable.message}")
            }
        }.apply {
            name = "PurrfectInstaDevConfigReset"
            isDaemon = true
            start()
        }
    }

    private fun importMobileConfigOverride(androidContext: Context, json: String) {
        Thread {
            runCatching {
                if (!json.startsWith("{") || !json.endsWith("}")) error("Not valid JSON")
                val dest = File(androidContext.filesDir, "mobileconfig/mc_overrides.json")
                dest.parentFile?.mkdirs()
                dest.writeText(json, Charsets.UTF_8)
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(androidContext.applicationContext, "Config imported.", Toast.LENGTH_LONG).show()
                }
                log(androidContext, "JSON imported into mobileconfig/mc_overrides.json")
            }.onFailure { throwable ->
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(androidContext.applicationContext, "Failed to import config.", Toast.LENGTH_LONG).show()
                }
                log(androidContext, "Developer config import failed: ${throwable.message}")
            }
        }.apply {
            name = "PurrfectInstaDevConfigImport"
            isDaemon = true
            start()
        }
    }

    private fun exportMobileConfigOverride(androidContext: Context) {
        val reply = Intent(Constants.INSTAGRAM_DEV_CONFIG_SEND_ACTION).setPackage(Constants.MODULE_PACKAGE_NAME)
        runCatching {
            val source = File(androidContext.filesDir, "mobileconfig/mc_overrides.json")
            if (!source.exists()) {
                val error = "mc_overrides.json not found."
                reply.putExtra(Constants.INSTAGRAM_DEV_CONFIG_ERROR_EXTRA, error)
                reply.putExtra("error", error)
            } else {
                val json = source.readText(Charsets.UTF_8).trim()
                reply.putExtra(Constants.INSTAGRAM_DEV_CONFIG_JSON_EXTRA, json)
                reply.putExtra("json_content", json)
            }
            androidContext.sendBroadcast(reply)
            log(androidContext, "Developer config export reply sent")
        }.onFailure { throwable ->
            val error = throwable.message ?: "Export failed"
            reply.putExtra(Constants.INSTAGRAM_DEV_CONFIG_ERROR_EXTRA, error)
            reply.putExtra("error", error)
            androidContext.sendBroadcast(reply)
            log(androidContext, "Developer config export failed: ${throwable.message}")
        }
    }

    private fun restoreSettingsBackup(androidContext: Context, json: String) {
        Thread {
            runCatching {
                if (!json.startsWith("{") || !json.endsWith("}")) error("Not valid JSON")
                val (state, settings) = InstagramSettingsBackup.restoredStateFromJson(json, InstagramFeatureStateStore.current)
                val previous = InstagramFeatureStateStore.current
                val stateJson = InstagramSettingsBackup.featureJson(state)
                InstagramFeatureStateStore.update(state)
                InstagramFeatureState.cacheInInstagramProcess(androidContext, stateJson)
                InstagramCustomEmojiFontHooks.syncAfterStateUpdate(androidContext, previous, state)
                InstagramSettingsBackup.forEachKnownSetting(settings) { key, value, isString ->
                    val update = Intent(Constants.INSTAGRAM_FEATURE_PREF_UPDATE_ACTION)
                        .setPackage(Constants.MODULE_PACKAGE_NAME)
                        .putExtra(Constants.INSTAGRAM_FEATURE_PREF_KEY_EXTRA, key)
                        .putExtra(Constants.INSTAGRAM_FEATURE_PREF_IS_STRING_EXTRA, isString)
                    if (isString) {
                        update.putExtra(Constants.INSTAGRAM_FEATURE_PREF_STRING_EXTRA, value as? String ?: value.toString())
                    } else {
                        update.putExtra(Constants.INSTAGRAM_FEATURE_PREF_BOOLEAN_EXTRA, value as? Boolean ?: false)
                    }
                    androidContext.sendBroadcast(update)
                }
                InstagramHooks.refreshActiveHooks("settings-restore")
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(androidContext.applicationContext, "Settings restored successfully.", Toast.LENGTH_SHORT).show()
                }
                log(androidContext, "Settings backup restored")
            }.onFailure { throwable ->
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(androidContext.applicationContext, "Restore failed: ${throwable.message}", Toast.LENGTH_LONG).show()
                }
                log(androidContext, "Settings restore failed: ${throwable.message}")
            }
        }.apply {
            name = "PurrfectInstaSettingsRestore"
            isDaemon = true
            start()
        }
    }

    private fun registerForceStopReceiver(androidContext: Context) {
        val filter = IntentFilter(Constants.INSTAGRAM_FORCE_STOP_ACTION)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                log(androidContext, "Received Instagram close signal; terminating Instagram process")
                Handler(Looper.getMainLooper()).post {
                    android.os.Process.killProcess(android.os.Process.myPid())
                    exitProcess(0)
                }
            }
        }

        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                androidContext.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                androidContext.registerReceiver(receiver, filter)
            }
            log(androidContext, "Registered Instagram force-stop broadcast receiver")
        }.onFailure { throwable ->
            log(androidContext, "Failed to register Instagram force-stop broadcast receiver: ${throwable.stackTraceToString()}")
        }
    }

    private fun requestConfigBroadcast(androidContext: Context) {
        runCatching {
            androidContext.sendBroadcast(Intent(Constants.INSTAGRAM_CONFIG_REQUEST_ACTION))
            androidContext.sendBroadcast(
                Intent(Constants.INSTAGRAM_CONFIG_REQUEST_ACTION)
                    .setClassName(
                        Constants.MODULE_PACKAGE_NAME,
                        "me.eternal.purrfect.instagram.InstagramConfigBroadcastReceiver"
                    )
            )
            log(androidContext, "Requested Instagram feature config broadcast from Purrfect")
        }.onFailure { throwable ->
            log(androidContext, "Failed to request Instagram feature config broadcast: ${throwable.stackTraceToString()}")
        }
    }

    private fun log(androidContext: Context, message: String) {
        XposedBridge.log("[${InstagramFeatureState.TAG}] $message")
        CoreLogger.xposedLog(message, InstagramFeatureState.TAG)
        InstagramAppLogWriter.info(androidContext, InstagramFeatureState.TAG, message)
    }
}
