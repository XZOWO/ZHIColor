package com.naivety.zhicolor.home

import kotlin.math.log10

internal data class BackgroundAudioPower(val low: Float, val mid: Float, val high: Float)

/** Original analyzer-report mapping and callback/render clocks, separate from display AGC. */
internal class BackgroundMotionPolicy {
    private val envelope = AmBackgroundEnvelope()
    private var lastReportNanos = 0L

    fun update(frame: BackgroundAudioFrame, elapsedSeconds: Float, nowNanos: Long): BackgroundAudioPower {
        if (frame.timestampNanos <= 0L || frame.timestampNanos > nowNanos ||
            nowNanos - frame.timestampNanos > MAX_AGE_NANOS) {
            reset()
            return ZERO
        }
        // StateFlow/Compose/Binder may coalesce publications. A bounded callback-history
        // snapshot preserves the original four audio reports even when the UI skips one.
        for (report in frame.history.takeLast(4) + frame) {
            if (report.timestampNanos > lastReportNanos && report.timestampNanos <= frame.timestampNanos &&
                nowNanos - report.timestampNanos <= MAX_AGE_NANOS) {
                envelope.submit(normalize(report))
                lastReportNanos = report.timestampNanos
            }
        }
        return envelope.advance(elapsedSeconds)
    }

    fun reset() {
        envelope.reset()
        lastReportNanos = 0L
    }

    companion object {
        private val ZERO = BackgroundAudioPower(0f, 0f, 0f)
        private const val MAX_AGE_NANOS = 500_000_000L

        fun showArtwork(mediaAvailable: Boolean, hasArtwork: Boolean): Boolean = mediaAvailable && hasArtwork

        /**
         * MediaCoreUI 0x1ce7d64dc..6518: clamp(bandFraction * 20*log10(totalFftPower)/120).
         * Reports are FFT energy fractions, NOT dB, RMS or the 48 adaptive display bands.
         * Intensity is one for the behind-lyrics background; PCM/meter recovery is upstream.
         */
        fun normalize(frame: BackgroundAudioFrame): BackgroundAudioPower {
            val total = frame.level
            if (!total.isFinite() || total <= 1f) return ZERO
            val scale = 20.0 * log10(total.toDouble()) / 120.0
            fun band(value: Float): Float = if (value.isFinite() && value > 0f)
                (value.coerceAtMost(1f) * scale).coerceIn(0.0, 1.0).toFloat() else 0f
            return BackgroundAudioPower(band(frame.low), band(frame.mid), band(frame.high))
        }
    }
}
