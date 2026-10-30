package me.eternal.purrfect.common.bridge

import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.ParcelFileDescriptor.AutoCloseInputStream
import android.os.ParcelFileDescriptor.AutoCloseOutputStream
import me.eternal.purrfect.bridge.storage.FileHandle
import me.eternal.purrfect.common.util.LazyBridgeValue
import me.eternal.purrfect.common.util.lazyBridge
import java.io.File


enum class FileHandleScope(
    val key: String
) {
    INTERNAL("internal"),
    LOCALE("locale"),
    USER_IMPORT("user_import"),
    VALDI("valdi");

    companion object {
        fun fromValue(name: String): FileHandleScope? = entries.find { it.key == name }
    }
}

enum class InternalFileHandleType(
    val key: String,
    val fileName: String,
    val isDatabase: Boolean = false
) {
    CONFIG("config", "config.json"),
    MAPPINGS("mappings", "mappings.json"),
    MAPPINGS_GENERATION_STATE("mappings_generation_state", "mappings_generation_state.txt"),
    SNAPCHAT_THEME_SURFACES("snapchat_theme_surfaces", "snapchat_theme_surfaces.json"),
    MESSAGE_LOGGER("message_logger", "message_logger.db", isDatabase = true),
    PINNED_BEST_FRIEND("pinned_best_friend", "pinned_best_friend.txt"),
    NATIVE_SIG_CACHE("native_sig_cache", "native_sig_cache.txt");

    fun resolve(context: Context): File = if (isDatabase) {
        context.getDatabasePath(fileName)
    } else {
        File(context.filesDir, fileName)
    }

    companion object {
        fun fromValue(name: String): InternalFileHandleType? = entries.find { it.key == name }
    }
}

fun FileHandle.toWrapper() = FileHandleWrapper(lazyBridge { this })

open class FileHandleWrapper(
    private val fileHandle: LazyBridgeValue<FileHandle>
) {
    fun exists() = fileHandle.value.exists()
    fun create() = fileHandle.value.create()
    fun delete() = fileHandle.value.delete()

    fun writeBytes(data: ByteArray) = fileHandle.value.open(
        ParcelFileDescriptor.MODE_WRITE_ONLY or
        ParcelFileDescriptor.MODE_CREATE or
        ParcelFileDescriptor.MODE_TRUNCATE
    ).use { pfd ->
        AutoCloseOutputStream(pfd).use {
            it.write(data)
        }
    }

    open fun readBytes(): ByteArray = fileHandle.value.open(
        ParcelFileDescriptor.MODE_READ_ONLY or
        ParcelFileDescriptor.MODE_CREATE
    ).use { pfd ->
        AutoCloseInputStream(pfd).use {
            it.readBytes()
        }
    }

    fun inputStream(block: (AutoCloseInputStream) -> Unit) = fileHandle.value.open(
        ParcelFileDescriptor.MODE_READ_ONLY or
        ParcelFileDescriptor.MODE_CREATE
    ).use { pfd ->
        AutoCloseInputStream(pfd).use {
            block(it)
        }
    }

    fun outputStream(block: (AutoCloseOutputStream) -> Unit) = fileHandle.value.open(
        ParcelFileDescriptor.MODE_WRITE_ONLY or
        ParcelFileDescriptor.MODE_CREATE or
        ParcelFileDescriptor.MODE_TRUNCATE
    ).use { pfd ->
        AutoCloseOutputStream(pfd).use {
            block(it)
        }
    }
}


