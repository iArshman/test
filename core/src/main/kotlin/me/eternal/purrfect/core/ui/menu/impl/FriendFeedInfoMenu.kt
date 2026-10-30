package me.eternal.purrfect.core.ui.menu.impl

import android.graphics.BitmapFactory
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Message
import androidx.compose.material.icons.filled.CheckCircleOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.NotInterested
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.eternal.purrfect.common.data.ContentType
import me.eternal.purrfect.common.data.FriendLinkType
import me.eternal.purrfect.common.database.impl.ConversationMessage
import me.eternal.purrfect.common.database.impl.FriendInfo
import me.eternal.purrfect.common.scripting.ui.EnumScriptInterface
import me.eternal.purrfect.common.scripting.ui.InterfaceManager
import me.eternal.purrfect.common.scripting.ui.ScriptInterface
import me.eternal.purrfect.common.ui.createComposeAlertDialog
import me.eternal.purrfect.common.ui.createComposeView
import me.eternal.purrfect.common.ui.rememberAsyncMutableState
import me.eternal.purrfect.common.ui.theme.LocalPurrfectSkin
import me.eternal.purrfect.common.util.protobuf.ProtoReader
import me.eternal.purrfect.common.util.snap.BitmojiSelfie
import me.eternal.purrfect.core.event.events.impl.AddViewEvent
import me.eternal.purrfect.core.features.impl.experiments.EndToEndEncryption
import me.eternal.purrfect.core.features.impl.messaging.AutoMarkAsRead
import me.eternal.purrfect.core.features.impl.messaging.Messaging
import me.eternal.purrfect.core.features.impl.spying.MessageLogger
import me.eternal.purrfect.core.features.impl.spying.StealthMode
import me.eternal.purrfect.core.ui.PurrfectOverlayTheme
import me.eternal.purrfect.core.ui.PurrfectGlassCard
import me.eternal.purrfect.core.ui.children
import me.eternal.purrfect.core.ui.menu.AbstractMenu
import me.eternal.purrfect.core.ui.triggerRootCloseTouchEvent
import me.eternal.purrfect.core.util.ktx.isDarkTheme
import me.eternal.purrfect.core.util.ktx.vibrateLongPress
import me.eternal.purrfect.core.wrapper.impl.sanitizeForLayout
import java.net.URL
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalFoundationApi::class)
class FriendFeedInfoMenu : AbstractMenu() {
    private fun formatDate(timestamp: Long): String? {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH).format(Date(timestamp))
    }

    private fun showProfileInfo(profile: FriendInfo) {
        val translation = context.translation.getCategory("profile_info")
        val firstCreatedUsername = context.database.getFriendOriginalUsername(profile.mutableUsername.toString()) ?: profile.firstCreatedUsername

        context.runOnUiThread {
            val addedTimestamp: Long = profile.addedTimestamp.coerceAtLeast(profile.reverseAddedTimestamp)
            val birthday = Calendar.getInstance()
            birthday[Calendar.MONTH] = (profile.birthday shr 32).toInt() - 1
            val birthdayLine = birthday.getDisplayName(
                Calendar.MONTH,
                Calendar.LONG,
                context.translation.loadedLocale
            )?.let {
                if (profile.birthday == 0L) context.translation["profile_info.hidden_birthday"]
                else context.translation.format(
                    "profile_info.birthday",
                    "month" to it,
                    "day" to profile.birthday.toInt().toString()
                )
            }
            val friendshipLine = run {
                context.translation["friendship_link_type.${FriendLinkType.fromValue(profile.friendLinkType).shortName}"]
            }.takeIf {
                if (profile.friendLinkType == FriendLinkType.MUTUAL.value) addedTimestamp.toInt() > 0 else true
            }
            val snapchatPlusState = translation.getCategory("snapchat_plus_state")[
                if (profile.postViewEmoji != null) "subscribed" else "not_subscribed"
            ]

            val lines = buildList {
                translation["first_created_username"]?.let { add("$it: $firstCreatedUsername") }
                translation["mutable_username"]?.let { add("$it: ${profile.mutableUsername}") }
                profile.displayName?.let { name ->
                    translation["display_name"]?.let { add("$it: $name") }
                }
                formatDate(addedTimestamp)?.takeIf { addedTimestamp > 0 }?.let { date ->
                    translation["added_date"]?.let { add("$it: $date") }
                }
                birthdayLine?.let { add(it) }
                friendshipLine?.let { value ->
                    translation["friendship"]?.let { add("$it: $value") }
                }
                context.database.getAddSource(profile.userId!!)?.takeIf { it.isNotEmpty() }?.let { value ->
                    translation["add_source"]?.let { add("$it: $value") }
                }
                translation["snapchat_plus"]?.let { add("$it: $snapchatPlusState") }
            }

            val avatarUrl = runCatching {
                if (profile.bitmojiSelfieId != null && profile.bitmojiAvatarId != null) {
                    BitmojiSelfie.getBitmojiSelfie(
                        profile.bitmojiSelfieId.toString(),
                        profile.bitmojiAvatarId.toString(),
                        BitmojiSelfie.BitmojiSelfieType.NEW_THREE_D
                    )
                } else null
            }.getOrNull()

            createComposeAlertDialog(context.mainActivity!!) { alertDialog ->
                PurrfectOverlayTheme(this@FriendFeedInfoMenu.context) {
                    val skin = LocalPurrfectSkin.current
                    val avatarBitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(initialValue = null, avatarUrl) {
                        value = runCatching {
                            val url = avatarUrl ?: return@runCatching null
                            val bytes = withContext(Dispatchers.IO) { URL(url).readBytes() }
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                        }.getOrNull()
                    }

                    PurrfectGlassCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .widthIn(max = 420.dp),
                        title = translation["title"] ?: "Profile Info",
                        subtitle = listOfNotNull(
                            profile.displayName,
                            profile.username,
                            profile.mutableUsername
                        ).firstOrNull()?.toString().orEmpty(),
                        icon = Icons.Outlined.Info
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Surface(
                                shape = RoundedCornerShape(999.dp),
                                color = skin.textPrimary.copy(alpha = 0.08f),
                                border = BorderStroke(1.dp, skin.textPrimary.copy(alpha = 0.12f))
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(72.dp)
                                        .padding(10.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (avatarBitmap != null) {
                                        Image(
                                            bitmap = avatarBitmap!!,
                                            contentDescription = null,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    } else {
                                        Icon(
                                            Icons.Outlined.Person,
                                            contentDescription = null,
                                            tint = skin.textPrimary.copy(alpha = 0.85f),
                                            modifier = Modifier.size(32.dp)
                                        )
                                    }
                                }
                            }
                            Surface(
                                shape = RoundedCornerShape(18.dp),
                                color = skin.textPrimary.copy(alpha = 0.06f),
                                border = BorderStroke(1.dp, skin.textPrimary.copy(alpha = 0.12f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 12.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    lines.forEach { line ->
                                        Text(
                                            text = line,
                                            color = skin.textPrimary,
                                            fontSize = 13.sp,
                                            lineHeight = 16.sp
                                        )
                                    }
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                Text(
                                    text = context.translation["button.ok"] ?: "OK",
                                    color = skin.glowPrimary,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier
                                        .clickable { alertDialog.dismiss() }
                                        .padding(horizontal = 6.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                }
            }.show()
        }
    }

    private suspend fun showConversationPreview(
        targetUser: String?,
        conversationId: String
    ) {
        val friendInfo = targetUser?.let { context.database.getFriendInfo(it) }
        val conversationInfo = conversationId.takeIf { targetUser == null }?.let { context.database.getFeedEntryByConversationId(it) }
        val participants by lazy {
            context.database.getConversationParticipants(conversationId)!!
                .map { context.database.getFriendInfo(it)!! }
                .associateBy { it.userId!! }
        }

        withContext(Dispatchers.Main) {
            createComposeAlertDialog(
                context.mainActivity!!,
            ) {
                var pageIndex by remember { mutableIntStateOf(0) }
                val messages = remember { mutableStateListOf<@Composable () -> Unit>() }
                var totalMessages by remember { mutableIntStateOf(-1) }
                val coroutineScope = rememberCoroutineScope()

                suspend fun loadMore() {
                    val conversationMessages = context.database.getMessagesFromConversationId(
                        conversationId,
                        50,
                        page = pageIndex++
                    ) ?: emptyList()

                    if (totalMessages == -1) {
                        totalMessages = conversationMessages.firstOrNull()?.serverMessageId ?: 0
                    }

                    val messageLogger = context.feature(MessageLogger::class)
                    val endToEndEncryption = context.feature(EndToEndEncryption::class)

                    val parsedMessages = conversationMessages.mapNotNull<ConversationMessage, @Composable () -> Unit> { message ->
                        val sender = participants[message.senderId]
                        val messageProtoReader =
                            (messageLogger.takeIf { it.isEnabled && message.contentType == ContentType.STATUS.id }?.getMessageProto(conversationId, message.clientMessageId.toLong()) // process deleted messages if message logger is enabled
                                ?: ProtoReader(message.messageContent!!).followPath(4, 4) // database message
                            )?.let {
                            if (endToEndEncryption.isEnabled) endToEndEncryption.decryptDatabaseMessage(message) else it // try to decrypt message if e2ee is enabled
                        } ?: return@mapNotNull null

                        val contentType = ContentType.fromMessageContainer(messageProtoReader) ?: ContentType.fromId(message.contentType)
                        var messageString = if (contentType == ContentType.CHAT) {
                            messageProtoReader.getString(2, 1) ?: return@mapNotNull null
                        } else "[${context.translation.getOrNull("content_type.${contentType.name}") ?: contentType.name}]"

                        if (contentType == ContentType.SNAP) {
                            messageString = "\uD83D\uDFE5" //red square
                            if (message.readTimestamp > 0) {
                                messageString += " \uD83D\uDC40 " //eyes
                                messageString += DateFormat.getDateTimeInstance(
                                    DateFormat.SHORT,
                                    DateFormat.SHORT
                                ).format(Date(message.readTimestamp))
                            }
                        }

                        messageString = messageString.sanitizeForLayout()

                        var displayUsername = sender?.displayName ?: sender?.usernameForSorting ?: context.translation["conversation_preview.unknown_user"]
                        displayUsername = displayUsername.sanitizeForLayout()

                        if (displayUsername.length > 12) {
                            displayUsername = displayUsername.substring(0, 13) + "... "
                        }

                        {
                            val skin = LocalPurrfectSkin.current
                            Text(
                                text = "$displayUsername: $messageString",
                                modifier = Modifier.padding(4.dp),
                                color = skin.textPrimary
                            )
                        }
                    }

                    withContext(Dispatchers.Main) {
                        messages.addAll(parsedMessages)
                    }
                }

                PurrfectOverlayTheme(this@FriendFeedInfoMenu.context) {
                    val skin = LocalPurrfectSkin.current
                    PurrfectGlassCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .widthIn(max = 420.dp),
                        title = context.translation.getOrNull("conversation_preview.title") ?: "Preview",
                        subtitle = (friendInfo?.displayName ?: conversationInfo?.feedDisplayName ?: conversationId).sanitizeForLayout(),
                        icon = Icons.Outlined.Visibility
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 560.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            @Composable
                            fun Entry(icon: ImageVector, text: String?, title: Boolean) {
                                val skin = LocalPurrfectSkin.current
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(icon, contentDescription = null, tint = skin.textPrimary.copy(alpha = 0.9f))
                                    Text(
                                        text = text ?: "",
                                        fontWeight = if (title) FontWeight.Bold else FontWeight.Normal,
                                        fontSize = if (title) 14.sp else 12.sp,
                                        color = skin.textPrimary.copy(alpha = if (title) 0.95f else 0.80f),
                                        maxLines = if (title) 1 else 2,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                    )
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    friendInfo?.let { info ->
                                        Entry(
                                            Icons.Outlined.Person,
                                            info.displayName?.let { "$it (${info.usernameForSorting})" } ?: info.usernameForSorting,
                                            true
                                        )
                                    }
                                    conversationInfo?.let {
                                        Entry(Icons.Outlined.Group, (it.feedDisplayName ?: it.key).toString(), true)
                                    }
                                    Entry(
                                        Icons.AutoMirrored.Outlined.Message,
                                        context.translation.format("conversation_preview.total_messages", "count" to totalMessages.toString()),
                                        false
                                    )
                                }
                                friendInfo?.let {
                                    IconButton(
                                        onClick = { coroutineScope.launch(Dispatchers.IO) { showProfileInfo(it) } }
                                    ) {
                                        Icon(Icons.Outlined.MoreVert, contentDescription = null, tint = skin.textPrimary)
                                    }
                                }
                            }

                            Spacer(
                                modifier = Modifier
                                    .height(1.dp)
                                    .fillMaxWidth()
                                    .background(skin.textPrimary.copy(alpha = 0.10f))
                            )

                            LazyColumn(
                                modifier = Modifier.fillMaxWidth().weight(1f),
                                contentPadding = PaddingValues(8.dp),
                                reverseLayout = true
                            ) {
                                items(messages) { message ->
                                    Row(modifier = Modifier.fillMaxWidth()) {
                                        CompositionLocalProvider(
                                            LocalContentColor provides skin.textPrimary
                                        ) {
                                            message()
                                        }
                                    }
                                }
                                item {
                                    Spacer(modifier = Modifier.height(10.dp))
                                    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { loadMore() } }
                                    if (messages.isEmpty()) {
                                        Text(
                                            text = context.translation["conversation_preview.no_messages"],
                                            modifier = Modifier
                                                .padding(4.dp)
                                                .fillMaxWidth(),
                                            textAlign = TextAlign.Center,
                                            color = skin.textPrimary.copy(alpha = 0.75f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }.show()
        }
    }

    @Composable
    private fun ListButton(
        icon: ImageVector? = null,
        text: String,
        onClick: () -> Unit,
        onLongClick: (() -> Unit)? = null,
        showDivider: Boolean = true,
        content: @Composable RowScope.() -> Unit = {}
    ) {
        val skin = LocalPurrfectSkin.current
        val contentColor = skin.textPrimary

        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = onClick,
                        onLongClick = {
                            context.androidContext.vibrateLongPress()
                            onLongClick?.invoke()
                        }
                    )
                    .height(56.dp)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (icon != null) {
                    Icon(
                        modifier = Modifier.size(22.dp),
                        imageVector = icon,
                        tint = contentColor,
                        contentDescription = text
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                }
                Text(
                    text = text,
                    modifier = Modifier.weight(1f),
                    color = contentColor,
                    fontWeight = FontWeight.Medium,
                    fontSize = 16.sp
                )
                content()
            }
            if (showDivider) {
                Spacer(
                    modifier = Modifier
                        .height(1.dp)
                        .fillMaxWidth()
                        .background(contentColor.copy(alpha = 0.12f))
                )
            }
        }
    }

    private val messaging by lazy { context.feature(Messaging::class)}

    override fun onViewAdded(event: AddViewEvent) {
        fun hasAvatarHeader(viewGroup: ViewGroup): Boolean {
            val constraintLayout = viewGroup.getChildAt(0)?.takeIf { it.javaClass.name.endsWith("ConstraintLayout") } as? ViewGroup ?: return false
            return constraintLayout.children().firstOrNull { it.javaClass.name.endsWith("AvatarView") } != null
        }

        // Persistent Long-Press Nesting Logic from build 88071a4
        if (messaging.lastFocusedConversationType == 1 &&
            event.viewClassName.endsWith("ConstraintLayout") &&
            event.parent.javaClass.name.endsWith("RecyclerView")
        ) {
            val actionSheetItemsContainerLayout = LinearLayout(event.view.context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }

            injectIntoActionSheetItems(actionSheetItemsContainerLayout) {
                actionSheetItemsContainerLayout.addView(it, 0)
            }

            (event.view as? ViewGroup)?.addView(actionSheetItemsContainerLayout, 0)
            actionSheetItemsContainerLayout.post {
                val parentViewGroup = actionSheetItemsContainerLayout.parent as? ViewGroup ?: return@post
                val topOffset = parentViewGroup.children()
                    .filter { it !== actionSheetItemsContainerLayout && it.visibility != View.GONE }
                    .maxOfOrNull { child: View ->
                        child.bottom.takeIf { it > 0 } ?: child.measuredHeight
                    } ?: 0

                val desiredPadding = topOffset + this@FriendFeedInfoMenu.context.userInterface.dpToPx(10)
                if (actionSheetItemsContainerLayout.paddingTop != desiredPadding) {
                    actionSheetItemsContainerLayout.setPadding(
                        actionSheetItemsContainerLayout.paddingLeft,
                        desiredPadding,
                        actionSheetItemsContainerLayout.paddingRight,
                        actionSheetItemsContainerLayout.paddingBottom
                    )
                    actionSheetItemsContainerLayout.requestLayout()
                }
            }
        }

        if (event.parent is LinearLayout && event.viewClassName.endsWith("SnapCardView") && hasAvatarHeader(event.parent)) {
            val actionSheetItemsContainerLayout = (event.view as ViewGroup).getChildAt(0) as? ViewGroup ?: throw IllegalStateException("ActionSheetItemsContainerLayout not found")
            injectIntoActionSheetItems(actionSheetItemsContainerLayout) {
                actionSheetItemsContainerLayout.addView(it, 0)
            }
        }
    }

    @Composable
    private fun RuleToggle(
        ruleType: me.eternal.purrfect.common.data.MessagingRuleType,
        conversationId: String,
        targetUser: String?,
        showDivider: Boolean,
        closeMenu: () -> Unit
    ) {
        val ruleFeature = context.features.getRuleFeatures().first { it.ruleType == ruleType }
        val ruleState = ruleFeature.getRuleState() ?: return
        var state by remember { mutableStateOf(ruleFeature.getState(conversationId)) }
        val skin = LocalPurrfectSkin.current

        fun toggle() {
            state = !ruleFeature.getState(conversationId)
            ruleFeature.setState(conversationId, state)
            context.inAppOverlay.showStatusToast(
                if (state) Icons.Default.CheckCircleOutline else Icons.Default.NotInterested,
                context.translation.format("rules.toasts.${if (state) "enabled" else "disabled"}", "ruleName" to context.translation[ruleType.translateOptionKey(ruleState.key)]),
                durationMs = 1500
            )
            closeMenu()
        }

        val rawText = context.translation[ruleType.translateOptionKey(ruleState.key)] ?: ruleType.key
        val cleanText = remember(rawText) { rawText.replace(Regex("[^\\p{L}\\p{N}\\p{P}\\p{Z}]"), "").trim() }

        ListButton(
            icon = ruleType.icon,
            text = cleanText,
            showDivider = showDivider,
            onClick = { toggle() }
        ) {
            Switch(
                checked = state,
                onCheckedChange = { toggle() },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = skin.glowPrimary.copy(alpha = 0.55f),
                    checkedBorderColor = Color.Transparent,
                    uncheckedThumbColor = Color.White.copy(alpha = 0.9f),
                    uncheckedTrackColor = skin.textPrimary.copy(alpha = 0.12f),
                    uncheckedBorderColor = skin.textPrimary.copy(alpha = 0.18f)
                )
            )
        }
    }

    private fun injectIntoActionSheetItems(actionSheetItemsContainer: View, viewConsumer: ((View) -> Unit)) {
        val friendFeedMenuOptions by context.config.userInterface.friendFeedMenuButtons
        if (friendFeedMenuOptions.isEmpty()) return

        val messaging = context.feature(Messaging::class)
        val conversationId = messaging.lastFocusedConversationId ?: return
        val targetUser by lazy { context.database.getDMOtherParticipant(conversationId) }
        messaging.resetLastFocusedConversation()

        val translation = context.translation.getCategory("friend_menu_option")

        fun closeMenu() {
            if (!context.config.userInterface.autoCloseFriendFeedMenu.get()) return
            context.mainActivity?.triggerRootCloseTouchEvent()
        }

        @Composable
        fun ComposeFriendFeedMenu() {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Transparent)
            ) {
                val ruleFeatures = context.features.getRuleFeatures()
                val allOptions = remember(friendFeedMenuOptions) {
                    ruleFeatures.filter { friendFeedMenuOptions.contains(it.ruleType.key) }.map { it.ruleType }
                }

                val showPreview = friendFeedMenuOptions.contains("conversation_info")
                val showMarkSnaps = friendFeedMenuOptions.contains("mark_snaps_as_seen")
                val showMarkChat = friendFeedMenuOptions.contains("mark_chat_as_read")
                val showMarkStories = targetUser != null && friendFeedMenuOptions.contains("mark_stories_as_seen_locally")

                if (showPreview) {
                    ListButton(
                        Icons.Outlined.RemoveRedEye,
                        translation["preview"],
                        showDivider = allOptions.isNotEmpty() || showMarkSnaps || showMarkChat || showMarkStories,
                        onClick = {
                            context.coroutineScope.launch {
                                showConversationPreview(targetUser, conversationId)
                            }
                        }
                    )
                }

                allOptions.forEachIndexed { index, ruleType ->
                    val isLast = index == allOptions.size - 1 && !showMarkSnaps && !showMarkChat && !showMarkStories
                    RuleToggle(
                        ruleType = ruleType,
                        conversationId = conversationId,
                        targetUser = targetUser,
                        showDivider = !isLast,
                        closeMenu = { closeMenu() }
                    )
                }

                if (showMarkSnaps) {
                    val label = remember { (translation["mark_snaps_as_seen"] ?: "").replace(Regex("[^\\p{L}\\p{N}\\p{P}\\p{Z}]"), "").trim() }
                    ListButton(
                        Icons.Outlined.EditNote,
                        label,
                        showDivider = showMarkChat || showMarkStories,
                        onClick = {
                            context.apply {
                                closeMenu()
                                feature(AutoMarkAsRead::class).markSnapsAsSeen(conversationId)
                            }
                        }
                    )
                }

                if (showMarkChat) {
                    val label = remember { (translation["mark_chat_as_read"] ?: "").replace(Regex("[^\\p{L}\\p{N}\\p{P}\\p{Z}]"), "").trim() }
                    ListButton(
                        Icons.Outlined.MarkChatRead,
                        label,
                        showDivider = showMarkStories,
                        onClick = {
                            context.apply {
                                closeMenu()
                                val latestMessageId = database.getMessagesFromConversationId(conversationId, 1)
                                    ?.firstOrNull()
                                    ?.clientMessageId
                                    ?.toLong()
                                    ?: return@apply

                                feature(StealthMode::class).addDisplayedMessageException(latestMessageId)
                                feature(Messaging::class).conversationManager?.displayedMessages(
                                    conversationId,
                                    latestMessageId
                                ) { error ->
                                    if (error != null) {
                                        log.error("Failed to mark chat as read: $error")
                                        shortToast(this.translation["toast_mark_conversation_read_failed"])
                                    } else {
                                        inAppOverlay.showStatusToast(
                                            Icons.Default.Info,
                                            translation["mark_chat_as_read_toast"],
                                            durationMs = 1800
                                        )
                                    }
                                }
                            }
                        }
                    )
                }

                if (showMarkStories) {
                    val markAsSeenTranslation = remember { context.translation.getCategory("mark_as_seen") }
                    val label = remember { (translation["mark_stories_as_seen_locally"] ?: "").replace(Regex("[^\\p{L}\\p{N}\\p{P}\\p{Z}]"), "").trim() }

                    ListButton(
                        Icons.Outlined.RemoveRedEye,
                        label,
                        showDivider = false,
                        onClick = {
                            context.apply {
                                closeMenu()
                                inAppOverlay.showStatusToast(
                                    Icons.Default.Info,
                                    if (database.setStoriesViewedState(targetUser!!, true)) markAsSeenTranslation["seen_toast"]
                                    else markAsSeenTranslation["already_seen_toast"],
                                    durationMs = 2500
                                )
                            }
                        },
                        onLongClick = {
                            context.apply {
                                closeMenu()
                                inAppOverlay.showStatusToast(
                                    Icons.Default.Info,
                                    if (database.setStoriesViewedState(targetUser!!, false)) markAsSeenTranslation["unseen_toast"]
                                    else markAsSeenTranslation["already_unseen_toast"],
                                    durationMs = 2500
                                )
                            }
                        }
                    )
                }
            }
        }

        viewConsumer(
            createComposeView(actionSheetItemsContainer.context) {
                PurrfectOverlayTheme(this@FriendFeedInfoMenu.context) {
                    CompositionLocalProvider(
                        LocalTextStyle provides LocalTextStyle.current.merge(
                            TextStyle(
                                fontFamily = FontFamily.SansSerif,
                                fontWeight = FontWeight.Medium
                            )
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 10.dp, bottom = 0.dp)
                        ) {
                            ComposeFriendFeedMenu()
                        }
                    }
                }
            }.apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
        )

        if (context.config.scripting.integratedUI.get()) {
            context.scriptRuntime.eachModule {
                val interfaceManager = getBinding(InterfaceManager::class)
                    ?.takeIf {
                        it.hasInterface(EnumScriptInterface.FRIEND_FEED_CONTEXT_MENU)
                    } ?: return@eachModule

                viewConsumer(LinearLayout(actionSheetItemsContainer.context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )

                    orientation = LinearLayout.VERTICAL
                    addView(createComposeView(actionSheetItemsContainer.context) {
                        PurrfectOverlayTheme(this@FriendFeedInfoMenu.context) {
                            val skin = LocalPurrfectSkin.current
                            val shape = RoundedCornerShape(18.dp)
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(skin.textPrimary.copy(alpha = 0.06f), shape)
                                    .border(
                                        1.dp,
                                        Brush.linearGradient(
                                            listOf(
                                                skin.glowPrimary.copy(alpha = 0.4f),
                                                skin.glowSecondary.copy(alpha = 0.28f)
                                            )
                                        ),
                                        shape
                                    )
                                    .padding(10.dp),
                                color = Color.Transparent,
                                shape = shape,
                                tonalElevation = 0.dp,
                                shadowElevation = 0.dp
                            ) {
                                ScriptInterface(interfaceBuilder = remember {
                                    interfaceManager.buildInterface(
                                        EnumScriptInterface.FRIEND_FEED_CONTEXT_MENU,
                                        mapOf(
                                            "conversationId" to conversationId,
                                            "userId" to targetUser
                                        )
                                    )
                                } ?: return@Surface)
                            }
                        }
                    })
                })
            }
        }
    }
}
