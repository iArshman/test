package me.eternal.purrfect.core.features.impl.experiments

import me.eternal.purrfect.common.Constants
import me.eternal.purrfect.core.event.events.impl.UnaryCallEvent
import me.eternal.purrfect.core.features.Feature
import java.nio.ByteBuffer

class ContextMenuFix: Feature("Context Menu Fix") {
    override fun init() {
        if (!context.config.experimental.contextMenuFix.get()) return
        if (context.androidContext.packageName != Constants.SNAPCHAT_PACKAGE_NAME) return
        context.event.subscribe(UnaryCallEvent::class) { event ->
            if (event.uri == "/snapchat.maps.device.MapDevice/IsPrimary") {
                 event.canceled = true
                 val unaryEventHandler = event.adapter.arg<Any>(3)
                 runCatching {
                     unaryEventHandler::class.java.methods.first { it.name == "onEvent" }.invoke(unaryEventHandler, ByteBuffer.wrap(
                         byteArrayOf(8, 1)
                     ), null)
                 }.onFailure {
                     context.log.error(null, it)
                 }
             }
        }
    }
}