package com.naivety.zhicolor.bridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.naivety.zhicolor.bridge.render.LyricTimingMode;
import com.naivety.zhicolor.bridge.render.WordLine;
import com.naivety.zhicolor.bridge.render.WordLyricModel;
import com.naivety.zhicolor.bridge.render.WordRange;
import com.naivety.zhicolor.bridge.systemui.lyrics.LyricsRecyclerFieldAccessor;

import org.junit.Test;

import java.util.ArrayList;

public final class LyricScrubMotionControllerTest {

    @Test
    public void continuousAdapterPositionInterpolatesSmoothlyAcrossLinesDuetsAndInterludes() {
        WordLyricModel model = new WordLyricModel();
        // Adapter 0: intro waiting dots (0..10s)
        WordLine introDots = WordLine.waitingDots(0L, 10_000L);
        // Adapter 1: 10s..15s
        WordLine line1 = line("First", 10_000L, 15_000L);
        // Adapter 2 & 3: simultaneous duet at 15s..21s
        WordLine duetA = line("Duet A", 15_000L, 21_000L);
        WordLine duetB = line("Duet B", 15_000L, 21_000L);
        // Adapter 4: interlude waiting dots (21s..35s)
        WordLine interlude = WordLine.waitingDots(21_000L, 35_000L);
        // Adapter 5: 35s..40s
        WordLine line5 = line("After interlude", 35_000L, 40_000L);

        model.lines.add(introDots);
        model.lines.add(line1);
        model.lines.add(duetA);
        model.lines.add(duetB);
        model.lines.add(interlude);
        model.lines.add(line5);
        model.officialLines.addAll(model.lines);

        // Before first formal line clamps cleanly at row 1 (skipping collapsed intro dots).
        assertEquals(1.0d, LyricScrubMotionController.continuousAdapterPosition(model, 0L), 1e-6);
        assertEquals(1.0d, LyricScrubMotionController.continuousAdapterPosition(model, 9_500L), 1e-6);
        // Midpoint inside line 1 (10s..15s) smoothly interpolates halfway to row 2.
        assertEquals(1.5d, LyricScrubMotionController.continuousAdapterPosition(model, 12_500L), 1e-6);
        // Duet lines at 15s bridge smoothly to row 5 at 35s (spanning interlude with zero dead zone).
        assertEquals(2.0d, LyricScrubMotionController.continuousAdapterPosition(model, 15_000L), 1e-6);
        assertEquals(3.5d, LyricScrubMotionController.continuousAdapterPosition(model, 25_000L), 1e-6);
        // Clamps cleanly at the final lyric row.
        assertEquals(5.0d, LyricScrubMotionController.continuousAdapterPosition(model, 35_000L), 1e-6);
        assertEquals(5.0d, LyricScrubMotionController.continuousAdapterPosition(model, 99_000L), 1e-6);

        // Strictly non-decreasing across the entire timeline.
        double prev = -1d;
        for (long ms = 0L; ms <= 45_000L; ms += 500L) {
            double pos = LyricScrubMotionController.continuousAdapterPosition(model, ms);
            assertTrue("Expected monotonic position at " + ms + "ms", pos >= prev - 1e-9);
            prev = pos;
        }
    }

    @Test
    public void virtualScrollMapMatchesAppleTopInsetAndSelfCalibratesRowHeights() {
        boolean[] dots = new boolean[]{false, false, false, false};
        // Apple position: row 0 has topMargin = 160px, topOffset(0) = 0, other rows topOffset = 160px.
        LyricScrubMotionController.VirtualScrollMap map =
                new LyricScrubMotionController.VirtualScrollMap(4, dots, 120, 160, 160);

        // Row 0 anchor V = C(0) - topOffset(0) = 0 - 0 = 0.
        assertEquals(0f, map.targetScrollYForRow(0), 1e-3f);
        // Row 1 anchor V = C(1) - topOffset(1) = (120 + 160) - 160 = 120.
        assertEquals(120f, map.targetScrollYForRow(1), 1e-3f);
        // Halfway between row 0 and row 1 is 60px!
        assertEquals(60f, map.continuousTargetScrollY(0.5d), 1e-3f);

        // Measure row 0 actually 140px + 160px topMargin = 300px, row 1 actually 200px (wrapped).
        map.updateMeasuredRow(0, 300, 0, true);
        map.updateMeasuredRow(1, 200, 160, true);
        assertEquals(0f, map.targetScrollYForRow(0), 1e-3f);
        assertEquals(140f, map.targetScrollYForRow(1), 1e-3f);
        // Observed scroll Y invariant: adjacent visible rows produce identical observed Y!
        // When row 0 decoratedTop == 0 (offset 0), row 1 decoratedTop == 300 (offset 300):
        assertEquals(0f, map.observedScrollY(0, 0), 1e-3f);
        assertEquals(0f, map.observedScrollY(1, 300), 1e-3f);

        // updateTopOffset updates anchor without marking an unmeasured row as measured.
        int avgBefore = map.estimatedNormalPitchPx();
        map.updateTopOffset(3, 130);
        assertEquals(avgBefore, map.estimatedNormalPitchPx());
        assertEquals(130, map.topOffsetOf(3));
    }

