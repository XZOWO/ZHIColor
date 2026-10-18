package com.naivety.zhicolor.bridge.render;

import static org.junit.Assert.*;

import org.junit.Test;

public final class LyricLineExitPolicyTest {
    @Test
    public void fastNextLineLandsAndFadesOnTheSameDeadline() {
        LyricLineExitPolicy old = new LyricLineExitPolicy();
        old.sample(true, 990L, 100L, true, 1_320L, 300L);
        float before = sink(old.renderPosition());
        old.sample(false, 1_000L, 110L, true, 1_320L, 300L);
        assertEquals(990L, old.renderPosition());
        assertEquals(before, sink(old.renderPosition()), 0.0001f);
        assertEquals(1f, old.highlightAmount(), 0f);
        old.sample(false, 1_170L, 280L, true, 1_320L, 300L);
        assertTrue(sink(old.renderPosition()) < before);
        assertTrue(old.highlightAmount() > 0f && old.highlightAmount() < 1f);
        assertTrue(old.isExiting());
        old.sample(false, 1_329L, 439L, true, 1_320L, 300L);
        assertTrue(old.renderPosition() < 1_320L);
        assertTrue(old.highlightAmount() > 0f);
        old.sample(false, 1_330L, 440L, true, 1_320L, 300L);
        assertEquals(1_320L, old.renderPosition());
        assertEquals(0f, sink(old.renderPosition()), 0.0001f);
        assertFalse(old.isExiting());
        assertEquals(0f, old.highlightAmount(), 0f);
    }

    @Test
    public void multipleFastLinesHaveIndependentCompletionClocks() {
        LyricLineExitPolicy first = new LyricLineExitPolicy();
        LyricLineExitPolicy second = new LyricLineExitPolicy();
        first.sample(true, 980L, 0L, true, 1_320L, 300L);
        second.sample(false, 980L, 0L, true, 1_440L, 300L);
        first.sample(false, 1_000L, 20L, true, 1_320L, 300L);
        second.sample(true, 1_000L, 20L, true, 1_440L, 300L);
        first.sample(false, 1_120L, 140L, true, 1_320L, 300L);
        second.sample(false, 1_120L, 140L, true, 1_440L, 300L);
        assertTrue(first.isExiting());
        assertTrue(second.isExiting());
        assertEquals(1_100L, first.renderPosition());
        assertEquals(1_000L, second.renderPosition());
        assertTrue(first.highlightAmount() < 1f);
        assertEquals(ZhiToolLyricMotion.easeOutCubic(120f / 300f), second.highlightAmount(), 0f);
        first.sample(false, 1_400L, 420L, true, 1_320L, 300L);
        second.sample(false, 1_400L, 420L, true, 1_440L, 300L);
        assertFalse(first.isExiting());
        assertTrue(second.isExiting());
        assertTrue(second.highlightAmount() > 0f && second.highlightAmount() < 1f);
        second.sample(false, 1_560L, 580L, true, 1_440L, 300L);
        assertFalse(second.isExiting());
        assertEquals(1_440L, second.renderPosition());
    }

    @Test
    public void pauseDoesNotAdvanceAnActiveWordOrOwnAnAnimationLoop() {
        LyricLineExitPolicy state = new LyricLineExitPolicy();
        state.sample(true, 500L, 0L, true, 1_320L, 300L);
        state.sample(true, 500L, 800L, true, 1_320L, 300L);
        assertEquals(500L, state.renderPosition());
        assertFalse(state.isExiting());
        assertEquals(1f, state.highlightAmount(), 0f);
    }

    @Test
    public void alreadyStartedTailSettlesEvenIfPlaybackPauses() {
        LyricLineExitPolicy state = new LyricLineExitPolicy();
        state.sample(true, 990L, 0L, true, 1_320L, 300L);
        state.sample(false, 1_000L, 10L, true, 1_320L, 300L);
        state.sample(false, 1_000L, 175L, true, 1_320L, 300L);
        assertEquals(1_155L, state.renderPosition());
        assertEquals(0.125f, state.highlightAmount(), 0.00001f);
        state.sample(false, 1_000L, 340L, true, 1_320L, 300L);
        assertFalse(state.isExiting());
        assertEquals(0f, state.highlightAmount(), 0f);
        assertEquals(0f, sink(state.renderPosition()), 0f);
    }

    @Test
    public void forwardSeekClearsOldTailImmediately() {
        LyricLineExitPolicy state = exiting();
        state.sample(false, 40_000L, 20L, true, 1_320L, 300L);
        assertFalse(state.isExiting());
        assertEquals(0f, state.highlightAmount(), 0f);
        assertEquals(40_000L, state.renderPosition());
    }

