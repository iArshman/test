package me.eternal.purrfect.core.logger

import android.annotation.SuppressLint
import android.util.Log
import me.eternal.purrfect.common.logger.AbstractLogger
import me.eternal.purrfect.common.logger.LogChannel
import me.eternal.purrfect.common.logger.LogLevel
import me.eternal.purrfect.core.bridge.BridgeClient
import me.eternal.purrfect.core.util.hook.HookStage
import me.eternal.purrfect.core.util.hook.YukiHookCompat
import me.eternal.purrfect.core.util.hook.hook

@SuppressLint("PrivateApi")
class CoreLogger(
    private val bridgeClient: BridgeClient
) : AbstractLogger(LogChannel.CORE) {

    companion object {
        private const val TAG = "PurrfectCore"

        fun xposedLog(message: Any?, tag: String = TAG) {
            Log.println(Log.INFO, tag, message.toString())
        }

        fun xposedLog(message: Any?, throwable: Throwable, tag: String = TAG) {
            Log.println(Log.INFO, tag, "${message}\n${throwable.stackTraceToString()}")
        }
    }

    private var invokeOriginalPrintLog: (Int, String, String) -> Unit

    init {
        val printLnMethod = Log::class.java.getDeclaredMethod(
            "println",
            Int::class.java,
            String::class.java,
            String::class.java
        )
        printLnMethod.hook(HookStage.BEFORE) { param ->
            val priority = param.arg(0) as Int
            val tag = param.arg(1) as String
            val message = param.arg(2) as String
            internalLog(tag, LogLevel.fromPriority(priority) ?: LogLevel.INFO, message)
        }
        invokeOriginalPrintLog = { priority, tag, message ->
            YukiHookCompat.invokeOriginal(
                printLnMethod,
                null,
                arrayOf<Any?>(priority, tag, message)
            )
        }
    }

    private fun internalLog(tag: String, logLevel: LogLevel, message: Any?) {
        runCatching {
            bridgeClient.broadcastLog(tag, logLevel.shortName, message.toString())
        }.onFailure {
            invokeOriginalPrintLog(logLevel.priority, tag, message.toString())
        }
    }

    override fun debug(message: Any?, tag: String) = internalLog(tag, LogLevel.DEBUG, message)
    override fun error(message: Any?, tag: String) = internalLog(tag, LogLevel.ERROR, message)
    override fun error(message: Any?, throwable: Throwable, tag: String) {
        internalLog(tag, LogLevel.ERROR, message)
        internalLog(tag, LogLevel.ERROR, throwable.stackTraceToString())
    }
    override fun info(message: Any?, tag: String) = internalLog(tag, LogLevel.INFO, message)
    override fun verbose(message: Any?, tag: String) = internalLog(tag, LogLevel.VERBOSE, message)
    override fun warn(message: Any?, tag: String) = internalLog(tag, LogLevel.WARN, message)
    override fun assert(message: Any?, tag: String) = internalLog(tag, LogLevel.ASSERT, message)
}
