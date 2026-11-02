package com.naivety.zhicolor.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AmBackgroundEnvelopeTest {
    private val zero = BackgroundAudioPower(0f, 0f, 0f)
    private val one = BackgroundAudioPower(1f, 1f, 1f)

    @Test fun oneAudioReportIsNotFourReportsAtHighRefreshRate() {
        val envelope = AmBackgroundEnvelope()
        envelope.submit(one)
        repeat(600) { envelope.advance(1f / 60f) }
        assertEquals(AmBackgroundEnvelope.curve(.4f), envelope.advance(0f).low, .000001f)
    }

    @Test fun firstReportGoldenVectorComesFromPointFourThenHalfAndQuintic() {
        val envelope = AmBackgroundEnvelope()
        envelope.submit(one)
        val first = envelope.advance(1f / 60f)
        assertEquals(.05792f, first.low, .000001f)
        assertEquals(first.low, first.mid, 0f)
        assertEquals(first.low, first.high, 0f)
        assertEquals(.16308f, envelope.advance(1f / 60f).low, .000001f)
    }

    @Test fun reportsShiftOnlyWhenSubmittedAndUseOldestToNewestWeights() {
        val envelope = AmBackgroundEnvelope()
        for (value in listOf(.1f, .2f, .3f, .4f)) envelope.submit(BackgroundAudioPower(value, value, value))
        // .1*.1 + .2*.2 + .3*.3 + .4*.4 = .3; first envelope tick = .15.
        assertEquals(.026611875f, envelope.advance(1f / 60f).low, .000001f)
    }

    @Test fun releaseUsesThreeDifferentOriginalDivisors() {
        val envelope = AmBackgroundEnvelope()
        repeat(4) { envelope.submit(one) }
        repeat(120) { envelope.advance(1f / 60f) }
        repeat(4) { envelope.submit(zero) }
        val released = envelope.advance(1f / 60f)
        assertEquals(AmBackgroundEnvelope.curve(.99f), released.low, .000001f)
        assertEquals(AmBackgroundEnvelope.curve(.995f), released.mid, .000001f)
        assertEquals(AmBackgroundEnvelope.curve(.9995f), released.high, .000001f)
        repeat(60) { envelope.advance(1f / 60f) }
        val later = envelope.advance(0f)
        assertTrue(later.low < later.mid && later.mid < later.high)
    }

    @Test fun nominalTicksMatchAtThirtySixtyAndOneTwentyHz() {
        fun atRate(rate: Int): BackgroundAudioPower {
            val envelope = AmBackgroundEnvelope()
            repeat(4) { envelope.submit(BackgroundAudioPower(.8f, .5f, .3f)) }
            repeat(rate) { envelope.advance(1f / rate) }
            repeat(4) { envelope.submit(zero) }
            repeat(rate) { envelope.advance(1f / rate) }
            return envelope.advance(0f)
        }
        assertEquals(atRate(30), atRate(60))
        assertEquals(atRate(60), atRate(120))
    }

    @Test fun resetAndInvalidInputsCannotCreateMotion() {
        val envelope = AmBackgroundEnvelope()
        for (invalid in listOf(Float.NaN, Float.POSITIVE_INFINITY, -1f)) {
            envelope.submit(BackgroundAudioPower(invalid, invalid, invalid))
            assertEquals(zero, envelope.advance(1f / 60f))
        }
        repeat(4) { envelope.submit(one) }
        val previous = envelope.advance(1f / 60f)
        for (dt in listOf(Float.NaN, Float.POSITIVE_INFINITY, 0f, -1f)) assertEquals(previous, envelope.advance(dt))
        envelope.reset()
        assertEquals(zero, envelope.advance(1f / 60f))
    }
}
