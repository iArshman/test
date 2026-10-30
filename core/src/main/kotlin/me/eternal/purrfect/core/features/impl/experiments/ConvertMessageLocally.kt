package me.eternal.purrfect.core.features.impl.experiments

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cached
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import me.eternal.purrfect.common.data.ContentType
import me.eternal.purrfect.common.ui.createComposeAlertDialog
import me.eternal.purrfect.common.util.protobuf.ProtoReader
import me.eternal.purrfect.common.util.protobuf.ProtoWriter
import me.eternal.purrfect.core.event.events.impl.BuildMessageEvent
import me.eternal.purrfect.core.features.Feature
import me.eternal.purrfect.core.features.impl.downloader.MediaDownloader
import me.eternal.purrfect.core.features.impl.messaging.Messaging
import me.eternal.purrfect.core.util.ktx.setObjectField
import me.eternal.purrfect.core.wrapper.impl.Message
import me.eternal.purrfect.core.wrapper.impl.MessageContent
import me.eternal.purrfect.core.wrapper.impl.MessageMetadata
import me.eternal.purrfect.core.event.events.impl.OnSnapInteractionEvent
import kotlinx.coroutines.launch
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import me.eternal.purrfect.core.ui.getValdiContext
import me.eternal.purrfect.core.util.hook.HookStage
import me.eternal.purrfect.core.util.hook.hook
import me.eternal.purrfect.mapper.impl.ChatEventDispatcherMapper
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Job
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import me.eternal.purrfect.core.features.impl.downloader.decoder.MessageDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.eternal.purrfect.common.data.download.SplitMediaAssetType
import me.eternal.purrfect.common.util.snap.MediaDownloaderHelper
import me.eternal.purrfect.common.data.FileType

class ConvertMessageLocally : Feature("Convert Message Edit") {
    private data class CachedMessageState(
        val messageContent: MessageContent,
        val messageMetadata: MessageMetadata?
    )

    private val messageCache = mutableMapOf<Long, CachedMessageState>()
    private val activePreviewJobs = ConcurrentHashMap<Long, Job>()
    // Widened regex to catch any string that looks like a UUID followed by a message ID,
    // handling possible formatting variations in Valdi's ChatViewModel representations.
    private val messageIdPattern = Regex("([0-9a-fA-F-]{36})[^0-9]+(\\d+)")

    fun isMessageConverted(clientMessageId: Long) = messageCache.containsKey(clientMessageId)

    private fun resolveTarget(rawValue: String?): Long? {
        val match = rawValue?.let { messageIdPattern.find(it) } ?: return null
        return match.groupValues[2].toLongOrNull()
    }

    private fun resolveTargetFromView(view: View): Long? {
        val valdiContext = view.getValdiContext() ?: return null
        return sequenceOf(
            valdiContext.viewModel,
            valdiContext.viewModelLegacy,
            valdiContext.componentContext?.get()
        ).mapNotNull { candidate ->
            resolveTarget(candidate?.toString())
        }.firstOrNull()
    }

    private fun dispatchMessageEdit(message: Message, restore: Boolean = false) {
        val messageId = message.messageDescriptor!!.messageId!!
        if (!restore) {
            messageCache[messageId] = CachedMessageState(
                messageContent = message.messageContent!!,
                messageMetadata = message.messageMetadata
            )
        }

        context.runOnUiThread {
            context.feature(Messaging::class).localUpdateMessage(
                message.messageDescriptor!!.conversationId!!.toString(),
                message
            )
        }
    }

