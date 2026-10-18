package com.naivety.zhicolor.ui.components.motion

import kotlin.math.abs
import kotlin.math.roundToInt

/** A frame has one meaning, whether reached by opening, closing, or seeking a back gesture. */
internal data class OpenMotionFrame(
    val alpha: Float,
    val scale: Float = 1f,
    val translationX: Float = 0f,
    val translationY: Float = 0f,
)

internal object PredictiveBackMotionPolicy {
    const val FullDurationMillis = 280

    fun fraction(value: Float): Float = if (value.isFinite()) value.coerceIn(0f, 1f) else 0f

    // A single symmetric spatial curve; temporal animation itself advances q linearly.
    // Consequently frame(q) is independent of the route used to reach q.
    fun visualFraction(openFraction: Float): Float {
        val q = fraction(openFraction)
        return q * q * (3f - 2f * q)
    }

    fun gestureOpenFraction(startOpenFraction: Float, backProgress: Float): Float =
        fraction(startOpenFraction) * (1f - fraction(backProgress))

    fun durationMillis(from: Float, to: Float): Int =
        (abs(fraction(to) - fraction(from)) * FullDurationMillis).roundToInt()

    fun pageChildFrame(openFraction: Float, widthPx: Float): OpenMotionFrame {
        val q = visualFraction(openFraction)
        return OpenMotionFrame(alpha = q, scale = 0.98f + 0.02f * q, translationX = (1f - q) * widthPx / 7f)
    }

    fun pageParentFrame(openFraction: Float, widthPx: Float): OpenMotionFrame {
        val q = visualFraction(openFraction)
        return OpenMotionFrame(alpha = 1f, scale = 1f - 0.02f * q, translationX = -q * widthPx / 10f)
    }

    fun dialogFrame(openFraction: Float, large: Boolean, heightPx: Float, density: Float): OpenMotionFrame {
        val q = visualFraction(openFraction)
        return OpenMotionFrame(
            alpha = q,
            scale = if (large) 0.9f + 0.1f * q else 0.96f + 0.04f * q,
            translationY = (1f - q) * if (large) 18f * density else heightPx + 32f * density,
        )
    }

    fun dropdownFrame(openFraction: Float, density: Float): OpenMotionFrame {
        val q = visualFraction(openFraction)
        return OpenMotionFrame(alpha = q, scale = 0.92f + 0.08f * q, translationY = (1f - q) * -8f * density)
    }

    fun shouldHandlePageBack(isRoot: Boolean, hasOverlay: Boolean, hasRetainedChild: Boolean = false): Boolean =
        (!isRoot || hasRetainedChild) && !hasOverlay

    fun shouldDismissOverlay(isTop: Boolean, isShown: Boolean, isDismissible: Boolean): Boolean =
        isTop && isShown && isDismissible
}

/** A second back during retained closing frames must not dismiss anything else. */
internal class OverlayBackCommitGate {
    var committed: Boolean = false
        private set

    fun reset() { committed = false }

    fun tryCommit(canDismiss: Boolean): Boolean {
        if (!canDismiss || committed) return false
        committed = true
        return true
    }
}

/** Old cancellation/commit work cannot settle a newer gesture or a reopened target. */
internal class BackGestureSession {
    private var nextId = 0
    private var activeId: Int? = null

    fun begin(): Int = (++nextId).also { activeId = it }
    fun isCurrent(id: Int): Boolean = activeId == id
    fun finish(id: Int): Boolean {
        if (!isCurrent(id)) return false
        activeId = null
        return true
    }
    fun invalidate() { activeId = null }
}
