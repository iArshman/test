package me.eternal.purrfect.core.whatsapp

import android.content.Context
import android.content.Intent
import me.eternal.purrfect.common.BuildConfig
import me.eternal.purrfect.common.logger.LogLevel
import java.io.File
import java.util.ArrayDeque
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

internal object WhatsAppAppLogWriter {
    private const val MAX_LOG_BYTES = 1024 * 1024L
    private const val MAX_PENDING_BROADCASTS = 200
    const val ACTION_WHATSAPP_LOG = "me.eternal.purrfect.WHATSAPP_LOG"
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private data class PendingBroadcast(val level: LogLevel, val tag: String, val message: String)

    private val pendingBroadcasts = ArrayDeque<PendingBroadcast>()
    @Volatile
    private var broadcastContext: Context? = null

    fun bind(context: Context) {
        val appContext = context.applicationContext ?: context
        broadcastContext = appContext
        val pending = synchronized(pendingBroadcasts) {
            pendingBroadcasts.toList().also { pendingBroadcasts.clear() }
        }
        pending.forEach { broadcast(appContext, it.level, it.tag, it.message) }
    }

    fun info(context: Context?, tag: String, message: String) {
        write(context, LogLevel.INFO, tag, message)
    }

    fun error(context: Context?, tag: String, message: String) {
        write(context, LogLevel.ERROR, tag, message)
    }

    private fun write(context: Context?, level: LogLevel, tag: String, message: String) {
        broadcast(context ?: broadcastContext, level, tag, message)
        runCatching {
            val logDir = File("/storage/emulated/0/Android/media/${BuildConfig.APPLICATION_ID}/logs")
            if (!logDir.exists() && !logDir.mkdirs()) return
            val logFile = File(logDir, "whatsapp_xposed.log")
            if (logFile.length() > MAX_LOG_BYTES) logFile.delete()
            val sanitized = message
                .replace('\n', ' ')
                .replace('\r', ' ')
                .replace('/', '\u2215')
                .take(4096)
            logFile.appendText(
                "|${level.letter}/${LocalDateTime.now().format(dateFormatter)}/$tag/$sanitized\n",
                Charsets.UTF_8
            )
        }
    }

    private fun broadcast(context: Context?, level: LogLevel, tag: String, message: String) {
        val targetContext = context ?: broadcastContext
        if (targetContext == null) {
            synchronized(pendingBroadcasts) {
                if (pendingBroadcasts.size >= MAX_PENDING_BROADCASTS) {
                    pendingBroadcasts.removeFirst()
                }
                pendingBroadcasts.addLast(PendingBroadcast(level, tag, message.take(4096)))
            }
            return
        }

        runCatching {
            targetContext.sendBroadcast(
                Intent(ACTION_WHATSAPP_LOG)
                    .setPackage(BuildConfig.APPLICATION_ID)
                    .putExtra("level", level.letter)
                    .putExtra("tag", tag)
                    .putExtra("message", message.take(4096))
            )
        }
    }
}
