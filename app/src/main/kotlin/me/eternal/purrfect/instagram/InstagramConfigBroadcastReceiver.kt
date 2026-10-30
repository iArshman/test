package me.eternal.purrfect.instagram

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import me.eternal.purrfect.SharedContextHolder
import me.eternal.purrfect.common.Constants

class InstagramConfigBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Constants.INSTAGRAM_CONFIG_REQUEST_ACTION) return

        runCatching {
            val remoteContext = SharedContextHolder.remote(context)
            val json = remoteContext.getInstagramFeaturesJson()
            setResultExtras(
                Bundle().apply {
                    putString(Constants.INSTAGRAM_CONFIG_JSON_EXTRA, json)
                }
            )
            remoteContext.mirrorInstagramFeaturePrefs()
        }
    }
}
