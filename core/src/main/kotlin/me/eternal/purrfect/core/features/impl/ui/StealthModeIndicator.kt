package me.eternal.purrfect.core.features.impl.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.Shape
import androidx.core.content.res.use
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.eternal.purrfect.core.event.events.impl.BindViewEvent
import me.eternal.purrfect.core.features.Feature
import me.eternal.purrfect.core.features.impl.spying.StealthMode
import me.eternal.purrfect.core.ui.addForegroundDrawable
import me.eternal.purrfect.core.ui.randomTag
import me.eternal.purrfect.core.ui.removeForegroundDrawable
import me.eternal.purrfect.core.util.EvictingMap
import me.eternal.purrfect.core.util.ktx.getDimens
import me.eternal.purrfect.core.util.ktx.getIdentifier

class StealthModeIndicator : Feature("StealthModeIndicator") {
    private val stealthMode by lazy { context.feature(StealthMode::class) }
    private val listeners = EvictingMap<String, (Boolean) -> Unit>(100)

    inner class UpdateHandler {
        private var fetchJob: Job? = null
        private var listener = { _: Boolean -> }

        private fun requestUpdate(conversationId: String) {
            fetchJob?.cancel()
            fetchJob = context.coroutineScope.launch {
                val isStealth = stealthMode.isAnyStealthEnabled(conversationId)
                withContext(Dispatchers.Main) {
                    listener(isStealth)
                }
            }
        }

        fun subscribe(conversationId: String, onStateChange: (Boolean) -> Unit) {
            listener = onStateChange.also {
                listeners[conversationId] = it
            }
            requestUpdate(conversationId)
        }
    }

    private val stealthModeIndicatorTag = randomTag()

    override fun init() {
        if (!context.config.userInterface.stealthModeIndicator.get()) return

        onNextActivityCreate {
            stealthMode.addStateListener { conversationId, _ ->
                runCatching {
                    listeners[conversationId]?.invoke(stealthMode.isAnyStealthEnabled(conversationId))
                }.onFailure {
                    context.log.error("Failed to update stealth mode indicator", it)
                }
            }

            context.event.subscribe(BindViewEvent::class) { event ->
                fun updateStealthIndicator(isStealth: Boolean = true) {
                    event.view.removeForegroundDrawable("stealthModeIndicator")
                    if (!isStealth || !event.view.isAttachedToWindow) return
                    event.view.addForegroundDrawable("stealthModeIndicator", ShapeDrawable(object : Shape() {
                        override fun draw(canvas: Canvas, paint: Paint) {
                            val secondaryTextSize = context.userInterface.dpToPx(10).toFloat()
                            paint.textSize = secondaryTextSize
                            paint.color = context.userInterface.colorPrimary
                            canvas.drawText(
                                "\uD83D\uDC7B",
                                0f,
                                canvas.height.toFloat() - secondaryTextSize / 2,
                                paint
                            )
                        }
                    }))
                }

                event.friendFeedItem { conversationId ->
                    val updateHandler = event.view.getTag(stealthModeIndicatorTag) as? UpdateHandler ?: run {
                        val handler = UpdateHandler()
                        event.view.setTag(stealthModeIndicatorTag, handler)
                        handler
                    }

                    event.view.post {
                        synchronized(listeners) {
                            updateHandler.subscribe(conversationId) { isStealth ->
                                updateStealthIndicator(isStealth)
                            }
                        }
                    }
                    return@subscribe
                }

                event.view.setTag(stealthModeIndicatorTag, null)
            }
        }
    }
}
