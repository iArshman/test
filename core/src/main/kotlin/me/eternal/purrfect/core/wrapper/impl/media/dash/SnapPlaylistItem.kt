package me.eternal.purrfect.core.wrapper.impl.media.dash

import me.eternal.purrfect.core.util.ktx.findFieldNamesByType
import me.eternal.purrfect.core.util.ktx.getObjectField
import me.eternal.purrfect.core.wrapper.AbstractWrapper

class SnapPlaylistItem (obj: Any?) : AbstractWrapper(obj) {
    private val longFields by lazy {
        instanceNonNull().findFieldNamesByType(Long::class.javaPrimitiveType ?: Long::class.java)
    }
    val snapId by lazy {
        instanceNonNull().getObjectField(longFields.first()) as Long
    }
}
