package me.eternal.purrfect.ui.manager.pages.social

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.BackHandler
import kotlinx.coroutines.*
import me.eternal.purrfect.RemoteSideContext
import me.eternal.purrfect.common.data.MessagingFriendInfo
import me.eternal.purrfect.common.data.MessagingGroupInfo
import me.eternal.purrfect.common.data.MessagingRuleType
import me.eternal.purrfect.common.util.snap.BitmojiSelfie
import me.eternal.purrfect.storage.getFriends
import me.eternal.purrfect.storage.getGroups
import me.eternal.purrfect.storage.getRuleIds
import me.eternal.purrfect.common.ui.theme.PurrfectPalette
import me.eternal.purrfect.ui.util.coil.BitmojiImage
import me.eternal.purrfect.common.ui.theme.LocalPurrfectSkin
import androidx.compose.ui.platform.LocalContext

class AddFriendDialog(
    private val context: RemoteSideContext,
    private val actionHandler: Actions,
    private val pinnedIds: List<String>? = null
) {
    class Actions(
        val onFriendState: (friend: MessagingFriendInfo, state: Boolean) -> Unit,
        val onGroupState: (group: MessagingGroupInfo, state: Boolean) -> Unit,
        val getFriendState: (friend: MessagingFriendInfo) -> Boolean,
        val getGroupState: (group: MessagingGroupInfo) -> Boolean,
    )

    private val stateCache = mutableStateMapOf<String, Boolean>()
    private val translation by lazy { context.translation.getCategory("manager.dialogs.add_friend")}

    @Composable
    private fun ListCardEntry(
        id: String,
        bitmoji: String? = null,
        name: String,
        participantsCount: Int? = null,
        getCurrentState: () -> Boolean,
        onState: (Boolean) -> Unit = {},
    ) {
        val managerTheme = remember { context.config.root.global.uiSettings.managerTheme.get() }
        val skin = if (managerTheme == "APHELION") LocalPurrfectSkin.current else PurrfectPalette
        val cachedState = stateCache[id]
        LaunchedEffect(id, cachedState) {
            if (cachedState == null) {
                stateCache[id] = getCurrentState()
            }
        }
        val currentState = stateCache[id] ?: false
        val coroutineScope = rememberCoroutineScope()

        val cardShape = RoundedCornerShape(18.dp)
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp)
                .clickable {
                    val nextState = !currentState
                    stateCache[id] = nextState
                    coroutineScope.launch(Dispatchers.IO) { onState(nextState) }
                },
            shape = cardShape,
            color = Color.Transparent,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            border = BorderStroke(
                1.dp,
                if (currentState) Brush.linearGradient(listOf(skin.glowPrimary, skin.glowSecondary))
                else SolidColor(skin.textPrimary.copy(alpha = 0.08f))
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(skin.cardOverlay, cardShape)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BitmojiImage(
                    context = this@AddFriendDialog.context,
                    url = bitmoji,
                    modifier = Modifier
                        .padding(end = 2.dp),
                    size = 40,
                )

                Column(
                    modifier = Modifier
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = name,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = skin.textPrimary
                    )

                    participantsCount?.let {
                        Text(
                            text = translation.format("participants_text", "count" to it.toString()),
                            fontSize = 12.sp,
                            lineHeight = 12.sp,
                            color = skin.textSecondary
                        )
                    }
                }

                Switch(
                    checked = currentState,
                    onCheckedChange = {
                        stateCache[id] = it
                        coroutineScope.launch(Dispatchers.IO) { onState(it) }
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = skin.textPrimary,
                        checkedTrackColor = skin.glowPrimary.copy(alpha = 0.6f),
                        uncheckedThumbColor = skin.textPrimary.copy(alpha = 0.8f),
                        uncheckedTrackColor = skin.textPrimary.copy(alpha = 0.2f)
                    )
                )
            }
        }
    }

    @Composable
    private fun DialogHeader(searchKeyword: MutableState<String>) {
        val skin = LocalPurrfectSkin.current
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    brush = Brush.linearGradient(
                        listOf(
                            skin.glowPrimary.copy(alpha = 0.4f),
                            skin.glowSecondary.copy(alpha = 0.35f)
                        )
                    ),
                    shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
                )
                .padding(horizontal = 16.dp, vertical = 18.dp)
        ) {
            Text(
                text = translation["title"] ?: "Add Participants",
                fontSize = 22.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White
            )
        }

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            shape = RoundedCornerShape(18.dp),
            color = skin.cardOverlayColor,
            border = BorderStroke(1.dp, skin.textPrimary.copy(alpha = 0.12f)),
            tonalElevation = 0.dp,
            shadowElevation = 0.dp
        ) {
            TextField(
                value = searchKeyword.value,
                onValueChange = { searchKeyword.value = it },
                placeholder = {
                    Text(text = translation["search_hint"] ?: "Search", color = skin.textSecondary)
                },
                modifier = Modifier
                    .fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Done),
                leadingIcon = {
                    Icon(Icons.Filled.Search, contentDescription = translation["search_icon_description"], tint = skin.textPrimary)
                },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = skin.cardOverlayColor.copy(alpha = 0.95f),
                    unfocusedContainerColor = skin.textPrimary.copy(alpha = 0.05f),
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    cursorColor = skin.glowSecondary,
                    focusedTextColor = skin.textPrimary,
                    unfocusedTextColor = skin.textPrimary,
                    focusedLeadingIconColor = skin.glowSecondary,
                    unfocusedLeadingIconColor = skin.textPrimary.copy(alpha = 0.6f),
                    focusedPlaceholderColor = skin.textSecondary,
                    unfocusedPlaceholderColor = skin.textSecondary
                ),
                textStyle = LocalTextStyle.current.copy(color = skin.textPrimary)
            )
        }
    }


    @Composable
    fun Content(dismiss: () -> Unit = { }) {
        val skin = LocalPurrfectSkin.current
        var cachedFriends by remember { mutableStateOf(null as List<MessagingFriendInfo>?) }
        var cachedGroups by remember { mutableStateOf(null as List<MessagingGroupInfo>?) }

        val coroutineScope = rememberCoroutineScope()

        var timeoutJob: Job? = null
        var hasFetchError by remember { mutableStateOf(false) }

        LaunchedEffect(Unit) {
            fun applySnapshot(
                friends: List<MessagingFriendInfo>,
                groups: List<MessagingGroupInfo>
            ) {
                cachedFriends = context.sortSocialFriends(friends, pinnedIds = pinnedIds)
                cachedGroups = groups.run {
                    if (pinnedIds != null) {
                        sortedBy { -pinnedIds.indexOf(it.conversationId) }
                    } else {
                        // Priority sort for whitelisted groups
                        val whitelistedIds = context.database.getRuleIds(MessagingRuleType.STEALTH.key).toSet()
                        sortedWith { a, b ->
                            val aSelected = whitelistedIds.contains(a.conversationId)
                            val bSelected = whitelistedIds.contains(b.conversationId)
                            if (aSelected != bSelected) if (aSelected) -1 else 1
                            else a.name.compareTo(b.name, ignoreCase = true)
                        }
                    }
                }
                if (friends.isNotEmpty() || groups.isNotEmpty()) {
                    timeoutJob?.cancel()
                    hasFetchError = false
                }
            }

            // Initial database load
            withContext(Dispatchers.IO) {
                applySnapshot(
                    context.database.getFriends(descOrder = true),
                    context.database.getGroups()
                )
            }

            // Real-time synchronization flow
            context.requestSocialSnapshotRefresh()
            coroutineScope.launch {
                context.database.messagingDataFlow.collect { (friends, groups) ->
                    applySnapshot(friends, groups)
                }
            }

            timeoutJob = coroutineScope.launch {
                withContext(Dispatchers.IO) {
                    delay(25000)
                    if ((cachedFriends?.isNullOrEmpty() != false) && (cachedGroups?.isNullOrEmpty() != false)) {
                        hasFetchError = true
                    }
                }
            }
        }
        DisposableEffect(Unit) {
            onDispose {
                timeoutJob?.cancel()
                context.bridgeService?.clearEphemeralSocialSnapshotRequest()
            }
        }

        me.eternal.purrfect.ui.util.Dialog(
            onDismissRequest = {
                timeoutJob?.cancel()
                dismiss()
            },
            properties = me.eternal.purrfect.ui.util.DialogProperties(usePlatformDefaultWidth = false)
        ) {
            val dialogShape = RoundedCornerShape(24.dp)
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                shape = dialogShape,
                color = skin.cardOverlayColor,
                tonalElevation = 0.dp,
                shadowElevation = 12.dp,
                border = BorderStroke(1.dp, skin.textPrimary.copy(alpha = 0.12f))
            ) {
                Column {
                    if (cachedGroups == null || cachedFriends == null) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 18.dp, vertical = 22.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            if (hasFetchError) {
                                Text(
                                    text = translation["fetch_error"] ?: "Fetch Error",
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(bottom = 10.dp, top = 10.dp),
                                    color = skin.textPrimary
                                )
                            } else {
                                CircularProgressIndicator(
                                    modifier = Modifier
                                        .size(32.dp),
                                    strokeWidth = 3.dp,
                                    color = skin.glowSecondary
                                )
                            }
                        }
                        return@Surface
                    }

                    val searchKeyword = remember { mutableStateOf("") }

                    BackHandler(enabled = searchKeyword.value.isNotEmpty()) {
                        searchKeyword.value = ""
                    }

                    val filteredGroups = cachedGroups!!.takeIf { searchKeyword.value.isNotBlank() }?.filter {
                        it.name.contains(searchKeyword.value, ignoreCase = true)
                    } ?: cachedGroups!!

                    val filteredFriends = cachedFriends!!.takeIf { searchKeyword.value.isNotBlank() }?.filter {
                        it.mutableUsername.contains(searchKeyword.value, ignoreCase = true) ||
                        it.displayName?.contains(searchKeyword.value, ignoreCase = true) == true
                    } ?: cachedFriends!!

                    val selectedFriendCount by remember(filteredFriends) {
                        derivedStateOf {
                            filteredFriends.count { friend ->
                                stateCache[friend.userId] ?: actionHandler.getFriendState(friend)
                            }
                        }
                    }
                    val hasFriendsSelected = selectedFriendCount > 0
                    val allFriendsSelected = filteredFriends.isNotEmpty() && selectedFriendCount == filteredFriends.size

                    val selectedGroupCount by remember(filteredGroups) {
                        derivedStateOf {
                            filteredGroups.count { group ->
                                stateCache[group.conversationId] ?: actionHandler.getGroupState(group)
                            }
                        }
                    }
                    val hasGroupsSelected = selectedGroupCount > 0
                    val allGroupsSelected = filteredGroups.isNotEmpty() && selectedGroupCount == filteredGroups.size

                    DialogHeader(searchKeyword)

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                    ) {
                        item {
                            if (filteredGroups.isNotEmpty()) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 8.dp, top = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = translation["category_groups"] ?: "Groups",
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = skin.textPrimary
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        TextButton(
                                            onClick = {
                                                coroutineScope.launch(Dispatchers.IO) {
                                                    filteredGroups.forEach { group ->
                                                        stateCache[group.conversationId] = true
                                                        actionHandler.onGroupState(group, true)
                                                    }
                                                }
                                            },
                                            enabled = !allGroupsSelected
                                        ) {
                                            Text(
                                                text = context.translation["manager.dialogs.messaging_action.select_all_button"] ?: "Select All",
                                                color = if (allGroupsSelected) skin.textPrimary.copy(alpha = 0.45f) else skin.glowSecondary
                                            )
                                        }
                                        TextButton(
                                            onClick = {
                                                coroutineScope.launch(Dispatchers.IO) {
                                                    filteredGroups.forEach { group ->
                                                        stateCache[group.conversationId] = false
                                                        actionHandler.onGroupState(group, false)
                                                    }
                                                }
                                            },
                                            enabled = hasGroupsSelected
                                        ) {
                                            Text(
                                                text = translation["unselect_all_button"] ?: "Unselect All",
                                                color = if (hasGroupsSelected) skin.glowPrimary else skin.textPrimary.copy(alpha = 0.45f)
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        items(filteredGroups.size) {
                            val group = filteredGroups[it]
                            ListCardEntry(
                                id = group.conversationId,
                                name = group.name,
                                participantsCount = group.participantsCount,
                                getCurrentState = { actionHandler.getGroupState(group) }
                            ) { state ->
                                actionHandler.onGroupState(group, state)
                            }
                        }

                        item {
                            if (filteredFriends.isNotEmpty()) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 8.dp, top = 14.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = translation["category_friends"] ?: "Friends",
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = skin.textPrimary
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        TextButton(
                                            onClick = {
                                                coroutineScope.launch(Dispatchers.IO) {
                                                    filteredFriends.forEach { friend ->
                                                        stateCache[friend.userId] = true
                                                        actionHandler.onFriendState(friend, true)
                                                    }
                                                }
                                            },
                                            enabled = !allFriendsSelected
                                        ) {
                                            Text(
                                                text = context.translation["manager.dialogs.messaging_action.select_all_button"] ?: "Select All",
                                                color = if (allFriendsSelected) skin.textPrimary.copy(alpha = 0.45f) else skin.glowSecondary
                                            )
                                        }
                                        TextButton(
                                            onClick = {
                                                coroutineScope.launch(Dispatchers.IO) {
                                                    filteredFriends.forEach { friend ->
                                                        stateCache[friend.userId] = false
                                                        actionHandler.onFriendState(friend, false)
                                                    }
                                                }
                                            },
                                            enabled = hasFriendsSelected
                                        ) {
                                            Text(
                                                text = translation["unselect_all_button"] ?: "Unselect All",
                                                color = if (hasFriendsSelected) skin.glowPrimary else skin.textPrimary.copy(alpha = 0.45f)
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        items(filteredFriends.size) { index ->
                            val friend = filteredFriends[index]

                            ListCardEntry(
                                id = friend.userId,
                                bitmoji = friend.takeIf { it.bitmojiId != null }?.let {
                                    BitmojiSelfie.getBitmojiSelfie(it.selfieId, it.bitmojiId, BitmojiSelfie.BitmojiSelfieType.NEW_THREE_D)
                                },
                                name = friend.displayName?.takeIf { name -> name.isNotBlank() } ?: friend.mutableUsername,
                                getCurrentState = { actionHandler.getFriendState(friend) }
                            ) { state ->
                                actionHandler.onFriendState(friend, state)
                            }
                        }
                    }
                }
            }
        }
    }
}
