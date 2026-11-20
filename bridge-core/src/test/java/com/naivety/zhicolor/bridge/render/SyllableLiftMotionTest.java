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

    @Test public void chineseMultiCharacterWordRangeLiftsStrictlyPerCharacter() {
        String text = "我爱你";
        SyllableLiftMotion motion = new SyllableLiftMotion(
                text,
                Collections.singletonList(new WordRange(1_000L, 0, text.length(), 1_900L)),
                1_900L);
        motion.sample(999L, 0L, true, true);
        assertEquals(0f, motion.amountForCharacter(0), 0f);
        assertEquals(0f, motion.amountForCharacter(1), 0f);
        assertEquals(0f, motion.amountForCharacter(2), 0f);

        // At 1000ms only '我' starts lifting; '爱' (1300ms) and '你' (1600ms) remain still.
        motion.sample(1_000L, 1L, true, true);
        motion.sample(1_150L, 151L, true, true);
        assertTrue(motion.amountForCharacter(0) > 0f);
        assertEquals(0f, motion.amountForCharacter(1), 0f);
        assertEquals(0f, motion.amountForCharacter(2), 0f);

        // At 1350ms '爱' has started lifting behind '我', while '你' is still waiting.
        motion.sample(1_350L, 351L, true, true);
        motion.sample(1_450L, 451L, true, true);
        assertTrue(motion.amountForCharacter(0) > motion.amountForCharacter(1));
        assertTrue(motion.amountForCharacter(1) > 0f);
        assertEquals(0f, motion.amountForCharacter(2), 0f);

        // At 1650ms '你' starts lifting independently behind '爱'.
        motion.sample(1_650L, 651L, true, true);
        motion.sample(1_750L, 751L, true, true);
        assertTrue(motion.amountForCharacter(1) > motion.amountForCharacter(2));
        assertTrue(motion.amountForCharacter(2) > 0f);
    }

    @Test public void westernWordsKeepWholeWordLiftAndMixedChineseWesternSplitsOnlyHan() {
        String western = "beautiful world";
        SyllableLiftMotion westernMotion = new SyllableLiftMotion(
                western,
                Arrays.asList(
                        new WordRange(1_000L, 0, 9, 1_900L),
                        new WordRange(2_000L, 10, 15, 2_800L)),
                2_800L);
        westernMotion.sample(999L, 0L, true, true);
        westernMotion.sample(1_000L, 1L, true, true);
        westernMotion.sample(1_150L, 151L, true, true);
        assertTrue(westernMotion.amountForCharacter(0) > 0f);
        for (int i = 0; i < 9; i++) {
            assertEquals("Western word must lift together",
                    westernMotion.amountForCharacter(0), westernMotion.amountForCharacter(i), 0f);
        }
        assertEquals(0f, westernMotion.amountForCharacter(10), 0f);

        // Mixed line: "我爱 forever" -> '我' and '爱' lift one-by-one, 'forever' lifts as one unit.
        String mixed = "我爱 forever";
        SyllableLiftMotion mixedMotion = new SyllableLiftMotion(
                mixed,
                Arrays.asList(
                        new WordRange(1_000L, 0, 2, 1_600L),
                        new WordRange(1_600L, 3, 10, 2_600L)),
                2_600L);
        mixedMotion.sample(999L, 0L, true, true);
        mixedMotion.sample(1_000L, 1L, true, true);
        mixedMotion.sample(1_150L, 151L, true, true);
        assertTrue(mixedMotion.amountForCharacter(0) > 0f);
        assertEquals(0f, mixedMotion.amountForCharacter(1), 0f);
        assertEquals(0f, mixedMotion.amountForCharacter(3), 0f);

        mixedMotion.sample(1_600L, 601L, true, true);
        mixedMotion.sample(1_750L, 751L, true, true);
        assertTrue(mixedMotion.amountForCharacter(3) > 0f);
        for (int i = 3; i < 10; i++) {
            assertEquals("Western token in mixed line must lift together",
                    mixedMotion.amountForCharacter(3), mixedMotion.amountForCharacter(i), 0f);
        }
    }

    @Test public void chineseSameTimestampRunsAndPunctuationLiftPerCharacterCleanly() {
        String sameTsText = "你好吗";
        SyllableLiftMotion sameTs = new SyllableLiftMotion(
                sameTsText,
                Arrays.asList(
                        new WordRange(1_000L, 0, 1, 1_000L),
                        new WordRange(1_000L, 1, 2, 1_000L),
                        new WordRange(1_000L, 2, 3, 1_900L)),
                1_900L);
        sameTs.sample(999L, 0L, true, true);
        sameTs.sample(1_000L, 1L, true, true);
        sameTs.sample(1_150L, 151L, true, true);
        assertTrue(sameTs.amountForCharacter(0) > 0f);
        assertEquals(0f, sameTs.amountForCharacter(1), 0f);
        assertEquals(0f, sameTs.amountForCharacter(2), 0f);

        // Trailing punctuation attaches to its preceding Han character instead of lifting separately.
        String punctText = "世界，你好！";
        SyllableLiftMotion punct = new SyllableLiftMotion(
                punctText,
                Collections.singletonList(new WordRange(1_000L, 0, punctText.length(), 2_200L)),
                2_200L);
        punct.sample(999L, 0L, true, true);
        punct.sample(1_300L, 301L, true, true);
        punct.sample(1_450L, 451L, true, true);
        assertEquals("Comma attaches to preceding '界'",
                punct.amountForCharacter(1), punct.amountForCharacter(2), 0f);
        assertTrue(punct.amountForCharacter(1) > 0f);
        assertEquals(0f, punct.amountForCharacter(3), 0f);
    }
}
