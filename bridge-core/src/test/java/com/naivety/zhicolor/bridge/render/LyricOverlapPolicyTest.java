package com.naivety.zhicolor.bridge.render;

import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.Arrays;
import org.junit.Test;

public class LyricOverlapPolicyTest {
    private static WordLine line(long begin, long end, String text, boolean explicit) {
        WordLine line = new WordLine(begin, text, new ArrayList<>(), end, LyricTimingMode.LINE_TIMED);
        line.explicitEndTimeProvided = explicit;
        return line;
    }

    private static WordLyricModel model(WordLine... lines) {
        WordLyricModel model = new WordLyricModel();
        model.lines.addAll(Arrays.asList(lines));
        return model;
    }

    @Test public void incomingSingerDoesNotStealFocusWhileEarlierSingerContinues() {
        WordLyricModel m = model(line(1000, 4000, "first", true), line(2000, 5000, "second", true));
        assertEquals(0, m.focusIndexAt(2500));
        assertEquals(1, m.overlapIndexAt(2500));
        assertTrue(m.isSingingLine(0, 2500));
        assertTrue(m.isSingingLine(1, 2500));
        assertEquals("second", m.findActiveLine(2500).text);
    }

    @Test public void handoffHappensExactlyAtEarlierEnd() {
        WordLyricModel m = model(line(1000, 4000, "first", true), line(2000, 5000, "second", true));
        assertEquals(-1, m.overlapIndexAt(1999));
        assertEquals(1, m.overlapIndexAt(2000));
        assertEquals(0, m.focusIndexAt(3999));
        assertEquals(1, m.focusIndexAt(4000));
        assertEquals(-1, m.overlapIndexAt(4000));
        assertFalse(m.isSingingLine(0, 4000));
    }

    @Test public void simultaneousMainRowsRemainDistinctWithOneFocus() {
        WordLyricModel m = model(line(1000, 4000, "voice A", true), line(1000, 5000, "voice B", true));
        assertEquals(0, m.focusIndexAt(1000));
        assertEquals(1, m.overlapIndexAt(1000));
        assertTrue(m.isSingingLine(m.lines.get(0), 2000));
        assertTrue(m.isSingingLine(m.lines.get(1), 2000));
        assertEquals(1, m.focusIndexAt(4000));
    }

    @Test public void ordinaryInferredLrcTailDoesNotCreateOverlap() {
        WordLyricModel m = model(line(1000, 4000, "first", false), line(2000, 5000, "second", false));
        assertEquals(1, m.focusIndexAt(2500));
        assertEquals(-1, m.overlapIndexAt(2500));
        assertFalse(m.hasVerifiedOverlapAfter(m.lines.get(0)));
    }

    @Test public void onlyAdjacentPairSingsAsInZhiTool() {
        WordLyricModel m = model(line(1000, 8000, "a", true),
                line(2000, 7000, "b", true), line(3000, 9000, "c", true));
        assertEquals(1, m.focusIndexAt(3500));
        assertEquals(2, m.overlapIndexAt(3500));
        assertFalse(m.isSingingLine(0, 3500));
    }

    @Test public void seekUsesCurrentPositionWithoutRememberingOldOverlap() {
        WordLyricModel m = model(line(1000, 4000, "a", true), line(2000, 5000, "b", true));
        assertEquals(1, m.focusIndexAt(4500));
        assertEquals(0, m.focusIndexAt(2500));
        assertEquals(-1, m.focusIndexAt(999));
        assertFalse(m.isSingingLine(0, 999));
    }

    @Test public void explicitFinalWordContinuesAcrossIncomingStart() {
        WordLine a = new WordLine(1000, "hold", new ArrayList<>(Arrays.asList(
                new WordRange(1000, 0, 4, 4000))), 4500, LyricTimingMode.WORD_TIMED);
        a.explicitEndTimeProvided = true;
        WordLyricModel m = model(a, line(2000, 5000, "incoming", true));
        assertEquals(4000, WordLyricRenderSupport.wordRevealEndMillis(m, a, 0));
        assertEquals(0.5f, WordLyricRenderSupport.wordRevealProgress(m, a, 0, 2500), 0.0001f);
    }

    @Test public void explicitOverlapNeverRevealsPastItsOwnLineEnd() {
        WordLine a = new WordLine(1000, "hold", new ArrayList<>(Arrays.asList(
                new WordRange(1000, 0, 4, 9000))), 4000, LyricTimingMode.WORD_TIMED);
        a.explicitEndTimeProvided = true;
        WordLyricModel m = model(a, line(2000, 6000, "incoming", true));
        assertEquals(4000, WordLyricRenderSupport.wordRevealEndMillis(m, a, 0));
    }

    @Test public void guessedRowKeepsExistingNextLineRevealBoundary() {
        WordLine a = new WordLine(1000, "hold", new ArrayList<>(Arrays.asList(
                new WordRange(1000, 0, 4, 4000))), 4500, LyricTimingMode.WORD_TIMED);
        WordLyricModel m = model(a, line(2000, 5000, "incoming", true));
        assertEquals(2000, WordLyricRenderSupport.wordRevealEndMillis(m, a, 0));
    }

    @Test public void touchingEndsHaveNoOverlapAndForeignRowsCannotJoin() {
        WordLyricModel m = model(line(1000, 2000, "a", true), line(2000, 4000, "b", true));
        assertFalse(m.hasVerifiedOverlapAfter(m.lines.get(0)));
        assertEquals(-1, m.overlapIndexAt(2000));
        assertFalse(m.isSingingLine(line(2000, 4000, "b", true), 2500));
    }

    @Test public void pendingHandoffWaitsOnlyForItsOriginalPair() {
        assertEquals(LyricOverlapHandoffPolicy.WAIT, LyricOverlapHandoffPolicy.action(0, 1, 0, 1));
        assertEquals(LyricOverlapHandoffPolicy.RELEASE, LyricOverlapHandoffPolicy.action(0, 1, 1, -1));
        assertEquals(LyricOverlapHandoffPolicy.CANCEL, LyricOverlapHandoffPolicy.action(0, 1, 3, -1));
        assertEquals(LyricOverlapHandoffPolicy.CANCEL, LyricOverlapHandoffPolicy.action(0, 1, 0, -1));
    }

    @Test public void pausedAndSnapshotClocksDoNotQueueHandoffPolling() {
        assertEquals(-1, LyricOverlapHandoffPolicy.delayMillis(2000, 4000, false, 1f));
        assertEquals(-1, LyricOverlapHandoffPolicy.delayMillis(2000, 4000, true, 0f));
        assertEquals(-1, LyricOverlapHandoffPolicy.delayMillis(2000, 4000, true, -1f));
        assertEquals(1000, LyricOverlapHandoffPolicy.delayMillis(2000, 4000, true, 2f));
        assertEquals(2000, LyricOverlapHandoffPolicy.delayMillis(2000, 4000, true, 1f));
    }
}