    @Test
    public void backwardSeekClearsTailAndReentryUsesPlaybackPosition() {
        LyricLineExitPolicy state = exiting();
        state.sample(true, 100L, 20L, true, 1_320L, 300L);
        assertFalse(state.isExiting());
        assertEquals(100L, state.renderPosition());
        assertEquals(1f, state.highlightAmount(), 0f);
    }

    @Test
    public void reusingViewForAnInactiveLineNeverInheritsOldLineHighlight() {
        LyricLineExitPolicy oldLine = exiting();
        LyricLineExitPolicy newLine = new LyricLineExitPolicy();
        newLine.sample(false, 1_000L, 10L, true, 1_320L, 300L);
        assertTrue(oldLine.isExiting());
        assertFalse(newLine.isExiting());
        assertEquals(0f, newLine.highlightAmount(), 0f);
    }

    @Test
    public void resetAndDetachedObservationDiscardStaleTail() {
        LyricLineExitPolicy state = exiting();
        state.reset();
        state.sample(false, 1_010L, 20L, true, 1_320L, 300L);
        assertFalse(state.isExiting());
        state = exiting();
        state.sample(false, 2_500L, 1_510L, true, 1_320L, 300L);
        assertFalse(state.isExiting());
    }

    @Test
    public void aodDisabledMotionAndSeekSuppressionDropTail() {
        LyricLineExitPolicy state = exiting();
        state.sample(false, 1_010L, 20L, false, 1_320L, 300L);
        assertFalse(state.isExiting());
        assertEquals(0f, state.highlightAmount(), 0f);
    }

    @Test
    public void alreadyLandedLineStartsFadeImmediatelyWithoutExtraHold() {
        LyricLineExitPolicy state = new LyricLineExitPolicy();
        state.sample(true, 2_000L, 0L, true, 1_320L, 300L);
        state.sample(false, 2_010L, 10L, true, 1_320L, 300L);
        state.sample(false, 2_160L, 160L, true, 1_320L, 300L);
        assertEquals(2_000L, state.renderPosition());
        assertEquals(0.125f, state.highlightAmount(), 0.00001f);
        state.sample(false, 2_310L, 310L, true, 1_320L, 300L);
        assertFalse(state.isExiting());
    }

    @Test
    public void longOverlappingWordLandsAndFadesWithinOneBoundedExit() {
        LyricLineExitPolicy state = new LyricLineExitPolicy();
        state.sample(true, 990L, 0L, true, 100_320L, 300L);
        state.sample(false, 1_000L, 10L, true, 100_320L, 300L);
        state.sample(false, 1_320L, 330L, true, 100_320L, 300L);
        assertTrue(state.renderPosition() > 990L && state.renderPosition() < 100_320L);
        assertEquals(0.125f, state.highlightAmount(), 0.00001f);
        state.sample(false, 1_639L, 649L, true, 100_320L, 300L);
        assertTrue(state.renderPosition() < 100_320L);
        assertTrue(state.highlightAmount() > 0f);
        state.sample(false, 1_640L, 650L, true, 100_320L, 300L);
        assertFalse(state.isExiting());
        assertEquals(100_320L, state.renderPosition());
        assertEquals(0f, state.highlightAmount(), 0f);
    }

    @Test
    public void shortRemainingLiftSharesTheFullNaturalFadeDuration() {
        LyricLineExitPolicy state = new LyricLineExitPolicy();
        state.sample(true, 1_290L, 0L, true, 1_320L, 300L);
        state.sample(false, 1_300L, 10L, true, 1_320L, 300L);
        state.sample(false, 1_450L, 160L, true, 1_320L, 300L);
        assertEquals(1_305L, state.renderPosition());
        assertEquals(0.125f, state.highlightAmount(), 0.00001f);
        state.sample(false, 1_599L, 309L, true, 1_320L, 300L);
        assertTrue(state.isExiting());
        assertTrue(state.renderPosition() < 1_320L);
        state.sample(false, 1_600L, 310L, true, 1_320L, 300L);
        assertFalse(state.isExiting());
        assertEquals(1_320L, state.renderPosition());
        assertEquals(0f, state.highlightAmount(), 0f);
    }

    @Test
    public void noLiftUsesReducedMotionFadeWithoutAnExtraCompletionPhase() {
        LyricLineExitPolicy state = new LyricLineExitPolicy();
        state.sample(true, 2_000L, 0L, true, 2_000L, 220L);
        state.sample(false, 2_010L, 10L, true, 2_010L, 220L);
        state.sample(false, 2_120L, 120L, true, 2_120L, 220L);
        assertEquals(2_000L, state.renderPosition());
        assertEquals(0.125f, state.highlightAmount(), 0.00001f);
        state.sample(false, 2_230L, 230L, true, 2_230L, 220L);
        assertFalse(state.isExiting());
        assertEquals(0f, state.highlightAmount(), 0f);
    }

