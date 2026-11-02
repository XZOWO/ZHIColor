package com.naivety.zhicolor.home

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Rectangular real FFT for the original analyzer report; no display window or gain. */
internal class RawBackgroundSpectrum {
    private var real = DoubleArray(0)
    private var imaginary = DoubleArray(0)
    private var mono = FloatArray(0)

    /** The original tap averages stereo PCM before the FFT, including phase cancellation. */
    fun analyzeStereo(left: FloatArray, right: FloatArray, samplingRateMilliHz: Int): BackgroundAudioFrame {
        if (left.size != right.size) return BackgroundAudioFrame.SILENT
        if (mono.size != left.size) mono = FloatArray(left.size)
        for (index in mono.indices) mono[index] = (left[index] + right[index]) * 0.5f
        return analyze(mono, samplingRateMilliHz)
    }

    fun analyze(pcm: FloatArray, samplingRateMilliHz: Int): BackgroundAudioFrame {
        val count = pcm.size
        if (count < 8 || count and (count - 1) != 0 || samplingRateMilliHz <= 0) return BackgroundAudioFrame.SILENT
        if (real.size != count) { real = DoubleArray(count); imaginary = DoubleArray(count) }
        for (index in pcm.indices) {
            real[index] = if (pcm[index].isFinite()) pcm[index].toDouble() else 0.0
            imaginary[index] = 0.0
        }
        var reversed = 0
        for (index in 1 until count) {
            var bit = count shr 1
            while (reversed and bit != 0) { reversed = reversed xor bit; bit = bit shr 1 }
            reversed = reversed xor bit
            if (index < reversed) { val value = real[index]; real[index] = real[reversed]; real[reversed] = value }
        }
        var length = 2
        while (length <= count) {
            val stepReal = cos(-2.0 * PI / length)
            val stepImaginary = sin(-2.0 * PI / length)
            var start = 0
            while (start < count) {
                var twiddleReal = 1.0
                var twiddleImaginary = 0.0
                for (index in 0 until length / 2) {
                    val even = start + index; val odd = even + length / 2
                    val oddReal = real[odd] * twiddleReal - imaginary[odd] * twiddleImaginary
                    val oddImaginary = real[odd] * twiddleImaginary + imaginary[odd] * twiddleReal
                    real[odd] = real[even] - oddReal; imaginary[odd] = imaginary[even] - oddImaginary
                    real[even] += oddReal; imaginary[even] += oddImaginary
                    val nextReal = twiddleReal * stepReal - twiddleImaginary * stepImaginary
                    twiddleImaginary = twiddleReal * stepImaginary + twiddleImaginary * stepReal
                    twiddleReal = nextReal
                }
                start += length
            }
            length *= 2
        }
        val regional = DoubleArray(3)
        var total = 0.0
        val binHz = samplingRateMilliHz / 1000.0 / count
        // vDSP_fft_zrip's forward real transform is twice a conventional DFT. Its packed
        // index zero contains DC in realp[0] and Nyquist in imagp[0]; both contribute to
        // total power but the packed index is labelled 0 Hz by the original band loop.
        for (bin in 0 until count / 2) {
            val power = 4.0 * if (bin == 0) real[0] * real[0] + real[count / 2] * real[count / 2]
                else real[bin] * real[bin] + imaginary[bin] * imaginary[bin]
            total += power
            val hz = bin * binHz
            val region = when { hz > 20.0 && hz <= 300.0 -> 0
                hz > 300.0 && hz <= 3500.0 -> 1
                hz > 3500.0 && hz <= 24000.0 -> 2
                else -> -1 }
            if (region >= 0) regional[region] += power
        }
        if (!total.isFinite() || total <= 0.0 || total > Float.MAX_VALUE) return BackgroundAudioFrame.SILENT
        return BackgroundAudioFrame((regional[0] / total).toFloat(), (regional[1] / total).toFloat(),
            (regional[2] / total).toFloat(), total.toFloat(), 0L, true)
    }
}
