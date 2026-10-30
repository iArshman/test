package me.eternal.purrfect.ui.manager.pages.social

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.RemoveRedEye
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavBackStackEntry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import me.eternal.purrfect.R
import me.eternal.purrfect.bridge.BridgeService
import me.eternal.purrfect.common.Constants
import me.eternal.purrfect.common.ui.rememberAsyncMutableState
import me.eternal.purrfect.common.ui.theme.LocalPurrfectSkin
import me.eternal.purrfect.common.util.snap.BitmojiSelfie
import me.eternal.purrfect.ui.manager.ManagerTheme
import me.eternal.purrfect.ui.manager.Routes
import me.eternal.purrfect.common.data.MessagingFriendInfo
import me.eternal.purrfect.common.data.MessagingGroupInfo
import me.eternal.purrfect.storage.*
import me.eternal.purrfect.common.data.SocialScope
import me.eternal.purrfect.ui.util.coil.BitmojiImage

class SocialRootSection : Routes.Route() {
    override val translation by lazy { context.translation.getCategory("manager.sections.social") }

    internal var friendList: List<MessagingFriendInfo> by mutableStateOf(emptyList())
    internal var groupList: List<MessagingGroupInfo> by mutableStateOf(emptyList())

    @Composable
    fun SocialDataController() {
        LaunchedEffect(Unit) {
            withContext(Dispatchers.IO) {
                val dbFriends = context.database.getFriends(descOrder = true)
                val dbGroups = context.database.getGroups()
                val sortedFriends = context.sortSocialFriends(dbFriends)
                withContext(Dispatchers.Main) {
                    friendList = sortedFriends
                    groupList = dbGroups
                }
            }

            context.database.messagingDataFlow.collect {
                withContext(Dispatchers.IO) {
                    val dbFriends = context.database.getFriends(descOrder = true)
                    val dbGroups = context.database.getGroups()
                    val sortedFriends = context.sortSocialFriends(dbFriends)
                    withContext(Dispatchers.Main) {
                        friendList = sortedFriends
                        groupList = dbGroups
                    }
                }
            }
        }
    }

