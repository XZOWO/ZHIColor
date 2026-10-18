package com.naivety.zhicolor.bridge;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import static org.junit.Assert.*;

public final class AnimationFractionSamplerTest {
    @Test public void oneHundredTwentyHzInputUsesTheRequestedSixtyOrNinetyHzGrid() {
        for (int cap : new int[]{60, 90}) {
            AnimationFractionSampler sampler = new AnimationFractionSampler(1000L, () -> cap);
            Set<Integer> outputFrames = new HashSet<>();
            int repeated = 0;
            float previous = -1f;
            for (int frame = 0; frame <= 120; frame++) {
                float input = frame / 120f;
                float output = sampler.sample(input);
                float expected = frame == 120 ? 1f
                        : (float) (Math.floor(input * (double) cap) / cap);
                assertEquals(expected, output, 0f);
                assertTrue(output >= previous);
                if (output == previous) repeated++;
                outputFrames.add(Math.round(output * cap));
                previous = output;
            }
            assertTrue(repeated > 0);
            assertTrue(outputFrames.size() <= cap + 1);
            assertEquals(1f, previous, 0f);
        }
    }

    @Test public void aLowRefreshRateNeverRequiresAdditionalHardwareTicksToFinish() {
        for (int cap : new int[]{60, 90, 120}) {
            AnimationFractionSampler sampler = new AnimationFractionSampler(600L, () -> cap);
            float previous = 0f;
            // A 30 Hz surface supplies only 18 updates over this animation.
            for (int frame = 0; frame <= 18; frame++) {
                float input = frame / 18f;
                float output = sampler.sample(input);
                assertTrue(output >= previous);
                assertTrue(output <= input);
                assertTrue(input - output < 1000f / (600f * cap) + 0.00001f);
                previous = output;
            }
            assertEquals(1f, previous, 0f);
        }
    }

    @Test public void changingTheCapDuringAnAnimationNeverMovesTheClockBackward() {
        AtomicInteger cap = new AtomicInteger(90);
        AnimationFractionSampler sampler = new AnimationFractionSampler(1000L, cap::get);
        float previous = sampler.sample(0.19f);
        cap.set(60);
        assertEquals(previous, sampler.sample(0.191f), 0f);
        cap.set(30);
        assertEquals(previous, sampler.sample(0.192f), 0f);
        assertTrue(sampler.sample(0.21f) > previous);
        cap.set(0);
        assertEquals(0.211f, sampler.sample(0.211f), 0f);
        cap.set(60);
        assertEquals(0.211f, sampler.sample(0.212f), 0f);
        assertEquals(1f, sampler.sample(1f), 0f);
    }

    @Test public void endpointsAreExactForEveryDurationAndCapWithoutAnExtraFrame() {
        for (long duration : new long[]{0L, 1L, 333L, 620L, 1000L}) {
            for (int cap : new int[]{0, 1, 60, 90, 120}) {
                AnimationFractionSampler sampler = new AnimationFractionSampler(duration, () -> cap);
                assertEquals(0f, sampler.sample(-1f), 0f);
                assertEquals(0f, sampler.sample(0f), 0f);
                assertTrue(sampler.sample(0.999f) < 1f);
                assertEquals(1f, sampler.sample(1f), 0f);
                assertEquals(1f, sampler.sample(2f), 0f);
            }
        }
    }

    @Test public void disabledCapsOrNonpositiveDurationsPassThroughUnchanged() {
        for (int cap : new int[]{-1, 0}) {
            AnimationFractionSampler sampler = new AnimationFractionSampler(620L, () -> cap);
            assertEquals(0.123456f, sampler.sample(0.123456f), 0f);
        }
        for (long duration : new long[]{-1L, 0L}) {
            AnimationFractionSampler sampler = new AnimationFractionSampler(duration, () -> 60);
            assertEquals(0.123456f, sampler.sample(0.123456f), 0f);
        }
        assertEquals(0.123456f, new AnimationFractionSampler(620L, null).sample(0.123456f), 0f);
    }

    @Test public void animationsKeepIndependentSamplingHistoryAndUseTheirOwnDurations() {
        AnimationFractionSampler first = new AnimationFractionSampler(1000L, () -> 60);
        AnimationFractionSampler second = new AnimationFractionSampler(500L, () -> 60);
        first.sample(0.9f);
        assertEquals(1f / 30f, second.sample(0.05f), 0f);
        AnimationFractionSampler next = new AnimationFractionSampler(1000L, () -> 60);
        assertEquals(3f / 60f, next.sample(0.05f), 0f);
    }
}
