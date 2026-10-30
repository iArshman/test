@file:OptIn(
    androidx.compose.animation.ExperimentalAnimationApi::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class
)
package me.eternal.purrfect.ui.overlay

import android.app.Dialog
import android.content.Intent
import android.net.Uri
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.rememberNavController
import me.eternal.purrfect.R
import me.eternal.purrfect.RemoteSideContext
import me.eternal.purrfect.common.TargetApp
import me.eternal.purrfect.common.ui.theme.LocalPurrfectSkin
import me.eternal.purrfect.ui.manager.Navigation
import me.eternal.purrfect.ui.manager.Routes

class RemoteOverlay(
    private val context: RemoteSideContext
) {
    private lateinit var dialog: Dialog
    private var dismissCallback: (() -> Boolean)? = null

    @Composable
    private fun OverlayContent(startRoute: (Routes) -> Routes.Route) {
        val navHostController = rememberNavController()
        LaunchedEffect(Unit) { dismissCallback = { navHostController.popBackStack() } }
        val navigation = remember { Navigation(context, navHostController) }

        Scaffold(
            containerColor = Color.Transparent,
            topBar = { navigation.TopBar() }
        ) { innerPadding ->
            navigation.NavContent(
                innerPadding,
                startDestination = remember { startRoute(navigation.routes).routeInfo.id }
            )
        }
    }

    fun close() {
        if (!::dialog.isInitialized || !dialog.isShowing) return
        dismissCallback = null
        context.setTargetAppOverride(null)
        context.sharedPreferences.edit().putBoolean("overlay_active", false).apply()
        context.androidContext.mainExecutor.execute { dialog.dismiss() }
    }

    fun show(targetAppOverride: TargetApp? = null, route: (Routes) -> Routes.Route) {
        if (!android.provider.Settings.canDrawOverlays(context.androidContext)) {
            val myIntent = Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
            myIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            myIntent.setData(android.net.Uri.parse("package:" + context.androidContext.packageName))
            context.androidContext.startActivity(myIntent)
            return
        }

        if (::dialog.isInitialized && dialog.isShowing) return

        context.setTargetAppOverride(targetAppOverride)
        context.sharedPreferences.edit().putBoolean("overlay_active", true).apply()
        context.androidContext.mainExecutor.execute {
            dialog = object: Dialog(context.androidContext, R.style.FullscreenOverlayDialog) {
                override fun dismiss() {
                    dismissCallback?.also { if (it()) return }
                    super.dismiss()
                    this@RemoteOverlay.context.sharedPreferences.edit().putBoolean("overlay_active", false).apply()
                    this@RemoteOverlay.context.setTargetAppOverride(null)
                    this@RemoteOverlay.context.config.writeConfig()
                }
            }
            dialog.window?.apply {
                setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
                setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
                clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            }

            dialog.setContentView(
                me.eternal.purrfect.common.ui.createComposeView(context.androidContext) {
                    me.eternal.purrfect.core.ui.PurrfectOverlayTheme(null) {
                        androidx.compose.runtime.CompositionLocalProvider(
                            androidx.compose.foundation.LocalOverscrollConfiguration provides null
                        ) {
                            val skin = LocalPurrfectSkin.current
                            val isAether = skin.id == "AETHER"
                            val shape = if (isAether) me.eternal.purrfect.common.ui.util.G2RoundedRectangle(26.dp) else RoundedCornerShape(26.dp)

                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Black.copy(alpha = 0.45f))
                                    .clickable { dialog.dismiss() },
                                contentAlignment = Alignment.Center
                            ) {
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth(0.92f)
                                        .fillMaxHeight()
                                        .padding(top = 52.dp, bottom = 48.dp)
                                        .clip(shape)
                                        .border(1.dp, skin.textPrimary.copy(alpha = 0.12f), shape)
                                        .clickable(enabled = false) {},
                                    shape = shape,
                                    color = skin.cardOverlayColor,
                                    tonalElevation = 0.dp,
                                    shadowElevation = 24.dp
                                ) {
                                    Box(modifier = Modifier.background(skin.cardOverlay)) {
                                        OverlayContent(route)
                                    }
                                }
                            }
                        }
                    }
                }
            )
            dialog.setCancelable(true)
            dialog.show()
        }
    }
}
