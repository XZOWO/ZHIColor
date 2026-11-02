package com.naivety.zhicolor.home

import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.*
import org.junit.Test

class WaveformBackgroundAudioTest {
    private fun tone(rate: Int, hz: Double, amplitude: Double = 0.4, size: Int = 4096) =
        FloatArray(size) { (amplitude * sin(2.0 * PI * hz * it / rate)).toFloat() }

    @Test fun rawFractionsKeepRegionsAtBothRates() {
        for (rate in listOf(44100, 48000)) for ((hz, region) in listOf(100.0 to 0, 750.0 to 1, 6000.0 to 2)) {
            val report = RawBackgroundSpectrum().analyze(tone(rate, hz), rate * 1000)
            val values = listOf(report.low, report.mid, report.high)
            assertTrue(values[region] > .98f)
            values.forEachIndexed { index, value -> if (index != region) assertTrue(value < .02f) }
            assertTrue(report.level > 1f); assertTrue(report.calibrated)
        }
    }

    @Test fun lowBassAndUpperTrebleAreNotLimitedByDisplayBands() {
        for (rate in listOf(44100, 48000)) {
            assertTrue(RawBackgroundSpectrum().analyze(tone(rate, 30.0, size = 32768), rate * 1000).low > .98f)
            assertTrue(RawBackgroundSpectrum().analyze(tone(rate, 20000.0), rate * 1000).high > .98f)
        }
    }

    @Test fun boundsAreLowerExclusiveUpperInclusive() {
        for ((hz, expected) in listOf(20.0 to -1, 30.0 to 0, 300.0 to 0, 310.0 to 1, 3500.0 to 1, 3510.0 to 2)) {
            val r = RawBackgroundSpectrum().analyze(tone(40960, hz), 40960000)
            listOf(r.low, r.mid, r.high).forEachIndexed { i, actual -> assertEquals(if (i == expected) 1f else 0f, actual, .00001f) }
        }
        assertEquals(1f, RawBackgroundSpectrum().analyze(tone(96000, 24000.0), 96000000).high, .00001f)
        assertEquals(0f, RawBackgroundSpectrum().analyze(tone(96000, 24023.4375), 96000000).high, .00001f)
    }

    @Test fun totalIsUnscaledVdspForwardRealFftPower() {
        val r = RawBackgroundSpectrum().analyze(tone(48000, 750.0), 48000000)
        assertEquals((4096.0 * 4096 * .4 * .4).toFloat(), r.level, r.level * .00001f)
        assertEquals(1f, r.mid, .00001f)
    }

    @Test fun volumeScalesTotalQuadraticallyButKeepsFractions() {
        val a = RawBackgroundSpectrum().analyze(tone(48000, 750.0, .6), 48000000)
        val b = RawBackgroundSpectrum().analyze(tone(48000, 750.0, .15), 48000000)
        assertEquals(a.level, b.level * 16f, a.level * .00001f); assertEquals(a.mid, b.mid, .00001f)
    }

    @Test fun silenceInvalidInputAndPackedDcNyquistDoNotCreateBandEnergy() {
        val fft = RawBackgroundSpectrum()
        assertEquals(BackgroundAudioFrame.SILENT, fft.analyze(FloatArray(1024), 48000000))
        assertEquals(BackgroundAudioFrame.SILENT, fft.analyze(FloatArray(7), 48000000))
        assertEquals(BackgroundAudioFrame.SILENT, fft.analyze(FloatArray(1024) { Float.NaN }, 48000000))
        for (pcm in listOf(FloatArray(1024) { .5f }, FloatArray(1024) { if (it % 2 == 0) .5f else -.5f })) {
            val r = fft.analyze(pcm, 48000000)
            assertEquals(0f, r.low, 0f); assertEquals(0f, r.mid, 0f); assertEquals(0f, r.high, 0f); assertTrue(r.level > 1f)
        }
    }

    @Test fun stereoArithmeticMeanCancelsOppositePhaseBeforeFft() {
        val pcm = tone(48000, 750.0); val fft = RawBackgroundSpectrum()
        val original = fft.analyze(pcm, 48000000)
        assertEquals(original, fft.analyzeStereo(pcm, pcm, 48000000))
        assertEquals(BackgroundAudioFrame.SILENT, fft.analyzeStereo(pcm, FloatArray(pcm.size) { -pcm[it] }, 48000000))
        val single = fft.analyzeStereo(pcm, FloatArray(pcm.size), 48000000)
        assertEquals(original.level / 4f, single.level, original.level * .00001f)
    }

    @Test fun normalizedVisualizerKeepsCapturePowerAndFractions() {
        val raw = BackgroundAudioFrame(.2f, .7f, .1f, 10000f, 0L)
        val restored = raw.fromNormalizedCapture(.04f, .4f, 5678L)
        assertEquals(raw.low, restored.low, 0f); assertEquals(raw.mid, restored.mid, 0f); assertEquals(raw.high, restored.high, 0f)
        assertEquals(raw.level, restored.level, 0f); assertEquals(5678L, restored.timestampNanos); assertFalse(restored.calibrated)
        val estimated = raw.fromNormalizedCapture(null, .4f, 6789L)
        assertEquals(raw.level, estimated.level, 0f); assertFalse(estimated.calibrated)
        assertEquals(BackgroundAudioFrame.SILENT.copy(timestampNanos = 7890L), raw.fromNormalizedCapture(.1f, 0f, 7890L))
    }

    @Test fun lowOutputMeterDoesNotEraseNormalizedMusic() {
        val capture = WaveformSpectrum().analyze(tone(48000, 93.75, .4, 1024), 48000000)
        for (meter in listOf(.001f, .002f, .0038f, .04f, .4f)) {
            val report = capture.backgroundAudio.fromNormalizedCapture(meter, capture.rms, 1234L)
            assertEquals(capture.backgroundAudio.level, report.level, 0f)
            assertEquals(capture.backgroundAudio.low, report.low, 0f)
            assertFalse(report.calibrated)
        }
    }

    @Test fun normalizedInputPreservesSilenceAndRejectsNonfinitePower() {
        val raw = BackgroundAudioFrame(.8f, .15f, .05f, 10000f, 0L)
        assertEquals(BackgroundAudioFrame.SILENT.copy(timestampNanos = 12L), raw.fromNormalizedCapture(0f, .4f, 12L))
        for (rms in listOf(0f, -.1f, Float.NaN, Float.POSITIVE_INFINITY))
            assertEquals(BackgroundAudioFrame.SILENT.copy(timestampNanos = 12L), raw.fromNormalizedCapture(.001f, rms, 12L))
        for (meter in listOf(null, -.1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            val report = raw.fromNormalizedCapture(meter, .4f, 12L)
            assertEquals(raw.level, report.level, 0f); assertFalse(report.calibrated)
        }
        for (level in listOf(-1f, Float.NaN, Float.POSITIVE_INFINITY))
            assertEquals(0f, raw.copy(level = level).fromNormalizedCapture(.001f, .4f, 12L).level, 0f)
    }
}
