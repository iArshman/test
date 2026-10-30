package me.eternal.purrfect.ui.manager.pages.tracker.themes

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Rule
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavBackStackEntry
import me.eternal.purrfect.common.ui.rememberAsyncMutableState
import me.eternal.purrfect.common.ui.rememberAsyncMutableStateList
import me.eternal.purrfect.common.ui.rememberAsyncUpdateDispatcher
import me.eternal.purrfect.ui.manager.components.FloatingTopBar
import me.eternal.purrfect.ui.manager.pages.tracker.FriendTrackerManagerRoot
import me.eternal.purrfect.common.ui.theme.LocalPurrfectSkin
import me.eternal.purrfect.common.data.TrackerRule
import me.eternal.purrfect.storage.getTrackerRulesDesc
import me.eternal.purrfect.storage.getTrackerRule
import me.eternal.purrfect.storage.getTrackerEvents
import me.eternal.purrfect.storage.getRuleTrackerScopes
import me.eternal.purrfect.ui.util.Motion
import me.eternal.purrfect.ui.util.headerHeightTracker
import kotlinx.coroutines.launch

@Composable
fun FriendTrackerManagerRoot.AphelionFriendTrackerContent(nav: NavBackStackEntry) {
    val listState = rememberLazyListState()
    var controlsHeight by remember { mutableStateOf(100.dp) }
    val skin = LocalPurrfectSkin.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(skin.backgroundGradient)
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(top = controlsHeight)) {
            ConfigRulesTab()
        }

        FloatingTopBar(
            title = translation["manager.routes.home_tracker"] ?: "Friend Tracker",
            onBack = { routes.navController.popBackStack() },
            scrollOffset = if (listState.firstVisibleItemIndex > 0) Motion.HEADER_MORPH_THRESHOLD.toInt() else listState.firstVisibleItemScrollOffset,
            enableMorph = true,
            modifier = Modifier.headerHeightTracker { controlsHeight = it }
        )
    }
}

@Composable
internal fun FriendTrackerManagerRoot.ConfigRulesTab() {
    val skin = LocalPurrfectSkin.current
    val updateRules = rememberAsyncUpdateDispatcher()
    val rules = rememberAsyncMutableStateList<TrackerRule>(defaultValue = listOf(), updateDispatcher = updateRules) {
        context.database.getTrackerRulesDesc()
    }

    @Composable
    fun EmptyState(text: String) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 50.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = skin.textPrimary.copy(alpha = 0.08f),
                tonalElevation = 0.dp,
                shadowElevation = 0.dp,
                border = BorderStroke(1.dp, (if (skin.isDark) skin.textPrimary else Color.Black).copy(alpha = 0.12f))
            ) {
                Box(
                    modifier = Modifier
                        .size(62.dp)
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    skin.glowPrimary.copy(alpha = 0.32f),
                                    skin.glowSecondary.copy(alpha = 0.28f)
                                )
                            ),
                            CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.AutoGraph, contentDescription = text, tint = skin.textPrimary)
                }
            }
            Text(text, color = skin.textPrimary, fontWeight = FontWeight.ExtraBold)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = routes.bottomPadding + 100.dp)
    ) {
        item {
            if (rules.isEmpty()) {
                EmptyState(translation["no_rules_found"])
            }
        }
        items(rules, key = { it.id }) { rule ->
            val ruleName by rememberAsyncMutableState<String>(defaultValue = rule.name) {
                context.database.getTrackerRule(rule.id)?.name ?: translation["empty_rule_name"]
            }
            val eventCount by rememberAsyncMutableState<Int>(defaultValue = 0) {
                context.database.getTrackerEvents(rule.id).size
            }
            val scopeCount by rememberAsyncMutableState<Int>(defaultValue = 0) {
                context.database.getRuleTrackerScopes(rule.id).size
            }

            val ruleShape = RoundedCornerShape(20.dp)
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .clickable {
                        routes.editRule.navigate {
                            this["rule_id"] = rule.id.toString()
                        }
                    },
                shape = ruleShape,
                color = Color.Transparent,
                tonalElevation = 0.dp,
                shadowElevation = 8.dp,
                border = BorderStroke(
                    1.dp,
                    Brush.linearGradient(
                        listOf(
                            skin.glowPrimary.copy(alpha = 0.5f),
                            skin.glowSecondary.copy(alpha = 0.4f)
                        )
                    )
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(skin.cardOverlay, ruleShape)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = (if (skin.isDark) skin.textPrimary else Color.Black).copy(alpha = 0.08f),
                        border = BorderStroke(1.dp, (if (skin.isDark) skin.textPrimary else Color.Black).copy(alpha = 0.18f))
                    ) {
                        Box(
                            modifier = Modifier
                                .size(54.dp)
                                .background(
                                    Brush.linearGradient(
                                        listOf(
                                            skin.glowPrimary.copy(alpha = 0.35f),
                                            skin.glowSecondary.copy(alpha = 0.3f)
                                        )
                                    ),
                                    CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Rule, contentDescription = null, tint = skin.textPrimary)
                        }
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(ruleName, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, color = skin.textPrimary)
                        Text(
                            text = buildString {
                                append(eventCount)
                                append(" events")
                                if (scopeCount > 0) {
                                    append(" ΓÇó ")
                                    append(scopeCount)
                                    append(" scopes")
                                }
                            },
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = skin.textSecondary
                        )
                    }
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = skin.textPrimary.copy(alpha = 0.3f)
                    )
                }
            }
        }
    }
}
