package com.naivety.zhicolor.bridge.render;

import org.junit.Test;
import static org.junit.Assert.*;

public final class LineProgressLiftMotionTest {
    private static LineProgressLiftMotion uneven() {
        return new LineProgressLiftMotion(1000L, 2000L, 240, null,
                new int[]{0, 2}, new int[]{2, 4}, new float[]{40f, 60f}, 2,
                new int[]{0, 1, 2, 3, 4}, new float[]{0f, 10f, 40f, 80f}, 100f);
    }

    @Test public void unevenGraphemeAdvancesTriggerInOrderWithoutLiftingTheWholeSentence() {
        SyllableLiftMotion motion = uneven().motion;
        motion.sample(999L, 0L, true, true);
        motion.sample(1000L, 1L, true, true);
        motion.sample(1050L, 51L, true, true);
        assertTrue(motion.amountForCharacter(0) > 0f);
        assertEquals(0f, motion.amountForCharacter(1), 0f);
        assertEquals(0f, motion.amountForCharacter(2), 0f);
        motion.sample(1100L, 101L, true, true);
        motion.sample(1200L, 201L, true, true);
        assertTrue(motion.amountForCharacter(1) > 0f);
        assertEquals(0f, motion.amountForCharacter(2), 0f);
        motion.sample(1400L, 401L, true, true);
        motion.sample(1500L, 501L, true, true);
        assertTrue(motion.amountForCharacter(2) > 0f);
        assertEquals(0f, motion.amountForCharacter(3), 0f);
        motion.sample(1800L, 801L, true, true);
        motion.sample(1900L, 901L, true, true);
        assertTrue(motion.amountForCharacter(3) > 0f);
    }

    @Test public void earlySelectionStaysNeutralAndFractionalTriggerCannotFireEarly() {
        SyllableLiftMotion motion = new LineProgressLiftMotion(1000L, 1007L, 240, null,
                new int[]{0}, new int[]{3}, new float[]{3f}, 1,
                new int[]{0, 1, 2, 3}, new float[]{0f, 1f, 2f}, 3f).motion;
        motion.sample(800L, 0L, true, true);
        motion.sample(999L, 199L, true, true);
        assertEquals(0f, motion.amountForCharacter(0), 0f);
        assertFalse(motion.isRunning());
        motion.sample(1002L, 202L, true, true);
        assertEquals(0f, motion.amountForCharacter(1), 0f);
        motion.sample(1003L, 203L, true, true);
        motion.sample(1004L, 204L, true, true);
        assertTrue(motion.amountForCharacter(1) > 0f);
        assertEquals(0f, motion.amountForCharacter(2), 0f);
    }

    @Test public void combiningAndSurrogateCodeUnitsStayInTheirOwnGraphemeSpring() {
        // a + combining acute, surrogate-pair emoji, b. Boundaries are UTF-16 offsets.
        SyllableLiftMotion motion = new LineProgressLiftMotion(1000L, 2000L, 240, null,
                new int[]{0}, new int[]{5}, new float[]{100f}, 1,
                new int[]{0, 2, 4, 5}, new float[]{0f, 20f, 80f}, 100f).motion;
        motion.sample(999L, 0L, true, true);
        motion.sample(1000L, 1L, true, true);
        motion.sample(1200L, 201L, true, true);
        assertTrue(motion.amountForCharacter(0) > 0f);
        assertEquals(motion.amountForCharacter(0), motion.amountForCharacter(1), 0f);
        motion.sample(1400L, 401L, true, true);
        assertTrue(motion.amountForCharacter(2) > 0f);
        assertEquals(motion.amountForCharacter(2), motion.amountForCharacter(3), 0f);
        assertEquals(0f, motion.amountForCharacter(4), 0f);
        assertEquals(0f, motion.amountForCharacter(5), 0f);
    }

    @Test public void pausedTriggeredSpringFinishesWithoutTriggeringTheNextGrapheme() {
        SyllableLiftMotion motion = uneven().motion;
        motion.sample(999L, 0L, true, true);
        motion.sample(1000L, 1L, true, true);
        for (long now = 101L; now <= 5001L; now += 100L) motion.sample(1000L, now, true, true);
        assertEquals(1f, motion.amountForCharacter(0), 0f);
        assertEquals(0f, motion.amountForCharacter(1), 0f);
        assertFalse(motion.isRunning());
    }

    @Test public void seeksAndFirstObservationSettleInsteadOfReplayingPastThresholds() {
        SyllableLiftMotion motion = uneven().motion;
        motion.sample(1500L, 0L, true, true);
        assertEquals(1f, motion.amountForCharacter(2), 0f);
        assertEquals(0f, motion.amountForCharacter(3), 0f);
        assertFalse(motion.isRunning());
        motion.sample(800L, 16L, true, true);
        assertEquals(0f, motion.amountForCharacter(0), 0f);
        assertFalse(motion.isRunning());
        motion.sample(1900L, 32L, true, true);
        for (int i = 0; i < 4; i++) assertEquals(1f, motion.amountForCharacter(i), 0f);
        assertFalse(motion.isRunning());
    }

