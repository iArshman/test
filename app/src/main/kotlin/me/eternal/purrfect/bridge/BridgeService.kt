package me.eternal.purrfect.bridge

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.eternal.purrfect.RemoteSideContext
import me.eternal.purrfect.SharedContextHolder
import me.eternal.purrfect.common.Constants
import me.eternal.purrfect.common.TargetApp
import me.eternal.purrfect.bridge.call.CallDownloadSession
import me.eternal.purrfect.bridge.snapclient.MessagingBridge
import me.eternal.purrfect.common.data.MessagingFriendInfo
import me.eternal.purrfect.common.data.MessagingGroupInfo
import me.eternal.purrfect.common.data.SocialScope
import me.eternal.purrfect.common.logger.LogLevel
import me.eternal.purrfect.common.ui.OverlayType
import me.eternal.purrfect.common.util.toParcelable
import me.eternal.purrfect.download.DownloadProcessor
import me.eternal.purrfect.download.FFMpegProcessor
import me.eternal.purrfect.download.call.CallDownloadSessionImpl
import me.eternal.purrfect.storage.*
import me.eternal.purrfect.task.Task
import me.eternal.purrfect.task.TaskType
import java.io.File
import java.util.UUID
import kotlin.system.measureTimeMillis

class BridgeService : Service() {
    private lateinit var remoteSideContext: RemoteSideContext
    private var syncCallback: SyncCallback? = null
    var messagingBridge: MessagingBridge? = null
    @Volatile
    private var pendingSocialSnapshotCallback: ((List<MessagingFriendInfo>, List<MessagingGroupInfo>) -> Unit)? = null

    private val isBridgeWarmed = java.util.concurrent.atomic.AtomicBoolean(false)

    private val syncCallbackDeathRecipient = IBinder.DeathRecipient {
        synchronized(this) {
            syncCallback = null
        }
    }

    private fun clearSyncCallback() {
        synchronized(this) {
            syncCallback?.asBinder()?.unlinkToDeath(syncCallbackDeathRecipient, 0)
            syncCallback = null
        }
    }

    fun requestEphemeralSocialSnapshot(callback: (List<MessagingFriendInfo>, List<MessagingGroupInfo>) -> Unit) {
        pendingSocialSnapshotCallback = callback
    }

    fun clearEphemeralSocialSnapshotRequest() {
        pendingSocialSnapshotCallback = null
    }

    override fun onDestroy() {
        clearSyncCallback()
        if (::remoteSideContext.isInitialized) {
            remoteSideContext.bridgeService = null
        }
    }

    private fun grantFolderUriPermission() {
        runCatching {
            val saveFolderUri = remoteSideContext.config.root.downloader.saveFolder.get()
            if (saveFolderUri.isNotEmpty()) {
                val uri = android.net.Uri.parse(saveFolderUri)
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                grantUriPermission("com.snapchat.android", uri, flags)
                grantUriPermission("com.reddit.frontpage", uri, flags)
                remoteSideContext.log.info("Successfully granted Tree URI permission to target apps: $uri", "BridgeService")
            }
        }.onFailure {
            remoteSideContext.log.error("Failed to grant Tree URI permission to target apps", it, "BridgeService")
        }
    }

    override fun onBind(intent: Intent): IBinder? {
        remoteSideContext = SharedContextHolder.remote(this).apply {
            if (checkForRequirements()) return null
        }
        remoteSideContext.apply {
            bridgeService = this@BridgeService
        }
        grantFolderUriPermission()
        return BridgeBinder()
    }

