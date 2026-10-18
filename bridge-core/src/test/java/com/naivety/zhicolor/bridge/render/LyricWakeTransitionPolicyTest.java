package com.naivety.zhicolor.bridge.render;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LyricWakeTransitionPolicyTest {
    @Test public void defaultBrightStateDoesNotInventAWakeTransition() {
        LyricWakeTransitionPolicy policy = new LyricWakeTransitionPolicy();
        policy.setAod(false);
        assertEquals(0f, policy.sample(100L, true, true), 0f);
        assertEquals(1f, policy.brightAmount(), 0f);
        assertFalse(policy.isArmed());
        assertFalse(policy.isRunning());
    }

    @Test public void enteringAodSettlesWithoutAHiddenAnimation() {
        LyricWakeTransitionPolicy policy = new LyricWakeTransitionPolicy();
        policy.setAod(true);
        assertEquals(1f, policy.amount(), 0f);
        assertEquals(1f, policy.sample(10_000L, false, true), 0f);
        assertFalse(policy.isArmed());
        assertFalse(policy.isRunning());
    }

    @Test public void broadcastToVisibleDelayNeverSpendsTheWakeAnimation() {
        LyricWakeTransitionPolicy policy = new LyricWakeTransitionPolicy(400L);
        policy.setAod(true);
        policy.setAod(false);
        assertTrue(policy.isArmed());
        assertEquals(1f, policy.sample(10_000L, false, true), 0f);
        assertEquals(1f, policy.sample(20_000L, false, true), 0f);
        assertFalse(policy.isRunning());
        assertEquals(1f, policy.sample(30_000L, true, true), 0f);
        assertFalse(policy.isArmed());
        assertTrue(policy.isRunning());
        assertEquals(.5f, policy.sample(30_200L, true, true), .00001f);
        assertEquals(0f, policy.sample(30_400L, true, true), 0f);
        assertFalse(policy.isRunning());
    }

    @Test public void hiddenDrawsPauseAtTheLastVisibleFrameAndResumeContinuously() {
        LyricWakeTransitionPolicy policy = new LyricWakeTransitionPolicy(400L);
        policy.setAod(true);
        policy.setAod(false);
        policy.sample(0L, true, true);
        float lastVisible = policy.sample(100L, true, true);
        assertEquals(.84375f, lastVisible, 0f);
        assertEquals(lastVisible, policy.sample(200L, false, true), 0f);
        assertEquals(lastVisible, policy.sample(5_000L, false, true), 0f);
        assertEquals(lastVisible, policy.sample(10_000L, true, true), 0f);
        assertEquals(.5f, policy.sample(10_100L, true, true), .00001f);
        assertEquals(0f, policy.sample(10_300L, true, true), 0f);
    }

    @Test public void repeatedModeSettersDoNotRestartArmedOrRunningWork() {
        LyricWakeTransitionPolicy policy = new LyricWakeTransitionPolicy(400L);
        policy.setAod(true);
        policy.setAod(true);
        policy.setAod(false);
        policy.setAod(false);
        policy.sample(0L, true, true);
        policy.sample(100L, true, true);
        policy.setAod(false);
        assertEquals(.5f, policy.sample(200L, true, true), .00001f);
        assertEquals(0f, policy.sample(400L, true, true), 0f);
        policy.setAod(false);
        assertEquals(0f, policy.sample(900L, true, true), 0f);
        assertFalse(policy.isRunning());
    }

    @Test public void rapidReverseSettlesAodAndArmsANewVisibleWake() {
        LyricWakeTransitionPolicy policy = new LyricWakeTransitionPolicy(400L);
        policy.setAod(true);
        policy.setAod(false);
        policy.sample(0L, true, true);
        assertEquals(.5f, policy.sample(200L, true, true), .00001f);
        policy.setAod(true);
        assertEquals(1f, policy.sample(210L, false, true), 0f);
        assertFalse(policy.isRunning());
        policy.setAod(false);
        assertEquals(1f, policy.sample(10_000L, true, true), 0f);
        assertEquals(.5f, policy.sample(10_200L, true, true), .00001f);
    }

    @Test public void disabledMotionFinishesEvenBeforeTheSurfaceBecomesVisible() {
        LyricWakeTransitionPolicy policy = new LyricWakeTransitionPolicy();
        policy.setAod(true);
        policy.setAod(false);
        assertEquals(0f, policy.sample(1L, false, false), 0f);
        assertFalse(policy.isArmed());
        assertFalse(policy.isRunning());
        assertEquals(0f, policy.sample(5_000L, true, true), 0f);
        policy.setAod(true);
        assertEquals(1f, policy.sample(6_000L, false, false), 0f);
    }

    @Test public void disablingMotionMidWakeDoesNotLeaveALaterTail() {
        LyricWakeTransitionPolicy policy = new LyricWakeTransitionPolicy(400L);
        policy.setAod(true);
        policy.setAod(false);
        policy.sample(0L, true, true);
        policy.sample(100L, true, true);
        assertEquals(0f, policy.sample(110L, true, false), 0f);
        assertEquals(0f, policy.sample(120L, true, true), 0f);
    }

    @Test public void repeatedTimestampAndOlderTimestampDoNotRewindTheBlend() {
        LyricWakeTransitionPolicy policy = new LyricWakeTransitionPolicy(400L);
        policy.setAod(true);
        policy.setAod(false);
        policy.sample(100L, true, true);
        float amount = policy.sample(200L, true, true);
        assertEquals(amount, policy.sample(200L, true, true), 0f);
        assertEquals(amount, policy.sample(150L, true, true), 0f);
        assertEquals(.5f, policy.sample(300L, true, true), .00001f);
    }

    @Test public void defaultDurationAndLerpKeepTheTwoEndpointsExact() {
        LyricWakeTransitionPolicy policy = new LyricWakeTransitionPolicy();
        policy.setAod(true);
        policy.setAod(false);
        policy.sample(0L, true, true);
        assertEquals(.5f, policy.sample(210L, true, true), .00001f);
        assertEquals(0f, policy.sample(420L, true, true), 0f);
        assertEquals(10f, LyricWakeTransitionPolicy.lerp(10f, 30f, -1f), 0f);
        assertEquals(20f, LyricWakeTransitionPolicy.lerp(10f, 30f, .5f), 0f);
        assertEquals(30f, LyricWakeTransitionPolicy.lerp(10f, 30f, 2f), 0f);
    }

    @Test public void aodAndBrightMasksHaveExactSinglePassEndpoints() {
        for (float wordMask : new float[]{0f, .25f, .5f, .75f, 1f}) {
            assertEquals(1f, LyricWakeTransitionPolicy.highlightAmount(wordMask, 1f), 0f);
            assertEquals(wordMask, LyricWakeTransitionPolicy.highlightAmount(wordMask, 0f), 0f);
        }
        assertEquals(0f, LyricWakeTransitionPolicy.liftAmount(12f, 1f), 0f);
        assertEquals(12f, LyricWakeTransitionPolicy.liftAmount(12f, 0f), 0f);
    }

    @Test public void halfwayHandoffKeepsSungTextLitAndFadesUnsungTextIntoItsOwnMask() {
        assertEquals(.5f, LyricWakeTransitionPolicy.highlightAmount(0f, .5f), 0f);
        assertEquals(.75f, LyricWakeTransitionPolicy.highlightAmount(.5f, .5f), 0f);
        assertEquals(1f, LyricWakeTransitionPolicy.highlightAmount(1f, .5f), 0f);
        assertEquals(6f, LyricWakeTransitionPolicy.liftAmount(12f, .5f), 0f);
        for (int i = 0; i <= 10; i++) {
            float mask = i / 10f;
            float amount = LyricWakeTransitionPolicy.highlightAmount(mask, .5f);
            assertTrue(amount >= mask && amount <= 1f);
        }
    }

    @Test public void invalidMaskInputsDoNotEscapeTheAlphaRange() {
        assertEquals(1f, LyricWakeTransitionPolicy.highlightAmount(-1f, 2f), 0f);
        assertEquals(1f, LyricWakeTransitionPolicy.highlightAmount(2f, -1f), 0f);
        assertEquals(0f, LyricWakeTransitionPolicy.highlightAmount(Float.NaN, Float.NaN), 0f);
        assertEquals(.5f, LyricWakeTransitionPolicy.highlightAmount(Float.NaN, .5f), 0f);
        assertEquals(0f, LyricWakeTransitionPolicy.liftAmount(Float.NaN, .5f), 0f);
        assertEquals(12f, LyricWakeTransitionPolicy.liftAmount(12f, Float.NaN), 0f);
        assertEquals(10f, LyricWakeTransitionPolicy.lerp(10f, 30f, Float.NaN), 0f);
    }
}
