package me.eternal.purrfect.core.wrapper.impl.media.opera

import me.eternal.purrfect.common.util.ktx.findFieldsToString
import me.eternal.purrfect.core.wrapper.AbstractWrapper

class Layer(obj: Any?) : AbstractWrapper(obj) {
    val paramMap: ParamMap
        get() {
            val layerControllerField = instanceNonNull()::class.java.findFieldsToString(instance, once = true) { _, value ->
                value.contains("OperaPageModel")
            }.firstOrNull() ?: throw RuntimeException("Could not find layerController field")

            val paramsMapHashMap = layerControllerField.type.findFieldsToString(layerControllerField[instance], once = true) { _, value ->
                value.contains("OperaPageModel")
            }.firstOrNull() ?: throw RuntimeException("Could not find paramsMap field")

            return ParamMap(paramsMapHashMap[layerControllerField[instance]]!!)
        }
}
