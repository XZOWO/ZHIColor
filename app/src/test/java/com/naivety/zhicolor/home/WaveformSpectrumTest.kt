package com.naivety.zhicolor.home

import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sin
import org.junit.Assert.*
import org.junit.Test

class WaveformSpectrumTest {
    @Test fun subBinDisplayPositionsHaveNoArtificialAlternatingHoles() {
        val frame = WaveformSpectrum().analyze(wave(48_000, 93.75 to .65), 48_000_000)
        for (i in 2..10) assertTrue("missing interpolated band $i", frame.bands[i] > .02f)
        assertTrue(frame.bands[band(8000.0)] < .005f)
    }

    @Test fun nativePcmRetainsVeryQuietMusicBelowEightBitQuantization() {
        val samples = FloatArray(2048) { (0.0001 * sin(2 * PI * 750.0 * it / 48000)).toFloat() }
        val frame = WaveformSpectrum().analyze(samples, 48_000_000)
        assertEquals(.0001f, frame.bands[band(750.0)], .000002f)
        assertTrue(frame.rms > .00006f)
    }

    @Test fun floatAndBytePathsHaveTheSameFrequencyContract() {
        val bytes = wave(48_000, 750.0 to .4, 6000.0 to .2)
        val floats = FloatArray(bytes.size) { ((bytes[it].toInt() and 255) - 128) / 128f }
        val a = WaveformSpectrum().analyze(bytes, 48_000_000)
        val b = WaveformSpectrum().analyze(floats, 48_000_000)
        assertEquals(a, b)
    }

    private fun wave(rate: Int = 48_000, vararg tones: Pair<Double, Double>) = ByteArray(1024) { i ->
        (128.0 + 127.0 * tones.sumOf { (hz, amplitude) -> amplitude * sin(2 * PI * hz * i / rate) })
            .roundToInt().coerceIn(0, 255).toByte()
    }
    private fun band(hz: Double) = floor(ln(hz / 40.0) / ln(400.0) * 48).toInt()

    @Test fun bassVoiceAndHiHatStayAtTheirOwnFrequencies() {
        for (hz in listOf(93.75, 750.0, 6000.0, 12000.0)) {
            val frame = WaveformSpectrum().analyze(wave(48_000, hz to .65),48_000_000)
            assertEquals(band(hz), frame.bands.indices.maxByOrNull { frame.bands[it] })
            assertEquals(.65f, frame.bands[band(hz)], .025f)
        }
    }

    @Test fun weakHighFrequencyInformationSurvivesAlongsideBass() {
        val frame = WaveformSpectrum().analyze(wave(48_000, 187.5 to .7, 9000.0 to .025),48_000_000)
        assertTrue(frame.bands[band(9000.0)] > .018f)
        assertTrue(frame.bands[band(187.5)] > frame.bands[band(9000.0)] * 20f)
    }

    @Test fun hannWindowSuppressesNonBinCenteredToneLeakage() {
        val frame = WaveformSpectrum().analyze(wave(48_000, 1000.0 to .8),48_000_000)
        assertTrue(frame.bands[band(8000.0)] < .005f)
        assertTrue(frame.bands[band(1000.0)] > .6f)
    }

    @Test fun silenceAndDcHaveNoMusicalEnergy() {
        val fft = WaveformSpectrum()
        for (value in listOf(0,64,128,255)) {
            val frame=fft.analyze(ByteArray(1024) { value.toByte() },48_000_000)
            assertEquals(0f,frame.rms,0f); assertTrue(frame.bands.all { it == 0f })
        }
    }

    @Test fun inputGainPreservesLinearRatiosBeforeDynamics() {
        val a=WaveformSpectrum().analyze(wave(48_000,750.0 to .8),48_000_000)
        val b=WaveformSpectrum().analyze(wave(48_000,750.0 to .2),48_000_000)
        assertEquals(4f,a.bands[band(750.0)] / b.bands[band(750.0)], .10f)
        assertEquals(4f,a.rms/b.rms,.10f)
    }

    @Test fun sampleRateChangesDoNotShiftFrequencyLabels() {
        for (rate in listOf(16_000,44_100,48_000,96_000)) {
            val frame=WaveformSpectrum().analyze(wave(rate,3000.0 to .7),rate*1000)
            assertEquals(band(3000.0),frame.bands.indices.maxByOrNull { frame.bands[it] })
        }
    }

    @Test fun invalidSizesAndRatesReturnSilence() {
        val fft=WaveformSpectrum()
        for (size in listOf(0,3,7,15,1023)) assertTrue(fft.analyze(ByteArray(size),48_000_000).bands.all { it==0f })
        assertTrue(fft.analyze(wave(48_000,1000.0 to .8),0).bands.all { it==0f })
    }

    @Test fun reusedBuffersDoNotLeaveOldHighFrequencyEnergy() {
        val fft=WaveformSpectrum()
        fft.analyze(wave(48_000,10000.0 to .8),48_000_000)
        val frame=fft.analyze(wave(48_000,187.5 to .8),48_000_000)
        assertTrue(frame.bands[band(10000.0)] < .005f)
    }
}
