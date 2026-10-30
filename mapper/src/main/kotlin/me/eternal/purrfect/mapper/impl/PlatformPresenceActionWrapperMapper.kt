package me.eternal.purrfect.mapper.impl

import me.eternal.purrfect.mapper.AbstractClassMapper
import me.eternal.purrfect.mapper.ext.getClassName

class PlatformPresenceActionWrapperMapper : AbstractClassMapper("PlatformPresenceActionWrapper") {
    val classReference = classReference("class")
    val chatVisibleMethod = string("chatVisibleMethod")
    val chatHiddenMethod = string("chatHiddenMethod")
    val startPeekingMethod = string("startPeekingMethod")
    val typingMethod = string("typingMethod")
    val usingReplyCameraMethod = string("usingReplyCameraMethod")
    val viewingChatMediaMethod = string("viewingChatMediaMethod")

    init {
        mapper {
            classes.firstOrNull { classDef ->
                classDef.fields.any { field ->
                    field.type == "Lcom/snap/presence/PlatformChatVisibleAction;"
                }
            }?.let { classDef ->
                classReference.set(classDef.getClassName())

                classDef.methods.forEach { method ->
                    when (method.parameterTypes.singleOrNull()) {
                        "Lcom/snap/presence/PlatformChatVisibleAction;" -> chatVisibleMethod.set(method.name)
                        "Lcom/snap/presence/PlatformChatHiddenAction;" -> chatHiddenMethod.set(method.name)
                        "Lcom/snap/presence/PlatformStartPeekingAction;" -> startPeekingMethod.set(method.name)
                        "Lcom/snap/presence/PlatformTypingAction;" -> typingMethod.set(method.name)
                        "Lcom/snap/presence/PlatformUsingReplyCameraAction;" -> usingReplyCameraMethod.set(method.name)
                        "Lcom/snap/presence/PlatformViewingChatMediaAction;" -> viewingChatMediaMethod.set(method.name)
                    }
                }
            }
        }
    }
}
