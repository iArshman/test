package me.eternal.purrfect.core.instagram

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import me.eternal.purrfect.core.logger.CoreLogger
import java.util.concurrent.atomic.AtomicBoolean

internal object InstagramShareSheetGroupCreationHooks {
    private val installed = AtomicBoolean(false)
    private const val TAG = "Purrfect-ShareSheetGroup"

    private fun log(msg: String) {
        CoreLogger.xposedLog(msg, TAG)
    }

    fun install(classLoader: ClassLoader) {
        if (!installed.compareAndSet(false, true)) return
        try {
            val binderClass = Class.forName(
                "com.instagram.direct.fragment.sharesheet.view.DirectShareSheetFragmentMessageComposerViewBinder",
                false,
                classLoader
            )
            
            // The target method takes (List, boolean, UserSession) and returns void
            val targetMethod = binderClass.declaredMethods.firstOrNull { method ->
                val params = method.parameterTypes
                params.size == 3 &&
                    params[0] == java.util.List::class.java &&
                    (params[1] == Boolean::class.javaPrimitiveType || params[1] == Boolean::class.javaObjectType) &&
                    params[2].name.endsWith("UserSession") &&
                    method.returnType == Void.TYPE
            }

            if (targetMethod != null) {
                XposedBridge.hookMethod(targetMethod, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam<*>) {
                        if (InstagramFeatureStateStore.current.disableGroupCreationFromShareSheet) {
                            param.result = null
                        }
                    }
                })
                log("Installed share-sheet group creation disable hook")
            } else {
                installed.set(false)
                log("Failed to find target method for share-sheet group creation disable hook")
            }
        } catch (throwable: Throwable) {
            installed.set(false)
            log("Share-sheet group creation disable hook failed: ${throwable.message}")
        }
    }
}
