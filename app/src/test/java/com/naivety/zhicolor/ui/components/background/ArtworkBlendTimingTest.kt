package com.naivety.zhicolor.ui.components.background

import org.junit.Assert.*
import org.junit.Test

class ArtworkBlendTimingTest {
    @Test fun recoveredCubicHasExpectedControlPointAndEndpoints() {
        assertEquals(0f, ArtworkBlendTiming.amount(0f), 0f)
        assertEquals(1f, ArtworkBlendTiming.amount(1f), 0f)
        // Parametric t=.5: x=.2375 and y=.5 for cubic(0,0,.3,1).
        assertEquals(.5f, ArtworkBlendTiming.amount(.2375f), .0001f)
    }

    @Test fun wallClockTransitionHasSameEndAtThirtySixtyAndOneTwentyHertz() {
        for (rate in listOf(30, 60, 120)) {
            var progress = 0f
            repeat((rate * .4f).toInt()) {
                progress = (progress + (1f / rate) / ArtworkBlendTiming.SECONDS).coerceAtMost(1f)
            }
            assertEquals(1f, ArtworkBlendTiming.amount(progress), .0001f)
        }
    }

    @Test fun resumedTransitionNeverRunsBackwards() {
        val values = (0..100).map { ArtworkBlendTiming.amount(it / 100f) }
        assertTrue(values.zipWithNext().all { (previous, next) -> next >= previous })
        assertTrue(values.all { it in 0f..1f })
    }
}
