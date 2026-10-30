package me.eternal.purrfect.mapper.impl

import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import me.eternal.purrfect.mapper.AbstractClassMapper
import me.eternal.purrfect.mapper.ext.getClassName
import me.eternal.purrfect.mapper.ext.getSuperClassName
import me.eternal.purrfect.mapper.ext.isFinal

class CallbackMapper : AbstractClassMapper(MAPPER_NAME) {
    val callbacks = map(CALLBACKS_KEY)

    init {
        mapper {
            val callbackClasses = classes.filter { clazz ->
                if (clazz.superclass == null) return@filter false

                val superclassName = clazz.getSuperClassName()!!
                if ((!superclassName.endsWith("Callback") && !superclassName.endsWith("Delegate") && !superclassName.endsWith("EventHandler"))
                    || superclassName.endsWith("\$Callback")) return@filter false

                if (clazz.getClassName().endsWith("\$CppProxy")) return@filter false

                // ignore dummy ContentCallback classes
                if (superclassName.endsWith("ContentCallback") && clazz.methods.none { method ->
                    method.name == "handleContentResult" &&
                    method.implementation?.instructions?.firstOrNull { instruction ->
                        instruction is Instruction35c && (instruction.reference as? MethodReference)?.name == "getBoltContentId"
                    } != null
                }) return@filter false

                val superClass = getClass(clazz.superclass) ?: return@filter false
                !superClass.isFinal()
            }.map {
                it.getSuperClassName()!!.substringAfterLast("/") to it.getClassName()
            }

            callbacks.get()?.putAll(callbackClasses)
        }
    }

    companion object {
        const val MAPPER_NAME = "Callbacks"
        const val CALLBACKS_KEY = "callbacks"
    }
}
