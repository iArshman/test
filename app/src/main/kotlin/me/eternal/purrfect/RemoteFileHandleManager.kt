package me.eternal.purrfect

import android.os.ParcelFileDescriptor
import me.eternal.purrfect.bridge.storage.FileHandle
import me.eternal.purrfect.bridge.storage.FileHandleManager
import me.eternal.purrfect.common.bridge.FileHandleScope
import me.eternal.purrfect.common.bridge.InternalFileHandleType
import me.eternal.purrfect.common.bridge.wrapper.LocaleWrapper
import me.eternal.purrfect.common.logger.AbstractLogger
import me.eternal.purrfect.common.util.ktx.toParcelFileDescriptor
import java.io.File
import java.io.OutputStream


class ByteArrayFileHandle(
    private val context: RemoteSideContext,
    private val data: ByteArray
): FileHandle.Stub() {
    override fun exists() = true
    override fun create() = false
    override fun delete() = false

    override fun open(mode: Int): ParcelFileDescriptor? {
        return runCatching {
            data.inputStream().toParcelFileDescriptor(context.coroutineScope)
        }.onFailure {
            context.log.error("Failed to open byte array file handle: ${it.message}", it)
        }.getOrNull()
    }
}

class LocalFileHandle(
    private val file: File
): FileHandle.Stub() {
    override fun exists() = file.exists()
    override fun create() = file.createNewFile()
    override fun delete() = file.delete()

    override fun open(mode: Int): ParcelFileDescriptor? {
        return runCatching {
            ParcelFileDescriptor.open(file, mode)
        }.onFailure {
            AbstractLogger.directError("Failed to open file handle: ${it.message}", it)
        }.getOrNull()
    }
}

class AssetFileHandle(
    private val context: RemoteSideContext,
    private val assetPath: String
): FileHandle.Stub() {
    override fun exists() = true
    override fun create() = false
    override fun delete() = false

    override fun open(mode: Int): ParcelFileDescriptor? {
        return runCatching {
            context.androidContext.assets.open(assetPath).toParcelFileDescriptor(context.coroutineScope)
        }.onFailure {
            context.log.error("Failed to open asset handle: ${it.message}", it)
        }.getOrNull()
    }
}


class RemoteFileHandleManager(
    private val context: RemoteSideContext
): FileHandleManager.Stub() {
    private val userImportFolder = File(context.androidContext.filesDir, "user_imports").apply {
        mkdirs()
    }

        override fun getFileHandle(scope: String, name: String): FileHandle? {
        val fileHandleScope = FileHandleScope.fromValue(scope) ?: run {
            context.log.error("invalid file handle scope: $scope", "FileHandleManager")
            return null
        }
        return when (fileHandleScope) {
            FileHandleScope.INTERNAL -> {
                val fileHandleType = InternalFileHandleType.fromValue(name) ?: run {
                    context.log.error("invalid file handle name: $name", "FileHandleManager")
                    return null
                }
                LocalFileHandle(fileHandleType.resolve(context.androidContext))
            }
            FileHandleScope.LOCALE -> {
                val foundLocale = context.androidContext.resources.assets.list("lang")?.firstOrNull {
                    it.startsWith(name)
                }?.substringBefore(".") ?: return null
                if (name == LocaleWrapper.DEFAULT_LOCALE) {
                    AssetFileHandle(
                        context,
                        "lang/${LocaleWrapper.DEFAULT_LOCALE}.json"
                    )
                } else {
                    AssetFileHandle(
                        context,
                        "lang/$foundLocale.json"
                    )
                }
            }
            FileHandleScope.USER_IMPORT -> {
                LocalFileHandle(
                    File(userImportFolder, name.substringAfterLast("/"))
                )
            }
            FileHandleScope.VALDI -> {
                AssetFileHandle(
                    context,
                    "valdi/${name.substringAfterLast("/")}"
                )
            }
        }
    }

    fun getStoredFiles(filter: ((File) -> Boolean)? = null): List<File> {
        return userImportFolder.listFiles()?.toList()?.let { files ->
            filter?.let { files.filter(it) } ?: files
        }?.sortedBy { -it.lastModified() } ?: emptyList()
    }

    fun getFileInfo(name: String): Pair<Long, Long>? {
        return runCatching {
            val file = File(userImportFolder, name)
            file.length() to file.lastModified()
        }.onFailure {
            context.log.error("Failed to get file info: ${it.message}", it)
        }.getOrNull()
    }

    fun importFile(name: String, block: OutputStream.() -> Unit): Boolean {
        return runCatching {
            val file = File(userImportFolder, name)
            file.outputStream().use(block)
            true
        }.onFailure {
            context.log.error("Failed to import file: ${it.message}", it)
        }.getOrDefault(false)
    }

    fun readFile(name: String): String? {
        return runCatching {
            File(userImportFolder, name).readText()
        }.onFailure {
            context.log.error("Failed to read file: ${it.message}", it)
        }.getOrNull()
    }

    fun deleteFile(name: String): Boolean {
        return runCatching {
            File(userImportFolder, name).delete()
        }.onFailure {
            context.log.error("Failed to delete file: ${it.message}", it)
        }.isSuccess
    }
}
