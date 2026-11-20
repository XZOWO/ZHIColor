package com.naivety.zhicolor.bridge.render;

/**
 * Pure-Java motion engine for long single-line translation horizontal marquee scrolling
 * and post-singing return transitions.
 *
 * <p>While singing ({@code activeLine == true}), the translation tracks the normalized
 * active marquee progress ({@code 0..1}). When the lyric finishes singing and leaves
 * {@code highlightExit}, instead of snapping abruptly back to {@code 0}, this controller
 * smoothly returns the translation to its leading edge:
 * <ul>
 *   <li>Moderate overflow ({@code overflow <= availableWidth * MAX_GLIDE_OVERFLOW_RATIO}):
 *       continuous horizontal glide ({@link #MODE_SMOOTH_GLIDE}) using Apple spring or
 *       cubic ease-out, timed to land alongside the row's upward scroll &amp; fade settle.</li>
 *   <li>Ultra-wide overflow ({@code overflow > availableWidth * MAX_GLIDE_OVERFLOW_RATIO}):
 *       calm masked directional glide-dissolve ({@link #MODE_CROSSFADE_GLIDE}) so very long
 *       translations never zip across the viewport at distracting speed.</li>
 * </ul>
 */
public final class LyricTranslationMarqueeMotion {
    public static final int MODE_STATIC = 0;
    public static final int MODE_SMOOTH_GLIDE = 1;
    public static final int MODE_CROSSFADE_GLIDE = 2;

    /** Same-VSYNC-frame guard so a duplicate inactive holder cannot clobber the active marquee. */
    public static final long SAME_FRAME_ACTIVE_GUARD_MS = 4L;

    /** Rows left off-screen/unobserved beyond this gap snap quietly to 0 when inactive. */
    public static final long MAX_OBSERVATION_GAP_MS = 1_000L;

    /** Maximum overflow-to-viewport ratio using direct horizontal glide; wider uses glide-dissolve. */
    public static final float MAX_GLIDE_OVERFLOW_RATIO = 1.25f;

    /** Crossfade glide travel distance cap as a fraction of overflow / available width. */
    public static final float CROSSFADE_TRAVEL_OVERFLOW_RATIO = 0.25f;
    public static final float CROSSFADE_MAX_TRAVEL_WIDTH_RATIO = 0.14f;

    public static final long GLIDE_MIN_APPLE_MS = 400L;
    public static final long GLIDE_MAX_APPLE_MS = 540L;
    public static final long GLIDE_MIN_STANDARD_MS = 360L;
    public static final long GLIDE_MAX_STANDARD_MS = 480L;
    public static final long GLIDE_MIN_REDUCED_MS = 240L;
    public static final long GLIDE_MAX_REDUCED_MS = 320L;

    public static final long CROSSFADE_APPLE_MS = 420L;
    public static final long CROSSFADE_STANDARD_MS = 380L;
    public static final long CROSSFADE_REDUCED_MS = 260L;

    private static final float SETTLE_EPSILON = 0.001f;

    /** Reusable zero-allocation draw snapshot populated by {@link #sample}. */
    public static final class Sample {
        public int mode;
        public float scrollProgress;
        public float fromProgress;
        public float progress;
        public float outgoingAlpha;
        public float incomingAlpha;
        public boolean animating;

        Sample setStatic(float scrollProgress) {
            this.mode = MODE_STATIC;
            this.scrollProgress = scrollProgress;
            this.fromProgress = scrollProgress;
            this.progress = 1f;
            this.outgoingAlpha = 0f;
            this.incomingAlpha = 1f;
            this.animating = false;
            return this;
        }

        Sample setSmoothGlide(float scrollProgress, float fromProgress, float easedProgress) {
            this.mode = MODE_SMOOTH_GLIDE;
            this.scrollProgress = scrollProgress;
            this.fromProgress = fromProgress;
            this.progress = easedProgress;
            this.outgoingAlpha = 0f;
            this.incomingAlpha = 1f;
            this.animating = true;
            return this;
        }