    fun triggerScopeSync(scope: SocialScope, id: String, updateOnly: Boolean = false) {
        val callback = synchronized(this) { syncCallback } ?: return
        runCatching {
            val database = remoteSideContext.database
            val syncedObject = when (scope) {
                SocialScope.FRIEND -> {
                    if (updateOnly && database.getFriendInfo(id) == null) return
                    callback.syncFriend(id)
                }
                SocialScope.GROUP -> {
                    if (updateOnly && database.getGroupInfo(id) == null) return
                    callback.syncGroup(id)
                }
            } ?: run {
                if (updateOnly) {
                    if (isBridgeWarmed.get()) {
                        when (scope) {
                            SocialScope.FRIEND -> database.deleteFriend(id)
                            SocialScope.GROUP -> database.deleteGroup(id)
                        }
                    }
                    return
                }
                remoteSideContext.log.warn("Failed to sync $scope $id")
                return
            }

            when (scope) {
                SocialScope.FRIEND -> {
                    toParcelable<MessagingFriendInfo>(syncedObject)?.let { database.syncFriend(it) } ?: run {
                        if (updateOnly) {
                            if (isBridgeWarmed.get()) {
                                database.deleteFriend(id)
                            }
                            return
                        }
                        remoteSideContext.log.warn("Failed to sync $scope $id")
                        return
                    }
                }
                SocialScope.GROUP -> {
                    toParcelable<MessagingGroupInfo>(syncedObject)?.let { database.syncGroupInfo(it) } ?: run {
                        if (updateOnly) {
                            if (isBridgeWarmed.get()) {
                                database.deleteGroup(id)
                            }
                            return
                        }
                        remoteSideContext.log.warn("Failed to sync $scope $id")
                        return
                    }
                }
            }
        }.onFailure {
            if (it is RemoteException) {
                clearSyncCallback()
                remoteSideContext.log.warn("Failed to sync $scope $id: Callback is dead")
                return@onFailure
            }
            remoteSideContext.log.error("Failed to sync $scope $id", it)
        }
    }

