package me.eternal.purrfect.bridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import me.eternal.purrfect.SharedContextHolder
import me.eternal.purrfect.common.logger.LogLevel

class WhatsAppLogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_WHATSAPP_LOG) return
        val tag = intent.getStringExtra("tag") ?: "PurrfectWA"
        val level = LogLevel.fromLetter(intent.getStringExtra("level").orEmpty()) ?: LogLevel.INFO
        val message = intent.getStringExtra("message") ?: return
        runCatching {
            SharedContextHolder.remote(context).log.internalLog(tag, level, message)
        }
    }

    companion object {
        const val ACTION_WHATSAPP_LOG = "me.eternal.purrfect.WHATSAPP_LOG"
    }
}
