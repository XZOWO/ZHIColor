package com.naivety.zhicolor.home

import kotlin.math.max

/**
 * MediaCoreUI's four audio reports and render-side envelope, recovered from the binary.
 * Audio callbacks append history; render ticks never insert duplicate audio reports.
 * A nominal 60 Hz clock preserves those coefficients on Android displays of any refresh rate.
 */
internal class AmBackgroundEnvelope {
    private val recent = Array(4) { FloatArray(3) }
    private val target = FloatArray(3)
    private val power = FloatArray(3)
    private var ticks = 0.0

    fun submit(value: BackgroundAudioPower) {
        for (index in 0..2) recent[index + 1].copyInto(recent[index])
        recent[3][0] = finite(value.low)
        recent[3][1] = finite(value.mid)
        recent[3][2] = finite(value.high)
    }

    fun advance(elapsedSeconds: Float): BackgroundAudioPower {
        if (elapsedSeconds.isFinite() && elapsedSeconds > 0f) {
            ticks += elapsedSeconds.toDouble().coerceAtMost(0.25) * 60.0
            while (ticks + 0.000001 >= 1.0) {
                ticks -= 1.0
                for (band in 0..2) {
                    var weighted = 0f
                    for (index in 0..3) weighted += recent[index][band] * WEIGHTS[index]
                    target[band] = max(weighted, target[band] - target[band] / DIVISORS[band])
                    power[band] += 0.5f * (target[band] - power[band])
                }
            }
        }
        return BackgroundAudioPower(curve(power[0]), curve(power[1]), curve(power[2]))
    }

    fun reset() {
        recent.forEach { it.fill(0f) }
        target.fill(0f)
        power.fill(0f)
        ticks = 0.0
    }

    companion object {
        private val WEIGHTS = floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f)
        private val DIVISORS = floatArrayOf(50f, 100f, 1000f)
        private fun finite(value: Float) = if (value.isFinite()) value.coerceIn(0f, 1f) else 0f
        internal fun curve(value: Float): Float {
            val p = finite(value).toDouble()
            return (p * p * p * (p * (6.0 * p - 15.0) + 10.0)).coerceIn(0.0, 1.0).toFloat()
        }
    }
}
