package com.naivety.zhicolor.ui.components.background

import org.junit.Assert.assertEquals
import org.junit.Test

class ArtworkFrameClockTest {
    @Test fun skippedFramesAdvanceByElapsedTime() {
        val clock = ArtworkFrameClock()
        assertEquals(0f, clock.step(1_000_000_000L), 0f)
        assertEquals(.05f, clock.step(1_050_000_000L), .000001f)
        assertEquals(.025f, clock.step(1_075_000_000L), .000001f)
    }

    @Test fun resumeDoesNotAdvanceThroughTimeSpentInAnotherPage() {
        val clock = ArtworkFrameClock()
        clock.step(1_000_000_000L)
        clock.step(1_016_666_667L)
        clock.reset()
        assertEquals(0f, clock.step(90_000_000_000L), 0f)
        assertEquals(1f / 60f, clock.step(90_016_666_667L), .000001f)
    }

    @Test fun stallsAndNonMonotonicInputsDoNotProduceAnimationJumps() {
        val clock = ArtworkFrameClock()
        clock.step(1_000_000_000L)
        assertEquals(.25f, clock.step(3_000_000_000L), 0f)
        assertEquals(0f, clock.step(3_000_000_000L), 0f)
        assertEquals(0f, clock.step(2_000_000_000L), 0f)
    }
}
