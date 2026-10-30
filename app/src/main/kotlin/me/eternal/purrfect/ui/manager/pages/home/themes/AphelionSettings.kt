package me.eternal.purrfect.ui.manager.pages.home.themes

import android.content.SharedPreferences
import android.content.Intent
import android.net.Uri
import android.app.Activity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.view.drawToBitmap
import androidx.navigation.NavBackStackEntry
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.eternal.purrfect.R
import me.eternal.purrfect.common.TargetApp
import me.eternal.purrfect.common.action.EnumAction
import me.eternal.purrfect.common.bridge.InternalFileHandleType
import me.eternal.purrfect.common.bridge.wrapper.LoggerConversationExportTarget
import me.eternal.purrfect.common.bridge.wrapper.LoggedMessage
import me.eternal.purrfect.common.data.ContentType
import me.eternal.purrfect.common.ui.rememberAsyncMutableState
import me.eternal.purrfect.common.ui.theme.LocalPurrfectSkin
import me.eternal.purrfect.common.ui.theme.PurrfectPalette
import me.eternal.purrfect.common.util.protobuf.ProtoReader
import me.eternal.purrfect.core.features.impl.downloader.decoder.DecodedAttachment
import me.eternal.purrfect.core.features.impl.downloader.decoder.MessageDecoder
import me.eternal.purrfect.core.wrapper.impl.getMessageText
import me.eternal.purrfect.storage.findFriend
import me.eternal.purrfect.storage.getAllScopeNotes
import me.eternal.purrfect.storage.getFriendInfo
import me.eternal.purrfect.storage.getGroupInfo
import me.eternal.purrfect.storage.setAllScopeNotes
import me.eternal.purrfect.ui.manager.Routes
import me.eternal.purrfect.ui.manager.components.AestheticDialog
import me.eternal.purrfect.ui.manager.components.FloatingTopBar
import me.eternal.purrfect.ui.manager.pages.home.HomeSettings
import me.eternal.purrfect.ui.manager.theme.aphelion.AphelionHaptics
import me.eternal.purrfect.ui.util.headerHeightTracker
import me.eternal.purrfect.ui.util.Motion
import me.eternal.purrfect.ui.setup.Requirements
import me.eternal.purrfect.ui.setup.SetupPreferences
import me.eternal.purrfect.ui.util.purrfectSwitchColors
import me.eternal.purrfect.ui.util.saveFile
import me.eternal.purrfect.ui.util.openFile
import java.io.File
import java.net.URLEncoder
import java.text.DateFormat
import java.util.Date
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.tween
import me.eternal.purrfect.ui.manager.pages.home.themes.components.AphelionSkinPicker

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun HomeSettings.AphelionSettingsContent(nav: NavBackStackEntry) {
    val managerTheme = context.config.root.global.uiSettings.managerTheme.get()
    val activeSkin = LocalPurrfectSkin.current
    val skin = remember(managerTheme, activeSkin) { if (managerTheme == "APHELION") activeSkin else PurrfectPalette }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val hapticFeedback = LocalHapticFeedback.current
    val view = LocalView.current
    var switchCenter by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    var controlsHeight by remember { mutableStateOf(100.dp) }
    var showResetSetupDialog by remember { mutableStateOf(false) }

    val sharedButtonColors = ButtonDefaults.buttonColors(
        containerColor = skin.textPrimary.copy(alpha = 0.07f),
        contentColor = skin.textPrimary
    )
    val sharedOutlinedColors = ButtonDefaults.outlinedButtonColors(contentColor = skin.textPrimary)

    val computedScrollOffset by remember {
        derivedStateOf {
            if (listState.firstVisibleItemIndex > 0) Motion.HEADER_MORPH_THRESHOLD.toInt()
            else listState.firstVisibleItemScrollOffset
        }
    }

    LaunchedEffect(computedScrollOffset) {
        routes.navigation?.globalScrollOffset = computedScrollOffset
    }

    if (context.isLimitedTargetMode) {
        this@AphelionSettingsContent.AphelionLimitedTargetSettingsScreen()
        return
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(skin.backgroundGradient)
    ) {
        if (showResetSetupDialog) {
            AestheticDialog(
                onDismissRequest = { showResetSetupDialog = false },
                title = translation["reset_setup_dialog_title"],
                text = translation["reset_setup_dialog_text"],
                icon = Icons.Filled.Warning,
                confirmButtonText = context.translation["button.positive"],
                dismissButtonText = context.translation["button.negative"],
                onConfirm = {
                    showResetSetupDialog = false
                    context.sharedPreferences.edit()
                        .remove("setup_in_progress")
                        .remove("setup_current_route")
                        .remove("setup_skip_patch")
                        .remove("setup_install_mode")
                        .remove("setup_selected_apps")
                        .apply()
                    SetupPreferences.clearSetupChoices(context.sharedPreferences)
                    context.config.reset()
                    context.config.writeConfig()
                    val intent = Intent(context.androidContext, me.eternal.purrfect.ui.setup.SetupActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    context.androidContext.startActivity(intent)
                    routes.navController.popBackStack()
                },
                onDismiss = { showResetSetupDialog = false },
                showCloseButton = false
            )
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = routes.bottomPadding + 24.dp)
        ) {
            item {
                Spacer(Modifier.height(controlsHeight))
            }

            item {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    GlassCard {
                        RowTitle(title = translation["target_app_title"] ?: "Target App")
                        AphelionTargetAppSwitchRow()
                    }

                    // THEME SWITCHER
                    GlassCard {
                        RowTitle(title = translation["ui_theme_title"] ?: "UI Theme")
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().heightIn(min = 55.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = translation["settings_ui_theme"] ?: "Aphelion Theme Skins",
                                    fontSize = 14.sp,
                                    color = skin.textPrimary
                                )

                                val currentThemeId = context.config.root.global.uiSettings.managerTheme.get()
                                var localThemeId by remember { mutableStateOf(currentThemeId) }

                                Switch(
                                    checked = localThemeId == "APHELION",
                                    onCheckedChange = { isAphelion ->
                                        val newId = if (isAphelion) "APHELION" else "LEGACY"
                                        localThemeId = newId 
                                        
                                        AphelionHaptics.themeRevealTick(context, hapticFeedback)
                                        scope.launch {
                                            val bitmap = me.eternal.purrfect.ui.manager.theme.aphelion.captureHardwareBitmap((view.context as Activity).window)

                                            routes.navigation?.themeRevealState?.requestReveal(
                                                newThemeId = newId,
                                                originCenter = switchCenter,
                                                bitmap = bitmap
                                            )

                                            kotlinx.coroutines.delay(50)
                                            context.config.root.global.uiSettings.managerTheme.set(newId)
                                            context.syncSkinSettings()
                                            withContext(Dispatchers.IO) {
                                                context.config.writeConfig()
                                            }
                                        }
                                        },                                    modifier = Modifier
                                        .onGloballyPositioned { coords ->
                                            val rootPos = coords.positionInRoot()
                                            switchCenter = androidx.compose.ui.geometry.Offset(
                                                x = rootPos.x + coords.size.width / 2f,
                                                y = rootPos.y + coords.size.height / 2f
                                            )
                                        },
                                    colors = purrfectSwitchColors()
                                )
                            }
                        }
                    }

                    // SKIN PICKER
                    val skin = LocalPurrfectSkin.current
                    var currentSkinId by remember {
                        mutableStateOf(context.config.root.global.uiSettings.aphelionSkin.get())
                    }
                    val currentThemeId_internal = context.config.root.global.uiSettings.managerTheme.get()
                    var localThemeId_internal by remember { mutableStateOf(currentThemeId_internal) }

                    AnimatedVisibility(
                        visible = localThemeId_internal == "APHELION",
                        enter = fadeIn(tween(300)),
                        exit = fadeOut(tween(200))
                    ) {
                        GlassCard {
                            RowTitle(title = "Aphelion Skins")
                            Text(
                                text = "Choose a tailored visual identity, refined for depth, clarity, and premium accents.",
                                fontSize = 12.sp,
                                color = skin.textSecondary,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                                lineHeight = 16.sp
                            )
                            Spacer(Modifier.height(10.dp))
                            AphelionSkinPicker(
                                currentSkinId = currentSkinId,
                                onSkinSelected = { newSkinId ->
                                    currentSkinId = newSkinId
                                    context.config.root.global.uiSettings.aphelionSkin.set(newSkinId)
                                    context.syncSkinSettings()
                                    context.config.writeConfig()
                                    AphelionHaptics.themeRevealTick(context, hapticFeedback)
                                }
                            )

                            // LUMINA CUSTOMIZATION
                            AnimatedVisibility(
                                visible = currentSkinId == "LUMINA",
                                enter = expandVertically() + fadeIn(),
                                exit = shrinkVertically() + fadeOut()
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 12.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    HorizontalDivider(
                                        modifier = Modifier.padding(horizontal = 14.dp),
                                        color = skin.textPrimary.copy(alpha = 0.08f)
                                    )

                                    // Mode Switcher
                                    val currentMode = context.config.root.global.uiSettings.luminaMode.get()
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 14.dp),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        listOf("AUTO", "LIGHT", "DARK").forEach { mode ->
                                            val isSelected = currentMode == mode
                                            Surface(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .height(38.dp)
                                                    .clickable {
                                                        context.config.root.global.uiSettings.luminaMode.set(mode)
                                                        context.syncSkinSettings()
                                                        context.config.writeConfig()
                                                        AphelionHaptics.themeRevealTick(context, hapticFeedback)
                                                    },
                                                shape = RoundedCornerShape(10.dp),
                                                color = if (isSelected) skin.glowPrimary.copy(alpha = 0.25f) else skin.textPrimary.copy(alpha = 0.05f),
                                                border = if (isSelected) BorderStroke(1.dp, if (skin.isDark) skin.glowPrimary else androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.6f)) else null
                                            ) {
                                                Box(contentAlignment = Alignment.Center) {
                                                    Text(
                                                        text = mode,
                                                        fontSize = 11.sp,
                                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                                        color = if (isSelected) (if (skin.isDark) skin.glowPrimary else androidx.compose.ui.graphics.Color.Black) else skin.textPrimary.copy(alpha = 0.7f)
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    // Accent Ribbon
                                    val currentAccent = context.config.root.global.uiSettings.luminaAccent.get()
                                    val accents = remember(skin.isDark) { 
                                        me.eternal.purrfect.common.ui.theme.Catppuccin.mocha.accents
                                            .filter { it.first != "Espresso" && it.first != "Forest" }
                                            .filter { (name, _) -> if (skin.isDark) name != "Black" else name != "White" }
                                    }

                                    Column(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalArrangement = Arrangement.spacedBy(14.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .horizontalScroll(rememberScrollState())
                                                .padding(horizontal = 14.dp),
                                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            accents.forEach { (name, color) ->
                                                val isSelected = if (currentSkinId == "LUMINA") {
                                                    context.config.root.global.uiSettings.luminaAccent.get().equals(name, ignoreCase = true)
                                                } else {
                                                    context.config.root.global.uiSettings.aetherAccent.get().equals(name, ignoreCase = true)
                                                }
                                                Box(
                                                    modifier = Modifier
                                                        .size(34.dp)
                                                        .clip(androidx.compose.foundation.shape.CircleShape)
                                                        .background(color)
                                                        .border(
                                                            width = if (isSelected) 2.dp else 1.dp,
                                                            color = if (isSelected) skin.textPrimary else skin.textPrimary.copy(alpha = 0.1f),
                                                            shape = androidx.compose.foundation.shape.CircleShape
                                                        )
                                                        .clickable {
                                                            if (currentSkinId == "LUMINA") {
                                                                context.config.root.global.uiSettings.luminaAccent.set(name)
                                                            } else {
                                                                context.config.root.global.uiSettings.aetherAccent.set(name)
                                                            }
                                                            context.syncSkinSettings()
                                                            context.config.writeConfig()
                                                            AphelionHaptics.themeRevealTick(context, hapticFeedback)
                                                        },
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    if (isSelected) {
                                                        Icon(
                                                            imageVector = Icons.Default.Check,
                                                            contentDescription = null,
                                                            tint = if (color.luminance() > 0.5f) skin.cardOverlayColor else Color.White,
                                                            modifier = Modifier.size(18.dp)
                                                        )
                                                    }
                                                }
                                            }
                                        }

                                        // Centered Dynamic Accent Label
                                        val activeAccentName = if (currentSkinId == "LUMINA") {
                                            context.config.root.global.uiSettings.luminaAccent.get()
                                        } else {
                                            context.config.root.global.uiSettings.aetherAccent.get()
                                        }
                                        Text(
                                            text = "Accent: $activeAccentName",
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (skin.isDark) skin.glowPrimary.copy(alpha = 0.85f) else skin.textPrimary,
                                            textAlign = TextAlign.Center,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }                                }
                            }

                            // AETHER CUSTOMIZATION
                            AnimatedVisibility(
                                visible = currentSkinId == "AETHER",
                                enter = expandVertically() + fadeIn(),
                                exit = shrinkVertically() + fadeOut()
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 12.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    HorizontalDivider(
                                        modifier = Modifier.padding(horizontal = 14.dp),
                                        color = skin.textPrimary.copy(alpha = 0.08f)
                                    )

                                    // Mode Switcher
                                    val currentMode = context.config.root.global.uiSettings.aetherMode.get()
                                    val isAmoled = context.config.root.global.uiSettings.aetherAmoled.get()

                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 14.dp),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        listOf("AUTO", "LIGHT", "DARK").forEach { mode ->
                                            val isSelected = currentMode == mode
                                            Surface(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .height(38.dp)
                                                    .clickable {
                                                        context.config.root.global.uiSettings.aetherMode.set(mode)
                                                        context.syncSkinSettings()
                                                        context.config.writeConfig()
                                                        AphelionHaptics.themeRevealTick(context, hapticFeedback)
                                                    },
                                                shape = RoundedCornerShape(10.dp),
                                                color = if (isSelected) skin.glowPrimary.copy(alpha = 0.25f) else skin.textPrimary.copy(alpha = 0.05f),
                                                border = if (isSelected) BorderStroke(1.dp, if (skin.isDark) skin.glowPrimary else androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.6f)) else null
                                            ) {
                                                Box(contentAlignment = Alignment.Center) {
                                                    Text(
                                                        text = mode,
                                                        fontSize = 11.sp,
                                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                                        color = if (isSelected) (if (skin.isDark) skin.glowPrimary else androidx.compose.ui.graphics.Color.Black) else skin.textPrimary.copy(alpha = 0.7f)
                                                    )
                                                }
                                            }
                                        }

                                        // AMOLED Toggle
                                        if (currentMode != "LIGHT") {
                                            Surface(
                                                modifier = Modifier
                                                    .size(38.dp)
                                                    .clickable {
                                                        context.config.root.global.uiSettings.aetherAmoled.set(!isAmoled)
                                                        context.syncSkinSettings()
                                                        context.config.writeConfig()
                                                        AphelionHaptics.themeRevealTick(context, hapticFeedback)
                                                    },
                                                shape = RoundedCornerShape(10.dp),
                                                color = if (isAmoled) Color.Black else skin.textPrimary.copy(alpha = 0.05f),
                                                border = if (isAmoled) BorderStroke(1.dp, skin.glowPrimary) else null
                                            ) {
                                                Box(contentAlignment = Alignment.Center) {
                                                    Icon(
                                                        imageVector = if (isAmoled) Icons.Default.BrightnessLow else Icons.Default.BrightnessHigh,
                                                        contentDescription = null,
                                                        modifier = Modifier.size(18.dp),
                                                        tint = if (isAmoled) skin.glowPrimary else skin.textPrimary.copy(alpha = 0.5f)
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    // Accent Ribbon (Aether Specific)
                                    val currentAccent = context.config.root.global.uiSettings.aetherAccent.get()
                                    val accents = remember(skin.isDark) { 
                                        me.eternal.purrfect.common.ui.theme.Catppuccin.mocha.accents
                                            .filter { it.first != "Espresso" && it.first != "Forest" }
                                            .filter { (name, _) -> if (skin.isDark) name != "Black" else name != "White" }
                                    }

                                    Column(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalArrangement = Arrangement.spacedBy(14.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .horizontalScroll(rememberScrollState())
                                                .padding(horizontal = 14.dp),
                                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            accents.forEach { (name, color) ->
                                                val isSelected = currentAccent.equals(name, ignoreCase = true)
                                                Box(
                                                    modifier = Modifier
                                                        .size(34.dp)
                                                        .clip(androidx.compose.foundation.shape.CircleShape)
                                                        .background(color)
                                                        .border(
                                                            width = if (isSelected) 2.dp else 1.dp,
                                                            color = if (isSelected) skin.textPrimary else skin.textPrimary.copy(alpha = 0.1f),
                                                            shape = androidx.compose.foundation.shape.CircleShape
                                                        )
                                                        .clickable {
                                                            context.config.root.global.uiSettings.aetherAccent.set(name)
                                                            context.syncSkinSettings()
                                                            context.config.writeConfig()
                                                            AphelionHaptics.themeRevealTick(context, hapticFeedback)
                                                        },
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    if (isSelected) {
                                                        Icon(
                                                            imageVector = Icons.Default.Check,
                                                            contentDescription = null,
                                                            tint = if (color.luminance() > 0.5f) skin.cardOverlayColor else Color.White,
                                                            modifier = Modifier.size(18.dp)
                                                        )
                                                    }
                                                }
                                            }
                                        }

                                        // Centered Dynamic Accent Label
                                        Text(
                                            text = "Accent: $currentAccent",
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (skin.isDark) skin.glowPrimary.copy(alpha = 0.85f) else skin.textPrimary,
                                            textAlign = TextAlign.Center,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }                                }
                            }

                            // CYBER CUSTOMIZATION
                            AnimatedVisibility(
                                visible = currentSkinId == "CYBER",
                                enter = expandVertically() + fadeIn(),
                                exit = shrinkVertically() + fadeOut()
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 12.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    HorizontalDivider(
                                        modifier = Modifier.padding(horizontal = 14.dp),
                                        color = skin.textPrimary.copy(alpha = 0.08f)
                                    )

                                    // Style Switcher
                                    val currentStyle = context.config.root.global.uiSettings.cyberwareStyle.get()

                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 14.dp),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        listOf("SYNTHWAVE", "NIGHTCITY").forEach { style ->
                                            val isSelected = currentStyle == style
                                            Surface(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .height(38.dp)
                                                    .clickable {
                                                        context.config.root.global.uiSettings.cyberwareStyle.set(style)
                                                        context.syncSkinSettings()
                                                        context.config.writeConfig()
                                                        AphelionHaptics.themeRevealTick(context, hapticFeedback)
                                                    },
                                                shape = RoundedCornerShape(10.dp),
                                                color = if (isSelected) skin.glowPrimary.copy(alpha = 0.25f) else skin.textPrimary.copy(alpha = 0.05f),
                                                border = if (isSelected) BorderStroke(1.dp, skin.glowPrimary) else null
                                            ) {
                                                Box(contentAlignment = Alignment.Center) {
                                                    Text(
                                                        text = if (style == "SYNTHWAVE") "Synthwave" else "Night City",
                                                        fontSize = 11.sp,
                                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                                        color = if (isSelected) skin.glowPrimary else skin.textPrimary.copy(alpha = 0.7f)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // ACTIONS
                    GlassCard {
                        RowTitle(title = translation["actions_title"])
                        Column(modifier = Modifier.padding(horizontal = 6.dp)) {
                            EnumAction.entries.forEach { enumAction -> RowAction(key = enumAction.key) { context.launchActionIntent(enumAction) } }
                            RowAction(key = "regen_mappings") { context.checkForRequirements(Requirements.MAPPINGS) }
                            RowAction(key = "change_language") { context.checkForRequirements(Requirements.LANGUAGE) }
                        }
                    }

                    // UI SETTINGS
                    GlassCard {
                        RowTitle(title = translation["ui_settings_title"])
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(modifier = Modifier.fillMaxWidth().heightIn(min = 55.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(text = translation["haptic_feedback_label"], fontSize = 14.sp, color = skin.textPrimary)
                                var hapticEnabled by remember { mutableStateOf(context.config.root.global.uiSettings.hapticFeedback.getNullable() ?: true) }
                                Switch(checked = hapticEnabled, onCheckedChange = { if (it) hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress); hapticEnabled = it; context.config.root.global.uiSettings.hapticFeedback.set(it); context.config.writeConfig() }, colors = purrfectSwitchColors())
                            }
                            Row(modifier = Modifier.fillMaxWidth().heightIn(min = 55.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(text = translation["use_system_toasts_label"], fontSize = 14.sp, color = skin.textPrimary)
                                var useSystemToasts by remember { mutableStateOf(context.config.root.global.uiSettings.useSystemToasts.getNullable() ?: false) }
                                Switch(checked = useSystemToasts, onCheckedChange = { if (context.config.root.global.uiSettings.hapticFeedback.get()) hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress); useSystemToasts = it; context.config.root.global.uiSettings.useSystemToasts.set(it); context.config.writeConfig() }, colors = purrfectSwitchColors())
                            }
                        }
                    }

                    // UPDATES
                    GlassCard {
                        RowTitle(title = translation["updates_title"])
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            var autoUpdateCheck by remember { mutableStateOf(context.config.root.global.updateSettings.autoUpdateCheck.getNullable() ?: true) }

                            Row(modifier = Modifier.fillMaxWidth().heightIn(min = 55.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(text = translation["auto_update_check"], fontSize = 14.sp, color = skin.textPrimary)
                                Switch(checked = autoUpdateCheck, onCheckedChange = { if (context.config.root.global.uiSettings.hapticFeedback.get()) hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress); autoUpdateCheck = it; context.config.root.global.updateSettings.autoUpdateCheck.set(it); context.config.writeConfig(); scheduleUpdateCheck() }, colors = purrfectSwitchColors())
                            }
                        }
                    }

                    // RESET SETUP
                    GlassCard {
                        RowTitle(title = translation["reset_setup_title"])
                        Row(
                            modifier = Modifier.fillMaxWidth().heightIn(min = 55.dp).clickable { showResetSetupDialog = true }.padding(horizontal = 14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = translation["reset_setup_action"], fontSize = 16.sp, fontWeight = FontWeight.Medium, lineHeight = 20.sp, color = skin.textPrimary)
                            Icon(imageVector = Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, tint = skin.textPrimary)
                        }
                    }

                    // MESSAGE LOGGER
                    GlassCard {
                        RowTitle(title = translation["message_logger_title"])
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            var storedMessagesCount by rememberAsyncMutableState(defaultValue = 0) { context.messageLogger.getStoredMessageCount() }
                            var storedStoriesCount by rememberAsyncMutableState(defaultValue = 0) { context.messageLogger.getStoredStoriesCount() }
                            var showImportDialog by remember { mutableStateOf(false) }
                            var showExportOptionsDialog by remember { mutableStateOf(false) }
                            var showConversationExportDialog by remember { mutableStateOf(false) }
                            var showConversationFormatDialog by remember { mutableStateOf(false) }
                            var conversationSearchQuery by remember { mutableStateOf("") }
                            var selectedConversationForExport by remember { mutableStateOf<LoggerConversationExportTarget?>(null) }
                            var pendingConversationExportTarget by remember { mutableStateOf<LoggerConversationExportTarget?>(null) }
                            val loggerHistoryTranslation = remember { context.translation.getCategory("logger_history") }

                            data class ConversationSearchTarget(
                                val target: LoggerConversationExportTarget,
                                val friendDisplayName: String?,
                                val friendUsername: String?,
                                val chatDisplayName: String?,
                                val groupDisplayName: String?,
                                val readableUsernames: List<String>,
                                val readableIdentifiers: List<String>,
                                val isDirectChat: Boolean,
                                val isGroupChat: Boolean,
                                val sortOrder: Int
                            )

                            data class ConversationExportFormat(
                                val extension: String,
                                val mimeType: String,
                                val label: String
                            )

                            data class ParsedConversationMessage(
                                val senderId: String,
                                val senderUsername: String,
                                val timestamp: Long,
                                val contentType: ContentType,
                                val messageText: String?,
                                val attachments: List<DecodedAttachment>
                            )

                            fun String.isUuidLike(): Boolean {
                                val value = trim()
                                if (value.length != 36) return false
                                if (value[8] != '-' || value[13] != '-' || value[18] != '-' || value[23] != '-') return false
                                return value.filterIndexed { index, _ ->
                                    index != 8 && index != 13 && index != 18 && index != 23
                                }.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
                            }

                            fun String.isLikelyInternalId(): Boolean {
                                val value = trim()
                                if (value.isUuidLike()) return true
                                if (value.length >= 10 && value.all(Char::isDigit)) return true
                                if (value.length >= 16 && value.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' || it == '-' }) {
                                    val digitCount = value.count(Char::isDigit)
                                    val alphaCount = value.count { it.lowercaseChar() in 'a'..'f' }
                                    if (digitCount >= 4 && alphaCount >= 4) return true
                                }
                                return false
                            }

                            fun String.toReadableIdentityOrNull(): String? {
                                val value = trim()
                                if (value.isEmpty()) return null
                                if (value.isLikelyInternalId()) return null
                                if (!value.any { it.isLetter() }) return null
                                return value
                            }

                            fun String.toSearchIdentityOrNull(): String? {
                                val value = trim()
                                if (value.isEmpty()) return null
                                if (value.equals("myai", ignoreCase = true)) return null
                                return value
                            }

                            val exportTargets by rememberAsyncMutableState(defaultValue = emptyList<LoggerConversationExportTarget>()) {
                                context.messageLogger.getConversationExportTargets()
                            }
                            val exportSearchTargets by rememberAsyncMutableState(
                                defaultValue = emptyList<ConversationSearchTarget>(),
                                keys = arrayOf(exportTargets)
                            ) {
                                val friendIdentityCache = mutableMapOf<String, Pair<String?, String?>?>()
                                exportTargets.mapIndexedNotNull { index, target ->
                                    val friend = context.database.findFriend(target.conversationId)
                                    val group = context.database.getGroupInfo(target.conversationId)
                                    val chatDisplayName = target.groupTitle
                                        ?.toReadableIdentityOrNull()
                                        ?.takeIf { !it.equals(target.conversationId, ignoreCase = true) }
                                    val friendDisplayName = friend?.displayName?.toReadableIdentityOrNull()
                                    val friendUsername = friend?.mutableUsername?.toReadableIdentityOrNull()
                                    val searchableUsernames = target.usernames
                                        .mapNotNull { it.toSearchIdentityOrNull() }
                                        .distinct()
                                    val readableUsernames = searchableUsernames
                                        .mapNotNull { it.toReadableIdentityOrNull() }
                                        .distinct()
                                    val hasManyParticipants = target.userIds.distinct().size > 2 || searchableUsernames.size > 2
                                    val fallbackFriendIdentities = if (friend == null && !hasManyParticipants) {
                                        target.userIds.mapNotNull { userId ->
                                            friendIdentityCache.getOrPut(userId) {
                                                context.database.getFriendInfo(userId)?.let {
                                                    it.displayName?.toReadableIdentityOrNull() to
                                                        it.mutableUsername.toReadableIdentityOrNull()
                                                }
                                            }?.takeIf { it.first != null || it.second != null }
                                        }
                                    } else {
                                        emptyList()
                                    }
                                    val fallbackFriendDisplayName = fallbackFriendIdentities.firstNotNullOfOrNull { it.first }
                                    val fallbackFriendUsername = fallbackFriendIdentities.firstNotNullOfOrNull { it.second }
                                    val resolvedFriendDisplayName = friendDisplayName ?: fallbackFriendDisplayName
                                    val resolvedFriendUsername = friendUsername ?: fallbackFriendUsername
                                    val groupDisplayName = group?.name?.toReadableIdentityOrNull()
                                        ?: chatDisplayName?.takeIf { hasManyParticipants }
                                    val isGroupChat = groupDisplayName != null || hasManyParticipants
                                    val isDirectChat = !isGroupChat
                                    val readableIdentifiers = buildList {
                                        add(target.conversationId)
                                        addAll(target.userIds)
                                        resolvedFriendDisplayName?.let { add(it) }
                                        resolvedFriendUsername?.let { add(it) }
                                        chatDisplayName?.let { add(it) }
                                        groupDisplayName?.let { add(it) }
                                        addAll(searchableUsernames)
                                        addAll(readableUsernames)
                                    }.distinct()
                                    ConversationSearchTarget(
                                        target = target,
                                        friendDisplayName = resolvedFriendDisplayName,
                                        friendUsername = resolvedFriendUsername,
                                        chatDisplayName = chatDisplayName,
                                        groupDisplayName = groupDisplayName,
                                        readableUsernames = readableUsernames,
                                        readableIdentifiers = readableIdentifiers,
                                        isDirectChat = isDirectChat,
                                        isGroupChat = isGroupChat,
                                        sortOrder = index
                                    )
                                }.sortedWith(
                                    compareBy<ConversationSearchTarget> {
                                        when {
                                            it.isDirectChat -> 0
                                            it.isGroupChat -> 1
                                            else -> 2
                                        }
                                    }.thenBy { it.sortOrder }
                                )
                            }
                            val filteredExportTargets = remember(exportSearchTargets, conversationSearchQuery) {
                                val query = conversationSearchQuery.trim()
                                if (query.isBlank()) {
                                    exportSearchTargets
                                } else {
                                    exportSearchTargets.filter { searchTarget ->
                                        searchTarget.readableIdentifiers.any {
                                            it.contains(query, ignoreCase = true)
                                        }
                                    }
                                }
                            }

                            val exportFormats = remember {
                                listOf(
                                    ConversationExportFormat("db", "application/octet-stream", ".db"),
                                    ConversationExportFormat("html", "text/html", "HTML"),
                                    ConversationExportFormat("txt", "text/plain", "TXT")
                                )
                            }

                            fun formatExportTarget(searchTarget: ConversationSearchTarget): String {
                                searchTarget.friendDisplayName?.let { displayName ->
                                    val username = searchTarget.friendUsername
                                    val formattedName = if (username != null && !username.equals(displayName, ignoreCase = true)) {
                                        "$displayName ΓÇó @$username"
                                    } else {
                                        displayName
                                    }
                                    return loggerHistoryTranslation.format("list_friend_format", "name" to formattedName)
                                }

                                searchTarget.friendUsername?.let { username ->
                                    return loggerHistoryTranslation.format("list_friend_format", "name" to "@$username")
                                }

                                searchTarget.chatDisplayName?.takeIf { searchTarget.isDirectChat }?.let {
                                    return loggerHistoryTranslation.format("list_friend_format", "name" to it)
                                }

                                if (searchTarget.isDirectChat && searchTarget.readableUsernames.isNotEmpty()) {
                                    val friendName = if (searchTarget.readableUsernames.size == 1) {
                                        searchTarget.readableUsernames.first()
                                    } else {
                                        searchTarget.readableUsernames.joinToString(", ")
                                    }
                                    return loggerHistoryTranslation.format("list_friend_format", "name" to friendName)
                                }

                                searchTarget.groupDisplayName?.let {
                                    return loggerHistoryTranslation.format("list_group_format", "name" to it)
                                }

                                if (searchTarget.readableUsernames.isNotEmpty()) {
                                    return loggerHistoryTranslation.format(
                                        "list_group_format",
                                        "name" to searchTarget.readableUsernames.joinToString(", ")
                                    )
                                }

                                return if (searchTarget.isGroupChat) {
                                    loggerHistoryTranslation.format("list_group_format", "name" to searchTarget.target.conversationId)
                                } else {
                                    loggerHistoryTranslation.format("list_friend_format", "name" to searchTarget.target.conversationId)
                                }
                            }

                            fun showExportError(throwable: Throwable) {
                                context.log.error("Failed to export message logger", throwable)
                                context.shortToast(
                                    translation.format(
                                        "message_logger_export_failed_toast",
                                        "message" to (throwable.message ?: "Unknown error")
                                    )
                                )
                            }

                            fun parseConversationMessage(message: LoggedMessage): ParsedConversationMessage {
                                val messageObject = runCatching {
                                    JsonParser.parseString(String(message.messageData, Charsets.UTF_8)).asJsonObject
                                }.getOrNull()
                                val messageContent = messageObject?.getAsJsonObject("mMessageContent")
                                val contentBytes = runCatching {
                                    messageContent?.getAsJsonArray("mContent")?.map { it.asByte }?.toByteArray()
                                }.getOrNull()
                                val contentType = messageContent?.getAsJsonPrimitive("mContentType")?.asString?.let {
                                    runCatching { ContentType.valueOf(it) }.getOrNull()
                                } ?: contentBytes?.let { ContentType.fromMessageContainer(ProtoReader(it)) } ?: ContentType.UNKNOWN
                                val messageText = contentBytes?.getMessageText(contentType)
                                val attachments = runCatching {
                                    messageContent?.let { MessageDecoder.decode(it) } ?: emptyList()
                                }.getOrDefault(emptyList())

                                return ParsedConversationMessage(
                                    senderId = message.userId,
                                    senderUsername = message.username,
                                    timestamp = message.sendTimestamp,
                                    contentType = contentType,
                                    messageText = messageText,
                                    attachments = attachments
                                )
                            }

                            fun htmlEscape(input: String): String {
                                val escaped = StringBuilder(input.length)
                                input.forEach { char ->
                                    when (char) {
                                        '&' -> escaped.append("&amp;")
                                        '<' -> escaped.append("&lt;")
                                        '>' -> escaped.append("&gt;")
                                        '"' -> escaped.append("&quot;")
                                        '\'' -> escaped.append("&#39;")
                                        else -> escaped.append(char)
                                    }
                                }
                                return escaped.toString()
                            }

                            fun writeConversationExportFile(
                                target: LoggerConversationExportTarget,
                                format: ConversationExportFormat,
                                outputFile: File
                            ): Int {
                                val conversationId = target.conversationId.trim()
                                if (conversationId.isEmpty()) {
                                    throw IllegalArgumentException("Conversation ID cannot be empty")
                                }

                                val searchTarget = exportSearchTargets.firstOrNull { it.target.conversationId == conversationId }
                                val conversationTitle = searchTarget?.let { formatExportTarget(it) }
                                    ?: (translation["message_logger_export_individual_chat"] ?: "Exported Chat")
                                val dateFormatter = DateFormat.getDateTimeInstance()
                                val senderCache = mutableMapOf<String, String>()

                                fun formatSenderLabel(senderId: String, senderUsername: String): String {
                                    val friendInfo = context.database.getFriendInfo(senderId)
                                    val senderDisplayName = friendInfo?.displayName?.toReadableIdentityOrNull()
                                    val senderReadableUsername = friendInfo?.mutableUsername?.toReadableIdentityOrNull()
                                        ?: senderUsername.toReadableIdentityOrNull()
                                    return when {
                                        senderDisplayName != null &&
                                            senderReadableUsername != null &&
                                            !senderDisplayName.equals(senderReadableUsername, ignoreCase = true) ->
                                            "$senderDisplayName (@$senderReadableUsername)"
                                        senderDisplayName != null -> senderDisplayName
                                        senderReadableUsername != null -> "@$senderReadableUsername"
                                        else -> translation["sender_unknown"] ?: "Unknown sender"
                                    }
                                }

                                outputFile.parentFile?.mkdirs()
                                if (outputFile.exists() && !outputFile.delete()) {
                                    throw IllegalStateException("Failed to prepare export file")
                                }

                                return outputFile.bufferedWriter(Charsets.UTF_8).use { writer ->
                                    val isHtmlFormat = format.extension == "html"
                                    if (isHtmlFormat) {
                                        writer.appendLine("<!DOCTYPE html>")
                                        writer.appendLine("<html><head><meta charset=\"UTF-8\" />")
                                        writer.appendLine("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\" />")
                                        writer.appendLine("<title>${htmlEscape(conversationTitle)}</title>")
                                        writer.appendLine(
                                            "<style>body{font-family:Arial,sans-serif;background:#121212;color:#f3f3f3;padding:16px;}h2{margin-top:0;}" +
                                                ".meta{color:#a5a5a5;font-size:12px;margin-bottom:4px;}" +
                                                ".message{border:1px solid #2d2d2d;border-radius:10px;padding:10px;margin:10px 0;background:#1b1b1b;}" +
                                                ".content{white-space:pre-wrap;word-break:break-word;}" +
                                                ".attachments{margin:8px 0 0 18px;padding:0;}a{color:#8db7ff;}</style>"
                                        )
                                        writer.appendLine("</head><body>")
                                        writer.appendLine("<h2>${htmlEscape(conversationTitle)}</h2>")
                                        writer.appendLine("<p>${htmlEscape(translation.format("message_logger_conversation_id", "id" to conversationId))}</p>")
                                    } else {
                                        writer.appendLine(conversationTitle)
                                        writer.appendLine("")
                                    }

                                    val exportedMessageCount = context.messageLogger.forEachConversationMessage(
                                        conversationId = conversationId,
                                        userIds = target.userIds,
                                        orderAscending = true
                                    ) { loggedMessage ->
                                        val parsed = parseConversationMessage(loggedMessage)
                                        val senderInfo = senderCache.getOrPut(parsed.senderId) {
                                            formatSenderLabel(parsed.senderId, parsed.senderUsername)
                                        }
                                        val senderLabel = senderInfo
                                        val content = parsed.messageText?.takeIf { it.isNotBlank() } ?: if (parsed.contentType == ContentType.CHAT) {
                                            loggerHistoryTranslation["empty_message"]
                                        } else {
                                            parsed.contentType.name.lowercase()
                                        }

                                        if (isHtmlFormat) {
                                            writer.appendLine("<div class=\"message\">")
                                            writer.appendLine(
                                                "<div class=\"meta\">${
                                                    htmlEscape(
                                                        "${dateFormatter.format(Date(parsed.timestamp))} ΓÇó $senderLabel ΓÇó ${
                                                            parsed.contentType.name.lowercase()
                                                        }"
                                                    )
                                                }</div>"
                                            )
                                            writer.appendLine("<div class=\"content\">${htmlEscape(content).replace("\n", "<br/>")}</div>")
                                            if (parsed.attachments.isNotEmpty()) {
                                                writer.appendLine("<ul class=\"attachments\">")
                                                parsed.attachments.forEachIndexed { index, attachment ->
                                                    val attachmentLabel = "${loggerHistoryTranslation.format("chat_attachment", "index" to (index + 1).toString())} [${attachment.type.name.lowercase()}]"
                                                    val directUrl = attachment.directUrl?.takeIf { it.isNotBlank() }
                                                    if (directUrl != null) {
                                                        writer.appendLine(
                                                            "<li><a href=\"${htmlEscape(directUrl)}\" target=\"_blank\" rel=\"noopener noreferrer\">${
                                                                htmlEscape(attachmentLabel)
                                                            }</a></li>"
                                                        )
                                                    } else {
                                                        val placeholder = attachment.boltKey?.takeIf { it.isNotBlank() }
                                                            ?: attachment.mediaUniqueId?.takeIf { it.isNotBlank() }
                                                            ?: (translation["message_logger_missing_attachment_placeholder"] ?: "Attachment unavailable")
                                                        writer.appendLine("<li>${htmlEscape("$attachmentLabel: $placeholder")}</li>")
                                                    }
                                                }
                                                writer.appendLine("</ul>")
                                            }
                                            writer.appendLine("</div>")
                                        } else {
                                            writer.appendLine("[${dateFormatter.format(Date(parsed.timestamp))}] $senderLabel: $content")
                                            parsed.attachments.forEachIndexed { index, attachment ->
                                                val attachmentLabel = "${loggerHistoryTranslation.format("chat_attachment", "index" to (index + 1).toString())} [${attachment.type.name.lowercase()}]"
                                                val attachmentValue = attachment.directUrl?.takeIf { it.isNotBlank() }
                                                    ?: (translation["message_logger_missing_attachment_placeholder"] ?: "Attachment unavailable")
                                                writer.appendLine("  - $attachmentLabel: $attachmentValue")
                                            }
                                            writer.appendLine("")
                                        }
                                    }

                                    if (exportedMessageCount == 0) {
                                        if (isHtmlFormat) {
                                            writer.appendLine("<p>${htmlEscape(translation["message_logger_no_messages_export_text"] ?: "No messages found in this chat.")}</p>")
                                        } else {
                                            writer.appendLine(translation["message_logger_no_messages_export_text"] ?: "No messages found in this chat.")
                                        }
                                    }

                                    if (isHtmlFormat) {
                                        writer.appendLine("</body></html>")
                                    }

                                    exportedMessageCount
                                }
                            }

                            fun exportFullDatabase() {
                                runCatching {
                                    activityLauncherHelper.saveFile("message_logger.db", "application/octet-stream") { uri ->
                                        context.androidContext.contentResolver.openOutputStream(uri.toUri())?.use { out ->
                                            context.messageLogger.databaseFile.inputStream().use { input -> input.copyTo(out) }
                                        } ?: throw IllegalStateException("Failed to open output stream")
                                    }
                                }.onFailure { showExportError(it) }
                            }

                            fun exportConversation(target: LoggerConversationExportTarget, format: ConversationExportFormat) {
                                val conversationId = target.conversationId.trim()
                                if (conversationId.isEmpty()) {
                                    context.shortToast(translation["message_logger_missing_conversation_toast"])
                                    return
                                }

                                val fileNameSuffix = conversationId
                                    .filter { it.isLetterOrDigit() || it == '-' || it == '_' }
                                    .take(24)
                                    .ifBlank { "chat" }

                                runCatching {
                                    activityLauncherHelper.saveFile("message_logger_${fileNameSuffix}.${format.extension}", format.mimeType) { uri ->
                                        scope.launch {
                                            runCatching {
                                                val exportedMessageCount = withContext(Dispatchers.IO) {
                                                    val tempFile = File(
                                                        context.androidContext.cacheDir,
                                                        "message_logger_export_${System.currentTimeMillis()}.${format.extension}"
                                                    )
                                                    try {
                                                        val messageCount = if (format.extension == "db") {
                                                            context.messageLogger.exportConversationDatabase(
                                                                outputFile = tempFile,
                                                                conversationId = conversationId,
                                                                userIds = target.userIds
                                                            ).messageCount
                                                        } else {
                                                            writeConversationExportFile(target, format, tempFile)
                                                        }
                                                        context.androidContext.contentResolver.openOutputStream(uri.toUri())?.use { output ->
                                                            tempFile.inputStream().use { input -> input.copyTo(output) }
                                                        } ?: throw IllegalStateException("Failed to open output stream")
                                                        messageCount
                                                    } finally {
                                                        tempFile.delete()
                                                    }
                                                }

                                                if (exportedMessageCount == 0) {
                                                    context.shortToast(translation["message_logger_empty_chat_toast"])
                                                } else {
                                                    context.shortToast(translation["success_toast"])
                                                }
                                            }.onFailure { showExportError(it) }
                                        }
                                    }
                                }.onFailure { showExportError(it) }
                            }

                            fun dismissConversationExportDialog() {
                                showConversationExportDialog = false
                                selectedConversationForExport = null
                                conversationSearchQuery = ""
                            }

                            fun dismissConversationFormatDialog() {
                                showConversationFormatDialog = false
                                pendingConversationExportTarget = null
                            }

                            Column(modifier = Modifier.fillMaxWidth().padding(5.dp), verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                val summary = translation.format("message_logger_summary", "messageCount" to storedMessagesCount.toString(), "storyCount" to storedStoriesCount.toString()).replace("\n", " | ")
                                Text(summary, maxLines = 2, color = skin.textPrimary, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                                FlowRow(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(10.dp) ) {
                                    Button(onClick = { showExportOptionsDialog = true }, colors = sharedButtonColors, border = BorderStroke(1.dp, skin.textPrimary.copy(alpha = 0.25f))) { Text(text = translation["export_button"] ?: "Export", fontWeight = FontWeight.Bold) }
                                    Button(onClick = { runCatching { activityLauncherHelper.openFile("application/octet-stream") { uri -> val tempFile = File(context.androidContext.cacheDir, "view_logger.db"); context.androidContext.contentResolver.openInputStream(uri.toUri())?.use { it.copyTo(tempFile.outputStream()) }; routes.viewLoggerHistory.navigate { put("uri", URLEncoder.encode(tempFile.toUri().toString(), "UTF-8")) } } } }, colors = sharedButtonColors, border = BorderStroke(1.dp, skin.textPrimary.copy(alpha = 0.25f))) { Text(text = translation["view_button"] ?: "View", fontWeight = FontWeight.Bold) }
                                    Button(onClick = { runCatching { context.messageLogger.purgeAll(); storedMessagesCount = 0; storedStoriesCount = 0 }.onSuccess { context.shortToast(translation["success_toast"]) } }, colors = sharedButtonColors, border = BorderStroke(1.dp, skin.textPrimary.copy(alpha = 0.25f))) { Text(text = translation["clear_button"] ?: "Clear", fontWeight = FontWeight.Bold) }
                                    Button(onClick = { showImportDialog = true }, colors = sharedButtonColors, border = BorderStroke(1.dp, skin.textPrimary.copy(alpha = 0.25f))) { Text(text = translation["import_button"] ?: "Import", fontWeight = FontWeight.Bold) }
                                }
                            }
                            OutlinedButton(modifier = Modifier.fillMaxWidth().padding(5.dp), onClick = { routes.loggerHistory.navigate() }, colors = sharedOutlinedColors, border = BorderStroke(1.dp, skin.textPrimary.copy(alpha = 0.2f))) { Text(translation["view_logger_history_button"]) }
                            
                            if (showImportDialog) {
                                AestheticDialog(onDismissRequest = { showImportDialog = false }, title = translation["message_logger_import_title"], text = translation["message_logger_import_text"], icon = Icons.Filled.Info, confirmButtonText = context.translation["button.import"], dismissButtonText = context.translation["button.cancel"], onConfirm = { showImportDialog = false; runCatching { activityLauncherHelper.openFile("application/octet-stream") { uri -> context.androidContext.contentResolver.openInputStream(uri.toUri())?.use { context.messageLogger.databaseFile.outputStream().use { out -> it.copyTo(out) } }; storedMessagesCount = context.messageLogger.getStoredMessageCount(); storedStoriesCount = context.messageLogger.getStoredStoriesCount(); context.shortToast(translation["success_toast"]) } } }, onDismiss = { showImportDialog = false }, showCloseButton = false)
                            }
                            if (showExportOptionsDialog) {
                                AestheticDialog(
                                    onDismissRequest = { showExportOptionsDialog = false },
                                    title = translation["message_logger_export_title"] ?: "Export Message Logger",
                                    text = translation["message_logger_export_text"] ?: "Choose what to export.",
                                    icon = Icons.Filled.SaveAlt,
                                    confirmButtonText = context.translation["button.cancel"],
                                    onConfirm = { showExportOptionsDialog = false },
                                    showCloseButton = false,
                                    customContent = {
                                        Button(
                                            onClick = {
                                                showExportOptionsDialog = false
                                                pendingConversationExportTarget = null
                                                showConversationExportDialog = true
                                            },
                                            modifier = Modifier.fillMaxWidth(),
                                            colors = sharedButtonColors,
                                            border = BorderStroke(1.dp, skin.textPrimary.copy(alpha = 0.25f))
                                        ) {
                                            Text(translation["message_logger_export_individual_chat"] ?: "Export Individual Chat")
                                        }
                                        Button(
                                            onClick = {
                                                showExportOptionsDialog = false
                                                exportFullDatabase()
                                            },
                                            modifier = Modifier.fillMaxWidth(),
                                            colors = sharedButtonColors,
                                            border = BorderStroke(1.dp, skin.textPrimary.copy(alpha = 0.25f))
                                        ) {
                                            Text(translation["message_logger_export_full_database"] ?: "Export Full Database")
                                        }
                                    }
                                )
                            }
                            if (showConversationExportDialog) {
                                AestheticDialog(
                                    onDismissRequest = { dismissConversationExportDialog() },
                                    title = translation["message_logger_select_chat_title"] ?: "Export Individual Chat",
                                    text = translation["message_logger_select_chat_text"] ?: "Search by username, display name, or chat name.",
                                    icon = Icons.Filled.Search,
                                    confirmButtonText = translation["message_logger_continue_button"] ?: "Continue",
                                    dismissButtonText = context.translation["button.cancel"],
                                    onConfirm = {
                                        val selectedTarget = selectedConversationForExport ?: return@AestheticDialog
                                        pendingConversationExportTarget = selectedTarget
                                        dismissConversationExportDialog()
                                        showConversationFormatDialog = true
                                    },
                                    onDismiss = { dismissConversationExportDialog() },
                                    showCloseButton = false,
                                    confirmEnabled = selectedConversationForExport != null,
                                    customContent = {
                                        OutlinedTextField(
                                            value = conversationSearchQuery,
                                            onValueChange = { conversationSearchQuery = it },
                                            singleLine = true,
                                            modifier = Modifier.fillMaxWidth(),
                                            placeholder = {
                                                Text(context.translation["manager.dialogs.add_friend.search_hint"] ?: "Search")
                                            },
                                            leadingIcon = {
                                                Icon(Icons.Filled.Search, contentDescription = null)
                                            },
                                            trailingIcon = if (conversationSearchQuery.isNotBlank()) {
                                                {
                                                    IconButton(onClick = { conversationSearchQuery = "" }) {
                                                        Icon(
                                                            imageVector = Icons.Filled.Close,
                                                            contentDescription = null
                                                        )
                                                    }
                                                }
                                            } else null,
                                            colors = TextFieldDefaults.colors(
                                                focusedIndicatorColor = Color.Transparent,
                                                unfocusedIndicatorColor = Color.Transparent,
                                                focusedContainerColor = skin.textPrimary.copy(alpha = 0.08f),
                                                unfocusedContainerColor = skin.textPrimary.copy(alpha = 0.06f),
                                                focusedTextColor = skin.textPrimary,
                                                unfocusedTextColor = skin.textPrimary
                                            )
                                        )

                                        if (filteredExportTargets.isEmpty()) {
                                            Text(
                                                text = translation["message_logger_no_chats_found"] ?: "No chats found",
                                                color = skin.textSecondary,
                                                textAlign = TextAlign.Center,
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                        } else {
                                            LazyColumn(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .heightIn(max = 280.dp),
                                                verticalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                items(filteredExportTargets.size) { index ->
                                                    val searchTarget = filteredExportTargets[index]
                                                    val target = searchTarget.target
                                                    val isSelected = selectedConversationForExport?.conversationId == searchTarget.target.conversationId
                                                    OutlinedButton(
                                                        onClick = { selectedConversationForExport = searchTarget.target },
                                                        modifier = Modifier.fillMaxWidth(),
                                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = skin.textPrimary),
                                                        border = BorderStroke(
                                                            1.dp,
                                                            if (isSelected) {
                                                                skin.glowPrimary.copy(alpha = 0.55f)
                                                            } else {
                                                                skin.textPrimary.copy(alpha = 0.18f)
                                                            }
                                                        )
                                                    ) {
                                                        Column(
                                                            modifier = Modifier.fillMaxWidth(),
                                                            verticalArrangement = Arrangement.spacedBy(2.dp)
                                                        ) {
                                                            Text(
                                                                text = formatExportTarget(searchTarget),
                                                                maxLines = 1,
                                                                overflow = TextOverflow.Ellipsis
                                                            )
                                                            val secondaryLabel = when {
                                                                searchTarget.friendDisplayName != null && searchTarget.friendUsername != null -> "@${searchTarget.friendUsername}"
                                                                searchTarget.friendDisplayName != null -> searchTarget.friendDisplayName
                                                                searchTarget.chatDisplayName != null -> searchTarget.chatDisplayName
                                                                searchTarget.groupDisplayName != null -> searchTarget.groupDisplayName
                                                                searchTarget.readableUsernames.isNotEmpty() -> searchTarget.readableUsernames.joinToString(", ")
                                                                else -> null
                                                            }
                                                            if (secondaryLabel != null) {
                                                                    Text(
                                                                        text = secondaryLabel,
                                                                        color = skin.textSecondary,
                                                                        fontSize = 12.sp,
                                                                        maxLines = 1,
                                                                        overflow = TextOverflow.Ellipsis
                                                                    )
                                                                }
                                                                Text(
                                                                    text = translation.format("message_logger_message_count", "count" to target.messageCount.toString()),     
                                                                    color = skin.textSecondary,
                                                                    fontSize = 12.sp
                                                                )
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    )
                                }
                                if (showConversationFormatDialog && pendingConversationExportTarget != null) {
                                    AestheticDialog(
                                        onDismissRequest = { dismissConversationFormatDialog() },
                                        title = translation["message_logger_select_export_format_title"] ?: "Select Export Format",
                                        text = translation["message_logger_select_export_format_text"] ?: "Choose how to export the selected chat.",
                                        icon = Icons.Filled.Description,
                                        confirmButtonText = context.translation["button.cancel"],
                                        onConfirm = { dismissConversationFormatDialog() },
                                        showCloseButton = false,
                                        customContent = {
                                            exportFormats.forEach { format ->
                                                val formatLabel = when (format.extension) {
                                                    "db" -> translation["message_logger_export_format_db"] ?: ".db"
                                                    "html" -> translation["message_logger_export_format_html"] ?: "HTML"
                                                    else -> translation["message_logger_export_format_txt"] ?: "TXT"
                                                }
                                                Button(
                                                    onClick = {
                                                        val exportTarget = pendingConversationExportTarget ?: return@Button
                                                        dismissConversationFormatDialog()
                                                        exportConversation(exportTarget, format)
                                                    },
                                                    modifier = Modifier.fillMaxWidth(),
                                                    colors = sharedButtonColors,
                                                    border = BorderStroke(1.dp, skin.textPrimary.copy(alpha = 0.25f))
                                                ) {
                                                    Text(formatLabel)
                                                }
                                            }
                                        }
                                    )
                                }
                            }
                        }

                        // FRIEND NOTES
                        GlassCard {
                            RowTitle(title = translation["friend_notes_title"])
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(text = translation["friend_notes_description"], modifier = Modifier.fillMaxWidth().padding(horizontal = 5.dp), color = skin.textPrimary, textAlign = TextAlign.Center)
                                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 5.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Button(onClick = { runCatching { val notes = context.database.getAllScopeNotes(); if (notes.isEmpty()) return@runCatching; val json = context.gson.toJson(notes); activityLauncherHelper.saveFile("notes.json", "application/json") { uri -> context.androidContext.contentResolver.openOutputStream(uri.toUri())?.use { it.write(json.toByteArray()) }; context.shortToast(translation["friend_notes_backup_success"]) } } }, colors = sharedButtonColors, border = BorderStroke(1.dp, skin.textPrimary.copy(alpha = 0.25f))) { Text(text = translation["backup_button"]) }
                                        Button(onClick = { runCatching { activityLauncherHelper.openFile("application/json") { uri -> context.androidContext.contentResolver.openInputStream(uri.toUri())?.use { val json = it.reader().readText(); val notes = context.gson.fromJson<Map<String, String>>(json, object : com.google.gson.reflect.TypeToken<Map<String, String>>() {}.type); context.database.setAllScopeNotes(notes); context.shortToast(translation["friend_notes_restore_success"]) } } } }, colors = sharedButtonColors, border = BorderStroke(1.dp, skin.textPrimary.copy(alpha = 0.25f))) { Text(text = translation["restore_button"]) }
                                    }
                                }
                            }
                        }

                        // DEBUG
                        GlassCard {
                            RowTitle(title = translation["debug_title"])
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 26.dp)) {
                                    var selectedFileType by remember { mutableStateOf(InternalFileHandleType.entries.first()) }
                                    var expanded by remember { mutableStateOf(false) }
                                    Box(modifier = Modifier.weight(1f)) {
                                        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = Modifier.fillMaxWidth()) {
                                            AestheticDropdownField(value = translation.getOrNull("debug_file_${selectedFileType.name.lowercase()}") ?: selectedFileType.fileName, expanded = expanded, modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable), onClick = { expanded = true })
                                            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                                InternalFileHandleType.entries.forEach { fileType -> DropdownMenuItem(onClick = { expanded = false; selectedFileType = fileType }, text = { Text(text = translation.getOrNull("debug_file_${fileType.name.lowercase()}") ?: fileType.fileName) }) }
                                            }
                                        }
                                    }
                                    Button(onClick = { runCatching { scope.launch { selectedFileType.resolve(context.androidContext).delete() } }.onSuccess { context.shortToast(translation["success_toast"]) } }, colors = ButtonDefaults.buttonColors(containerColor = skin.textPrimary.copy(alpha = 0.1f), contentColor = skin.textPrimary), border = BorderStroke(1.dp, skin.textPrimary.copy(alpha = 0.18f)), shape = RoundedCornerShape(14.dp)) {
                                        Icon(Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(8.dp)); Text(translation["clear_button"])
                                    }
                                }
                                ShiftedRow {
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        PremiumPreferenceToggle(context.sharedPreferences, key = "test_mode", text = translation["test_mode_label"], defaultValue = true, confirmDisableTitle = translation["purr_aura_disable_title"], confirmDisableText = translation["purr_aura_disable_text"])
                                        PreferenceToggle(context.sharedPreferences, key = "disable_feature_loading", text = translation["disable_feature_loading_label"])     
                                        PreferenceToggle(context.sharedPreferences, key = "disable_mapper", text = translation["disable_auto_mapper_label"])
                                        PreferenceToggle(context.sharedPreferences, key = "disable_bypass_indicator", text = translation["disable_bypass_indicator_label"])   
                                        PreferenceToggle(context.sharedPreferences, key = "disable_cant_login_button", text = translation["disable_cant_login_button_label"] ?: "Disable Can't Login Button")
                                    }
                                }
                            }
                        }
                }
            }
        }

        FloatingTopBar(
            title = routeInfo.translatedKey?.value ?: translation["manager.routes.home_settings"] ?: "Settings",
            onBack = { routes.navController.popBackStack() },
            scrollOffset = computedScrollOffset,
            enableMorph = true,
            titleAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.headerHeightTracker { controlsHeight = it },
            actions = {
                IconButton(
                    modifier = Modifier.size(44.dp),
                    onClick = {
                        if (context.config.root.global.uiSettings.hapticFeedback.get()) hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                        routes.navigation?.openBottomBarCustomization = true
                    }
                ) {
                    Icon(
                        imageVector = Icons.Filled.Tune,
                        contentDescription = null,
                        tint = skin.textPrimary.copy(alpha = 0.85f)
                    )
                }
            }
        )
    }
}