    @Test
    public void draggingSpringGlidesSmoothlyWithoutOvershootAndAbsorbsCalibrationShifts() {
        boolean[] dots = new boolean[20];
        LyricScrubMotionController.VirtualScrollMap map =
                new LyricScrubMotionController.VirtualScrollMap(20, dots, 140, 120, 120);
        LyricScrubMotionController.ScrollSession session =
                new LyricScrubMotionController.ScrollSession(map);

        session.enterDragging(0f, true, 1_000L, true);
        session.updateDragTarget(10.5d, 1_000L);
        float targetY = map.continuousTargetScrollY(10.5d);

        float recyclerY = 0f;
        float prevY = 0f;
        for (int frame = 1; frame <= 60; frame++) {
            long t = 1_000L + frame * 16L;
            LyricScrubMotionController.StepResult step =
                    session.step(t, recyclerY, true, false);
            assertEquals(LyricScrubMotionController.MotionPhase.DRAGGING, step.phase);
            assertTrue(step.active);
            assertTrue(step.holdSecondaryEffects);
            assertFalse(step.justReleasedHandoff);
            // Smooth monotonic glide without teleporting straight to target on frame 1.
            if (frame == 1) {
                assertTrue("First frame should smoothly start gliding, not teleport",
                        step.nextScrollY > 0f && step.nextScrollY < targetY * 0.4f);
            }
            assertTrue("Must be monotonic toward target", step.nextScrollY >= prevY - 1e-3f);
            assertTrue("Critically damped drag must not overshoot target",
                    step.nextScrollY <= targetY + 0.5f);
            int dy = Math.round(step.deltaScrollY);
            recyclerY += dy;
            session.recordAppliedRecyclerY(recyclerY, false);
            prevY = step.nextScrollY;
        }
        assertEquals(targetY, recyclerY, 2f);

        // Measure visible rows around 10.5 at 140px, record row 10's screen offset, then let
        // row 1 above measure at 220px (+80px): real RecyclerView keeps row 10 at the same
        // screen offset so observedScrollY and targetY shift identically -> ZERO visual jump!
        for (int r = 2; r <= 12; r++) {
            map.updateMeasuredRow(r, 140, 120, true);
        }
        int row10ScreenOffset = Math.round(map.cumulativeStart(10) - recyclerY);
        session.updateDragTarget(10.5d, 2_000L);
        map.updateMeasuredRow(1, 220, 120, true);
        float shiftedObservedY = map.observedScrollY(10, row10ScreenOffset);
        LyricScrubMotionController.StepResult afterShift =
                session.step(2_016L, shiftedObservedY, true, false);
        assertTrue("Calibration shift must not cause a jump, got delta=" + afterShift.deltaScrollY,
                Math.abs(afterShift.deltaScrollY) < 2f);
    }

    @Test
    public void longSwipeDelaysHandoffUntilTargetApproachesAndShortDragReleasesImmediately() {
        boolean[] dots = new boolean[40];
        LyricScrubMotionController.VirtualScrollMap map =
                new LyricScrubMotionController.VirtualScrollMap(40, dots, 140, 120, 120);
        LyricScrubMotionController.ScrollSession session =
                new LyricScrubMotionController.ScrollSession(map);

        // Long swipe: currently at row 0 (y=0), release onto row 30 (y=4200px).
        session.enterDragging(0f, true, 1_000L, true);
        session.updateDragTarget(30.0d, 1_000L);
        session.beginSettle(30, 1_000L, LyricUiConfig.MOTION_APPLE, true);

        float recyclerY = 0f;
        int handoffFrame = -1;
        int finishFrame = -1;
        for (int frame = 1; frame <= 80; frame++) {
            long t = 1_000L + frame * 16L;
            boolean targetLaidOut = Math.abs(map.targetScrollYForRow(30) - recyclerY) < 400f;
            LyricScrubMotionController.StepResult step =
                    session.step(t, recyclerY, true, targetLaidOut);
            if (step.justReleasedHandoff) {
                assertEquals("Handoff must only fire once", -1, handoffFrame);
                handoffFrame = frame;
            }
            recyclerY += Math.round(step.deltaScrollY);
            session.recordAppliedRecyclerY(recyclerY, false);
            if (step.justFinishedSettle) {
                finishFrame = frame;
                assertEquals(30, step.settleTargetRow);
                break;
            }
        }
        assertTrue("Long swipe should defer handoff past initial frames", handoffFrame > 2);
        assertTrue("Settle should finish cleanly within 1s", finishFrame > handoffFrame);
        assertEquals(map.targetScrollYForRow(30), recyclerY, 2f);

        // Short drag: release 40px from row 30 -> handoff releases IMMEDIATELY on frame 1!
        session.enterDragging(map.targetScrollYForRow(30) + 40f, true, 3_000L, true);
        session.beginSettle(30, 3_000L, LyricUiConfig.MOTION_APPLE, true);
        LyricScrubMotionController.StepResult first =
                session.step(3_016L, map.targetScrollYForRow(30) + 40f, true, true);
        assertTrue("Short release should trigger handoff immediately", first.justReleasedHandoff);
        assertFalse(first.holdSecondaryEffects);
    }

