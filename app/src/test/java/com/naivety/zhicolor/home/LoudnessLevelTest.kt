package com.naivety.zhicolor.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LoudnessLevelTest {
    @Test fun unsignedPcmSilenceAndEmptyInputProduceZero() {
        assertEquals(0f, LoudnessLevel.fromWaveform(byteArrayOf()), 0f)
        assertEquals(0f, LoudnessLevel.fromWaveform(ByteArray(1024) { 0x80.toByte() }), 0f)
    }

    @Test fun signedStorageDoesNotChangeUnsignedPcmAmplitude() {
        assertEquals(0.5f, LoudnessLevel.fromWaveform(byteArrayOf(0x40, 0xc0.toByte())), 0.000001f)
        assertEquals(1f, LoudnessLevel.fromWaveform(byteArrayOf(0)), 0.000001f)
        assertEquals(127f / 128f, LoudnessLevel.fromWaveform(byteArrayOf(0xff.toByte())), 0.000001f)
    }

    @Test fun rmsDependsOnTotalEnergyInsteadOfTheLargestSample() {
        val waveform = byteArrayOf(0x00, 0x80.toByte(), 0x80.toByte(), 0x80.toByte())
        assertEquals(0.5f, LoudnessLevel.fromWaveform(waveform), 0.000001f)
    }

    @Test fun millibelsUseAmplitudeRatherThanPowerConversion() {
        assertEquals(1f, LoudnessLevel.fromMillibels(0), 0f)
        assertEquals(0.1f, LoudnessLevel.fromMillibels(-2000), 0.000001f)
        assertEquals(0.01f, LoudnessLevel.fromMillibels(-4000), 0.000001f)
    }

    @Test fun silenceFloorAndOutOfRangeMeasurementsStayBounded() {
        assertEquals(0f, LoudnessLevel.fromMillibels(-9600), 0f)
        assertEquals(0f, LoudnessLevel.fromMillibels(Int.MIN_VALUE), 0f)
        assertEquals(1f, LoudnessLevel.fromMillibels(Int.MAX_VALUE), 0f)
        assertTrue(LoudnessLevel.fromMillibels(-9599) in 0f..1f)
    }
}
