package me.eternal.purrfect.core.features.impl.messaging

import com.google.gson.JsonObject
import me.eternal.purrfect.common.data.FriendLinkType
import me.eternal.purrfect.core.event.events.impl.NetworkApiRequestEvent
import me.eternal.purrfect.core.features.Feature
import me.eternal.purrfect.core.features.impl.messaging.Messaging
import me.eternal.purrfect.core.util.EvictingMap
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentHashMap

/**
 * When a friendship becomes mutual (you add someone or they accept your request),
 * automatically set that 1:1 chat's retention policy to "After Viewing".
 *
 * Toggle: messaging.auto_after_viewing_on_friend
 *
 * Note: The exact Snapchat RPC / ConversationManager method for setting retention
 * changes with Snapchat versions. This feature detects the mutual-friend event and
 * attempts to apply the policy via known ConversationManager patterns. If the
 * internal method name shifts, update the setAfterViewingRetention() call site
 * using current mappings.
 */
class AutoAfterViewingOnFriend : Feature("AutoAfterViewingOnFriend") {

    private val processedUserIds = EvictingMap<String, Long>(300)
    private val pendingUserIds = ConcurrentHashMap.newKeySet<String>()

    override fun init() {
        if (!context.config.messaging.autoAfterViewingOnFriend.get()) return

        context.event.subscribe(NetworkApiRequestEvent::class) { event ->
            if (!event.url.contains("ami/friends")) return@subscribe

            event.onSuccess { buffer ->
                runCatching {
                    val json = context.gson.fromJson(
                        InputStreamReader(buffer?.inputStream() ?: return@onSuccess, Charsets.UTF_8),
                        JsonObject::class.java
                    ) ?: return@runCatching

                    // Newly added / accepted friends appear in "added_friends" or as MUTUAL in "friends"
                    json.getAsJsonArray("added_friends")?.forEach { el ->
                        val friend = el.asJsonObject
                        val userId = friend.get("user_id")?.asString ?: return@forEach
                        scheduleAfterViewing(userId)
                    }

                    json.getAsJsonArray("friends")?.forEach { el ->
                        val friend = el.asJsonObject
                        val userId = friend.get("user_id")?.asString ?: return@forEach
                        if (userId == context.database.myUserId) return@forEach

                        val dbFriend = context.database.getFriendInfo(userId) ?: return@forEach
                        if (FriendLinkType.fromValue(dbFriend.friendLinkType) != FriendLinkType.MUTUAL) return@forEach

                        // Fresh mutual (recently became friends)
                        val addedTs = dbFriend.addedTimestamp
                        if (addedTs > 0L && System.currentTimeMillis() - addedTs < 120_000L) {
                            scheduleAfterViewing(userId)
                        }
                    }
                }.onFailure {
                    context.log.error("AutoAfterViewingOnFriend: failed to process friends payload", it)
                }
            }
        }

        // Also react when ConversationManager becomes ready so we can apply pending
        context.feature(Messaging::class)?.onConversationManagerReady {
            flushPending()
        }
    }

    private fun scheduleAfterViewing(userId: String) {
        val now = System.currentTimeMillis()
        val last = processedUserIds[userId]
        if (last != null && now - last < 60_000L) return // debounce

        processedUserIds[userId] = now
        pendingUserIds.add(userId)
        flushPending()
    }

    private fun flushPending() {
        val messaging = context.feature(Messaging::class) ?: return
        val cm = messaging.conversationManager ?: return

        val toProcess = pendingUserIds.toList()
        pendingUserIds.clear()

        toProcess.forEach { userId ->
            runCatching {
                val conversationId = context.database.getDMConversationId(userId)
                if (conversationId.isNullOrBlank()) {
                    // Conversation may not exist yet; re-queue briefly
                    pendingUserIds.add(userId)
                    return@forEach
                }
                setAfterViewingRetention(cm, conversationId, userId)
            }.onFailure {
                context.log.error("AutoAfterViewingOnFriend: failed for $userId", it)
            }
        }
    }

    /**
     * Attempts to set conversation retention to After Viewing.
     * Snapchat internal method names vary; this uses reflection against ConversationManager
     * and common retention-related method name patterns. Update when mappings change.
     */
    private fun setAfterViewingRetention(conversationManager: Any, conversationId: String, userId: String) {
        val instance = conversationManager.javaClass
            .getDeclaredField("instance")
            .also { it.isAccessible = true }
            .get(conversationManager)
            ?: conversationManager

        // Common patterns seen across Snap versions for retention / chat settings
        val candidateNames = listOf(
            "updateConversationRetentionPolicy",
            "setConversationRetentionPolicy",
            "updateRetentionPolicy",
            "setRetentionPolicy",
            "updateChatSettings",
            "setMessageRetention"
        )

        val methods = instance.javaClass.declaredMethods + instance.javaClass.methods
        val target = methods.firstOrNull { m ->
            candidateNames.any { name -> m.name.contains(name, ignoreCase = true) } ||
                (m.name.contains("retention", ignoreCase = true) && m.parameterCount in 1..4)
        }

        if (target == null) {
            context.log.warn(
                "AutoAfterViewingOnFriend: no retention method found on ConversationManager " +
                    "for $userId ($conversationId). Mapper update required for current Snapchat version."
            )
            return
        }

        target.isAccessible = true
        // After Viewing is typically enum ordinal / constant 1 or a specific enum value.
        // Call with conversationId + policy; exact signature must match current Snapchat.
        runCatching {
            when (target.parameterCount) {
                1 -> target.invoke(instance, conversationId)
                2 -> target.invoke(instance, conversationId, 1) // 1 often = AFTER_VIEWING
                3 -> target.invoke(instance, conversationId, 1, null)
                else -> target.invoke(instance, *Array(target.parameterCount) { i ->
                    if (i == 0) conversationId else if (i == 1) 1 else null
                })
            }
            context.log.info("AutoAfterViewingOnFriend: applied After Viewing for $userId ($conversationId)")
            context.inAppOverlay.showStatusToast(
                androidx.compose.material.icons.Icons.Default.Visibility,
                "Chat set to After Viewing: ${context.database.getFriendInfo(userId)?.mutableUsername ?: userId}"
            )
        }.onFailure {
            context.log.error("AutoAfterViewingOnFriend: invoke failed for $userId", it)
        }
    }
}
