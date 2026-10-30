package me.eternal.purrfect.core.features.impl.experiments

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import androidx.core.content.edit
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import me.eternal.purrfect.bridge.AutoOpenInterface
import me.eternal.purrfect.common.BuildConfig
import me.eternal.purrfect.common.config.PropertyValue
import me.eternal.purrfect.common.config.ModConfig
import me.eternal.purrfect.common.data.ContentType
import me.eternal.purrfect.common.data.MessageState
import me.eternal.purrfect.common.data.MessageUpdate
import me.eternal.purrfect.common.data.MessagingRuleType
import me.eternal.purrfect.core.event.events.impl.BuildMessageEvent
import me.eternal.purrfect.core.wrapper.impl.Message
import me.eternal.purrfect.core.features.MessagingRuleFeature
import me.eternal.purrfect.core.features.impl.messaging.Messaging
import me.eternal.purrfect.core.features.impl.tweaks.PerformanceMode
import me.eternal.purrfect.core.util.hook.HookStage
import me.eternal.purrfect.core.util.hook.hook
import me.eternal.purrfect.core.util.hook.hookConstructor
import java.util.*
import java.util.Objects
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.random.Random

class AutoOpenSnaps: MessagingRuleFeature("Auto Open Snaps", MessagingRuleType.AUTO_OPEN_SNAPS) {
    private val messaging by lazy { context.features.get(Messaging::class)!! }
    
    companion object {
        const val ACTION_PAUSE_RESUME = "me.eternal.purrfect.AUTO_OPEN_SNAPS_PAUSE_RESUME"
        const val ACTION_CLEAR_QUEUE = "me.eternal.purrfect.AUTO_OPEN_SNAPS_CLEAR_QUEUE"
        
        private const val STATUS_NOTIFICATION_ID = 54321
        private const val NOTIFICATION_GROUP_KEY = "purrfect.AUTO_OPEN"
        private const val PREF_TOTAL_OPENED = "auto_open_total_opened"
        private const val PREF_SESSION_START = "auto_open_session_start"
        private const val PREF_SAVED_QUEUE = "auto_open_saved_queue"
    }

    private val gson = Gson()
    private val isPaused = AtomicBoolean(false)
    private val engineActive = AtomicBoolean(true)
    private val totalProcessed = AtomicInteger(0)
    private val sessionProcessed = AtomicInteger(0)
    private val sessionStartTime = AtomicLong(System.currentTimeMillis())
    private val totalPausedDuration = AtomicLong(0)
    private var lastPausedAt = AtomicLong(0)
    private val averageProcessingTime = AtomicLong(800)
    private val isScreenOn = AtomicBoolean(true)

    private val snapQueue = MutableSharedFlow<Long>(extraBufferCapacity = 100)
    private val openedSnaps = ConcurrentHashMap.newKeySet<Long>()
    private val queuedSnaps = mutableListOf<SnapQueueItem>()
    private val deadLetterQueue = mutableListOf<SnapQueueItem>()

