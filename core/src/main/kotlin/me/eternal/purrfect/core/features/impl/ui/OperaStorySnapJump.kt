package me.eternal.purrfect.core.features.impl.ui

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import kotlin.math.abs
class OperaStorySnapJump(
    private val context: me.eternal.purrfect.core.ModContext,
    private val overlayState: OperaStoryOverlayState,
    private val storyFrameLayout: () -> ViewGroup?
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var isJumping = false
    @Volatile private var jumpTargetIndex = -1
    private var jumpGeneration = 0
    private var retryRunnable: Runnable? = null
    private var nextTapRunnable: Runnable? = null
    private var lastHandledIndex = -1

    fun simulateTap(forward: Boolean) {
        val activity = context.mainActivity ?: return
        val decorView = activity.window.decorView
        val x = if (forward) decorView.width * 0.88f else decorView.width * 0.12f
        val y = decorView.height * 0.5f
        val downTime = SystemClock.uptimeMillis()
        val tapDurationMs = 50L

        val downEvent = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0)
        decorView.dispatchTouchEvent(downEvent)
        downEvent.recycle()

        val upEvent = MotionEvent.obtain(downTime, downTime + tapDurationMs, MotionEvent.ACTION_UP, x, y, 0)
        decorView.dispatchTouchEvent(upEvent)
        upEvent.recycle()
    }

    private fun navigateStory(forward: Boolean) {
        if (storyFrameLayout()?.isAttachedToWindow != true) return
        simulateTap(forward)
    }

    fun showJumpOverlay() {
        storyFrameLayout()?.findViewWithTag<View>("jump_overlay")?.let {
            (it.parent as? ViewGroup)?.removeView(it)
        }
        (context.mainActivity?.window?.decorView as? ViewGroup)?.findViewWithTag<View>("jump_overlay")?.let {
            (it.parent as? ViewGroup)?.removeView(it)
        }
    }

    private fun cancelPendingRetry() {
        retryRunnable?.let { mainHandler.removeCallbacks(it) }
        retryRunnable = null
    }

    private fun cancelPendingNextTap() {
        nextTapRunnable?.let { mainHandler.removeCallbacks(it) }
        nextTapRunnable = null
    }

    fun removeJumpOverlay() {
        cancelPendingRetry()
        cancelPendingNextTap()
        isJumping = false
        jumpTargetIndex = -1
        lastHandledIndex = -1
        mainHandler.postDelayed({
            val decor = context.mainActivity?.window?.decorView as? ViewGroup
            val overlay = storyFrameLayout()?.findViewWithTag<View>("jump_overlay")
                ?: decor?.findViewWithTag("jump_overlay")
            overlay ?: return@postDelayed
            (overlay.parent as? ViewGroup)?.removeView(overlay)
        }, 150)
    }

    private fun dispatchTapAndWaitForChange(fromIndex: Int, gen: Int, retryCount: Int = 0) {
        if (!isJumping || jumpTargetIndex < 0 || gen != jumpGeneration) return
        if (storyFrameLayout()?.isAttachedToWindow != true) {
            removeJumpOverlay()
            return
        }

        val forward = jumpTargetIndex > fromIndex
        simulateTap(forward)

        val remainingDistance = abs(jumpTargetIndex - fromIndex)
        val maxRetries = maxOf(36, remainingDistance * 6)
        val retryDelayMs = when {
            retryCount < 5 -> 180L
            retryCount < 12 -> 280L
            else -> 400L
        }

        val retry = Runnable {
            if (!isJumping || gen != jumpGeneration) return@Runnable
            val currentIdx = overlayState.currentIndexState.intValue
            if (currentIdx == fromIndex) {
                if (retryCount >= maxRetries) {
                    removeJumpOverlay()
                } else {
                    dispatchTapAndWaitForChange(fromIndex, gen, retryCount + 1)
                }
            }
        }
        retryRunnable = retry
        mainHandler.postDelayed(retry, retryDelayMs)
    }

    fun onSnapFullyDisplayed(currentIndex: Int) {
        if (!isJumping || jumpTargetIndex < 0) return
        if (currentIndex == lastHandledIndex) return

        cancelPendingRetry()
        cancelPendingNextTap()

        if (currentIndex == jumpTargetIndex) {
            removeJumpOverlay()
            return
        }

        lastHandledIndex = currentIndex
        val gen = jumpGeneration
        val tapRunnable = Runnable {
            nextTapRunnable = null
            if (isJumping && gen == jumpGeneration && overlayState.currentIndexState.intValue == currentIndex) {
                dispatchTapAndWaitForChange(currentIndex, gen)
            }
        }
        nextTapRunnable = tapRunnable
        mainHandler.postDelayed(tapRunnable, 45L)
    }
    fun requestJumpToSnap(targetIndex: Int, totalCountOverride: Int? = null): Boolean {
        if (storyFrameLayout()?.isAttachedToWindow != true) return false
        val totalCount = totalCountOverride ?: overlayState.totalCountState.intValue
        if (totalCount <= 1) return false
        if (targetIndex < 0 || targetIndex >= totalCount) return false
        mainHandler.post { jumpToSnap(targetIndex) }
        return true
    }

    fun jumpToSnap(targetIndex: Int) {
        val current = overlayState.currentIndexState.intValue
        if (current < 0 || targetIndex == current) return

        if (isJumping) {
            cancelPendingRetry()
            cancelPendingNextTap()
            isJumping = false
            jumpTargetIndex = -1
        }

        if (abs(targetIndex - current) == 1) {
            navigateStory(targetIndex > current)
            return
        }

        jumpGeneration++
        jumpTargetIndex = targetIndex
        lastHandledIndex = -1
        isJumping = true

        showJumpOverlay()

        val gen = jumpGeneration
        mainHandler.postDelayed({
            if (gen == jumpGeneration && isJumping) {
                dispatchTapAndWaitForChange(current, gen)
            }
        }, 50L)
    }

    fun isJumping(): Boolean = isJumping
}
