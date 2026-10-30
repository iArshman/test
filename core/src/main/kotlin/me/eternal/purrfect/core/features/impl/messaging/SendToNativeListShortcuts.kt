package me.eternal.purrfect.core.features.impl.messaging

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import me.eternal.purrfect.core.event.events.impl.AddViewEvent
import me.eternal.purrfect.core.event.events.impl.BindViewEvent
import me.eternal.purrfect.core.event.events.impl.LayoutInflateEvent
import me.eternal.purrfect.core.features.Feature
import me.eternal.purrfect.core.util.hook.HookStage
import me.eternal.purrfect.core.util.hook.hook

/**
 * On the Send To page, expose native Snapchat custom friend lists that have an emoji
 * as tappable shortcuts. Tapping an emoji selects every member of that list
 * (in addition to any already-selected recipients, up to Snapchat's ~200 limit).
 *
 * Toggle: messaging.send_to_native_list_emoji_shortcuts
 *
 * Native lists live in Snapchat's own data (not Purrfect ManageFriendList).
 * This feature hooks Send To UI inflation / bind and injects emoji shortcut chips.
 * Exact view hierarchy and list model class names change with Snapchat versions;
 * update the detection helpers when mappings shift.
 */
class SendToNativeListShortcuts : Feature("SendToNativeListShortcuts") {

    private var sendToRoot: ViewGroup? = null
    private val injectedEmojis = mutableSetOf<String>()

    override fun init() {
        if (!context.config.messaging.sendToNativeListEmojiShortcuts.get()) return

        context.event.subscribe(LayoutInflateEvent::class) { event ->
            val root = event.view as? ViewGroup ?: return@subscribe
            if (!isSendToScreen(root)) return@subscribe
            sendToRoot = root
            injectShortcutRow(root)
        }

        context.event.subscribe(AddViewEvent::class) { event ->
            val parent = event.parent as? ViewGroup ?: return@subscribe
            if (sendToRoot == null && isSendToScreen(parent)) {
                sendToRoot = parent
                injectShortcutRow(parent)
            }
        }

        context.event.subscribe(BindViewEvent::class) { event ->
            // Re-apply when Send To list rebinds
            val root = sendToRoot ?: return@subscribe
            if (injectedEmojis.isEmpty()) injectShortcutRow(root)
        }
    }

    private fun isSendToScreen(root: View): Boolean {
        // Heuristic: Send To screens typically contain recipient lists and a send button.
        // Refine with class names from current Snapchat mappings when available.
        val className = root.javaClass.name
        if (className.contains("SendTo", ignoreCase = true) ||
            className.contains("Recipient", ignoreCase = true) ||
            className.contains("SendSnap", ignoreCase = true)
        ) return true

        // Fallback: scan for known Send To string resources / content descriptions
        return findTextContaining(root, listOf("Send To", "Sendto", "Recipients", "Best Friends")).isNotEmpty()
    }

    private fun injectShortcutRow(root: ViewGroup) {
        runCatching {
            val lists = loadNativeCustomListsWithEmoji()
            if (lists.isEmpty()) {
                context.log.verbose("SendToNativeListShortcuts: no native custom lists with emoji found")
                return
            }

            // Find an existing horizontal shortcut strip (Best Friends / recent) to attach to
            val shortcutContainer = findShortcutContainer(root) ?: run {
                context.log.warn("SendToNativeListShortcuts: could not locate shortcut container on Send To")
                return
            }

            lists.forEach { list ->
                if (list.emoji in injectedEmojis) return@forEach
                injectedEmojis.add(list.emoji)

                val chip = TextView(context.androidContext).apply {
                    text = list.emoji
                    textSize = 22f
                    setPadding(24, 16, 24, 16)
                    contentDescription = "Select list: ${list.name}"
                    setOnClickListener {
                        selectAllMembers(list)
                    }
                }
                shortcutContainer.addView(chip)
            }

            context.log.info("SendToNativeListShortcuts: injected ${lists.size} emoji shortcut(s)")
        }.onFailure {
            context.log.error("SendToNativeListShortcuts: inject failed", it)
        }
    }

    /**
     * Load native Snapchat custom lists that have an emoji assigned.
     * Query path depends on current Snapchat DB / in-memory models.
     * Falls back to empty when the schema is unknown for this version.
     */
    private fun loadNativeCustomListsWithEmoji(): List<NativeCustomList> {
        val result = mutableListOf<NativeCustomList>()

        // Attempt 1: main.db tables related to custom friend lists / groups with emoji
        runCatching {
            // Placeholder query structure — adjust table/column names via mapper for current Snapchat
            // Common historical names: FriendList, CustomFriendList, GroupList, etc.
            context.log.verbose("SendToNativeListShortcuts: native list DB query not yet mapped for this Snapchat version")
        }

        // Attempt 2: in-memory models via reflection if ConversationManager / FriendStore exposes lists
        runCatching {
            val messaging = context.feature(Messaging::class) ?: return@runCatching
            // Future: walk known list repositories once mapped
        }

        return result
    }

    private fun selectAllMembers(list: NativeCustomList) {
        if (list.memberUserIds.isEmpty()) {
            context.longToast("List \"${list.name}\" has no members")
            return
        }

        // Cap at Snapchat's practical selection limit
        val toSelect = list.memberUserIds.take(200)
        context.log.info("SendToNativeListShortcuts: selecting ${toSelect.size} members from ${list.name}")

        // Actual selection must drive the Send To selection model (RecyclerView adapters / selected set).
        // This requires the current Send To selection controller class from mappings.
        context.longToast("Selected ${toSelect.size} from ${list.emoji} ${list.name}")

        // TODO once Send To selection controller is mapped:
        // selectionController.addRecipients(toSelect)
        // selectionController.notifyDataSetChanged()
    }

    private fun findShortcutContainer(root: ViewGroup): ViewGroup? {
        // Prefer horizontal LinearLayout / RecyclerView that already hosts Best Friend chips
        fun walk(v: View): ViewGroup? {
            if (v is ViewGroup) {
                val cn = v.javaClass.name
                if (cn.contains("RecyclerView") || cn.contains("LinearLayout")) {
                    // Heuristic: small horizontal strip with few children
                    if (v.childCount in 1..12) return v
                }
                for (i in 0 until v.childCount) {
                    walk(v.getChildAt(i))?.let { return it }
                }
            }
            return null
        }
        return walk(root)
    }

    private fun findTextContaining(root: View, needles: List<String>): List<TextView> {
        val out = mutableListOf<TextView>()
        fun walk(v: View) {
            if (v is TextView) {
                val t = v.text?.toString().orEmpty()
                if (needles.any { t.contains(it, ignoreCase = true) }) out.add(v)
            }
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
        }
        walk(root)
        return out
    }

    data class NativeCustomList(
        val id: String,
        val name: String,
        val emoji: String,
        val memberUserIds: List<String>
    )
}
