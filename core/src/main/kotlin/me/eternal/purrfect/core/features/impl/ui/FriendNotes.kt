package me.eternal.purrfect.core.features.impl.ui

import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import me.eternal.purrfect.common.ui.AutoClearKeyboardFocus
import me.eternal.purrfect.common.ui.EditNoteTextField
import me.eternal.purrfect.common.ui.createComposeView
import me.eternal.purrfect.common.ui.rememberAsyncMutableState
import me.eternal.purrfect.core.event.events.impl.AddViewEvent
import me.eternal.purrfect.core.features.Feature
import me.eternal.purrfect.core.ui.getValdiContext
import me.eternal.purrfect.core.util.ktx.getObjectFieldOrNull

class FriendNotes: Feature("Friend Notes") {
    override fun init() {
        if (!context.config.experimental.friendNotes.get()) return

        context.event.subscribe(AddViewEvent::class) { event ->
            if (!event.viewClassName.endsWith("UnifiedProfileFlatlandProfileViewTopViewFrameLayout")) return@subscribe

            val viewGroup = (event.view as? ViewGroup) ?: return@subscribe
            viewGroup.post {
                val composerRootView = viewGroup.getChildAt(0) ?: return@post
                val composerContext = composerRootView.getValdiContext() ?: return@post
                val userId = composerContext.viewModel?.getObjectFieldOrNull("_userId")?.toString() ?: return@post

                if (userId == context.database.myUserId) return@post

                viewGroup.removeView(composerRootView)

                val manageNotesView = createComposeView(viewGroup.context, ViewCompositionStrategy.DisposeOnDetachedFromWindow) {
                    val primaryColor = remember { Color(this@FriendNotes.context.userInterface.colorPrimary) }
                    var isFetched by remember { mutableStateOf(false) }
                    var scopeNotes by rememberAsyncMutableState(null) {
                        this@FriendNotes.context.bridgeClient.getScopeNotes(userId).also {
                            isFetched = true
                        }
                    }

                    DisposableEffect(Unit) {
                        onDispose {
                            runCatching {
                                if (!isFetched) return@runCatching
                                context.bridgeClient.setScopeNotes(userId, scopeNotes)
                            }.onFailure {
                                context.log.error("Failed to save notes", it)
                            }
                        }
                    }

                    AutoClearKeyboardFocus()

                    EditNoteTextField(
                        modifier = Modifier.padding(top = 8.dp),
                        primaryColor = primaryColor,
                        placeholder = context.translation["features.friend_notes.placeholder"],
                        content = scopeNotes,
                        setContent = { scopeNotes = it }
                    )
                }

                val linearLayout = LinearLayout(viewGroup.context).apply {
                    orientation = LinearLayout.VERTICAL

                    addView(composerRootView)
                    addView(manageNotesView)
                }

                viewGroup.addView(linearLayout)
            }
        }
    }
}
