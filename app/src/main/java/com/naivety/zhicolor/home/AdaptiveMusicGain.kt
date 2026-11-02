package com.naivety.zhicolor.home

import kotlin.math.exp
import kotlin.math.expm1
import kotlin.math.ln
import kotlin.math.ln1p
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Slow, partial level compensation for the album visualizer only.
 *
 * A moving RMS reference sets one shared gain for every frequency. Its eight-second memory and
 * slower gain changes leave beats and quiet passages intact. The exponent deliberately stops
 * short of full normalization, so sustained energetic music still looks stronger than quiet music.
 * No gain is learned from silence, and a low-level gate prevents boosting the measurement floor.
 * Call [reset] when the playback session or media item changes.
 */
internal class AdaptiveMusicGain {
    data class Frame(
        val bands: List<Float>,
        val loudness: Float,
        /** Slow control gain, before the low-level noise gate/boost fade. */
        val gain: Float,
        val reference: Float
    )

    private var reference = TARGET_RMS
    private var gain = 1.0
    private var quietSeconds = 0.0

    fun reset() {
        reference = TARGET_RMS
        gain = 1.0
        quietSeconds = 0.0
    }

    /** [bands] use SpectrumBands' ln1p magnitude scale; [rms] is linear RMS relative to full scale. */
    fun process(bands: List<Float>, rms: Float, elapsedSeconds: Float): Frame {
        // A stale UI frame must not skip seconds of adaptation or turn an invalid delta into NaN.
        val elapsed = if (elapsedSeconds.isFinite()) elapsedSeconds.toDouble().coerceIn(0.0, 0.25) else 0.0
        val level = if (rms.isFinite()) rms.toDouble().coerceIn(0.0, 1.0) else 0.0

        if (level < LEARNING_FLOOR) {
            quietSeconds = (quietSeconds + elapsed).coerceAtMost(IDLE_RESET_SECONDS)
            // Silence cannot wind the gain up and amplify the next song's first beat.
            gain = smoothLogGain(gain, 1.0, elapsed, IDLE_GAIN_SECONDS)
            if (quietSeconds >= IDLE_RESET_SECONDS) reference = TARGET_RMS
        } else {
            quietSeconds = 0.0
            reference += (level - reference) * smoothingAmount(elapsed, REFERENCE_SECONDS)
            val target = (TARGET_RMS / reference).pow(COMPENSATION).coerceIn(MIN_GAIN, MAX_GAIN)
            val seconds = if (target > gain) GAIN_RISE_SECONDS else GAIN_FALL_SECONDS
            gain = smoothLogGain(gain, target, elapsed, seconds).coerceIn(MIN_GAIN, MAX_GAIN)
        }

        val gate = smoothStep(NOISE_FLOOR, LEARNING_FLOOR, level)
        // Signals near the gate may fade in but never receive the accumulated positive boost.
        val boostMix = smoothStep(LEARNING_FLOOR, FULL_BOOST_LEVEL, level)
        val appliedGain = (if (gain > 1.0) 1.0 + (gain - 1.0) * boostMix else gain) * gate
        val adjustedBands = bands.map { band ->
            val bounded = if (band.isFinite()) band.toDouble().coerceIn(0.0, 1.0) else 0.0
            // Scaling the log display value directly would distort both gain and spectral ratios.
            val magnitude = expm1(bounded * MAGNITUDE_SCALE)
            (ln1p(magnitude * appliedGain) / MAGNITUDE_SCALE).toFloat().coerceIn(0f, 1f)
        }
        return Frame(
            bands = adjustedBands,
            loudness = (level * appliedGain).toFloat().coerceIn(0f, 1f),
            gain = gain.toFloat(),
            reference = reference.toFloat()
        )
    }

    private fun smoothingAmount(elapsed: Double, seconds: Double) = -expm1(-elapsed / seconds)

    private fun smoothLogGain(current: Double, target: Double, elapsed: Double, seconds: Double): Double =
        exp(ln(current) + (ln(target) - ln(current)) * smoothingAmount(elapsed, seconds))

    private fun smoothStep(lower: Double, upper: Double, value: Double): Double {
        val fraction = ((value - lower) / (upper - lower)).coerceIn(0.0, 1.0)
        return fraction * fraction * (3.0 - 2.0 * fraction)
    }

    private companion object {
        const val TARGET_RMS = 0.08
        const val COMPENSATION = 0.80
        const val REFERENCE_SECONDS = 8.0
        const val GAIN_RISE_SECONDS = 3.0
        const val GAIN_FALL_SECONDS = 1.5
        const val MIN_GAIN = 0.20
        const val MAX_GAIN = 16.0
        const val NOISE_FLOOR = 0.0015
        const val LEARNING_FLOOR = 0.003
        const val FULL_BOOST_LEVEL = 0.006
        const val IDLE_RESET_SECONDS = 3.0
        const val IDLE_GAIN_SECONDS = 1.0
        val MAGNITUDE_SCALE = ln1p(128.0 * sqrt(2.0))
    }
}
