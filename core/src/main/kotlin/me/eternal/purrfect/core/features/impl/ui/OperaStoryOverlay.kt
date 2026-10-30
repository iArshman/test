package me.eternal.purrfect.core.features.impl.ui

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import me.eternal.purrfect.core.features.impl.downloader.MediaDownloader
import me.eternal.purrfect.core.util.ktx.vibrateLongPress
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.eternal.purrfect.core.ui.PurrfectOverlayTheme
import me.eternal.purrfect.common.ui.theme.LocalPurrfectSkin
import me.eternal.purrfect.common.ui.createComposeView
import me.eternal.purrfect.common.util.ktx.copyToClipboard
import me.eternal.purrfect.core.event.events.impl.AddViewEvent
import me.eternal.purrfect.core.features.Feature
import me.eternal.purrfect.core.ui.children
import java.lang.ref.WeakReference

private const val STORY_OVERLAY_COMPOSE_TAG = "purrfect_opera_story_overlay_compose"

private fun View.topOnScreen(): Int {
    val loc = IntArray(2)
    getLocationOnScreen(loc)
    return loc[1]
}

private fun ViewGroup.collectViewsWithTag(tag: Any, out: MutableList<View>) {
    if (this.tag == tag) out.add(this)
    for (i in 0 until childCount) {
        val c = getChildAt(i)
        if (c is ViewGroup) c.collectViewsWithTag(tag, out)
        else if (c.tag == tag) out.add(c)
    }
}

private fun findSmallestAncestorWithDuplicateOverlays(anchor: View): List<View>? {
    var node: View? = anchor
    var steps = 0
    while (node != null && steps++ < 24) {
        val g = node as? ViewGroup ?: break
        val found = mutableListOf<View>()
        g.collectViewsWithTag(STORY_OVERLAY_COMPOSE_TAG, found)
        if (found.size >= 2) return found
        node = g.parent as? View
    }
    return null
}

private fun dedupeStoryOverlayComposes(
    overlays: List<View>,
    setStoryFrame: (ViewGroup) -> Unit
) {
    val ours = overlays.filterIsInstance<ComposeView>().filter { it.tag == STORY_OVERLAY_COMPOSE_TAG }
    if (ours.size <= 1) return
    val laidOut = ours.filter { it.isAttachedToWindow && it.width > 0 && it.height > 0 }
    val candidates = if (laidOut.size >= 2) laidOut else ours
    if (candidates.size <= 1) return
    val maxTop = candidates.maxOf { it.topOnScreen() }
    val keep = candidates.filter { it.topOnScreen() == maxTop }.last()
    candidates.filter { it !== keep }.forEach { v ->
        (v.parent as? ViewGroup)?.removeView(v)
    }
    (keep.parent as? ViewGroup)?.let(setStoryFrame)
}

class OperaStoryOverlay : Feature("OperaStoryOverlay") {
    private val overlayState = OperaStoryOverlayState()
    private var storyFrameLayout = WeakReference<ViewGroup>(null)
    private lateinit var snapJump: OperaStorySnapJump

