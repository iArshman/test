package me.eternal.purrfect.ui.manager.pages.social

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.rounded.BookmarkAdded
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.RemoveRedEye
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.navigation.NavBackStackEntry
import kotlinx.coroutines.*
import me.eternal.purrfect.bridge.snapclient.MessagingBridge
import me.eternal.purrfect.bridge.snapclient.SessionStartListener
import me.eternal.purrfect.bridge.snapclient.types.Message
import me.eternal.purrfect.common.Constants
import me.eternal.purrfect.common.ReceiversConfig
import me.eternal.purrfect.common.data.ContentType
import me.eternal.purrfect.common.data.SocialScope
import me.eternal.purrfect.common.messaging.MessagingConstraints
import me.eternal.purrfect.common.messaging.MessagingTask
import me.eternal.purrfect.common.messaging.MessagingTaskConstraint
import me.eternal.purrfect.common.messaging.MessagingTaskType
import me.eternal.purrfect.common.ui.rememberAsyncMutableState
import me.eternal.purrfect.common.util.protobuf.ProtoReader
import me.eternal.purrfect.common.util.snap.SnapWidgetBroadcastReceiverHelper
import me.eternal.purrfect.storage.getFriendInfo
import me.eternal.purrfect.storage.getGroupInfo
import me.eternal.purrfect.ui.manager.Routes
import me.eternal.purrfect.ui.manager.components.FloatingTopBar
import me.eternal.purrfect.common.ui.theme.LocalPurrfectSkin
import me.eternal.purrfect.ui.util.Dialog
import me.eternal.purrfect.ui.util.purrfectSwitchColors

private object MessagingPreviewSkinPalette {
    @Composable
    private fun isAphelion(): Boolean {
        val context = LocalContext.current
        return remember(context) { 
            me.eternal.purrfect.SharedContextHolder.remote(context).config.root.global.uiSettings.managerTheme.get() == "APHELION"
        }
    }

    val backgroundGradient: Brush @Composable get() = if (isAphelion()) LocalPurrfectSkin.current.backgroundGradient else Brush.verticalGradient(listOf(Color(0xFF261F58), Color(0xFF302A6D), Color(0xFF241F52)))
    val glowPrimary: Color @Composable get() = if (isAphelion()) LocalPurrfectSkin.current.glowPrimary else Color(0xFF8C7BFF)
    val glowSecondary: Color @Composable get() = if (isAphelion()) LocalPurrfectSkin.current.glowSecondary else Color(0xFF5FD8FF)
    val cardOverlay: Brush @Composable get() = if (isAphelion()) LocalPurrfectSkin.current.cardOverlay else SolidColor(Color(0xFF1B152E))
    val textPrimary: Color @Composable get() = if (isAphelion()) LocalPurrfectSkin.current.textPrimary else LocalPurrfectSkin.current.textPrimary
    val textSecondary: Color @Composable get() = if (isAphelion()) LocalPurrfectSkin.current.textSecondary else Color(0xFFD9D3FF)
    val cardOverlayColor: Color @Composable get() = if (isAphelion()) LocalPurrfectSkin.current.cardOverlayColor else Color(0xFF1B152E)
}

class MessagingPreview: Routes.Route() {
    override val translation by lazy { context.translation.getCategory("manager.sections.social.messaging_preview") }
    private lateinit var coroutineScope: CoroutineScope
    private lateinit var previewScrollState: LazyListState

    private val contentTypeTranslation by lazy { context.translation.getCategory("content_type") }
    private val messagingBridge: MessagingBridge? get() = context.bridgeService?.messagingBridge

    private var messages = mutableStateListOf<Message>()
    private var conversationId by mutableStateOf<String?>(null)
    private val selectedMessages = mutableStateListOf<Long>() // client message id

    private fun toggleSelectedMessage(messageId: Long) {
        if (selectedMessages.contains(messageId)) selectedMessages.remove(messageId)
        else selectedMessages.add(messageId)
    }

