package me.eternal.purrfect.common.bridge

import me.eternal.purrfect.bridge.storage.FileHandleManager
import me.eternal.purrfect.common.util.LazyBridgeValue
import me.eternal.purrfect.common.util.lazyBridge

open class InternalFileWrapper(
    fileHandleManager: LazyBridgeValue<FileHandleManager>,
    private val fileType: InternalFileHandleType,
    val defaultValue: String? = null
): FileHandleWrapper(lazyBridge { fileHandleManager.value.getFileHandle(FileHandleScope.INTERNAL.key, fileType.key)!! }) {
    override fun readBytes(): ByteArray {
        if (!exists()) {
            defaultValue?.toByteArray(Charsets.UTF_8)?.let {
                writeBytes(it)
                return it
            }
        }
        return super.readBytes()
    }
}