package com.naivety.zhicolor.bridge;

import java.util.function.IntSupplier;

/** Samples one animation's input clock without changing its curve or end time. */
final class AnimationFractionSampler {
    private final long durationMillis;
    private final IntSupplier maximumRefreshRate;
    private float lastSampled;

    AnimationFractionSampler(long durationMillis, IntSupplier maximumRefreshRate) {
        this.durationMillis = durationMillis;
        this.maximumRefreshRate = maximumRefreshRate;
    }

    float sample(float fraction) {
        if (fraction <= 0f) {
            lastSampled = 0f;
            return 0f;
        }
        if (fraction >= 1f) {
            lastSampled = 1f;
            return 1f;
        }
        int cap = maximumRefreshRate == null ? 0 : maximumRefreshRate.getAsInt();
        if (cap <= 0 || durationMillis <= 0L) {
            lastSampled = fraction;
            return fraction;
        }
        double frames = durationMillis * (double) cap / 1000d;
        float sampled = (float) (Math.floor(fraction * frames) / frames);
        // A live cap change can move the next sample grid behind the previous one.
        lastSampled = Math.max(lastSampled, sampled);
        return lastSampled;
    }
}
