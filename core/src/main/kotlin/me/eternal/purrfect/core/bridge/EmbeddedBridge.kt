package me.eternal.purrfect.core.bridge

import android.content.Intent
import android.os.ParcelFileDescriptor
import android.util.Log
import me.eternal.purrfect.bridge.AccountStorage
import me.eternal.purrfect.bridge.BridgeInterface
import me.eternal.purrfect.bridge.ConfigStateListener
import me.eternal.purrfect.bridge.DownloadCallback
import me.eternal.purrfect.bridge.SyncCallback
import me.eternal.purrfect.bridge.call.CallDownloadSession
import me.eternal.purrfect.bridge.e2ee.E2eeInterface
import me.eternal.purrfect.bridge.e2ee.EncryptionResult
import me.eternal.purrfect.bridge.location.FriendLocation
import me.eternal.purrfect.bridge.location.LocationManager
import me.eternal.purrfect.bridge.logger.BridgeLoggedMessage
import me.eternal.purrfect.bridge.logger.LoggedChatEdit
import me.eternal.purrfect.bridge.logger.LoggerInterface
import me.eternal.purrfect.bridge.logger.TrackerInterface
import me.eternal.purrfect.bridge.scripting.AutoReloadListener
import me.eternal.purrfect.bridge.scripting.IPCListener
import me.eternal.purrfect.bridge.scripting.IScripting
import me.eternal.purrfect.bridge.snapclient.MessagingBridge
import me.eternal.purrfect.bridge.storage.FileHandle
import me.eternal.purrfect.bridge.storage.FileHandleManager
import me.eternal.purrfect.bridge.task.TaskInterface
import me.eternal.purrfect.bridge.task.TaskListener
import me.eternal.purrfect.common.bridge.FileHandleScope
import me.eternal.purrfect.common.bridge.InternalFileHandleType
import me.eternal.purrfect.common.bridge.wrapper.LocaleWrapper
import me.eternal.purrfect.common.util.ktx.toParcelFileDescriptor
import me.eternal.purrfect.core.ModContext
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

internal class EmbeddedBridge(
    private val context: ModContext,
    private val reason: String?
) : BridgeInterface.Stub() {
    private val fileHandleManager = EmbeddedFileHandleManager(context)
    private val logger = EmbeddedLogger()
    private val tracker = EmbeddedTracker()
    private val accountStorage = EmbeddedAccountStorage()
    private val locationManager = EmbeddedLocationManager()
    private val taskInterface = EmbeddedTaskInterface()
    private val e2eeInterface = EmbeddedE2eeInterface()
    private val scripting = EmbeddedScripting()

    init {
        Log.i("BridgeClient", "Using embedded Purrfect bridge fallback${reason?.let { ": $it" } ?: ""}")
    }

    override fun getApplicationApkPath(): String {
        return moduleApkPath() ?: context.androidContext.applicationInfo.sourceDir
    }

    override fun broadcastLog(tag: String, level: String, message: String) = Unit

    override fun enqueueDownload(intent: Intent?, callback: DownloadCallback?) = Unit

    override fun convertMedia(
        input: ParcelFileDescriptor?,
        inputExtension: String?,
        outputExtension: String?,
        audioCodec: String?,
        videoCodec: String?
    ): ParcelFileDescriptor? = null

    override fun getRules(uuid: String?): MutableList<String> = mutableListOf()

    override fun getRuleIds(type: String?): MutableList<String> = mutableListOf()

    override fun setRule(uuid: String?, type: String?, state: Boolean) = Unit

    override fun sync(callback: SyncCallback?) = Unit

    override fun triggerSync(scope: String?, id: String?) = Unit

    override fun passGroupsAndFriends(
        groups: MutableList<String>?,
        friends: MutableList<String>?,
        chunkIndex: Int,
        totalChunks: Int
    ) = Unit

    override fun getScopeNotes(id: String?): String? = null

    override fun setScopeNotes(id: String?, content: String?) = Unit

    override fun getAllScopeNotes(): MutableMap<String, String> = mutableMapOf()

    override fun setAllScopeNotes(notes: MutableMap<String, String>?) = Unit

    override fun getScriptingInterface(): IScripting = scripting

    override fun getE2eeInterface(): E2eeInterface = e2eeInterface

    override fun getLogger(): LoggerInterface = logger

    override fun getTracker(): TrackerInterface = tracker

    override fun getAccountStorage(): AccountStorage = accountStorage

    override fun getFileHandleManager(): FileHandleManager = fileHandleManager

    override fun getLocationManager(): LocationManager = locationManager

    override fun getTaskInterface(): TaskInterface = taskInterface

    override fun registerMessagingBridge(bridge: MessagingBridge?) = Unit

    override fun openOverlay(type: String?) = Unit

    override fun closeOverlay() = Unit

    override fun registerConfigStateListener(listener: ConfigStateListener?) = Unit

    override fun getDebugProp(key: String?, defaultValue: String?): String? = defaultValue

    override fun openMappingsGenerator(reason: String?, completionMode: String?): Boolean = false

    override fun getRedditFeaturesJson(): String = "{}"

    override fun getWhatsAppFeaturesJson(): String = "{}"

    override fun getInstagramFeaturesJson(): String = "{}"

    override fun startCallDownload(startTimestamp: Long, author: String?): CallDownloadSession {
        return EmbeddedCallDownloadSession(context)
    }
}

