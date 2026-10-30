package me.eternal.purrfect.ui.manager.pages.social.themes

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.RemoveRedEye
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavBackStackEntry
import kotlinx.coroutines.launch
import me.eternal.purrfect.R
import me.eternal.purrfect.common.data.SocialScope
import me.eternal.purrfect.ui.manager.pages.social.SocialRootSection
import me.eternal.purrfect.common.ui.theme.LocalPurrfectSkin

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SocialRootSection.AphelionSocialContent(nav: NavBackStackEntry) {
    SocialDataController()

    val titles = remember {
        listOf(translation["friends_tab"], translation["groups_tab"])
    }
    val coroutineScope = rememberCoroutineScope()
    val pagerState = rememberPagerState { titles.size }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var searchActive by rememberSaveable { mutableStateOf(false) }

    val normalizedQuery = remember(searchQuery) { searchQuery.trim() }
    val filteredFriends = remember(friendList, normalizedQuery) {
        if (normalizedQuery.isBlank()) {
            friendList
        } else {
            friendList.filter {
                it.mutableUsername.contains(normalizedQuery, ignoreCase = true) ||
                    it.displayName?.contains(normalizedQuery, ignoreCase = true) == true
            }
        }
    }
    val filteredGroups = remember(groupList, normalizedQuery) {
        if (normalizedQuery.isBlank()) {
            groupList
        } else {
            groupList.filter { it.name.contains(normalizedQuery, ignoreCase = true) }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LocalPurrfectSkin.current.backgroundGradient)
    ) {
        SocialHeader(
            titles = titles,
            pagerState = pagerState,
            onTabSelected = { index ->
                coroutineScope.launch { pagerState.animateScrollToPage(index) }
            },
            friendCount = friendList.size,
            groupCount = groupList.size,
            searchActive = searchActive,
            onSearchToggle = {
                searchActive = !searchActive
                if (!searchActive) searchQuery = ""
            }
        )
        if (searchActive) {
            val searchHint = context.translation["manager.dialogs.add_friend.search_hint"] ?: "Search"
            val searchShape = RoundedCornerShape(18.dp)
            val searchBorder = Brush.linearGradient(
                listOf(
                    LocalPurrfectSkin.current.glowPrimary.copy(alpha = 0.45f),
                    LocalPurrfectSkin.current.glowSecondary.copy(alpha = 0.35f)
                )
            )
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                shape = searchShape,
                color = LocalPurrfectSkin.current.textPrimary.copy(alpha = 0.05f),
                border = BorderStroke(1.dp, searchBorder),
                tonalElevation = 0.dp,
                shadowElevation = 0.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(LocalPurrfectSkin.current.cardOverlay, searchShape)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = searchHint,
                        tint = LocalPurrfectSkin.current.textSecondary
                    )
                    BasicTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = LocalPurrfectSkin.current.textPrimary,
                            fontSize = 15.sp
                        ),
                        cursorBrush = SolidColor(LocalPurrfectSkin.current.glowSecondary),
                        modifier = Modifier.weight(1f)
                    ) { innerTextField ->
                        if (searchQuery.isEmpty()) {
                            Text(
                                text = searchHint,
                                color = LocalPurrfectSkin.current.textSecondary,
                                fontSize = 14.sp
                            )
                        }
                        innerTextField()
                    }
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = translation["clear_search_button_description"] ?: "Clear",
                                tint = LocalPurrfectSkin.current.textPrimary
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        HorizontalPager(
            modifier = Modifier
                .fillMaxSize(),
            state = pagerState
        ) { page ->
            when (page) {
                0 -> ScopeList(SocialScope.FRIEND, filteredFriends, filteredGroups)
                1 -> ScopeList(SocialScope.GROUP, filteredFriends, filteredGroups)
            }
        }
    }
}
