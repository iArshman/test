package me.eternal.purrfect.core.reddit

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import de.robv.android.xposed.XposedBridge
import me.eternal.purrfect.common.Constants
import me.eternal.purrfect.core.ModContext
import me.eternal.purrfect.core.Purrfect
import me.eternal.purrfect.core.bridge.BridgeClient
import me.eternal.purrfect.core.logger.CoreLogger
import kotlin.system.exitProcess

class RedditRuntime {
    fun init(androidContext: Context, appClassLoader: ClassLoader = androidContext.classLoader) {
        XposedBridge.log("[${RedditAdBlockHooks.TAG}] Runtime attached to ${androidContext.packageName}")
        CoreLogger.xposedLog("Purrfect Reddit runtime attached to ${androidContext.packageName}", RedditAdBlockHooks.TAG)
        val context = ModContext(androidContext, Purrfect())
        context.bridgeClient = BridgeClient(context)
        XposedBridge.log("[${RedditAdBlockHooks.TAG}] Installing Reddit hooks without bridge dependency")
        CoreLogger.xposedLog("Installing Reddit hooks with lightweight config bridge read", RedditAdBlockHooks.TAG)
        registerConfigBroadcastReceiver(androidContext)
        registerForceStopReceiver(androidContext)
        RedditFeatureStateStore.update(RedditFeatureState.load(androidContext))
        requestConfigBroadcast(androidContext)
        RedditFeatureState.loadAsync(androidContext)
        RedditAdBlockHooks(context, appClassLoader).init()
    }

    private fun registerConfigBroadcastReceiver(androidContext: Context) {
        val filter = IntentFilter(Constants.REDDIT_CONFIG_UPDATE_ACTION)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val json = intent.getStringExtra(Constants.REDDIT_CONFIG_JSON_EXTRA)
                if (json.isNullOrBlank()) {
                    log("Received empty Reddit feature config broadcast")
                    return
                }
                runCatching {
                    val state = RedditFeatureState.fromJson(json, "broadcast")
                    RedditFeatureStateStore.update(state)
                    log(
                        "Reddit feature state loaded from broadcast: " +
                            "blockPromotedPosts=${state.blockPromotedPosts}, blockCommentAds=${state.blockCommentAds}, " +
                            "unlockRedditPremium=${state.unlockRedditPremium}, " +
                            "openLinksInExternalBrowser=${state.openLinksInExternalBrowser}, " +
                            "disableScreenshotPopup=${state.disableScreenshotPopup}, " +
                            "hideCreateButton=${state.hideCreateButton}, " +
                            "hideTrendingTodayShelf=${state.hideTrendingTodayShelf}, " +
                            "addScrollToTopButton=${state.addScrollToTopButton}"
                    )
                }.onFailure { throwable ->
                    log("Failed to parse Reddit feature config broadcast: ${throwable.stackTraceToString()}")
                }
            }
        }

        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                androidContext.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                androidContext.registerReceiver(receiver, filter)
            }
            log("Registered Reddit feature config broadcast receiver")
        }.onFailure { throwable ->
            log("Failed to register Reddit feature config broadcast receiver: ${throwable.stackTraceToString()}")
        }
    }

    private fun registerForceStopReceiver(androidContext: Context) {
        val filter = IntentFilter(Constants.REDDIT_FORCE_STOP_ACTION)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                log("Received Reddit close signal; terminating Reddit process")
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
            log("Registered Reddit force-stop broadcast receiver")
        }.onFailure { throwable ->
            log("Failed to register Reddit force-stop broadcast receiver: ${throwable.stackTraceToString()}")
        }
    }

    private fun requestConfigBroadcast(androidContext: Context) {
        runCatching {
            val request = Intent(Constants.REDDIT_CONFIG_REQUEST_ACTION)
            androidContext.sendBroadcast(request)
            androidContext.sendBroadcast(
                Intent(Constants.REDDIT_CONFIG_REQUEST_ACTION)
                    .setClassName(
                        Constants.MODULE_PACKAGE_NAME,
                        "me.eternal.purrfect.reddit.RedditConfigBroadcastReceiver"
                    )
            )
            log("Requested Reddit feature config broadcast from Purrfect")
        }.onFailure { throwable ->
            log("Failed to request Reddit feature config broadcast: ${throwable.stackTraceToString()}")
        }
    }

    private fun log(message: String) {
        XposedBridge.log("[${RedditAdBlockHooks.TAG}] $message")
        CoreLogger.xposedLog(message, RedditAdBlockHooks.TAG)
        RedditAppLogWriter.info(null, RedditAdBlockHooks.TAG, message)
    }
}
