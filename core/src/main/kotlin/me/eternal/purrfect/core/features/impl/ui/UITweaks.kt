package me.eternal.purrfect.core.features.impl.ui

import android.content.res.Resources
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.MarginLayoutParams
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import me.eternal.purrfect.common.Constants
import me.eternal.purrfect.core.event.events.impl.AddViewEvent
import me.eternal.purrfect.core.event.events.impl.BindViewEvent
import me.eternal.purrfect.core.features.Feature
import me.eternal.purrfect.core.ui.children
import me.eternal.purrfect.core.ui.getValdiContext
import me.eternal.purrfect.core.ui.hideViewCompletely
import me.eternal.purrfect.core.ui.onLayoutChange
import me.eternal.purrfect.core.util.dataBuilder
import me.eternal.purrfect.core.util.hook.HookStage
import me.eternal.purrfect.core.util.hook.Hooker
import me.eternal.purrfect.core.util.hook.hook
import me.eternal.purrfect.core.util.ktx.getIdentifier

fun getChatInputBar(event: AddViewEvent): Lazy<ViewGroup?>? {
    if (!event.parent.javaClass.name.endsWith("ChatInputLayout")) return null
    val isViewSwitcher = event.viewClassName.endsWith("ViewSwitcher")

    return lazy {
        // get the first linear layout in the view switcher
        val firstLinearLayout = if (isViewSwitcher) {
            (event.view as ViewGroup).children()
                .firstOrNull { it is LinearLayout } as? ViewGroup ?: return@lazy null
        } else {
            event.view as? ViewGroup ?: return@lazy null
        }
        // get the first linear layout with at least 3 children
        firstLinearLayout.children()
            .firstOrNull { v -> v is LinearLayout && v.childCount > 2 } as? LinearLayout
            ?: return@lazy null
    }
}

class UITweaks : Feature("UITweaks") {
    private val identifierCache = mutableMapOf<String, Int>()
    private var loggedUnreadHintFallback = false
    private val uiElementHider by lazy { SnapchatUiElementHider(context) }

    fun getId(name: String, defType: String): Int {
        return identifierCache.getOrPut("$name:$defType") {
            context.resources.getIdentifier(name, defType)
        }
    }

    private fun getOptionalId(name: String, defType: String): Int {
        return identifierCache.getOrPut("optional:$name:$defType") {
            context.resources.getIdentifier(name, defType, Constants.SNAPCHAT_PACKAGE_NAME)
        }
    }

    private fun hideStorySection(event: AddViewEvent) {
        val parent = event.parent
        parent.visibility = View.GONE
        val marginLayoutParams = parent.layoutParams as MarginLayoutParams
        marginLayoutParams.setMargins(-99999, -99999, -99999, -99999)
        event.canceled = true
    }

