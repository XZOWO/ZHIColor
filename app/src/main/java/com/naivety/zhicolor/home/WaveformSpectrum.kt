package com.naivety.zhicolor.home

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/** Float FFT of the SAME normalized capture used for RMS.
 * Visualizer's packed FFT quantizes small high-frequency coefficients back to eight bits;
 * working from its waveform avoids that second quantization and callback skew. Hann windowing
 * suppresses frame-edge leakage. Sub-bin display positions interpolate the measured spectrum;
 * they are not independently resolved tones and never receive independent gain.
 */
internal class WaveformSpectrum {
    data class Frame(val bands: List<Float>, val rms: Float,
                     val backgroundAudio: BackgroundAudioFrame = BackgroundAudioFrame.SILENT)
    private var real = DoubleArray(0)
    private var imaginary = DoubleArray(0)
    private var window = DoubleArray(0)
    private var windowSum = 0.0
    private val backgroundSpectrum = RawBackgroundSpectrum()

    fun analyze(waveform: ByteArray, samplingRateMilliHz: Int): Frame =
        analyze(FloatArray(waveform.size) { ((waveform[it].toInt() and 0xff) - 128) / 128f }, samplingRateMilliHz)

    /** PCM loopback keeps its native precision rather than requantizing to Visualizer bytes. */
    fun analyze(waveform: FloatArray, samplingRateMilliHz: Int): Frame {
        val count = waveform.size
        if (count < 8 || (count and (count - 1)) != 0 || samplingRateMilliHz <= 80_000)
            return Frame(List(SpectrumBands.COUNT) { 0f }, 0f)
        if (real.size != count) {
            real = DoubleArray(count)
            imaginary = DoubleArray(count)
            window = DoubleArray(count) { .5 - .5 * cos(2.0 * PI * it / (count - 1)) }
            windowSum = window.sum()
        }
        var mean = 0.0
        for (sample in waveform) mean += if (sample.isFinite()) sample else 0f
        mean /= count
        var squareSum = 0.0
        for (i in waveform.indices) {
            val sample = (if (waveform[i].isFinite()) waveform[i].toDouble() else 0.0) - mean
            squareSum += sample * sample
            real[i] = sample * window[i]
            imaginary[i] = 0.0
        }
        val rms = sqrt(squareSum / count).toFloat()
        if (rms <= 0f) return Frame(List(SpectrumBands.COUNT) { 0f }, 0f,
            backgroundSpectrum.analyze(waveform, samplingRateMilliHz))
        var reversed = 0
        for (i in 1 until count) {
            var bit = count shr 1
            while ((reversed and bit) != 0) { reversed = reversed xor bit; bit = bit shr 1 }
            reversed = reversed xor bit
            if (i < reversed) {
                val swap = real[i]; real[i] = real[reversed]; real[reversed] = swap
            }
        }
        var length = 2
        while (length <= count) {
            val stepReal = cos(-2.0 * PI / length)
            val stepImaginary = sin(-2.0 * PI / length)
            var start = 0
            while (start < count) {
                var twiddleReal = 1.0
                var twiddleImaginary = 0.0
                for (j in 0 until length / 2) {
                    val even = start + j
                    val odd = even + length / 2
                    val oddReal = real[odd] * twiddleReal - imaginary[odd] * twiddleImaginary
                    val oddImaginary = real[odd] * twiddleImaginary + imaginary[odd] * twiddleReal
                    real[odd] = real[even] - oddReal
                    imaginary[odd] = imaginary[even] - oddImaginary
                    real[even] += oddReal
                    imaginary[even] += oddImaginary
                    val nextReal = twiddleReal * stepReal - twiddleImaginary * stepImaginary
                    twiddleImaginary = twiddleReal * stepImaginary + twiddleImaginary * stepReal
                    twiddleReal = nextReal
                }
                start += length
            }
            length *= 2
        }
        val peaks = FloatArray(SpectrumBands.COUNT)
        val populated = BooleanArray(SpectrumBands.COUNT)
        val magnitudes = FloatArray(count / 2)
        val binHz = samplingRateMilliHz / 1000.0 / count
        for (bin in 1 until count / 2) {
            val hz = bin * binHz
            val amplitude = (2.0 * hypot(real[bin], imaginary[bin]) / windowSum).toFloat().coerceIn(0f, 1f)
            magnitudes[bin] = amplitude
            if (hz < 40.0 || hz >= 16_000.0) continue
            val band = floor(ln(hz / 40.0) / ln(400.0) * SpectrumBands.COUNT).toInt()
            populated[band] = true
            peaks[band] = maxOf(peaks[band], amplitude)
        }
        for (band in peaks.indices) {
            if (populated[band]) continue
            val center = 40.0 * exp(ln(400.0) * (band + 0.5) / SpectrumBands.COUNT) / binHz
            val lower = floor(center).toInt()
            if (lower < 1 || lower + 1 >= magnitudes.size) continue
            val fraction = (center - lower).toFloat()
            peaks[band] = magnitudes[lower] * (1f - fraction) + magnitudes[lower + 1] * fraction
        }
        val background = backgroundSpectrum.analyze(waveform, samplingRateMilliHz)
        return Frame(peaks.toList(), rms, background)
    }
}
