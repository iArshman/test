package me.eternal.purrfect.mapper.impl

import me.eternal.purrfect.mapper.AbstractClassMapper
import me.eternal.purrfect.mapper.ext.findConstString
import me.eternal.purrfect.mapper.ext.getClassName

class ScoreUpdateMapper : AbstractClassMapper("ScoreUpdate") {
    val classReference = classReference("class")

    init {
        mapper {
            for (classDef in classes) {
                val toStringMethod = classDef.methods.firstOrNull {
                    it.name == "toString"
                } ?: continue
                if (classDef.methods.none {
                    it.name == "<init>" &&
                    it.parameterTypes.size > 4
                }) continue

                if (toStringMethod.implementation?.findConstString("selectFriendUserScoresNeedToUpdate", contains = true) != true) continue

                classReference.set(classDef.getClassName())
                return@mapper
            }
        }
    }
}