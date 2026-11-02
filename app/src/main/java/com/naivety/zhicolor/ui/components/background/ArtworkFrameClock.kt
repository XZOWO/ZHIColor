package com.naivety.zhicolor.ui.components.background

/** Elapsed time, not callback count, drives the artwork and its audio envelope. */
internal class ArtworkFrameClock {
    private var previousNanos: Long? = null

    fun step(nowNanos: Long): Float {
        val previous = previousNanos
        previousNanos = nowNanos
        return if (previous == null || nowNanos <= previous) 0f else
            ((nowNanos - previous) / 1_000_000_000.0).coerceAtMost(0.25).toFloat()
    }

    fun reset() { previousNanos = null }
}
