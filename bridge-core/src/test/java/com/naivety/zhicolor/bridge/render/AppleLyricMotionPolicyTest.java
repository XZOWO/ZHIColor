package com.naivety.zhicolor.bridge.render;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class AppleLyricMotionPolicyTest {
    @Test
    public void springCurveIsStrictlyMonotonicFromZeroToOne() {
        assertEquals(0f, AppleLyricMotionPolicy.springValue(-0.1f), 0.0001f);
        assertEquals(0f, AppleLyricMotionPolicy.springValue(0f), 0.0001f);
        assertEquals(1f, AppleLyricMotionPolicy.springValue(1f), 0.0001f);
        assertEquals(1f, AppleLyricMotionPolicy.springValue(1.3f), 0.0001f);

        float previous = 0f;
        for (int i = 1; i <= 100; i++) {
            float progress = i / 100f;
            float value = AppleLyricMotionPolicy.springValue(progress);
            assertTrue("spring progress should stay in (0, 1] at " + progress,
                    value > 0f && value <= 1.0001f);
            assertTrue("spring curve must not reverse direction at " + progress
                    + ": prev=" + previous + " cur=" + value, value >= previous);
            previous = value;
        }
    }

    @Test
    public void dynamicDampingAndResponseInterpolateAcrossLineIntervals() {
        assertEquals(0.90f, AppleLyricMotionPolicy.dampingRatioForIntervalMillis(100L), 0.001f);
        assertEquals(0.48f, AppleLyricMotionPolicy.responseSecondsForIntervalMillis(100L), 0.001f);

        assertEquals(0.78f, AppleLyricMotionPolicy.dampingRatioForIntervalMillis(750L), 0.001f);
        assertEquals(0.75f, AppleLyricMotionPolicy.responseSecondsForIntervalMillis(750L), 0.001f);

        assertEquals(0.78f, AppleLyricMotionPolicy.dampingRatioForIntervalMillis(3000L), 0.001f);
        assertEquals(0.75f, AppleLyricMotionPolicy.responseSecondsForIntervalMillis(3000L), 0.001f);

        assertEquals(0.84f, AppleLyricMotionPolicy.dampingRatioForIntervalMillis(475L), 0.001f);
        assertEquals(0.615f, AppleLyricMotionPolicy.responseSecondsForIntervalMillis(475L), 0.001f);
    }

    @Test
    public void focusBezierMatchesEaseInOutSymmetry() {
        assertEquals(0f, AppleLyricMotionPolicy.focusInterpolation(0f), 0.0001f);
        assertEquals(1f, AppleLyricMotionPolicy.focusInterpolation(1f), 0.0001f);
        assertEquals(0.5f, AppleLyricMotionPolicy.focusInterpolation(0.5f), 0.002f);
        assertTrue(AppleLyricMotionPolicy.focusInterpolation(0.25f) < 0.25f);
        assertTrue(AppleLyricMotionPolicy.focusInterpolation(0.75f) > 0.75f);
    }

    @Test
    public void cascadeDelaysFollowForwardAndReverseOrder() {
        assertEquals(0L, AppleLyricMotionPolicy.cascadeDelayMillis(2, 3, 8, 120f));
        assertEquals(0L, AppleLyricMotionPolicy.cascadeDelayMillis(3, 3, 8, 120f));
        assertEquals(35L, AppleLyricMotionPolicy.cascadeDelayMillis(4, 3, 8, 120f));
        assertEquals(175L, AppleLyricMotionPolicy.cascadeDelayMillis(8, 3, 8, 120f));
        assertEquals(175L, AppleLyricMotionPolicy.cascadeDelayMillis(20, 3, 20, 120f));

        assertEquals(0L, AppleLyricMotionPolicy.cascadeDelayMillis(8, 3, 8, -120f));
        assertEquals(20L, AppleLyricMotionPolicy.cascadeDelayMillis(7, 3, 8, -120f));
        assertEquals(100L, AppleLyricMotionPolicy.cascadeDelayMillis(3, 3, 8, -120f));
        assertEquals(100L, AppleLyricMotionPolicy.cascadeDelayMillis(0, 1, 20, -120f));

        assertEquals(0L, AppleLyricMotionPolicy.cascadeDelayMillis(4, 3, 8, 0.2f));
    }

    @Test
    public void cascadeTranslationFollowsLagAndReturnsToZero() {
        float damping = AppleLyricMotionPolicy.DEFAULT_DAMPING_RATIO;
        float response = AppleLyricMotionPolicy.DEFAULT_RESPONSE_SECONDS;
        assertEquals(0f,
                AppleLyricMotionPolicy.cascadeTranslationY(120f, 0L, 680L, 35L, damping, response),
                0.0001f);
        assertTrue(AppleLyricMotionPolicy.cascadeTranslationY(
                120f, 35L, 680L, 35L, damping, response) > 0f);
        assertTrue(AppleLyricMotionPolicy.cascadeTranslationY(
                -120f, 35L, 680L, 20L, damping, response) < 0f);
        assertEquals(0f,
                AppleLyricMotionPolicy.cascadeTranslationY(120f, 715L, 680L, 35L, damping, response),
                0.0001f);
    }

    @Test
    public void cascadeLagRatioDecreasesMonotonicallyFromOneToZero() {
        float damping = AppleLyricMotionPolicy.DEFAULT_DAMPING_RATIO;
        float response = AppleLyricMotionPolicy.DEFAULT_RESPONSE_SECONDS;
        long[] delays = {20L, 35L, 70L, 105L, 140L, 175L};
        for (long delay : delays) {
            assertEquals(1f,
                    AppleLyricMotionPolicy.cascadeLagRatio(0L, 680L, delay, damping, response),
                    0.0001f);
            assertEquals(1f,
                    AppleLyricMotionPolicy.cascadeLagRatio(delay, 680L, delay, damping, response),
                    0.0001f);
            assertEquals(0f,
                    AppleLyricMotionPolicy.cascadeLagRatio(680L + delay, 680L, delay, damping, response),
                    0.0001f);
            float previous = 1f;
            for (long t = delay; t <= 680L + delay; t += 4L) {
                float ratio = AppleLyricMotionPolicy.cascadeLagRatio(t, 680L, delay, damping, response);
                assertTrue("lag ratio must stay in [0, 1] at t=" + t + " delay=" + delay,
                        ratio >= 0f && ratio <= 1f);
                assertTrue("lag ratio must decrease monotonically at t=" + t + " delay=" + delay
                        + ": prev=" + previous + " cur=" + ratio,
                        ratio <= previous + 1e-5f);
                previous = ratio;
            }
        }
        assertEquals(0f, AppleLyricMotionPolicy.cascadeLagRatio(100L, 680L, 0L, damping, response), 0.0001f);
    }

    @Test
    public void cascadeRowTranslationMatchesPerRowActualDisplacementAndPreservesCarry() {
        float damping = AppleLyricMotionPolicy.DEFAULT_DAMPING_RATIO;
        float response = AppleLyricMotionPolicy.DEFAULT_RESPONSE_SECONDS;
        long delay = 70L;
        // Identical to legacy cascadeTranslationY when actual delta equals -scrollDelta * S(t)
        for (long t = 0L; t <= 760L; t += 20L) {
            float mainP = AppleLyricMotionPolicy.springValue(t / 680f, damping, response);
            float actualDelta = -120f * mainP;
            float legacy = AppleLyricMotionPolicy.cascadeTranslationY(
                    120f, t, 680L, delay, damping, response);
            float perRow = AppleLyricMotionPolicy.cascadeRowTranslationY(
                    actualDelta, 0f, t, 680L, delay, damping, response);
            assertEquals("t=" + t, legacy, perRow, 0.01f);
        }
        // Zero actual movement never produces a false translation kick
        assertEquals(0f,
                AppleLyricMotionPolicy.cascadeRowTranslationY(0f, 0f, 120L, 680L, delay, damping, response),
                0.0001f);
        // Carry preserves exact visual position at t=0 and decays smoothly even when delay=0
        assertEquals(18f,
                AppleLyricMotionPolicy.cascadeRowTranslationY(0f, 18f, 0L, 680L, 0L, damping, response),
                0.0001f);
        float midCarry = AppleLyricMotionPolicy.cascadeRowTranslationY(
                -50f, 18f, 200L, 680L, 0L, damping, response);
        assertTrue("delay=0 carry should decay smoothly: " + midCarry, midCarry > 0f && midCarry < 18f);
        assertEquals(0f,
                AppleLyricMotionPolicy.cascadeRowTranslationY(-100f, 18f, 680L, 680L, 0L, damping, response),
                0.0001f);
    }
}
