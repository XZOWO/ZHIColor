package com.naivety.zhicolor.advanced

import com.naivety.zhicolor.home.MusicDynamics
import com.naivety.zhicolor.home.SilentCapturePolicy
import org.junit.Assert.*
import org.junit.Test

class AdvancedSilentAudioTest {
    @Test fun outputMuteAlwaysProducesRealZeroDynamicsEvenWithNonzeroCapture() {
        val frame = MusicDynamics().process(List(48) { .8f }, .3f, .2f, .7f, 1f / 60f, 0)
        assertTrue(frame.bands.all { it == 0f })
        assertEquals(0f, frame.loudness, 0f)
        assertEquals(0f, frame.onset, 0f)
    }

    @Test fun noSignalDoesNotInventAudioDynamics() {
        val frame = MusicDynamics().process(emptyList(), 0f, null, null, 1f / 60f, 4)
        assertTrue(frame.bands.all { it == 0f })
        assertEquals(0f, frame.loudness, 0f)
    }

    @Test fun mutedOutputNeverTriggersSilentCaptureRepair() {
        val policy = SilentCapturePolicy()
        repeat(600) { assertFalse(policy.observe(0f, 0f, .25f, 0)) }
    }
}
