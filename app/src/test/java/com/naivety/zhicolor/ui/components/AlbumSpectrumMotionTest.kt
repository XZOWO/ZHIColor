package com.naivety.zhicolor.ui.components

import kotlin.math.PI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlbumSpectrumMotionTest {
    @Test fun displayPreservesUpstreamAmplitudesWithoutAnotherGainStage() {
        for (energy in listOf(0f, 0.005f, 0.03f, 0.1f, 0.3f, 0.6f, 1f)) {
            assertEquals(energy, AlbumSpectrumMotion.spectrumStrength(energy), 0f)
        }
    }

    @Test fun weakHighFrequencyBandsRemainVisibleWithoutReversingEnergyOrder() {
        val inputs = listOf(0f, .001f, .005f, .02f, .08f, .2f, .6f, 1f)
        val outputs = inputs.map { AlbumSpectrumMotion.visibleStrength(it, .5f) }
        assertTrue(outputs.zipWithNext().all { (a, b) -> a < b })
        assertTrue(outputs[2] > .13f)
        assertTrue(outputs[4] > .35f)
        assertEquals(1f, outputs.last(), .00001f)
        assertEquals(0f, AlbumSpectrumMotion.visibleStrength(0f, 0f), 0f)
        assertTrue(AlbumSpectrumMotion.visibleStrength(.005f, .8f) > AlbumSpectrumMotion.visibleStrength(.005f, .1f))
    }

    @Test fun everyBandOwnsOneDistinctAngleAroundTheWholeCircle() {
        val angles = List(AlbumSpectrumMotion.BAND_COUNT) { AlbumSpectrumMotion.angleRadians(it) }
        assertEquals(48, angles.distinct().size)
        assertEquals(-PI / 2.0, angles.first(), 0.0000001)
        for (index in 1 until angles.size) {
            assertEquals(2.0 * PI / 48, angles[index] - angles[index - 1], 0.0000001)
        }
        assertEquals(2.0 * PI / 48, angles.first() + 2.0 * PI - angles.last(), 0.0000001)
    }

    @Test fun spectrumAndHaloFitTheOriginalCardWithoutResizingTheCover() {
        assertEquals(96f, AlbumSpectrumMotion.CANVAS_DP, 0f)
        val half = AlbumSpectrumMotion.CANVAS_DP / 2f
        assertTrue(AlbumSpectrumMotion.BASE_RADIUS_DP > 56f / 2f)
        assertEquals(AlbumSpectrumMotion.COVER_RADIUS_DP,
            AlbumSpectrumMotion.BASE_RADIUS_DP - AlbumSpectrumMotion.RAY_WIDTH_DP / 2f, 0f)
        assertTrue(AlbumSpectrumMotion.BASE_RADIUS_DP + AlbumSpectrumMotion.SPECTRUM_LIFT_DP +
            AlbumSpectrumMotion.RAY_WIDTH_DP / 2f < half)
        assertTrue(AlbumSpectrumMotion.GLOW_MAX_RADIUS_DP < half)
    }

    @Test fun percussionRespondsWithinOneFrameInsteadOfSlowlySwelling() {
        assertTrue(AlbumSpectrumMotion.stepBand(0f, 1f, 1f / 60f) > 0.74f)
        assertTrue(AlbumSpectrumMotion.stepBand(0f, 1f, 0.034f) > 0.94f)
    }

    @Test fun spectrumReleaseLeavesRoomForTheNextBeat() {
        val after = AlbumSpectrumMotion.stepBand(1f, 0f, 0.25f)
        assertTrue(after < 0.1f)
        assertTrue(after > 0f)
    }

    @Test fun haloTracksLoudnessWithoutTheOldLongTail() {
        assertTrue(AlbumSpectrumMotion.stepGlow(0f, 1f, 0.06f) > 0.86f)
        assertTrue(AlbumSpectrumMotion.stepGlow(1f, 0f, 0.35f) < 0.09f)
        assertTrue(AlbumSpectrumMotion.stepGlow(1f, 0f, 0.75f) < 0.005f)
    }

    @Test fun steadyLevelsConvergeWithoutFlippingTheirRelativeStrength() {
        var quiet = 0f
        var loud = 0f
        repeat(60) {
            quiet = AlbumSpectrumMotion.stepBand(quiet, 0.2f, 1f / 60f)
            loud = AlbumSpectrumMotion.stepBand(loud, 0.8f, 1f / 60f)
            assertTrue(loud >= quiet)
        }
        assertEquals(0.2f, quiet, 0.00001f)
        assertEquals(0.8f, loud, 0.00001f)
        assertEquals(4f, loud / quiet, 0.00001f)
    }

    @Test fun envelopesAreIndependentOfRefreshRate() {
        fun decay(fps: Int): Pair<Float, Float> {
            var band = 1f
            var glow = 1f
            repeat(fps) {
                band = AlbumSpectrumMotion.stepBand(band, 0f, 1f / fps)
                glow = AlbumSpectrumMotion.stepGlow(glow, 0f, 1f / fps)
            }
            return band to glow
        }
        val reference = decay(60)
        for (fps in listOf(30, 90, 120)) {
            assertEquals(reference.first, decay(fps).first, 0.00001f)
            assertEquals(reference.second, decay(fps).second, 0.00001f)
        }
    }

    @Test fun silenceNeverCreatesMotionOrMinimumEnergy() {
        repeat(120) {
            assertEquals(0f, AlbumSpectrumMotion.stepBand(0f, 0f, 1f / 60f), 0f)
            assertEquals(0f, AlbumSpectrumMotion.stepGlow(0f, 0f, 1f / 60f), 0f)
        }
        assertEquals(31f, AlbumSpectrumMotion.glowRadiusDp(0f), 0f)
    }

    @Test fun invalidInputsDoNotPoisonTheAnimationState() {
        for (invalid in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertEquals(0f, AlbumSpectrumMotion.spectrumStrength(invalid), 0f)
            assertTrue(AlbumSpectrumMotion.stepBand(invalid, 0.5f, 0.016f).isFinite())
            assertEquals(0.5f, AlbumSpectrumMotion.stepGlow(0.5f, 1f, invalid), 0f)
        }
        assertEquals(0f, AlbumSpectrumMotion.spectrumStrength(-1f), 0f)
        assertEquals(1f, AlbumSpectrumMotion.spectrumStrength(2f), 0f)
        assertEquals(0.5f, AlbumSpectrumMotion.stepBand(0.5f, 1f, -1f), 0f)
    }
}