        Sample setCrossfade(
                float fromProgress,
                float easedProgress,
                float outgoingAlpha,
                float incomingAlpha) {
            this.mode = MODE_CROSSFADE_GLIDE;
            this.scrollProgress = 0f;
            this.fromProgress = fromProgress;
            this.progress = easedProgress;
            this.outgoingAlpha = outgoingAlpha;
            this.incomingAlpha = incomingAlpha;
            this.animating = true;
            return this;
        }
    }

    private boolean initialized;
    private float currentProgress;
    private float fromProgress;
    private float targetProgress;
    private boolean returning;
    private boolean crossfadeMode;
    private boolean useAppleSpring;
    private long startedAtMs;
    private long durationMs;
    private long lastObservedAtMs = Long.MIN_VALUE;
    private long lastActiveAtMs = Long.MIN_VALUE;

    /**
     * Samples the marquee offset and optional smooth return animation for one draw frame.
     */
    public Sample sample(
            float activeTargetProgress,
            float overflowPx,
            float availableWidthPx,
            boolean activeLine,
            boolean animateAllowed,
            boolean appleMotion,
            boolean reducedMotion,
            boolean immediateSnap,
            long nowMs,
            Sample out) {
        Sample result = out != null ? out : new Sample();
        if (!Float.isFinite(overflowPx) || overflowPx <= 0.5f) {
            snapInternal(0f, nowMs);
            return result.setStatic(0f);
        }

        // Guard against a duplicate inactive holder drawing the same WordLine in the same frame.
        if (!activeLine
                && initialized
                && lastActiveAtMs != Long.MIN_VALUE
                && nowMs >= lastActiveAtMs
                && (nowMs - lastActiveAtMs) <= SAME_FRAME_ACTIVE_GUARD_MS) {
            return evaluateCurrent(nowMs, result);
        }

        float clampedActive = clampUnit(activeTargetProgress);
        if (activeLine) {
            lastActiveAtMs = nowMs;
        }

        boolean inactiveOffscreenGap = !activeLine
                && initialized
                && lastObservedAtMs != Long.MIN_VALUE
                && nowMs >= lastObservedAtMs
                && (nowMs - lastObservedAtMs) > MAX_OBSERVATION_GAP_MS;

        if (!animateAllowed || immediateSnap || inactiveOffscreenGap) {
            float snapped = activeLine ? clampedActive : 0f;
            snapInternal(snapped, nowMs);
            return result.setStatic(snapped);
        }

        if (!initialized) {
            float initial = activeLine ? clampedActive : 0f;
            snapInternal(initial, nowMs);
            return result.setStatic(initial);
        }

        if (activeLine) {
            snapInternal(clampedActive, nowMs);
            return result.setStatic(clampedActive);
        }

        // Inactive line: smoothly return from currentProgress -> 0 instead of hard-cutting.
        if (returning) {
            lastObservedAtMs = nowMs;
            return evaluateCurrent(nowMs, result);
        }

        if (currentProgress <= SETTLE_EPSILON) {
            snapInternal(0f, nowMs);
            return result.setStatic(0f);
        }

        float effectiveOverflow = overflowPx * currentProgress;
        boolean useCrossfade = Float.isFinite(availableWidthPx)
                && availableWidthPx > 1f
                && effectiveOverflow > availableWidthPx * MAX_GLIDE_OVERFLOW_RATIO;
        this.fromProgress = currentProgress;
        this.targetProgress = 0f;
        this.returning = true;
        this.crossfadeMode = useCrossfade;
        this.useAppleSpring = appleMotion;
        this.startedAtMs = nowMs;
        this.durationMs = resolveReturnDurationMs(
                effectiveOverflow,
                availableWidthPx,
                useCrossfade,
                appleMotion,
                reducedMotion);
        this.lastObservedAtMs = nowMs;
        return evaluateCurrent(nowMs, result);
    }

    /** Legacy behavior when smooth translation scroll optimization is disabled. */
    public Sample sampleLegacy(
            float activeTargetProgress,
            float overflowPx,
            boolean activeLine,
            boolean animateAllowed,
            long nowMs,
            Sample out) {
        Sample result = out != null ? out : new Sample();
        if (!animateAllowed || !activeLine || !Float.isFinite(overflowPx) || overflowPx <= 0.5f) {
            return result.setStatic(0f);
        }
        float clamped = clampUnit(activeTargetProgress);
        snapInternal(clamped, nowMs);
        lastActiveAtMs = nowMs;
        return result.setStatic(clamped);
    }

