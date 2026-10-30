package me.eternal.purrfect.core.wrapper.impl.valdi

import me.eternal.purrfect.core.Purrfect
import me.eternal.purrfect.core.wrapper.AbstractWrapper

class ValdiFunction(obj: Any): AbstractWrapper(obj) {
    private val performMethod by lazy {
        instanceNonNull().javaClass.getMethod(
            "perform",
            Purrfect.classCache.valdiMarshaller
        )
    }

    fun perform(valdiMarshaller: ValdiMarshaller): Boolean {
        return performMethod.invoke(instanceNonNull(), valdiMarshaller.instanceNonNull()) as Boolean
    }
}