    private fun hideView(view: View) {
        view.apply {
            visibility = View.GONE
            post {
                isEnabled = false
                visibility = View.GONE
                setWillNotDraw(true)
            }
            addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
                view.post { view.visibility = View.GONE }
            }
        }
    }

    private fun findSpotlightNavTarget(
        event: AddViewEvent,
        spotlightNavIds: Set<Int>
    ): View? {
        val viewChain = buildList {
            var current: View? = event.view
            repeat(5) {
                current ?: return@repeat
                add(current!!)
                current = current?.parent as? View
            }
        }

        // Only check strictly numeric IDs to avoid expensive getResourceEntryName() calls
        // which flood the system with 'Invalid resource ID' exceptions on obfuscated views.
        return viewChain.firstOrNull { it.id in spotlightNavIds }
    }

    private fun collectTextLabels(current: View, depth: Int = 0, maxDepth: Int = 2): List<String> {
        if (depth > maxDepth) return emptyList()

        val ownText = listOfNotNull(
            current.contentDescription?.toString(),
            (current as? TextView)?.text?.toString()
        ).filter { it.isNotBlank() }

        if (current !is ViewGroup) return ownText

        return ownText + current.children().flatMap { child ->
            collectTextLabels(child, depth + 1, maxDepth)
        }
    }

    private fun findSpotlightHeaderTabsTarget(view: View): View? {
        fun isHeaderMarkerText(value: String): Boolean {
            val lower = value.lowercase()
            return lower.contains("spotlight") || lower.contains("discover") || lower.contains("following")
        }

        val candidateChain = buildList {
            var current: View? = view
            repeat(6) {
                current ?: return@repeat
                add(current!!)
                current = current?.parent as? View
            }
        }

        candidateChain.forEach { candidate ->
            val group = candidate as? ViewGroup ?: return@forEach
            if (group.childCount !in 2..4) return@forEach

            val directMarkedChildren = group.children().count { child ->
                collectTextLabels(child).any(::isHeaderMarkerText)
            }

            if (directMarkedChildren < 2) return@forEach

            val texts = collectTextLabels(group)
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()

            val hasSpotlightOrDiscover = texts.any {
                val lower = it.lowercase()
                lower.contains("spotlight") || lower.contains("discover")
            }
            val hasFollowing = texts.any { it.lowercase().contains("following") }

            if (hasSpotlightOrDiscover && hasFollowing) {
                return group
            }
        }

        return null
    }

    private fun findUnreadHintTarget(event: AddViewEvent, legacyUnreadHintButtonId: Int): View? {
        val viewChain = buildList {
            var current: View? = event.view
            repeat(7) {
                current ?: return@repeat
                add(current!!)
                current = current?.parent as? View
            }
        }

        if (legacyUnreadHintButtonId != 0) {
            viewChain.firstOrNull { it.id == legacyUnreadHintButtonId }?.let { return it }
        }

        return viewChain
            .filter { isUnreadHintCandidate(it) }
            .lastOrNull()
            ?.also {
                if (!loggedUnreadHintFallback) {
                    context.log.verbose("Hiding unread chat hint using label fallback")
                    loggedUnreadHintFallback = true
                }
            }
    }

    private fun isUnreadHintCandidate(view: View): Boolean {
        val labels = collectTextLabels(view, maxDepth = 3)
            .map { it.trim().replace(Regex("\\s+"), " ") }
            .filter { it.isNotBlank() }

        if (labels.none(::isUnreadHintLabel)) return false

        val density = context.resources.displayMetrics.density
        val screenWidth = context.resources.displayMetrics.widthPixels
        val viewWidth = view.width.takeIf { it > 0 }
            ?: view.measuredWidth.takeIf { it > 0 }
            ?: view.layoutParams?.width?.takeIf { it > 0 }
        val viewHeight = view.height.takeIf { it > 0 }
            ?: view.measuredHeight.takeIf { it > 0 }
            ?: view.layoutParams?.height?.takeIf { it > 0 }

        val maxHintWidth = (screenWidth * 0.80f).toInt()
        val maxHintHeight = (96 * density).toInt()

        return (viewWidth == null || viewWidth <= maxHintWidth) &&
            (viewHeight == null || viewHeight <= maxHintHeight)
    }

    private fun isUnreadHintLabel(value: String): Boolean {
        val lower = value.lowercase()
        return lower == "more new chats" ||
            lower == "new chats" ||
            lower == "unread chat" ||
            lower == "unread chats" ||
            (lower.contains("more") && lower.contains("new") && lower.contains("chat")) ||
            (lower.contains("unread") &&
                lower.contains("chat") &&
                (lower.contains("hint") || lower.contains("jump") || lower.contains("button")))
    }

    private fun onActivityCreate() {
        val blockAds by context.config.global.blockAds
        val hiddenElements by context.config.userInterface.hideUiComponents
        val hideStorySuggestions by context.config.userInterface.hideStorySuggestions
        val disableSpotlight by context.config.userInterface.disableSpotlight
        val isImmersiveCamera by context.config.camera.immersiveCameraPreview

        val displayMetrics = context.resources.displayMetrics
        val deviceAspectRatio = displayMetrics.widthPixels.toFloat() / displayMetrics.heightPixels.toFloat()

        val chatNoteRecordButton by lazy { getOptionalId("chat_note_record_button", "id") }
        val unreadHintButton by lazy { getOptionalId("unread_hint_button", "id") }
        val spotlightNavIds by lazy {
            listOf(
                getOptionalId("hova_nav_spotlight", "id"),
                getOptionalId("ngs_hova_nav_spotlight", "id"),
                getOptionalId("hova_nav_spotlight_tab", "id"),
                getOptionalId("hova_nav_spotlight_button", "id"),
                getOptionalId("hova_nav_discover", "id"),
                getOptionalId("ngs_hova_nav_discover", "id"),
                getOptionalId("hova_nav_discover_tab", "id"),
                getOptionalId("hova_nav_discover_button", "id")
            ).filter { it != 0 }.toSet()
        }

        Resources::class.java.methods.first { it.name == "getDimensionPixelSize" }.hook(
            HookStage.AFTER,
            { isImmersiveCamera }
        ) { param ->
            val id = param.arg<Int>(0)
            if (
                id == getId("capri_viewfinder_default_corner_radius", "dimen") ||
                id == getId("ngs_hova_nav_larger_camera_button_size", "dimen")
            ) {
                param.setResult(0)
            }
        }

        context.event.subscribe(BindViewEvent::class, { hideStorySuggestions.isNotEmpty() }) { event ->
            if (event.view is FrameLayout) {
                fun removeView() {
                    event.view.layoutParams = event.view.layoutParams?.apply {
                        width = 0
                        height = 0
                    } ?: return
                    event.view.visibility = View.GONE
                }

                val viewModelString = event.prevModel.toString()
                
                // 1. Precise Story Suggestion Hiding (O(1) UI String Match)
                // This replaces the dangerous SQL injection in RequerySqlite.
                if (hideStorySuggestions.contains("hide_suggested_friend_stories")) {
                    val isSuggestedStory = viewModelString.let {
                        it.startsWith("StoryCarouselItemViewModel") && 
                        (it.contains("suggested") || it.contains("mutual") || it.contains("friendOfFriend"))
                    }
                    if (isSuggestedStory) {
                        removeView()
                        return@subscribe
                    }
                }

                // 2. Hide My Stories
                val isMyStory by lazy {
                    viewModelString.let {
                        it.startsWith("StoryCarouselItemViewModel") && it.contains("storyId=")
                    }
                }

                if (hideStorySuggestions.contains("hide_my_stories") && isMyStory) {
                    removeView()
                    return@subscribe
                }
            }
        }

        context.event.subscribe(BindViewEvent::class, { disableSpotlight }) { event ->
            findSpotlightHeaderTabsTarget(event.view)?.hideViewCompletely()
        }

        context.event.subscribe(AddViewEvent::class, {
            blockAds || disableSpotlight || isImmersiveCamera || hiddenElements.contains("hide_unread_chat_hint")
        }) { event ->
            val viewId = event.view.id
            val view = event.view

            if (blockAds && viewId == getId("df_promoted_story", "id")) {
                hideStorySection(event)
            }

            if (disableSpotlight) {
                findSpotlightNavTarget(event, spotlightNavIds)?.let { targetView ->
                    targetView.hideViewCompletely()
                    if (targetView !== view) {
                        view.hideViewCompletely()
                    }
                    event.canceled = true
                    return@subscribe
                }
            }

            if (isImmersiveCamera) {
                if (view.id == getId("edits_container", "id")) {
                    Hooker.hookObjectMethod(View::class.java, view, "layout", HookStage.BEFORE) {
                        val width = it.arg(2) as Int
                        val realHeight = (width / deviceAspectRatio).toInt()
                        it.setArg(3, realHeight)
                    }
                }
                if (view.id == getId("full_screen_surface_view", "id")) {
                    Hooker.hookObjectMethod(View::class.java, view, "layout", HookStage.BEFORE) {
                        it.setArg(1, 1)
                        it.setArg(3, displayMetrics.heightPixels)
                    }
                }
            }

            if (
                hiddenElements.contains("hide_billboard_prompt") &&
                event.parent.javaClass.name.endsWith("BillboardFeedHeaderPromptComponent")
            ) {
                hideView(event.parent)
                view.getValdiContext()?.componentContext?.get()?.dataBuilder {
                    val dismissFunction = get<Any>("_onDismiss") ?: return@subscribe
                    dismissFunction.javaClass.getMethod("invoke").invoke(dismissFunction)
                }
            }

            if (
                event.parent.javaClass.name.endsWith("ConstraintLayout") &&
                event.view is LinearLayout &&
                hiddenElements.contains("hide_map_reactions")
            ) {
                val viewGroup = event.view as ViewGroup
                val children = viewGroup.children()

                // hide image views in the reaction bar
                if (children.takeIf { it.count() == 5 }?.all { it.javaClass.name.endsWith("SnapImageView") } == true) {
                    children.forEach { imageView ->
                        imageView.hideViewCompletely()
                    }
                }
            }

            if (
                event.parent.javaClass.name.endsWith("PreviewBottomToolbarView") &&
                hiddenElements.contains("hide_post_to_story_buttons")
            ) {
                if (event.parent.childCount == 1) {
                    event.view.hideViewCompletely()
                }
            }

            if (viewId == getId("send_btn", "id") && hiddenElements.contains("hide_post_to_story_buttons")) {
                // hide previous view
                if (event.parent.childCount > 0) {
                    val lastChild = event.parent.getChildAt(event.parent.childCount - 1)
                        ?.takeIf { it is LinearLayout } ?: return@subscribe
                    context.log.verbose("Hiding post to story button")
                    lastChild.hideViewCompletely()
                }
            }

            getChatInputBar(event)?.let { lazyChatInputBar ->
                val chatInputBar by lazyChatInputBar

                if (hiddenElements.contains("hide_live_location_share_button")) {
                    chatInputBar?.onLayoutChange {
                        chatInputBar!!.children()
                            .lastOrNull {
                                // Only check for nameless AppCompatImageButton (typical of live location button)
                                // This avoids the getResourceName() loop that causes lag.
                                it.javaClass.name.endsWith("AppCompatImageButton") && it.id == View.NO_ID
                            }
                            ?.hideViewCompletely()
                    }
                }

                if (hiddenElements.contains("hide_stickers_button")) {
                    chatInputBar
                        ?.children()
                        ?.lastOrNull { layout ->
                            layout is FrameLayout && layout.children().all {
                                it.javaClass.name.endsWith("SnapImageView")
                            }
                        }
                        ?.hideViewCompletely()
                }
            }

            if (
                hiddenElements.contains("hide_voice_record_button") &&
                chatNoteRecordButton != 0 &&
                viewId == chatNoteRecordButton
            ) {
                view.hideViewCompletely()
            }

            if (hiddenElements.contains("hide_unread_chat_hint")) {
                findUnreadHintTarget(event, unreadHintButton)?.let { targetView ->
                    targetView.hideViewCompletely()
                    if (targetView !== view) {
                        view.hideViewCompletely()
                    }
                    event.canceled = true
                }
            }
        }
    }

    override fun init() {
        uiElementHider.init()
        onNextActivityCreate {
            uiElementHider.onActivityVisible(it, "activity-create")
            onActivityCreate()
        }
    }
}
