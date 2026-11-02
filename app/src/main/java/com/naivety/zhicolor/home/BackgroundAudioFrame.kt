package com.naivety.zhicolor.home

/** Background-only raw FFT report before display gain, windowing and soft knee.
 * low/mid/high are fractions of total FFT power; level is the raw FFT sum of squared moduli.
 * level is NOT RMS and is not bounded by one. timestampNanos is capture time, never publish time. */
data class BackgroundAudioFrame(
    val low: Float,
    val mid: Float,
    val high: Float,
    val level: Float,
    val timestampNanos: Long,
    val calibrated: Boolean = true,
    val history: List<BackgroundAudioFrame> = emptyList(),
) {
    /**
     * Android host adaptation: keep NORMALIZED capture power for this visual effect.
     * The output meter covers different mixer buffers/channels; dividing it by this
     * capture's RMS neither recovers same-window PCM nor removes device volume gain.
     * Doing so made ordinary low-volume playback vanish after the AM envelope/curve.
     * A valid zero meter still gates silence; missing/invalid meters never invent gain.
     * This is normalized input, not a claim of calibrated pre-volume AM PCM.
     */
    fun fromNormalizedCapture(rawRms: Float?, capturedRms: Float, capturedAtNanos: Long): BackgroundAudioFrame {
        if (!capturedRms.isFinite() || capturedRms <= 0f) return SILENT.copy(timestampNanos = capturedAtNanos)
        val measured = rawRms?.takeIf { it.isFinite() && it >= 0f }
        if (measured == 0f) return SILENT.copy(timestampNanos = capturedAtNanos)
        return copy(level = if (level.isFinite()) level.coerceAtLeast(0f) else 0f,
            timestampNanos = capturedAtNanos, calibrated = false)
    }

    companion object {
        val SILENT = BackgroundAudioFrame(0f, 0f, 0f, 0f, 0L, false)

    }
}