    @Composable
    private fun ActionsSheetItem(
        title: String,
        subtitle: String?,
        icon: ImageVector,
        danger: Boolean = false,
        onClick: () -> Unit,
    ) {
        val glowPrimary = MessagingPreviewSkinPalette.glowPrimary
        val glowSecondary = MessagingPreviewSkinPalette.glowSecondary
        val cardOverlayColor = MessagingPreviewSkinPalette.cardOverlayColor
        val textSecondary = MessagingPreviewSkinPalette.textSecondary

        val shape = RoundedCornerShape(18.dp)
        val border = if (danger) {
            Brush.linearGradient(
                listOf(
                    Color(0xFFFF5C8A).copy(alpha = 0.7f),
                    glowSecondary.copy(alpha = 0.35f)
                )
            )
        } else {
            Brush.linearGradient(
                listOf(
                    glowPrimary.copy(alpha = 0.55f),
                    glowSecondary.copy(alpha = 0.35f)
                )
            )
        }

        Surface(
            onClick = onClick,
            shape = shape,
            color = cardOverlayColor.copy(alpha = 0.85f),
            border = BorderStroke(1.dp, border),
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    (if (danger) Color(0xFFFF5C8A) else glowPrimary).copy(alpha = 0.32f),
                                    glowSecondary.copy(alpha = 0.18f)
                                )
                            ),
                            RoundedCornerShape(14.dp)
                        )
                        .border(1.dp, LocalPurrfectSkin.current.textPrimary.copy(alpha = 0.12f), RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = LocalPurrfectSkin.current.textPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        color = LocalPurrfectSkin.current.textPrimary,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!subtitle.isNullOrBlank()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = subtitle,
                            color = textSecondary,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun ConstraintsSelectionDialog(
        onChoose: (Array<ContentType>) -> Unit,
        onDismiss: () -> Unit
    ) {
        val glowPrimary = MessagingPreviewSkinPalette.glowPrimary
        val glowSecondary = MessagingPreviewSkinPalette.glowSecondary
        val cardOverlayColor = MessagingPreviewSkinPalette.cardOverlayColor

        val selectedTypes = remember { mutableStateListOf<ContentType>() }
        var selectAllState by remember { mutableStateOf(false) }
        val availableTypes = remember { arrayOf(
            ContentType.CHAT,
            ContentType.NOTE,
            ContentType.SNAP,
            ContentType.STICKER,
            ContentType.EXTERNAL_MEDIA
        ) }

        fun toggleContentType(contentType: ContentType) {
            if (selectAllState) return
            if (selectedTypes.contains(contentType)) {
                selectedTypes.remove(contentType)
            } else {
                selectedTypes.add(contentType)
            }
        }

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .background(cardOverlayColor)
                .border(1.dp, LocalPurrfectSkin.current.textPrimary.copy(alpha = 0.12f), RoundedCornerShape(22.dp)),
            shape = RoundedCornerShape(22.dp),
            color = Color.Transparent
        ) {
            Column(
                modifier = Modifier.padding(15.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Text(context.translation["manager.dialogs.messaging_action.title"] ?: "Select Filters", color = LocalPurrfectSkin.current.textPrimary)
                Spacer(modifier = Modifier.height(5.dp))
                availableTypes.forEach { contentType ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(2.dp)
                            .pointerInput(Unit) {
                                detectTapGestures(onTap = { toggleContentType(contentType) })
                            },
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = selectedTypes.contains(contentType),
                            enabled = !selectAllState,
                            onCheckedChange = { toggleContentType(contentType) },
                            colors = CheckboxDefaults.colors(
                                checkedColor = glowSecondary,
                                uncheckedColor = LocalPurrfectSkin.current.textPrimary.copy(alpha = 0.85f),
                                checkmarkColor = Color.Black
                            )
                        )
                        Text(text = contentTypeTranslation[contentType.name] ?: contentType.name, color = LocalPurrfectSkin.current.textPrimary)
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(5.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Switch(
                        checked = selectAllState,
                        onCheckedChange = {
                            selectAllState = it
                        },
                        colors = purrfectSwitchColors()
                    )
                    Text(text = context.translation["manager.dialogs.messaging_action.select_all_button"] ?: "Select All", color = LocalPurrfectSkin.current.textPrimary)
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    Button(
                        onClick = { onDismiss() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = LocalPurrfectSkin.current.textPrimary.copy(alpha = 0.08f),
                            contentColor = LocalPurrfectSkin.current.textPrimary
                        )
                    ) {
                        Text(context.translation["button.cancel"] ?: "Cancel", color = LocalPurrfectSkin.current.textPrimary)
                    }
                    Button(
                        onClick = {
                            onChoose(
                                if (selectAllState) ContentType.entries.toTypedArray()
                                else selectedTypes.toTypedArray()
                            )
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = glowPrimary.copy(alpha = 0.34f),
                            contentColor = LocalPurrfectSkin.current.textPrimary
                        )
                    ) {
                        Text(context.translation["button.ok"] ?: "Apply", color = LocalPurrfectSkin.current.textPrimary)
                    }
                }
            }
        }
    }

    @Composable
    private fun ConversationPreview(
        messages: List<Message>,
        scope: SocialScope,
        scopeId: String,
        myUserId: String?,
        friendDisplayName: String?,
        fetchNewMessages: () -> Unit,
    ) {
        val glowPrimary = MessagingPreviewSkinPalette.glowPrimary
        val glowSecondary = MessagingPreviewSkinPalette.glowSecondary
        val textSecondary = MessagingPreviewSkinPalette.textSecondary

        DisposableEffect(Unit) {
            onDispose {
                selectedMessages.clear()
            }
        }

        LazyColumn(
            reverseLayout = true,
            modifier = Modifier
                .fillMaxWidth(),
            state = previewScrollState,
            contentPadding = PaddingValues(top = 10.dp, bottom = routes.bottomPadding + 18.dp)
        ) {
            items(messages, key = { it.serverMessageId }) {message ->
                val messageReader = remember(message.contentType) { ProtoReader(message.content) }
                val contentType = ContentType.fromMessageContainer(messageReader)
                val senderId = message.senderId
                val isMine = senderId != null && senderId == myUserId

                val isSelected = selectedMessages.contains(message.clientMessageId)
                val borderBrush = if (isSelected) {
                    Brush.linearGradient(
                        listOf(
                            glowPrimary.copy(alpha = 0.85f),
                            glowSecondary.copy(alpha = 0.75f)
                        )
                    )
                } else {
                    Brush.linearGradient(
                        listOf(
                            LocalPurrfectSkin.current.textPrimary.copy(alpha = 0.08f),
                            LocalPurrfectSkin.current.textPrimary.copy(alpha = 0.08f)
                        )
                    )
                }

                val senderDisplayName by rememberAsyncMutableState<String?>(null, keys = arrayOf(senderId, myUserId, scope.key, scopeId, friendDisplayName)) {
                    when {
                        senderId == null -> translation["sender_unknown"] ?: "Unknown"
                        senderId == myUserId -> translation["sender_you"] ?: "You"
                        scope == SocialScope.FRIEND -> friendDisplayName
                            ?: context.database.getFriendInfo(scopeId)?.displayName
                            ?: context.database.getFriendInfo(scopeId)?.mutableUsername
                            ?: translation["sender_friend"] ?: "Friend"
                        else -> context.database.getFriendInfo(senderId)?.displayName
                            ?: context.database.getFriendInfo(senderId)?.mutableUsername
                            ?: translation["sender_unknown"] ?: "Unknown"
                    }
                }

                val contentTypeLabel = remember(contentType) {
                    contentType?.let { contentTypeTranslation[it.name] ?: it.name } ?: translation["sender_unknown"] ?: "Unknown"
                }
                val bodyText = remember(message.contentType) { messageReader.getString(2, 1)?.trim().orEmpty() }

                if (contentType == ContentType.STATUS) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 10.dp),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(LocalPurrfectSkin.current.textPrimary.copy(alpha = 0.08f))
                                .border(1.dp, LocalPurrfectSkin.current.textPrimary.copy(alpha = 0.10f), RoundedCornerShape(999.dp))
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Text(
                                text = "[$contentTypeLabel] ${bodyText.ifBlank { "—" }}",
                                color = textSecondary,
                                fontSize = 12.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    return@items
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    horizontalArrangement = if (isMine) Arrangement.End else Arrangement.Start
                ) {
                    val bubbleShape = if (isMine) {
                        RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp, bottomStart = 22.dp, bottomEnd = 8.dp)
                    } else {
                        RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp, bottomStart = 8.dp, bottomEnd = 22.dp)
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth(0.86f)
                            .widthIn(max = 360.dp)
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onLongPress = { toggleSelectedMessage(message.clientMessageId) },
                                    onTap = {
                                        if (selectedMessages.isNotEmpty()) toggleSelectedMessage(message.clientMessageId)
                                    }
                                )
                            },
                        horizontalAlignment = if (isMine) Alignment.End else Alignment.Start
                    ) {
                        Text(
                            text = senderDisplayName ?: translation["sender_unknown"] ?: "Unknown",
                            color = LocalPurrfectSkin.current.textPrimary.copy(alpha = 0.82f),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 12.dp)
                        )
                        Spacer(Modifier.height(4.dp))

                        Box(
                            modifier = Modifier
                                .shadow(
                                    elevation = if (isSelected) 10.dp else 6.dp,
                                    shape = bubbleShape,
                                    clip = true,
                                    ambientColor = glowSecondary.copy(alpha = 0.18f),
                                    spotColor = glowPrimary.copy(alpha = 0.16f),
                                )
                                .clip(bubbleShape)
                                .background(
                                    brush = if (isMine) {
                                        Brush.linearGradient(
                                            listOf(
                                                glowPrimary.copy(alpha = 0.30f),
                                                glowSecondary.copy(alpha = 0.16f)
                                            )
                                        )
                                    } else {
                                        Brush.linearGradient(
                                            listOf(
                                                LocalPurrfectSkin.current.textPrimary.copy(alpha = 0.10f),
                                                LocalPurrfectSkin.current.textPrimary.copy(alpha = 0.06f)
                                            )
                                        )
                                    },
                                    shape = bubbleShape
                                )
                                .border(BorderStroke(1.dp, borderBrush), bubbleShape)
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                                if (contentType != ContentType.CHAT) {
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(999.dp))
                                            .background(LocalPurrfectSkin.current.textPrimary.copy(alpha = 0.10f))
                                            .border(1.dp, LocalPurrfectSkin.current.textPrimary.copy(alpha = 0.10f), RoundedCornerShape(999.dp))
                                            .padding(horizontal = 10.dp, vertical = 4.dp)
                                    ) {
                                        Text(
                                            text = contentTypeLabel,
                                            color = textSecondary,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Spacer(Modifier.height(6.dp))
                                }

                                Text(
                                    text = bodyText.ifBlank { contentTypeLabel },
                                    color = LocalPurrfectSkin.current.textPrimary,
                                    fontSize = 14.sp
                                )
                            }
                        }
                    }
                }
            }
            item {
                if (messages.isEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(40.dp),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(translation["no_message_hint"] ?: "No messages loaded", color = textSecondary)
                    }
                }
                Spacer(modifier = Modifier.height(20.dp))

                LaunchedEffect(Unit) {
                    if (messages.isNotEmpty()) {
                        fetchNewMessages()
                    }
                }
            }
        }
    }


    @Composable
    private fun LoadingRow() {
        val glowSecondary = MessagingPreviewSkinPalette.glowSecondary

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(40.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            CircularProgressIndicator(
                modifier = Modifier
                    .padding()
                    .size(30.dp),
                strokeWidth = 3.dp,
                color = glowSecondary
            )
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override val content: @Composable (NavBackStackEntry) -> Unit = { navBackStackEntry ->
        val backgroundGradient = MessagingPreviewSkinPalette.backgroundGradient
        val textSecondary = MessagingPreviewSkinPalette.textSecondary
        val glowPrimary = MessagingPreviewSkinPalette.glowPrimary
        val glowSecondary = MessagingPreviewSkinPalette.glowSecondary
        val cardOverlayColor = MessagingPreviewSkinPalette.cardOverlayColor

        val scope = remember { SocialScope.getByName(navBackStackEntry.arguments?.getString("scope")!!) }
        val id = remember { navBackStackEntry.arguments?.getString("id")!! }

        previewScrollState = rememberLazyListState()
        coroutineScope = rememberCoroutineScope()
        val density = LocalDensity.current
        var topBarHeight by remember { mutableStateOf(96.dp) }

        val titleText by rememberAsyncMutableState<String?>(null, keys = arrayOf(scope.key, id)) {
            when (scope) {
                SocialScope.FRIEND -> context.database.getFriendInfo(id)?.displayName
                    ?: context.database.getFriendInfo(id)?.mutableUsername
                SocialScope.GROUP -> context.database.getGroupInfo(id)?.name
            }
        }

        var lastMessageId by remember { mutableLongStateOf(Long.MAX_VALUE) }
        var isBridgeConnected by remember { mutableStateOf(false) }
        var hasBridgeError by remember { mutableStateOf(false) }
        var actionsOpen by remember { mutableStateOf(false) }
        var selectConstraintsDialog by remember { mutableStateOf(false) }
        var activeTask by remember { mutableStateOf(null as MessagingTask?) }
        var activeJob by remember { mutableStateOf(null as Job?) }
        val processMessageCount = remember { mutableIntStateOf(0) }

        fun runCurrentTask() {
            activeJob = coroutineScope.launch(Dispatchers.IO) {
                activeTask?.run()
                withContext(Dispatchers.Main) {
                    activeTask = null
                    activeJob = null
                }
            }.also { job ->
                job.invokeOnCompletion {
                    if (it != null) {
                        context.log.verbose("Failed to process messages: ${it.message}")
                        return@invokeOnCompletion
                    }
                    val toastText = translation.format("processed_messages_toast", "count" to processMessageCount.intValue.toString())
                    context.longToast(toastText)
                }
            }
        }

        fun launchMessagingTask(
            taskType: MessagingTaskType,
            constraints: List<MessagingTaskConstraint> = listOf(),
            onSuccess: (Message) -> Unit = {},
        ) {
            if (messagingBridge == null) {
                context.longToast(translation["bridge_connection_error"] ?: "Snapchat not connected")
                return
            }
            actionsOpen = false
            processMessageCount.intValue = 0
            activeTask = MessagingTask(
                messagingBridge!!,
                conversationId!!,
                taskType,
                constraints,
                overrideClientMessageIds = selectedMessages.takeIf { it.isNotEmpty() }?.toList(),
                processedMessageCount = processMessageCount,
                onSuccess = onSuccess,
                onFailure = { message, reason ->
                    context.log.verbose("Failed to process message ${message.clientMessageId}: $reason")
                }
            )
            selectedMessages.clear()
        }

        if (selectConstraintsDialog && activeTask != null) {
            Dialog(onDismissRequest = {
                selectConstraintsDialog = false
                activeTask = null
            }) {
                ConstraintsSelectionDialog(
                    onChoose = { contentTypes ->
                        launchMessagingTask(
                            taskType = activeTask!!.taskType,
                            constraints = activeTask!!.constraints + MessagingConstraints.CONTENT_TYPE(contentTypes),
                            onSuccess = activeTask!!.onSuccess
                        )
                        runCurrentTask()
                        selectConstraintsDialog = false
                    },
                    onDismiss = {
                        selectConstraintsDialog = false
                        activeTask = null
                    }
                )
            }
        }

        if (activeJob != null) {
            Dialog(onDismissRequest = {
                activeJob?.cancel()
                activeJob = null
                activeTask = null
            }) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(cardOverlayColor, RoundedCornerShape(20.dp))
                        .padding(15.dp)
                        .border(1.dp, LocalPurrfectSkin.current.textPrimary.copy(alpha = 0.12f), RoundedCornerShape(20.dp)),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    val processedText = translation.format("processed_messages_text", "count" to processMessageCount.intValue.toString())
                    Text(processedText, color = LocalPurrfectSkin.current.textPrimary)
                    if (activeTask?.hasFixedGoal() == true) {
                        LinearProgressIndicator(
                            progress = { processMessageCount.intValue.toFloat() / selectedMessages.size.toFloat() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(5.dp),
                            color = glowSecondary,
                            trackColor = LocalPurrfectSkin.current.textPrimary.copy(alpha = 0.1f)
                        )
                    } else {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .padding()
                                .size(30.dp),
                            strokeWidth = 3.dp,
                            color = glowSecondary
                        )
                    }
                }
            }
        }

        fun fetchNewMessages() {
            coroutineScope.launch(Dispatchers.IO) cs@{
                runCatching {
                    val queriedMessages = messagingBridge!!.fetchConversationWithMessagesPaginated(
                        conversationId!!,
                        20,
                        lastMessageId
                    )?.reversed() ?: throw IllegalStateException("Failed to fetch messages. Bridge returned null")

                    withContext(Dispatchers.Main) {
                        messages.addAll(queriedMessages)
                        lastMessageId = queriedMessages.lastOrNull()?.clientMessageId ?: lastMessageId
                    }
                }.onFailure {
                    context.log.error("Failed to fetch messages", it)
                    context.shortToast(translation["message_fetch_failed"] ?: "Failed to fetch messages")
                }
            }
        }

        fun onMessagingBridgeReady(scope: SocialScope, scopeId: String) {
            context.log.verbose("onMessagingBridgeReady: $scope $scopeId")

            runCatching {
                conversationId = (if (scope == SocialScope.FRIEND) messagingBridge!!.getOneToOneConversationId(scopeId) else scopeId) ?: throw IllegalStateException("Failed to get conversation id")
                if (runCatching { !messagingBridge!!.isSessionStarted }.getOrDefault(true)) {
                    context.androidContext.packageManager.getLaunchIntentForPackage(
                        Constants.SNAPCHAT_PACKAGE_NAME
                    )?.let {
                        val mainIntent = Intent.makeMainActivity(it.component).apply {
                            putExtra(ReceiversConfig.MESSAGING_PREVIEW_EXTRA, true)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.androidContext.startActivity(mainIntent)
                    }
                    messagingBridge!!.registerSessionStartListener(object: SessionStartListener.Stub() {
                        override fun onConnected() {
                            fetchNewMessages()
                        }
                    })
                    return
                }
                fetchNewMessages()
            }.onFailure {
                context.longToast(translation["bridge_init_failed"] ?: "Messaging engine error")
                context.log.error("Failed to initialize messaging bridge", it)
            }
        }

        LaunchedEffect(Unit) {
            messages.clear()
            conversationId = null

            isBridgeConnected = context.hasMessagingBridge()
            if (isBridgeConnected) {
                withContext(Dispatchers.IO) {
                    onMessagingBridgeReady(scope, id)
                }
            } else {
                coroutineScope.launch(Dispatchers.IO) {
                    SnapWidgetBroadcastReceiverHelper.create("wakeup") {}.also {
                        context.androidContext.sendBroadcast(it)
                    }
                    withTimeout(10000) {
                        while (!context.hasMessagingBridge()) {
                            delay(100)
                        }
                        isBridgeConnected = true
                        onMessagingBridgeReady(scope, id)
                    }
                }.invokeOnCompletion {
                    if (it != null) {
                        hasBridgeError = true
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(backgroundGradient)
        ) {
            FloatingTopBar(
                title = titleText ?: translation["title"] ?: "Preview",
                subtitle = if (selectedMessages.isNotEmpty()) {
                    "${selectedMessages.size} selected"
                } else {
                    translation["subtitle"]?.substringBefore("•")?.trim() ?: ""
                },
                onBack = { routes.navController.popBackStack() },
                actions = {
                    AnimatedVisibility(
                        visible = selectedMessages.isNotEmpty(),
                        enter = fadeIn(),
                        exit = fadeOut()
                    ) {
                        IconButton(onClick = { selectedMessages.clear() }) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = null,
                                tint = LocalPurrfectSkin.current.textPrimary
                            )
                        }
                    }
                    IconButton(onClick = { if (messages.isNotEmpty()) actionsOpen = true }) {
                        Icon(imageVector = Icons.Rounded.MoreVert, contentDescription = null, tint = LocalPurrfectSkin.current.textPrimary)
                    }
                },
                modifier = Modifier
                    .zIndex(2f)
                    .onGloballyPositioned {
                        val newHeight = with(density) { it.size.height.toDp() }
                        if (newHeight != topBarHeight) topBarHeight = newHeight
                    }
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = topBarHeight + 6.dp)
                    .padding(horizontal = 14.dp)
            ) {
                if (hasBridgeError) {
                    Text(
                        translation["bridge_connection_error"] ?: "Snapchat connection lost",
                        modifier = Modifier.padding(16.dp),
                        color = LocalPurrfectSkin.current.textPrimary
                    )
                }

                if (!isBridgeConnected && !hasBridgeError) {
                    LoadingRow()
                }

                if (isBridgeConnected && !hasBridgeError) {
                    ConversationPreview(
                        messages = messages,
                        scope = scope,
                        scopeId = id,
                        myUserId = messagingBridge?.myUserId,
                        friendDisplayName = titleText,
                        fetchNewMessages = ::fetchNewMessages
                    )
                }
            }

            if (actionsOpen) {
                ModalBottomSheet(
                    onDismissRequest = { actionsOpen = false },
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                    containerColor = Color.Transparent,
                    dragHandle = null
                ) {
                    val hasSelection = selectedMessages.isNotEmpty()
                    val selectionSubtitle = if (hasSelection) {
                        "${selectedMessages.size} selected"
                    } else {
                        translation["choose_message_types_subtitle"] ?: "Action filters"
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                color = cardOverlayColor,
                                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
                            )
                            .border(
                                1.dp,
                                Brush.linearGradient(
                                    listOf(
                                        glowPrimary.copy(alpha = 0.5f),
                                        glowSecondary.copy(alpha = 0.3f)
                                    )
                                ),
                                RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
                            )
                            .padding(horizontal = 16.dp, vertical = 16.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterHorizontally)
                                .size(width = 42.dp, height = 5.dp)
                                .clip(RoundedCornerShape(999.dp))
                                .background(LocalPurrfectSkin.current.textPrimary.copy(alpha = 0.14f))
                        )
                        Spacer(Modifier.height(14.dp))
                        Text(
                            text = translation["actions_title"] ?: "Conversation Actions",
                            color = LocalPurrfectSkin.current.textPrimary,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 18.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = selectionSubtitle,
                            color = textSecondary,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(14.dp))

                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            ActionsSheetItem(
                                title = translation[if (hasSelection) "save_selection_option" else "save_all_option"] ?: "Save Messages",
                                subtitle = if (hasSelection) "Save selected items" else "Filter by type",
                                icon = Icons.Rounded.BookmarkAdded
                            ) {
                                launchMessagingTask(MessagingTaskType.SAVE)
                                if (hasSelection) runCurrentTask() else selectConstraintsDialog = true
                            }
                            ActionsSheetItem(
                                title = translation[if (hasSelection) "unsave_selection_option" else "unsave_all_option"] ?: "Unsave Messages",
                                subtitle = if (hasSelection) "Unsave selected items" else "Filter by type",
                                icon = Icons.Rounded.BookmarkBorder
                            ) {
                                launchMessagingTask(MessagingTaskType.UNSAVE)
                                if (hasSelection) runCurrentTask() else selectConstraintsDialog = true
                            }
                            ActionsSheetItem(
                                title = translation[if (hasSelection) "mark_selection_as_seen_option" else "mark_all_as_seen_option"] ?: "Mark as Seen",
                                subtitle = "Clear notification dot",
                                icon = Icons.Rounded.RemoveRedEye
                            ) {
                                if (messagingBridge == null) {
                                    context.longToast(translation["bridge_connection_error"] ?: "Snapchat not connected")
                                    return@ActionsSheetItem
                                }
                                launchMessagingTask(
                                    MessagingTaskType.READ,
                                    listOf(
                                        MessagingConstraints.NO_USER_ID(messagingBridge!!.myUserId),
                                        MessagingConstraints.CONTENT_TYPE(arrayOf(ContentType.SNAP))
                                    )
                                )
                                runCurrentTask()
                            }
                            ActionsSheetItem(
                                title = translation[if (hasSelection) "delete_selection_option" else "delete_all_option"] ?: "Delete Messages",
                                subtitle = "Permanent removal",
                                icon = Icons.Rounded.DeleteForever,
                                danger = true
                            ) {
                                if (messagingBridge == null) {
                                    context.longToast(translation["bridge_connection_error"] ?: "Snapchat not connected")
                                    return@ActionsSheetItem
                                }
                                launchMessagingTask(
                                    MessagingTaskType.DELETE,
                                    listOf(
                                        MessagingConstraints.USER_ID(messagingBridge!!.myUserId),
                                        { contentType != ContentType.STATUS.id }
                                    )
                                ) { message ->
                                    coroutineScope.launch { message.contentType = ContentType.STATUS.id }
                                }
                                if (hasSelection) runCurrentTask() else selectConstraintsDialog = true
                            }
                        }

                        Spacer(Modifier.height(12.dp))
                        Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
                    }
                }
            }
        }
    }
}