    @Composable
    internal fun ScopeList(
        scope: SocialScope,
        friends: List<MessagingFriendInfo>,
        groups: List<MessagingGroupInfo>
    ) {
        val remainingHours = remember { context.config.root.streaksReminder.remainingHours.get() }
        val list = if (scope == SocialScope.GROUP) groups else friends

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 10.dp, end = 10.dp, bottom = routes.bottomPadding + 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (list.isEmpty()) {
                item { EmptyState(scope) }
            }
            items(list.size) { index ->
                val friend = if (scope == SocialScope.FRIEND) list[index] as MessagingFriendInfo else null
                val group = if (scope == SocialScope.GROUP) list[index] as MessagingGroupInfo else null
                val id = friend?.userId ?: group?.conversationId.orEmpty()

                SocialCard(
                    scope = scope,
                    friend = friend,
                    group = group,
                    onManage = {
                        routes.manageScope.navigate {
                            put("id", id)
                            put("scope", scope.key)
                        }
                    },
                    onPreview = {
                        routes.messagingPreview.navigate {
                            put("id", id)
                            put("scope", scope.key)
                        }
                    },
                    remainingHours = remainingHours
                )
            }
        }
    }

    @Composable
    private fun EmptyState(scope: SocialScope) {
        val skin = LocalPurrfectSkin.current
        val icon = if (scope == SocialScope.FRIEND) Icons.Default.People else Icons.Default.Groups
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(skin.textPrimary.copy(alpha = 0.05f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = skin.textPrimary.copy(alpha = 0.2f)
                )
            }
            Text(
                text = translation["friends_empty_title"] ?: "Nothing here yet",
                color = skin.textPrimary.copy(alpha = 0.6f),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = translation["social_empty_hint"] ?: "Use the + button to add entries manually or wait for the bridge to sync data.",
                color = skin.textPrimary.copy(alpha = 0.4f),
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 240.dp)
            )
        }
    }

    override val content: @Composable (NavBackStackEntry) -> Unit = { nav ->
        val themeId by produceState(initialValue = context.config.root.global.uiSettings.managerTheme.get()) {
            while (true) {
                delay(300)
                value = context.config.root.global.uiSettings.managerTheme.get()
            }
        }
        key(themeId) {
            with(ManagerTheme.fromId(themeId).theme) {
                this@SocialRootSection.SocialScreen(nav)
            }
        }
    }

    override val floatingActionButton: @Composable () -> Unit = {
        val skin = LocalPurrfectSkin.current
        var addFriendDialog by remember { mutableStateOf(null as AddFriendDialog?) }

        if (addFriendDialog != null) {
            addFriendDialog?.Content {
                addFriendDialog = null
            }
        }

        FloatingActionButton(
            onClick = {
                addFriendDialog = AddFriendDialog(
                    context,
                    AddFriendDialog.Actions(
                        onFriendState = { friend, state ->
                            if (state) {
                                context.bridgeService?.triggerScopeSync(
                                    SocialScope.FRIEND,
                                    friend.userId
                                )
                            } else {
                                context.database.deleteFriend(friend.userId)
                            }
                        },
                        onGroupState = { group, state ->
                            if (state) {
                                context.bridgeService?.triggerScopeSync(
                                    SocialScope.GROUP,
                                    group.conversationId
                                )
                            } else {
                                context.database.deleteGroup(group.conversationId)
                            }
                        },
                        getFriendState = { friend -> context.database.getFriendInfo(friend.userId) != null },
                        getGroupState = { group -> context.database.getGroupInfo(group.conversationId) != null }
                    )
                )
            },
            containerColor = skin.glowPrimary,
            contentColor = skin.cardOverlayColor,
            shape = CircleShape,
            elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp)
        ) {
            Icon(imageVector = Icons.Default.Add, contentDescription = null)
        }
    }

    @Composable
    internal fun SocialCard(
        scope: SocialScope,
        friend: MessagingFriendInfo?,
        group: MessagingGroupInfo?,
        onManage: () -> Unit,
        onPreview: () -> Unit,
        remainingHours: Int
    ) {
        val skin = LocalPurrfectSkin.current
        val title = friend?.displayName ?: group?.name ?: "Unknown"
        val subtitle = if (scope == SocialScope.FRIEND) {
            friend?.mutableUsername?.let { "@$it" } ?: friend?.userId
        } else {
            group?.participantsCount?.let { "$it participants" } ?: group?.conversationId
        }
        val icon = if (scope == SocialScope.FRIEND) Icons.Default.Person else Icons.Default.Group

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onManage),
            shape = RoundedCornerShape(22.dp),
            color = skin.cardOverlayColor.copy(alpha = 0.6f),
            border = BorderStroke(1.dp, skin.glassBorder.copy(alpha = 0.2f))
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                if (scope == SocialScope.FRIEND && friend != null) {
                    BitmojiImage(
                        context = context,
                        url = BitmojiSelfie.getBitmojiSelfie(
                            friend.selfieId,
                            friend.bitmojiId,
                            BitmojiSelfie.BitmojiSelfieType.NEW_THREE_D
                        )
                    )
                } else {
                    Surface(
                        modifier = Modifier.size(48.dp),
                        shape = CircleShape,
                        color = skin.textPrimary.copy(alpha = 0.1f)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                tint = skin.glowPrimary,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        color = skin.textPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = subtitle ?: "",
                        color = skin.textPrimary.copy(alpha = 0.6f),
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                OutlinedButton(
                    onClick = onPreview,
                    shape = RoundedCornerShape(50),
                    border = BorderStroke(1.dp, skin.glowPrimary.copy(alpha = 0.4f)),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                    modifier = Modifier.height(34.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.RemoveRedEye,
                            contentDescription = null,
                            tint = skin.glowPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "Preview",
                            color = skin.glowPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }

    @Composable
    internal fun SocialHeader(
        titles: List<String>,
        pagerState: androidx.compose.foundation.pager.PagerState,
        onTabSelected: (Int) -> Unit,
        friendCount: Int,
        groupCount: Int,
        searchActive: Boolean,
        onSearchToggle: () -> Unit
    ) {
        val skin = LocalPurrfectSkin.current
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp)
                .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()),
            shape = RoundedCornerShape(26.dp),
            color = skin.cardOverlayColor.copy(alpha = 0.6f),
            border = BorderStroke(1.dp, skin.glassBorder.copy(alpha = 0.2f))
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = translation["manager.routes.social"],
                        color = skin.textPrimary,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 18.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatPill(label = translation["friends_tab"], value = friendCount)
                        StatPill(label = translation["groups_tab"], value = groupCount)
                        IconButton(onClick = onSearchToggle) {
                            Icon(
                                imageVector = if (searchActive) Icons.Filled.Close else Icons.Filled.Search,
                                contentDescription = null,
                                tint = skin.textPrimary
                            )
                        }
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    titles.forEachIndexed { index, title ->
                        val selected = pagerState.currentPage == index
                        Surface(
                            modifier = Modifier.weight(1f).clickable { onTabSelected(index) },
                            shape = RoundedCornerShape(18.dp),
                            color = if (selected) skin.glowPrimary.copy(alpha = 0.12f) else skin.textPrimary.copy(alpha = 0.05f),
                            border = BorderStroke(1.dp, if (selected) skin.glowPrimary.copy(alpha = 0.5f) else skin.textPrimary.copy(alpha = 0.1f))
                        ) {
                            Text(
                                text = title,
                                modifier = Modifier.padding(vertical = 10.dp),
                                textAlign = TextAlign.Center,
                                color = if (selected) skin.glowPrimary else skin.textPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    internal fun StatPill(label: String, value: Int) {
        val skin = LocalPurrfectSkin.current
        Surface(
            shape = RoundedCornerShape(50),
            color = skin.textPrimary.copy(alpha = 0.08f),
            border = BorderStroke(1.dp, skin.textPrimary.copy(alpha = 0.12f))
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(text = value.toString(), color = skin.textPrimary, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
                Text(text = label, color = skin.textPrimary.copy(alpha = 0.6f), fontSize = 11.sp)
            }
        }
    }
}