@Composable
private fun HomeSettings.AphelionTargetAppSwitchRow() {
    val managerTheme = context.config.root.global.uiSettings.managerTheme.get()
    val activeSkin = LocalPurrfectSkin.current
    val skin = remember(managerTheme, activeSkin) { if (managerTheme == "APHELION") activeSkin else PurrfectPalette }
    val hapticFeedback = LocalHapticFeedback.current
    val currentLabel = when (context.activeTargetApp) {
        me.eternal.purrfect.common.TargetApp.SNAPCHAT -> translation["target_app_snapchat_summary"] ?: "Current: Snapchat"
        me.eternal.purrfect.common.TargetApp.REDDIT -> translation["target_app_reddit_summary"] ?: "Current: Reddit"
        me.eternal.purrfect.common.TargetApp.WHATSAPP -> translation["target_app_whatsapp_summary"] ?: "Current: WhatsApp"
        me.eternal.purrfect.common.TargetApp.INSTAGRAM -> translation["target_app_instagram_summary"] ?: "Current: Instagram"
    }
    val buttonLabel = translation["switch_target_button"] ?: "Switch"

    var showSwitcher by remember { mutableStateOf(false) }

    if (showSwitcher) {
        TargetSwitcherDialog(onDismiss = { showSwitcher = false })
    }

    ShiftedRow {
        Column(
            modifier = Modifier.fillMaxWidth().padding(end = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = currentLabel,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = skin.textPrimary.copy(alpha = 0.82f)
            )
            Button(
                onClick = {
                    if (context.config.root.global.uiSettings.hapticFeedback.get()) hapticFeedback.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    showSwitcher = true
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 54.dp),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = skin.textPrimary.copy(alpha = 0.1f),
                    contentColor = skin.textPrimary
                ),
                border = BorderStroke(1.dp, skin.textPrimary.copy(alpha = 0.18f)),
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp)
            ) {
                Icon(Icons.Filled.Forum, contentDescription = null, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Text(buttonLabel, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun HomeSettings.AphelionLimitedTargetSettingsScreen() {
    val managerTheme = context.config.root.global.uiSettings.managerTheme.get()
    val activeSkin = LocalPurrfectSkin.current
    val skin = remember(managerTheme, activeSkin) { if (managerTheme == "APHELION") activeSkin else PurrfectPalette }
    val hapticFeedback = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val view = LocalView.current
    var switchCenter by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    var controlsHeight by remember { mutableStateOf(100.dp) }
    var showResetSetupDialog by remember { mutableStateOf(false) }
    val computedScrollOffset by remember { derivedStateOf { scrollState.value } }

    LaunchedEffect(computedScrollOffset) {
        routes.navigation?.globalScrollOffset = computedScrollOffset
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(skin.backgroundGradient)
    ) {
        if (showResetSetupDialog) {
            AestheticDialog(
                onDismissRequest = { showResetSetupDialog = false },
                title = translation["reset_setup_dialog_title"] ?: "Reset Setup",
                text = translation["reset_setup_dialog_text"] ?: "",
                icon = Icons.Filled.Warning,
                confirmButtonText = context.translation["button.positive"],
                dismissButtonText = context.translation["button.negative"],
                onConfirm = {
                    showResetSetupDialog = false
                    context.sharedPreferences.edit()
                        .remove("setup_in_progress")
                        .remove("setup_current_route")
                        .remove("setup_skip_patch")
                        .remove("setup_install_mode")
                        .remove("setup_selected_apps")
                        .apply()
                    SetupPreferences.clearSetupChoices(context.sharedPreferences)
                    context.config.reset()
                    context.config.writeConfig()
                    val intent = Intent(context.androidContext, me.eternal.purrfect.ui.setup.SetupActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    context.androidContext.startActivity(intent)
                    routes.navController.popBackStack()
                },
                onDismiss = { showResetSetupDialog = false },
                showCloseButton = false
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 12.dp)
                .padding(bottom = routes.bottomPadding + 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Spacer(Modifier.height(controlsHeight))
            GlassCard {
                RowTitle(title = translation["target_app_title"] ?: "Target App")
                AphelionTargetAppSwitchRow()
            }
            GlassCard {
                RowTitle(title = translation["actions_title"] ?: "Actions")
                RowAction(key = "change_language") { context.checkForRequirements(Requirements.LANGUAGE) }
                if (shouldShowRedditRepatchAction()) {
                    RowAction(key = "repatch_reddit") { launchRedditRepatchSetup() }
                }
            }
            GlassCard {
                RowTitle(title = translation["ui_theme_title"] ?: "UI Theme")
                ShiftedRow {
                    Row(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 55.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = translation["settings_ui_theme"] ?: "Aphelion Theme", fontSize = 14.sp, color = skin.textPrimary)
                        val currentThemeId = context.config.root.global.uiSettings.managerTheme.get()
                        var localThemeId by remember { mutableStateOf(currentThemeId) }
                        Switch(
                            checked = localThemeId == "APHELION",
                            onCheckedChange = { isAphelion ->
                                val newId = if (isAphelion) "APHELION" else "LEGACY"
                                localThemeId = newId
                                AphelionHaptics.themeRevealTick(context, hapticFeedback)
                                scope.launch {
                                    val bitmap = me.eternal.purrfect.ui.manager.theme.aphelion.captureHardwareBitmap((view.context as Activity).window)
                                    routes.navigation?.themeRevealState?.requestReveal(
                                        newThemeId = newId,
                                        originCenter = switchCenter,
                                        bitmap = bitmap
                                    )
                                    kotlinx.coroutines.delay(50)
                                    context.config.root.global.uiSettings.managerTheme.set(newId)
                                    context.syncSkinSettings()
                                    withContext(Dispatchers.IO) {
                                        context.config.writeConfig()
                                    }
                                }
                            },
                            modifier = Modifier
                                .onGloballyPositioned { coords ->
                                    val rootPos = coords.positionInRoot()
                                    switchCenter = androidx.compose.ui.geometry.Offset(
                                        x = rootPos.x + coords.size.width / 2f,
                                        y = rootPos.y + coords.size.height / 2f
                                    )
                                },
                            colors = purrfectSwitchColors()
                        )
                    }
                }
            }

            // SKIN PICKER SECTION
            val skin = LocalPurrfectSkin.current
            var currentSkinId by remember {
                mutableStateOf(context.config.root.global.uiSettings.aphelionSkin.get())
            }
            val currentThemeId_internal = context.config.root.global.uiSettings.managerTheme.get()
            var localThemeId_internal by remember { mutableStateOf(currentThemeId_internal) }

            AnimatedVisibility(
                visible = localThemeId_internal == "APHELION",
                enter = fadeIn(tween(300)),
                exit = fadeOut(tween(200))
            ) {
                GlassCard {
                    RowTitle(title = "Aphelion Skins")
                    Text(
                        text = "Choose a tailored visual identity, refined for depth, clarity, and premium accents.",
                        fontSize = 12.sp,
                        color = skin.textSecondary,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                        lineHeight = 16.sp
                    )
                    Spacer(Modifier.height(10.dp))
                    AphelionSkinPicker(
                        currentSkinId = currentSkinId,
                        onSkinSelected = { newSkinId ->
                            currentSkinId = newSkinId
                            context.config.root.global.uiSettings.aphelionSkin.set(newSkinId)
                            context.syncSkinSettings()
                            context.config.writeConfig()
                            AphelionHaptics.themeRevealTick(context, hapticFeedback)
                        }
                    )

                    // LUMINA CUSTOMIZATION
                    AnimatedVisibility(
                        visible = currentSkinId == "LUMINA",
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 14.dp),
                                color = skin.textPrimary.copy(alpha = 0.08f)
                            )

                            // Mode Switcher
                            val currentMode = context.config.root.global.uiSettings.luminaMode.get()
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                listOf("AUTO", "LIGHT", "DARK").forEach { mode ->
                                    val isSelected = currentMode == mode
                                    Surface(
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(38.dp)
                                            .clickable {
                                                context.config.root.global.uiSettings.luminaMode.set(mode)
                                                context.syncSkinSettings()
                                                context.config.writeConfig()
                                                AphelionHaptics.themeRevealTick(context, hapticFeedback)
                                            },
                                        shape = RoundedCornerShape(10.dp),
                                        color = if (isSelected) skin.glowPrimary.copy(alpha = 0.25f) else skin.textPrimary.copy(alpha = 0.05f),
                                        border = if (isSelected) BorderStroke(1.dp, if (skin.isDark) skin.glowPrimary else androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.6f)) else null
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Text(
                                                text = mode,
                                                fontSize = 11.sp,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                                color = if (isSelected) (if (skin.isDark) skin.glowPrimary else androidx.compose.ui.graphics.Color.Black) else skin.textPrimary.copy(alpha = 0.7f)
                                            )
                                        }
                                    }
                                }
                            }

                            // Accent Ribbon
                            val currentAccent = context.config.root.global.uiSettings.luminaAccent.get()
                            val accents = remember { 
                                me.eternal.purrfect.common.ui.theme.Catppuccin.mocha.accents
                                    .filter { it.first != "Espresso" && it.first != "Forest" }
                            }

                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(14.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState())
                                        .padding(horizontal = 14.dp),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    accents.forEach { (name, color) ->
                                        val isSelected = if (currentSkinId == "LUMINA") {
                                            context.config.root.global.uiSettings.luminaAccent.get().equals(name, ignoreCase = true)
                                        } else {
                                            context.config.root.global.uiSettings.aetherAccent.get().equals(name, ignoreCase = true)
                                        }
                                        Box(
                                            modifier = Modifier
                                                .size(34.dp)
                                                .clip(androidx.compose.foundation.shape.CircleShape)
                                                .background(color)
                                                .border(
                                                    width = if (isSelected) 2.dp else 1.dp,
                                                    color = if (isSelected) skin.textPrimary else skin.textPrimary.copy(alpha = 0.1f),
                                                    shape = androidx.compose.foundation.shape.CircleShape
                                                )
                                                .clickable {
                                                    if (currentSkinId == "LUMINA") {
                                                        context.config.root.global.uiSettings.luminaAccent.set(name)
                                                    } else {
                                                        context.config.root.global.uiSettings.aetherAccent.set(name)
                                                    }
                                                    context.syncSkinSettings()
                                                    context.config.writeConfig()
                                                    AphelionHaptics.themeRevealTick(context, hapticFeedback)
                                                },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            if (isSelected) {
                                                Icon(
                                                    imageVector = Icons.Default.Check,
                                                    contentDescription = null,
                                                    tint = if (color.luminance() > 0.5f) skin.cardOverlayColor else Color.White,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                        }
                                    }
                                }

                                // Centered Dynamic Accent Label
                                val activeAccentName = if (currentSkinId == "LUMINA") {
                                    context.config.root.global.uiSettings.luminaAccent.get()
                                } else {
                                    context.config.root.global.uiSettings.aetherAccent.get()
                                }
                                Text(
                                    text = "Accent: $activeAccentName",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (skin.isDark) skin.glowPrimary.copy(alpha = 0.85f) else skin.textPrimary,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }                                }
                            }

                            // AETHER CUSTOMIZATION
                            AnimatedVisibility(
                                visible = currentSkinId == "AETHER",
                                enter = expandVertically() + fadeIn(),
                                exit = shrinkVertically() + fadeOut()
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 12.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    HorizontalDivider(
                                        modifier = Modifier.padding(horizontal = 14.dp),
                                        color = skin.textPrimary.copy(alpha = 0.08f)
                                    )

                                    // Mode Switcher
                                    val currentMode = context.config.root.global.uiSettings.aetherMode.get()
                                    val isAmoled = context.config.root.global.uiSettings.aetherAmoled.get()

                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 14.dp),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        listOf("AUTO", "LIGHT", "DARK").forEach { mode ->
                                            val isSelected = currentMode == mode
                                            Surface(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .height(38.dp)
                                                    .clickable {
                                                        context.config.root.global.uiSettings.aetherMode.set(mode)
                                                        context.syncSkinSettings()
                                                        context.config.writeConfig()
                                                        AphelionHaptics.themeRevealTick(context, hapticFeedback)
                                                    },
                                                shape = RoundedCornerShape(10.dp),
                                                color = if (isSelected) skin.glowPrimary.copy(alpha = 0.25f) else skin.textPrimary.copy(alpha = 0.05f),
                                                border = if (isSelected) BorderStroke(1.dp, if (skin.isDark) skin.glowPrimary else androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.6f)) else null
                                            ) {
                                                Box(contentAlignment = Alignment.Center) {
                                                    Text(
                                                        text = mode,
                                                        fontSize = 11.sp,
                                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                                        color = if (isSelected) (if (skin.isDark) skin.glowPrimary else androidx.compose.ui.graphics.Color.Black) else skin.textPrimary.copy(alpha = 0.7f)
                                                    )
                                                }
                                            }
                                        }

                                        // AMOLED Toggle
                                        if (currentMode != "LIGHT") {
                                            Surface(
                                                modifier = Modifier
                                                    .size(38.dp)
                                                    .clickable {
                                                        context.config.root.global.uiSettings.aetherAmoled.set(!isAmoled)
                                                        context.syncSkinSettings()
                                                        context.config.writeConfig()
                                                        AphelionHaptics.themeRevealTick(context, hapticFeedback)
                                                    },
                                                shape = RoundedCornerShape(10.dp),
                                                color = if (isAmoled) Color.Black else skin.textPrimary.copy(alpha = 0.05f),
                                                border = if (isAmoled) BorderStroke(1.dp, skin.glowPrimary) else null
                                            ) {
                                                Box(contentAlignment = Alignment.Center) {
                                                    Icon(
                                                        imageVector = if (isAmoled) Icons.Default.BrightnessLow else Icons.Default.BrightnessHigh,
                                                        contentDescription = null,
                                                        modifier = Modifier.size(18.dp),
                                                        tint = if (isAmoled) skin.glowPrimary else skin.textPrimary.copy(alpha = 0.5f)
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    // Accent Ribbon (Aether Specific)
                                    val currentAccent = context.config.root.global.uiSettings.aetherAccent.get()
                                    val accents = remember(skin.isDark) { 
                                        me.eternal.purrfect.common.ui.theme.Catppuccin.mocha.accents
                                            .filter { it.first != "Espresso" && it.first != "Forest" }
                                            .filter { (name, _) -> if (skin.isDark) name != "Black" else name != "White" }
                                    }

                                    Column(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalArrangement = Arrangement.spacedBy(14.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .horizontalScroll(rememberScrollState())
                                                .padding(horizontal = 14.dp),
                                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            accents.forEach { (name, color) ->
                                                val isSelected = currentAccent.equals(name, ignoreCase = true)
                                                Box(
                                                    modifier = Modifier
                                                        .size(34.dp)
                                                        .clip(androidx.compose.foundation.shape.CircleShape)
                                                        .background(color)
                                                        .border(
                                                            width = if (isSelected) 2.dp else 1.dp,
                                                            color = if (isSelected) skin.textPrimary else skin.textPrimary.copy(alpha = 0.1f),
                                                            shape = androidx.compose.foundation.shape.CircleShape
                                                        )
                                                        .clickable {
                                                            context.config.root.global.uiSettings.aetherAccent.set(name)
                                                            context.syncSkinSettings()
                                                            context.config.writeConfig()
                                                            AphelionHaptics.themeRevealTick(context, hapticFeedback)
                                                        },
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    if (isSelected) {
                                                        Icon(
                                                            imageVector = Icons.Default.Check,
                                                            contentDescription = null,
                                                            tint = if (color.luminance() > 0.5f) skin.cardOverlayColor else Color.White,
                                                            modifier = Modifier.size(18.dp)
                                                        )
                                                    }
                                                }
                                            }
                                        }

                                        // Centered Dynamic Accent Label
                                        Text(
                                            text = "Accent: $currentAccent",
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (skin.isDark) skin.glowPrimary.copy(alpha = 0.85f) else skin.textPrimary,
                                            textAlign = TextAlign.Center,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }                                }
                            }

                            // CYBER CUSTOMIZATION
                            AnimatedVisibility(
                                visible = currentSkinId == "CYBER",
                                enter = expandVertically() + fadeIn(),
                                exit = shrinkVertically() + fadeOut()
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 12.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    HorizontalDivider(
                                        modifier = Modifier.padding(horizontal = 14.dp),
                                        color = skin.textPrimary.copy(alpha = 0.08f)
                                    )

                                    // Style Switcher
                                    val currentStyle = context.config.root.global.uiSettings.cyberwareStyle.get()

                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 14.dp),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        listOf("SYNTHWAVE", "NIGHTCITY").forEach { style ->
                                            val isSelected = currentStyle == style
                                            Surface(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .height(38.dp)
                                                    .clickable {
                                                        context.config.root.global.uiSettings.cyberwareStyle.set(style)
                                                        context.syncSkinSettings()
                                                        context.config.writeConfig()
                                                        AphelionHaptics.themeRevealTick(context, hapticFeedback)
                                                    },
                                                shape = RoundedCornerShape(10.dp),
                                                color = if (isSelected) skin.glowPrimary.copy(alpha = 0.25f) else skin.textPrimary.copy(alpha = 0.05f),
                                                border = if (isSelected) BorderStroke(1.dp, skin.glowPrimary) else null
                                            ) {
                                                Box(contentAlignment = Alignment.Center) {
                                                    Text(
                                                        text = if (style == "SYNTHWAVE") "Synthwave" else "Night City",
                                                        fontSize = 11.sp,
                                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                                        color = if (isSelected) skin.glowPrimary else skin.textPrimary.copy(alpha = 0.7f)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                }
            }

            GlassCard {
                RowTitle(title = translation["ui_settings_title"] ?: "UI Settings")
                ShiftedRow {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(modifier = Modifier.fillMaxWidth().heightIn(min = 55.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(text = translation["haptic_feedback_label"] ?: "Haptic Feedback", fontSize = 14.sp, color = skin.textPrimary)
                            var hapticEnabled by remember { mutableStateOf(context.config.root.global.uiSettings.hapticFeedback.getNullable() ?: true) }
                            Switch(checked = hapticEnabled, onCheckedChange = { if (it) hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress); hapticEnabled = it; context.config.root.global.uiSettings.hapticFeedback.set(it); context.config.writeConfig() }, colors = purrfectSwitchColors())    
                        }
                    }
                }
            }
            GlassCard {
                RowTitle(title = translation["updates_title"] ?: "Updates")
                var autoUpdateCheck by remember { mutableStateOf(context.config.root.global.updateSettings.autoUpdateCheck.getNullable() ?: true) }
                ShiftedRow {
                    Row(modifier = Modifier.fillMaxWidth().heightIn(min = 55.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(text = translation["auto_update_check"] ?: "Auto Update Check", fontSize = 14.sp, color = skin.textPrimary)
                        Switch(checked = autoUpdateCheck, onCheckedChange = { if (context.config.root.global.uiSettings.hapticFeedback.get()) hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress); autoUpdateCheck = it; context.config.root.global.updateSettings.autoUpdateCheck.set(it); context.config.writeConfig(); scheduleUpdateCheck() }, colors = purrfectSwitchColors())
                    }
                }
            }
            GlassCard {
                RowTitle(title = translation["reset_setup_title"] ?: "Reset Setup")
                ShiftedRow(modifier = Modifier.fillMaxWidth().heightIn(min = 55.dp).clickable { showResetSetupDialog = true }.padding(horizontal = 14.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(text = translation["reset_setup_action"] ?: "Reset and restart Purrfect", fontSize = 16.sp, fontWeight = FontWeight.Medium, lineHeight = 20.sp, color = skin.textPrimary)      
                    Icon(imageVector = Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, tint = skin.textPrimary)
                }
            }
        }

        FloatingTopBar(
            title = routeInfo.translatedKey?.value ?: translation["manager.routes.home_settings"] ?: "Settings",
            onBack = { routes.navController.popBackStack() },
            scrollOffset = computedScrollOffset,
            enableMorph = true,
            titleAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.headerHeightTracker { controlsHeight = it },
            actions = { Spacer(Modifier.size(44.dp)) }
        )
    }
}