    inner class BridgeBinder : BridgeInterface.Stub() {
        override fun getApplicationApkPath(): String = applicationInfo.publicSourceDir

        override fun broadcastLog(tag: String, level: String, message: String) {
            remoteSideContext.log.internalLog(tag, LogLevel.fromShortName(level) ?: LogLevel.INFO, message)
        }
        override fun enqueueDownload(intent: Intent, callback: DownloadCallback) {
            DownloadProcessor(
                remoteSideContext = remoteSideContext,
                callback = callback
            ).onReceive(intent)
        }

        override fun convertMedia(
            input: ParcelFileDescriptor?,
            inputExtension: String,
            outputExtension: String,
            audioCodec: String?,
            videoCodec: String?
        ): ParcelFileDescriptor? {
            return runBlocking {
                val taskId = UUID.randomUUID().toString()
                val inputFile = File.createTempFile(taskId, ".$inputExtension", remoteSideContext.androidContext.cacheDir)

                runCatching {
                    ParcelFileDescriptor.AutoCloseInputStream(input).use { inputStream ->
                        inputFile.outputStream().use { outputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    }
                }.onFailure {
                    remoteSideContext.log.error("Failed to copy input file", it)
                    inputFile.delete()
                    return@runBlocking null
                }
                val cachedFile = File.createTempFile(taskId, ".$outputExtension", remoteSideContext.androidContext.cacheDir)

                val pendingTask = remoteSideContext.taskManager.createPendingTask(
                    Task(
                        type = TaskType.DOWNLOAD,
                        title = remoteSideContext.translation["task_media_conversion_title"],
                        author = null,
                        hash = taskId
                    )
                )
                runCatching {
                    FFMpegProcessor.newFFMpegProcessor(remoteSideContext, pendingTask).execute(
                        FFMpegProcessor.Request(
                            action = FFMpegProcessor.Action.CONVERSION,
                            inputs = listOf(inputFile.absolutePath),
                            output = cachedFile,
                            videoCodec = videoCodec,
                            audioCodec = audioCodec
                        )
                    )
                    pendingTask.success()
                    return@runBlocking ParcelFileDescriptor.open(cachedFile, ParcelFileDescriptor.MODE_READ_ONLY)
                }.onFailure {
                    pendingTask.fail(it.message ?: "Failed to convert video")
                    remoteSideContext.log.error("Failed to convert video", it)
                }

                inputFile.delete()
                cachedFile.delete()
                null
            }
        }

        override fun getRules(uuid: String): List<String> {
            return remoteSideContext.database.getRules(uuid).map { it.key }
        }

        override fun getRuleIds(type: String): MutableList<String> {
            return remoteSideContext.database.getRuleIds(type)
        }

        override fun setRule(uuid: String, rule: String, state: Boolean) {
            remoteSideContext.database.setRule(uuid, rule, state)
        }

        override fun sync(callback: SyncCallback) {
            clearSyncCallback()
            synchronized(this@BridgeService) {
                syncCallback = callback
                callback.asBinder().linkToDeath(syncCallbackDeathRecipient, 0)
            }
            remoteSideContext.coroutineScope.launch(Dispatchers.IO) {
                delay(300) // 300ms Stabilizer: Ensures Binder connection is solid on cold starts
                isBridgeWarmed.set(true) // Immediate Unblock: Allow UI features to start loading data from cache
                
                val time = measureTimeMillis {
                    // Fetch all friends and groups without hard-coded limits
                    val activeFriendIds: List<String> = remoteSideContext.database.getFriends(descOrder = true).map { it.userId }
                    val groupIds: List<String> = remoteSideContext.database.getGroups().map { it.conversationId }

                    // Batched Synchronization: 10 items per batch with 50ms intervals to optimize IPC throughput
                    val batchSize = 10

                    // Sync Groups in batches
                    for (batch in groupIds.chunked(batchSize)) {
                        batch.forEach { groupId -> triggerScopeSync(SocialScope.GROUP, groupId, true) }
                        delay(50) // High-quality breather to keep IPC pipe clear
                    }

                    // Sync Friends in batches
                    for (batch in activeFriendIds.chunked(batchSize)) {
                        batch.forEach { friendId -> triggerScopeSync(SocialScope.FRIEND, friendId, true) }
                        delay(50)
                    }
                }
                remoteSideContext.log.verbose("Background metadata synchronization completed in ${time}ms")
            }
        }

        override fun triggerSync(scope: String, id: String) {
            remoteSideContext.log.verbose("trigger sync for $scope $id")
            triggerScopeSync(SocialScope.getByName(scope), id, true)
        }

        private val friendAccumulator = mutableListOf<MessagingFriendInfo>()
        private val groupAccumulator = mutableListOf<MessagingGroupInfo>()

        override fun passGroupsAndFriends(
            groups: List<String>,
            friends: List<String>,
            chunkIndex: Int,
            totalChunks: Int
        ) {
            synchronized(friendAccumulator) {
                if (chunkIndex == 0) {
                    friendAccumulator.clear()
                    groupAccumulator.clear()
                }

                remoteSideContext.log.verbose("Received chunk $chunkIndex/$totalChunks: ${groups.size} groups, ${friends.size} friends")
                friendAccumulator.addAll(friends.mapNotNull { toParcelable<MessagingFriendInfo>(it) })
                groupAccumulator.addAll(groups.mapNotNull { toParcelable<MessagingGroupInfo>(it) })

                if (chunkIndex == totalChunks - 1) {
                    val finalFriends = friendAccumulator.toList()
                    val finalGroups = groupAccumulator.toList()

                    friendAccumulator.clear()
                    groupAccumulator.clear()

                    remoteSideContext.coroutineScope.launch(Dispatchers.IO) {
                        pendingSocialSnapshotCallback?.let { callback ->
                            pendingSocialSnapshotCallback = null
                            callback(finalFriends, finalGroups)
                        }
                        remoteSideContext.database.replaceMessagingData(finalFriends, finalGroups)
                        remoteSideContext.database.messagingDataFlow.tryEmit(finalFriends to finalGroups)
                    }
                }
            }
        }

        override fun getScopeNotes(id: String): String? {
            return remoteSideContext.database.getScopeNotes(id)
        }

        override fun setScopeNotes(id: String, content: String?) {
            remoteSideContext.database.setScopeNotes(id, content)
        }

        override fun getAllScopeNotes(): Map<String, String> {
            return remoteSideContext.database.getAllScopeNotes()
        }

        override fun setAllScopeNotes(notes: Map<String, String>) {
            remoteSideContext.database.setAllScopeNotes(notes)
        }

        override fun getScriptingInterface() = remoteSideContext.scriptManager

        override fun getE2eeInterface() = remoteSideContext.e2eeImplementation
        override fun getLogger() = remoteSideContext.messageLogger
        override fun getTracker() = remoteSideContext.tracker
        override fun getAccountStorage() = remoteSideContext.accountStorage
        override fun getFileHandleManager() = remoteSideContext.fileHandleManager
        override fun getLocationManager() = remoteSideContext.locationManager
        override fun getTaskInterface() = remoteSideContext.taskInterface

        override fun registerMessagingBridge(bridge: MessagingBridge) {
            messagingBridge = bridge
        }

        override fun openOverlay(type: String) {
            runCatching {
                val overlayType = OverlayType.fromKey(type) ?: throw IllegalArgumentException("Unknown overlay type: $type")
                remoteSideContext.remoteOverlay.show(TargetApp.SNAPCHAT) { routes ->
                    when (overlayType) {
                        OverlayType.SETTINGS -> routes.features
                        OverlayType.BETTER_LOCATION -> routes.betterLocation
                    }
                }
            }.onFailure {
                remoteSideContext.log.error("Failed to open $type overlay", it)
            }
        }

        override fun closeOverlay() {
            runCatching {
                remoteSideContext.remoteOverlay.close()
            }.onFailure {
                remoteSideContext.log.error("Failed to close overlay", it)
            }
        }

        override fun registerConfigStateListener(listener: ConfigStateListener) {
            remoteSideContext.config.configStateListener = listener
        }

        override fun getDebugProp(key: String, defaultValue: String?): String? {
            return remoteSideContext.sharedPreferences.all["debug_$key"]?.toString() ?: defaultValue
        }

        override fun openMappingsGenerator(reason: String, completionMode: String): Boolean {
            val intent = Intent(Constants.MAPPINGS_GENERATION_ACTION).apply {
                setClass(this@BridgeService, MappingGenerationActivity::class.java)
                putExtra(Constants.MAPPINGS_GENERATION_REASON_EXTRA, reason)
                putExtra(Constants.MAPPINGS_COMPLETION_MODE_EXTRA, completionMode)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }

            var directLaunchSucceeded = false
            runCatching {
                startActivity(intent)
                directLaunchSucceeded = true
                remoteSideContext.log.verbose(
                    "Requested mappings generator from BridgeService via direct start; " +
                        "reason=$reason completionMode=$completionMode"
                )
            }.onFailure {
                remoteSideContext.log.warn(
                    "Direct mappings generator start from BridgeService failed: ${it.message}"
                )
            }

            if (directLaunchSucceeded) return true
            return scheduleMappingsGenerator(intent, reason, completionMode)
        }

        private fun scheduleMappingsGenerator(intent: Intent, reason: String, completionMode: String): Boolean {
            return runCatching {
                val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }
                val pendingIntent = PendingIntent.getActivity(
                    this@BridgeService,
                    31337,
                    intent,
                    pendingFlags
                )
                val alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager
                val triggerAt = System.currentTimeMillis() + 120L
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
                } else {
                    alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
                }
                remoteSideContext.log.verbose(
                    "Scheduled mappings generator PendingIntent from BridgeService; " +
                        "reason=$reason completionMode=$completionMode"
                )
                true
            }.onFailure {
                remoteSideContext.log.warn(
                    "Failed to schedule mappings generator from BridgeService: ${it.message}"
                )
            }.getOrDefault(false)
        }

        override fun getRedditFeaturesJson(): String {
            remoteSideContext.mirrorRedditFeaturePrefs()
            return remoteSideContext.getRedditFeaturesJson()
        }

        override fun getWhatsAppFeaturesJson(): String {
            remoteSideContext.mirrorWhatsAppFeaturePrefs()
            return remoteSideContext.getWhatsAppFeaturesJson()
        }

        override fun getInstagramFeaturesJson(): String {
            remoteSideContext.mirrorInstagramFeaturePrefs()
            return remoteSideContext.getInstagramFeaturesJson()
        }

        override fun startCallDownload(
            startTimestamp: Long,
            author: String
        ): CallDownloadSession {
            return CallDownloadSessionImpl(
                context = remoteSideContext,
                callStartTimestamp = startTimestamp,
                author = author
            )
        }
    }
}
