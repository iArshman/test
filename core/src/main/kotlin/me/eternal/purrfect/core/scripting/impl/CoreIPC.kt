package me.eternal.purrfect.core.scripting.impl

import me.eternal.purrfect.bridge.scripting.IPCListener
import me.eternal.purrfect.common.scripting.impl.IPCInterface
import me.eternal.purrfect.common.scripting.impl.Listener

class CoreIPC : IPCInterface() {
    override fun onBroadcast(channel: String, eventName: String, listener: Listener) {
        bridgeAutoReload {
            context.runtime.scripting.registerIPCListener(channel, eventName, object: IPCListener.Stub() {
                override fun onMessage(args: Array<out String?>) {
                    listener(args.toList())
                }
            })
        }
    }

    override fun on(eventName: String, listener: Listener) {
        onBroadcast(context.moduleInfo.name, eventName, listener)
    }

    override fun emit(eventName: String, vararg args: String?): Int {
        return broadcast(context.moduleInfo.name, eventName, *args)
    }

    override fun broadcast(channel: String, eventName: String, vararg args: String?): Int {
        return runCatching { context.runtime.scripting.sendIPCMessage(channel, eventName, args) }.getOrNull() ?: 0
    }
}