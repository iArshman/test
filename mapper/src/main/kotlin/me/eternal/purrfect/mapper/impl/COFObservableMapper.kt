package me.eternal.purrfect.mapper.impl

import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import me.eternal.purrfect.mapper.AbstractClassMapper
import me.eternal.purrfect.mapper.ext.getClassName

class COFObservableMapper: AbstractClassMapper("COFObservable") {
    val classReference = classReference("class")
    val getBooleanObservable = string("getBooleanObservable")

    init {
        mapper {
            for (classDef in classes) {
                if (classDef.interfaces.isEmpty()) continue
                if (classDef.methods.none { it.name == "dispose" }) continue

                val getBooleanObservableDexMethod = classDef.methods.firstOrNull { method ->
                    method.parameterTypes.size == 2 &&
                    method.parameterTypes[0] == "Ljava/lang/String;" &&
                    getClass(method.returnType)?.methods?.any { it.name == "mergeFrom" } == true
                } ?: continue

                if (getBooleanObservableDexMethod.implementation?.instructions?.any { instruction ->
                    instruction is Instruction35c && (instruction.reference as? MethodReference)?.name == "elapsedRealtime"
                } == true) {
                    getBooleanObservable.set(getBooleanObservableDexMethod.name)
                    classReference.set(classDef.getClassName())
                    return@mapper
                }
            }
        }
    }
}