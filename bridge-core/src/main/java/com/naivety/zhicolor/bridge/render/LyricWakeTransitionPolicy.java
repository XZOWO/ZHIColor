package com.naivety.zhicolor.bridge.render;

/** A visible-frame clock for the full-line AOD to karaoke handoff. */
public final class LyricWakeTransitionPolicy {
    public static final long DEFAULT_DURATION_MILLIS = 420L;

    private final long durationMillis;
    private boolean aod;
    private boolean armed;
    private boolean running;
    private boolean paused;
    private long lastSampleMillis = Long.MIN_VALUE;
    private long lastVisibleMillis;
    private long visibleElapsedMillis;
    private float amount;

    public LyricWakeTransitionPolicy() {
        this(DEFAULT_DURATION_MILLIS);
    }

    public LyricWakeTransitionPolicy(long durationMillis) {
        this.durationMillis = Math.max(1L, durationMillis);
    }

    public void setAod(boolean enabled) {
        if (aod == enabled) return;
        aod = enabled;
        armed = !enabled;
        running = false;
        paused = false;
        visibleElapsedMillis = 0L;
        amount = 1f;
    }

    /**
     * Returns the full-line AOD contribution. Hidden draws retain the last visible sample;
     * the first visible draw after a hidden interval resumes without spending hidden time.
     */
    public float sample(long nowMillis, boolean visiblyDrawing, boolean animate) {
        long now = Math.max(0L, nowMillis);
        if (lastSampleMillis != Long.MIN_VALUE) now = Math.max(now, lastSampleMillis);
        lastSampleMillis = now;
        if (aod) return amount = 1f;
        if (!animate) {
            armed = false;
            running = false;
            paused = false;
            visibleElapsedMillis = 0L;
            return amount = 0f;
        }
        if (armed) {
            if (!visiblyDrawing) return amount;
            armed = false;
            running = true;
            paused = false;
            lastVisibleMillis = now;
            return amount;
        }
        if (!running) return amount;
        if (!visiblyDrawing) {
            paused = true;
            return amount;
        }
        if (paused) {
            paused = false;
            lastVisibleMillis = now;
            return amount;
        }
        long remaining = durationMillis - visibleElapsedMillis;
        visibleElapsedMillis += Math.min(remaining, now - lastVisibleMillis);
        lastVisibleMillis = now;
        float progress = visibleElapsedMillis / (float) durationMillis;
        amount = 1f - progress * progress * (3f - 2f * progress);
        if (visibleElapsedMillis >= durationMillis) {
            running = false;
            amount = 0f;
        }
        return amount;
    }

    public float amount() {
        return amount;
    }

    public float brightAmount() {
        return 1f - amount;
    }

    public boolean isRunning() {
        return running;
    }

    public boolean isArmed() {
        return armed;
    }

    public static float lerp(float from, float to, float amount) {
        return from + (to - from) * clampUnit(amount);
    }

    public static float highlightAmount(float wordMask, float aodAmount) {
        float aod = clampUnit(aodAmount);
        return aod + (1f - aod) * clampUnit(wordMask);
    }

    public static float liftAmount(float sink, float aodAmount) {
        return (Float.isFinite(sink) ? sink : 0f) * (1f - clampUnit(aodAmount));
    }

    private static float clampUnit(float amount) {
        return Float.isNaN(amount) ? 0f : Math.max(0f, Math.min(1f, amount));
    }
}
