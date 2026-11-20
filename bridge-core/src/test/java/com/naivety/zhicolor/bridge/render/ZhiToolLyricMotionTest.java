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
    public void retargetFocusFollowsAppleFocusCurveAndInterruptsSmoothly() {
        ZhiToolLyricMotion.Value scale = new ZhiToolLyricMotion.Value();
        scale.reset(0.90f);
        scale.retargetFocus(1.00f, 1_000L, AppleLyricMotionPolicy.FOCUS_MS, true, true);
        assertTrue(scale.isRunning(1_000L));
        float midExpected = 0.90f + 0.10f * AppleLyricMotionPolicy.focusInterpolation(0.5f);
        assertEquals(midExpected, scale.value(1_000L + AppleLyricMotionPolicy.FOCUS_MS / 2L), 0.0005f);

        // Retargeting mid-flight preserves current scale without snapping.
        float beforeReverse = scale.value(1_000L + AppleLyricMotionPolicy.FOCUS_MS / 2L);
        scale.retargetFocus(0.90f, 1_000L + AppleLyricMotionPolicy.FOCUS_MS / 2L,
                AppleLyricMotionPolicy.FOCUS_MS, true, true);
        assertEquals(beforeReverse, scale.value(1_000L + AppleLyricMotionPolicy.FOCUS_MS / 2L), 0.0005f);
        assertEquals(0.90f, scale.value(1_000L + AppleLyricMotionPolicy.FOCUS_MS * 2L), 0.0001f);
        assertFalse(scale.isRunning(1_000L + AppleLyricMotionPolicy.FOCUS_MS * 2L));
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
    public void physicsSpringApproachesTargetSmoothlyAndPreservesVelocityOnInterruption() {
        double hideOmega = (2d * Math.PI) / 0.2d;
        double hideStiffness = hideOmega * hideOmega;
        double hideDamping = 2d * hideOmega;

        ZhiToolLyricMotion.Value show = new ZhiToolLyricMotion.Value();
        show.reset(0f);
        show.retargetSpring(1f, 0L, 30d, 9d, true);
        assertTrue(show.isRunning(0L));
        float mid = show.value(300L);
        assertTrue(mid > 0.3f && mid < 1.02f);

        // Repeated calls with identical spring target/parameters keep the active trajectory.
        show.retargetSpring(1f, 300L, 30d, 9d, true);
        assertEquals(mid, show.value(300L), EPSILON);
        assertEquals(1f, show.value(3_000L), 0.001f);
        assertFalse(show.isRunning(3_000L));

        // Critically damped 0.2s hide spring decays without negative undershoot and settles quickly.
        ZhiToolLyricMotion.Value hide = new ZhiToolLyricMotion.Value();
        hide.reset(1f);
        hide.retargetSpring(0f, 0L, hideStiffness, hideDamping, true);
        float previous = 1f;
        for (long t = 20L; t <= 400L; t += 20L) {
            float sample = hide.value(t);
            assertTrue(sample >= -0.0005f && sample <= previous + EPSILON);
            previous = sample;
        }
        assertEquals(0f, hide.value(500L), 0.001f);
        assertFalse(hide.isRunning(500L));

        // Interrupting show mid-flight preserves continuity at the interruption timestamp.
        ZhiToolLyricMotion.Value interrupted = new ZhiToolLyricMotion.Value();
        interrupted.reset(0f);
        interrupted.retargetSpring(1f, 0L, 30d, 9d, true);
        float beforeReverse = interrupted.value(200L);
        interrupted.retargetSpring(0f, 200L, hideStiffness, hideDamping, true);
        assertEquals(beforeReverse, interrupted.value(200L), 0.001f);
        assertEquals(0f, interrupted.value(1_000L), 0.001f);

        // Non-animated spring update settles immediately.
        interrupted.retargetSpring(1f, 1_100L, 30d, 9d, false);
        assertEquals(1f, interrupted.value(1_100L), EPSILON);
        assertFalse(interrupted.isRunning(1_100L));
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
