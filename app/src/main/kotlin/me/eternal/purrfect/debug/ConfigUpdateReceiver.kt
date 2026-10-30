package me.eternal.purrfect.debug

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import me.eternal.purrfect.SharedContextHolder
import me.eternal.purrfect.common.config.ConfigContainer

class ConfigUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_UPDATE_CONFIG) return

        val path = intent.getStringExtra(EXTRA_PATH)
            ?.split(".")
            ?.filter { it.isNotBlank() }
            ?.takeIf { it.isNotEmpty() }
            ?: run {
                returnResult(Activity.RESULT_CANCELED, "missing path")
                return
            }

        val value = parseValue(intent)
        val remoteContext = SharedContextHolder.remote(context)
        val updated = setProperty(remoteContext.config.root, path, value)

        if (!updated) {
            returnResult(Activity.RESULT_CANCELED, "unknown path: ${path.joinToString(".")}")
            return
        }

        remoteContext.config.writeConfig()
        returnResult(Activity.RESULT_OK, "updated ${path.joinToString(".")}")
    }

    private fun parseValue(intent: Intent): Any? {
        return when (intent.getStringExtra(EXTRA_TYPE)?.lowercase()) {
            "null" -> null
            "boolean", "bool" -> intent.getBooleanExtra(EXTRA_BOOLEAN_VALUE, false)
            "int", "integer" -> intent.getIntExtra(EXTRA_INT_VALUE, 0)
            "float" -> intent.getFloatExtra(EXTRA_FLOAT_VALUE, 0f)
            else -> intent.getStringExtra(EXTRA_STRING_VALUE)
        }
    }

    private fun setProperty(container: ConfigContainer, path: List<String>, value: Any?): Boolean {
        val entry = container.properties.entries.firstOrNull { it.key.name == path.first() } ?: return false
        if (path.size == 1) {
            entry.value.setAny(value)
            return true
        }

        val child = entry.value.getNullable() as? ConfigContainer ?: return false
        return setProperty(child, path.drop(1), value)
    }

    private fun returnResult(code: Int, message: String) {
        setResultCode(code)
        setResultExtras(Bundle().apply { putString(EXTRA_RESULT_MESSAGE, message) })
    }

    companion object {
        const val ACTION_UPDATE_CONFIG = "me.eternal.purrfect.action.DEBUG_UPDATE_CONFIG"
        const val EXTRA_PATH = "path"
        const val EXTRA_TYPE = "type"
        const val EXTRA_STRING_VALUE = "string_value"
        const val EXTRA_BOOLEAN_VALUE = "boolean_value"
        const val EXTRA_INT_VALUE = "int_value"
        const val EXTRA_FLOAT_VALUE = "float_value"
        const val EXTRA_RESULT_MESSAGE = "result_message"
    }
}
