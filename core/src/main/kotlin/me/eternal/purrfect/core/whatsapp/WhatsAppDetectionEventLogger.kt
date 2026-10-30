package me.eternal.purrfect.core.whatsapp

import de.robv.android.xposed.XposedBridge
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal object WhatsAppDetectionEventLogger {
    private const val TAG = "PurrfectWA.Detector"
    private const val MAX_QUEUE_SIZE = 1024
    private val started = AtomicBoolean(false)
    private val events = ConcurrentHashMap<String, AtomicInteger>()
    private val queue = LinkedBlockingQueue<String>(MAX_QUEUE_SIZE)

    fun lifecycle(message: String) {
        enqueue(message)
    }

    fun probe(type: String, detail: String, action: String) {
        val key = "$type|$detail|$action".take(768)
        val count = events.getOrPut(key) { AtomicInteger(0) }.incrementAndGet()
        if (!shouldLog(count)) return

        enqueue(
            "WhatsApp detection probe: type=$type action=$action detail=${detail.take(1400)} count=$count"
        )
    }

    private fun shouldLog(count: Int): Boolean {
        return count <= 3 || count == 10 || count == 25 || count == 50 || count % 100 == 0
    }

    private fun enqueue(message: String) {
        XposedBridge.log("[${WhatsAppChannelHooks.TAG}] $message")
        startWorker()
        queue.offer(message.take(2048))
    }

    private fun startWorker() {
        if (!started.compareAndSet(false, true)) return
        Thread {
            while (true) {
                val message = runCatching { queue.take() }.getOrNull() ?: continue
                WhatsAppAppLogWriter.info(null, TAG, message)
            }
        }.apply {
            name = "PurrfectWhatsAppDetectorLog"
            isDaemon = true
            start()
        }
    }
}
