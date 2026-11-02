package com.naivety.zhicolor.home

import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.ln1p
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Converts the signed, packed FFT returned by Visualizer into logarithmic display bands. */
internal object SpectrumBands {
    const val COUNT = 48

    private const val MIN_FREQUENCY_HZ = 40.0
    private const val MAX_FREQUENCY_HZ = 16_000.0
    private val logarithmicSpan = ln(MAX_FREQUENCY_HZ / MIN_FREQUENCY_HZ)
    private val magnitudeScale = ln1p(128.0 * sqrt(2.0))

    /** Linear peak amplitudes, with each measured bin assigned to its actual frequency.
     * Empty sub-resolution bands remain empty: duplicating the nearest bass bin fabricates
     * a broad low-frequency hump. Packed DC/Nyquist and frequencies outside the display are excluded.
     */
    fun linearFromFft(fft: ByteArray, samplingRateMilliHz: Int): List<Float> {
        val output = FloatArray(COUNT)
        if (fft.size < 8 || fft.size % 2 != 0 || samplingRateMilliHz <= 80_000) return output.toList()
        val binHz = samplingRateMilliHz / 1_000.0 / fft.size
        for (bin in 1 until fft.size / 2) {
            val frequency = bin * binHz
            if (frequency < MIN_FREQUENCY_HZ || frequency >= MAX_FREQUENCY_HZ) continue
            val band = floor(ln(frequency / MIN_FREQUENCY_HZ) / logarithmicSpan * COUNT).toInt()
            if (band !in output.indices) continue
            val amplitude = (hypot(fft[2 * bin].toDouble(), fft[2 * bin + 1].toDouble()) /
                (128.0 * sqrt(2.0))).toFloat().coerceIn(0f, 1f)
            output[band] = maxOf(output[band], amplitude)
        }
        return output.toList()
    }

    /**
     * [samplingRateMilliHz] uses Visualizer's milli-Hertz units, not AudioTrack's Hertz units.
     * Each band contains the peak magnitude of its FFT bins. An empty band uses the nearest
     * in-range bin, preserving real input rather than adding a synthetic signal.
     * The 48 band edges stay fixed at 40-16000 Hz; bands above input Nyquist remain silent.
     *
     * Packed DC (index 0) and Nyquist (index 1) values are deliberately never read.
     */
    fun fromFft(fft: ByteArray, samplingRateMilliHz: Int): List<Float> {
        val silent = List(COUNT) { 0f }
        if (fft.size < 8 || fft.size % 2 != 0 || samplingRateMilliHz <= 0) return silent

        val sampleRateHz = samplingRateMilliHz / 1_000.0
        val maxFrequencyHz = min(MAX_FREQUENCY_HZ, sampleRateHz / 2.0)
        if (maxFrequencyHz <= MIN_FREQUENCY_HZ) return silent

        val binWidthHz = sampleRateHz / fft.size
        val firstBin = ceil(MIN_FREQUENCY_HZ / binWidthHz).toInt().coerceAtLeast(1)
        val lastBin = floor(maxFrequencyHz / binWidthHz).toInt()
            .coerceAtMost(fft.size / 2 - 1)
        if (firstBin > lastBin) return silent

        val magnitudes = DoubleArray(lastBin + 1)
        for (bin in firstBin..lastBin) {
            // Byte.toDouble() sign-extends; interpreting these bytes as unsigned adds false energy.
            val real = fft[2 * bin].toDouble()
            val imaginary = fft[2 * bin + 1].toDouble()
            magnitudes[bin] = ln1p(hypot(real, imaginary)) / magnitudeScale
        }

        return List(COUNT) { band ->
            val lowerHz = MIN_FREQUENCY_HZ * exp(logarithmicSpan * band / COUNT)
            val upperHz = MIN_FREQUENCY_HZ * exp(logarithmicSpan * (band + 1) / COUNT)
            // Keep every display band's frequency identity when the input Nyquist is lower.
            if (lowerHz >= maxFrequencyHz) return@List 0f
            val startBin = ceil(lowerHz / binWidthHz).toInt().coerceAtLeast(firstBin)
            val endBin = (if (band == COUNT - 1) lastBin else ceil(upperHz / binWidthHz).toInt() - 1)
                .coerceAtMost(lastBin)

            val peak = if (startBin <= endBin) {
                var maximum = 0.0
                for (bin in startBin..endBin) maximum = maxOf(maximum, magnitudes[bin])
                maximum
            } else {
                val centerHz = sqrt(lowerHz * upperHz)
                magnitudes[(centerHz / binWidthHz).roundToInt().coerceIn(firstBin, lastBin)]
            }
            peak.toFloat().coerceIn(0f, 1f)
        }
    }
}
