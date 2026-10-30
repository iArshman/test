package me.eternal.purrfect.core.features.impl.messaging

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.core.app.NotificationCompat
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.input.KeyboardType
import kotlinx.coroutines.*
import me.eternal.purrfect.bridge.task.TaskListener
import me.eternal.purrfect.common.data.ContentType
import me.eternal.purrfect.common.ui.createComposeAlertDialog
import me.eternal.purrfect.common.util.protobuf.ProtoEditor
import me.eternal.purrfect.common.util.protobuf.ProtoReader
import me.eternal.purrfect.common.util.protobuf.ProtoWriter
import me.eternal.purrfect.core.ModContext
import me.eternal.purrfect.core.event.events.impl.MediaUploadEvent
import me.eternal.purrfect.core.event.events.impl.NativeUnaryCallEvent
import me.eternal.purrfect.core.event.events.impl.SendMessageWithContentEvent
import me.eternal.purrfect.core.event.events.impl.UnaryCallEvent
import me.eternal.purrfect.core.features.Feature
import me.eternal.purrfect.core.features.impl.experiments.MediaFilePicker
import me.eternal.purrfect.core.messaging.MessageSender
import me.eternal.purrfect.core.ui.PurrfectOverlayPalette
import me.eternal.purrfect.core.ui.PurrfectOverlayTheme
import me.eternal.purrfect.core.wrapper.impl.MessageContent
import me.eternal.purrfect.core.wrapper.impl.MessageDestinations
import me.eternal.purrfect.core.util.ktx.getObjectFieldOrNull
import me.eternal.purrfect.core.util.ktx.setObjectField
import me.eternal.purrfect.core.util.CallbackBuilder
import me.eternal.purrfect.core.util.hook.HookStage
import me.eternal.purrfect.core.util.hook.Hooker
import me.eternal.purrfect.core.util.hook.hook
import me.eternal.purrfect.core.util.hook.hookConstructor
import me.eternal.purrfect.mapper.impl.CallbackMapper
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Collections
import java.util.IdentityHashMap
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.DurationUnit
import kotlin.time.toDuration
import me.eternal.purrfect.core.util.ktx.vibrateLongPress


@OptIn(ExperimentalMaterial3Api::class)
class SendOverride : Feature("Send Override") {
    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "scheduled_send"
        private const val CONTINUOUS_SEND_CHANNEL_ID = "continuous_send_status"
        private const val STATUS_NOTIFICATION_ID = 54322
        private const val COMPLETION_NOTIFICATION_ID = 54323

        private val internalMultipartSend = ThreadLocal.withInitial { false }
        private var queuedOriginalItemRepeatCount = 0
        private var queuedOriginalItemRepeatOverrideType: String? = null
        
        // Progress Tracking
        private var totalRepeatCount = 0
        private var processedRepeatCount = 0
        private var currentRecipientName: String = "Unknown"
        private val engineActive = AtomicBoolean(false)

        private fun queueOriginalItemRepeats(context: ModContext, repeatCount: Int, overrideType: String) {
            queuedOriginalItemRepeatCount = repeatCount
            queuedOriginalItemRepeatOverrideType = overrideType
            MediaFilePicker.setQueuedOverrideType(overrideType)
            engineActive.set(true)
            context.log.info("Continuous Send: Starting task for $currentRecipientName (Total: ${repeatCount + 1})")
        }

        private fun resetEngine(context: ModContext) {
            context.log.info("Continuous Send: Engine reset. All states cleared.")
            queuedOriginalItemRepeatCount = 0
            queuedOriginalItemRepeatOverrideType = null
            totalRepeatCount = 0
            processedRepeatCount = 0
            engineActive.set(false)
            MediaFilePicker.clearQueuedSplitItems(true)
        }

        private fun updateContinuousSendNotification(context: ModContext) {
            val notificationManager = context.androidContext.getSystemService(NotificationManager::class.java)
            val remaining = queuedOriginalItemRepeatCount
            val processed = processedRepeatCount
            val total = totalRepeatCount
            
            // Logic: If there is a total but nothing remaining, we are finished.
            val isFinished = total > 0 && remaining <= 0

            if (isFinished || !engineActive.get()) {
                if (total > 0) {
                    notificationManager.cancel(STATUS_NOTIFICATION_ID)
                    showCompletionNotification(context, processed, total)
                    resetEngine(context)
                }
                return
            }

            val progressPercent = if (total > 0) (processed * 100) / total else 0

            val builder = Notification.Builder(context.androidContext, CONTINUOUS_SEND_CHANNEL_ID)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSmallIcon(android.R.drawable.ic_popup_sync)
                .setColor(0xFF3498DB.toInt())
                .setContentTitle("Sending Snaps to $currentRecipientName")
                .setContentText("Progress: $processed / $total ($progressPercent%)")
                .setSubText("$processed / $total")
                .setProgress(total, processed, false)

            notificationManager.notify(STATUS_NOTIFICATION_ID, builder.build())
        }

        private fun showCompletionNotification(context: ModContext, sent: Int, total: Int) {
            val isError = sent < total
            val title = if (isError) "Continuous Send Failed" else "Continuous Send Finished"
            val content = "Sent $sent / $total snaps to $currentRecipientName"
            
            if (isError) {
                context.log.error("Continuous Send: Task ended prematurely. Failed at step ${sent + 1} of $total")
            } else {
                context.log.info("Continuous Send: Task completed successfully ($total/$total)")
            }

            val notificationManager = context.androidContext.getSystemService(NotificationManager::class.java)
            val builder = Notification.Builder(context.androidContext, CONTINUOUS_SEND_CHANNEL_ID)
                .setSmallIcon(if (isError) android.R.drawable.stat_notify_error else android.R.drawable.checkbox_on_background)
                .setColor(if (isError) 0xFFE74C3C.toInt() else 0xFF2ECC71.toInt())
                .setContentTitle(title)
                .setContentText(content)
                .setAutoCancel(true)

            notificationManager.notify(COMPLETION_NOTIFICATION_ID, builder.build())
        }

