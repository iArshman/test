package me.eternal.purrfect.ui.setup.screens.impl

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.eternal.purrfect.common.TargetApp
import me.eternal.purrfect.common.ui.theme.LocalPurrfectSkin
import me.eternal.purrfect.common.ui.util.G2RoundedRectangle
import me.eternal.purrfect.ui.setup.screens.SetupScreen
import me.eternal.purrfect.ui.util.ActivityLauncherHelper
import me.eternal.purrfect.ui.util.OnLifecycleEvent
import me.eternal.purrfect.ui.util.scaleOnPress

data class PermissionData(
    val translationKey: String,
    val isPermissionGranted: () -> Boolean,
    val requestPermission: (PermissionData) -> Unit,
)

class PermissionsScreen(
    private val selectedAppsProvider: () -> Set<TargetApp> = { setOf(TargetApp.SNAPCHAT) }
) : SetupScreen() {
    private lateinit var activityLauncherHelper: ActivityLauncherHelper

    override fun init() {
        activityLauncherHelper = ActivityLauncherHelper(context.activity!!)
    }

    private fun descriptionFor(key: String, redditOnly: Boolean): String {
        return when (key) {
            "notification_access" -> if (redditOnly) {
                context.translation["setup.permissions.notification_access_description_reddit"]
            } else {
                context.translation["setup.permissions.notification_access_description"]
            }
            "battery_optimization" -> context.translation["setup.permissions.battery_optimization_description"]
            "display_over_other_apps" -> context.translation["setup.permissions.display_over_other_apps_description"]
            else -> ""
        }
    }

    @Composable
    private fun PermissionRow(
        label: String,
        translationKey: String,
        granted: Boolean,
        redditOnly: Boolean,
        onRequest: () -> Unit
    ) {
        val skin = LocalPurrfectSkin.current
        val accent = if (granted) skin.glowSecondary else skin.glowPrimary
        val contentColor = if (skin.isDark) Color.White else Color.Black
        val buttonColors = ButtonDefaults.buttonColors(
            containerColor = accent.copy(alpha = 0.28f),
            contentColor = contentColor
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = skin.cardOverlayColor,
            border = BorderStroke(1.dp, accent.copy(alpha = 0.45f)),
            tonalElevation = if (granted) 8.dp else 0.dp,
            shadowElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = accent.copy(alpha = 0.2f),
                        modifier = Modifier.size(46.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = when (translationKey) {
                                    "battery_optimization" -> Icons.Filled.BatteryFull
                                    "display_over_other_apps" -> Icons.Filled.Layers
                                    else -> Icons.Filled.NotificationsActive
                                },
                                contentDescription = null,
                                tint = accent,
                                modifier = Modifier.align(Alignment.Center)
                            )
                        }
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = label,
                            fontWeight = FontWeight.SemiBold,
                            color = skin.textPrimary,
                            fontSize = 16.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val description = descriptionFor(translationKey, redditOnly)
                        if (description.isNotBlank()) {
                            Text(
                                text = description,
                                fontSize = 12.sp,
                                color = skin.textSecondary,
                                lineHeight = 16.sp
                            )
                        }
                    }
                }
                AnimatedContent(
                    targetState = granted,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 50.dp),
                    transitionSpec = {
                        fadeIn(animationSpec = tween(150)) togetherWith fadeOut(animationSpec = tween(150))
                    }, label = "permState"
                ) { isGranted ->
                    if (isGranted) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = if (skin.id == "AETHER") G2RoundedRectangle(14.dp) else RoundedCornerShape(14.dp),
                            color = skin.glowSecondary.copy(alpha = 0.18f),
                            border = BorderStroke(1.dp, skin.glowSecondary.copy(alpha = 0.5f))
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Check,
                                    contentDescription = null,
                                    tint = skin.glowSecondary,
                                    modifier = Modifier.padding(end = 6.dp)
                                )
                                Text(
                                    text = context.translation["setup.permissions.granted_label"],
                                    color = skin.glowSecondary,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    } else {
                        val src = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                        Button(
                            onClick = onRequest,
                            interactionSource = src,
                            modifier = Modifier
                                .fillMaxWidth()
                                .scaleOnPress(src),
                            colors = buttonColors
                        ) {
                            Text(text = context.translation["setup.permissions.request_button"])
                        }
                    }
                }
            }
        }
    }

    @SuppressLint("BatteryLife")
    @Composable
    override fun Content() {
        val coroutineScope = rememberCoroutineScope()
        val selectedApps = selectedAppsProvider()
        val redditStylePermissions = selectedApps.isNotEmpty() &&
                selectedApps.all { it == TargetApp.REDDIT || it == TargetApp.INSTAGRAM }
        val grantedPermissions = remember {
            mutableStateMapOf<String, Boolean>()
        }
        val permissions = remember(redditStylePermissions) {
            listOf(
                PermissionData(
                    translationKey = "notification_access",
                    isPermissionGranted = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            context.androidContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                        } else {
                            true
                        }
                    },
                    requestPermission = { perm ->
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            activityLauncherHelper.requestPermission(Manifest.permission.POST_NOTIFICATIONS) { resultCode, _ ->
                                coroutineScope.launch {
                                    grantedPermissions[perm.translationKey] = resultCode == Activity.RESULT_OK
                                }
                            }
                        }
                    }
                ),
                PermissionData(
                    translationKey = "battery_optimization",
                    isPermissionGranted = {
                        val powerManager =
                            context.androidContext.getSystemService(Context.POWER_SERVICE) as PowerManager
                        powerManager.isIgnoringBatteryOptimizations(context.androidContext.packageName)
                    },
                    requestPermission = { perm ->
                        activityLauncherHelper.launch(Intent().apply {
                            action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                            data = Uri.parse("package:${context.androidContext.packageName}")
                        }) { resultCode, _ ->
                            coroutineScope.launch {
                                grantedPermissions[perm.translationKey] = resultCode == 0
                            }
                        }
                    }
                ),
                PermissionData(
                    translationKey = "display_over_other_apps",
                    isPermissionGranted = {
                        Settings.canDrawOverlays(context.androidContext)
                    },
                    requestPermission = { perm ->
                        activityLauncherHelper.launch(Intent().apply {
                            action = Settings.ACTION_MANAGE_OVERLAY_PERMISSION
                            data = Uri.parse("package:${context.androidContext.packageName}")
                        }) { resultCode, _ ->
                            coroutineScope.launch {
                                grantedPermissions[perm.translationKey] = resultCode == 0
                            }
                        }
                    }
                )
            ).filterNot { redditStylePermissions && it.translationKey == "display_over_other_apps" }
        }

        fun updateState() {
            permissions.forEach { perm ->
                grantedPermissions[perm.translationKey] = perm.isPermissionGranted()
            }
            if (permissions.all { perm -> grantedPermissions[perm.translationKey] == true }) {
                goNext()
            }
        }

        OnLifecycleEvent { _, event ->
            if (event != Lifecycle.Event.ON_RESUME) return@OnLifecycleEvent
            coroutineScope.launch {
                updateState()
                delay(1000)
                updateState()
            }
        }

        LaunchedEffect(Unit) {
            updateState()
        }

        SetupCard {
            StepTitle(
                title = context.translation["setup.permissions.dialog"],
                subtitle = null
            )
            Spacer(modifier = Modifier.height(6.dp))
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                permissions.forEach { perm ->
                    PermissionRow(
                        label = context.translation["setup.permissions.${perm.translationKey}"],
                        translationKey = perm.translationKey,
                        granted = grantedPermissions[perm.translationKey] == true,
                        redditOnly = redditStylePermissions
                    ) {
                        if (perm.isPermissionGranted()) {
                            grantedPermissions[perm.translationKey] = true
                        } else {
                            perm.requestPermission(perm)
                        }
                    }
                }
            }
        }
    }
}
