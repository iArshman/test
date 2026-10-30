package me.eternal.purrfect.instagram

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import me.eternal.purrfect.R
import me.eternal.purrfect.common.Constants

class InstagramJsonImportActivity : Activity() {
    companion object {
        private const val PICK_JSON_FILE = 1234
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val picker = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "application/json"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        runCatching {
            startActivityForResult(
                Intent.createChooser(picker, getString(R.string.instagram_json_select_config)),
                PICK_JSON_FILE
            )
        }.onFailure {
            Toast.makeText(this, getString(R.string.instagram_json_cancelled), Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    @Deprecated("Deprecated in Android API; kept to mirror InstaEclipse's file picker flow.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PICK_JSON_FILE) return

        if (resultCode == RESULT_OK && data != null) {
            val uri: Uri? = data.data
            try {
                val json = uri?.let {
                    contentResolver.openInputStream(it)?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                        reader.readText()
                    }
                }?.trim().orEmpty()

                if (!json.startsWith("{") || !json.endsWith("}")) {
                    Toast.makeText(this, getString(R.string.instagram_json_not_valid), Toast.LENGTH_LONG).show()
                } else {
                    val targetPackage = intent.getStringExtra("target_package")
                    if (targetPackage.isNullOrBlank()) {
                        Toast.makeText(this, getString(R.string.instagram_json_target_not_specified), Toast.LENGTH_LONG).show()
                    } else {
                        val action = intent.getStringExtra("broadcast_action")
                            ?.takeIf { it.isNotBlank() }
                            ?: Constants.INSTAGRAM_DEV_CONFIG_IMPORT_ACTION
                        sendBroadcast(
                            Intent(action)
                                .setPackage(targetPackage)
                                .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                                .putExtra(Constants.INSTAGRAM_DEV_CONFIG_JSON_EXTRA, json)
                                .putExtra("json_content", json)
                        )
                        Toast.makeText(this, getString(R.string.instagram_json_sent), Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (throwable: Throwable) {
                Toast.makeText(
                    this,
                    getString(R.string.instagram_json_read_failed, throwable.message ?: ""),
                    Toast.LENGTH_LONG
                ).show()
            }
        } else {
            Toast.makeText(this, getString(R.string.instagram_json_cancelled), Toast.LENGTH_SHORT).show()
        }
        finish()
    }
}
