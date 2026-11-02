package com.naivety.zhicolor.home

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.expm1
import kotlin.math.ln
import kotlin.math.ln1p
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveMusicGainTest {
    @Test fun playbackVolumeChangesBecomeComparableWithoutBeingIdentical() {
        val outputs = listOf(0.25f, 1f, 4f).map { scale ->
            settle(AdaptiveMusicGain(), rms = 0.08f * scale, magnitude = 6f * scale)
        }
        val levels = outputs.map { it.loudness }
        val spectralPeaks = outputs.map { magnitude(it.bands.single()) }
        assertTrue("Adjusted levels must preserve low/medium/high ordering: $levels", levels.zipWithNext().all { (a, b) -> b > a })
        assertTrue("16x source difference should be reduced substantially: $levels", levels.last() / levels.first() < 1.9f)
        assertTrue("Partial compensation must leave a real loudness difference: $levels", levels.last() / levels.first() > 1.5f)
        assertTrue(spectralPeaks.last() / spectralPeaks.first() < 1.9f)
        println("AdaptiveMusicGain volume 0.25x/1x/4x: rms=$levels gain=${outputs.map { it.gain }} linearBands=$spectralPeaks")
    }

    @Test fun lowSystemVolumeCanReceiveMoreThanFourTimesGain() {
        val frame = settle(AdaptiveMusicGain(), rms = 0.008f, magnitude = 1f)
        assertTrue(frame.gain > 4f)
        assertTrue(frame.loudness > 0.04f)
        assertTrue(frame.gain in 0.2f..16f)
    }

    @Test fun quietPassageStaysQuietDuringTheSlowReferenceWindow() {
        val processor = AdaptiveMusicGain()
        val normal = settle(processor, 0.08f, 8f)
        var quiet = normal
        repeat(120) { quiet = processor.process(bands(2f), 0.02f, DT) }
        assertTrue("A two-second quiet passage must stay quieter: ${quiet.loudness}", quiet.loudness < normal.loudness * 0.4f)
        assertTrue(quiet.reference > 0.06f)
        println("AdaptiveMusicGain two-second quiet passage: normal=${normal.loudness} quiet=${quiet.loudness} reference=${quiet.reference}")
    }

    @Test fun shortStrongBeatRemainsMuchStrongerThanItsPrecedingMusic() {
        val processor = AdaptiveMusicGain()
        val steady = settle(processor, 0.04f, 4f)
        val beat = processor.process(bands(24f), 0.24f, DT)
        assertTrue(beat.loudness > steady.loudness * 5.8f)
        assertTrue(magnitude(beat.bands.single()) > magnitude(steady.bands.single()) * 5.8f)
        assertTrue(abs(ln(beat.gain / steady.gain)) < 0.01f)
    }

    @Test fun sustainedEnergeticMusicRemainsStrongerThanSustainedQuietMusic() {
        val quiet = settle(AdaptiveMusicGain(), 0.02f, 2f)
        val energetic = settle(AdaptiveMusicGain(), 0.16f, 16f)
        assertTrue(energetic.loudness > quiet.loudness * 1.4f)
        assertTrue(magnitude(energetic.bands.single()) > magnitude(quiet.bands.single()) * 1.4f)
    }

    @Test fun silenceAndLowNoiseNeverBuildPositiveGain() {
        val processor = AdaptiveMusicGain()
        repeat(1800) {
            val frame = processor.process(bands(1f), if (it % 2 == 0) 0f else 0.001f, DT)
            assertEquals(0f, frame.loudness, 0f)
            assertEquals(listOf(0f), frame.bands)
            assertEquals(1f, frame.gain, 0.000001f)
        }
    }

    @Test fun noiseAfterBoostedMusicStaysSilentAndReleasesTheOldGain() {
        val processor = AdaptiveMusicGain()
        var previous = settle(processor, 0.008f, 1f)
        repeat(600) {
            val frame = processor.process(bands(1f), 0.001f, DT)
            assertEquals(0f, frame.loudness, 0f)
            assertEquals(listOf(0f), frame.bands)
            assertTrue(frame.gain <= previous.gain)
            previous = frame
        }
        assertEquals(1f, previous.gain, 0.001f)
        assertEquals(0.08f, previous.reference, 0.000001f)
        val resumed = processor.process(bands(8f), 0.08f, DT)
        assertEquals(0.08f, resumed.loudness, 0.001f)
    }

    @Test fun signalNearTheNoiseGateReceivesNoPositiveBoost() {
        val processor = AdaptiveMusicGain()
        settle(processor, 0.008f, 1f)
        val low = processor.process(bands(1f), 0.0025f, DT)
        assertTrue(low.loudness <= 0.0025f)
        assertTrue(magnitude(low.bands.single()) <= 1f)
    }

    @Test fun gainIsBoundedAndChangesGraduallyAcrossVeryLargeLevelSteps() {
        val processor = AdaptiveMusicGain()
        var previous = processor.process(bands(1f), 0f, DT)
        for (rms in listOf(0.003f, 1f, 0.003f)) {
            repeat(1800) {
                val frame = processor.process(bands(1f), rms, DT)
                assertTrue(frame.gain in 0.2f..16f)
                assertTrue("One frame must not jump the gain", abs(ln(frame.gain / previous.gain)) < 0.04f)
                previous = frame
            }
        }
    }

    @Test fun applyingOneLinearGainPreservesUnsaturatedSpectralRatios() {
        val processor = AdaptiveMusicGain()
        settle(processor, 0.02f, 1f)
        val frame = processor.process(listOf(band(1f), band(3f), band(6f)), 0.02f, DT)
        val magnitudes = frame.bands.map(::magnitude)
        assertEquals(3f, magnitudes[1] / magnitudes[0], 0.0001f)
        assertEquals(6f, magnitudes[2] / magnitudes[0], 0.0001f)
    }

    @Test fun referenceAndGainAreStableAcrossDifferentUiFrameRates() {
        val at30 = settle(AdaptiveMusicGain(), 0.02f, 2f, 30)
        val at120 = settle(AdaptiveMusicGain(), 0.02f, 2f, 120)
        assertEquals(at30.reference, at120.reference, 0.0001f)
        assertEquals(at30.gain, at120.gain, 0.001f)
        assertEquals(at30.loudness, at120.loudness, 0.0001f)
    }

    @Test fun invalidValuesDoNotPoisonSubsequentMusicAndBandsKeepTheirShape() {
        val processor = AdaptiveMusicGain()
        val invalid = listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -1f, Float.MAX_VALUE)
        val silent = processor.process(invalid, Float.NaN, Float.POSITIVE_INFINITY)
        assertEquals(List(invalid.size) { 0f }, silent.bands)
        assertEquals(0f, silent.loudness, 0f)
        assertTrue(processor.process(emptyList(), 0.08f, DT).bands.isEmpty())
        val valid = processor.process(invalid, 0.08f, DT)
        assertTrue(valid.bands.all { it.isFinite() && it in 0f..1f })
        assertEquals(listOf(0f, 0f, 0f, 0f), valid.bands.take(4))
        assertEquals(1f, valid.bands.last(), 0.000001f)
        assertTrue(valid.gain.isFinite() && valid.reference.isFinite())
    }

    @Test fun invalidAndOversizedFrameDeltasCannotSkipTheAdaptation() {
        val processor = AdaptiveMusicGain()
        val fresh = processor.process(bands(1f), 0.02f, 0f)
        for (elapsed in listOf(-1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            val unchanged = processor.process(bands(1f), 0.02f, elapsed)
            assertEquals(fresh, unchanged)
        }
        val oversized = processor.process(bands(1f), 0.02f, 100f)
        val bounded = AdaptiveMusicGain().process(bands(1f), 0.02f, 0.25f)
        assertEquals(bounded, oversized)
    }

    @Test fun resetRestoresTheSameBehaviorAsANewPlaybackSession() {
        val processor = AdaptiveMusicGain()
        settle(processor, 0.008f, 1f)
        processor.reset()
        val resumed = processor.process(bands(4f), 0.04f, DT)
        val fresh = AdaptiveMusicGain().process(bands(4f), 0.04f, DT)
        assertEquals(fresh, resumed)
    }

    private fun settle(processor: AdaptiveMusicGain, rms: Float, magnitude: Float, fps: Int = 60): AdaptiveMusicGain.Frame {
        var frame = processor.process(bands(magnitude), rms, 0f)
        repeat(60 * fps) { frame = processor.process(bands(magnitude), rms, 1f / fps) }
        return frame
    }

    private fun bands(magnitude: Float) = listOf(band(magnitude))
    private fun band(magnitude: Float) = (ln1p(magnitude.toDouble()) / SCALE).toFloat()
    private fun magnitude(band: Float) = expm1(band.toDouble() * SCALE).toFloat()

    private companion object {
        const val DT = 1f / 60f
        val SCALE = ln1p(128.0 * sqrt(2.0))
    }
}