private class EmbeddedFileHandleManager(
    private val context: ModContext
) : FileHandleManager.Stub() {
    private val baseDir = File(context.androidContext.filesDir, "purrfect_embedded").apply { mkdirs() }
    private val userImportDir = File(baseDir, "user_imports").apply { mkdirs() }

    override fun getFileHandle(scope: String?, name: String?): FileHandle? {
        val handleScope = FileHandleScope.fromValue(scope ?: return null) ?: return null
        val safeName = name ?: return null
        return when (handleScope) {
            FileHandleScope.INTERNAL -> {
                val type = InternalFileHandleType.fromValue(safeName) ?: return null
                EmbeddedLocalFileHandle(type.resolveEmbedded())
            }
            FileHandleScope.LOCALE -> {
                val locale = safeName.substringBefore(".")
                val assetPath = if (locale == LocaleWrapper.DEFAULT_LOCALE) {
                    "lang/${LocaleWrapper.DEFAULT_LOCALE}.json"
                } else {
                    findModuleAsset("lang", "$locale.")
                        ?: "lang/${LocaleWrapper.DEFAULT_LOCALE}.json"
                }
                EmbeddedAssetFileHandle(context, assetPath)
            }
            FileHandleScope.USER_IMPORT -> {
                EmbeddedLocalFileHandle(File(userImportDir, safeName.substringAfterLast("/")))
            }
            FileHandleScope.VALDI -> {
                EmbeddedAssetFileHandle(context, "valdi/${safeName.substringAfterLast("/")}")
            }
        }
    }

    private fun InternalFileHandleType.resolveEmbedded(): File {
        return if (isDatabase) {
            File(baseDir, "databases/$fileName")
        } else {
            File(baseDir, fileName)
        }
    }

    private fun findModuleAsset(folder: String, prefix: String): String? {
        val apk = moduleApkPath() ?: return null
        return runCatching {
            ZipFile(apk).use { zip ->
                zip.entries().asSequence()
                    .map { it.name }
                    .firstOrNull { it.startsWith("assets/$folder/") && it.substringAfterLast("/").startsWith(prefix) }
                    ?.removePrefix("assets/")
            }
        }.getOrNull()
    }
}

private class EmbeddedLocalFileHandle(
    private val file: File
) : FileHandle.Stub() {
    override fun exists(): Boolean = file.exists()

    override fun create(): Boolean {
        file.parentFile?.mkdirs()
        return file.createNewFile()
    }

    override fun delete(): Boolean = file.delete()

    override fun open(mode: Int): ParcelFileDescriptor? {
        return runCatching {
            file.parentFile?.mkdirs()
            ParcelFileDescriptor.open(file, mode)
        }.getOrNull()
    }
}

private class EmbeddedAssetFileHandle(
    private val context: ModContext,
    private val assetPath: String
) : FileHandle.Stub() {
    override fun exists(): Boolean = open(ParcelFileDescriptor.MODE_READ_ONLY) != null

    override fun create(): Boolean = false

    override fun delete(): Boolean = false

    override fun open(mode: Int): ParcelFileDescriptor? {
        return runCatching {
            val stream = context.androidContext.assets.open(assetPath)
            stream.toParcelFileDescriptor(context.coroutineScope)
        }.recoverCatching {
            val apk = moduleApkPath() ?: throw it
            val entryName = "assets/$assetPath"
            ZipFile(apk).use { zip ->
                val entry = zip.getEntry(entryName) ?: throw it
                zip.getInputStream(entry).readBytes().inputStream()
                    .toParcelFileDescriptor(context.coroutineScope)
            }
        }.getOrNull()
    }
}

private class EmbeddedLogger : LoggerInterface.Stub() {
    private val messages = ConcurrentHashMap<String, ConcurrentHashMap<Long, ByteArray>>()

    override fun getLoggedIds(conversationIds: Array<String>?, limit: Int): LongArray {
        val ids = conversationIds.orEmpty()
            .flatMap { messages[it]?.keys.orEmpty() }
            .take(limit.coerceAtLeast(0))
        return ids.toLongArray()
    }

    override fun getMessage(conversationId: String?, id: Long): ByteArray? {
        return messages[conversationId]?.get(id)
    }

