package com.naivety.zhicolor.home

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sin

class MusicDynamicsTest {
    private fun bands(vararg points: Pair<Int, Float>) = List(48) { i -> points.firstOrNull { it.first == i }?.second ?: 0f }
    private fun warm(d: MusicDynamics, input: List<Float> = bands(2 to .25f, 20 to .12f, 38 to .04f), rms: Float = .1f) {
        repeat(80) { d.process(input, .4f, rms, rms * 2, .05f, 5) }
    }

    @Test fun quietAndLoudMasteringHaveSameHeadroomAfterAdaptation() {
        val input = bands(3 to .3f, 15 to .12f, 40 to .05f)
        val a = MusicDynamics(); val b = MusicDynamics()
        warm(a, input, .002f); warm(b, input, .2f)
        val quiet = a.process(input, .4f, .002f, .004f, .05f, 5)
        val loud = b.process(input, .4f, .2f, .4f, .05f, 5)
        quiet.bands.zip(loud.bands).forEach { assertEquals(it.first, it.second, .0001f) }
        assertEquals(quiet.loudness, loud.loudness, .0001f)
    }

    @Test fun aDrumAccentRisesImmediatelyAboveSustainedMusic() {
        val d = MusicDynamics(); warm(d)
        val steady = d.process(bands(2 to .25f, 20 to .12f, 38 to .04f), .4f, .1f, .2f, .05f, 5)
        val hit = d.process(bands(2 to .6f, 20 to .14f, 38 to .2f), .45f, .21f, .7f, .05f, 5)
        assertTrue(hit.bands[2] > steady.bands[2] + .20f)
        assertTrue(hit.loudness > steady.loudness + .20f)
        assertTrue(hit.onset > .5f)
    }

    @Test fun quietPassageIsNotPumpedToTheSameHeightAsAnAccent() {
        val d = MusicDynamics(); warm(d)
        val quiet = d.process(bands(2 to .25f, 20 to .12f), .4f, .015f, .025f, .05f, 5)
        val accent = d.process(bands(2 to .25f, 20 to .12f), .4f, .25f, .6f, .05f, 5)
        assertTrue(accent.bands[2] > quiet.bands[2] + .4f)
        assertTrue(accent.loudness > quiet.loudness + .35f)
    }

    @Test fun noWeakFrequencyCanOvertakeAStrongerFrequency() {
        val d = MusicDynamics(); warm(d)
        for (frame in 0..200) {
            val input = List(48) { i -> (((i * 17 + frame * 3) % 49) / 64f) }
            val result = d.process(input, .4f, .1f, .3f, .05f, 5)
            for (i in input.indices) for (j in input.indices) if (input[i] > input[j])
                assertTrue(result.bands[i] >= result.bands[j])
        }
    }

    @Test fun constantVolumeScalingDoesNotChangeFrequencyRatios() {
        val a = MusicDynamics(); val b = MusicDynamics()
        repeat(120) { frame ->
            val level = .04f + .02f * sin(frame * .4f)
            val input = bands(1 to .3f, 24 to .1f, 44 to .02f)
            val x = a.process(input, .4f, level, level * 2f, .05f, 3)
            val y = b.process(input, .4f, level * .02f, level * .04f, .05f, 1)
            x.bands.zip(y.bands).forEach { assertEquals(it.first, it.second, .0001f) }
        }
    }

    @Test fun aVolumeButtonIsNotAMusicalTransient() {
        val d = MusicDynamics(); warm(d)
        val input = bands(2 to .25f, 20 to .12f, 38 to .04f)
        val before = d.process(input, .4f, .1f, .2f, .05f, 5)
        val after = d.process(input, .4f, .01f, .02f, .05f, 2)
        assertEquals(0f, after.onset, 0f)
        assertEquals(before.loudness, after.loudness, .001f)
        before.bands.zip(after.bands).forEach { assertEquals(it.first, it.second, .001f) }
    }

    @Test fun silenceMutesImmediatelyEvenWithStaleMeterValues() {
        val d = MusicDynamics(); warm(d)
        val frame = d.process(List(48) { 0f }, 0f, .2f, .8f, .05f, 5)
        assertTrue(frame.bands.all { it == 0f }); assertEquals(0f, frame.loudness, 0f)
    }

    @Test fun meterSilenceAndSystemMuteNeverBoostNormalizedNoise() {
        val d = MusicDynamics(); warm(d)
        assertEquals(0f, d.process(List(48) { .8f }, .6f, 0f, 0f, .05f, 5).loudness, 0f)
        assertEquals(0f, d.process(List(48) { .8f }, .6f, .2f, .8f, .05f, 0).loudness, 0f)
    }

    @Test fun sparseAndDenseMusicBothLeaveAccentHeadroom() {
        for (input in listOf(bands(3 to .3f), List(48) { .15f + it / 200f })) {
            val d = MusicDynamics(); warm(d, input)
            val frame = d.process(input, .4f, .1f, .2f, .05f, 5)
            assertTrue(frame.bands.maxOrNull()!! in .55f.. .75f)
            assertTrue(frame.loudness in .4f.. .65f)
        }
    }

    @Test fun invalidDataCannotProduceNanOrFakeSignal() {
        val d = MusicDynamics()
        val frame = d.process(listOf(Float.NaN, Float.POSITIVE_INFINITY, -1f), Float.NaN, null, null, Float.NaN)
        assertEquals(48, frame.bands.size); assertTrue(frame.bands.all { it == 0f })
        assertEquals(0f, frame.loudness, 0f)
    }

    @Test fun resetMakesNewTrackIndependentOfThePreviousTrack() {
        val d = MusicDynamics(); warm(d, rms = .7f); d.reset()
        val input = bands(10 to .2f)
        assertEquals(MusicDynamics().process(input,.3f,.005f,.01f,.05f), d.process(input,.3f,.005f,.01f,.05f))
    }

    @Test fun packedFftNeverCopiesBassAcrossEmptyBands() {
        val fft = ByteArray(1024).apply { this[2] = 100 }
        val frame = SpectrumBands.linearFromFft(fft, 48_000_000)
        assertEquals(1, frame.count { it > 0f })
        assertTrue(frame[1] > 0f)
    }

    @Test fun linearFftPreservesAmplitudeRatiosBeforeSharedNormalization() {
        val fft = ByteArray(1024).apply { this[16] = 100; this[128] = 25 }
        val values = SpectrumBands.linearFromFft(fft,48_000_000).filter { it > 0f }.sortedDescending()
        assertEquals(2, values.size); assertEquals(4f, values[0] / values[1], .001f)
    }
}
