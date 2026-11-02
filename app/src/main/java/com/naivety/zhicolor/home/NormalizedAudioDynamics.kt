package com.naivety.zhicolor.home

import kotlin.math.exp
import kotlin.math.expm1
import kotlin.math.ln1p
import kotlin.math.pow
import kotlin.math.sqrt

/** Restores slow relative dynamics after Visualizer normalizes its eight-bit capture. */
internal class NormalizedAudioDynamics {
    data class Frame(val bands: List<Float>, val loudness: Float, val gain: Float)

    private var reference: Double? = null

    fun process(bands: List<Float>, normalizedRms: Float, rawRms: Float?, elapsedSeconds: Float): Frame {
        val level = if (normalizedRms.isFinite()) normalizedRms.coerceIn(0f, 1f) else 0f
        val raw = rawRms?.let { if (it.isFinite()) it.toDouble().coerceIn(0.0, 1.0) else 0.0 }
        if (level <= 0f || (raw != null && raw <= RAW_SILENCE_FLOOR)) {
            return Frame(List(bands.size) { 0f }, 0f, 0f)
        }
        val gain = if (raw == null) {
            // A missing meter never invents energy: the actual normalized capture is still required.
            1.0
        } else {
            val previous = reference ?: raw
            val dt = if (elapsedSeconds.isFinite()) elapsedSeconds.toDouble().coerceIn(0.0, 0.25) else 0.0
            val next = previous + (raw - previous) * (1.0 - exp(-dt / REFERENCE_SECONDS))
            reference = next
            (raw / next).pow(DYNAMICS_EXPONENT).coerceIn(0.0, MAX_GAIN)
        }
        return Frame(
            bands = bands.map { band ->
                val display = if (band.isFinite()) band.toDouble().coerceIn(0.0, 1.0) else 0.0
                (ln1p(expm1(display * MAGNITUDE_SCALE) * gain) / MAGNITUDE_SCALE).toFloat().coerceIn(0f, 1f)
            },
            loudness = (level * gain).toFloat().coerceIn(0f, 1f),
            gain = gain.toFloat(),
        )
    }

    private companion object {
        const val RAW_SILENCE_FLOOR = 0.000001
        const val REFERENCE_SECONDS = 8.0
        const val DYNAMICS_EXPONENT = 0.65
        const val MAX_GAIN = 3.0
        val MAGNITUDE_SCALE = ln1p(128.0 * sqrt(2.0))
    }
}
