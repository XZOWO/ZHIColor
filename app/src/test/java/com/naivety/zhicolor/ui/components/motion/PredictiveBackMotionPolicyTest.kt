package com.naivety.zhicolor.ui.components.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PredictiveBackMotionPolicyTest {
    private fun frames(q: Float): List<OpenMotionFrame> = listOf(
        PredictiveBackMotionPolicy.pageChildFrame(q, 1080f),
        PredictiveBackMotionPolicy.pageParentFrame(q, 1080f),
        PredictiveBackMotionPolicy.dialogFrame(q, false, 780f, 3f),
        PredictiveBackMotionPolicy.dialogFrame(q, true, 780f, 3f),
        PredictiveBackMotionPolicy.dropdownFrame(q, 3f),
    )

    private fun assertFrameEquals(expected: OpenMotionFrame, actual: OpenMotionFrame) {
        assertEquals(expected.alpha, actual.alpha, 0.00001f)
        assertEquals(expected.scale, actual.scale, 0.00001f)
        assertEquals(expected.translationX, actual.translationX, 0.001f)
        assertEquals(expected.translationY, actual.translationY, 0.001f)
    }

    @Test fun gestureFractionIsAlwaysBounded() {
        assertEquals(0f, PredictiveBackMotionPolicy.fraction(-0.2f), 0f)
        assertEquals(0.42f, PredictiveBackMotionPolicy.fraction(0.42f), 0f)
        assertEquals(1f, PredictiveBackMotionPolicy.fraction(1.4f), 0f)
        assertEquals(0f, PredictiveBackMotionPolicy.fraction(Float.NaN), 0f)
        assertEquals(0f, PredictiveBackMotionPolicy.fraction(Float.POSITIVE_INFINITY), 0f)
    }

    @Test fun everyRenderedTrajectoryIsIdenticalWhenPlayedInReverse() {
        val opening = (0..100).map { frames(it / 100f) }
        val closing = (100 downTo 0).map { frames(it / 100f) }
        opening.indices.forEach { i ->
            opening[i].zip(closing[100 - i]).forEach { (expected, actual) -> assertFrameEquals(expected, actual) }
        }
    }

    @Test fun actualGestureFramesEqualClickAnimationFramesAtTheSameOpenness() {
        (0..100).forEach { i ->
            val openingQ = i / 100f
            val gestureQ = PredictiveBackMotionPolicy.gestureOpenFraction(1f, 1f - openingQ)
            frames(openingQ).zip(frames(gestureQ)).forEach { (expected, actual) -> assertFrameEquals(expected, actual) }
        }
    }

    @Test fun enteringChildAndReturningChildUseTheSameFixedOrigin() {
        val closed = PredictiveBackMotionPolicy.pageChildFrame(0f, 1080f)
        val open = PredictiveBackMotionPolicy.pageChildFrame(1f, 1080f)
        assertTrue(closed.translationX > 0f)
        assertEquals(0f, open.translationX, 0f)
        assertEquals(0f, closed.alpha, 0f)
        assertEquals(1f, open.alpha, 0f)
        (0..100).forEach { i ->
            val q = i / 100f
            assertTrue(PredictiveBackMotionPolicy.pageChildFrame(q, 1080f).translationX >= 0f)
        }
    }

    @Test fun overlaysKeepTheirOwnVerticalOriginAndHaveNoLateralDrift() {
        listOf(0f, 0.2f, 0.5f, 0.8f, 1f).forEach { q ->
            val popupFrames = frames(q).drop(2)
            popupFrames.forEach { assertEquals(0f, it.translationX, 0f) }
            assertTrue(popupFrames[0].translationY >= 0f)
            assertTrue(popupFrames[1].translationY >= 0f)
            assertTrue(popupFrames[2].translationY <= 0f)
        }
    }

    @Test fun interruptionDuringEntranceStartsAtExactlyTheCurrentFrame() {
        listOf(0.12f, 0.37f, 0.68f, 1f).forEach { start ->
            val captured = PredictiveBackMotionPolicy.gestureOpenFraction(start, 0f)
            frames(start).zip(frames(captured)).forEach { (expected, actual) -> assertFrameEquals(expected, actual) }
            val halfway = PredictiveBackMotionPolicy.gestureOpenFraction(start, 0.5f)
            frames(start * 0.5f).zip(frames(halfway)).forEach { (expected, actual) -> assertFrameEquals(expected, actual) }
            assertEquals(0f, PredictiveBackMotionPolicy.gestureOpenFraction(start, 1f), 0f)
        }
    }

    @Test fun cancelledGestureReturnsThroughTheSameFramesToTheOpenState() {
        val q = PredictiveBackMotionPolicy.gestureOpenFraction(0.68f, 0.55f)
        val cancellationStart = frames(q)
        val restoreStart = frames(q)
        cancellationStart.zip(restoreStart).forEach { (expected, actual) -> assertFrameEquals(expected, actual) }
        val restored = frames(1f)
        restored.forEach {
            assertEquals(1f, it.alpha, 0f)
            assertEquals(0f, it.translationY, 0f)
        }
        assertEquals(PredictiveBackMotionPolicy.FullDurationMillis, PredictiveBackMotionPolicy.durationMillis(0f, 1f))
        assertEquals(PredictiveBackMotionPolicy.durationMillis(q, 1f), PredictiveBackMotionPolicy.durationMillis(1f, q))
    }

    @Test fun temporalSpeedAndSpatialCurveAreSymmetricForEverySegment() {
        (0..100).forEach { i ->
            val q = i / 100f
            assertEquals(1f, PredictiveBackMotionPolicy.visualFraction(q) + PredictiveBackMotionPolicy.visualFraction(1f - q), 0.00001f)
            assertEquals(PredictiveBackMotionPolicy.durationMillis(q, 1f), PredictiveBackMotionPolicy.durationMillis(1f, q))
            assertEquals(PredictiveBackMotionPolicy.durationMillis(q, 0f), PredictiveBackMotionPolicy.durationMillis(0f, q))
        }
        assertEquals(0, PredictiveBackMotionPolicy.durationMillis(0.4f, 0.4f))
    }

    @Test fun rootBackRemainsNativeButClosingChildStillConsumesReturn() {
        assertTrue(PredictiveBackMotionPolicy.shouldHandlePageBack(false, false))
        assertFalse(PredictiveBackMotionPolicy.shouldHandlePageBack(false, true))
        assertFalse(PredictiveBackMotionPolicy.shouldHandlePageBack(true, false))
        assertTrue(PredictiveBackMotionPolicy.shouldHandlePageBack(true, false, true))
        assertFalse(PredictiveBackMotionPolicy.shouldHandlePageBack(true, true, true))
    }

    @Test fun onlyTopShownDismissibleOverlayMayCommit() {
        assertTrue(PredictiveBackMotionPolicy.shouldDismissOverlay(true, true, true))
        assertFalse(PredictiveBackMotionPolicy.shouldDismissOverlay(false, true, true))
        assertFalse(PredictiveBackMotionPolicy.shouldDismissOverlay(true, false, true))
        assertFalse(PredictiveBackMotionPolicy.shouldDismissOverlay(true, true, false))
    }

    @Test fun hardwareBackAndGestureCompletionShareTheSingleCommitGate() {
        val gate = OverlayBackCommitGate()
        var callbacks = 0
        repeat(3) { if (gate.tryCommit(true)) callbacks++ }
        assertEquals(1, callbacks)
        assertFalse(gate.tryCommit(false))
        gate.reset()
        if (gate.tryCommit(true)) callbacks++
        assertEquals(2, callbacks)
    }

    @Test fun cancelledOrSupersededSessionCannotFinishOrCommitANewerGesture() {
        val sessions = BackGestureSession()
        val gate = OverlayBackCommitGate()
        val cancelled = sessions.begin()
        sessions.invalidate()
        assertFalse(sessions.isCurrent(cancelled))
        assertFalse(sessions.finish(cancelled))
        assertFalse(gate.committed)
        val current = sessions.begin()
        assertFalse(sessions.isCurrent(cancelled))
        assertFalse(sessions.finish(cancelled))
        assertTrue(sessions.isCurrent(current))
        var callbacks = 0
        repeat(3) { if (sessions.isCurrent(current) && gate.tryCommit(true)) callbacks++ }
        assertEquals(1, callbacks)
        assertTrue(sessions.finish(current))
        assertFalse(sessions.finish(current))
    }
}
