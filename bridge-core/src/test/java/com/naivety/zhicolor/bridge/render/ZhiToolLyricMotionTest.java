package com.naivety.zhicolor.bridge.render;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ZhiToolLyricMotionTest {
    private static final float EPSILON = 0.00001f;

    @Test
    public void firstBindingStartsAtItsTargetWithoutAnEntranceFromZero() {
        ZhiToolLyricMotion.Value value = new ZhiToolLyricMotion.Value();
        value.retarget(240f, 1_000L, ZhiToolLyricMotion.SCROLL_MS, true);

        assertEquals(240f, value.value(1_000L), EPSILON);
        assertFalse(value.isRunning(1_000L));
    }

    @Test
    public void reversingMidTransitionContinuesFromTheVisibleValue() {
        ZhiToolLyricMotion.Value value = new ZhiToolLyricMotion.Value();
        value.reset(0f);
        value.retarget(100f, 0L, 1_000L, true);
        float before = value.value(250L);
        assertEquals(57.8125f, before, EPSILON);

        value.retarget(0f, 250L, 1_000L, true);
        assertEquals(before, value.value(250L), EPSILON);
        float previous = before;
        for (long now = 260L; now <= 1_250L; now += 10L) {
            float current = value.value(now);
            assertTrue("reversal must move toward the new target", current <= previous);
            assertTrue("reversal must not overshoot", current >= 0f);
            previous = current;
        }
        assertEquals(0f, value.value(1_250L), EPSILON);
        assertFalse(value.isRunning(1_250L));
    }

    @Test
    public void repeatedTargetUpdatesDoNotPostponeCompletion() {
        ZhiToolLyricMotion.Value value = new ZhiToolLyricMotion.Value();
        value.reset(0f);
        value.retarget(1f, 1_000L, ZhiToolLyricMotion.SCROLL_MS, true);
        for (long now = 1_050L; now < 1_620L; now += 50L) {
            value.retarget(1f, now, ZhiToolLyricMotion.SCROLL_MS, true);
        }

        assertEquals(1f, value.value(1_620L), EPSILON);
        assertFalse(value.isRunning(1_620L));
    }

    @Test
    public void longFrameGapFinishesAtTheExactTarget() {
        ZhiToolLyricMotion.Value value = new ZhiToolLyricMotion.Value();
        value.reset(100f);
        value.retarget(-30f, 100L, ZhiToolLyricMotion.SCROLL_MS, true);
        assertTrue(value.isRunning(101L));

        assertEquals(-30f, value.value(60_000L), EPSILON);
        assertFalse(value.isRunning(60_000L));
        assertEquals(-30f, value.value(90_000L), EPSILON);
    }

    @Test
    public void disablingAnimationImmediatelySettlesAnUnchangedTarget() {
        ZhiToolLyricMotion.Value value = new ZhiToolLyricMotion.Value();
        value.reset(0f);
        value.retarget(1f, 0L, 1_000L, true);
        assertTrue(value.value(100L) < 1f);

        value.retarget(1f, 100L, 1_000L, false);
        assertEquals(1f, value.value(100L), EPSILON);
        assertFalse(value.isRunning(100L));
    }

    @Test
    public void resetAndNonpositiveDurationSettleImmediately() {
        ZhiToolLyricMotion.Value value = new ZhiToolLyricMotion.Value();
        value.reset(0f);
        value.retarget(1f, 0L, 1_000L, true);
        value.reset(8f);
        assertEquals(8f, value.value(1L), EPSILON);
        assertFalse(value.isRunning(1L));

        value.retarget(4f, 2L, 0L, true);
        assertEquals(4f, value.value(2L), EPSILON);
        assertFalse(value.isRunning(2L));
    }

    @Test
    public void anOlderFrameTimestampDoesNotRewindTheVisibleValue() {
        ZhiToolLyricMotion.Value value = new ZhiToolLyricMotion.Value();
        value.reset(0f);
        value.retarget(1f, 1_000L, 1_000L, true);
        float current = value.value(1_400L);
        assertEquals(current, value.value(1_300L), EPSILON);

        value.retarget(2f, 1_300L, 1_000L, true);
        assertEquals(current, value.value(1_400L), EPSILON);
        assertTrue(value.value(1_500L) > current);
    }

    @Test
    public void distanceLayersAreSymmetricAndMonotonicAcrossTheVisibleWindow() {
        float previousFade = 1f;
        float previousBlur = 0f;
        for (int step = 0; step <= 100; step++) {
            float slots = step * 0.05f;
            float fade = ZhiToolLyricMotion.distanceFade(slots);
            float blur = ZhiToolLyricMotion.distanceBlur(slots, 12f);
            assertTrue("farther rows must not brighten", fade <= previousFade);
            assertTrue("farther rows must not sharpen", blur >= previousBlur);
            assertTrue(fade >= 0f && fade <= 1f);
            assertTrue(blur >= 0f && blur <= 12f);
            assertEquals(fade, ZhiToolLyricMotion.distanceFade(-slots), EPSILON);
            assertEquals(blur, ZhiToolLyricMotion.distanceBlur(-slots, 12f), EPSILON);
            previousFade = fade;
            previousBlur = blur;
        }
        assertEquals(1f, ZhiToolLyricMotion.distanceFade(0f), EPSILON);
        assertEquals(0f, ZhiToolLyricMotion.distanceBlur(0f, 12f), EPSILON);
        assertEquals(0f, ZhiToolLyricMotion.distanceFade(3.6f), EPSILON);
        assertEquals(12f, ZhiToolLyricMotion.distanceBlur(3.6f, 12f), EPSILON);
    }

    @Test
    public void cubicEasingClampsAndDeceleratesWithoutOvershoot() {
        assertEquals(0f, ZhiToolLyricMotion.easeOutCubic(-1f), EPSILON);
        assertEquals(0f, ZhiToolLyricMotion.easeOutCubic(0f), EPSILON);
        assertEquals(0.875f, ZhiToolLyricMotion.easeOutCubic(0.5f), EPSILON);
        assertEquals(1f, ZhiToolLyricMotion.easeOutCubic(1f), EPSILON);
        assertEquals(1f, ZhiToolLyricMotion.easeOutCubic(2f), EPSILON);
        float previous = 0f;
        float previousStep = Float.POSITIVE_INFINITY;
        for (int frame = 1; frame <= 100; frame++) {
            float current = ZhiToolLyricMotion.easeOutCubic(frame / 100f);
            float step = current - previous;
            assertTrue(current >= previous);
            assertTrue("ease-out speed must fall", step <= previousStep + EPSILON);
            previous = current;
            previousStep = step;
        }
    }
}