    public boolean isAnimating(long nowMs) {
        if (!returning) {
            return false;
        }
        if (rawProgressAt(nowMs) >= 1f) {
            settleToTarget();
            return false;
        }
        return true;
    }

    public void reset() {
        initialized = false;
        currentProgress = 0f;
        fromProgress = 0f;
        targetProgress = 0f;
        returning = false;
        crossfadeMode = false;
        useAppleSpring = false;
        startedAtMs = 0L;
        durationMs = 0L;
        lastObservedAtMs = Long.MIN_VALUE;
        lastActiveAtMs = Long.MIN_VALUE;
    }

    public static float crossfadeTravelPx(float overflowPx, float availableWidthPx) {
        if (!Float.isFinite(overflowPx) || overflowPx <= 0f) {
            return 0f;
        }
        float proportional = overflowPx * CROSSFADE_TRAVEL_OVERFLOW_RATIO;
        if (Float.isFinite(availableWidthPx) && availableWidthPx > 0f) {
            return Math.min(proportional, availableWidthPx * CROSSFADE_MAX_TRAVEL_WIDTH_RATIO);
        }
        return proportional;
    }

    public static long resolveReturnDurationMs(
            float effectiveOverflowPx,
            float availableWidthPx,
            boolean crossfade,
            boolean appleMotion,
            boolean reducedMotion) {
        if (crossfade) {
            if (reducedMotion) return CROSSFADE_REDUCED_MS;
            return appleMotion ? CROSSFADE_APPLE_MS : CROSSFADE_STANDARD_MS;
        }
        float ratio = (Float.isFinite(availableWidthPx) && availableWidthPx > 1f)
                ? clampUnit(effectiveOverflowPx / (availableWidthPx * MAX_GLIDE_OVERFLOW_RATIO))
                : 0.5f;
        long minMs = reducedMotion ? GLIDE_MIN_REDUCED_MS
                : (appleMotion ? GLIDE_MIN_APPLE_MS : GLIDE_MIN_STANDARD_MS);
        long maxMs = reducedMotion ? GLIDE_MAX_REDUCED_MS
                : (appleMotion ? GLIDE_MAX_APPLE_MS : GLIDE_MAX_STANDARD_MS);
        return Math.round(minMs + (maxMs - minMs) * ratio);
    }

    private void snapInternal(float value, long nowMs) {
        initialized = true;
        currentProgress = value;
        fromProgress = value;
        targetProgress = value;
        returning = false;
        crossfadeMode = false;
        startedAtMs = nowMs;
        durationMs = 0L;
        lastObservedAtMs = nowMs;
    }

    private void settleToTarget() {
        returning = false;
        crossfadeMode = false;
        currentProgress = targetProgress;
        fromProgress = targetProgress;
    }

    private Sample evaluateCurrent(long nowMs, Sample out) {
        if (!returning) {
            return out.setStatic(currentProgress);
        }
        float raw = rawProgressAt(nowMs);
        if (raw >= 1f) {
            settleToTarget();
            return out.setStatic(targetProgress);
        }
        float eased = useAppleSpring
                ? AppleLyricMotionPolicy.springValue(raw)
                : ZhiToolLyricMotion.easeOutCubic(raw);
        if (crossfadeMode) {
            return out.setCrossfade(
                    fromProgress,
                    eased,
                    LyricWrappedWindowMotion.crossfadeOutgoingAlpha(raw),
                    LyricWrappedWindowMotion.crossfadeIncomingAlpha(raw));
        }
        float next = clampUnit(fromProgress + (targetProgress - fromProgress) * eased);
        currentProgress = next;
        return out.setSmoothGlide(next, fromProgress, eased);
    }

    private float rawProgressAt(long nowMs) {
        if (!returning || durationMs <= 0L) {
            return 1f;
        }
        long elapsed = nowMs - startedAtMs;
        if (elapsed <= 0L) return 0f;
        if (elapsed >= durationMs) return 1f;
        return elapsed / (float) durationMs;
    }

    private static float clampUnit(float value) {
        if (!Float.isFinite(value) || value <= 0f) return 0f;
        return Math.min(1f, value);
    }
}