    @Test public void duplicateDrawPassesDoNotRestartAnExistingSpring() {
        SyllableLiftMotion motion = uneven().motion;
        motion.sample(999L, 0L, true, true);
        motion.sample(1000L, 1L, true, true);
        motion.sample(1050L, 51L, true, true);
        float amount = motion.amountForCharacter(0);
        for (int i = 0; i < 50; i++) motion.sample(1050L, 51L, true, true);
        assertEquals(amount, motion.amountForCharacter(0), 0f);
        motion.sample(1060L, 61L, true, true);
        assertTrue(motion.amountForCharacter(0) > amount);
    }

    @Test public void layoutSnapshotIsClonedAndMatchesOnlyExactActiveGeometryAndTypeface() {
        Object font = new Object();
        int[] starts = {0, 2, 999}, ends = {2, 4, 999};
        float[] widths = {40f, 60f, 999f};
        LineProgressLiftMotion holder = new LineProgressLiftMotion(1000L, 2000L, 240, font,
                starts, ends, widths, 2, new int[]{0, 1, 2, 3, 4}, new float[]{0f, 10f, 40f, 80f}, 100f);
        assertTrue(holder.matches(1000L, 2000L, 240, font,
                new int[]{0, 2}, new int[]{2, 4}, new float[]{40f, 60f}, 2));
        starts[0] = 1; ends[1] = 5; widths[0] = 41f;
        assertFalse(holder.matches(1000L, 2000L, 240, font, starts, ends, widths, 2));
        assertTrue(holder.matches(1000L, 2000L, 240, font,
                new int[]{0, 2}, new int[]{2, 4}, new float[]{40f, 60f}, 2));
        assertFalse(holder.matches(999L, 2000L, 240, font, new int[]{0, 2}, new int[]{2, 4}, new float[]{40f, 60f}, 2));
        assertFalse(holder.matches(1000L, 2001L, 240, font, new int[]{0, 2}, new int[]{2, 4}, new float[]{40f, 60f}, 2));
        assertFalse(holder.matches(1000L, 2000L, 241, font, new int[]{0, 2}, new int[]{2, 4}, new float[]{40f, 60f}, 2));
        assertFalse(holder.matches(1000L, 2000L, 240, new Object(), new int[]{0, 2}, new int[]{2, 4}, new float[]{40f, 60f}, 2));
        assertFalse(holder.matches(1000L, 2000L, 240, font, new int[]{0, 2}, new int[]{2, 4}, new float[]{40f, Math.nextUp(60f)}, 2));
        assertFalse(holder.matches(1000L, 2000L, 240, font, new int[]{0}, new int[]{2}, new float[]{40f}, 1));
        assertFalse(holder.matches(1000L, 2000L, 240, font, null, new int[]{2, 4}, new float[]{40f, 60f}, 2));
    }

    @Test public void invalidLayoutsAndOverallGeometryRemainNeutral() {
        LineProgressLiftMotion[] invalid = {
                new LineProgressLiftMotion(1000L, 1000L, 240, null, new int[]{0}, new int[]{2}, new float[]{100f}, 1, new int[]{0, 1, 2}, new float[]{0f, 50f}, 100f),
                new LineProgressLiftMotion(1000L, 2000L, 240, null, null, new int[]{2}, new float[]{100f}, 1, new int[]{0, 1, 2}, new float[]{0f, 50f}, 100f),
                new LineProgressLiftMotion(1000L, 2000L, 240, null, new int[]{0}, new int[]{2}, new float[]{Float.NaN}, 1, new int[]{0, 1, 2}, new float[]{0f, 50f}, 100f),
                new LineProgressLiftMotion(1000L, 2000L, 240, null, new int[]{0}, new int[]{2}, new float[]{100f}, 2, new int[]{0, 1, 2}, new float[]{0f, 50f}, 100f),
                new LineProgressLiftMotion(1000L, 2000L, 240, null, new int[]{0}, new int[]{2}, new float[]{100f}, 1, new int[]{0, 1, 2}, new float[]{0f, 50f}, 0f),
                new LineProgressLiftMotion(1000L, 2000L, 240, null, new int[]{0}, new int[]{2}, new float[]{0f}, 1, new int[]{0, 1, 2}, new float[]{0f, 50f}, 100f),
                new LineProgressLiftMotion(1000L, 2000L, 240, null, new int[]{0}, new int[]{2}, new float[]{100f}, 1, new int[]{0, 1, 2}, new float[]{0f}, 100f)
        };
        for (LineProgressLiftMotion holder : invalid) {
            holder.motion.sample(5000L, 0L, true, true);
            assertEquals(0f, holder.motion.amountForCharacter(0), 0f);
            assertFalse(holder.motion.isRunning());
        }
    }

    @Test public void malformedIndividualGraphemeGeometryDoesNotLeakCoverage() {
        SyllableLiftMotion motion = new LineProgressLiftMotion(1000L, 2000L, 240, null,
                new int[]{0}, new int[]{4}, new float[]{100f}, 1,
                new int[]{0, 1, 2, 3, 4}, new float[]{0f, Float.NaN, 50f, 25f}, 100f).motion;
        motion.sample(5000L, 0L, true, true);
        assertEquals(1f, motion.amountForCharacter(0), 0f);
        assertEquals(0f, motion.amountForCharacter(1), 0f);
        assertEquals(1f, motion.amountForCharacter(2), 0f);
        assertEquals(0f, motion.amountForCharacter(3), 0f);
    }
}
