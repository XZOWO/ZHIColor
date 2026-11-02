package com.naivety.zhicolor.home

import kotlin.math.floor
import kotlin.math.ln
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpectrumBandsTest {
    private val rate = 48_000_000

    @Test
    fun silenceProducesZeroBands() {
        assertSilent(SpectrumBands.fromFft(ByteArray(1024), rate))
    }

    @Test
    fun dcIsNeverTreatedAsAnAudioBin() {
        val fft = ByteArray(1024)
        fft[0] = 127
        assertSilent(SpectrumBands.fromFft(fft, rate))
        fft[0] = -128
        assertSilent(SpectrumBands.fromFft(fft, rate))
    }

    @Test
    fun nyquistIsNeverTreatedAsAnAudioBin() {
        val fft = ByteArray(1024)
        fft[1] = -128
        assertSilent(SpectrumBands.fromFft(fft, rate))
    }

    @Test
    fun lowFrequencyPeakAppearsInItsLogarithmicBand() {
        assertPeakBand(bin = 8, size = 4096)
    }

    @Test
    fun middleFrequencyPeakAppearsInItsLogarithmicBand() {
        assertPeakBand(bin = 128, size = 4096)
    }

    @Test
    fun highFrequencyPeakAppearsInItsLogarithmicBand() {
        assertPeakBand(bin = 1024, size = 4096)
    }

    @Test
    fun negativeSignedComponentsHaveTheSameMagnitudeAsPositiveComponents() {
        val positive = ByteArray(1024).apply { this[64] = 73; this[65] = 41 }
        val negative = ByteArray(1024).apply { this[64] = -73; this[65] = -41 }
        assertEquals(SpectrumBands.fromFft(positive, rate), SpectrumBands.fromFft(negative, rate))
    }

    @Test
    fun minimumSignedComponentsRemainFiniteAndReachFullScale() {
        val fft = ByteArray(1024).apply { this[64] = -128; this[65] = -128 }
        val bands = SpectrumBands.fromFft(fft, rate)
        assertValid(bands)
        assertEquals(1f, bands.maxOrNull()!!, 0.000001f)
    }

    @Test
    fun invalidSamplingRatesProduceZeroBands() {
        for (sampleRate in listOf(Int.MIN_VALUE, -1, 0, 1, 79_999, 80_000)) {
            assertSilent(SpectrumBands.fromFft(ByteArray(1024) { 127 }, sampleRate))
        }
    }

    @Test
    fun smallInputsProduceZeroBands() {
        for (size in 0..7) assertSilent(SpectrumBands.fromFft(ByteArray(size) { 127 }, rate))
    }

    @Test
    fun oddPackedInputsProduceZeroBands() {
        assertSilent(SpectrumBands.fromFft(ByteArray(1023) { 127 }, rate))
    }

    @Test
    fun frequenciesAboveSixteenKilohertzAreExcluded() {
        val fft = ByteArray(4096).apply { this[2 * 1000] = 127 }
        assertSilent(SpectrumBands.fromFft(fft, 96_000_000))
    }

    @Test
    fun frequenciesBelowFortyHertzAreExcluded() {
        val fft = ByteArray(4096).apply { this[2] = 127; this[3] = -128 }
        assertSilent(SpectrumBands.fromFft(fft, rate))
    }

    @Test
    fun outputAlwaysContainsFortyEightFiniteNormalizedBands() {
        val fft = ByteArray(1024) { ((it * 29) % 256 - 128).toByte() }
        for (sampleRate in listOf(8_000_000, 44_100_000, rate, 96_000_000, Int.MAX_VALUE)) {
            assertValid(SpectrumBands.fromFft(fft, sampleRate))
        }
    }

    @Test
    fun emptyLogarithmicBandsReuseRealBinsWithoutAddingEnergy() {
        val fft = ByteArray(128).apply { this[2] = 80 }
        val bands = SpectrumBands.fromFft(fft, rate)
        assertValid(bands)
        assertTrue(bands.any { it > 0f })
        assertTrue(bands.last() == 0f)
    }

    @Test
    fun samplingRatesKeepPeaksInFixedFrequencyBands() {
        for (sampleRate in listOf(8_000_000, 16_000_000, 22_050_000, 44_100_000, rate)) {
            val bin = 256
            val size = 4096
            val fft = ByteArray(size).apply { this[2 * bin] = 127 }
            val frequency = bin * (sampleRate / 1_000.0) / size
            val expectedBand = floor(ln(frequency / 40.0) / ln(16_000.0 / 40.0) * SpectrumBands.COUNT).toInt()
            val bands = SpectrumBands.fromFft(fft, sampleRate)
            assertValid(bands)
            assertEquals(listOf(expectedBand), bands.indices.filter { bands[it] > 0f })
        }
    }

    @Test
    fun bandsWhollyAboveNyquistDoNotRepeatTheLastAvailableBin() {
        for (sampleRate in listOf(8_000_000, 16_000_000, 22_050_000)) {
            val fft = ByteArray(128).apply { this[size - 2] = 127 }
            val bands = SpectrumBands.fromFft(fft, sampleRate)
            val nyquist = sampleRate / 2_000.0
            val outOfRange = bands.indices.filter {
                40.0 * kotlin.math.exp(ln(16_000.0 / 40.0) * it / SpectrumBands.COUNT) >= nyquist
            }
            assertTrue(outOfRange.isNotEmpty())
            assertTrue(bands.any { it > 0f })
            assertTrue(outOfRange.all { bands[it] == 0f })
        }
    }

    @Test
    fun lowRateEmptyInRangeBandsStillReuseTheNearestAvailableBin() {
        val fft = ByteArray(128).apply { this[2] = 80 }
        val bands = SpectrumBands.fromFft(fft, 16_000_000)
        assertValid(bands)
        // The first 40 Hz band has no 125 Hz-spaced FFT bin, but is below Nyquist.
        assertTrue(bands.first() > 0f)
        assertEquals(0f, bands.last(), 0f)
    }

    @Test
    fun increasingInputMagnitudeIncreasesBandMagnitude() {
        val quiet = ByteArray(1024).apply { this[64] = 10 }
        val loud = ByteArray(1024).apply { this[64] = 100 }
        assertTrue(SpectrumBands.fromFft(loud, rate).maxOrNull()!! >
            SpectrumBands.fromFft(quiet, rate).maxOrNull()!!)
    }

    private fun assertPeakBand(bin: Int, size: Int) {
        val fft = ByteArray(size).apply { this[2 * bin] = 127 }
        val frequency = bin * (rate / 1_000.0) / size
        val expectedBand = floor(ln(frequency / 40.0) / ln(16_000.0 / 40.0) * SpectrumBands.COUNT).toInt()
        val bands = SpectrumBands.fromFft(fft, rate)
        assertValid(bands)
        assertTrue("Expected energy at $frequency Hz in band $expectedBand", bands[expectedBand] > 0f)
        assertEquals(listOf(expectedBand), bands.indices.filter { bands[it] > 0f })
    }

    private fun assertSilent(bands: List<Float>) {
        assertValid(bands)
        assertTrue(bands.all { it == 0f })
    }

    private fun assertValid(bands: List<Float>) {
        assertEquals(SpectrumBands.COUNT, bands.size)
        assertTrue(bands.all { it.isFinite() && it in 0f..1f })
    }
}
