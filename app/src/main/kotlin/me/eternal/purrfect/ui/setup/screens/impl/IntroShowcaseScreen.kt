package me.eternal.purrfect.ui.setup.screens.impl

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.eternal.purrfect.R
import me.eternal.purrfect.common.TargetApp
import me.eternal.purrfect.common.ui.theme.LocalPurrfectSkin
import me.eternal.purrfect.ui.setup.screens.SetupScreen
import me.eternal.purrfect.ui.util.scaleOnPress

class IntroShowcaseScreen(
    private val selectedAppsProvider: () -> Set<TargetApp> = { setOf(TargetApp.SNAPCHAT) },
    private val onSelectionChanged: (Set<TargetApp>) -> Unit = {}
) : SetupScreen() {
    override fun onLeave() {
        val preferredTarget = setupTargetOrder.firstOrNull { it in selectedAppsProvider() }
        preferredTarget?.let { context.setActiveTargetApp(it) }
    }

    @Composable
    override fun Content() {
        val skin = LocalPurrfectSkin.current
        var selectedKeys by rememberSaveable {
            mutableStateOf(selectedAppsProvider().toSetupTargetPrefsValue())
        }
        val selectedApps = remember(selectedKeys) { parseSetupTargetApps(selectedKeys) }
        val supportedApps = remember {
            listOf(
                SupportedAppShowcase(
                    targetApp = TargetApp.SNAPCHAT,
                    name = "Snapchat",
                    iconRes = R.drawable.setup_app_snapchat,
                    features = listOf(
                        "Save Snaps, Stories, and Spotlights",
                        "Unlock Snapchat+ for free",
                        "Bypass media upload tag and screenshot detection",
                        "Bulk messaging actions like bulk remove friends, chats, etc"
                    )
                ),
                SupportedAppShowcase(
                    targetApp = TargetApp.INSTAGRAM,
                    name = "Instagram",
                    iconRes = R.drawable.setup_app_instagram,
                    features = listOf(
                        "Download posts, reels, stories, and profile media",
                        "Keep unsent messages and add DM controls",
                        "Block ads, analytics, tracking links, and suggestions",
                        "Customize reels, feed, and hidden UI elements"
                    )
                ),
                SupportedAppShowcase(
                    targetApp = TargetApp.REDDIT,
                    name = "Reddit",
                    iconRes = R.drawable.setup_app_reddit,
                    features = listOf(
                        "Block promoted posts and comment ads",
                        "Unlock Reddit Premium for free",
                        "Open links in your preferred browser",
                        "Tidy up shelves, popups, and distractions"
                    )
                )
            )
        }

        LaunchedEffect(selectedKeys) {
            onSelectionChanged(selectedApps)
            allowNext(selectedApps.isNotEmpty())
        }

        fun toggleSelection(targetApp: TargetApp) {
            val updated = if (targetApp in selectedApps) {
                selectedApps - targetApp
            } else {
                selectedApps + targetApp
            }
            selectedKeys = updated.toSetupTargetPrefsValue()
        }

        SetupCard {
            StepTitle(
                title = "Welcome to Purrfect!",
                subtitle = "An all-in-one companion for your favorite social media apps.",
                modifier = Modifier.align(Alignment.CenterHorizontally),
                textAlign = TextAlign.Center
            )

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = skin.textPrimary.copy(alpha = 0.05f),
                border = BorderStroke(1.dp, skin.textPrimary.copy(alpha = 0.14f))
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "Current supported apps",
                        color = skin.textPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                    Text(
                        text = "Choose the apps you need. Tap an app to preview the features included, then tick one or both before continuing.",
                        color = skin.textSecondary,
                        fontWeight = FontWeight.Medium,
                        fontSize = 14.sp,
                        lineHeight = 18.sp
                    )
                }
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                supportedApps.forEach { app ->
                    SupportedAppCard(
                        app = app,
                        selected = app.targetApp in selectedApps,
                        onCheckedChange = { toggleSelection(app.targetApp) }
                    )
                }
            }
        }
    }
}

private data class SupportedAppShowcase(
    val targetApp: TargetApp,
    val name: String,
    val iconRes: Int,
    val features: List<String>
)

@Composable
private fun SupportedAppCard(
    app: SupportedAppShowcase,
    selected: Boolean,
    onCheckedChange: () -> Unit
) {
    val skin = LocalPurrfectSkin.current
    var expanded by rememberSaveable(app.targetApp.key) { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val background = if (selected) skin.textPrimary.copy(alpha = 0.08f) else skin.textPrimary.copy(alpha = 0.04f)
    val borderColor = if (selected) skin.textPrimary.copy(alpha = 0.38f) else skin.textPrimary.copy(alpha = 0.16f)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .scaleOnPress(interactionSource)
            .clip(RoundedCornerShape(22.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = null
            ) { expanded = !expanded },
        shape = RoundedCornerShape(22.dp),
        color = background,
        border = BorderStroke(1.dp, borderColor),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Column(
            modifier = Modifier
                .background(skin.cardOverlayColor.copy(alpha = 0.28f))
                .padding(horizontal = 14.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Image(
                    painter = painterResource(app.iconRes),
                    contentDescription = "${app.name} logo",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(14.dp))
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = app.name,
                        color = skin.textPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                }
                Checkbox(
                    checked = selected,
                    onCheckedChange = { onCheckedChange() },
                    colors = CheckboxDefaults.colors(
                        checkedColor = skin.glowSecondary,
                        uncheckedColor = skin.textPrimary.copy(alpha = 0.5f),
                        checkmarkColor = if (skin.isDark) Color.White else Color.Black
                    )
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = if (skin.isDark) Color(0xFF4ADE80) else Color(0xFF16A34A),
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "Available Features",
                        color = skin.textPrimary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                    tint = skin.textPrimary.copy(alpha = 0.86f)
                )
            }

            if (expanded) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    app.features.take(4).forEach { feature ->
                        FeatureBullet(feature)
                    }
                    FeatureBullet("& Many more!", emphasized = true)
                    Spacer(modifier = Modifier.height(2.dp))
                }
            }
        }
    }
}

@Composable
private fun FeatureBullet(
    text: String,
    emphasized: Boolean = false
) {
    val skin = LocalPurrfectSkin.current
    val contentColor = if (emphasized) {
        if (skin.isDark) Color.White else Color.Black
    } else skin.textPrimary

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Surface(
            modifier = Modifier
                .padding(top = 2.dp)
                .size(20.dp),
            shape = CircleShape,
            color = if (emphasized) {
                skin.glowPrimary.copy(alpha = 0.28f)
            } else {
                skin.textPrimary.copy(alpha = 0.08f)
            },
            border = BorderStroke(1.dp, skin.textPrimary.copy(alpha = 0.16f))
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = if (emphasized) contentColor else skin.textPrimary,
                    modifier = Modifier.size(13.dp)
                )
            }
        }
        Text(
            text = text,
            color = if (emphasized) (if (skin.isDark) Color.White else Color.Black) else skin.textPrimary,
            fontWeight = if (emphasized) FontWeight.Bold else FontWeight.Medium,
            fontSize = 14.sp,
            lineHeight = 18.sp
        )
    }
}