    @Test
    public void trackingBlurTransitionAnimatesOutOnEnterAndInOver330msOnLeave() {
        LyricScrubMotionController.TrackingBlurTransition blur =
                new LyricScrubMotionController.TrackingBlurTransition();
        assertEquals(1f, blur.sample(0L), 1e-4f);
        assertFalse(blur.isAnimating(0L));

        blur.enterTracking(1_000L, true);
        assertTrue(blur.isAnimating(1_050L));
        float midEnter = blur.sample(1_100L);
        assertTrue("Blur should smoothly decrease during enterTracking", midEnter > 0f && midEnter < 0.5f);
        assertEquals(0f, blur.sample(1_200L), 1e-4f);
        assertFalse(blur.isAnimating(1_200L));

        blur.leaveTracking(2_000L, true);
        assertTrue(blur.isAnimating(2_100L));
        float midLeave = blur.sample(2_165L);
        assertTrue("Blur should smoothly restore during leaveTracking", midLeave > 0.5f && midLeave < 1f);
        assertEquals(1f, blur.sample(2_330L), 1e-4f);
        assertFalse(blur.isAnimating(2_330L));

        // Motion-off snaps cleanly.
        blur.enterTracking(3_000L, false);
        assertEquals(0f, blur.sample(3_000L), 1e-4f);
        assertFalse(blur.isAnimating(3_000L));
        blur.reset();
        assertEquals(1f, blur.sample(3_000L), 1e-4f);
    }

    @Test
    public void lyricsRecyclerFieldAccessorWritesCurrentIndexOnColorOs17AndLegacyLayouts() {
        LyricsRecyclerFieldAccessor accessor = new LyricsRecyclerFieldAccessor();

        FakeColorOs17Recycler cos17 = new FakeColorOs17Recycler();
        assertEquals(2, accessor.readCurrentIndex(cos17, -1));
        assertTrue(accessor.writeCurrentIndex(cos17, 17));
        assertEquals(17, accessor.readCurrentIndex(cos17, -1));

        FakeLegacyRecycler legacy = new FakeLegacyRecycler();
        assertEquals(3, accessor.readCurrentIndex(legacy, -1));
        assertTrue(accessor.writeCurrentIndex(legacy, 9));
        assertEquals(9, accessor.readCurrentIndex(legacy, -1));
    }

    @Test
    public void coordinateShiftDuringScrollByAbsorbsHeightDeltaWithoutKillingVelocity() {
        boolean[] dots = new boolean[30];
        LyricScrubMotionController.VirtualScrollMap map =
                new LyricScrubMotionController.VirtualScrollMap(30, dots, 100, 0, 200);
        LyricScrubMotionController.ScrollSession session =
                new LyricScrubMotionController.ScrollSession(map);

        session.enterDragging(1_200f, true, 1_000L, true);
        session.updateDragTarget(2.0d, 1_000L);

        // Step upward with negative velocity.
        LyricScrubMotionController.StepResult step1 = session.step(1_016L, 1_200f, true, false);
        int dy = Math.round(step1.deltaScrollY);
        assertTrue("Expected upward scroll step, got dy=" + dy, dy < -5);
        float velBefore = session.velocityY();
        assertTrue("Velocity should be strongly negative during drag", velBefore < -200f);

        // Simulate a newly revealed wrapped row above the viewport shifting virtual Y by +65px
        // while NOT at a scroll boundary (hitBoundary = false).
        float afterObservedY = 1_200f + dy + 65f;
        session.recordAppliedRecyclerY(1_200f, dy, afterObservedY, false);
        assertEquals("Velocity must NOT be killed by non-boundary height calibration",
                velBefore, session.velocityY(), 0.001f);

        // Next frame continues smoothly in the same direction with no backward jump.
        LyricScrubMotionController.StepResult step2 = session.step(1_032L, afterObservedY, true, false);
        assertTrue("Next frame must continue scrolling smoothly upward, got delta=" + step2.deltaScrollY,
                step2.deltaScrollY < 0f && Math.abs(step2.deltaScrollY) < Math.abs(dy) * 3f);
    }

    private enum LyricsSwitchPhase {
        IDLE
    }

    @SuppressWarnings("unused")
    private static final class FakeColorOs17Recycler {
        int c = 2;
        int i = 0;
        int j = 24;
        long k = 300L;
        float n = 0f;
        float r = 0.96f;
        LyricsSwitchPhase w = LyricsSwitchPhase.IDLE;
    }

    @SuppressWarnings("unused")
    private static final class FakeLegacyRecycler {
        int n = 3;
    }

    private static WordLine line(String text, long startMs, long endMs) {
        ArrayList<WordRange> words = new ArrayList<>();
        words.add(new WordRange(startMs, 0, text.length(), endMs));
        return new WordLine(startMs, text, words, endMs, LyricTimingMode.WORD_TIMED);
    }
}
