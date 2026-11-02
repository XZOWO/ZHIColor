package com.naivety.zhicolor.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundMotionPolicyTest {
    private val zero = BackgroundAudioPower(0f, 0f, 0f)
    private fun frame(total: Float = 100f, timestamp: Long = 1L) = BackgroundAudioFrame(1f, .5f, 0f, total, timestamp)

    @Test fun mediaPresenceControlsArtworkIndependentlyOfPlayback() {
        assertTrue(BackgroundMotionPolicy.showArtwork(true, true))
        assertEquals(false, BackgroundMotionPolicy.showArtwork(false, true))
        assertEquals(false, BackgroundMotionPolicy.showArtwork(true, false))
    }

    @Test fun callbackMultipliesFractionByTwentyLogTotalPowerThenDividesBy120() {
        val value = BackgroundMotionPolicy.normalize(frame())
        assertEquals(1f / 3f, value.low, .000001f)
        assertEquals(1f / 6f, value.mid, .000001f)
        assertEquals(0f, value.high, 0f)
    }

    @Test fun originalCallbackClampsItsOutputAtFullScale() {
        assertEquals(BackgroundAudioPower(1f, 1f, 1f), BackgroundMotionPolicy.normalize(BackgroundAudioFrame(1f, 1f, 1f, 1e10f, 1L)))
    }

    @Test fun silenceInvalidAndSubUnitFftPowerNeverCreateEnergy() {
        for (total in listOf(0f, -1f, 1f, .1f, Float.NaN, Float.POSITIVE_INFINITY))
            assertEquals(zero, BackgroundMotionPolicy.normalize(frame(total)))
        assertEquals(zero, BackgroundMotionPolicy.normalize(BackgroundAudioFrame(Float.NaN, -1f, Float.POSITIVE_INFINITY, 100f, 1L)))
    }

    @Test fun absoluteMusicalLevelIsNotLearnedAwayOverLongPlayback() {
        fun steady(total: Float): Float {
            val policy = BackgroundMotionPolicy()
            var result = zero
            repeat(600) { index ->
                val now = (index + 1L) * 16_666_667L
                result = policy.update(frame(total, now), 1f / 60f, now)
            }
            return result.low
        }
        assertTrue(steady(1000f) > steady(100f))
        assertTrue(steady(100f) > steady(10f))
    }

    @Test fun repeatedCaptureTimestampDoesNotFillTheFourAudioReportHistory() {
        val policy = BackgroundMotionPolicy()
        val sample = frame(1_000_000f)
        repeat(20) { policy.update(sample, 1f / 60f, (it + 1L) * 16_666_667L) }
        assertEquals(AmBackgroundEnvelope.curve(.4f), policy.update(sample, 0f, 340_000_000L).low, .000001f)
    }

    @Test fun staleFutureAndStoppedCaptureClearAllPreviousEnergy() {
        for (bad in listOf(BackgroundAudioFrame.SILENT, frame(timestamp = 999_999_999L), frame(timestamp = 1L))) {
            val policy = BackgroundMotionPolicy()
            repeat(4) { index -> policy.update(frame(1_000_000f, 600_000_000L + index), 1f / 60f, 600_000_000L + index) }
            assertEquals(zero, policy.update(bad, 1f / 60f, 600_000_010L))
        }
    }

    @Test fun outOfOrderReportsCannotReplaceANewerCapture() {
        val policy = BackgroundMotionPolicy()
        val first = policy.update(frame(timestamp = 100L), 1f / 60f, 100L)
        assertEquals(first, policy.update(frame(1_000_000f, 50L), 0f, 110L))
    }

    @Test fun resetRemovesTrackHistoryAndPeakHold() {
        val policy = BackgroundMotionPolicy()
        repeat(4) { index -> policy.update(frame(1_000_000f, index + 1L), 1f / 60f, index + 1L) }
        policy.reset()
        assertEquals(BackgroundMotionPolicy().update(frame(), 1f / 60f, 1L), policy.update(frame(), 1f / 60f, 1L))
    }

    @Test fun coalescedCallbacksPreserveFourAudioReportsAndOverlappingSnapshotsDeduplicate() {
        val reports = (1L..4L).map { frame(100f * it, it) }
        val sequential = BackgroundMotionPolicy()
        reports.forEach { sequential.update(it, 0f, 4L) }
        val batched = BackgroundMotionPolicy()
        val snapshot = reports.last().copy(history = reports)
        batched.update(snapshot, 0f, 4L)
        assertEquals(sequential.update(reports.last(), 1f / 60f, 4L), batched.update(snapshot, 1f / 60f, 4L))
        assertEquals(sequential.update(reports.last(), 1f / 60f, 4L), batched.update(snapshot, 1f / 60f, 4L))
    }
}
