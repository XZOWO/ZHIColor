package com.naivety.zhicolor.advanced

import org.junit.Assert.*
import org.junit.Test

class AudioCapturePolicyTest {
    private val song = PlaybackCaptureRequest(32, "speaker", "song-a", "player")

    @Test fun activityAndLockscreenShareSameCaptureAndEitherCanRetire() {
        val policy = AudioCapturePolicy<String>()
        policy.update("activity", song)
        policy.update("lockscreen", song)
        assertTrue(policy.receives("activity"))
        assertTrue(policy.receives("lockscreen"))
        policy.update("activity", null)
        assertEquals(song, policy.selected)
        assertTrue(policy.receives("lockscreen"))
        policy.update("lockscreen", null)
        assertNull(policy.selected)
    }

    @Test fun oldScreenHeartbeatDoesNotRevertNewTrackOrRoute() {
        val policy = AudioCapturePolicy<String>()
        val next = song.copy(route = "bluetooth", trackKey = "song-b")
        policy.update("activity", song)
        policy.update("lockscreen", next)
        policy.update("activity", song)
        assertEquals(next, policy.selected)
        assertFalse(policy.receives("activity"))
        policy.update("activity", null)
        assertEquals(next, policy.selected)
    }

    @Test fun invalidSessionDoesNotDisplaceWorkingConsumer() {
        val policy = AudioCapturePolicy<String>()
        policy.update("lockscreen", song)
        policy.update("preview", song.copy(sessionId = 0))
        assertEquals(song, policy.selected)
        assertFalse(policy.receives("preview"))
    }

    @Test fun lateFramesCannotReanimatePreviousTrack() {
        val silence = FloatArray(48)
        assertTrue(AudioFramePolicy.accepts(2, 2, 1000, 990, silence, 0f))
        assertFalse(AudioFramePolicy.accepts(2, 1, 1000, 990, silence, .5f))
        assertFalse(AudioFramePolicy.accepts(2, 2, 1000, 499, silence, .5f))
        assertFalse(AudioFramePolicy.accepts(2, 2, 1000, 1001, silence, .5f))
    }

    @Test fun invalidWireValuesDoNotReachRenderer() {
        assertFalse(AudioFramePolicy.accepts(1, 1, 10, 10, FloatArray(47), 0f))
        assertFalse(AudioFramePolicy.accepts(1, 1, 10, 10, FloatArray(48) { Float.NaN }, 0f))
        assertFalse(AudioFramePolicy.accepts(1, 1, 10, 10, FloatArray(48), Float.POSITIVE_INFINITY))
        assertFalse(AudioFramePolicy.accepts(1, 1, 10, 10, FloatArray(48) { 1.01f }, 0f))
    }
}
