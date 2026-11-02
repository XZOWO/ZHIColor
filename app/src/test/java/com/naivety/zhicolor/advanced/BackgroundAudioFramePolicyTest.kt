package com.naivety.zhicolor.advanced

import com.naivety.zhicolor.home.BackgroundAudioFrame
import org.junit.Assert.*
import org.junit.Test

class BackgroundAudioFramePolicyTest {
    private val now = 10_000_000_000L
    private val frame = BackgroundAudioFrame(.1f, .2f, .3f, .4f, now)

    @Test fun freshCapturePreservesAllRegionalValues() {
        assertEquals(frame, BackgroundAudioFramePolicy.freshOrSilent(frame, now))
    }

    @Test fun normalizedEstimateDoesNotBecomeCalibratedByTransport() {
        val estimate = frame.copy(calibrated = false)
        assertEquals(estimate, BackgroundAudioFramePolicy.freshOrSilent(estimate, now))
        assertFalse(BackgroundAudioFramePolicy.freshOrSilent(estimate, now).calibrated)
    }

    @Test fun absentOldAndFutureCapturesStaySilent() {
        for (value in listOf(0L, -1L, now + 1L, now - 500_000_001L)) {
            assertEquals(BackgroundAudioFrame.SILENT,
                BackgroundAudioFramePolicy.freshOrSilent(frame.copy(timestampNanos = value), now))
        }
        assertEquals(frame.copy(timestampNanos = now - 500_000_000L),
            BackgroundAudioFramePolicy.freshOrSilent(frame.copy(timestampNanos = now - 500_000_000L), now))
    }

    @Test fun unscaledTotalFftPowerMayExceedOne() {
        for (value in listOf(0f, 1.01f, 1024f, Float.MAX_VALUE)) {
            val raw = frame.copy(level = value)
            assertEquals(raw, BackgroundAudioFramePolicy.freshOrSilent(raw, now))
        }
    }

    @Test fun invalidRegionalFractionsNeverReachRenderer() {
        for (value in listOf(Float.NaN, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY, -.01f, 1.01f)) {
            for (invalid in listOf(frame.copy(low = value), frame.copy(mid = value),
                frame.copy(high = value)))
                assertEquals(BackgroundAudioFrame.SILENT, BackgroundAudioFramePolicy.freshOrSilent(invalid, now))
        }
    }

    @Test fun negativeAndNonfiniteTotalPowerNeverReachRenderer() {
        for (value in listOf(Float.NaN, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY, -.01f))
            assertEquals(BackgroundAudioFrame.SILENT,
                BackgroundAudioFramePolicy.freshOrSilent(frame.copy(level = value), now))
    }

    @Test fun validFourReportSnapshotKeepsHistoryAndUnscaledPower() {
        val history = List(4) { index -> frame.copy(level = 1024f + index,
            timestampNanos = now - (3 - index) * 16_000_000L) }
        val snapshot = history.last().copy(history = history)
        assertEquals(snapshot, BackgroundAudioFramePolicy.freshOrSilent(snapshot, now))
    }

    @Test fun oversizedRecursiveAndUnorderedHistoriesStaySilent() {
        val earlier = frame.copy(timestampNanos = now - 16_000_000L)
        val invalid = listOf(List(5) { earlier }, listOf(frame, earlier), listOf(earlier, earlier),
            listOf(earlier.copy(history = listOf(earlier))), listOf(frame.copy(timestampNanos = now + 1)))
        for (history in invalid) assertEquals(BackgroundAudioFrame.SILENT,
            BackgroundAudioFramePolicy.freshOrSilent(frame.copy(history = history), now))
    }

    @Test fun staleAndInvalidHistoryCannotBeRefreshedByLatestReport() {
        for (report in listOf(frame.copy(timestampNanos = now - 500_000_001L),
            frame.copy(low = Float.NaN), frame.copy(level = -1f)))
            assertEquals(BackgroundAudioFrame.SILENT,
                BackgroundAudioFramePolicy.freshOrSilent(frame.copy(history = listOf(report)), now))
    }
}
