package me.eternal.purrfect.mapper.impl

import me.eternal.purrfect.mapper.AbstractClassMapper
import me.eternal.purrfect.mapper.ext.findConstString
import me.eternal.purrfect.mapper.ext.getClassName
import me.eternal.purrfect.mapper.ext.isAbstract
import me.eternal.purrfect.mapper.ext.searchNextFieldReference

class DefaultMediaItemMapper : AbstractClassMapper("DefaultMediaItem") {
    val cameraRollMediaId = classReference("cameraRollMediaIdClass")
    val durationMsField = string("durationMsField")
    val defaultMediaItemClass = classReference("defaultMediaItemClass")
    val defaultMediaItemDurationMsField = string("defaultMediaItemDurationMsField")

    val defaultMediaItemClass2 = classReference("defaultMediaItemClass2")
    val defaultMediaItemDurationMsField2 = string("defaultMediaItemDurationMsField2")

    init {
        mapper {
            for (clazz in classes) {
                if (clazz.methods.find { it.name == "toString" }?.implementation?.findConstString("CameraRollMediaId", contains = true) != true) {
                    continue
                }
                val durationMsDexField = clazz.fields.firstOrNull { it.type == "J" } ?: continue

                cameraRollMediaId.set(clazz.getClassName())
                durationMsField.set(durationMsDexField.name)
                return@mapper
            }
        }

        arrayOf(
            "metadata" to { classRef: String, fieldName: String ->
                defaultMediaItemClass.set(classRef)
                defaultMediaItemDurationMsField.set(fieldName)
            },
            "location" to { classRef: String, fieldName: String ->
                defaultMediaItemClass2.set(classRef)
                defaultMediaItemDurationMsField2.set(fieldName)
            }
        ).forEach { (keyword, setter) ->
            mapper {
                for (clazz in classes) {
                    val superClass = getClass(clazz.superclass) ?: continue

                    if (!superClass.isAbstract() || superClass.interfaces.isEmpty() || superClass.interfaces[0] != "Ljava/lang/Comparable;") continue
                    if (clazz.methods.none { it.returnType == "Landroid/net/Uri;" }) continue

                    val durationInMillisDexField = clazz.methods.firstOrNull { it.name == "toString" }?.implementation?.takeIf {
                        it.findConstString(keyword, contains = true)
                    }?.searchNextFieldReference("durationInMillis", contains = true) ?: continue

                    setter(clazz.getClassName(), durationInMillisDexField.name)
                    return@mapper
                }
            }
        }
    }
}