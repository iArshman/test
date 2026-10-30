package me.eternal.purrfect.ui.manager.pages

import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import me.eternal.purrfect.R
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.navigation.NavBackStackEntry
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.eternal.purrfect.bridge.DownloadCallback
import me.eternal.purrfect.common.bridge.wrapper.ConversationInfo
import me.eternal.purrfect.common.bridge.wrapper.LoggedMessage
import me.eternal.purrfect.common.bridge.wrapper.LoggerWrapper
import me.eternal.purrfect.common.data.ContentType
import me.eternal.purrfect.common.data.MessagingFriendInfo
import me.eternal.purrfect.common.data.download.DownloadMetadata
import me.eternal.purrfect.common.data.download.DownloadRequest
import me.eternal.purrfect.common.data.download.MediaDownloadSource
import me.eternal.purrfect.common.data.download.createNewFilePath
import me.eternal.purrfect.common.ui.rememberAsyncMutableState
import me.eternal.purrfect.common.ui.theme.LocalPurrfectSkin
import me.eternal.purrfect.common.ui.theme.PurrfectPalette
import me.eternal.purrfect.common.ui.transparentTextFieldColors
import me.eternal.purrfect.common.util.ktx.copyToClipboard
import me.eternal.purrfect.common.util.ktx.longHashCode
import me.eternal.purrfect.common.util.protobuf.ProtoReader
import me.eternal.purrfect.common.util.snap.BitmojiSelfie
import me.eternal.purrfect.core.features.impl.downloader.decoder.DecodedAttachment
import me.eternal.purrfect.core.features.impl.downloader.decoder.MessageDecoder
import me.eternal.purrfect.download.DownloadProcessor
import me.eternal.purrfect.storage.findFriend
import me.eternal.purrfect.ui.manager.Routes
import me.eternal.purrfect.ui.manager.components.AestheticDialog
import me.eternal.purrfect.ui.manager.components.FloatingTopBar
import me.eternal.purrfect.ui.util.coil.BitmojiImage
import java.net.URLDecoder
import java.text.DateFormat
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.absoluteValue

internal object LoggerSkinPalette {
    @Composable
    internal fun isAphelion(): Boolean {
        val context = LocalContext.current
        return remember(context) { 
            me.eternal.purrfect.SharedContextHolder.remote(context).config.root.global.uiSettings.managerTheme.get() == "APHELION"
        }
    }

    val glowPrimary: Color @Composable get() = if (isAphelion()) LocalPurrfectSkin.current.glowPrimary else PurrfectPalette.glowPrimary
    val glowSecondary: Color @Composable get() = if (isAphelion()) LocalPurrfectSkin.current.glowSecondary else PurrfectPalette.glowSecondary
    val backgroundGradient: Brush @Composable get() = if (isAphelion()) LocalPurrfectSkin.current.backgroundGradient else PurrfectPalette.backgroundGradient
    val cardOverlay: Brush @Composable get() = if (isAphelion()) LocalPurrfectSkin.current.cardOverlay else PurrfectPalette.cardOverlay
    val textPrimary: Color @Composable get() = if (isAphelion()) LocalPurrfectSkin.current.textPrimary else Color.White
    val textSecondary: Color @Composable get() = if (isAphelion()) LocalPurrfectSkin.current.textSecondary else Color(0xFFD9D3FF)
    val cardOverlayColor: Color @Composable get() = if (isAphelion()) LocalPurrfectSkin.current.cardOverlayColor else PurrfectPalette.cardOverlayColor
}

class LoggerHistoryRoot : Routes.Route() {
    override val translation by lazy { context.translation.getCategory("logger_history") }
    private lateinit var loggerWrapper: LoggerWrapper
    private var selectedConversation by mutableStateOf<String?>(null)
    private var stringFilter by mutableStateOf("")
    private var reverseOrder by mutableStateOf(true)

    private data class RichConversationInfo(
        val id: String,
        val displayName: String,
        val username: String?,
        val friendInfo: MessagingFriendInfo? = null,
        val groupTitle: String? = null
    )

