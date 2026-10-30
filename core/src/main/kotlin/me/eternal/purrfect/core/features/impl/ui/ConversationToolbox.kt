package me.eternal.purrfect.core.features.impl.ui

import me.eternal.purrfect.core.ui.PurrfectOverlayTheme

import me.eternal.purrfect.common.ui.theme.LocalPurrfectSkin

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.times
import me.eternal.purrfect.common.scripting.JSModule
import me.eternal.purrfect.common.scripting.ui.EnumScriptInterface
import me.eternal.purrfect.common.scripting.ui.InterfaceManager
import me.eternal.purrfect.common.scripting.ui.ScriptInterface
import me.eternal.purrfect.common.ui.createComposeAlertDialog
import me.eternal.purrfect.core.event.events.impl.AddViewEvent
import me.eternal.purrfect.core.features.Feature
import me.eternal.purrfect.core.features.impl.messaging.Messaging
import me.eternal.purrfect.core.ui.PurrfectOverlayPalette
import me.eternal.purrfect.core.ui.PurrfectOverlayTheme


data class ComposableMenu(
    val title: String,
    val filter: (conversationId: String) -> Boolean,
    val composable: @Composable (alertDialog: AlertDialog, conversationId: String) -> Unit,
)

class ConversationToolbox : Feature("Conversation Toolbox") {
    private val composableList = mutableListOf<ComposableMenu>()
    private val expandedComposableCache = mutableStateMapOf<String, Boolean>()

    fun addComposable(title: String, filter: (conversationId: String) -> Boolean = { true }, composable: @Composable (alertDialog: AlertDialog, conversationId: String) -> Unit) {
        composableList.add(
            ComposableMenu(title, filter, composable)
        )
    }

    @SuppressLint("SetTextI18n")
    override fun init() {
        onNextActivityCreate {
            context.event.subscribe(AddViewEvent::class) { event ->
                if (composableList.isEmpty()) return@subscribe

                val chatInputBar by getChatInputBar(event) ?: return@subscribe

                chatInputBar?.addView(FrameLayout(event.view.context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        (52 * context.resources.displayMetrics.density).toInt(),
                    ).apply {
                        gravity = Gravity.BOTTOM
                    }
                    setPadding(25, 0, 25, 0)

                    addView(TextView(event.view.context).apply {
                        layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ).apply {
                            gravity = Gravity.CENTER_VERTICAL
                        }
                        setOnClickListener {
                            openToolbox()
                        }
                        textSize = 21f
                        text = "\uD83E\uDDF0"
                    })
                })
            }

            context.scriptRuntime.eachModule {
                val interfaceManager = getBinding(InterfaceManager::class)?.takeIf {
                    it.hasInterface(EnumScriptInterface.CONVERSATION_TOOLBOX)
                } ?: return@eachModule
                addComposable("\uD83D\uDCDC ${moduleInfo.displayName}") { alertDialog, conversationId ->
                    val skin = LocalPurrfectSkin.current
                    ScriptInterface(remember {
                        interfaceManager.buildInterface(EnumScriptInterface.CONVERSATION_TOOLBOX, mapOf(
                            "alertDialog" to alertDialog,
                            "conversationId" to conversationId,
                        ))
                    } ?: return@addComposable)
                }
            }
        }
    }

    private fun openToolbox() {
        val openedConversationId = context.feature(Messaging::class).openedConversationUUID?.toString() ?: run {
            context.shortToast(context.translation["toast_open_conversation_first"])
            return
        }

        createComposeAlertDialog(context.mainActivity!!) { alertDialog ->
            PurrfectOverlayTheme(context) {
                val skin = LocalPurrfectSkin.current
                val shape = RoundedCornerShape(24.dp)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(
                            min = 100.dp,
                            max = LocalConfiguration.current.screenHeightDp * 0.8f.dp
                        )
                        .clip(shape)
                        .background(skin.cardOverlayColor, shape)
                        .border(
                            1.dp,
                            Brush.linearGradient(
                                listOf(
                                    skin.glowPrimary.copy(alpha = 0.55f),
                                    skin.glowSecondary.copy(alpha = 0.35f)
                                )
                            ),
                            shape
                        )
                        .padding(12.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        context.translation["conversation_toolbox.title"],
                        fontSize = 18.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp),
                        textAlign = TextAlign.Center,
                        color = skin.textPrimary
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    composableList.reversed().forEach { (title, filter, composable) ->
                        if (!filter(openedConversationId)) return@forEach
                        val expanded = expandedComposableCache[title] == true
                        val itemShape = RoundedCornerShape(18.dp)
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(itemShape)
                                .background(skin.textPrimary.copy(alpha = 0.08f), itemShape)
                                .border(1.dp, skin.textPrimary.copy(alpha = 0.15f), itemShape)
                        ) {
                            Row(
                                modifier = Modifier
                                    .clickable(
                                        indication = null,
                                        interactionSource = remember { MutableInteractionSource() }
                                    ) { expandedComposableCache[title] = !expanded }
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Image(
                                    imageVector = if (expanded) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowUp,
                                    contentDescription = null,
                                    colorFilter = ColorFilter.tint(skin.textPrimary.copy(alpha = 0.9f)),
                                )
                                Text(
                                    title,
                                    fontSize = 15.sp,
                                    fontStyle = FontStyle.Italic,
                                    color = skin.textPrimary,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            if (expanded) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 10.dp)
                                ) {
                                    runCatching {
                                        composable(alertDialog, openedConversationId)
                                    }.onFailure { throwable ->
                                        Text(
                                            context.translation.format(
                                                "conversation_toolbox.failed_to_load",
                                                "message" to (throwable.message ?: "unknown error")
                                            ),
                                            color = skin.textSecondary,
                                            fontSize = 12.sp
                                        )
                                        context.log.error("Failed to load composable: ${throwable.message}", throwable)
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                    }
                }
            }
        }.show()
    }
}
