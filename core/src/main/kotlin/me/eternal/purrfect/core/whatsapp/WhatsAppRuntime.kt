package me.eternal.purrfect.core.whatsapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import de.robv.android.xposed.XposedBridge
import me.eternal.purrfect.common.Constants
import me.eternal.purrfect.core.logger.CoreLogger
import kotlin.system.exitProcess

class WhatsAppRuntime {
    fun init(androidContext: Context, appClassLoader: ClassLoader = androidContext.classLoader) {
        WhatsAppAppLogWriter.bind(androidContext)
        WhatsAppFeatureStateStore.update(WhatsAppFeatureState.load(androidContext))
        WhatsAppDetectionHooks.installRuntime(appClassLoader)
        XposedBridge.log("[${WhatsAppChannelHooks.TAG}] Runtime attached to ${androidContext.packageName}")
        CoreLogger.xposedLog("Purrfect WhatsApp runtime attached to ${androidContext.packageName}", WhatsAppChannelHooks.TAG)
        registerConfigBroadcastReceiver(androidContext, appClassLoader)
        registerForceStopReceiver(androidContext)
        requestConfigBroadcast(androidContext)
        WhatsAppFeatureState.loadAsync(androidContext)
        WhatsAppChannelHooks(androidContext, appClassLoader).init()
        WhatsAppPrivacyHooks(androidContext, appClassLoader).init()
        WhatsAppLiquidGlassHooks(androidContext, appClassLoader).init()
        WhatsAppUiElementHooks(androidContext, appClassLoader).init()
    }

    private fun registerConfigBroadcastReceiver(androidContext: Context, appClassLoader: ClassLoader) {
        val filter = IntentFilter(Constants.WHATSAPP_CONFIG_UPDATE_ACTION)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val json = intent.getStringExtra(Constants.WHATSAPP_CONFIG_JSON_EXTRA)
                if (json.isNullOrBlank()) {
                    log(androidContext, "Received empty WhatsApp feature config broadcast")
                    return
                }
                runCatching {
                    val state = WhatsAppFeatureState.fromJson(json, "broadcast")
                    WhatsAppFeatureState.cacheInWhatsAppProcess(androidContext, json)
                    WhatsAppFeatureStateStore.update(state)
                    log(
                        androidContext,
                        "WhatsApp feature state loaded from broadcast: ${describe(state)}"
                    )
                    WhatsAppChannelHooks.refreshActiveHooks("broadcast")
                    WhatsAppPrivacyHooks.refreshActiveHooks("broadcast")
                    WhatsAppLiquidGlassHooks.refreshActiveHooks("broadcast")
                    WhatsAppUiElementHooks.refreshActiveHooks("broadcast")
                    WhatsAppDetectionHooks.installRuntime(appClassLoader)
                }.onFailure { throwable ->
                    log(androidContext, "Failed to parse WhatsApp feature config broadcast: ${throwable.stackTraceToString()}")
                }
            }
        }

        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                androidContext.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                androidContext.registerReceiver(receiver, filter)
            }
            log(androidContext, "Registered WhatsApp feature config broadcast receiver")
        }.onFailure { throwable ->
            log(androidContext, "Failed to register WhatsApp feature config broadcast receiver: ${throwable.stackTraceToString()}")
        }
    }

    private fun registerForceStopReceiver(androidContext: Context) {
        val filter = IntentFilter(Constants.WHATSAPP_FORCE_STOP_ACTION)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                log(androidContext, "Received WhatsApp close signal; terminating WhatsApp process")
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
            log(androidContext, "Registered WhatsApp force-stop broadcast receiver")
        }.onFailure { throwable ->
            log(androidContext, "Failed to register WhatsApp force-stop broadcast receiver: ${throwable.stackTraceToString()}")
        }
    }

    private fun requestConfigBroadcast(androidContext: Context) {
        runCatching {
            androidContext.sendBroadcast(Intent(Constants.WHATSAPP_CONFIG_REQUEST_ACTION))
            androidContext.sendBroadcast(
                Intent(Constants.WHATSAPP_CONFIG_REQUEST_ACTION)
                    .setClassName(
                        Constants.MODULE_PACKAGE_NAME,
                        "me.eternal.purrfect.whatsapp.WhatsAppConfigBroadcastReceiver"
                    )
            )
            log(androidContext, "Requested WhatsApp feature config broadcast from Purrfect")
        }.onFailure { throwable ->
            log(androidContext, "Failed to request WhatsApp feature config broadcast: ${throwable.stackTraceToString()}")
        }
    }

    private fun log(androidContext: Context, message: String) {
        XposedBridge.log("[${WhatsAppChannelHooks.TAG}] $message")
        CoreLogger.xposedLog(message, WhatsAppChannelHooks.TAG)
        WhatsAppAppLogWriter.info(androidContext, WhatsAppChannelHooks.TAG, message)
    }

    private fun describe(state: WhatsAppFeatureState): String {
        return "hideChannels=${state.hideChannels}, " +
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
    }
}
