package com.naivety.zhicolor.ui.components

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow

/** Fixed cover geometry and short frame interpolation, not automatic audio gain or peak holding. */
internal object AlbumSpectrumMotion {
    const val BAND_COUNT = 48
    const val CANVAS_DP = 96f
    const val COVER_DIAMETER_DP = 56f
    const val COVER_RADIUS_DP = COVER_DIAMETER_DP / 2f
    const val RAY_WIDTH_DP = 1.1f
    // Round caps extend half a stroke inward. Their visible edge meets the cover, never its interior.
    const val BASE_RADIUS_DP = COVER_RADIUS_DP + RAY_WIDTH_DP / 2f
    const val SPECTRUM_LIFT_DP = 18f
    const val GLOW_BASE_RADIUS_DP = COVER_RADIUS_DP + 3f
    const val GLOW_LIFT_DP = 15f
    const val GLOW_MAX_RADIUS_DP = GLOW_BASE_RADIUS_DP + GLOW_LIFT_DP

    /** Preserve the upstream amplitude exactly; this only rejects invalid or out-of-range input. */
    fun spectrumStrength(energy: Float): Float =
        if (energy.isFinite()) energy.coerceIn(0f, 1f) else 0f

    /** Common monotonic display compression, with a small sound-driven breathing baseline.
     * Raw FFT and loudness normalization stay untouched; silence still maps to exact zero. */
    fun visibleStrength(energy: Float, loudness: Float): Float {
        val level = spectrumStrength(loudness)
        val value = spectrumStrength(energy)
        if (level == 0f && value == 0f) return 0f
        val floor = 0.055f * level.pow(0.6f)
        return (floor + (1f - floor) * value.pow(0.42f)).coerceIn(0f, 1f)
    }

    /** Low to high frequency clockwise from twelve o'clock, without repeating the endpoint. */
    fun angleRadians(index: Int): Double {
        require(index in 0 until BAND_COUNT)
        return -PI / 2.0 + index * 2.0 * PI / BAND_COUNT
    }

    fun glowRadiusDp(level: Float): Float =
        GLOW_BASE_RADIUS_DP + GLOW_LIFT_DP * spectrumStrength(level)

    fun stepBand(current: Float, target: Float, seconds: Float): Float =
        step(current, target, seconds, 0.012f, 0.105f)

    fun stepGlow(current: Float, target: Float, seconds: Float): Float =
        step(current, target, seconds, 0.030f, 0.140f)

    private fun step(current: Float, target: Float, seconds: Float, attack: Float, release: Float): Float {
        val from = spectrumStrength(current)
        val to = spectrumStrength(target)
        if (!seconds.isFinite() || seconds <= 0f) return from
        val timeConstant = if (to > from) attack else release
        return from + (to - from) * (1f - exp(-seconds / timeConstant))
    }
}