    fun convertMessageInterface(messageInstance: Message) {
        val actions = mutableListOf<Pair<String, (Message) -> Unit>>()
        actions += context.translation["button.restore_original"] to actions@{ message ->
            val descriptor = message.messageDescriptor ?: return@actions
            messageCache.remove(descriptor.messageId!!)
            context.feature(Messaging::class).conversationManager?.fetchMessage(
                descriptor.conversationId!!.toString(),
                descriptor.messageId!!,
                onSuccess = { msg ->
                    dispatchMessageEdit(msg, true)
                }
            )
        }

        val contentType = messageInstance.messageContent?.contentType
        if (contentType == ContentType.SNAP) {
            actions += context.translation["button.convert_external_media"] to convert@{ message ->
                val reader = ProtoReader(message.messageContent!!.content!!)
                val snapMessageContent = reader.followPath(11)?.getBuffer() ?: return@convert
                
                message.messageContent!!.content = ProtoWriter().apply {
                    reader.forEach { id, wire ->
                        if (id != 11) {
                            addWire(wire)
                        }
                    }
                    from(3) {
                        addBuffer(3, snapMessageContent)
                    }
                }.toByteArray()
                message.messageContent!!.contentType = ContentType.EXTERNAL_MEDIA
                // Clear snap-specific playable state so the external media viewer doesn't reject the payload
                // Note: EnumAccessor.setValue cannot handle null, use raw reflection to bypass
                message.messageMetadata?.instanceNonNull()?.setObjectField("mPlayableSnapState", null)
                dispatchMessageEdit(message)
            }
        }

        // Add "View Media" action for converted messages so users can directly view the media
        val messageId = messageInstance.messageDescriptor?.messageId
        if (messageId != null && isMessageConverted(messageId)) {
            val label = context.translation["button.view_media"]?.takeIf { !it.startsWith("button.") } ?: "View Media"
            actions += label to { _ ->
                context.coroutineScope.launch {
                    runCatching {
                        openConvertedMedia(messageId)
                    }.onFailure { err ->
                        context.log.error("Failed to preview converted message via context menu", err)
                        context.shortToast("Failed to preview: ${err.message}")
                    }
                }
            }
        }

        createComposeAlertDialog(context.mainActivity!!) { alertDialog ->
            me.eternal.purrfect.core.ui.PurrfectOverlayTheme(context) {
                ConvertMessageDialog(
                    title = context.translation["chat_action_menu.convert_message"],
                    subtitle = context.translation["convert_message_dialog.subtitle"],
                    closeLabel = context.translation["button.cancel"],
                    actions = actions.map { (label, _) ->
                        ConvertMessageAction(
                            label = label,
                            icon = when {
                                label == context.translation["button.restore_original"] -> Icons.Default.Restore
                                label == "View Media" || label == context.translation["button.view_media"] -> Icons.Default.Visibility
                                else -> Icons.Default.Cached
                            }
                        )
                    },
                    onSelect = { index ->
                        actions.getOrNull(index)?.second?.invoke(messageInstance)
                        alertDialog.dismiss()
                    },
                    onDismiss = { alertDialog.dismiss() }
                )
            }
        }.show()
    }

