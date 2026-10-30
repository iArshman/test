package me.eternal.purrfect.core.action

import me.eternal.purrfect.core.ModContext
import java.io.File

abstract class AbstractAction{
    lateinit var context: ModContext

    /**
     * called when the action is triggered
     */
    open fun run() {}

    open fun onActivityCreate() {}

    protected open fun deleteRecursively(parent: File?) {
        if (parent == null) return
        if (parent.isDirectory) for (child in parent.listFiles()!!) deleteRecursively(
            child
        )
        if (parent.exists() && (parent.isFile || parent.isDirectory)) {
            parent.delete()
        }
    }
}