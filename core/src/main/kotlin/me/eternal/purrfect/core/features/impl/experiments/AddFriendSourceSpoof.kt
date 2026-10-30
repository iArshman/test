package me.eternal.purrfect.core.features.impl.experiments

import me.eternal.purrfect.common.data.FriendAddSource
import me.eternal.purrfect.common.util.protobuf.ProtoEditor
import me.eternal.purrfect.common.util.protobuf.ProtoReader
import me.eternal.purrfect.core.event.events.impl.UnaryCallEvent
import me.eternal.purrfect.core.features.Feature
import me.eternal.purrfect.core.util.hook.HookStage
import me.eternal.purrfect.core.util.hook.hookConstructor
import me.eternal.purrfect.mapper.impl.FriendRelationshipChangerMapper

class AddFriendSourceSpoof : Feature("AddFriendSourceSpoof") {
    var friendRelationshipChangerInstance: Any? = null
        private set

    override fun init() {
        onNextActivityCreate {
            context.mappings.useMapper(FriendRelationshipChangerMapper::class) {
                classReference.get()?.hookConstructor(HookStage.AFTER) { param ->
                    friendRelationshipChangerInstance = param.thisObject()
                }
            }

            context.event.subscribe(UnaryCallEvent::class) { event ->
                if (event.uri != "/snapchat.friending.server.FriendAction/AddFriends") return@subscribe
                val spoofedSource = context.config.experimental.addFriendSourceSpoof.getNullable() ?: return@subscribe
                
                event.buffer = ProtoEditor(event.buffer).apply {
                    edit {
                        fun setPage(value: String) {
                            remove(1)
                            addString(1, value)
                        }

                        fun setSource(source: FriendAddSource) {
                            val field2Exists = getOrNull(2) != null
                            
                            if (field2Exists) {
                                editEach(2) {
                                    val field1Data = getOrNull(1)
                                    clear()
                                    if (field1Data != null) {
                                        field1Data.forEach { wire ->
                                            addWire(wire)
                                        }
                                    }
                                    addVarInt(2, source.id)
                                }
                            } else {
                                add(2) { addVarInt(2, source.id) }
                            }
                        }

                        val mapping: Map<String, Pair<String?, FriendAddSource>> = mapOf(
                            "added_by_group_chat" to Pair("group_profile", FriendAddSource.GROUP_CHAT),
                            "added_by_username" to Pair("search", FriendAddSource.USERNAME),
                            "added_by_qr_code" to Pair("scan_snapcode", FriendAddSource.QR_CODE),
                            "added_by_mention" to Pair("context_card", FriendAddSource.MENTION),
                            "added_by_community" to Pair("profile", FriendAddSource.COMMUNITY),
                            "added_by_quick_add" to Pair("add_friends_button_on_top_bar_on_friends_feed", FriendAddSource.SUGGESTED),
                            "added_by_spotlight" to Pair("spotlight", FriendAddSource.SPOTLIGHT),
                        )
                        mapping[spoofedSource]?.let { (page, source) ->
                            page?.let { setPage(it) }
                            setSource(source)
                        }
                        
                        remove(3)
                    }
                }.toByteArray()
            }
        }
    }
}