    override fun init() {
        onNextActivityCreate {
            context.event.subscribe(BuildMessageEvent::class, priority = 2) {
                val clientMessageId = it.message.messageDescriptor?.messageId ?: return@subscribe
                val cached = messageCache[clientMessageId] ?: return@subscribe
                it.message.messageContent = cached.messageContent
                // Propagate the cleared playableSnapState using raw reflection (EnumAccessor can't handle null)
                if (cached.messageMetadata != null) {
                    val cachedState = cached.messageMetadata.instanceNonNull().javaClass.getDeclaredField("mPlayableSnapState").apply { isAccessible = true }.get(cached.messageMetadata.instanceNonNull())
                    it.message.messageMetadata?.instanceNonNull()?.setObjectField("mPlayableSnapState", cachedState)
                }
            }

            // Hook dispatchTouchEvent natively to reliably catch single taps before Valdi consumes them
            View::class.java.hook("dispatchTouchEvent", HookStage.BEFORE) { param ->
                val motionEvent = param.arg<MotionEvent>(0)
                if (motionEvent.actionMasked != MotionEvent.ACTION_UP) return@hook
                
                if (motionEvent.eventTime - motionEvent.downTime > ViewConfiguration.getLongPressTimeout()) return@hook
                
                val view = param.thisObject<View>()
                val messageId = resolveTargetFromViewTree(view)
                
                if (messageId != null && isMessageConverted(messageId)) {
                    context.log.verbose("[ConvertMsg] Intercepted single tap via dispatchTouchEvent: $messageId", "ConvertMessageTap")
                    
                    // Dispatch CANCEL to gracefully exit Valdi's touch state without triggering native click
                    val cancelEvent = MotionEvent.obtain(motionEvent).apply { action = MotionEvent.ACTION_CANCEL }
                    param.invokeOriginal(arrayOf(cancelEvent))
                    param.setResult(true) // consume the event

                    if (!activePreviewJobs.containsKey(messageId)) {
                        activePreviewJobs[messageId] = context.coroutineScope.launch {
                            try {
                                openConvertedMedia(messageId)
                            } catch (err: Throwable) {
                                context.log.error("[ConvertMsg] Failed to open converted media", err)
                                context.shortToast("Preview Failed: ${err.message}")
                            } finally {
                                activePreviewJobs.remove(messageId)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun resolveTargetFromViewTree(view: View): Long? {
        var current: View? = view
        while (current != null) {
            val valdiContext = current.getValdiContext()
            if (valdiContext != null) {
                val seq = sequenceOf(
                    valdiContext.viewModel,
                    valdiContext.viewModelLegacy,
                    valdiContext.componentContext?.get()
                ).mapNotNull { it?.toString() }
                
                for (str in seq) {
                    val id = resolveTarget(str)
                    if (id != null) return id
                }
            }
            current = current.parent as? View
        }
        return null
    }

    private suspend fun openConvertedMedia(messageId: Long) {
        val modCtx = this@ConvertMessageLocally.context
        val mainActivity = modCtx.mainActivity ?: throw Exception("MainActivity not found")
        val message = modCtx.database.getConversationMessageFromId(messageId) ?: throw Exception("Message not found in DB")
        
        val decodedAttachments = message.messageContent?.let { content ->
            MessageDecoder.decode(ProtoReader(content))
        }?.toMutableList() ?: throw Exception("Could not decode message content")

        val downloadableAttachments = decodedAttachments.filter {
            it.boltKey != null || it.directUrl != null
        }
        
        if (downloadableAttachments.isEmpty()) throw Exception("No downloadable attachments found")
        val attachment = downloadableAttachments.first()

        // Immediately show the dialog with a loading spinner
        var dialogInstance: android.app.AlertDialog? = null
        val viewGroup = withContext(Dispatchers.Main) {
            val container = android.widget.FrameLayout(modCtx.androidContext).apply { 
                setBackgroundColor(android.graphics.Color.BLACK)
            }
            val spinner = android.widget.ProgressBar(modCtx.androidContext).apply {
                isIndeterminate = true
            }
            container.addView(spinner, android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.CENTER
            ))

            dialogInstance = me.eternal.purrfect.core.ui.ViewAppearanceHelper.newAlertDialogBuilder(mainActivity).show().apply {
                window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.BLACK))
                setContentView(container)
                window?.setLayout(
                    modCtx.androidContext.resources.displayMetrics.widthPixels, 
                    modCtx.androidContext.resources.displayMetrics.heightPixels
                )
            }
            container
        }

        try {
            // Fetch and decrypt the stream directly to a cache file (this takes time)
            attachment.openStream { attachmentStream, _ ->
                if (attachmentStream == null) throw Exception("Failed to decrypt or open media stream")

                val downloadedMediaList = mutableMapOf<SplitMediaAssetType, ByteArray>()
                // Using existing helper to parse the multiplexed snap stream
                MediaDownloaderHelper.getSplitElements(attachmentStream) { type, inputStream -> 
                    downloadedMediaList[type] = inputStream.readBytes() 
                }
                
                val originalMedia = downloadedMediaList[SplitMediaAssetType.ORIGINAL] ?: throw Exception("Original media asset missing")
                val isVideo = FileType.fromByteArray(originalMedia).isVideo
                
                // Create a temporary file to hold the media for OS playback
                val cacheDir = modCtx.androidContext.cacheDir
                val tempFile = File.createTempFile("ps_preview_${System.currentTimeMillis()}_", if (isVideo) ".mp4" else ".jpg", cacheDir)
                
                tempFile.writeBytes(originalMedia)
                
                withContext(Dispatchers.Main) {
                    viewGroup.removeAllViews() // Remove the spinner

                    if (isVideo) {
                        val videoView = android.widget.VideoView(modCtx.androidContext).apply {
                            setVideoPath(tempFile.absolutePath)
                            setOnPreparedListener { mp ->
                                mp.isLooping = true
                                start()
                            }
                        }
                        viewGroup.addView(videoView, android.widget.FrameLayout.LayoutParams(
                            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                            android.view.Gravity.CENTER
                        ))
                    } else {
                        val imageView = android.widget.ImageView(modCtx.androidContext).apply {
                            setImageURI(android.net.Uri.fromFile(tempFile))
                            adjustViewBounds = true
                        }
                        viewGroup.addView(imageView, android.widget.FrameLayout.LayoutParams(
                            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                            android.view.Gravity.CENTER
                        ))
                    }

                    dialogInstance?.setOnDismissListener { 
                        runCatching { tempFile.delete() }
                    }
                }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                dialogInstance?.dismiss()
            }
            throw e
        }
    }

    @Composable
    private fun ConvertMessageDialog(
        title: String,
        subtitle: String,
        closeLabel: String,
        actions: List<ConvertMessageAction>,
        onSelect: (Int) -> Unit,
        onDismiss: () -> Unit
    ) {
        val skin = me.eternal.purrfect.common.ui.theme.LocalPurrfectSkin.current
        val shape = remember { RoundedCornerShape(24.dp) }
        val accentBrush = remember(skin) {
            Brush.linearGradient(
                listOf(
                    skin.glowPrimary.copy(alpha = 0.42f),
                    skin.glowSecondary.copy(alpha = 0.34f)
                )
            )
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            shape = shape,
            border = BorderStroke(1.dp, skin.textPrimary.copy(alpha = 0.12f)),
            colors = CardDefaults.cardColors(containerColor = skin.cardOverlayColor)
        ) {
            Box(
                modifier = Modifier
                    .padding(20.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(62.dp)
                            .background(accentBrush, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.EditNote,
                            contentDescription = null,
                            tint = skin.textPrimary,
                            modifier = Modifier.size(30.dp)
                        )
                    }

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold),
                            color = skin.textPrimary,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = skin.textSecondary,
                            textAlign = TextAlign.Center
                        )
                    }

                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        actions.forEachIndexed { index, action ->
                            ConvertMessageOptionCard(
                                label = action.label,
                                icon = action.icon,
                                onClick = { onSelect(index) }
                            )
                        }
                    }

                    Button(
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = skin.textPrimary.copy(alpha = 0.08f),
                            contentColor = skin.textPrimary
                        )
                    ) {
                        Text(
                            text = closeLabel,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun ConvertMessageOptionCard(
        label: String,
        icon: ImageVector,
        onClick: () -> Unit
    ) {
        val skin = me.eternal.purrfect.common.ui.theme.LocalPurrfectSkin.current
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        listOf(
                            skin.glowPrimary.copy(alpha = 0.18f),
                            skin.glowSecondary.copy(alpha = 0.1f)
                        )
                    ),
                    RoundedCornerShape(18.dp)
                )
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .background(skin.textPrimary.copy(alpha = 0.1f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = skin.textPrimary,
                    modifier = Modifier.size(20.dp)
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                color = skin.textPrimary,
                fontWeight = FontWeight.SemiBold
            )
        }
    }

    private data class ConvertMessageAction(
        val label: String,
        val icon: ImageVector
    )
}
