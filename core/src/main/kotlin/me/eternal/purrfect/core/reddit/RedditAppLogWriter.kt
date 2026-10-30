package me.eternal.purrfect.core.reddit

import android.content.Context
import android.content.Intent
import me.eternal.purrfect.common.BuildConfig
import me.eternal.purrfect.common.logger.LogLevel
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

internal object RedditAppLogWriter {
    private const val MAX_LOG_BYTES = 1024 * 1024L
    private const val ACTION_REDDIT_LOG = "me.eternal.purrfect.REDDIT_LOG"
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    fun info(context: Context?, tag: String, message: String) {
        write(context, LogLevel.INFO, tag, message)
    }

    fun error(context: Context?, tag: String, message: String) {
        write(context, LogLevel.ERROR, tag, message)
    }

    private fun write(context: Context?, level: LogLevel, tag: String, message: String) {
        broadcast(context, level, tag, message)
        runCatching {
            val logDir = File("/storage/emulated/0/Android/media/${BuildConfig.APPLICATION_ID}/logs")
            if (!logDir.exists() && !logDir.mkdirs()) return
            val logFile = File(logDir, "reddit_xposed.log")
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
        runCatching {
            context?.sendBroadcast(
                Intent(ACTION_REDDIT_LOG)
                    .setPackage(BuildConfig.APPLICATION_ID)
                    .putExtra("level", level.letter)
                    .putExtra("tag", tag)
                    .putExtra("message", message.take(4096))
            )
        }
    }
}
