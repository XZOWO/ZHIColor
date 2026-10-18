package com.naivety.zhicolor.bridge.render;

/**
 * Android-free motion shared with the lock-screen renderer. Curves and timings
 * follow ZHITool's FullLyricView: elapsed-time cubic easing, continuous
 * retargeting, and a gradual loss of opacity and sharpness away from the focus.
 */
public final class ZhiToolLyricMotion {

    public static final long SCROLL_MS = 620L;
    public static final long FOCUS_MS = 300L;
    public static final long SECONDARY_MS = 380L;
    public static final long SECONDARY_DELAY_MS = 248L;
    public static final long SONG_SWITCH_MS = 500L;

    private ZhiToolLyricMotion() {
    }

    public static float easeOutCubic(float progress) {
        float remaining = 1f - clampUnit(progress);
        return 1f - remaining * remaining * remaining;
    }

    public static float distanceFade(float distanceSlots) {
        float distance = clampUnit(Math.abs(distanceSlots) / 3.6f);
        return (float) Math.pow(1f - distance, 1.45f);
    }

    public static float distanceBlur(float distanceSlots, float maxRadius) {
        float distance = clampUnit(Math.abs(distanceSlots) / 3.6f);
        return maxRadius * (float) Math.pow(distance, 1.6f);
    }

    private static float clampUnit(float value) {
        return Math.max(0f, Math.min(value, 1f));
    }

    /**
     * One interruptible channel. Callers supply one monotonic wall clock for
     * every channel, so skipped frames never stretch a transition's duration.
     */
    public static final class Value {
        private boolean initialized;
        private boolean running;
        private float from;
        private float target;
        private long startedAt;
        private long duration;
        private long lastSampleAt = Long.MIN_VALUE;

        public float value(long now) {
            long sampleAt = Math.max(now, lastSampleAt);
            lastSampleAt = sampleAt;
            if (!running) {
                return target;
            }
            long elapsed = sampleAt - startedAt;
            if (elapsed >= duration) {
                running = false;
                from = target;
                return target;
            }
            float progress = elapsed <= 0L ? 0f : (float) elapsed / duration;
            return from + (target - from) * easeOutCubic(progress);
        }

        public void retarget(float target, long now, long duration, boolean animate) {
            if (!initialized || !animate || duration <= 0L) {
                reset(target);
                lastSampleAt = now;
                return;
            }
            float current = value(now);
            if (this.target == target) {
                return;
            }
            from = current;
            this.target = target;
            startedAt = lastSampleAt;
            this.duration = duration;
            running = current != target;
        }

        public boolean isRunning(long now) {
            value(now);
            return running;
        }

        public void reset(float value) {
            initialized = true;
            running = false;
            from = value;
            target = value;
            startedAt = 0L;
            duration = 0L;
            lastSampleAt = Long.MIN_VALUE;
        }
    }
}
