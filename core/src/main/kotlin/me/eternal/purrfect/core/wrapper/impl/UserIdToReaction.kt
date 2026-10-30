package me.eternal.purrfect.core.wrapper.impl

import me.eternal.purrfect.core.util.ktx.getObjectField
import me.eternal.purrfect.core.util.ktx.setObjectField
import me.eternal.purrfect.core.wrapper.AbstractWrapper
import org.mozilla.javascript.annotations.JSGetter
import org.mozilla.javascript.annotations.JSSetter

class UserIdToReaction(obj: Any?) : AbstractWrapper(obj) {
    @get:JSGetter @set:JSSetter
    var userId by field("mUserId") { SnapUUID(it) }
    @get:JSGetter @set:JSSetter
    var reactionId get() = (instanceNonNull().getObjectField("mReaction")
        ?.getObjectField("mReactionContent")
        ?.getObjectField("mIntentionType") as Long?) ?: -1
    set(value) {
        instanceNonNull().getObjectField("mReaction")
            ?.getObjectField("mReactionContent")
            ?.setObjectField("mIntentionType", value)
    }
}