    @Test
    public void firstElapsedFrameAlreadyAdvancesLiftAndReducesHighlight() {
        LyricLineExitPolicy state = exiting();
        state.sample(false, 1_001L, 11L, true, 1_320L, 300L);
        assertTrue(state.renderPosition() > 990L);
        assertTrue(state.highlightAmount() < 1f);
        assertTrue(state.isExiting());
    }

    @Test
    public void inactiveSamplesDoNotRestartOrRetargetTheSharedExit() {
        LyricLineExitPolicy state = exiting();
        for (long now = 60L; now < 340L; now += 50L) {
            state.sample(false, now + 990L, now, true, 100_320L, 600L);
        }
        state.sample(false, 1_330L, 340L, true, 100_320L, 600L);
        assertFalse(state.isExiting());
        assertEquals(1_320L, state.renderPosition());
        assertEquals(0f, state.highlightAmount(), 0f);
    }

    @Test
    public void continuousReentryCancelsExitAndResumesTheRealPlaybackClock() {
        LyricLineExitPolicy state = exiting();
        state.sample(false, 1_100L, 110L, true, 1_320L, 300L);
        state.sample(false, 1_110L, 120L, true, 1_320L, 300L);
        float before = state.highlightAmount();
        state.sample(true, 1_110L, 120L, true, 1_320L, 300L);
        assertFalse(state.isExiting());
        assertEquals(1_110L, state.renderPosition());
        assertEquals(before, state.highlightAmount(), 0f);
        assertTrue(state.isEntering(120L));
        state.sample(true, 1_110L, 420L, true, 1_320L, 300L);
        assertEquals(1f, state.highlightAmount(), 0f);
        assertFalse(state.isEntering(420L));
    }

    @Test public void enteringRowRevealsFromInactiveInkWithoutChangingItsWordClock() {
        LyricLineExitPolicy state = new LyricLineExitPolicy();
        state.sample(false, 990L, 0L, true, 1_320L, 300L);
        state.sample(true, 1_000L, 10L, true, 1_320L, 300L);
        assertEquals(0f, state.highlightAmount(), 0f);
        assertEquals(1_000L, state.renderPosition());
        assertTrue(state.isEntering(10L));
        state.sample(true, 1_150L, 160L, true, 1_320L, 300L);
        assertEquals(0.875f, state.highlightAmount(), 0f);
        assertEquals(1_150L, state.renderPosition());
        state.sample(true, 1_300L, 310L, true, 1_320L, 300L);
        assertEquals(1f, state.highlightAmount(), 0f);
        assertFalse(state.isEntering(310L));
    }

    @Test public void exitDuringEntranceStartsAtTheInkActuallyVisibleAndKeepsItsDeadline() {
        LyricLineExitPolicy state = new LyricLineExitPolicy();
        state.sample(false, 990L, 0L, true, 1_320L, 300L);
        state.sample(true, 1_000L, 10L, true, 1_320L, 300L);
        state.sample(true, 1_150L, 160L, true, 1_320L, 300L);
        float before = state.highlightAmount();
        state.sample(false, 1_150L, 160L, true, 1_320L, 300L);
        assertEquals(before, state.highlightAmount(), 0f);
        assertEquals(before, state.liftActivationAmount(), 0f);
        assertTrue(state.isExiting());
        assertEquals(300L, state.exitDurationMillis());
        state.sample(false, 1_300L, 310L, true, 1_320L, 300L);
        assertEquals(before * 0.125f, state.highlightAmount(), 0f);
        assertEquals(before, state.liftActivationAmount(), 0f);
        state.sample(false, 1_450L, 460L, true, 1_320L, 300L);
        assertEquals(0f, state.highlightAmount(), 0f);
        assertEquals(1_320L, state.renderPosition());
        assertFalse(state.isExiting());
    }

    @Test public void reversingPartwayThroughAnExitReentersContinuouslyAndCanExitAgain() {
        LyricLineExitPolicy state = new LyricLineExitPolicy();
        state.sample(false, 990L, 0L, true, 1_320L, 300L);
        state.sample(true, 1_000L, 10L, true, 1_320L, 300L);
        state.sample(false, 1_150L, 160L, true, 1_320L, 300L);
        state.sample(false, 1_300L, 310L, true, 1_320L, 300L);
        float before = state.highlightAmount();
        state.sample(true, 1_300L, 310L, true, 1_620L, 300L);
        assertEquals(before, state.highlightAmount(), 0f);
        assertFalse(state.isExiting());
        assertTrue(state.isEntering(310L));
        assertEquals(1_300L, state.renderPosition());
        state.sample(true, 1_450L, 460L, true, 1_620L, 300L);
        float returned = before + (1f - before) * 0.875f;
        assertEquals(returned, state.highlightAmount(), 0f);
        state.sample(false, 1_450L, 460L, true, 1_620L, 300L);
        assertEquals(returned, state.highlightAmount(), 0f);
        state.sample(false, 1_750L, 760L, true, 1_620L, 300L);
        assertFalse(state.isExiting());
        assertEquals(0f, state.highlightAmount(), 0f);
    }