    private val metadataCache = Collections.synchronizedMap(object : LinkedHashMap<String, String>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > 500
    })

    private val config by lazy { context.config.messaging.autoOpenSnaps }
    private val notificationManager by lazy { context.androidContext.getSystemService(NotificationManager::class.java) }
    private val prefs by lazy { context.androidContext.getSharedPreferences("me.eternal.purrfect_preferences", Context.MODE_PRIVATE) }

    private var lastConversationId: String? = null
    private var currentStatusText = "Monitoring..."
    private var currentSpeedText = "Full Speed"
    private var isCurrentlyWaiting = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var wakeLockCooldownJob: Job? = null
    private var lastQueueActivity = System.currentTimeMillis()

    private val lastNotificationUpdate = AtomicLong(0)
    private val notificationUpdateDelay = 1000L
    private val pendingNotificationUpdate = AtomicBoolean(false)
    private val snapTimestamps = LinkedList<Long>()

    private val isSaving = AtomicBoolean(false)
    private val needsSaving = AtomicBoolean(false)
    private var isThermalThrottled = false
    private var lastThermalThrottleAt = 0L
    private var lastNotificationStateHash = 0

    data class SnapQueueItem(
        val conversationId: String,
        val messageId: Long,
        val serverMessageId: Long?,
        val senderId: String,
        var senderName: String = "Pending...",
        var conversationType: String = "Processing",
        val contentType: String,
        val timestamp: Long = System.currentTimeMillis()
    )

    private val autoOpenInterface = object : AutoOpenInterface.Stub() {
        override fun getProcessedCount(): Int = sessionProcessed.get()
        override fun getQueueItems(): List<String> = synchronized(queuedSnaps) { queuedSnaps.map { gson.toJson(it) } }
        override fun reset() { clearInternalState() }
    }

    private fun clearInternalState() {
        sessionProcessed.set(0)
        totalProcessed.set(0)
        totalPausedDuration.set(0)
        lastPausedAt.set(0)
        sessionStartTime.set(System.currentTimeMillis())
        synchronized(queuedSnaps) { queuedSnaps.clear() }
        synchronized(deadLetterQueue) { deadLetterQueue.clear() }
        openedSnaps.clear()

        prefs.edit()
            .putLong(PREF_SESSION_START, System.currentTimeMillis())
            .remove(PREF_SAVED_QUEUE)
            .remove(PREF_TOTAL_OPENED)
            .apply()

        updateStatusNotification(force = true)
    }

    fun getSnapMetadata(clientMessageId: Long): SnapQueueItem? = synchronized(queuedSnaps) { queuedSnaps.find { it.messageId == clientMessageId } }

    fun getInterface(): AutoOpenInterface = autoOpenInterface

    private val actionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_PAUSE_RESUME -> {
                    val paused = !isPaused.get()
                    isPaused.set(paused)
                    if (paused) lastPausedAt.set(System.currentTimeMillis())
                    else {
                        if (lastPausedAt.get() > 0) totalPausedDuration.addAndGet(System.currentTimeMillis() - lastPausedAt.get())
                        snapQueue.tryEmit(System.currentTimeMillis())
                    }
                    updateStatusNotification(force = true)
                }
                ACTION_CLEAR_QUEUE -> clearInternalState()
                Intent.ACTION_SCREEN_ON -> { isScreenOn.set(true); updateStatusNotification(force = true) }
                Intent.ACTION_SCREEN_OFF -> isScreenOn.set(false)
            }
        }
    }

    override fun init() {
        val messaging = context.feature(Messaging::class)
        restorePersistence()
        engineActive.set(config.globalState == true)
        if (config.globalState != true) return

        if (config.allowRunningInBackground.get()) {
            acquireWakeLock()
            findClass("com.snapchat.client.duplex.DuplexClient\$CppProxy").apply {
                hook("appStateChanged", HookStage.BEFORE) { param ->
                    if (config.allowRunningInBackground.get()) {
                        val state = param.arg<Any>(0).toString()
                        if (state == "INACTIVE" || state == "BACKGROUND") param.setResult(null)
                    }
                }
                hookConstructor(HookStage.AFTER) { param ->
                    methods.firstOrNull { it.name == "appStateChanged" }?.let { method ->
                        val enumClass = method.parameterTypes[0]
                        val activeState = enumClass.enumConstants?.firstOrNull { it.toString() == "ACTIVE" || it.toString() == "FOREGROUND" }
                        if (activeState != null) method.invoke(param.thisObject<Any>(), activeState)
                    }
                }
            }
            findClass("com.snapchat.client.network_manager.NetworkManager\$CppProxy").apply {
                hook("onAppForegrounded", HookStage.BEFORE) { param -> if (config.allowRunningInBackground.get()) param.setResult(null) }
                hook("onAppBackgrounded", HookStage.BEFORE) { param -> if (config.allowRunningInBackground.get()) param.setResult(null) }
            }
        }

        createNotificationChannels()
        val filter = IntentFilter().apply {
            addAction(ACTION_PAUSE_RESUME)
            addAction(ACTION_CLEAR_QUEUE)
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }

        val batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_BATTERY_CHANGED && config.thermalProtection.get()) {
                    val temp = intent.getIntExtra("temperature", 0) / 10f
                    if (temp >= 40f && !isThermalThrottled) {
                        isThermalThrottled = true; lastThermalThrottleAt = System.currentTimeMillis()
                    } else if (isThermalThrottled && temp <= 36f && System.currentTimeMillis() - lastThermalThrottleAt > 600000) {
                        isThermalThrottled = false
                    }
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.androidContext.registerReceiver(actionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            context.androidContext.registerReceiver(batteryReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.androidContext.registerReceiver(actionReceiver, filter)
            context.androidContext.registerReceiver(batteryReceiver, filter)
        }

        if (synchronized(queuedSnaps) { queuedSnaps.isNotEmpty() }) snapQueue.tryEmit(System.currentTimeMillis())

        // Watchdog Loop
        context.coroutineScope.launch(Dispatchers.Default) {
            while (isActive && engineActive.get()) {
                val remainingCount = synchronized(queuedSnaps) { queuedSnaps.size }

                if (remainingCount > 0) {
                    lastQueueActivity = System.currentTimeMillis(); acquireWakeLock()
                    if (!isPaused.get()) snapQueue.tryEmit(System.currentTimeMillis())
                } else {
                    if (!isPaused.get() && System.currentTimeMillis() - lastQueueActivity > 300000) {
                        val revived = synchronized(deadLetterQueue) { if (deadLetterQueue.isNotEmpty()) deadLetterQueue.removeAt(0) else null }
                        if (revived != null) { synchronized(queuedSnaps) { queuedSnaps.add(revived) }; snapQueue.tryEmit(System.currentTimeMillis()) }
                    }
                    if (System.currentTimeMillis() - lastQueueActivity > 300000) {
                        startWakeLockCooldown()
                    }
                }
                updateStatusNotification()
                delay(5000)
            }
        }

        // Processing Loop
        context.coroutineScope.launch(Dispatchers.Default) {
            snapQueue.collect {
                if (isPaused.get()) return@collect
                while (isActive && engineActive.get()) {
                    val item = synchronized(queuedSnaps) { if (queuedSnaps.isNotEmpty()) queuedSnaps.removeAt(0) else null } ?: break

                    var resourceWaiting = true
                    while (resourceWaiting) {
                        if (isPaused.get() || !engineActive.get()) break
                        val isWifi = isWifiConnected()
                        val isIdle = isDeviceIdle()
                        val onlyIdle = config.onlyWhenIdle.get()
                        val inSleepWindow = if (onlyIdle) isInsideSleepWindow() else false

                        when {
                            config.onlyOnWifi.get() && !isWifi -> {
                                currentStatusText = "Waiting for WiFi..."; currentSpeedText = "Throttled"; isCurrentlyWaiting = true; delay(5000)
                            }
                            onlyIdle && !isIdle && !inSleepWindow -> {
                                currentStatusText = "Waiting for idle..."; currentSpeedText = "Throttled"; isCurrentlyWaiting = true; delay(5000)
                            }
                            else -> {
                                resourceWaiting = false;
                                val thermalActive = config.thermalProtection.get() && isThermalThrottled
                                currentSpeedText = if (inSleepWindow || thermalActive) "Throttled" else "Full Speed"
                            }
                        }
                        if (resourceWaiting) updateStatusNotification()
                    }

                    if (isPaused.get() || !engineActive.get()) { synchronized(queuedSnaps) { queuedSnaps.add(0, item) }; continue }
                    isCurrentlyWaiting = false

                    if (lastConversationId != null && lastConversationId != item.conversationId) { delay(40) }
                    lastConversationId = item.conversationId
                    currentStatusText = "Active"; updateStatusNotification()

                    var success = false
                    val startTime = System.currentTimeMillis()
                    var currentRetryDelay = config.retryDelay.get().toLong()

                    for (i in 0 until config.retryAttempts.get()) {
                        if (isPaused.get() || !engineActive.get()) break

                        if (messaging.conversationManager == null) {
                            runCatching { context.messagingBridge.triggerSessionStart() }
                            var waitTime = 0
                            while (messaging.conversationManager == null && waitTime < 2000) { delay(100); waitTime += 100 }
                        }

                        success = performOpen(messaging, item)
                        if (success) {
                            sessionProcessed.incrementAndGet()
                            totalProcessed.incrementAndGet()
                            synchronized(snapTimestamps) { snapTimestamps.addLast(System.currentTimeMillis()); if (snapTimestamps.size > 100) snapTimestamps.removeFirst() }  
                            val duration = System.currentTimeMillis() - startTime
                            averageProcessingTime.set((averageProcessingTime.get() * 0.7 + duration * 0.3).toLong())
                            delay(5)
                            break
                        }
                        if (i < config.retryAttempts.get() - 1) {
                            currentStatusText = "Retrying..."; updateStatusNotification(); delay(currentRetryDelay); currentRetryDelay *= 2
                        }
                    }

                    if (!success && !isPaused.get() && engineActive.get()) {
                        currentStatusText = "Failed: ${item.senderName}"; updateStatusNotification()
                        synchronized(openedSnaps) { openedSnaps.remove(item.messageId) }
                        synchronized(deadLetterQueue) { if (deadLetterQueue.size < 100) deadLetterQueue.add(item) else { deadLetterQueue.removeAt(0); deadLetterQueue.add(item) } }
                    }

                    if (synchronized(queuedSnaps) { queuedSnaps.isEmpty() }) {
                        currentStatusText = "Monitoring..."; updateStatusNotification()
                        delay(50)
                    }
                }
            }
        }

        // Global Detector (Database-First Filter)
        context.event.subscribe(BuildMessageEvent::class, priority = 103) { event ->
            if (config.globalState != true || !engineActive.get()) return@subscribe

            val message = event.message
            if (message.messageState != MessageState.COMMITTED || message.senderId?.toString() == context.database.myUserId) return@subscribe

            val conversationId = message.messageDescriptor?.conversationId?.toString() ?: return@subscribe
            val clientMessageId = message.messageDescriptor?.messageId ?: return@subscribe
            val serverMsgId = message.orderKey
            val contentType = message.messageContent?.contentType
            if (contentType != ContentType.SNAP && contentType != ContentType.EXTERNAL_MEDIA) return@subscribe

            // WHITESPACE RULE CHECK
            val ruleState = context.config.rules.getRuleState(ruleType)
            val isWhitelisted = getState(conversationId)
            val canProcess = if (ruleState == me.eternal.purrfect.common.data.RuleState.BLACKLIST) !isWhitelisted else isWhitelisted

            if (!canProcess) return@subscribe

            // DATABASE-FIRST FILTER: Stop Queue Explosion on startup using Messaging unread cache
            val unreadIds = messaging.getFeedCachedMessageIds(conversationId)
            if (unreadIds?.contains(clientMessageId) != true) return@subscribe

            acquireWakeLock()
            synchronized(openedSnaps) {
                if (openedSnaps.contains(clientMessageId)) return@subscribe
                openedSnaps.add(clientMessageId)
                if (openedSnaps.size > 5000) openedSnaps.clear()
            }

            val senderId = message.senderId?.toString() ?: "unknown"
            val item = SnapQueueItem(conversationId, clientMessageId, serverMsgId, senderId, getSenderDisplayName(senderId), getConversationType(conversationId, senderId), getSnapContentType(contentType))

            synchronized(queuedSnaps) {
                if (queuedSnaps.size >= config.queueSize.get()) queuedSnaps.removeFirstOrNull()
                queuedSnaps.add(item)
            }

            if (context.config.messaging.preFetchSnaps.get()) {
                runCatching { messaging.conversationManager?.fetchMessage(conversationId, clientMessageId, {}, {}) }
            }

            if (!isPaused.get()) snapQueue.tryEmit(System.currentTimeMillis())
            updateStatusNotification()
            triggerLazySave()
        }
    }

    private suspend fun performOpen(messaging: Messaging, item: SnapQueueItem): Boolean = withContext(Dispatchers.IO) {
        // v1.6.8 Efficiency: Switch conversation delay
        if (lastConversationId != null && lastConversationId != item.conversationId) delay(40)
        lastConversationId = item.conversationId

        val manager = messaging.conversationManager ?: return@withContext false
        val success = kotlin.coroutines.suspendCoroutine<Boolean> { cont ->
            runCatching {
                manager.updateMessage(item.conversationId, item.messageId, MessageUpdate.READ) { result ->
                    if (result == null || result == "DUPLICATEREQUEST") {
                        cont.resume(true)
                    } else if (item.serverMessageId != null) {
                        manager.updateMessage(item.conversationId, item.serverMessageId, MessageUpdate.READ) { serverResult ->
                            cont.resume(serverResult == null || serverResult == "DUPLICATEREQUEST")
                        }
                    } else {
                        cont.resume(false)
                    }
                }
            }.onFailure { cont.resume(false) }
        }
        
        if (success) {
            // v1.6.8 Efficiency: Inter-snap delay
            delay(5)
        }
        success
    }

    private fun getSnapsPerSecond(): Double {
        val now = System.currentTimeMillis(); val window = 5000L
        synchronized(snapTimestamps) {
            snapTimestamps.removeIf { now - it > window }; return (snapTimestamps.size.toDouble() / (window / 1000.0))
        }
    }

    private fun updateStatusNotification(force: Boolean = false) {
        val currentTime = System.currentTimeMillis()
        if (!isScreenOn.get() && !force) return
        val lastUpdate = lastNotificationUpdate.get()
        if (!force && (currentTime - lastUpdate) < notificationUpdateDelay) {
            if (pendingNotificationUpdate.compareAndSet(false, true)) {
                context.coroutineScope.launch { delay(notificationUpdateDelay - (currentTime - lastUpdate)); pendingNotificationUpdate.set(false); updateStatusNotificationInternal() }
            }
            return
        }
        lastNotificationUpdate.set(currentTime); updateStatusNotificationInternal()
    }

    private fun logError(msg: String, e: Throwable? = null) {
        if (e != null) context.log.error("[AutoOpenEngine] $msg", e)
        else context.log.error("[AutoOpenEngine] $msg")
    }

    private fun updateStatusNotificationInternal() {
        if (!engineActive.get()) return
        val processed = sessionProcessed.get()
        val total = totalProcessed.get()
        val remaining = synchronized(queuedSnaps) { queuedSnaps.size }

        val currentStateHash = Objects.hash(processed, total, remaining, currentStatusText, isPaused.get())
        if (currentStateHash == lastNotificationStateHash && remaining == 0) return
        lastNotificationStateHash = currentStateHash

        val isWorking = remaining > 0
        val speed = if (isWorking) getSnapsPerSecond() else 0.0

        val sessionTotal = processed + remaining
        val progressPercent = if (sessionTotal > 0) (processed * 100) / sessionTotal else 0
        val eta = if (isWorking && !isPaused.get()) formatDuration(remaining * averageProcessingTime.get()) else "..."

        val builder = Notification.Builder(context.androidContext, "auto_open_status")
            .setOngoing(isWorking).setOnlyAlertOnce(true).setGroup(NOTIFICATION_GROUP_KEY)

        builder.setSmallIcon(if (isPaused.get()) android.R.drawable.ic_media_pause else if (!isWorking) android.R.drawable.ic_popup_sync else android.R.drawable.ic_media_play)
        builder.setContentTitle("Auto-Open: $currentStatusText")

        val isCompact = (config.compactNotification as PropertyValue<Boolean>).get()
        if (isWorking) {
            builder.setContentText("Opened: $processed │ Queue: $remaining ($progressPercent%)")
            if (isCompact) {
                builder.setSubText("Speed: ${String.format(Locale.US, "%.1f", speed)}/s • Ends in: $eta")
            } else {
                builder.setSubText("")
            }
            builder.setProgress(sessionTotal, processed, false)
        } else {
            builder.setContentText("$processed Opened Today │ $total Total")
            builder.setSubText(null)
            builder.setProgress(0, 0, false)
        }

        builder.addAction(Notification.Action.Builder(null, if (isPaused.get()) "Resume" else "Pause", createPendingIntent(ACTION_PAUSE_RESUME)).build())
        builder.addAction(Notification.Action.Builder(null, "Clear", createPendingIntent(ACTION_CLEAR_QUEUE)).build())

        if (!isCompact) {
            val recentSnaps = synchronized(queuedSnaps) { queuedSnaps.takeLast(5) }
            val bigTextStyle = Notification.BigTextStyle().setSummaryText(null)
            val detailText = buildString {
                append("QUEUE STATISTICS\n")
                append("├─ Opened: $processed snaps\n")
                append("├─ Queue: $remaining snaps • Ends in: $eta\n")
                if ((config.showLifetimeStats as PropertyValue<Boolean>).get()) {
                    append("├─ Total Opened: $total snaps\n")
                }
                val speedNotion = if (isWorking) currentSpeedText else "Idle"
                val speedValue = "${String.format(Locale.US, "%.1f", speed)}/s"
                append("└─ Speed: $speedNotion ($speedValue)")

                if ((config.showQueuePreview as PropertyValue<Boolean>).get()) {
                    append("\n\nQUEUE PREVIEW\n")
                    if (isWorking && remaining > 0) {
                        recentSnaps.reversed().forEach { item ->
                            append("• ${item.senderName} │ ${item.conversationType} (${item.contentType})\n")
                        }
                    } else {
                        append("Monitoring snaps in background...")
                    }
                }
            }
            bigTextStyle.bigText(detailText)
            builder.setStyle(bigTextStyle)
        }

        runCatching { notificationManager.notify(STATUS_NOTIFICATION_ID, builder.build()) }.onFailure { logError("Failed to update notification", it) }
    }

    private fun formatDuration(m: Long): String {
        val s = (m / 1000) % 60; val min = (m / 60000) % 60; val h = m / 3600000
        return when { h > 0 -> "${h}h ${min}m"; min > 0 -> "${min}m ${s}s"; else -> "${s}s" }
    }

    private fun triggerLazySave() {
        needsSaving.set(true)
        if (isSaving.compareAndSet(false, true)) {
            context.coroutineScope.launch(Dispatchers.IO) {
                while (needsSaving.get()) { needsSaving.set(false); saveToDiskInternal(); delay(300000) }
                isSaving.set(false)
            }
        }
    }

    private fun saveToDiskInternal() {
        prefs.edit {
            putInt(PREF_TOTAL_OPENED, totalProcessed.get())
            putLong(PREF_SESSION_START, sessionStartTime.get())
            synchronized(queuedSnaps) { putString(PREF_SAVED_QUEUE, gson.toJson(queuedSnaps)) }
        }
    }

    private fun restorePersistence() {
        val savedStartTime = prefs.getLong(PREF_SESSION_START, 0)
        val now = System.currentTimeMillis()
        if (now - savedStartTime > 21600000) return
        totalProcessed.set(prefs.getInt(PREF_TOTAL_OPENED, 0)); sessionStartTime.set(savedStartTime)
        val savedQueueJson = prefs.getString(PREF_SAVED_QUEUE, null)
        if (!savedQueueJson.isNullOrBlank()) {
            runCatching {
                val restored: List<SnapQueueItem> = gson.fromJson(savedQueueJson, object : TypeToken<List<SnapQueueItem>>() {}.type)
                synchronized(queuedSnaps) { queuedSnaps.addAll(restored.filter { (now - it.timestamp) < 3600000 }) }
            }
        }
    }

    private fun isInsideSleepWindow(): Boolean {
        try {
            val window = config.sleepWindow.get().split("-"); if (window.size != 2) return false
            val start = window[0].split(":"); val end = window[1].split(":")
            val now = Calendar.getInstance().apply { set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
            val s = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, start[0].toInt()); set(Calendar.MINUTE, start[1].toInt()); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
            val e = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, end[0].toInt()); set(Calendar.MINUTE, end[1].toInt()); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
            return if (e.before(s)) now.after(s) || now.before(e) else now.after(s) && now.before(e)
        } catch (e: Exception) { return false }
    }

    private fun isWifiConnected(): Boolean {
        val cm = context.androidContext.getSystemService(ConnectivityManager::class.java) ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
        } else {
            @Suppress("DEPRECATION") cm.activeNetworkInfo?.type == ConnectivityManager.TYPE_WIFI
        }
    }

    private fun isDeviceIdle(): Boolean = (context.androidContext.getSystemService(Context.POWER_SERVICE) as PowerManager).isDeviceIdleMode

    private fun acquireWakeLock() {
        wakeLockCooldownJob?.cancel()
        if (wakeLock == null) {
            val pm = context.androidContext.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Purrfect:AutoOpen").apply { setReferenceCounted(false) }
        }
        wakeLock?.acquire(8 * 60 * 60 * 1000L)
    }

    private fun releaseWakeLock() { 
        runCatching {
            if (wakeLock?.isHeld == true) wakeLock?.release()
            wakeLock = null 
        }
    }

    private fun startWakeLockCooldown(timeout: Long = 600000L) { // 10m Default
        wakeLockCooldownJob?.cancel()
        wakeLockCooldownJob = context.coroutineScope.launch {
            delay(timeout)
            if (synchronized(queuedSnaps) { queuedSnaps.isEmpty() }) {
                releaseWakeLock()
            }
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(NotificationChannel("auto_open_status", "Auto Open Snaps", NotificationManager.IMPORTANCE_LOW).apply { enableVibration(false); setSound(null, null) })
        }
    }

    private fun createPendingIntent(action: String): PendingIntent {
        val intent = Intent(action).setPackage(context.androidContext.packageName)
        return PendingIntent.getBroadcast(context.androidContext, action.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun cancelStatusNotification() = notificationManager.cancel(STATUS_NOTIFICATION_ID)

    private fun getSenderDisplayName(id: String): String = metadataCache.getOrPut(id) { context.database.getFriendInfo(id)?.let { it.displayName ?: it.mutableUsername } ?: "Unknown" }

    private fun getConversationType(cid: String, sid: String): String = metadataCache.getOrPut("$cid:$sid") { if (context.database.getDMOtherParticipant(cid) != null) "Friend DM" else context.database.getFeedEntryByConversationId(cid)?.feedDisplayName ?: "Group Chat" }

    private fun getSnapContentType(type: ContentType?): String = when (type) {
        ContentType.SNAP -> "Photo/Video"
        ContentType.EXTERNAL_MEDIA -> "Media"
        else -> context.translation["auto_open_snaps.content_type_snap"] ?: "Snap"
    }
}
