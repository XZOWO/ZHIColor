package com.naivety.zhicolor.home

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow

/**
 * One adaptive scale for the entire spectrum, halo and backdrop.
 *
 * Visualizer normalizes EACH capture buffer by its peak, so its FFT alone is not a loudness
 * meter. Use that FFT for instantaneous spectral shape, and the independent pre-normalization
 * meter for relative musical dynamics. The meter averages several mixer buffers: spectral
 * flux and newly observed meter peaks supply the attack rather than prolonging the release.
 * No frequency has its own gain: a weak bin cannot overtake a stronger bin in the same frame.
 */
internal class MusicDynamics {
    data class Frame(
        val bands: List<Float>,
        val loudness: Float,
        val onset: Float,
        val reference: Float,
        val gain: Float,
    )

    private data class Observation(val time: Double, val level: Double, val peak: Double)
    private val history = ArrayDeque<Observation>()
    private var time = 0.0
    private var levelReference = 0.0
    private var spectralReference = 0.0
    private var previousShape = FloatArray(SpectrumBands.COUNT)
    private var previousMeterPeak = 0.0
    private var volumeKey: Int? = null
    private var rebaseSeconds = 0.0
    private var quietSeconds = 0.0
    private var initialized = false

    fun reset() {
        history.clear()
        time = 0.0
        levelReference = 0.0
        spectralReference = 0.0
        previousShape.fill(0f)
        previousMeterPeak = 0.0
        volumeKey = null
        rebaseSeconds = 0.0
        quietSeconds = 0.0
        initialized = false
    }

    fun process(
        linearBands: List<Float>,
        normalizedRms: Float,
        rawRms: Float?,
        rawPeak: Float?,
        elapsedSeconds: Float,
        outputVolume: Int? = null,
    ): Frame {
        val dt = finite(elapsedSeconds).coerceIn(0.0, 0.25)
        time += dt
        val input = FloatArray(SpectrumBands.COUNT) { finite(linearBands.getOrElse(it) { 0f }).toFloat() }
        val peak = input.maxOrNull()?.toDouble() ?: 0.0
        val wave = finite(normalizedRms)
        val measured = rawRms?.let(::finite)
        val level = measured ?: wave
        val meterPeak = rawPeak?.let(::finite) ?: level
        if (outputVolume != null && volumeKey != null && outputVolume != volumeKey) {
            // A user's volume button is not a musical accent. Rebase only the loudness meter;
            // normalized FFT shape remains unchanged and no per-frequency gain is relearned.
            rebaseSeconds = 0.35
            history.clear()
            levelReference = level
            previousMeterPeak = meterPeak
        }
        if (outputVolume != null) volumeKey = outputVolume
        if (peak <= 0.0 || wave <= 0.0 || level <= 0.000001 || outputVolume == 0) {
            quietSeconds += dt
            previousShape.fill(0f)
            previousMeterPeak = 0.0
            if (quietSeconds >= 1.5) {
                history.clear()
                initialized = false
            }
            return Frame(List(SpectrumBands.COUNT) { 0f }, 0f, 0f, levelReference.toFloat(), 0f)
        }
        quietSeconds = 0.0
        val shape = FloatArray(input.size) { (input[it] / max(wave, 0.001)).toFloat() }
        val shapePeak = shape.maxOrNull()!!.toDouble()
        val firstFrame = !initialized
        if (firstFrame) {
            levelReference = level
            spectralReference = shapePeak
            previousMeterPeak = meterPeak
            initialized = true
        }
        history.addLast(Observation(time, level, shapePeak))
        while (history.isNotEmpty() && history.first().time < time - 3.0) history.removeFirst()
        // A high percentile follows changing mastering/arrangement, not one amplified quiet bin.
        val levelTarget = percentile(history.map { it.level }, 0.80)
        val spectralTarget = percentile(history.map { it.peak }, 0.85)
        val rebasing = rebaseSeconds > 0.0
        rebaseSeconds = max(0.0, rebaseSeconds - dt)
        levelReference = smoothLog(levelReference, levelTarget, dt, if (rebasing) 0.075 else 1.25)
        spectralReference = smoothLog(spectralReference, spectralTarget, dt, 1.0)

        var positiveFlux = 0.0
        var previousEnergy = 0.0
        for (i in shape.indices) {
            positiveFlux += max(0.0, shape[i].toDouble() - previousShape[i])
            previousEnergy += previousShape[i]
        }
        val flux = if (firstFrame || rebasing) 0.0 else
            (positiveFlux / max(previousEnergy, spectralReference * 2.0)).coerceIn(0.0, 1.0)
        val peakRise = if (firstFrame || rebasing || previousMeterPeak <= 0.000001) 0.0 else
            ((meterPeak / previousMeterPeak - 1.0) * 0.8).coerceIn(0.0, 1.0)
        val onset = max(flux, peakRise)
        previousShape = shape
        previousMeterPeak = meterPeak
        val relativeLevel = (level / max(levelReference, 0.000001)).pow(0.70).coerceIn(0.0, 2.5)
        val commonGain = relativeLevel * (1.0 + 0.35 * onset) / max(spectralReference, 0.000001)
        // A monotonic common soft knee leaves headroom for accents and never changes bin order.
        val output = shape.map { (1.0 - exp(-1.05 * it * commonGain)).toFloat().coerceIn(0f, 1f) }
        val loudness = (1.0 - exp(-0.72 * relativeLevel * (1.0 + 0.65 * onset)))
            .toFloat().coerceIn(0f, 1f)
        return Frame(output, loudness, onset.toFloat(), levelReference.toFloat(), commonGain.toFloat())
    }

    private fun finite(value: Float): Double = if (value.isFinite()) value.toDouble().coerceIn(0.0, 1.0) else 0.0
    private fun percentile(values: List<Double>, percentile: Double): Double {
        val sorted = values.sorted()
        return sorted[((sorted.size - 1) * percentile).toInt()]
    }
    private fun smoothLog(current: Double, target: Double, dt: Double, seconds: Double): Double =
        exp(ln(max(current, 0.000001)) +
            (ln(max(target, 0.000001)) - ln(max(current, 0.000001))) * (1.0 - exp(-dt / seconds)))
}