    override fun addMessage(message: BridgeLoggedMessage?) {
        message ?: return
        addMessages(mutableListOf(message))
    }

    override fun addMessages(messages: MutableList<BridgeLoggedMessage>?) {
        messages.orEmpty().forEach { message ->
            val conversationId = message.conversationId ?: return@forEach
            val data = message.messageData ?: return@forEach
            this.messages.getOrPut(conversationId) { ConcurrentHashMap() }[message.messageId] = data
        }
    }

    override fun deleteMessage(conversationId: String?, id: Long) {
        messages[conversationId]?.remove(id)
    }

    override fun addStory(
        userId: String?,
        url: String?,
        postedAt: Long,
        createdAt: Long,
        key: ByteArray?,
        iv: ByteArray?
    ): Boolean = false

    override fun logTrackerEvent(
        conversationId: String?,
        conversationTitle: String?,
        isGroup: Boolean,
        username: String?,
        userId: String?,
        eventType: String?,
        data: String?
    ) = Unit

    override fun getChatEdits(conversationId: String?, messageId: Long): MutableList<LoggedChatEdit> = mutableListOf()
}

private class EmbeddedTracker : TrackerInterface.Stub() {
    override fun getTrackedEvents(eventType: String?): String = "{}"

    override fun updateFriendScore(userId: String?, score: Long): Long = -1
}

private class EmbeddedAccountStorage : AccountStorage.Stub() {
    override fun getAccounts(): MutableMap<String, String> = mutableMapOf()

    override fun addAccount(userId: String?, username: String?, data: ParcelFileDescriptor?) = Unit

    override fun removeAccount(userId: String?) = Unit

    override fun isAccountExists(userId: String?): Boolean = false

    override fun getAccountData(userId: String?): ParcelFileDescriptor? = null
}

private class EmbeddedLocationManager : LocationManager.Stub() {
    override fun provideFriendsLocation(friendsLocation: MutableList<FriendLocation>?) = Unit
}

private class EmbeddedTaskInterface : TaskInterface.Stub() {
    override fun createTask(type: String?, title: String?, author: String?, hash: String?): String {
        return hash ?: UUID.randomUUID().toString()
    }

    override fun updateTaskProgress(hash: String?, label: String?, progress: Int) = Unit

    override fun cancelTask(hash: String?) = Unit

    override fun failTask(hash: String?, reason: String?) = Unit

    override fun successTask(hash: String?) = Unit

    override fun registerTaskListener(hash: String?, listener: TaskListener?) = Unit

    override fun unregisterTaskListener(hash: String?, listener: TaskListener?) = Unit
}

private class EmbeddedE2eeInterface : E2eeInterface.Stub() {
    override fun createKeyExchange(friendId: String?): ByteArray? = null

    override fun acceptPairingRequest(friendId: String?, publicKey: ByteArray?): ByteArray? = null

    override fun acceptPairingResponse(friendId: String?, encapsulatedSecret: ByteArray?): Boolean = false

    override fun friendKeyExists(friendId: String?): Boolean = false

    override fun getSecretFingerprint(friendId: String?): String? = null

    override fun encryptMessage(friendId: String?, message: ByteArray?): EncryptionResult? = null

    override fun decryptMessage(friendId: String?, message: ByteArray?, iv: ByteArray?): ByteArray? = null
}

private class EmbeddedScripting : IScripting.Stub() {
    override fun getEnabledScripts(): MutableList<String> = mutableListOf()

    override fun getScriptContent(path: String?): ParcelFileDescriptor? = null

    override fun registerIPCListener(channel: String?, eventName: String?, listener: IPCListener?) = Unit

    override fun sendIPCMessage(channel: String?, eventName: String?, args: Array<String>?): Int = 0

    override fun configTransaction(
        module: String?,
        action: String?,
        key: String?,
        value: String?,
        save: Boolean
    ): String? = null

    override fun registerAutoReloadListener(listener: AutoReloadListener?) = Unit
}

private class EmbeddedCallDownloadSession(
    private val context: ModContext
) : CallDownloadSession.Stub() {
    override fun createStream(
        startTimestampMillis: Long,
        channels: Int,
        sampleRate: Int,
        encoding: Int
    ): ParcelFileDescriptor {
        val file = File.createTempFile("purrfect-call-", ".pcm", context.androidContext.cacheDir)
        return ParcelFileDescriptor.open(
            file,
            ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_TRUNCATE
        )
    }

    override fun end() = Unit
}

internal fun moduleApkPath(): String? {
    val loaderString = EmbeddedBridge::class.java.classLoader?.toString().orEmpty()
    return loaderString.substringAfter("module=", "")
        .substringBefore(",")
        .takeIf { it.endsWith(".apk") }
}
