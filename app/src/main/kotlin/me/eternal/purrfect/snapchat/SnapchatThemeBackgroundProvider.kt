package me.eternal.purrfect.snapchat

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import me.eternal.purrfect.common.Constants
import java.io.File
import java.io.FileNotFoundException

class SnapchatThemeBackgroundProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String {
        val name = fileForUri(uri)?.name ?: uri.lastPathSegment.orEmpty()
        val extension = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "image/*"
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (!mode.contains('r') || mode.any { it == 'w' || it == 'a' || it == '+' }) {
            throw FileNotFoundException("Theme backgrounds are read-only")
        }
        val file = fileForUri(uri) ?: throw FileNotFoundException("Theme background not found")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        val file = fileForUri(uri) ?: return null
        val columns = projection?.takeIf { it.isNotEmpty() }
            ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cursor = MatrixCursor(columns)
        cursor.addRow(columns.map { column ->
            when (column) {
                OpenableColumns.DISPLAY_NAME -> file.name
                OpenableColumns.SIZE -> file.length()
                else -> null
            }
        })
        return cursor
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0

    private fun fileForUri(uri: Uri): File? {
        if (uri.authority != Constants.SNAPCHAT_THEME_BACKGROUND_AUTHORITY) return null
        val fileName = uri.lastPathSegment
            ?.trim()
            ?.takeIf { it.isNotEmpty() && "/" !in it && "\\" !in it && it != "." && it != ".." }
            ?: return null
        val baseDir = File(context?.filesDir ?: return null, "snapchat_theme_backgrounds").canonicalFile
        val file = File(baseDir, fileName).canonicalFile
        if (!file.path.startsWith(baseDir.path + File.separator)) return null
        return file.takeIf { it.isFile }
    }
}
