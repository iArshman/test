package me.eternal.purrfect.mapper.impl

import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import me.eternal.purrfect.mapper.AbstractClassMapper
import me.eternal.purrfect.mapper.ext.findConstString
import me.eternal.purrfect.mapper.ext.getClassName

class OperaViewerParamsMapper : AbstractClassMapper("OperaViewerParams") {
    val classReference = classReference("class")

    private fun Method.hasHashMapReference(methodName: String) = implementation?.instructions?.any {
        val instruction = it as? Instruction35c ?: return@any false
        val reference = instruction.reference as? MethodReference ?: return@any false
        reference.name == methodName && reference.definingClass == "Ljava/util/concurrent/ConcurrentHashMap;"
    } == true

    init {
        mapper {
            for (classDef in classes) {
                if (classDef.methods.firstOrNull { it.name == "toString" }?.implementation?.findConstString("Params") != true) continue

                classDef.methods.firstOrNull { method ->
                    method.returnType == "Ljava/lang/Object;" &&
                    method.hasHashMapReference("get")
                } ?: continue
                classDef.methods.firstOrNull { method ->
                    method.returnType == "V" &&
                    method.hasHashMapReference("remove")
                } ?: continue

                classReference.set(classDef.getClassName())
                return@mapper
            }
        }
    }
}
