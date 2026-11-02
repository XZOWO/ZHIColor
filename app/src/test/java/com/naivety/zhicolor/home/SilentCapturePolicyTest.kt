package com.naivety.zhicolor.home

import org.junit.Assert.*
import org.junit.Test

class SilentCapturePolicyTest {
    @Test fun sustainedDigitalSilenceTriggersOnlyOneAttempt() {
        val policy = SilentCapturePolicy()
        repeat(23) { assertFalse(policy.observe(0f, 0f, 0.05f, 60)) }
        var attempts = 0
        repeat(100) { if (policy.observe(0f, 0f, 0.05f, 60)) attempts++ }
        assertEquals(1, attempts)
    }
    @Test fun quietMusicDoesNotTriggerFallback() {
        val policy = SilentCapturePolicy()
        repeat(1000) { assertFalse(policy.observe(0.00002f, 0.00002f, 0.05f, 1)) }
    }
    @Test fun muteDoesNotCreateACaptureAttempt() {
        val policy = SilentCapturePolicy()
        repeat(1000) { assertFalse(policy.observe(0f, 0f, 0.05f, 0)) }
    }
    @Test fun soundResetsTheSilenceWindow() {
        val policy = SilentCapturePolicy()
        repeat(20) { assertFalse(policy.observe(0f, 0f, 0.05f, 60)) }
        assertFalse(policy.observe(.2f, .1f, .05f, 60))
        repeat(20) { assertFalse(policy.observe(0f, 0f, 0.05f, 60)) }
    }
    @Test fun pausedCallbacksAndInvalidTimeDoNotCountAsDigitalSilence() {
        val policy = SilentCapturePolicy()
        for (dt in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            repeat(100) { assertFalse(policy.observe(0f, 0f, dt, 60)) }
        }
    }
    @Test fun validRawMeterPreventsFalseFailureFromAQuietWaveform() {
        val policy = SilentCapturePolicy()
        repeat(100) { assertFalse(policy.observe(0f, .001f, .05f, 60)) }
    }
}
