package com.naivety.zhicolor.home

import kotlin.math.expm1
import kotlin.math.ln1p
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NormalizedAudioDynamicsTest {
    @Test fun realLowVolumePlaybackIsNotTreatedAsSilence() {
        val frame = NormalizedAudioDynamics().process(bands(8f), 0.32f, 0.000267f, DT)
        assertEquals(0.32f, frame.loudness, 0.000001f)
        assertEquals(8f, magnitude(frame.bands.single()), 0.00001f)
    }

    @Test fun steadyLowAndHighPlaybackVolumesHaveComparableVisualLevels() {
        val low = settle(0.0003f)
        val high = settle(0.03f)
        assertEquals(low.loudness, high.loudness, 0.000001f)
        assertEquals(low.bands.single(), high.bands.single(), 0.000001f)
        assertTrue(low.loudness > 0f)
    }

    @Test fun quietPassagesRemainQuietWithinTheSlowReferenceWindow() {
        val dynamics = NormalizedAudioDynamics()
        val normal = dynamics.process(bands(8f), 0.4f, 0.0008f, DT)
        var quiet = normal
        repeat(40) { quiet = dynamics.process(bands(8f), 0.4f, 0.0002f, DT) }
        assertTrue(quiet.loudness < normal.loudness * 0.5f)
        assertTrue(quiet.loudness > 0f)
        assertEquals(quiet.gain, magnitude(quiet.bands.single()) / 8f, 0.00001f)
    }

    @Test fun rawSilenceAndMissingWaveformNeverCreateEnergy() {
        for (raw in listOf(0f, 0.000001f, Float.NaN)) {
            val frame = NormalizedAudioDynamics().process(bands(8f), 0.4f, raw, DT)
            assertEquals(0f, frame.loudness, 0f)
            assertEquals(listOf(0f), frame.bands)
        }
        for (raw in listOf<Float?>(null, 0.01f)) {
            val frame = NormalizedAudioDynamics().process(bands(8f), 0f, raw, DT)
            assertEquals(0f, frame.loudness, 0f)
            assertEquals(listOf(0f), frame.bands)
        }
    }

    @Test fun unsupportedMeterUsesOnlyTheRealNormalizedCapture() {
        val frame = NormalizedAudioDynamics().process(bands(8f), 0.25f, null, DT)
        assertEquals(0.25f, frame.loudness, 0f)
        assertEquals(1f, frame.gain, 0f)
        assertEquals(8f, magnitude(frame.bands.single()), 0.00001f)
    }

    @Test fun normalizedWaveformCrestFactorStillChangesVisualLoudness() {
        val dynamics = NormalizedAudioDynamics()
        val dense = dynamics.process(bands(8f), 0.5f, 0.001f, DT)
        val sparse = dynamics.process(bands(8f), 0.125f, 0.001f, DT)
        assertEquals(4f, dense.loudness / sparse.loudness, 0.000001f)
    }

    @Test fun invalidInputsAndAbruptBeatsStayFiniteAndBounded() {
        val dynamics = NormalizedAudioDynamics()
        dynamics.process(bands(8f), 0.4f, 0.0001f, DT)
        val frame = dynamics.process(listOf(Float.NaN, Float.POSITIVE_INFINITY, -1f, 1f), 0.8f, 0.9f, Float.NaN)
        assertTrue(frame.gain in 0f..3f)
        assertTrue(frame.loudness.isFinite() && frame.loudness in 0f..1f)
        assertTrue(frame.bands.all { it.isFinite() && it in 0f..1f })
        assertEquals(listOf(0f, 0f, 0f), frame.bands.take(3))
        val invalid = dynamics.process(bands(8f), Float.POSITIVE_INFINITY, 0.001f, DT)
        assertEquals(0f, invalid.loudness, 0f)
    }

    private fun settle(rawRms: Float): NormalizedAudioDynamics.Frame {
        val dynamics = NormalizedAudioDynamics()
        var frame = dynamics.process(bands(8f), 0.32f, rawRms, 0f)
        repeat(600) { frame = dynamics.process(bands(8f), 0.32f, rawRms, DT) }
        return frame
    }

    private fun bands(magnitude: Float) = listOf((ln1p(magnitude.toDouble()) / SCALE).toFloat())
    private fun magnitude(band: Float) = expm1(band.toDouble() * SCALE).toFloat()

    private companion object {
        const val DT = 0.05f
        val SCALE = ln1p(128.0 * sqrt(2.0))
    }
}
