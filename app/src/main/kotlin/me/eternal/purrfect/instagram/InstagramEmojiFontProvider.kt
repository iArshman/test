package me.eternal.purrfect.instagram

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException

class InstagramEmojiFontProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String = "font/ttf"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (uri.lastPathSegment != "font") {
            throw FileNotFoundException("Unknown emoji font path")
        }
        val fontFile = fontFile()
        if (!fontFile.exists() || fontFile.length() <= 0L) {
            throw FileNotFoundException("No custom emoji font imported")
        }
        return ParcelFileDescriptor.open(fontFile, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0

    private fun fontFile(): File {
        val ctx = context ?: throw FileNotFoundException("No context")
        return File(File(ctx.filesDir, "emoji"), "custom_emoji_font.ttf")
    }
}