    @Test public void naturalPlaybackCanCrossTwoShortRowsWithoutDroppingTheOldTail() {
        LyricLineExitPolicy old = new LyricLineExitPolicy();
        LyricLineExitPolicy skipped = new LyricLineExitPolicy();
        LyricLineExitPolicy incoming = new LyricLineExitPolicy();
        old.sample(true, 990L, 0L, true, 1_320L, 300L);
        skipped.sample(false, 990L, 0L, true, 1_040L, 300L);
        incoming.sample(false, 990L, 0L, true, 1_440L, 300L);
        // Native focus advances twice between two normal playback observations.
        old.sample(false, 1_070L, 80L, true, 1_320L, 300L);
        skipped.sample(false, 1_070L, 80L, true, 1_040L, 300L);
        incoming.sample(true, 1_070L, 80L, true, 1_440L, 300L);
        assertTrue(old.isExiting());
        assertEquals(1f, old.highlightAmount(), 0f);
        assertEquals(990L, old.renderPosition());
        assertEquals(0f, skipped.highlightAmount(), 0f);
        assertFalse(skipped.isExiting());
        assertEquals(0f, incoming.highlightAmount(), 0f);
        assertTrue(incoming.isEntering(80L));
        old.sample(false, 1_235L, 245L, true, 1_320L, 300L);
        incoming.sample(true, 1_235L, 245L, true, 1_440L, 300L);
        assertTrue(old.highlightAmount() > 0f && old.highlightAmount() < 1f);
        assertTrue(incoming.highlightAmount() > 0f && incoming.highlightAmount() < 1f);
    }

    @Test public void reversingAnInterruptedLiftKeepsItsCurrentGeometryInsteadOfUsingFadedInk() {
        LyricLineExitPolicy state = new LyricLineExitPolicy();
        state.sample(false, 990L, 0L, true, 1_320L, 300L);
        state.sample(true, 1_000L, 10L, true, 1_320L, 300L);
        state.sample(true, 1_150L, 160L, true, 1_320L, 300L);
        float entranceLift = state.liftActivationAmount();
        assertEquals(0.875f, entranceLift, 0f);
        state.sample(false, 1_150L, 160L, true, 1_320L, 300L);
        assertEquals(entranceLift, state.liftActivationAmount(), 0f);

        state.sample(false, 1_235L, 310L, true, 1_320L, 300L);
        assertEquals(entranceLift * 0.125f, state.highlightAmount(), 0f);
        assertEquals(entranceLift, state.liftActivationAmount(), 0f);
        long beforePosition = state.renderPosition();
        float beforeMaximumDisplacement = 16f * state.liftActivationAmount();
        state.sample(true, 1_235L, 310L, true, 1_620L, 300L);
        assertEquals(beforePosition, state.renderPosition());
        assertEquals(beforeMaximumDisplacement, 16f * state.liftActivationAmount(), 0f);
        assertTrue(state.liftActivationAmount() > state.highlightAmount());

        state.sample(true, 1_385L, 460L, true, 1_620L, 300L);
        float resumedLift = entranceLift + (1f - entranceLift) * 0.875f;
        assertEquals(resumedLift, state.liftActivationAmount(), 0f);
        state.sample(false, 1_385L, 460L, true, 1_620L, 300L);
        assertEquals(resumedLift, state.liftActivationAmount(), 0f);
        state.sample(false, 1_685L, 760L, true, 1_620L, 300L);
        assertEquals(0f, state.highlightAmount(), 0f);
        assertEquals(0f, state.liftActivationAmount(), 0f);
    }

    @Test
    public void skippedFramesDoNotStretchExit() {
        LyricLineExitPolicy state = exiting();
        state.sample(false, 1_630L, 640L, true, 1_320L, 300L);
        assertFalse(state.isExiting());
    }

    private static LyricLineExitPolicy exiting() {
        LyricLineExitPolicy state = new LyricLineExitPolicy();
        state.sample(true, 990L, 0L, true, 1_320L, 300L);
        state.sample(false, 1_000L, 10L, true, 1_320L, 300L);
        return state;
    }

    private static float sink(long position) {
        float wave = 60f;
        float front = Math.min(100f, position * 0.1f)
                + CharLiftGeometry.finishOvershoot(position, 1_000L, 320L, wave);
        return CharLiftGeometry.sinkFor(front, 99.99f, wave, 8f, 1f);
    }
}
