package com.naivety.zhicolor.home

import kotlin.math.pow
import kotlin.math.sqrt

/** Converts either Visualizer amplitude source to full-band RMS relative to full scale. */
internal object LoudnessLevel {
    /** Visualizer uses unsigned 8-bit PCM, with silence at 0x80 rather than zero. */
    fun fromWaveform(waveform: ByteArray): Float {
        if (waveform.isEmpty()) return 0f
        var sumSquares = 0.0
        for (sample in waveform) {
            val centered = (sample.toInt() and 0xff) - 128
            sumSquares += centered * centered
        }
        return (sqrt(sumSquares / waveform.size) / 128.0).toFloat().coerceIn(0f, 1f)
    }

    /** MeasurementPeakRms reports millibels: 2000 mB corresponds to a 10x amplitude ratio. */
    fun fromMillibels(rmsMillibels: Int): Float {
        // -9600 mB is the Visualizer's silence floor. Positive readings are above full scale.
        if (rmsMillibels <= -9600) return 0f
        if (rmsMillibels >= 0) return 1f
        return 10.0.pow(rmsMillibels / 2000.0).toFloat().coerceIn(0f, 1f)
    }
}
