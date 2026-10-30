package me.eternal.purrfect.mapper.impl

import me.eternal.purrfect.mapper.AbstractClassMapper
import me.eternal.purrfect.mapper.ext.getClassName

class ChatEventDispatcherMapper : AbstractClassMapper("ChatEventDispatcher")  {
    val classReference = classReference("class")

    init {
        mapper {
            for (clazz in classes) {
                if (clazz.methods.firstOrNull { it.name == "onChatItemDoubleClickEvent" } == null) continue
                classReference.set(clazz.getClassName())
                return@mapper
            }
        }
    }
}