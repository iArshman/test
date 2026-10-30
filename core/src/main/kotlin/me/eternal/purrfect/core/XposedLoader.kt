package me.eternal.purrfect.core

import android.app.Application
import android.content.Context
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import me.eternal.purrfect.common.BuildConfig
import me.eternal.purrfect.common.Constants
import me.eternal.purrfect.core.instagram.InstagramRuntime
import me.eternal.purrfect.core.reddit.RedditRuntime
import me.eternal.purrfect.core.util.hook.HookStage
import me.eternal.purrfect.core.util.hook.hook
import me.eternal.purrfect.core.whatsapp.WhatsAppDetectionHooks
import me.eternal.purrfect.core.whatsapp.WhatsAppRuntime
import java.util.concurrent.atomic.AtomicBoolean

class XposedLoader : IXposedHookLoadPackage, IXposedHookZygoteInit {
    companion object {
        @Volatile
        private var moduleSourcePath: String? = null
    }

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        moduleSourcePath = startupParam.modulePath
    }

    override fun handleLoadPackage(param: XC_LoadPackage.LoadPackageParam) {
        if (param.packageName !in Constants.HOOK_TARGET_PACKAGES) return
        if (param.processName.contains(":")) return

        if (param.packageName == Constants.SNAPCHAT_PACKAGE_NAME) {
            XposedBridge.log(
                "Loading Purrfect v${BuildConfig.VERSION_NAME}#${BuildConfig.GIT_HASH} (package: ${BuildConfig.APPLICATION_ID})"
            )
            Application::class.java.hook("attach", HookStage.BEFORE) { hookParam ->
                Purrfect().init(hookParam.arg(0))
            }
            return
        }

        XposedBridge.log(
            "Loading Purrfect v${BuildConfig.VERSION_NAME}#${BuildConfig.GIT_HASH} into ${param.packageName} (package: ${BuildConfig.APPLICATION_ID})"
        )

        if (param.packageName == Constants.WHATSAPP_PACKAGE_NAME) {
            WhatsAppDetectionHooks.installEarly(param.classLoader)
        }

        val initialized = AtomicBoolean(false)

        fun initFromContext(source: String, context: Context) {
            if (!initialized.compareAndSet(false, true)) {
                XposedBridge.log("Purrfect already initialized for ${param.packageName}; ignored $source")
                return
            }
            runCatching {
                XposedBridge.log("Purrfect $source for ${param.packageName}")
                when (param.packageName) {
                    Constants.REDDIT_PACKAGE_NAME -> RedditRuntime().init(context, param.classLoader)
                    Constants.WHATSAPP_PACKAGE_NAME -> WhatsAppRuntime().init(context, param.classLoader)
                    in Constants.INSTAGRAM_PACKAGE_NAMES -> InstagramRuntime(param.appInfo.sourceDir, moduleSourcePath).init(context, param.classLoader)
                }
            }.onFailure { throwable ->
                initialized.set(false)
                XposedBridge.log("Purrfect failed during $source for ${param.packageName}: ${throwable.stackTraceToString()}")
            }
        }

        XposedBridge.hookAllMethods(
            Application::class.java,
            "attach",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(hookParam: MethodHookParam<*>) {
                    val context = hookParam.args.getOrNull(0) as? Context ?: return
                    initFromContext("Application.attach", context)
                }
            }
        )

        XposedBridge.hookAllMethods(
            Application::class.java,
            "onCreate",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(hookParam: MethodHookParam<*>) {
                    val application = hookParam.thisObject as? Application ?: return
                    initFromContext("Application.onCreate", application)
                }
            }
        )

        if (param.packageName == Constants.REDDIT_PACKAGE_NAME) {
            runCatching {
                val redditApplicationClass = param.classLoader.loadClass("com.reddit.frontpage.FrontpageApplication")
                XposedBridge.log("Purrfect found Reddit application class: ${redditApplicationClass.name}")
                XposedBridge.hookAllMethods(
                    redditApplicationClass,
                    "onCreate",
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(hookParam: MethodHookParam<*>) {
                            val application = hookParam.thisObject as? Application ?: return
                            initFromContext("FrontpageApplication.onCreate", application)
                        }
                    }
                )
            }.onFailure { throwable ->
                XposedBridge.log("Purrfect could not install Reddit application fallback hook: ${throwable.stackTraceToString()}")
            }
        }

        if (param.packageName == Constants.WHATSAPP_PACKAGE_NAME) {
            runCatching {
                val whatsAppShellClass = param.classLoader.loadClass("com.whatsapp.AppShell")
                XposedBridge.log("Purrfect found WhatsApp application class: ${whatsAppShellClass.name}")
                XposedBridge.hookAllMethods(
                    whatsAppShellClass,
                    "onCreate",
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(hookParam: MethodHookParam<*>) {
                            val application = hookParam.thisObject as? Application ?: return
                            initFromContext("AppShell.onCreate", application)
                        }
                    }
                )
            }.onFailure { throwable ->
                XposedBridge.log("Purrfect could not install WhatsApp application fallback hook: ${throwable.stackTraceToString()}")
            }
        }

    }
}