        private fun handleQueuedOriginalItemRepeatSuccess(context: ModContext): Boolean {
            if (queuedOriginalItemRepeatCount <= 0 || !engineActive.get()) {
                updateContinuousSendNotification(context)
                return false
            }

            val overrideType = queuedOriginalItemRepeatOverrideType ?: run {
                updateContinuousSendNotification(context)
                return false
            }

            processedRepeatCount++
            queuedOriginalItemRepeatCount--
            updateContinuousSendNotification(context)

            MediaFilePicker.setQueuedOverrideType(overrideType)
            val result = MediaFilePicker.sendReusableOriginalItem()
            if (!result) {
                context.log.error("Continuous Send: Failed to trigger reusable item send at step $processedRepeatCount.")
                updateContinuousSendNotification(context)
            }
            return result
        }
    }

    private var selectedType by mutableStateOf("SNAP")
    private var disableSplitForCurrentSend by mutableStateOf(false)
    private var customDuration by mutableFloatStateOf(10f)
    private var scheduledTime by mutableStateOf<Long?>(null)
    private var showClockPicker by mutableStateOf(false)
    private var clockPickerHour by mutableIntStateOf(12)
    private var clockPickerMinute by mutableIntStateOf(0)
    private var notificationIdCounter = 1000
    private val backgroundHookLock = Any()
    private var backgroundHookRefs = 0
    private var backgroundHooks: List<Hooker.HookHandle>? = null

    private fun createContinuousNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.androidContext.getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CONTINUOUS_SEND_CHANNEL_ID,
                "Continuous Send",
                NotificationManager.IMPORTANCE_LOW
            )
            channel.description = "Progress status for continuous snap sending"
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun acquireScheduledSendBackground(): () -> Unit {
        if (!context.config.messaging.scheduledSendAllowRunningInBackground.get()) return {}
        var enableFailed = false
        synchronized(backgroundHookLock) {
            backgroundHookRefs++
            if (backgroundHookRefs == 1) {
                if (!enableScheduledSendBackgroundLocked()) {
                    backgroundHookRefs--
                    enableFailed = true
                }
            }
        }
        if (enableFailed) return {}
        var released = false
        return {
            synchronized(backgroundHookLock) {
                if (released) return@synchronized
                released = true
                if (backgroundHookRefs > 0) backgroundHookRefs--
                if (backgroundHooks != null && backgroundHookRefs == 0) {
                    backgroundHooks?.forEach { it.unhook() }
                    backgroundHooks = null
                }
            }
        }
    }

    private fun enableScheduledSendBackgroundLocked(): Boolean {
        return runCatching {
            val duplexClass = findClass("com.snapchat.client.duplex.DuplexClient\$CppProxy")
            val appStateMethod = duplexClass.methods.firstOrNull { it.name == "appStateChanged" } ?: return false
            val hooks = mutableListOf<Hooker.HookHandle>()
            hooks.addAll(
                duplexClass.hook("appStateChanged", HookStage.BEFORE) { param ->
                    if (param.arg<Any>(0).toString() == "INACTIVE") param.setResult(null)
                }
            )
            hooks.addAll(
                duplexClass.hookConstructor(HookStage.AFTER) { param ->
                    val activeValue = appStateMethod.parameterTypes[0].enumConstants?.firstOrNull { it.toString() == "ACTIVE" }
                        ?: return@hookConstructor
                    appStateMethod.invoke(param.thisObject(), activeValue)
                }
            )
            backgroundHooks = hooks
            true
        }.getOrElse {
            context.log.error("Failed to enable scheduled send background mode", it)
            false
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.androidContext.getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Scheduled Send",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            channel.description = "Notifications for scheduled snap sends"
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun showNotification(title: String, content: String) {
        val notificationManager = context.androidContext.getSystemService(NotificationManager::class.java)
        val builder = NotificationCompat.Builder(context.androidContext, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
        notificationManager.notify(notificationIdCounter++, builder.build())
    }

    @OptIn(ExperimentalLayoutApi::class)
    override fun init() {
        createNotificationChannel()
        createContinuousNotificationChannel()

        val stripMediaMetadata = context.config.messaging.stripMediaMetadata.get()
        var postSavePolicy: Int? = null

        val configOverrideType = context.config.messaging.galleryMediaSendOverride.mode.getNullable()?.toString()
        if (configOverrideType == null && stripMediaMetadata.isEmpty()) return

        context.event.subscribe(MediaUploadEvent::class) { event ->
            // Handle audio notes separately since they don't have path 11, 5
            if (stripMediaMetadata.isNotEmpty() &&
                (event.localMessageContent.contentType == ContentType.NOTE ||
                 stripMediaMetadata.contains("remove_audio_note_duration") ||
                 stripMediaMetadata.contains("remove_audio_note_transcript_capability"))) {
                event.onMediaUploaded { result ->
                    if (result.messageContent.contentType == ContentType.NOTE) {
                        val contentReader = ProtoReader(result.messageContent.content!!)
                        result.messageContent.content = ProtoEditor(result.messageContent.content!!).apply {
                            // Check which path structure exists - try both to be safe
                            val hasFullPath = contentReader.followPath(4, 4, 6, 1, 1) != null
                            val hasDirectPath = contentReader.followPath(6, 1, 1) != null

                            if (stripMediaMetadata.contains("remove_audio_note_duration")) {
                                // Audio note duration is at field 13 (confirmed from MessageDecoder line 94 and MessageSender line 27)
                                if (hasFullPath) {
                                    edit(4, 4, 6, 1, 1) {
                                        remove(13)
                                    }
                                }
                                if (hasDirectPath || !hasFullPath) {
                                    edit(6, 1, 1) {
                                        remove(13)
                                    }
                                }
                            }
                            if (stripMediaMetadata.contains("remove_audio_note_transcript_capability")) {
                                if (hasFullPath) {
                                    edit(4, 4, 6, 1) {
                                        remove(3) // locale string
                                    }
                                }
                                if (hasDirectPath || !hasFullPath) {
                                    edit(6, 1) {
                                        remove(3) // locale string
                                    }
                                }
                                runCatching {
                                    result.messageContent.instanceNonNull().setObjectField("mAllowsTranscription", false)
                                }
                            }
                        }.toByteArray()
                    }
                }
            }

            ProtoReader(event.localMessageContent.content!!).followPath(11, 5)?.let { snapDocPlayback ->
                event.onMediaUploaded { result ->
                    result.messageContent.content = ProtoEditor(result.messageContent.content!!).apply {
                        edit(11, 5) {
                            edit(1) {
                                edit(1) {
                                    snapDocPlayback.getVarInt(2, 99)?.let { customDuration ->
                                        remove(15)
                                        addVarInt(15, customDuration)
                                    }
                                    remove(27)
                                    remove(26)
                                    addBuffer(26, byteArrayOf())
                                }
                            }

                            // set back the original snap duration
                            snapDocPlayback.getByteArray(2)?.let {
                                val originalHasSound = firstOrNull(2)?.toReader()?.getVarInt(5)
                                remove(2)
                                addBuffer(2, it)

                                originalHasSound?.let { hasSound ->
                                    edit(2) {
                                        remove(5)
                                        addVarInt(5, hasSound)
                                    }
                                }
                            }
                        }

                        if (stripMediaMetadata.isNotEmpty()) {
                            when (result.messageContent.contentType) {
                                ContentType.SNAP, ContentType.EXTERNAL_MEDIA -> {
                                    edit(*(if (result.messageContent.contentType == ContentType.SNAP) intArrayOf(11) else intArrayOf(3, 3))) {
                                        if (stripMediaMetadata.contains("hide_caption_text")) {
                                            edit(5) {
                                                editEach(1) {
                                                    remove(2)
                                                }
                                            }
                                        }
                                        if (stripMediaMetadata.contains("hide_snap_filters")) {
                                            remove(9)
                                            remove(11)
                                        }
                                        if (stripMediaMetadata.contains("hide_extras")) {
                                            remove(13)
                                            edit(5, 1) {
                                                remove(2)
                                            }
                                        }
                                    }
                                }
                                ContentType.NOTE -> {
                                    if (stripMediaMetadata.contains("remove_audio_note_duration")) {
                                        edit(6, 1, 1) {
                                            remove(13)
                                        }
                                    }
                                    if (stripMediaMetadata.contains("remove_audio_note_transcript_capability")) {
                                        edit(6, 1) {
                                            remove(3)
                                        }
                                    }
                                }
                                else -> {}
                            }
                        }

                        edit(11, 5, 2) {
                            remove(99)
                        }
                    }.toByteArray()
                }
            }
        }

        if (configOverrideType == null) return

        context.event.subscribe(NativeUnaryCallEvent::class, priority = 100) { event ->
            if (event.uri != "/messagingcoreservice.MessagingCoreService/CreateContentMessage") return@subscribe
            postSavePolicy?.let { savePolicy ->
                context.log.verbose("postSavePolicy=$savePolicy")
                val protoReader = ProtoReader(event.buffer)
                event.buffer = ProtoEditor(event.buffer).apply {
                    // Handle chat messages (field 4)
                    if (protoReader.followPath(4) != null) {
                        edit(4) {
                            remove(7)
                            addVarInt(7, savePolicy)
                        }

                        // remove Keep Snaps in Chat ability
                        if (savePolicy == 1/* PROHIBITED */) {
                            edit(6, 9) {
                                remove(1)
                            }
                        }
                    }

                    // Handle NOTE messages (field 6) - audio notes
                    // Check both root level (6) and nested under media (4, 4, 6)
                    val noteAtRoot = protoReader.followPath(6) != null
                    val noteNested = protoReader.followPath(4, 4, 6) != null

                    if (noteAtRoot || noteNested) {
                        // Set save policy in the NOTE path
                        val hasNestedPath = if (noteNested) {
                            protoReader.followPath(4, 4, 6, 1, 1) != null
                        } else {
                            protoReader.followPath(6, 1, 1) != null
                        }

                        if (noteNested) {
                            if (hasNestedPath) {
                                edit(4, 4, 6, 1, 1) {
                                    remove(7)
                                    addVarInt(7, savePolicy)
                                }
                            } else {
                                edit(4, 4, 6, 1) {
                                    remove(7)
                                    addVarInt(7, savePolicy)
                                }
                            }
                        } else {
                            if (hasNestedPath) {
                                edit(6, 1, 1) {
                                    remove(7)
                                    addVarInt(7, savePolicy)
                                }
                            } else {
                                edit(6, 1) {
                                    remove(7)
                                    addVarInt(7, savePolicy)
                                }
                            }
                        }
                    }

                    // Handle SNAP messages (field 11)
                    val snapAtRoot = protoReader.followPath(11) != null
                    val snapNested = protoReader.followPath(4, 4, 11) != null
                    if (snapAtRoot || snapNested) {
                        if (snapNested) {
                            edit(4, 4, 11) {
                                remove(7)
                                addVarInt(7, savePolicy)
                            }
                        } else {
                            edit(11) {
                                remove(7)
                                addVarInt(7, savePolicy)
                            }
                        }
                    }
                }.toByteArray()
            }
        }

        context.event.subscribe(UnaryCallEvent::class, priority = 100) { event ->
            if (event.uri != "/messagingcoreservice.MessagingCoreService/CreateContentMessage") return@subscribe
        }

        context.event.subscribe(SendMessageWithContentEvent::class, priority = -100) { event ->
            if (internalMultipartSend.get() == true) return@subscribe
            postSavePolicy = null
            if (event.destinations.stories?.isNotEmpty() == true && event.destinations.conversations?.isEmpty() == true) return@subscribe
            val localMessageContent = event.messageContent
            // Allow both EXTERNAL_MEDIA (gallery) and SNAP (camera)
            if (localMessageContent.contentType != ContentType.EXTERNAL_MEDIA &&
                localMessageContent.contentType != ContentType.SNAP &&
                localMessageContent.instanceNonNull().getObjectFieldOrNull("mExternalContentMetadata") == null) return@subscribe
            val includeCameraSnaps = context.config.messaging.galleryMediaSendOverride.includeCameraSnaps.get()
            if (localMessageContent.contentType == ContentType.SNAP && !includeCameraSnaps) return@subscribe

            //prevent story replies
            val messageProtoReader = ProtoReader(localMessageContent.content ?: return@subscribe)
            if (messageProtoReader.contains(7)) return@subscribe

            val conversationIds = event.destinations.conversations?.map { it.toString() } ?: return@subscribe
            if (conversationIds.isEmpty()) return@subscribe

            val recipientNames = conversationIds.mapNotNull { convId ->
                runCatching {
                    val dmParticipant = context.database.getDMOtherParticipant(convId)
                    if (dmParticipant != null) {
                        context.database.getFriendInfo(dmParticipant)?.displayName ?: context.database.getFriendInfo(dmParticipant)?.mutableUsername
                    } else {
                        context.database.getFeedEntryByConversationId(convId)?.feedDisplayName
                    }
                }.getOrNull()
            }.ifEmpty { listOf("Unknown") }

            val recipientName = recipientNames.joinToString(", ")

            event.canceled = true
            event.adapter.setResult(null)

            fun invokeOriginalAndRestoreResult(ev: SendMessageWithContentEvent) {
                val result = ev.adapter.invokeOriginal()
                ev.adapter.setResult(result)
                ev.canceled = false
            }

            val sendMessageCallbackClass by lazy {
                lateinit var result: Class<*>
                context.mappings.useMapper(CallbackMapper::class) {
                    result = callbacks.getClass("SendMessageCallback") ?: error("Failed to resolve SendMessageCallback")
                }
                result
            }

            fun cloneDestinations(source: MessageDestinations): Any {
                return context.gson.fromJson(
                    context.gson.toJson(source.instanceNonNull()),
                    context.classCache.messageDestinations
                )
            }

            val sendMessageWithContentMethod by lazy {
                sequence {
                    var current: Class<*>? = context.classCache.conversationManager
                    while (current != null && current != Any::class.java && current != Object::class.java) {
                        yield(current)
                        current = current.superclass
                    }
                }.flatMap { it.declaredMethods.asSequence() }
                    .first { it.name == "sendMessageWithContent" }
            }

            val originalMessageJson = context.gson.toJson(localMessageContent.instanceNonNull())
            val originalCallback = event.adapter.args().getOrNull(2)
            val conversationManagerInstance by lazy {
                context.feature(Messaging::class).conversationManager?.instanceNonNull()
            }

            fun invokeCallbackError(callback: Any?, error: Any?) {
                runCatching {
                    callback?.javaClass?.methods?.firstOrNull { method ->
                        method.name == "onError" && method.parameterCount == 1
                    }?.invoke(callback, error)
                }
            }

            fun applyOverride(
                targetMessageContent: MessageContent,
                targetReader: ProtoReader,
                overrideType: String,
                snapDurationMs: Int?
            ): Boolean {
                val bypassLimit = context.config.experimental.nativeHooks.valdiHooks.bypassCameraRollLimit.get()
                if (overrideType != "ORIGINAL" && !bypassLimit && (targetReader.followPath(3)?.getCount(3) ?: 0) > 1) {
                    context.inAppOverlay.showStatusToast(
                        icon = Icons.Default.WarningAmber,
                        context.translation["gallery_media_send_override.multiple_media_toast"]
                    )
                    return false
                }

                when (overrideType) {
                    "SNAP", "SAVEABLE_SNAP" -> {
                        val savePolicyValue = if (overrideType == "SAVEABLE_SNAP") 2 else 1
                        postSavePolicy = savePolicyValue

                        val extras = targetReader.followPath(3, 3, 13)?.getBuffer()

                        if (targetMessageContent.contentType != ContentType.SNAP) {
                            targetMessageContent.content = ProtoWriter().apply {
                                from(11) {
                                    from(5) {
                                        from(1) {
                                            from(1) {
                                                addVarInt(2, 0)
                                                addVarInt(12, 0)
                                                addVarInt(15, 0)
                                            }
                                            addVarInt(6, 1)
                                        }
                                        from(2) {}
                                    }
                                    extras?.let {
                                        addBuffer(13, it)
                                    }
                                    from(22) {}
                                }
                            }.toByteArray()
                        }

                        targetMessageContent.contentType = ContentType.SNAP
                        targetMessageContent.content = ProtoEditor(targetMessageContent.content!!).apply {
                            edit(11, 5, 2) {
                                arrayOf(6, 7, 8).forEach { remove(it) }
                                addVarInt(5, targetReader.getVarInt(3, 3, 5, 2, 5) ?: targetReader.getVarInt(11, 5, 2, 5) ?: 1)
                                if (snapDurationMs != null && overrideType != "SAVEABLE_SNAP") {
                                    addVarInt(8, snapDurationMs / 1000)
                                    if (snapDurationMs / 1000 <= 0) {
                                        addVarInt(99, snapDurationMs)
                                    }
                                } else {
                                    addBuffer(6, byteArrayOf())
                                }
                            }

                            // set app source (same as SnapEnhance - no save policy in proto for story+chat)
                            edit(11, 22) {
                                remove(4)
                                addVarInt(4, 5) // APP_SOURCE_CAMERA
                            }

                            // Enforce save policy directly on SNAP message body.
                            edit(11) {
                                remove(7)
                                addVarInt(7, savePolicyValue)
                            }
                        }.toByteArray()
                    }
                    "NOTE" -> {
                        // Check if "prevent audio" is enabled in UnsaveableMessages
                        val shouldPreventSave = context.config.messaging.unsaveableMessages.note.get()
                        if (shouldPreventSave) {
                            postSavePolicy = 1 // PROHIBITED
                        }
                        targetMessageContent.contentType = ContentType.NOTE
                        val stripMeta = context.config.messaging.stripMediaMetadata.get()
                        val omitTranscript = stripMeta.contains("remove_audio_note_transcript_capability")
                        val rawDurationMs = targetReader.getVarInt(3, 3, 5, 1, 1, 15)?.toLong()
                            ?: targetReader.getVarInt(3, 3, 5, 2, 8)?.toLong()?.times(1000)
                            ?: (context.feature(MediaFilePicker::class).lastMediaDuration ?: 0).toLong()
                        val durationForProto = minOf(rawDurationMs, MessageSender.VOICE_NOTE_MAX_DURATION_MS)
                        val audioNoteProto = MessageSender.audioNoteProto(
                            durationForProto,
                            if (omitTranscript) null else Locale.getDefault().toLanguageTag()
                        )

                        // Set save policy in the proto if prevent audio is enabled
                        targetMessageContent.content = if (shouldPreventSave) {
                            // Check which path structure exists in the audio note proto
                            val protoReader = ProtoReader(audioNoteProto)
                            val hasNestedPath = protoReader.followPath(6, 1, 1) != null

                            ProtoEditor(audioNoteProto).apply {
                                // Set save policy to PROHIBITED (1) in the NOTE path
                                if (hasNestedPath) {
                                    edit(6, 1, 1) {
                                        remove(7)
                                        addVarInt(7, 1)
                                    }
                                } else {
                                    edit(6, 1) {
                                        remove(7)
                                        addVarInt(7, 1)
                                    }
                                }
                            }.toByteArray()
                        } else {
                            audioNoteProto
                        }

                    }
                }

                if (postSavePolicy != null) {
                    try {
                        val savePolicyEnumClass = runCatching {
                            Class.forName(
                                "com.snapchat.client.messaging.SavePolicy",
                                false,
                                targetMessageContent.instanceNonNull().javaClass.classLoader
                            )
                        }.getOrNull()

                        if (savePolicyEnumClass != null && savePolicyEnumClass.isEnum) {
                            @Suppress("UNCHECKED_CAST")
                            val enumClass = savePolicyEnumClass as Class<out Enum<*>>
                            val policyName = when (postSavePolicy) {
                                1 -> "PROHIBITED"
                                2 -> "VIEWER_SAVABLE"
                                else -> null
                            }
                            if (policyName != null) {
                                val policyEnum = runCatching {
                                    java.lang.Enum.valueOf(enumClass, policyName)
                                }.getOrNull()

                                if (policyEnum != null) {
                                    targetMessageContent.instanceNonNull().setObjectField("mSavePolicy", policyEnum)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        context.log.warn("SendOverride: Failed to set mSavePolicy: ${e.message}")
                    }
                }

                return true
            }

            fun createMessageContentFromOriginal(): MessageContent {
                return MessageContent(
                    context.gson.fromJson(originalMessageJson, context.classCache.localMessageContent)
                ).also { messageContent ->
                    val visited = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())

                    fun shouldScrubField(fieldName: String): Boolean {
                        if (fieldName == "mId") return false
                        return fieldName in setOf("mMessageId", "mQuotedMessageId") ||
                            fieldName.contains("AttemptId", ignoreCase = true) ||
                            fieldName.contains("ClientMessageId", ignoreCase = true) ||
                            fieldName.contains("ClientId", ignoreCase = true) ||
                            fieldName.contains("MessageUuid", ignoreCase = true) ||
                            fieldName.contains("UUID", ignoreCase = true)
                    }

                    fun scrubValue(value: Any?) {
                        if (value == null) return
                        if (!visited.add(value)) return

                        when (value) {
                            is String, is Number, is Boolean, is ByteArray, is Enum<*> -> return
                            is Iterable<*> -> {
                                value.forEach { scrubValue(it) }
                                return
                            }
                            is Map<*, *> -> {
                                value.values.forEach { scrubValue(it) }
                                return
                            }
                        }

                        sequence<Class<*>> {
                            var current: Class<*>? = value.javaClass
                            while (current != null && current != Any::class.java && current != Object::class.java) {
                                yield(current)
                                current = current.superclass
                            }
                        }.flatMap { it.declaredFields.asSequence() }
                            .forEach { field ->
                                runCatching {
                                    field.isAccessible = true
                                    if (shouldScrubField(field.name)) {
                                        when (field.type) {
                                            java.lang.Long.TYPE -> field.setLong(value, 0L)
                                            java.lang.Integer.TYPE -> field.setInt(value, 0)
                                            java.lang.Boolean.TYPE -> field.setBoolean(value, false)
                                            else -> field.set(value, null)
                                        }
                                    } else {
                                        scrubValue(field.get(value))
                                    }
                                }
                            }
                    }

                    scrubValue(messageContent.instanceNonNull())
                }
            }

            fun invokeSendManually(messageContent: MessageContent, callback: Any?) {
                val conversationManager = conversationManagerInstance ?: error("ConversationManager is null")
                internalMultipartSend.set(true)
                try {
                    sendMessageWithContentMethod.invoke(
                        conversationManager,
                        cloneDestinations(event.destinations),
                        messageContent.instanceNonNull(),
                        callback
                    )
                } finally {
                    internalMultipartSend.set(false)
                }
            }

            fun sendMediaManual(
                sourceMessageContent: MessageContent,
                overrideType: String,
                snapDurationMs: Int?,
                completionCallback: Any?
            ): Boolean {
                val sourceReader = ProtoReader(sourceMessageContent.content ?: return false)
                val mediaCount = sourceReader.followPath(3)?.getCount(3) ?: 0
                if (overrideType != "ORIGINAL" && mediaCount > 1) {
                    val mediaBuffers = mutableListOf<ByteArray>()
                    sourceReader.followPath(3)?.eachBuffer { id, buffer ->
                        if (id == 3) mediaBuffers.add(buffer)
                    }
                    if (mediaBuffers.isEmpty()) return false

                    fun buildPartMessageContent(partIndex: Int): MessageContent {
                        val partContent = createMessageContentFromOriginal()
                        val metadata = partContent.instanceNonNull().getObjectFieldOrNull("mExternalContentMetadata")
                        val refs = ArrayList(partContent.localMediaReferences ?: arrayListOf())
                        val contentRefs = (metadata?.getObjectFieldOrNull("mContentReferences") as? ArrayList<*>)?.toCollection(ArrayList())
                        val encryptionRefs = (metadata?.getObjectFieldOrNull("mRemoteMediaEncryption") as? ArrayList<*>)?.toCollection(ArrayList())
                        partContent.content = ProtoEditor(partContent.content!!).apply {
                            edit(3) {
                                remove(3)
                                addBuffer(3, mediaBuffers[partIndex])
                            }
                        }.toByteArray()
                        if (partIndex < refs.size) {
                            partContent.localMediaReferences = arrayListOf(refs[partIndex])
                        }
                        metadata?.let {
                            if (contentRefs != null && partIndex < contentRefs.size) {
                                it.setObjectField("mContentReferences", arrayListOf(contentRefs[partIndex]))
                            }
                            if (encryptionRefs != null && partIndex < encryptionRefs.size) {
                                it.setObjectField("mRemoteMediaEncryption", arrayListOf(encryptionRefs[partIndex]))
                            }
                        }
                        return partContent
                    }

                    fun sendPart(partIndex: Int) {
                        postSavePolicy = null
                        val partContent = buildPartMessageContent(partIndex)
                        val partReader = ProtoReader(partContent.content ?: return)
                        if (!applyOverride(partContent, partReader, overrideType, snapDurationMs)) return

                        val callback = if (partIndex == mediaCount - 1) {
                            completionCallback
                        } else {
                            CallbackBuilder(sendMessageCallbackClass)
                                .override("onSuccess") {
                                    sendPart(partIndex + 1)
                                }
                                .override("onError", shouldUnhook = false) {
                                    invokeCallbackError(completionCallback, it.argNullable<Any>(0))
                                }
                                .build()
                        }

                        invokeSendManually(partContent, callback)
                    }

                    sendPart(0)
                    return true
                }

                postSavePolicy = null
                val targetReader = ProtoReader(sourceMessageContent.content ?: return false)
                if (!applyOverride(sourceMessageContent, targetReader, overrideType, snapDurationMs)) return false
                invokeSendManually(sourceMessageContent, completionCallback)
                return true
            }

            fun sendRepeatedMediaManual(
                repeatCount: Int,
                overrideType: String,
                snapDurationMs: Int?
            ): Boolean {
                if (repeatCount <= 0) return false

                fun sendIteration(index: Int) {
                    val callback = CallbackBuilder(sendMessageCallbackClass)
                        .override("onSuccess") {
                            processedRepeatCount++
                            queuedOriginalItemRepeatCount--
                            updateContinuousSendNotification(context)
                            
                            if (index < repeatCount - 1) {
                                sendIteration(index + 1)
                            } else {
                                // Batch Finished: Trigger original Snapchat callback
                                originalCallback?.let { cb ->
                                    runCatching {
                                        val method = cb.javaClass.methods.firstOrNull { it.name == "onSuccess" }
                                        if (method != null) {
                                            if (method.parameterCount == 0) {
                                                method.invoke(cb)
                                            } else {
                                                // Pass null for all required parameters to safely trigger the completion UI
                                                method.invoke(cb, *arrayOfNulls<Any>(method.parameterCount))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        .override("onError", shouldUnhook = false) {
                            invokeCallbackError(originalCallback, it.argNullable<Any>(0))
                            resetEngine(context)
                            updateContinuousSendNotification(context)
                        }
                        .build()

                    val preparedContent = createMessageContentFromOriginal()
                    if (!sendMediaManual(preparedContent, overrideType, snapDurationMs, callback)) {
                        invokeCallbackError(originalCallback, "Failed to send")
                    }
                }

                sendIteration(0)
                return true
            }

            fun sendMedia(overrideType: String, snapDurationMs: Int?): Boolean {
                postSavePolicy = null
                return applyOverride(localMessageContent, messageProtoReader, overrideType, snapDurationMs)
            }

            val resolvedOverrideType = MediaFilePicker.getQueuedOverrideType()
                ?: configOverrideType?.takeIf { it != "always_ask" }

            fun attachQueuedRepeatCallbacks(sendEvent: SendMessageWithContentEvent) {
                sendEvent.addCallbackResult("onSuccess") {
                    context.runOnUiThread {
                        val handledSplit = MediaFilePicker.handleCurrentQueuedItemSuccess()
                        val handledRepeat = if (!handledSplit) {
                            handleQueuedOriginalItemRepeatSuccess(context)
                        } else {
                            false
                        }
                        if (!handledSplit && !handledRepeat) {
                            resetEngine(context)
                            updateContinuousSendNotification(context)
                        }
                    }
                }
                sendEvent.addCallbackResult("onError") {
                    context.runOnUiThread {
                        resetEngine(context)
                        updateContinuousSendNotification(context)
                    }
                }
            }

            if (resolvedOverrideType != null) {
                if (MediaFilePicker.hasPendingSplitCleanup() || MediaFilePicker.getQueuedOverrideType() != null || queuedOriginalItemRepeatCount > 0) {
                    attachQueuedRepeatCallbacks(event)
                }
                if (sendMedia(resolvedOverrideType, 10000)) {
                    if (event.canceled) invokeOriginalAndRestoreResult(event)
                }
                return@subscribe
            }

            context.runOnUiThread {
                val recipientNameForTask = recipientName
                
                createComposeAlertDialog(context.mainActivity!!) { alertDialog ->
                    PurrfectOverlayTheme {
                        val skin = me.eternal.purrfect.common.ui.theme.LocalPurrfectSkin.current
                        val is24Hour = android.text.format.DateFormat.is24HourFormat(context.androidContext)
                        val mainTranslation = remember {
                            context.translation.getCategory("send_override_dialog")
                        }
                        val dialogShape = RoundedCornerShape(24.dp)
                        val dialogSurfaceColor = skin.cardOverlayColor
                        
                        val glowPrimary = skin.glowPrimary
                        val glowSecondary = skin.glowSecondary
                        
                        val border = remember(glowPrimary, glowSecondary) {
                            Brush.linearGradient(
                                listOf(
                                    glowPrimary.copy(alpha = 0.55f),
                                    glowSecondary.copy(alpha = 0.35f)
                                )
                            )
                        }
                        val dialogBackground = skin.cardOverlayColor

                        @Composable
                        fun ActionTile(
                            modifier: Modifier = Modifier,
                            selected: Boolean = false,
                            icon: ImageVector,
                            title: String,
                            onClick: () -> Unit
                        ) {
                            val unselectedColor = if (skin.isDark) Color(0xFF1A1A1A) else Color(0xFFF0F0F0)
                            val selectedColor = if (skin.isDark) Color(0xFF262626) else Color(0xFFE0E0E0)
                            
                            Card(
                                modifier = modifier,
                                onClick = onClick,
                                shape = RoundedCornerShape(18.dp),
                                elevation = CardDefaults.cardElevation(defaultElevation = if (selected) 4.dp else 1.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (selected) selectedColor else unselectedColor,
                                    contentColor = skin.textPrimary
                                ),
                                border = if (selected) BorderStroke(1.dp, skin.textPrimary.copy(alpha = 0.5f)) else null
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(horizontal = 10.dp, vertical = 12.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        icon,
                                        contentDescription = title,
                                        modifier = Modifier.size(28.dp),
                                        tint = if (selected) skin.textPrimary else skin.textPrimary.copy(alpha = 0.5f)
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        title,
                                        modifier = Modifier.fillMaxWidth(),
                                        fontSize = 12.sp,
                                        color = skin.textPrimary,
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.SansSerif,
                                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                        softWrap = true,
                                        lineHeight = 14.sp,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }

                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = dialogShape,
                            color = dialogBackground,
                            tonalElevation = 0.dp,
                            shadowElevation = 18.dp,
                            border = BorderStroke(1.dp, border)
                        ) {
                            Column(
                                modifier = Modifier
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                val translation = remember {
                                    context.translation.getCategory("features.options.gallery_media_send_override")
                                }
                                var scheduleEnabled by remember { mutableStateOf(false) }
                                var continuousSendEnabled by remember { mutableStateOf(false) }
                                var continuousSendCount by remember { mutableStateOf("2") }

                                Text(
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Medium,
                                    text = "Send as ${translation[selectedType]}",
                                    modifier = Modifier.padding(5.dp)
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    ActionTile(
                                        modifier = Modifier.weight(1f).height(92.dp),
                                        selected = selectedType == "ORIGINAL",
                                        icon = Icons.Filled.Photo,
                                        title = translation["ORIGINAL"]
                                    ) {
                                        selectedType = "ORIGINAL"
                                    }
                                    ActionTile(
                                        modifier = Modifier.weight(1f).height(92.dp),
                                        selected = selectedType == "SNAP" || selectedType == "SAVEABLE_SNAP",
                                        icon = Icons.Filled.PhotoCamera,
                                        title = translation["SNAP"]
                                    ) {
                                        selectedType = "SNAP"
                                    }
                                    ActionTile(
                                        modifier = Modifier.weight(1f).height(92.dp),
                                        selected = selectedType == "NOTE",
                                        icon = Icons.Filled.MusicNote,
                                        title = translation["NOTE"]
                                    ) {
                                        selectedType = "NOTE"
                                    }
                                }

                                fun convertDuration(duration: Float) = when {
                                    duration in -2f..-1f -> 100
                                    duration in -1f..-0f -> 250
                                    duration in -0f..1f -> 500
                                    duration >= 11f -> null
                                    else -> ((duration * 1000).toInt() / 1000) * 1000
                                }
                        
                        fun formatTimeText(ms: Long): String {
                            val days = (ms / (24 * 60 * 60 * 1000)).toInt()
                            val hours = ((ms / (60 * 60 * 1000)) % 24).toInt()
                            val minutes = ((ms / (60 * 1000)) % 60).toInt()
                            val seconds = ((ms / 1000) % 60).toInt()
                            return buildString {
                                if (days > 0) append("${days}d ")
                                if (hours > 0 || days > 0) append("${hours}h ")
                                if (minutes > 0 || hours > 0 || days > 0) append("${minutes}m ")
                                append("${seconds}s")
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth().clickable {
                                scheduleEnabled = !scheduleEnabled
                                if (!scheduleEnabled) scheduledTime = null
                            },
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = scheduleEnabled,
                                onCheckedChange = {
                                    scheduleEnabled = it
                                    if (!it) scheduledTime = null
                                }
                            )
                            Text(text = mainTranslation["schedule"], modifier = Modifier.weight(1f))
                            if (scheduleEnabled) {
                                Button(
                                    onClick = { 
                                        context.androidContext.vibrateLongPress()
                                        showClockPicker = true 
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = skin.glowPrimary,
                                        contentColor = skin.primaryButtonText
                                    )
                                ) {
                                    scheduledTime?.let { time ->
                                        val cal = Calendar.getInstance().apply { timeInMillis = time }
                                        Text(
                                            text = SimpleDateFormat(if (is24Hour) "HH:mm" else "hh:mm a", Locale.getDefault()).format(cal.time),
                                            color = skin.primaryButtonText,
                                            fontWeight = FontWeight.Bold
                                        )
                                    } ?: Text(
                                        text = context.translation["select"],
                                        color = skin.primaryButtonText,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }

                        if (scheduleEnabled && showClockPicker) {
                            val datePickerState = rememberDatePickerState(
                                initialSelectedDateMillis = scheduledTime ?: System.currentTimeMillis()
                            )
                            val timePickerState = rememberTimePickerState(
                                initialHour = clockPickerHour,
                                initialMinute = clockPickerMinute,
                                is24Hour = is24Hour
                            )

                            var showDatePickerDialog by remember { mutableStateOf(false) }
                            var showTimePickerDialog by remember { mutableStateOf(false) }

                            if (showDatePickerDialog) {
                                DatePickerDialog(
                                    onDismissRequest = { showDatePickerDialog = false },
                                    confirmButton = {
                                        TextButton(onClick = {
                                            context.androidContext.vibrateLongPress()
                                            showDatePickerDialog = false
                                        }) {
                                            Text(context.translation["button.ok"], color = skin.glowPrimary, fontWeight = FontWeight.Bold)
                                        }
                                    },
                                    dismissButton = {
                                        TextButton(onClick = { showDatePickerDialog = false }) {
                                            Text(context.translation["button.cancel"], color = skin.textSecondary)
                                        }
                                    },
                                    colors = DatePickerDefaults.colors(
                                        containerColor = skin.cardOverlayColor,
                                        titleContentColor = skin.textPrimary,
                                        headlineContentColor = skin.textPrimary,
                                        weekdayContentColor = skin.textSecondary,
                                        subheadContentColor = skin.textSecondary,
                                        yearContentColor = skin.textSecondary,
                                        currentYearContentColor = skin.glowPrimary,
                                        selectedYearContentColor = skin.primaryButtonText,
                                        selectedYearContainerColor = skin.glowPrimary,
                                        dayContentColor = skin.textPrimary,
                                        disabledDayContentColor = skin.textPrimary.copy(alpha = 0.38f),
                                        selectedDayContentColor = skin.primaryButtonText,
                                        selectedDayContainerColor = skin.glowPrimary,
                                        todayContentColor = skin.glowPrimary,
                                        todayDateBorderColor = skin.glowPrimary
                                    )
                                ) {
                                    DatePicker(
                                        state = datePickerState,
                                        colors = DatePickerDefaults.colors(
                                            containerColor = skin.cardOverlayColor,
                                            titleContentColor = skin.textPrimary,
                                            headlineContentColor = skin.textPrimary,
                                            weekdayContentColor = skin.textSecondary,
                                            subheadContentColor = skin.textSecondary,
                                            yearContentColor = skin.textSecondary,
                                            currentYearContentColor = skin.glowPrimary,
                                            selectedYearContentColor = skin.primaryButtonText,
                                            selectedYearContainerColor = skin.glowPrimary,
                                            dayContentColor = skin.textPrimary,
                                            disabledDayContentColor = skin.textPrimary.copy(alpha = 0.38f),
                                            selectedDayContentColor = skin.primaryButtonText,
                                            selectedDayContainerColor = skin.glowPrimary,
                                            todayContentColor = skin.glowPrimary,
                                            todayDateBorderColor = skin.glowPrimary
                                        )
                                    )
                                }
                            }

                            if (showTimePickerDialog) {
                                AlertDialog(
                                    onDismissRequest = { showTimePickerDialog = false },
                                    confirmButton = {
                                        TextButton(onClick = {
                                            context.androidContext.vibrateLongPress()
                                            clockPickerHour = timePickerState.hour
                                            clockPickerMinute = timePickerState.minute
                                            showTimePickerDialog = false
                                        }) {
                                            Text(context.translation["button.ok"], color = skin.glowPrimary, fontWeight = FontWeight.Bold)
                                        }
                                    },
                                    dismissButton = {
                                        TextButton(onClick = { showTimePickerDialog = false }) {
                                            Text(context.translation["button.cancel"], color = skin.textSecondary)
                                        }
                                    },
                                    containerColor = skin.cardOverlayColor,
                                    text = {
                                        TimePicker(
                                            state = timePickerState,
                                            colors = TimePickerDefaults.colors(
                                                clockDialColor = skin.textPrimary.copy(alpha = 0.05f),
                                                clockDialSelectedContentColor = skin.primaryButtonText,
                                                clockDialUnselectedContentColor = skin.textPrimary,
                                                selectorColor = skin.glowPrimary,
                                                periodSelectorBorderColor = skin.textPrimary.copy(alpha = 0.12f),
                                                periodSelectorSelectedContainerColor = skin.glowPrimary,
                                                periodSelectorUnselectedContainerColor = Color.Transparent,
                                                periodSelectorSelectedContentColor = skin.primaryButtonText,
                                                periodSelectorUnselectedContentColor = skin.textPrimary,
                                                timeSelectorSelectedContainerColor = skin.glowPrimary,
                                                timeSelectorUnselectedContainerColor = skin.textPrimary.copy(alpha = 0.05f),
                                                timeSelectorSelectedContentColor = skin.primaryButtonText,
                                                timeSelectorUnselectedContentColor = skin.textPrimary
                                            )
                                        )
                                    }
                                )
                            }

                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = MaterialTheme.shapes.medium,
                                colors = CardDefaults.cardColors(containerColor = skin.textPrimary.copy(alpha = 0.05f))
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Text(
                                        mainTranslation["select_time"],
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.titleMedium,
                                        color = skin.textPrimary
                                    )

                                    OutlinedButton(
                                        onClick = {
                                            context.androidContext.vibrateLongPress()
                                            showDatePickerDialog = true
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, skin.textPrimary.copy(alpha = 0.12f)),
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = skin.textPrimary)
                                    ) {
                                        Icon(Icons.Default.CalendarToday, contentDescription = null, tint = skin.glowPrimary)
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            datePickerState.selectedDateMillis?.let {
                                                SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(it)
                                            } ?: context.translation.getOrNull("select_date") ?: "Select Date"
                                        )
                                    }

                                    OutlinedButton(
                                        onClick = {
                                            context.androidContext.vibrateLongPress()
                                            showTimePickerDialog = true
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, skin.textPrimary.copy(alpha = 0.12f)),
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = skin.textPrimary)
                                    ) {
                                        Icon(Icons.Default.Schedule, contentDescription = null, tint = skin.glowPrimary)
                                        Spacer(Modifier.width(8.dp))
                                        val cal = Calendar.getInstance().apply {
                                            set(Calendar.HOUR_OF_DAY, clockPickerHour)
                                            set(Calendar.MINUTE, clockPickerMinute)
                                        }
                                        Text(SimpleDateFormat(if (is24Hour) "HH:mm" else "hh:mm a", Locale.getDefault()).format(cal.time))
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        OutlinedButton(
                                            onClick = { showClockPicker = false },
                                            modifier = Modifier.weight(1f),
                                            border = androidx.compose.foundation.BorderStroke(1.dp, skin.textPrimary.copy(alpha = 0.12f)),
                                            colors = ButtonDefaults.outlinedButtonColors(contentColor = skin.textSecondary)
                                        ) {
                                            Text(context.translation["button.cancel"])
                                        }
                                        Button(
                                            onClick = {
                                                context.androidContext.vibrateLongPress()
                                                val selectedDateMillis = datePickerState.selectedDateMillis
                                                if (selectedDateMillis == null) {
                                                    context.inAppOverlay.showStatusToast(
                                                        icon = Icons.Default.WarningAmber,
                                                        text = mainTranslation.getOrNull("select_date_first") ?: "Please select a date"
                                                    )
                                                    return@Button
                                                }

                                                val calendar = Calendar.getInstance()
                                                calendar.timeInMillis = selectedDateMillis
                                                calendar.set(Calendar.HOUR_OF_DAY, clockPickerHour)
                                                calendar.set(Calendar.MINUTE, clockPickerMinute)
                                                calendar.set(Calendar.SECOND, 0)
                                                calendar.set(Calendar.MILLISECOND, 0)

                                                if (calendar.timeInMillis <= System.currentTimeMillis()) {
                                                    context.inAppOverlay.showStatusToast(
                                                        icon = Icons.Default.WarningAmber,
                                                        text = mainTranslation.getOrNull("invalid_time") ?: "Please select a future time"
                                                    )
                                                    return@Button
                                                }

                                                scheduledTime = calendar.timeInMillis
                                                showClockPicker = false
                                            },
                                            modifier = Modifier.weight(1f),
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = skin.glowPrimary,
                                                contentColor = skin.primaryButtonText
                                            )
                                        ) {
                                            Text(
                                                text = context.translation["button.ok"], 
                                                color = skin.primaryButtonText,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable {
                                continuousSendEnabled = !continuousSendEnabled
                            },
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = continuousSendEnabled,
                                onCheckedChange = {
                                    continuousSendEnabled = it
                                }
                            )
                            Text(text = mainTranslation["continuous_send_toggle"], lineHeight = 15.sp)
                        }

                        if (continuousSendEnabled) {
                            OutlinedTextField(
                                value = continuousSendCount,
                                onValueChange = { value ->
                                    continuousSendCount = value.filter(Char::isDigit).take(3)
                                },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                label = { Text(mainTranslation["continuous_send_count_label"]) },
                                placeholder = { Text(mainTranslation["continuous_send_count_placeholder"]) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                keyboardActions = KeyboardActions.Default
                            )
                            Text(
                                text = mainTranslation["continuous_send_hint"],
                                fontSize = 12.sp,
                                color = Color.White.copy(alpha = 0.72f)
                            )
                        }

                        when (selectedType) {
                            "SNAP", "SAVEABLE_SNAP" -> {
                                fun toggleSaveable() {
                                    selectedType = if (selectedType == "SAVEABLE_SNAP") "SNAP" else "SAVEABLE_SNAP"
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth().clickable {
                                        disableSplitForCurrentSend = !disableSplitForCurrentSend
                                    },
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(
                                        checked = disableSplitForCurrentSend,
                                        onCheckedChange = {
                                            disableSplitForCurrentSend = it
                                        }
                                    )
                                    Text(text = mainTranslation["single_send_hint"], lineHeight = 15.sp)
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth().clickable {
                                        toggleSaveable()
                                    },
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ){
                                    Checkbox(
                                        checked = selectedType == "SAVEABLE_SNAP",
                                        onCheckedChange = {
                                            toggleSaveable()
                                        }
                                    )
                                    Text(text = mainTranslation["saveable_snap_hint"], lineHeight = 15.sp)
                                }
                                Column(
                                    modifier = Modifier.padding(start = 8.dp)
                                ) {
                                    Text(
                                        text = mainTranslation.format("duration",
                                            "duration" to (convertDuration(customDuration)?.toDuration(DurationUnit.MILLISECONDS)?.toString(DurationUnit.SECONDS, 2) ?: mainTranslation["unlimited_duration"])
                                        )
                                    )
                                    Slider(
                                        modifier = Modifier.fillMaxWidth(),
                                        enabled = selectedType != "SAVEABLE_SNAP",
                                        value = customDuration,
                                        onValueChange = { newValue ->
                                            val snapped = Math.round(newValue).toFloat().coerceIn(-2f, 11f)
                                            if (snapped != customDuration) {
                                                context.androidContext.vibrateLongPress()
                                                customDuration = snapped
                                            }
                                        },
                                        valueRange = -2f..11f,
                                        steps = 12,
                                        colors = androidx.compose.material3.SliderDefaults.colors(
                                            thumbColor = skin.glowPrimary,
                                            activeTrackColor = skin.glowPrimary,
                                            inactiveTrackColor = skin.textPrimary.copy(alpha = 0.12f)
                                        )
                                    )
                                }
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedButton(onClick = {
                                alertDialog.dismiss()
                            }) {
                                Text(context.translation["button.cancel"])
                            }
                            Button(onClick = {
                                context.androidContext.vibrateLongPress()
                                val finalSelectedType = selectedType
                                val repeatCount = if (continuousSendEnabled) {
                                    continuousSendCount.toIntOrNull()?.takeIf { it > 0 }
                                } else {
                                    1
                                }
                                if (repeatCount == null) {
                                    context.inAppOverlay.showStatusToast(
                                        icon = Icons.Default.WarningAmber,
                                        text = mainTranslation["continuous_send_invalid_count"]
                                    )
                                    return@Button
                                }
                                if (repeatCount > 1 && disableSplitForCurrentSend && MediaFilePicker.hasOriginalUnsplitItem()) {
                                    context.inAppOverlay.showStatusToast(
                                        icon = Icons.Default.WarningAmber,
                                        text = mainTranslation["continuous_send_single_send_conflict"]
                                    )
                                    return@Button
                                }
                                alertDialog.dismiss()
                                if (disableSplitForCurrentSend && MediaFilePicker.hasOriginalUnsplitItem()) {
                                    MediaFilePicker.setQueuedOverrideType(finalSelectedType)
                                    if (!MediaFilePicker.sendOriginalUnsplitItem()) {
                                        MediaFilePicker.setQueuedOverrideType(null)
                                    }
                                    return@Button
                                } else if (MediaFilePicker.hasPendingSplitCleanup()) {
                                    MediaFilePicker.setQueuedOverrideType(finalSelectedType)
                                    event.addCallbackResult("onSuccess") {
                                        context.runOnUiThread {
                                            if (!MediaFilePicker.handleCurrentQueuedItemSuccess()) {
                                                MediaFilePicker.clearQueuedSplitItems()
                                            }
                                        }
                                    }
                                    event.addCallbackResult("onError") {
                                        MediaFilePicker.clearQueuedSplitItems()
                                    }
                                }
                                val delayMs = scheduledTime?.let { it - System.currentTimeMillis() }
                                if (delayMs != null && delayMs > 0) {
                                    val taskHash = java.util.UUID.randomUUID().toString()
                                    // Format the scheduled time for display
                                    val scheduledDateTime = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()).format(scheduledTime)
                                    context.bridgeClient.getTaskInterface().createTask(
                                        "scheduled_send",
                                        scheduledDateTime,
                                        recipientNameForTask,
                                        taskHash
                                    )
                                    val scheduledTimeText = formatTimeText(delayMs)
                                    context.inAppOverlay.showStatusToast(
                                        icon = Icons.Filled.Schedule,
                                        text = context.translation.format("schedule_scheduled_for", "name" to recipientNameForTask, "time" to scheduledTimeText) ?: "Scheduled for $recipientNameForTask in $scheduledTimeText"
                                    )
                                    val releaseBackground = acquireScheduledSendBackground()
                                    
                                    event.addCallbackResult("onSuccess") {
                                        context.bridgeClient.getTaskInterface().successTask(taskHash)
                                    }
                                    event.addCallbackResult("onError") { 
                                        context.bridgeClient.getTaskInterface().failTask(taskHash, it.getOrNull(0)?.toString() ?: "Unknown error")
                                    }

                                    val job = context.coroutineScope.launch {
                                        val startTime = System.currentTimeMillis()
                                        while (true) {
                                            val elapsed = System.currentTimeMillis() - startTime
                                            val remaining = delayMs - elapsed
                                            if (remaining <= 0) break
                                            
                                            val timeText = formatTimeText(remaining)
                                            val progress = (elapsed * 100 / delayMs).toInt().coerceIn(0, 99)
                                            
                                            context.bridgeClient.getTaskInterface().updateTaskProgress(
                                                taskHash,
                                                context.translation.getOrNull("schedule_sending_in")?.replace("{time}", timeText) ?: "Sending in $timeText",
                                                progress
                                            )
                                            delay(1000)
                                        }

                                        context.bridgeClient.getTaskInterface().updateTaskProgress(taskHash, "Sending...", 100)

                                        if (sendRepeatedMediaManual(
                                                repeatCount,
                                                finalSelectedType,
                                                if (finalSelectedType != "SAVEABLE_SNAP") convertDuration(customDuration) else null
                                            )) {
                                            val successText = context.translation.format("schedule_sent_to", "name" to recipientNameForTask) ?: "Sent to $recipientNameForTask"
                                            context.inAppOverlay.showStatusToast(
                                                icon = Icons.Filled.CheckCircle,
                                                text = successText
                                            )
                                            val notificationTitle = context.translation.getOrNull("schedule_sent") ?: "Scheduled snap sent"
                                            val notificationContent = "$scheduledDateTime\n$recipientNameForTask"
                                            showNotification(
                                                notificationTitle,
                                                notificationContent
                                            )
                                        } else {
                                            context.bridgeClient.getTaskInterface().failTask(taskHash, "Failed to send")
                                            val failureText = context.translation.format("schedule_failed_to", "name" to recipientNameForTask) ?: "Failed to send to $recipientNameForTask"
                                            context.inAppOverlay.showStatusToast(
                                                icon = Icons.Filled.WarningAmber,
                                                text = failureText
                                            )
                                            val failNotificationTitle = context.translation.getOrNull("schedule_failed") ?: "Scheduled snap failed"
                                            val failNotificationContent = "$scheduledDateTime\n$recipientNameForTask"
                                            showNotification(
                                                failNotificationTitle,
                                                failNotificationContent
                                            )
                                        }
                                    }
                                    val listener = object : TaskListener.Stub() {
                                        override fun onCancel() {
                                            job.cancel()
                                        }
                                        override fun onProgress(label: String, progress: Int) {}
                                        override fun onStateChange(status: String) {}
                                        override fun onSuccess() {}
                                    }
                                    context.bridgeClient.getTaskInterface().registerTaskListener(taskHash, listener)
                                    job.invokeOnCompletion { throwable ->
                                        releaseBackground()
                                        context.bridgeClient.getTaskInterface().unregisterTaskListener(taskHash, listener)
                                        if (throwable is CancellationException) {
                                            context.inAppOverlay.showStatusToast(
                                                icon = Icons.Filled.Cancel,
                                                text = context.translation.format("schedule_cancelled_for", "name" to recipientNameForTask) ?: "Cancelled for $recipientNameForTask"
                                            )
                                        }
                                    }
                                } else {
                                    if (repeatCount == 1) {
                                        if (sendMedia(finalSelectedType, if (finalSelectedType != "SAVEABLE_SNAP") convertDuration(customDuration) else null)) {
                                            invokeOriginalAndRestoreResult(event)
                                        }
                                    } else if (MediaFilePicker.hasReusableOriginalItem()) {
                                        totalRepeatCount = repeatCount
                                        processedRepeatCount = 1
                                        currentRecipientName = recipientNameForTask
                                        
                                        queueOriginalItemRepeats(context, repeatCount - 1, finalSelectedType)
                                        attachQueuedRepeatCallbacks(event)
                                        if (sendMedia(finalSelectedType, if (finalSelectedType != "SAVEABLE_SNAP") convertDuration(customDuration) else null)) {
                                            invokeOriginalAndRestoreResult(event)
                                        } else {
                                            resetEngine(context)
                                            updateContinuousSendNotification(context)
                                        }
                                    } else {
                                        totalRepeatCount = repeatCount
                                        processedRepeatCount = 0
                                        queuedOriginalItemRepeatCount = repeatCount
                                        currentRecipientName = recipientNameForTask

                                        sendRepeatedMediaManual(
                                            repeatCount,
                                            finalSelectedType,
                                            if (finalSelectedType != "SAVEABLE_SNAP") convertDuration(customDuration) else null
                                        )
                                    }
                                }
                            },
                            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                                containerColor = skin.glowPrimary,
                                contentColor = skin.primaryButtonText
                            )) {
                                Text(
                                    text = if (scheduledTime != null) mainTranslation["schedule"] else context.translation["button.send"] ?: "Send",
                                    color = skin.primaryButtonText,
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                                )
                            }
                        }
                    }
                }
                }
                }.show()
            }
        }
    }
}