    @Composable
    private fun ConversationPickerDialog(
        onDismiss: () -> Unit,
        onSelect: (String) -> Unit
    ) {
        val conversationsIds by rememberAsyncMutableState(defaultValue = emptyList<String>()) {
            loggerWrapper.getAllConversations().toList()
        }
        
        val richConversations = remember(conversationsIds) { mutableStateListOf<RichConversationInfo>() }
        var isResolving by remember { mutableStateOf(true) }

        LaunchedEffect(conversationsIds) {
            if (conversationsIds.isEmpty()) {
                isResolving = false
                return@LaunchedEffect
            }
            withContext(Dispatchers.IO) {
                val resolved = conversationsIds.map { id ->
                    val friend = context.database.findFriend(id)
                    val info = loggerWrapper.getConversationInfo(id)
                    RichConversationInfo(
                        id = id,
                        displayName = friend?.displayName ?: info?.groupTitle ?: id,
                        username = friend?.mutableUsername ?: info?.usernames?.joinToString(", "),
                        friendInfo = friend,
                        groupTitle = info?.groupTitle
                    )
                }
                withContext(Dispatchers.Main) {
                    richConversations.clear()
                    richConversations.addAll(resolved)
                    isResolving = false
                }
            }
        }

        var pickerSearch by remember { mutableStateOf("") }
        val filteredConversations = remember(pickerSearch, richConversations.size) {
            if (pickerSearch.isBlank()) richConversations 
            else richConversations.filter { 
                it.displayName.contains(pickerSearch, ignoreCase = true) || 
                it.username?.contains(pickerSearch, ignoreCase = true) == true ||
                it.id.contains(pickerSearch, ignoreCase = true)
            }
        }

        AestheticDialog(
            onDismissRequest = onDismiss,
            title = translation["select_conversation_placeholder"] ?: "Select Conversation",
            text = "",
            icon = Icons.Default.Forum,
            confirmButtonText = context.translation["button.ok"] ?: "OK",
            onConfirm = onDismiss,
            dismissButtonText = context.translation["button.cancel"],
            onDismiss = onDismiss,
            opaque = true,
            showCloseButton = false,
            showIcon = false,
            customContent = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = pickerSearch,
                        onValueChange = { pickerSearch = it },
                        placeholder = { Text(context.translation["manager.dialogs.add_friend.search_hint"] ?: "Search...", color = LoggerSkinPalette.textSecondary) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = LoggerSkinPalette.textSecondary) },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = LoggerSkinPalette.textPrimary.copy(alpha = 0.08f),
                            unfocusedContainerColor = LoggerSkinPalette.textPrimary.copy(alpha = 0.05f),
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            cursorColor = LoggerSkinPalette.glowSecondary,
                            focusedTextColor = LoggerSkinPalette.textPrimary,
                            unfocusedTextColor = LoggerSkinPalette.textPrimary
                        )
                    )

                    if (isResolving) {
                        Box(modifier = Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = LoggerSkinPalette.glowPrimary)
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.heightIn(max = 600.dp),
                            contentPadding = PaddingValues(vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(filteredConversations, key = { it.id }) { rich ->
                                Surface(
                                    onClick = { onSelect(rich.id) },
                                    shape = RoundedCornerShape(16.dp),
                                    color = LoggerSkinPalette.textPrimary.copy(alpha = 0.05f),
                                    border = BorderStroke(1.dp, LoggerSkinPalette.textPrimary.copy(alpha = 0.1f))
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        if (rich.friendInfo != null) {
                                            BitmojiImage(
                                                modifier = Modifier.size(42.dp).clip(CircleShape),
                                                context = context,
                                                url = rich.friendInfo.takeIf { it.bitmojiId != null }?.let {
                                                    BitmojiSelfie.getBitmojiSelfie(it.selfieId, it.bitmojiId, BitmojiSelfie.BitmojiSelfieType.NEW_THREE_D)
                                                },
                                                size = 42
                                            )
                                        } else {
                                            Box(
                                                modifier = Modifier.size(42.dp).clip(CircleShape).background(LoggerSkinPalette.glowPrimary.copy(alpha = 0.2f)),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    imageVector = if (rich.groupTitle != null) Icons.Default.Groups else Icons.Default.Person,
                                                    contentDescription = null,
                                                    tint = LoggerSkinPalette.glowPrimary,
                                                    modifier = Modifier.size(24.dp)
                                                )
                                            }
                                        }
                                        
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(rich.displayName, fontWeight = FontWeight.Bold, color = LoggerSkinPalette.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            if (rich.username != null) {
                                                Text(rich.username, fontSize = 12.sp, color = LoggerSkinPalette.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        )
    }

    private inline fun decodeMessage(message: LoggedMessage, result: (contentType: ContentType, messageReader: ProtoReader, attachments: List<DecodedAttachment>) -> Unit) {   
        runCatching {
            val messageObject = JsonParser.parseString(String(message.messageData, Charsets.UTF_8)).asJsonObject
            val messageContent = messageObject.getAsJsonObject("mMessageContent")
            val messageReader = messageContent.getAsJsonArray("mContent").map { it.asByte }.toByteArray().let { ProtoReader(it) }
            result(ContentType.fromMessageContainer(messageReader) ?: ContentType.UNKNOWN, messageReader, MessageDecoder.decode(messageContent))
        }.onFailure {
            context.log.error("Failed to decode message", it)
        }
    }

    private fun downloadAttachment(creationTimestamp: Long, attachment: DecodedAttachment) {
        context.shortToast(translation["download_started_toast"])
        val attachmentHash = attachment.mediaUniqueId!!.longHashCode().absoluteValue.toString()

        DownloadProcessor(
            remoteSideContext = context,
            callback = object: DownloadCallback.Default() {
                override fun onSuccess(outputPath: String?) {
                    context.shortToast(translation.format("download_success_toast", "path" to outputPath.toString()))
                }

                override fun onFailure(message: String?, throwable: String?) {
                    context.shortToast(translation.format("download_failed_toast", "message" to message.toString()))
                }
            }
        ).enqueue(
            DownloadRequest(
                inputMedias = arrayOf(attachment.createInputMedia()!!)
            ),
            DownloadMetadata(
                mediaIdentifier = attachmentHash,
                outputPath = createNewFilePath(
                    context.config.root,
                    attachment.mediaUniqueId!!,
                    MediaDownloadSource.MESSAGE_LOGGER,
                    attachmentHash,
                    creationTimestamp
                ),
                iconUrl = null,
                mediaAuthor = null,
                downloadSource = MediaDownloadSource.MESSAGE_LOGGER.translate(context.translation),
            )
        )
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun MessageView(message: LoggedMessage) {
        var contentView by remember { mutableStateOf<@Composable () -> Unit>({
            Spacer(modifier = Modifier.height(30.dp))
        }) }

        val glowPrimary = LoggerSkinPalette.glowPrimary
        val glowSecondary = LoggerSkinPalette.glowSecondary
        val borderBrush = remember(glowPrimary, glowSecondary) {
            Brush.linearGradient(
                listOf(
                    glowPrimary.copy(alpha = 0.45f),
                    glowSecondary.copy(alpha = 0.35f)
                )
            )
        }
        Surface(
            modifier = Modifier
                .padding(vertical = 4.dp)
                .fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            color = LoggerSkinPalette.cardOverlayColor,
            tonalElevation = 0.dp,
            shadowElevation = 12.dp,
            border = BorderStroke(1.dp, borderBrush)
        ) {
            Row(
                modifier = Modifier
                    .padding(12.dp)
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                contentView()

                LaunchedEffect(Unit, message) {
                    runCatching {
                        decodeMessage(message) { contentType, messageReader, attachments ->
                            @Composable
                            fun ContentHeader() {
                                val date = remember { DateFormat.getDateTimeInstance().format(message.sendTimestamp) }
                                Text(
                                    translation.format("log_header_format", "username" to message.username, "type" to contentType.toString().lowercase(), "date" to date),     
                                    modifier = Modifier.padding(end = 4.dp),
                                    fontWeight = FontWeight.ExtraLight,
                                    color = LoggerSkinPalette.textSecondary
                                )
                            }

                            if (contentType == ContentType.CHAT) {
                                val content = messageReader.getString(2, 1) ?: "[${translation["empty_message"]}]"
                                contentView = {
                                    Column {
                                        Text(
                                            content,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .pointerInput(Unit) {
                                                    detectTapGestures(onLongPress = {
                                                        context.androidContext.copyToClipboard(content)
                                                    })
                                                },
                                            color = LoggerSkinPalette.textPrimary
                                        )

                                        val edits by rememberAsyncMutableState(defaultValue = emptyList()) {
                                            loggerWrapper.getChatEdits(selectedConversation!!, message.messageId)
                                        }
                                        edits.forEach { messageEdit ->
                                            val date = remember {
                                                DateFormat.getDateTimeInstance().format(messageEdit.timestamp)
                                            }
                                            Text(
                                                modifier = Modifier.pointerInput(Unit) {
                                                    detectTapGestures(onLongPress = {
                                                        context.androidContext.copyToClipboard(messageEdit.message)
                                                    })
                                                }.fillMaxWidth().padding(start = 4.dp),
                                                text = translation.format("edited_at_text", "message" to messageEdit.message, "date" to date),
                                                fontWeight = FontWeight.Light,
                                                fontStyle = FontStyle.Italic,
                                                fontSize = 12.sp,
                                                color = LoggerSkinPalette.textSecondary
                                            )
                                        }
                                        ContentHeader()
                                    }
                                }
                                return@runCatching
                            }
                            contentView = {
                                Column column@{
                                    if (attachments.isEmpty()) return@column

                                    FlowRow(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(2.dp),
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        attachments.forEachIndexed { index, attachment ->
                                            Button(
                                                onClick = {
                                                    context.coroutineScope.launch {
                                                        runCatching {
                                                            downloadAttachment(message.sendTimestamp, attachment)
                                                        }.onFailure {
                                                            context.log.error("Failed to download attachment", it)
                                                            context.shortToast(translation["download_attachment_failed_toast"])
                                                        }
                                                    }
                                                },
                                                colors = ButtonDefaults.buttonColors(
                                                    containerColor = glowPrimary.copy(alpha = 0.28f),
                                                    contentColor = LoggerSkinPalette.textPrimary
                                                )
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Download,
                                                    contentDescription = translation["download_button"],
                                                    modifier = Modifier.padding(end = 4.dp)
                                                )
                                                Text(translation.format("chat_attachment", "index" to (index + 1).toString()))
                                            }
                                        }
                                    }
                                    ContentHeader()
                                }
                            }
                        }
                    }.onFailure {
                        context.log.error("Failed to parse message", it)
                        contentView = {
                            Text(translation["message_parse_failed"])
                        }
                    }
                }
            }
        }
    }


    @OptIn(ExperimentalMaterial3Api::class)
    override val content: @Composable (NavBackStackEntry) -> Unit = { navBackStackEntry ->
        val avenirNext = remember { FontFamily(Font(R.font.avenir_next_medium, FontWeight.Medium)) }
        LaunchedEffect(Unit) {
            val uri = navBackStackEntry.arguments?.getString("uri")?.let {
                runCatching {
                    URLDecoder.decode(it, "UTF-8").toUri()
                }.getOrNull()
            }
            loggerWrapper = LoggerWrapper(context.androidContext, uri)
        }

        var showPicker by remember { mutableStateOf(false) }
        var showSearchBar by remember { mutableStateOf(false) }
        
        var pinnedIds by remember {
            mutableStateOf(context.sharedPreferences.getStringSet("logger_pinned_conversations", emptySet()) ?: emptySet())
        }

        if (showPicker) {
            ConversationPickerDialog(
                onDismiss = { showPicker = false },
                onSelect = { 
                    selectedConversation = it
                    showPicker = false
                }
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(LoggerSkinPalette.backgroundGradient)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                FloatingTopBar(
                    title = context.translation["manager.routes.logger_history"] ?: "Logger History",
                    onBack = { routes.navController.popBackStack() }
                )

                Column(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                    if (selectedConversation == null) {
                        // State 1: Entry Dashboard
                        Surface(
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 10.dp),
                            shape = RoundedCornerShape(22.dp),
                            color = LoggerSkinPalette.cardOverlayColor,
                            tonalElevation = 0.dp,
                            shadowElevation = 10.dp,
                            border = BorderStroke(1.dp, LoggerSkinPalette.textPrimary.copy(alpha = 0.12f))
                        ) {
                            Column(
                                modifier = Modifier
                                    .background(LoggerSkinPalette.cardOverlay, RoundedCornerShape(22.dp))
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Surface(
                                    onClick = { showPicker = true },
                                    shape = RoundedCornerShape(14.dp),
                                    color = LoggerSkinPalette.textPrimary.copy(alpha = 0.08f),
                                    border = BorderStroke(1.dp, LoggerSkinPalette.textPrimary.copy(alpha = 0.05f))
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        Icon(Icons.Default.Forum, contentDescription = null, tint = LoggerSkinPalette.glowPrimary)
                                        Text(
                                            text = translation["select_conversation_placeholder"] ?: "Select a conversation",
                                            color = LoggerSkinPalette.textPrimary,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                }
                            }
                        }

                        if (pinnedIds.isNotEmpty()) {
                            Text(
                                text = "Pinned Conversations",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = LoggerSkinPalette.textPrimary,
                                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
                            )

                            LazyVerticalGrid(
                                columns = GridCells.Fixed(3),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                items(pinnedIds.toList()) { id ->
                                    var friendInfo by remember { mutableStateOf<MessagingFriendInfo?>(null) }
                                    LaunchedEffect(id) {
                                        withContext(Dispatchers.IO) {
                                            friendInfo = context.database.findFriend(id)
                                        }
                                    }

                                    Surface(
                                        onClick = { selectedConversation = id },
                                        shape = RoundedCornerShape(18.dp),
                                        color = LoggerSkinPalette.textPrimary.copy(alpha = 0.05f),
                                        border = BorderStroke(1.dp, LoggerSkinPalette.textPrimary.copy(alpha = 0.1f))
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(12.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            BitmojiImage(
                                                modifier = Modifier.size(52.dp).clip(CircleShape),
                                                context = context,
                                                url = friendInfo?.takeIf { it.bitmojiId != null }?.let {
                                                    BitmojiSelfie.getBitmojiSelfie(it.selfieId, it.bitmojiId, BitmojiSelfie.BitmojiSelfieType.NEW_THREE_D)
                                                },
                                                size = 52
                                            )
                                            Text(
                                                text = friendInfo?.displayName ?: id,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = LoggerSkinPalette.textPrimary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                textAlign = TextAlign.Center
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        // State 2: Dynamic Island
                        var activeFriend by remember { mutableStateOf<MessagingFriendInfo?>(null) }
                        LaunchedEffect(selectedConversation) {
                            withContext(Dispatchers.IO) {
                                activeFriend = context.database.findFriend(selectedConversation!!)
                            }
                        }

                        val isPinned = remember(pinnedIds, selectedConversation) { pinnedIds.contains(selectedConversation) }

                        Surface(
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp),
                            shape = RoundedCornerShape(22.dp),
                            color = LoggerSkinPalette.cardOverlayColor,
                            border = BorderStroke(1.dp, LoggerSkinPalette.textPrimary.copy(alpha = 0.12f))
                        ) {
                            Column(modifier = Modifier.background(LoggerSkinPalette.cardOverlay).padding(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    if (activeFriend != null) {
                                        BitmojiImage(
                                            modifier = Modifier.size(48.dp).clip(CircleShape),
                                            context = context,
                                            url = activeFriend?.takeIf { it.bitmojiId != null }?.let {
                                                BitmojiSelfie.getBitmojiSelfie(it.selfieId, it.bitmojiId, BitmojiSelfie.BitmojiSelfieType.NEW_THREE_D)
                                            },
                                            size = 48
                                        )
                                    } else {
                                        Box(
                                            modifier = Modifier.size(48.dp).clip(CircleShape).background(LoggerSkinPalette.glowPrimary.copy(alpha = 0.2f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(Icons.Default.Person, contentDescription = null, tint = LoggerSkinPalette.glowPrimary, modifier = Modifier.size(28.dp))
                                        }
                                    }

                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(activeFriend?.displayName ?: selectedConversation!!, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, color = LoggerSkinPalette.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        if (activeFriend?.mutableUsername != null) {
                                            Text("(${activeFriend!!.mutableUsername})", fontSize = 12.sp, color = LoggerSkinPalette.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                    
                                    IconButton(onClick = { showSearchBar = !showSearchBar }) {
                                        Icon(Icons.Default.Search, contentDescription = "Search", tint = if (showSearchBar) LoggerSkinPalette.glowPrimary else LoggerSkinPalette.textPrimary)
                                    }
                                    IconButton(onClick = { 
                                        selectedConversation = null
                                        stringFilter = ""
                                        showSearchBar = false
                                    }) {
                                        Icon(Icons.Default.Close, contentDescription = "Clear", tint = Color(0xFFFF8585))
                                    }
                                }

                                AnimatedVisibility(
                                    visible = showSearchBar,
                                    enter = expandVertically() + fadeIn(),
                                    exit = shrinkVertically() + fadeOut()
                                ) {
                                    OutlinedTextField(
                                        value = stringFilter,
                                        onValueChange = { stringFilter = it },
                                        placeholder = { Text(context.translation["manager.dialogs.add_friend.search_hint"] ?: "Search messages...", color = LoggerSkinPalette.textSecondary) },
                                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                                        shape = RoundedCornerShape(16.dp),
                                        singleLine = true,
                                        trailingIcon = {
                                            if (stringFilter.isNotEmpty()) {
                                                IconButton(onClick = { stringFilter = "" }) {
                                                    Icon(Icons.Default.Clear, contentDescription = null, tint = LoggerSkinPalette.textPrimary)
                                                }
                                            }
                                        },
                                        colors = TextFieldDefaults.colors(
                                            focusedContainerColor = LoggerSkinPalette.textPrimary.copy(alpha = 0.08f),
                                            unfocusedContainerColor = LoggerSkinPalette.textPrimary.copy(alpha = 0.05f),
                                            focusedIndicatorColor = Color.Transparent,
                                            unfocusedIndicatorColor = Color.Transparent,
                                            cursorColor = LoggerSkinPalette.glowSecondary,
                                            focusedTextColor = LoggerSkinPalette.textPrimary,
                                            unfocusedTextColor = LoggerSkinPalette.textPrimary
                                        )
                                    )
                                }
                                
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(
                                            checked = isPinned,
                                            onCheckedChange = {
                                                val newPinned = pinnedIds.toMutableSet()
                                                if (isPinned) newPinned.remove(selectedConversation) else newPinned.add(selectedConversation!!)
                                                context.sharedPreferences.edit().putStringSet("logger_pinned_conversations", newPinned).apply()
                                                pinnedIds = newPinned
                                            },
                                            colors = CheckboxDefaults.colors(
                                                checkedColor = LoggerSkinPalette.glowPrimary,
                                                checkmarkColor = LoggerSkinPalette.textPrimary,
                                                uncheckedColor = LoggerSkinPalette.textPrimary.copy(alpha = 0.35f)
                                            )
                                        )
                                        Text("Pin Chat", color = LoggerSkinPalette.textSecondary, fontSize = 12.sp, fontFamily = avenirNext)
                                    }

                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(translation["reverse_order_checkbox"], color = LoggerSkinPalette.textSecondary, fontSize = 12.sp, fontFamily = avenirNext)
                                        Checkbox(
                                            checked = reverseOrder,
                                            onCheckedChange = { reverseOrder = it },
                                            colors = CheckboxDefaults.colors(
                                                checkedColor = LoggerSkinPalette.glowPrimary,
                                                checkmarkColor = LoggerSkinPalette.textPrimary,
                                                uncheckedColor = LoggerSkinPalette.textPrimary.copy(alpha = 0.35f)
                                            )
                                        )
                                    }
                                }
                            }
                        }

                        var hasReachedEnd by remember(selectedConversation, stringFilter, reverseOrder) { mutableStateOf(false) }
                        var lastFetchMessageTimestamp by remember(selectedConversation, stringFilter, reverseOrder) { mutableLongStateOf(if (reverseOrder) Long.MAX_VALUE else Long.MIN_VALUE) }
                        val messages = remember(selectedConversation, stringFilter, reverseOrder) { mutableStateListOf<LoggedMessage>() }

                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = routes.bottomPadding)
                        ) {
                            items(messages) { message ->
                                MessageView(message)
                            }
                            item {
                                if (hasReachedEnd) {
                                    Text(translation["no_more_messages"], modifier = Modifier.padding(16.dp).fillMaxWidth(), textAlign = TextAlign.Center, color = LoggerSkinPalette.textPrimary)
                                } else {
                                    Row(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                                        CircularProgressIndicator(modifier = Modifier.size(24.dp), color = LoggerSkinPalette.glowSecondary, strokeWidth = 2.dp)
                                    }
                                }
                                LaunchedEffect(Unit, selectedConversation, stringFilter, reverseOrder) {
                                    withContext(Dispatchers.IO) {
                                        val newMessages = loggerWrapper.fetchMessages(
                                            selectedConversation!!,
                                            lastFetchMessageTimestamp,
                                            30,
                                            reverseOrder
                                        ) { messageData ->
                                            if (stringFilter.isEmpty()) return@fetchMessages true
                                            var isMatch = false
                                            decodeMessage(messageData) { contentType, messageReader, _ ->
                                                if (contentType == ContentType.CHAT) {
                                                    val content = messageReader.getString(2, 1) ?: return@decodeMessage
                                                    isMatch = content.contains(stringFilter, ignoreCase = true)
                                                }
                                            }
                                            isMatch
                                        }
                                        if (newMessages.isEmpty()) {
                                            hasReachedEnd = true
                                            return@withContext
                                        }
                                        lastFetchMessageTimestamp = newMessages.lastOrNull()?.sendTimestamp ?: return@withContext
                                        withContext(Dispatchers.Main) {
                                            messages.addAll(newMessages)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
