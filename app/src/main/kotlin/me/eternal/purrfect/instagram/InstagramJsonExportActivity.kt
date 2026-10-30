package me.eternal.purrfect.instagram

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import me.eternal.purrfect.R
import me.eternal.purrfect.common.Constants

class InstagramJsonExportActivity : Activity() {
    companion object {
        private const val SAVE_JSON_FILE = 5678
    }

    private var jsonContent: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        jsonContent = intent.getStringExtra(Constants.INSTAGRAM_DEV_CONFIG_JSON_EXTRA)
            ?: intent.getStringExtra("json_content")
            ?: ""
        if (jsonContent.isEmpty()) {
            Toast.makeText(this, getString(R.string.instagram_export_no_config_data), Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val fileName = intent.getStringExtra("file_name")
            ?.takeIf { it.isNotBlank() }
            ?: "mc_overrides_exported.json"
        val saveIntent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, fileName)
        }
        startActivityForResult(saveIntent, SAVE_JSON_FILE)
    }

    @Deprecated("Deprecated in Android API; kept to mirror InstaEclipse's save-document flow.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != SAVE_JSON_FILE) return

        if (resultCode == RESULT_OK && data != null) {
            val uri: Uri? = data.data
            if (uri == null) {
                Toast.makeText(this, getString(R.string.instagram_export_invalid_uri), Toast.LENGTH_SHORT).show()
                finish()
                return
            }
            try {
                contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(jsonContent.toByteArray(Charsets.UTF_8))
                    out.flush()
                } ?: error("Output stream unavailable")
                Toast.makeText(this, getString(R.string.instagram_export_config_success), Toast.LENGTH_LONG).show()
            } catch (throwable: Throwable) {
                Toast.makeText(
                    this,
                    getString(R.string.instagram_export_config_failed, throwable.message ?: ""),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
        finish()
    }
}
