package me.eternal.purrfect.core.wrapper.impl.media

import android.os.Parcelable
import me.eternal.purrfect.core.util.ktx.getObjectField
import me.eternal.purrfect.core.wrapper.AbstractWrapper
import java.lang.reflect.Field

private fun Class<*>.getDeclaredFieldsRecursive(): List<Field> {
    val list = mutableListOf<Field>()
    var current: Class<*>? = this
    while (current != null && current != Any::class.java && current != java.lang.Object::class.java) {
        list.addAll(current.declaredFields)
        current = current.superclass
    }
    return list
}

class MediaInfo(obj: Any?) : AbstractWrapper(obj) {
    val uri: String
        get() {
            val firstStringUriField = instanceNonNull().javaClass.getDeclaredFieldsRecursive().first { f: Field ->
                f.type == String::class.java
            }.apply { isAccessible = true }
            return firstStringUriField.get(instanceNonNull()) as String
        }

    init {
        instance?.let {
            if (it is List<*>) {
                if (it.isEmpty()) {
                    throw RuntimeException("MediaInfo is empty")
                }
                
                // Select highest quality media by comparing width * height
                // Use explicit field name search to avoid relying on field order
                instance = it.filterNotNull().maxByOrNull { mediaObj ->
                    runCatching {
                        val fields = mediaObj.javaClass.getDeclaredFieldsRecursive()
                        
                        // Search for width and height fields by name (case-insensitive)
                        // Common patterns: "width", "mWidth", "height", "mHeight"
                        val widthField = fields.find { f -> 
                            f.name.equals("width", ignoreCase = true) ||
                            f.name.equals("mWidth", ignoreCase = true)
                        }
                        val heightField = fields.find { f -> 
                            f.name.equals("height", ignoreCase = true) ||
                            f.name.equals("mHeight", ignoreCase = true)
                        }
                        
                        // Validate fields exist and are integers before calculating resolution
                        if (widthField != null && heightField != null &&
                            (widthField.type == Int::class.javaPrimitiveType || widthField.type == Int::class.java) &&
                            (heightField.type == Int::class.javaPrimitiveType || heightField.type == Int::class.java)) {
                            widthField.isAccessible = true
                            heightField.isAccessible = true
                            widthField.getInt(mediaObj) * heightField.getInt(mediaObj)
                        } else {
                            0
                        }
                    }.getOrDefault(0)
                } ?: it.filterNotNull().firstOrNull() ?: it.firstOrNull()
            }
        }
    }

    val encryption: EncryptionWrapper?
        get() {
            val encryptionAlgorithmField = instanceNonNull().javaClass.getDeclaredFieldsRecursive().firstOrNull { f: Field ->
                f.type.isInterface && Parcelable::class.java.isAssignableFrom(f.type)
            }?.apply { isAccessible = true }
            return encryptionAlgorithmField?.get(instance)?.let { EncryptionWrapper(it) }
        }
}

