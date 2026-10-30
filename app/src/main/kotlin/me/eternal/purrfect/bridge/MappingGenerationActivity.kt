package me.eternal.purrfect.bridge

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import me.eternal.purrfect.common.Constants
import me.eternal.purrfect.ui.setup.Requirements
import me.eternal.purrfect.ui.setup.SetupActivity

class MappingGenerationActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (intent.action != Constants.MAPPINGS_GENERATION_ACTION) {
            finish()
            return
        }

        startActivity(
            Intent(this, SetupActivity::class.java).apply {
                putExtra("requirements", Requirements.MAPPINGS)
                putExtra(
                    Constants.MAPPINGS_GENERATION_REASON_EXTRA,
                    intent.getStringExtra(Constants.MAPPINGS_GENERATION_REASON_EXTRA)
                )
                putExtra(
                    Constants.MAPPINGS_COMPLETION_MODE_EXTRA,
                    intent.getStringExtra(Constants.MAPPINGS_COMPLETION_MODE_EXTRA)
                )
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
        )
        finish()
    }
}
