package me.eternal.purrfect.bridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import me.eternal.purrfect.SharedContextHolder
import me.eternal.purrfect.common.logger.LogLevel

class InstagramLogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_INSTAGRAM_LOG) return
        val tag = intent.getStringExtra("tag") ?: "PurrfectInsta"
        val level = LogLevel.fromLetter(intent.getStringExtra("level").orEmpty()) ?: LogLevel.INFO
        val message = intent.getStringExtra("message") ?: return
        runCatching {
            SharedContextHolder.remote(context).log.internalLog(tag, level, message)
        }
    }

    companion object {
        const val ACTION_INSTAGRAM_LOG = "me.eternal.purrfect.INSTAGRAM_LOG"
    }
}
