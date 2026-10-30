package me.eternal.purrfect.ui.manager.pages.home

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.navigation.NavBackStackEntry
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.eternal.purrfect.R
import me.eternal.purrfect.common.Constants
import me.eternal.purrfect.common.TargetApp
import me.eternal.purrfect.common.action.EnumAction
import me.eternal.purrfect.common.bridge.InternalFileHandleType
import me.eternal.purrfect.common.ui.rememberAsyncMutableState
import me.eternal.purrfect.storage.getAllScopeNotes
import me.eternal.purrfect.storage.setAllScopeNotes
import me.eternal.purrfect.task.UpdateCheckWorker
import me.eternal.purrfect.common.ui.theme.LocalPurrfectSkin
import me.eternal.purrfect.ui.manager.theme.aetherGlass
import me.eternal.purrfect.ui.manager.Routes
import me.eternal.purrfect.ui.manager.ManagerTheme
import me.eternal.purrfect.ui.manager.components.AestheticDialog
import me.eternal.purrfect.ui.manager.theme.PurrfectPalette
import me.eternal.purrfect.ui.setup.Requirements
import me.eternal.purrfect.ui.setup.SetupPreferences
import me.eternal.purrfect.ui.util.ActivityLauncherHelper
import me.eternal.purrfect.ui.util.AlertDialogs
import me.eternal.purrfect.ui.util.openFile
import me.eternal.purrfect.ui.util.saveFile
import me.eternal.purrfect.ui.util.purrfectSwitchColors
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class HomeSettings : Routes.Route() {
    override val translation by lazy { context.translation.getCategory("manager.sections.home_settings") }
    internal lateinit var activityLauncherHelper: ActivityLauncherHelper
    private val dialogs by lazy { AlertDialogs(context.translation) }

    internal fun scheduleUpdateCheck() {
        val workManager = WorkManager.getInstance(context.androidContext)
        val updateSettings = context.config.root.global.updateSettings
        val autoUpdateCheck = updateSettings.autoUpdateCheck.get()

        if (autoUpdateCheck) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val inputData = Data.Builder()
                .putString("channel_name", translation["update_notification_channel_name"])
                .putString("channel_description", translation["update_notification_channel_description"])
                .putString("notification_title", translation["update_notification_title"])
                .putString("notification_text", translation["update_notification_text"])
                .putString("update_channel", "stable")
                .build()

            val workRequest = PeriodicWorkRequestBuilder<UpdateCheckWorker>(1, TimeUnit.DAYS)
                .setConstraints(constraints)
                .setInputData(inputData)
                .build()

            workManager.enqueueUniquePeriodicWork(
                "purrfect_update_check",
                ExistingPeriodicWorkPolicy.REPLACE,
                workRequest
            )
        } else {
            workManager.cancelUniqueWork("purrfect_update_check")
        }
    }

    override val init: () -> Unit = {
        activityLauncherHelper = ActivityLauncherHelper(context.activity!!)
    }

    override val content: @Composable (NavBackStackEntry) -> Unit = { nav ->
        val themeId by produceState(
            initialValue = context.config.root.global.uiSettings.managerTheme.get()
        ) {
            while (true) {
                delay(300)
                value = context.config.root.global.uiSettings.managerTheme.get()
            }
        }

        key(themeId) {
            val currentTheme = ManagerTheme.fromId(themeId).theme
            with(currentTheme) {
                this@HomeSettings.SettingsScreen(nav)
            }
        }
    }

    internal fun launchRedditRepatchSetup() {
        val currentContext = context.activity ?: context.androidContext
        Intent(currentContext, me.eternal.purrfect.ui.setup.SetupActivity::class.java).apply {
            putExtra("requirements", Requirements.REDDIT_REPATCH)
            if (currentContext !is Activity) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            currentContext.startActivity(this)
        }
    }

    internal fun launchTargetInstallSetup(targetApp: TargetApp) {
        val currentContext = context.activity ?: context.androidContext
        if (targetApp == TargetApp.WHATSAPP) {
            val packageName = context.packageNameForTargetApp(targetApp)
            val label = targetDisplayName(targetApp)
            val marketIntent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("market://details?id=$packageName")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val webIntent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://play.google.com/store/apps/details?id=$packageName")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching {
                currentContext.startActivity(marketIntent)
            }.onFailure {
                runCatching { currentContext.startActivity(webIntent) }
                    .onFailure { context.shortToast("$label is not installed") }
            }
            return
        }
        val requirement = when (targetApp) {
            TargetApp.SNAPCHAT -> Requirements.INSTALL_SNAPCHAT
            TargetApp.REDDIT -> Requirements.INSTALL_REDDIT
            TargetApp.WHATSAPP -> Requirements.INSTALL_SNAPCHAT
            TargetApp.INSTAGRAM -> Requirements.INSTALL_INSTAGRAM
        }
        Intent(currentContext, me.eternal.purrfect.ui.setup.SetupActivity::class.java).apply {
            putExtra("requirements", requirement)
            if (currentContext !is Activity) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            currentContext.startActivity(this)
        }
    }

    internal fun shouldShowRedditRepatchAction(): Boolean {
        return context.activeTargetApp == TargetApp.REDDIT &&
                !SetupPreferences.wasAutoSetupSkipped(context.sharedPreferences) &&
                SetupPreferences.lastInstallModeName(context.sharedPreferences) == "NON_ROOT"
    }

    internal fun isTargetReady(targetApp: TargetApp): Boolean {
        if (SetupPreferences.hasCompletedTarget(context.sharedPreferences, targetApp)) return true
        val packageName = context.packageNameForTargetApp(targetApp)
        return runCatching {
            context.androidContext.packageManager.getPackageInfo(packageName, 0)
        }.isSuccess
    }

    internal fun targetDisplayName(targetApp: TargetApp): String {
        return when (targetApp) {
            TargetApp.SNAPCHAT -> "Snapchat"
            TargetApp.REDDIT -> "Reddit"
            TargetApp.WHATSAPP -> "WhatsApp"
            TargetApp.INSTAGRAM -> "Instagram"
        }
    }

    internal fun targetSwitchLabel(targetApp: TargetApp): String {
        if (isTargetReady(targetApp)) {
            return when (targetApp) {
                TargetApp.SNAPCHAT -> translation["switch_to_snapchat_button"] ?: "Switch to Snapchat"
                TargetApp.REDDIT -> translation["switch_to_reddit_button"] ?: "Switch to Reddit"
                TargetApp.WHATSAPP -> translation["switch_to_whatsapp_button"] ?: "Switch to WhatsApp"
                TargetApp.INSTAGRAM -> translation["switch_to_instagram_button"] ?: "Switch to Instagram"
            }
        }
        return when (targetApp) {
            TargetApp.SNAPCHAT -> translation["install_snapchat_button"] ?: "Snapchat Available: Install!"
            TargetApp.REDDIT -> translation["install_reddit_button"] ?: "Reddit Available: Install!"
            TargetApp.WHATSAPP -> translation["install_whatsapp_button"] ?: "Install WhatsApp"
            TargetApp.INSTAGRAM -> translation["install_instagram_button"] ?: "Install Instagram"
        }
    }

    internal fun handleTargetSwitch(targetApp: TargetApp) {
        if (isTargetReady(targetApp)) {
            context.setActiveTargetApp(targetApp)
            
            // Cold Restart to ensure entire engine (ABI, Config, Locale) reloads for the new target app
            val intent = Intent(context.androidContext, me.eternal.purrfect.ui.manager.MainActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            context.androidContext.startActivity(intent)
            context.activity?.finish()
        } else {
            launchTargetInstallSetup(targetApp)
        }
    }

    @Composable
    private fun LimitedTargetSettingsScreen() {
        val hapticFeedback = LocalHapticFeedback.current
        val skin = LocalPurrfectSkin.current
        val currentTarget = context.activeTargetApp
        var showSwitcher by remember { mutableStateOf(false) }
        val title = when (currentTarget) {
            TargetApp.REDDIT -> translation["reddit_settings_title"]
            TargetApp.WHATSAPP -> translation["whatsapp_settings_title"] ?: "WhatsApp Mode"
            TargetApp.INSTAGRAM -> translation["instagram_settings_title"] ?: "Instagram Mode"
            TargetApp.SNAPCHAT -> translation["target_app_title"]
        }
        val icon = Icons.Filled.Forum
        fun openSwitcher() {
            if (context.config.root.global.uiSettings.hapticFeedback.get()) {
                hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
            }
            showSwitcher = true
        }
        if (showSwitcher) {
            TargetSwitcherDialog(onDismiss = { showSwitcher = false })
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(skin.backgroundGradient)
                .padding(horizontal = 18.dp)
                .verticalScroll(rememberScrollState()),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 48.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = when (currentTarget) {
                        TargetApp.WHATSAPP -> Color(0xFF25D366)
                        TargetApp.INSTAGRAM -> Color(0xFFE4405F)
                        TargetApp.REDDIT -> Color(0xFFFF4500)
                        else -> skin.textPrimary
                    },
                    modifier = Modifier.size(48.dp)
                )
                Text(
                    text = title,
                    color = skin.textPrimary,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    listOf(TargetApp.SNAPCHAT, TargetApp.INSTAGRAM, TargetApp.REDDIT)
                        .filter { it != currentTarget }
                        .forEach { targetApp ->
                            Button(
                                onClick = { handleTargetSwitch(targetApp) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = skin.textPrimary.copy(alpha = 0.85f),
                                    contentColor = skin.cardOverlayColor
                                )
                            ) {
                                Text(targetSwitchLabel(targetApp), fontSize = 12.sp)
                            }
                        }
                    
                    Button(
                        onClick = { openSwitcher() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = skin.textPrimary,
                            contentColor = skin.cardOverlayColor
                        )
                    ) {
                        Icon(Icons.Filled.Forum, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(translation["switch_target_button"] ?: "Switch", fontSize = 12.sp)
                    }
                }
            }
        }
    }

    @Composable
    internal fun TargetSwitcherDialog(onDismiss: () -> Unit) {
        val hapticFeedback = LocalHapticFeedback.current
        val skin = LocalPurrfectSkin.current
        Dialog(onDismissRequest = onDismiss) {
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = skin.cardOverlayColor,
                border = BorderStroke(1.dp, skin.glassBorder.copy(alpha = 0.2f))
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = translation["switch_target_dialog_title"] ?: "Switch Target App",
                        color = skin.textPrimary,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    TargetApp.entries
                        .filter { it != context.activeTargetApp && it != TargetApp.WHATSAPP }
                        .forEach { targetApp ->
                            Button(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = {
                                    if (context.config.root.global.uiSettings.hapticFeedback.get()) {
                                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                    }
                                    onDismiss()
                                    handleTargetSwitch(targetApp)
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = when (targetApp) {
                                        TargetApp.WHATSAPP -> Color(0xFF25D366)
                                        TargetApp.INSTAGRAM -> Color(0xFFE4405F)
                                        TargetApp.REDDIT -> Color(0xFFFF4500)
                                        else -> skin.textPrimary
                                    },
                                    contentColor = if (targetApp == TargetApp.SNAPCHAT) skin.cardOverlayColor else Color.White
                                )
                            ) {
                                Icon(Icons.Filled.Forum, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(targetSwitchLabel(targetApp))
                            }
                        }
                }
            }
        }
    }

    @Composable
    internal fun RowTitle(title: String) {
        val skin = LocalPurrfectSkin.current
        Text(
            text = title,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = skin.textPrimary
        )
    }

    @Composable
    internal fun PremiumPreferenceToggle(
        sharedPreferences: SharedPreferences,
        key: String,
        text: String,
        defaultValue: Boolean = false,
        confirmDisableTitle: String? = null,
        confirmDisableText: String? = null
    ) {
        val realKey = "debug_$key"
        var value by remember { mutableStateOf(sharedPreferences.getBoolean(realKey, defaultValue)) }
        var showDisableDialog by remember { mutableStateOf(false) }
        val hapticFeedback = LocalHapticFeedback.current
        val positiveLabel = context.translation["button.positive"]
        val negativeLabel = context.translation["button.negative"]

        LaunchedEffect(realKey) {
            if (!sharedPreferences.contains(realKey)) {
                sharedPreferences.edit().putBoolean(realKey, defaultValue).apply()
                value = defaultValue
            }
        }

        if (showDisableDialog) {
            AestheticDialog(
                onDismissRequest = { showDisableDialog = false },
                title = confirmDisableTitle ?: translation["reset_setup_dialog_title"],
                text = confirmDisableText.orEmpty(),
                icon = Icons.Filled.Warning,
                confirmButtonText = positiveLabel,
                dismissButtonText = negativeLabel,
                onConfirm = {
                    value = false
                    sharedPreferences.edit().putBoolean(realKey, false).apply()
                    showDisableDialog = false
                },
                onDismiss = { showDisableDialog = false },
                showCloseButton = false
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 55.dp)
                .clickable {
                    if (context.config.root.global.uiSettings.hapticFeedback.get()) {
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                    }
                    val nextValue = !value
                    if (!nextValue && confirmDisableTitle != null) {
                        showDisableDialog = true
                    } else {
                        value = nextValue
                        sharedPreferences.edit() {
                            putBoolean(realKey, value)
                        }
                    }
                },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val skin = LocalPurrfectSkin.current
            Text(text = text, modifier = Modifier.padding(start = 26.dp, end = 16.dp), fontSize = 14.sp, color = skin.textPrimary)
            Switch(
                checked = value,
                onCheckedChange = null,
                modifier = Modifier.padding(end = 26.dp),
                colors = purrfectSwitchColors()
            )
        }
    }

    @Composable
    internal fun PreferenceToggle(sharedPreferences: SharedPreferences, key: String, text: String) {
        val realKey = "debug_$key"
        var value by remember { mutableStateOf(sharedPreferences.getBoolean(realKey, false)) }
        val hapticFeedback = LocalHapticFeedback.current
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 55.dp)
                .clickable {
                    if (context.config.root.global.uiSettings.hapticFeedback.get()) {
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                    }
                    value = !value
                    sharedPreferences
                        .edit() {
                            putBoolean(realKey, value)
                        }
                },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val skin = LocalPurrfectSkin.current
            Text(text = text, modifier = Modifier.padding(start = 26.dp, end = 16.dp), fontSize = 14.sp, color = skin.textPrimary)
            Switch(
                checked = value,
                onCheckedChange = null,
                modifier = Modifier.padding(end = 26.dp),
                colors = purrfectSwitchColors()
            )
        }
    }

    @Composable
    internal fun RowAction(key: String, requireConfirmation: Boolean = false, action: () -> Unit) {
        var confirmationDialog by remember {
            mutableStateOf(false)
        }
        val skin = LocalPurrfectSkin.current
        fun takeAction() {
            if (requireConfirmation) {
                confirmationDialog = true
            } else {
                action()
            }
        }
        if (requireConfirmation && confirmationDialog) {
            Dialog(onDismissRequest = { confirmationDialog = false }) {
                dialogs.ConfirmDialog(title = context.translation["manager.dialogs.action_confirm.title"], onConfirm = {
                    action()
                    confirmationDialog = false
                }, onDismiss = {
                    confirmationDialog = false
                })
            }
        }
        ShiftedRow(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 55.dp)
                .clickable {
                    takeAction()
                },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
            ) {
                Text(text = context.translation["actions.$key.name"], fontSize = 16.sp, fontWeight = FontWeight.Bold, lineHeight = 20.sp, color = skin.textPrimary)
                context.translation.getOrNull("actions.$key.description")?.let { Text(text = it, fontSize = 12.sp, fontWeight = FontWeight.Light, lineHeight = 15.sp, color = skin.textPrimary.copy(alpha = 0.8f)) }
            }
            IconButton(onClick = { takeAction() },
                modifier = Modifier.padding(end = 2.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = context.translation.getOrNull("actions.$key.name"),
                    modifier = Modifier.size(24.dp),
                    tint = skin.textPrimary
                )
            }
        }
    }

    @Composable
    internal fun ShiftedRow(
        modifier: Modifier = Modifier,
        horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
        verticalAlignment: Alignment.Vertical = Alignment.Top,
        content: @Composable RowScope.() -> Unit
    ) {
        Row(
            modifier = modifier.padding(start = 26.dp),
            horizontalArrangement = horizontalArrangement,
            verticalAlignment = verticalAlignment
        ) { content(this) }
    }

    @Composable
    internal fun GlassCard(
        modifier: Modifier = Modifier,
        content: @Composable ColumnScope.() -> Unit
    ) {
        val skin = LocalPurrfectSkin.current
        Surface(
            modifier = modifier.aetherGlass(skin, 22.dp),
            shape = RoundedCornerShape(22.dp),
            color = Color.Transparent,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            border = BorderStroke(1.dp, skin.glassBorder.copy(alpha = 0.2f)),
            contentColor = skin.textPrimary
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                content()
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    internal fun AestheticDropdownField(
        value: String,
        expanded: Boolean,
        modifier: Modifier = Modifier,
        onClick: () -> Unit
    ) {
        val skin = LocalPurrfectSkin.current
        val shape = RoundedCornerShape(16.dp)
        Row(
            modifier = modifier
                .clip(shape)
                .background(skin.cardOverlayColor.copy(alpha = 0.3f))
                .border(1.dp, skin.glassBorder.copy(alpha = 0.45f), shape)
                .clickable { onClick() }
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = value, color = skin.textPrimary)
            ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
        }
    }
}
