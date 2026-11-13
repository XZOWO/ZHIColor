package com.naivety.zhicolor.bridge.render;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;

public final class SyllableLiftMotionTest {
    private static SyllableLiftMotion one(long end, int characters) {
        return new SyllableLiftMotion(Collections.singletonList(new WordRange(1_000L, 0, characters, end)));
    }

    @Test public void realStartTriggersOneSharedSpringIndependentOfLengthAndWordEnd() {
        SyllableLiftMotion shortWord = one(1_001L, 1), longWord = one(90_000L, 12);
        for (SyllableLiftMotion motion : Arrays.asList(shortWord, longWord)) {
            motion.sample(900L, 0L, true, true);
            motion.sample(999L, 99L, true, true);
            assertEquals(0f, motion.amountForCharacter(0), 0f);
            assertFalse(motion.isRunning());
            motion.sample(1_000L, 100L, true, true);
            assertTrue(motion.isRunning());
            motion.sample(1_100L, 200L, true, true);
        }
        assertTrue(shortWord.amountForCharacter(0) > 0f);
        assertEquals(shortWord.amountForCharacter(0), longWord.amountForCharacter(0), 0f);
        for (int i = 0; i < 12; i++)
            assertEquals(longWord.amountForCharacter(0), longWord.amountForCharacter(i), 0f);
    }

    @Test public void springMatchesClosedFormMassOneStiffnessFourteenDampingSeven() {
        SyllableLiftMotion motion = one(2_000L, 2);
        motion.sample(999L, 0L, true, true);
        motion.sample(1_000L, 1L, true, true);
        motion.sample(1_200L, 201L, true, true);
        double frequency = Math.sqrt(1.75d), seconds = 0.2d;
        double expected = 1d - Math.exp(-3.5d * seconds)
                * (Math.cos(frequency * seconds) + 3.5d / frequency * Math.sin(frequency * seconds));
        assertEquals(expected, motion.amountForCharacter(0), 1e-7d);
        assertEquals(2f, SyllableLiftMotion.LIFT_DP, 0f);
    }

    @Test public void duplicateFramesDoNotRestartAndPausedTriggeredSpringFinishes() {
        SyllableLiftMotion motion = one(99_000L, 2);
        motion.sample(999L, 0L, true, true);
        motion.sample(1_000L, 1L, true, true);
        motion.sample(1_100L, 101L, true, true);
        float first = motion.amountForCharacter(0);
        for (int i = 0; i < 50; i++) motion.sample(1_100L, 101L, true, true);
        assertEquals(first, motion.amountForCharacter(0), 0f);
        for (long now = 201L; now <= 5_001L; now += 100L) motion.sample(1_100L, now, true, true);
        assertEquals(1f, motion.amountForCharacter(0), 0f);
        assertFalse(motion.isRunning());
        motion.sample(1_100L, 5_101L, true, true);
        assertFalse(motion.isRunning());
    }

    @Test public void firstObservationAndLargeSeeksSettleInsteadOfReplaying() {
        SyllableLiftMotion motion = one(2_000L, 2);
        motion.sample(1_500L, 0L, true, true);
        assertEquals(1f, motion.amountForCharacter(0), 0f);
        assertFalse(motion.isRunning());
        motion.sample(0L, 16L, true, true);
        assertEquals(0f, motion.amountForCharacter(0), 0f);
        assertFalse(motion.isRunning());
        motion.sample(5_000L, 32L, true, true);
        assertEquals(1f, motion.amountForCharacter(0), 0f);
        assertFalse(motion.isRunning());
    }

    @Test public void longGapOrDisabledAnimationSettlesAndResetClearsState() {
        SyllableLiftMotion motion = one(2_000L, 2);
        motion.sample(999L, 0L, true, true);
        motion.sample(1_000L, 1L, true, true);
        motion.sample(1_000L, 2_000L, true, true);
        assertEquals(1f, motion.amountForCharacter(0), 0f);
        assertFalse(motion.isRunning());
        motion.sample(1_000L, 2_001L, false, false);
        assertEquals(0f, motion.amountForCharacter(0), 0f);
        motion.reset();
        assertFalse(motion.isRunning());
        assertEquals(0f, motion.amountForCharacter(0), 0f);
        motion.sample(1_500L, 2_002L, true, true);
        assertEquals(1f, motion.amountForCharacter(0), 0f);
        assertFalse(motion.isRunning());
    }

    @Test public void selectionAndBeforeStartReversalKeepPositionAndVelocity() {
        SyllableLiftMotion motion = one(2_000L, 2), control = one(2_000L, 2);
        for (SyllableLiftMotion item : Arrays.asList(motion, control)) {
            item.sample(999L, 0L, true, true);
            item.sample(1_000L, 1L, true, true);
            item.sample(1_100L, 101L, true, true);
        }
        float current = motion.amountForCharacter(0);
        motion.sample(1_100L, 101L, false, true);
        assertEquals(current, motion.amountForCharacter(0), 0f);
        motion.sample(1_100L, 102L, false, true);
        assertTrue("retarget retains upward velocity instead of snapping or reversing instantly",
                motion.amountForCharacter(0) > current);
        control.sample(999L, 101L, true, true);
        control.sample(999L, 102L, true, true);
        assertEquals(control.amountForCharacter(0), motion.amountForCharacter(0), 0f);
        for (long now = 202L; now <= 5_002L; now += 100L) motion.sample(1_100L, now, false, true);
        assertEquals(0f, motion.amountForCharacter(0), 0f);
        assertFalse(motion.isRunning());
    }

    @Test public void actualRangesHaveIndependentStartsAndUncoveredCharactersStayStill() {
        SyllableLiftMotion motion = new SyllableLiftMotion(Arrays.asList(
                new WordRange(1_000L, 3, 5), new WordRange(1_500L, 8, 11)));
        motion.sample(999L, 0L, true, true);
        motion.sample(1_000L, 1L, true, true);
        motion.sample(1_200L, 201L, true, true);
        assertTrue(motion.amountForCharacter(3) > 0f);
        assertEquals(motion.amountForRange(0), motion.amountForCharacter(4), 0f);
        assertEquals(0f, motion.amountForCharacter(8), 0f);
        for (int index : new int[]{-1,0,2,5,7,11,Integer.MAX_VALUE})
            assertEquals(0f, motion.amountForCharacter(index), 0f);
        motion.sample(1_500L, 501L, true, true);
        motion.sample(1_600L, 601L, true, true);
        assertTrue(motion.amountForCharacter(8) > 0f);
        assertEquals(motion.amountForCharacter(8), motion.amountForCharacter(10), 0f);
    }

    @Test public void invalidAndUnsortedRangesDoNotInventCoverageOrTimings() {
        SyllableLiftMotion motion = new SyllableLiftMotion(Arrays.asList(
                new WordRange(1_000L, 8, 10), null, new WordRange(1_000L, 0, 2),
                new WordRange(1_000L, 5, 5)));
        motion.sample(1_500L, 0L, true, true);
        assertEquals(1f, motion.amountForCharacter(0), 0f);
        assertEquals(1f, motion.amountForCharacter(9), 0f);
        assertEquals(0f, motion.amountForCharacter(5), 0f);
        assertEquals(0f, motion.amountForRange(1), 0f);
        assertEquals(0f, motion.amountForRange(-1), 0f);
        assertEquals(0f, motion.amountForRange(99), 0f);
        SyllableLiftMotion empty = new SyllableLiftMotion(null);
        empty.sample(1_500L, 0L, true, true);
        assertFalse(empty.isRunning());
        assertEquals(0f, empty.amountForCharacter(0), 0f);
    }
}