    override fun init() {
        val showCounter = context.config.userInterface.storyCounter.get()
        val showSourceIndicator = context.config.userInterface.storySourceIndicator.get()
        val enableSnapJump = context.config.userInterface.storySnapJump.get()
        val showCaptionText = context.config.userInterface.storyCaptionText.get()
        val collapsibleStoryOverlay = context.config.userInterface.collapsibleStoryOverlay.get()
        val storySnapListDownload = context.config.downloader.storySnapListDownload.get()
        val showDownloadButton = context.config.downloader.operaDownloadButton.get()
        if (!showCounter && !showSourceIndicator && !enableSnapJump && !showCaptionText && !storySnapListDownload && !showDownloadButton) return

        snapJump = OperaStorySnapJump(context, overlayState) { storyFrameLayout.get() }

        context.event.subscribe(AddViewEvent::class) { event ->
            if (event.parent.javaClass.superclass?.name?.endsWith("OpenLayout") == true) {
                val viewGroup = event.view as? ViewGroup ?: return@subscribe

                if (viewGroup.findViewWithTag<View>(STORY_OVERLAY_COMPOSE_TAG) != null) return@subscribe

                val isWrapped = viewGroup is FrameLayout && viewGroup.childCount == 1 && viewGroup.getChildAt(0) is ViewGroup
                val actualLayer = if (isWrapped) viewGroup.getChildAt(0) as ViewGroup else viewGroup

                if (actualLayer.javaClass.name.endsWith("ScalableCircleMaskFrameLayout")) return@subscribe

                val displayMetrics = event.parent.resources.displayMetrics
                val parentW = event.parent.width
                val parentH = event.parent.height
                if (parentW > 0 && parentH > 0 &&
                    parentW < displayMetrics.widthPixels * 0.55f &&
                    parentH < displayMetrics.heightPixels * 0.42f) {
                    return@subscribe
                }

                if (actualLayer.childCount > 0 && !actualLayer.javaClass.name.contains("OperaShapeView")) {
                    storyFrameLayout = WeakReference(viewGroup)

                    val composeView = createComposeView(viewGroup.context) {
                        PurrfectOverlayTheme(modContext = context) {
                            val skin = LocalPurrfectSkin.current
                            val counterText = overlayState.counterState.value
                            val source = overlayState.sourceState.value
                            val snapSource = overlayState.snapSourceState.value
                            val playlistV2 = overlayState.playlistV2GroupState.value
                            val inSpotlight = overlayState.isInSpotlightContext.value
                            val mapStoryEligible = overlayState.mapStoryOverlayEligible.value
                            val showCounterContext = shouldShowStoryCounterOverlay(snapSource, playlistV2, inSpotlight, mapStoryEligible)
                            val hasCounter = showCounter && counterText.isNotEmpty() && showCounterContext
                            val hasSource = showSourceIndicator && source.isNotEmpty() && showCounterContext
                            val currentIdx = overlayState.currentIndexState.intValue
                            val totalCount = overlayState.totalCountState.intValue
                            val hasSnapJump = enableSnapJump && totalCount > 1 && showCounterContext
                            val captionText = overlayState.captionTextState.value
                            val hasCaptionText = showCaptionText && captionText.isNotEmpty() && showCounterContext
                            var showJumpDialog by remember { mutableStateOf(false) }

                            val isDownloadButtonEnabled = context.config.downloader.operaDownloadButton.get()
                            val isInConversation = overlayState.isInConversationState.value
                            val overlayDownloadVisible = isDownloadButtonEnabled && showCounterContext &&
                                snapSource != "SINGLE_SNAP_STORY" && snapSource != "SPOTLIGHT" && snapSource != "PUBLIC_STORY" && !isInConversation && !inSpotlight
                            val showCollapsibleToggle = collapsibleStoryOverlay && showCounterContext && !inSpotlight

                            if (hasCounter || hasSource || hasSnapJump || hasCaptionText || overlayDownloadVisible) {
                                Column(
                                    horizontalAlignment = Alignment.End
                                ) {
                                    var overlayExpanded by remember { mutableStateOf(false) }
                                    val hasInfoRow = hasCounter || hasSource || hasSnapJump || hasCaptionText
                                    val panelOrigin = TransformOrigin(1f, 0f)
                                    val panelScaleSpring = spring<Float>(
                                        dampingRatio = Spring.DampingRatioNoBouncy,
                                        stiffness = Spring.StiffnessMediumLow
                                    )
                                    val panelEnter = fadeIn(
                                        animationSpec = tween(
                                            durationMillis = 280,
                                            delayMillis = 40,
                                            easing = FastOutSlowInEasing
                                        )
                                    ) + scaleIn(
                                        initialScale = 0.86f,
                                        transformOrigin = panelOrigin,
                                        animationSpec = panelScaleSpring
                                    ) + slideInVertically(
                                        initialOffsetY = { (-(it / 5)).coerceAtLeast(-48) },
                                        animationSpec = tween(
                                            durationMillis = 320,
                                            delayMillis = 20,
                                            easing = FastOutSlowInEasing
                                        )
                                    )
                                    val panelExit = fadeOut(
                                        animationSpec = tween(180, easing = FastOutLinearInEasing)
                                    ) + scaleOut(
                                        targetScale = 0.9f,
                                        transformOrigin = panelOrigin,
                                        animationSpec = tween(200, easing = FastOutLinearInEasing)
                                    ) + slideOutVertically(
                                        targetOffsetY = { (-(it / 6)).coerceAtLeast(-40) },
                                        animationSpec = tween(200, easing = FastOutLinearInEasing)
                                    )

                                    if (showCollapsibleToggle) {
                                        val chevronRotation by animateFloatAsState(
                                            targetValue = if (overlayExpanded) 180f else 0f,
                                            animationSpec = spring(
                                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                                stiffness = Spring.StiffnessMedium
                                            ),
                                            label = "story_overlay_chevron"
                                        )
                                        val toggleScale by animateFloatAsState(
                                            targetValue = if (overlayExpanded) 1.06f else 1f,
                                            animationSpec = spring(
                                                dampingRatio = Spring.DampingRatioNoBouncy,
                                                stiffness = Spring.StiffnessMedium
                                            ),
                                            label = "story_overlay_toggle_scale"
                                        )
                                        Box(
                                            modifier = Modifier
                                                .scale(toggleScale)
                                                .background(
                                                    color = skin.cardOverlayColor.copy(alpha = 0.7f),
                                                    shape = CircleShape
                                                )
                                                .clip(CircleShape)
                                                .clickable { 
                                                    viewGroup.context.vibrateLongPress()
                                                    overlayExpanded = !overlayExpanded 
                                                }
                                                .padding(6.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.KeyboardArrowDown,
                                                contentDescription = null,
                                                tint = skin.textPrimary,
                                                modifier = Modifier
                                                    .size(17.dp)
                                                    .rotate(chevronRotation)
                                            )
                                        }
                                        AnimatedVisibility(
                                            visible = overlayExpanded,
                                            enter = panelEnter,
                                            exit = panelExit
                                        ) {
                                            Column(horizontalAlignment = Alignment.End) {
                                                if (hasInfoRow) {
                                                    Spacer(modifier = Modifier.height(6.dp))
                                                    Box(
                                                        modifier = Modifier
                                                            .background(
                                                                color = skin.cardOverlayColor.copy(alpha = 0.7f),
                                                                shape = CircleShape
                                                            )
                                                            .clip(CircleShape)
                                                    ) {
                                                        Row(
                                                            verticalAlignment = Alignment.CenterVertically,
                                                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                        ) {
                                                            if (hasCounter) {
                                                                OperaStoryCounterDisplay(
                                                                    counterText = counterText,
                                                                    enableSnapJump = enableSnapJump,
                                                                    totalCount = totalCount,
                                                                    onCounterClick = { 
                                                                        viewGroup.context.vibrateLongPress()
                                                                        showJumpDialog = true 
                                                                    }
                                                                )
                                                            }

                                                            if (hasSnapJump) {
                                                                if (hasCounter) {
                                                                    Box(
                                                                        modifier = Modifier
                                                                            .width(1.dp)
                                                                            .height(10.dp)
                                                                            .background(skin.textPrimary.copy(alpha = 0.4f))
                                                                    )
                                                                }
                                                                Icon(
                                                                    imageVector = Icons.Outlined.SkipNext,
                                                                    contentDescription = null,
                                                                    tint = skin.textPrimary,
                                                                    modifier = Modifier
                                                                        .size(14.dp)
                                                                        .clickable { 
                                                                            viewGroup.context.vibrateLongPress()
                                                                            showJumpDialog = true 
                                                                        }
                                                                )
                                                            }

                                                            if (hasSource) {
                                                                if (hasCounter || hasSnapJump) {
                                                                    Box(
                                                                        modifier = Modifier
                                                                            .width(1.dp)
                                                                            .height(10.dp)
                                                                            .background(skin.textPrimary.copy(alpha = 0.4f))
                                                                    )
                                                                }
                                                                OperaStorySourceIndicatorDisplay(source = source)
                                                            }

                                                            if (hasCaptionText) {
                                                                if (hasCounter || hasSnapJump || hasSource) {
                                                                    Box(
                                                                        modifier = Modifier
                                                                            .width(1.dp)
                                                                            .height(10.dp)
                                                                            .background(skin.textPrimary.copy(alpha = 0.4f))
                                                                    )
                                                                }
                                                                OperaStoryCaptionTextDisplay(
                                                                    captionText = captionText,
                                                                    onClick = {
                                                                        viewGroup.context.vibrateLongPress()
                                                                        context.androidContext.copyToClipboard(captionText)
                                                                        context.shortToast("Caption copied")
                                                                    }
                                                                )
                                                            }
                                                        }
                                                    }
                                                }

                                                if (overlayDownloadVisible) {
                                                    val mediaDownloader = remember { context.feature(MediaDownloader::class) }
                                                    if (hasInfoRow) {
                                                        Spacer(modifier = Modifier.height(6.dp))
                                                    }
                                                    Box(
                                                        modifier = Modifier
                                                            .background(
                                                                color = skin.cardOverlayColor.copy(alpha = 0.7f),
                                                                shape = CircleShape
                                                            )
                                                            .clip(CircleShape)
                                                    ) {
                                                        @OptIn(ExperimentalFoundationApi::class)
                                                        Icon(
                                                            imageVector = Icons.Outlined.Download,
                                                            contentDescription = null,
                                                            tint = skin.textPrimary,
                                                            modifier = Modifier
                                                                .padding(6.dp)
                                                                .size(18.dp)
                                                                .combinedClickable(
                                                                    onClick = {
                                                                        viewGroup.context.vibrateLongPress()
                                                                        mediaDownloader.downloadLastOperaMediaAsync(allowDuplicate = false)
                                                                    },
                                                                    onLongClick = {
                                                                        context.androidContext.vibrateLongPress()
                                                                        mediaDownloader.downloadLastOperaMediaAsync(allowDuplicate = true)
                                                                    }
                                                                )
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    } else {
                                        if (hasInfoRow) {
                                            Box(
                                                modifier = Modifier
                                                    .background(
                                                        color = skin.cardOverlayColor.copy(alpha = 0.7f),
                                                        shape = CircleShape
                                                    )
                                                    .clip(CircleShape)
                                            ) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                ) {
                                                    if (hasCounter) {
                                                        OperaStoryCounterDisplay(
                                                            counterText = counterText,
                                                            enableSnapJump = enableSnapJump,
                                                            totalCount = totalCount,
                                                            onCounterClick = { 
                                                                viewGroup.context.vibrateLongPress()
                                                                showJumpDialog = true 
                                                            }
                                                        )
                                                    }

                                                    if (hasSnapJump) {
                                                        if (hasCounter) {
                                                            Box(
                                                                modifier = Modifier
                                                                    .width(1.dp)
                                                                    .height(10.dp)
                                                                    .background(skin.textPrimary.copy(alpha = 0.4f))
                                                            )
                                                        }
                                                        Icon(
                                                            imageVector = Icons.Outlined.SkipNext,
                                                            contentDescription = null,
                                                            tint = skin.textPrimary,
                                                            modifier = Modifier
                                                                .size(14.dp)
                                                                .clickable { 
                                                                    viewGroup.context.vibrateLongPress()
                                                                    showJumpDialog = true 
                                                                }
                                                        )
                                                    }

                                                    if (hasSource) {
                                                        if (hasCounter || hasSnapJump) {
                                                            Box(
                                                                modifier = Modifier
                                                                    .width(1.dp)
                                                                    .height(10.dp)
                                                                    .background(skin.textPrimary.copy(alpha = 0.4f))
                                                            )
                                                        }
                                                        OperaStorySourceIndicatorDisplay(source = source)
                                                    }

                                                    if (hasCaptionText) {
                                                        if (hasCounter || hasSnapJump || hasSource) {
                                                            Box(
                                                                modifier = Modifier
                                                                    .width(1.dp)
                                                                    .height(10.dp)
                                                                    .background(skin.textPrimary.copy(alpha = 0.4f))
                                                            )
                                                        }
                                                        OperaStoryCaptionTextDisplay(
                                                            captionText = captionText,
                                                            onClick = {
                                                                viewGroup.context.vibrateLongPress()
                                                                context.androidContext.copyToClipboard(captionText)
                                                                context.shortToast("Caption copied")
                                                            }
                                                        )
                                                    }
                                                }
                                            }
                                        }

                                        if (overlayDownloadVisible) {
                                            val mediaDownloader = remember { context.feature(MediaDownloader::class) }
                                            if (hasInfoRow) {
                                                Spacer(modifier = Modifier.height(6.dp))
                                            }
                                            Box(
                                                modifier = Modifier
                                                    .background(
                                                        color = skin.cardOverlayColor.copy(alpha = 0.7f),
                                                        shape = CircleShape
                                                    )
                                                    .clip(CircleShape)
                                            ) {
                                                @OptIn(ExperimentalFoundationApi::class)
                                                Icon(
                                                    imageVector = Icons.Outlined.Download,
                                                    contentDescription = null,
                                                    tint = skin.textPrimary,
                                                    modifier = Modifier
                                                        .padding(6.dp)
                                                        .size(18.dp)
                                                        .combinedClickable(
                                                            onClick = {
                                                                viewGroup.context.vibrateLongPress()
                                                                mediaDownloader.downloadLastOperaMediaAsync(allowDuplicate = false)
                                                            },
                                                            onLongClick = {
                                                                context.androidContext.vibrateLongPress()
                                                                mediaDownloader.downloadLastOperaMediaAsync(allowDuplicate = true)
                                                            }
                                                        )
                                                )
                                            }
                                        }
                                    }

                                    if (enableSnapJump && showJumpDialog && totalCount > 1) {
                                        OperaStorySnapJumpDialog(
                                            currentIndex = currentIdx,
                                            totalCount = totalCount,
                                            onDismiss = { showJumpDialog = false },
                                            onJump = { targetIndex ->
                                                showJumpDialog = false
                                                snapJump.jumpToSnap(targetIndex)
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    composeView.tag = STORY_OVERLAY_COMPOSE_TAG
                    composeView.isClickable = false
                    composeView.isFocusable = false
                    val topPx = this@OperaStoryOverlay.context.userInterface.dpToPx(50)
                    val endPx = this@OperaStoryOverlay.context.userInterface.dpToPx(10)
                    composeView.layoutParams = when (viewGroup) {
                        is FrameLayout -> FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.WRAP_CONTENT,
                            FrameLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            gravity = Gravity.TOP or Gravity.END
                            topMargin = topPx
                            marginEnd = endPx
                        }
                        else -> ViewGroup.MarginLayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply {
                            topMargin = topPx
                            marginEnd = endPx
                        }
                    }
                    viewGroup.addView(composeView)

                    val dedupAnchor = event.parent
                    event.parent.post {
                        event.parent.post {
                            val w = event.parent.width
                            val h = event.parent.height
                            val dm = event.parent.resources.displayMetrics
                            if (w > 0 && h > 0 && (w < dm.widthPixels * 0.7 || h < dm.heightPixels * 0.5)) {
                                viewGroup.removeView(composeView)
                                return@post
                            }
                            val dupes = findSmallestAncestorWithDuplicateOverlays(dedupAnchor) ?: return@post
                            dedupeStoryOverlayComposes(dupes) { fl ->
                                storyFrameLayout = WeakReference(fl)
                            }
                        }
                    }
                }
            }
        }

        onNextActivityCreate {
            overlayState.setupDisplayStateHook(
                context = context,
                showCounter = showCounter,
                showSourceIndicator = showSourceIndicator,
                onSnapFullyDisplayed = if (enableSnapJump) {
                    { currentIndex ->
                        if (snapJump.isJumping()) {
                            snapJump.onSnapFullyDisplayed(currentIndex)
                        }
                    }
                } else null,
                onClearState = if (enableSnapJump) { { snapJump.removeJumpOverlay() } } else null
            )
        }
    }

    fun requestJumpToSnap(targetIndex: Int, totalCountOverride: Int? = null): Boolean =
        snapJump.requestJumpToSnap(targetIndex, totalCountOverride)
}

private fun shouldShowStoryCounterOverlay(
    snapSource: String?,
    playlistV2: String?,
    isInSpotlightContext: Boolean,
    mapStoryEligible: Boolean
): Boolean {
    if (snapSource == "SPOTLIGHT" || snapSource == "SINGLE_SNAP_STORY") return false
    if (snapSource == "PUBLIC_STORY") return true
    if (mapStoryEligible) return true
    val g = playlistV2 ?: return false
    if (g.contains("PublicUserStory", ignoreCase = true)) return !isInSpotlightContext
    if (g.contains("storyUserId=")) return true
    return false
}
