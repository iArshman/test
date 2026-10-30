package me.eternal.purrfect.common.database.impl

import android.database.Cursor
import me.eternal.purrfect.common.database.DatabaseObject
import me.eternal.purrfect.common.util.ktx.getStringOrNull

data class StorySnapEntry(
    var rawSnapId: String? = null,
    var mediaUrl: String? = null,
    var mediaKey: String? = null,
    var mediaIv: String? = null,
) : DatabaseObject {
    override fun write(cursor: Cursor) {
        with(cursor) {
            rawSnapId = getStringOrNull("rawSnapId")!!
            mediaUrl = getStringOrNull("mediaUrl")
            mediaKey = getStringOrNull("mediaKey")?.takeIf { it.isNotEmpty() }
            mediaIv = getStringOrNull("mediaIv")?.takeIf { it.isNotEmpty() }
        }
    }
}