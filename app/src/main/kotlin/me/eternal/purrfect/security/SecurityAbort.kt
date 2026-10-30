package me.eternal.purrfect.security

import android.os.Process
import kotlin.system.exitProcess

object SecurityAbort {
    fun failFast(): Nothing {
        Process.killProcess(Process.myPid())
        exitProcess(139)
    }
}

