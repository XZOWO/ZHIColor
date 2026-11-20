package com.naivety.zhicolor.bridge.render;

/**
 * Pure-Java motion engine for the two-line wrapped lyric sliding window.
 *
 * <p>Long lyrics wrap into 3+ visual rows inside a fixed two-row viewport. While singing,
 * the window glides continuously downward row-by-row ({@link #MODE_CONTINUOUS_SCROLL}) so
 * any shared row physically travels between slots with solid opacity and zero double-vision,
 * while departing/entering edge rows dissolve softly before reaching the clip boundary.
 *
 * <p>When a lyric finishes singing and leaves active/exit highlight state, instead of
 * snapping abruptly back to {@code [0, 1]}, this controller smoothly returns the window to
 * {@code 0} — using continuous physical row scrolling for 1-to-2-row returns (3–4 wrapped
 * rows) and a calm masked directional glide-dissolve ({@link #MODE_CROSSFADE_GLIDE}) for
 * 3+-row jumps (5+ wrapped rows).
 */
public final class LyricWrappedWindowMotion {
    public static final int MODE_STATIC = 0;
    public static final int MODE_CONTINUOUS_SCROLL = 1;
    public static final int MODE_CROSSFADE_GLIDE = 2;
    public static final int MODE_LEGACY_CROSSFADE = 3;

    public static final long LEGACY_ANIMATION_MS = 220L;
    public static final float LEGACY_SLIDE_DP = 7f;

    /** Same-VSYNC-frame guard so a stale duplicate inactive holder cannot clobber the active window. */
    public static final long SAME_FRAME_ACTIVE_GUARD_MS = 4L;

    /** Rows left off-screen/unobserved beyond this gap reset quietly to [0, 1] when inactive. */
    public static final long MAX_OBSERVATION_GAP_MS = 1_000L;

    /** Maximum row span that uses continuous physical scrolling; wider jumps use soft glide-dissolve. */
    public static final int MAX_CONTINUOUS_SCROLL_ROWS = 2;

    /** Edge visibility threshold below which departing/entering rows are fully dissolved. */
    public static final float EDGE_FADE_MARGIN = 0.08f;

    /** Crossfade glide travel distance as a fraction of one visual row pitch. */
    public static final float CROSSFADE_TRAVEL_ROW_RATIO = 0.55f;

    // Forward advancement durations while actively singing (crisp, responsive).
    public static final long FORWARD_APPLE_MS = 300L;
    public static final long FORWARD_STANDARD_MS = 260L;
    public static final long FORWARD_REDUCED_MS = 180L;

    // Calm return durations after highlightExit finishes (~280-300ms), landing in unison
    // with the RecyclerView row scroll & fade settle (~620-680ms total).
    public static final long RETURN_ONE_ROW_APPLE_MS = 400L;
    public static final long RETURN_ONE_ROW_STANDARD_MS = 360L;
    public static final long RETURN_ONE_ROW_REDUCED_MS = 240L;

    public static final long RETURN_TWO_ROW_APPLE_MS = 480L;
    public static final long RETURN_TWO_ROW_STANDARD_MS = 440L;
    public static final long RETURN_TWO_ROW_REDUCED_MS = 280L;

    public static final long CROSSFADE_APPLE_MS = 420L;
    public static final long CROSSFADE_STANDARD_MS = 380L;
    public static final long CROSSFADE_REDUCED_MS = 260L;

    /** Reusable zero-allocation draw snapshot populated by {@link #sample}. */
    public static final class Sample {
        public int mode;
        public int currentStart;
        public int previousStart;
        public int visibleCount;
        public float continuousOffset;
        public float progress;
        public float outgoingAlpha;
        public float incomingAlpha;
        public boolean animating;

        Sample setStatic(int start, int visibleCount) {
            this.mode = MODE_STATIC;
            this.currentStart = start;
            this.previousStart = start;
            this.visibleCount = visibleCount;
            this.continuousOffset = start;
            this.progress = 1f;
            this.outgoingAlpha = 0f;
            this.incomingAlpha = 1f;
            this.animating = false;
            return this;
        }

        Sample setContinuous(float offset, int targetStart, int previousStart, int visibleCount, float progress) {
            this.mode = MODE_CONTINUOUS_SCROLL;
            this.currentStart = targetStart;
            this.previousStart = previousStart;
            this.visibleCount = visibleCount;
            this.continuousOffset = offset;
            this.progress = progress;
            this.outgoingAlpha = 1f - progress;
            this.incomingAlpha = progress;
            this.animating = true;
            return this;
        }

        Sample setCrossfade(
                int fromStart,
                int targetStart,
                int visibleCount,
                float easedProgress,
                float outgoingAlpha,
                float incomingAlpha) {
            this.mode = MODE_CROSSFADE_GLIDE;
            this.currentStart = targetStart;
            this.previousStart = fromStart;
            this.visibleCount = visibleCount;
            this.continuousOffset = targetStart;
            this.progress = easedProgress;
            this.outgoingAlpha = outgoingAlpha;
            this.incomingAlpha = incomingAlpha;
            this.animating = true;
            return this;
        }

        Sample setLegacyCrossfade(
                int fromStart,
                int targetStart,
                int visibleCount,
                float easedProgress) {
            this.mode = MODE_LEGACY_CROSSFADE;
            this.currentStart = targetStart;
            this.previousStart = fromStart;
            this.visibleCount = visibleCount;
            this.continuousOffset = targetStart;
            this.progress = easedProgress;
            this.outgoingAlpha = 1f - easedProgress;
            this.incomingAlpha = easedProgress;
            this.animating = true;
            return this;
        }
    }

    private boolean initialized;
    private int layoutKey = Integer.MIN_VALUE;
    private int targetStart;
    private int previousStart;
    private float continuousFromOffset;
    private float currentOffset;
    private boolean animating;
    private boolean crossfadeMode;
    private boolean useAppleSpring;
    private long startedAtMs;
    private long durationMs;
    private long lastObservedAtMs = Long.MIN_VALUE;
    private long lastActiveAtMs = Long.MIN_VALUE;

    /**
     * Evaluates and advances the sliding window state for one draw call.
     */
    public Sample sample(
            int layoutKey,
            int totalRows,
            int visibleLimit,
            int activeTargetStart,
            boolean activeLine,
            boolean animateAllowed,
            boolean appleMotion,
            boolean reducedMotion,
            boolean holdCurrentForWakeBlend,
            boolean immediateSnap,
            long nowMs,
            Sample out) {
        Sample result = out != null ? out : new Sample();
        int safeVisibleLimit = Math.max(0, visibleLimit);
        int visibleCount = Math.max(0, Math.min(totalRows, safeVisibleLimit));
        int maxStart = Math.max(0, totalRows - visibleCount);

        if (totalRows <= safeVisibleLimit || visibleCount <= 0) {
            snapInternal(layoutKey, 0, nowMs);
            return result.setStatic(0, visibleCount);
        }

        // Guard against a duplicate inactive RecyclerView holder drawing the same WordLine
        // in the same VSYNC frame as the active holder.
        if (!activeLine
                && initialized
                && this.layoutKey == layoutKey
                && lastActiveAtMs != Long.MIN_VALUE
                && nowMs >= lastActiveAtMs
                && (nowMs - lastActiveAtMs) <= SAME_FRAME_ACTIVE_GUARD_MS) {
            return evaluateCurrent(maxStart, visibleCount, nowMs, result);
        }

        if (activeLine) {
            lastActiveAtMs = nowMs;
        }

        int desiredTarget = activeLine ? clampStart(activeTargetStart, maxStart) : 0;

        // Retain the AOD window through the AOD-to-bright wake blend.
        if (holdCurrentForWakeBlend && initialized && this.layoutKey == layoutKey) {
            int held = clampStart(Math.round(evaluateOffsetAt(nowMs)), maxStart);
            lastObservedAtMs = nowMs;
            return result.setStatic(held, visibleCount);
        }

        boolean inactiveOffscreenGap = !activeLine
                && initialized
                && this.layoutKey == layoutKey
                && lastObservedAtMs != Long.MIN_VALUE
                && nowMs >= lastObservedAtMs
                && (nowMs - lastObservedAtMs) > MAX_OBSERVATION_GAP_MS;

        if (!initialized
                || this.layoutKey != layoutKey
                || !animateAllowed
                || immediateSnap
                || inactiveOffscreenGap) {
            snapInternal(layoutKey, desiredTarget, nowMs);
            return result.setStatic(desiredTarget, visibleCount);
        }

        // Advance any active transition up to nowMs before checking for retargeting.
        float nowRawProgress = rawProgressAt(nowMs);
        float nowOffset = evaluateOffsetAt(nowMs);
        if (animating && nowRawProgress >= 1f) {
            settleToTarget();
            nowOffset = targetStart;
            nowRawProgress = 1f;
        }

        if (desiredTarget != targetStart) {
            float fromOffset;
            if (animating && crossfadeMode) {
                fromOffset = nowRawProgress >= 0.5f ? targetStart : previousStart;
            } else {
                fromOffset = nowOffset;
            }
            float delta = Math.abs(desiredTarget - fromOffset);
            if (delta < 0.001f) {
                targetStart = desiredTarget;
                previousStart = desiredTarget;
                continuousFromOffset = desiredTarget;
                currentOffset = desiredTarget;
                animating = false;
                crossfadeMode = false;
                startedAtMs = nowMs;
                durationMs = 0L;
            } else {
                boolean useCrossfade = delta > (MAX_CONTINUOUS_SCROLL_ROWS + 0.05f);
                boolean returning = !activeLine || desiredTarget < fromOffset;
                long nextDuration = resolveDurationMs(
                        delta,
                        returning,
                        useCrossfade,
                        appleMotion,
                        reducedMotion);
                this.previousStart = clampStart(Math.round(fromOffset), maxStart);
                this.targetStart = desiredTarget;
                this.continuousFromOffset = fromOffset;
                this.currentOffset = fromOffset;
                this.crossfadeMode = useCrossfade;
                this.useAppleSpring = appleMotion;
                this.startedAtMs = nowMs;
                this.durationMs = nextDuration;
                this.animating = true;
            }
        }

        lastObservedAtMs = nowMs;
        return evaluateCurrent(maxStart, visibleCount, nowMs, result);
    }

    /**
     * Evaluates legacy two-line window switching when smooth long-line paging is disabled:
     * inactive lines show {@code [0, 1]} immediately without resetting an active holder, and
     * active lines use the original 220ms 7dp smoothStep crossfade.
     */
    public Sample sampleLegacy(
            int layoutKey,
            int totalRows,
            int visibleLimit,
            int activeTargetStart,
            boolean activeLine,
            boolean fullMotionAllowed,
            boolean holdCurrentForWakeBlend,
            long nowMs,
            Sample out) {
        Sample result = out != null ? out : new Sample();
        int safeVisibleLimit = Math.max(0, visibleLimit);
        int visibleCount = Math.max(0, Math.min(totalRows, safeVisibleLimit));
        int maxStart = Math.max(0, totalRows - visibleCount);

        if (totalRows <= safeVisibleLimit || visibleCount <= 0) {
            snapInternal(layoutKey, 0, nowMs);
            return result.setStatic(0, visibleCount);
        }
        if (!activeLine) {
            return result.setStatic(0, visibleCount);
        }
        int desiredTarget = clampStart(activeTargetStart, maxStart);
        if (!initialized || this.layoutKey != layoutKey) {
            snapInternal(layoutKey, desiredTarget, nowMs);
            return result.setStatic(desiredTarget, visibleCount);
        }
        if (!fullMotionAllowed) {
            snapInternal(layoutKey, desiredTarget, nowMs);
            return result.setStatic(desiredTarget, visibleCount);
        }
        if (holdCurrentForWakeBlend) {
            int held = clampStart(targetStart, maxStart);
            return result.setStatic(held, visibleCount);
        }
        if (targetStart != desiredTarget) {
            previousStart = clampStart(targetStart, maxStart);
            targetStart = desiredTarget;
            continuousFromOffset = previousStart;
            currentOffset = desiredTarget;
            startedAtMs = nowMs;
            durationMs = LEGACY_ANIMATION_MS;
            crossfadeMode = true;
            animating = previousStart != targetStart;
        }
        lastActiveAtMs = nowMs;
        lastObservedAtMs = nowMs;
        float rawProgress = rawProgressAt(nowMs);
        boolean stillAnimating = rawProgress < 1f && previousStart != targetStart;
        if (!stillAnimating) {
            settleToTarget();
            return result.setStatic(targetStart, visibleCount);
        }
        float eased = rawProgress * rawProgress * (3f - 2f * rawProgress);
        return result.setLegacyCrossfade(
                clampStart(previousStart, maxStart),
                clampStart(targetStart, maxStart),
                visibleCount,
                eased);
    }

    public boolean isAnimating(long nowMs) {
        if (!animating) {
            return false;
        }
        if (rawProgressAt(nowMs) >= 1f) {
            settleToTarget();
            return false;
        }
        return true;
    }

    public int currentStart() {
        return targetStart;
    }

    public int previousStart() {
        return animating ? previousStart : targetStart;
    }

    public long changedAtMs() {
        return startedAtMs;
    }

    public int layoutKey() {
        return layoutKey;
    }

    public void reset() {
        initialized = false;
        layoutKey = Integer.MIN_VALUE;
        targetStart = 0;
        previousStart = 0;
        continuousFromOffset = 0f;
        currentOffset = 0f;
        animating = false;
        crossfadeMode = false;
        useAppleSpring = false;
        startedAtMs = 0L;
        durationMs = 0L;
        lastObservedAtMs = Long.MIN_VALUE;
        lastActiveAtMs = Long.MIN_VALUE;
    }

    public void snap(int layoutKey, int start, long nowMs) {
        snapInternal(layoutKey, Math.max(0, start), nowMs);
    }

    public static Sample staticSample(int start, int visibleCount, Sample out) {
        Sample result = out != null ? out : new Sample();
        return result.setStatic(Math.max(0, start), Math.max(0, visibleCount));
    }

    /**
     * Per-row opacity during {@link #MODE_CONTINUOUS_SCROLL}.
     *
     * <p>Rows whose slot position {@code rowOffset = rowIndex - continuousOffset} lies inside
     * {@code [0, visibleCount - 1]} stay fully solid ({@code 1f}), eliminating double-ghosting.
     * Rows crossing the top ({@code (-1, 0)}) or bottom ({@code (visibleCount - 1, visibleCount)})
     * edge dissolve smoothly to {@code 0f} slightly before reaching the clip boundary.
     */
    public static float continuousRowAlpha(float rowOffset, int visibleCount) {
        if (visibleCount <= 0 || !Float.isFinite(rowOffset)) {
            return 0f;
        }
        float maxInnerSlot = visibleCount - 1f;
        if (rowOffset >= 0f && rowOffset <= maxInnerSlot) {
            return 1f;
        }
        float visibility = rowOffset < 0f
                ? (1f + rowOffset)
                : (visibleCount - rowOffset);
        if (visibility <= EDGE_FADE_MARGIN) {
            return 0f;
        }
        if (visibility >= 1f) {
            return 1f;
        }
        float u = (visibility - EDGE_FADE_MARGIN) / (1f - EDGE_FADE_MARGIN);
        return u * u * (3f - 2f * u);
    }

    /** Front-loaded fade-out alpha for {@link #MODE_CROSSFADE_GLIDE} departing rows. */
    public static float crossfadeOutgoingAlpha(float rawProgress) {
        float remaining = 1f - clampUnit(rawProgress);
        return (float) Math.pow(remaining, 1.5d);
    }

    /** Gently delayed smoothstep fade-in alpha for {@link #MODE_CROSSFADE_GLIDE} arriving rows. */
    public static float crossfadeIncomingAlpha(float rawProgress) {
        float u = clampUnit((clampUnit(rawProgress) - 0.12f) / 0.88f);
        return u * u * (3f - 2f * u);
    }

    /** Proportional vertical glide travel distance for {@link #MODE_CROSSFADE_GLIDE}. */
    public static float crossfadeTravelPx(float rowStepPx) {
        return Float.isFinite(rowStepPx) && rowStepPx > 0f
                ? rowStepPx * CROSSFADE_TRAVEL_ROW_RATIO
                : 0f;
    }

    public static long resolveDurationMs(
            float rowDelta,
            boolean returning,
            boolean crossfade,
            boolean appleMotion,
            boolean reducedMotion) {
        if (crossfade) {
            if (reducedMotion) return CROSSFADE_REDUCED_MS;
            return appleMotion ? CROSSFADE_APPLE_MS : CROSSFADE_STANDARD_MS;
        }
        if (!returning) {
            if (reducedMotion) return FORWARD_REDUCED_MS;
            return appleMotion ? FORWARD_APPLE_MS : FORWARD_STANDARD_MS;
        }
        if (rowDelta > 1.05f) {
            if (reducedMotion) return RETURN_TWO_ROW_REDUCED_MS;
            return appleMotion ? RETURN_TWO_ROW_APPLE_MS : RETURN_TWO_ROW_STANDARD_MS;
        }
        if (reducedMotion) return RETURN_ONE_ROW_REDUCED_MS;
        return appleMotion ? RETURN_ONE_ROW_APPLE_MS : RETURN_ONE_ROW_STANDARD_MS;
    }

    private void snapInternal(int layoutKey, int start, long nowMs) {
        this.initialized = true;
        this.layoutKey = layoutKey;
        this.targetStart = start;
        this.previousStart = start;
        this.continuousFromOffset = start;
        this.currentOffset = start;
        this.animating = false;
        this.crossfadeMode = false;
        this.startedAtMs = nowMs;
        this.durationMs = 0L;
        this.lastObservedAtMs = nowMs;
    }

    private void settleToTarget() {
        animating = false;
        crossfadeMode = false;
        previousStart = targetStart;
        continuousFromOffset = targetStart;
        currentOffset = targetStart;
    }

    private Sample evaluateCurrent(int maxStart, int visibleCount, long nowMs, Sample out) {
        int clampedTarget = clampStart(targetStart, maxStart);
        if (!animating) {
            targetStart = clampedTarget;
            previousStart = clampedTarget;
            continuousFromOffset = clampedTarget;
            currentOffset = clampedTarget;
            return out.setStatic(clampedTarget, visibleCount);
        }
        float rawProgress = rawProgressAt(nowMs);
        if (rawProgress >= 1f) {
            targetStart = clampedTarget;
            settleToTarget();
            return out.setStatic(clampedTarget, visibleCount);
        }
        float eased = useAppleSpring
                ? AppleLyricMotionPolicy.springValue(rawProgress)
                : ZhiToolLyricMotion.easeOutCubic(rawProgress);
        if (crossfadeMode) {
            return out.setCrossfade(
                    clampStart(previousStart, maxStart),
                    clampedTarget,
                    visibleCount,
                    eased,
                    crossfadeOutgoingAlpha(rawProgress),
                    crossfadeIncomingAlpha(rawProgress));
        }
        float offset = continuousFromOffset + (clampedTarget - continuousFromOffset) * eased;
        offset = Math.max(0f, Math.min(maxStart, offset));
        currentOffset = offset;
        return out.setContinuous(
                offset,
                clampedTarget,
                clampStart(previousStart, maxStart),
                visibleCount,
                eased);
    }

    private float rawProgressAt(long nowMs) {
        if (!animating || durationMs <= 0L) {
            return 1f;
        }
        long elapsed = nowMs - startedAtMs;
        if (elapsed <= 0L) {
            return 0f;
        }
        if (elapsed >= durationMs) {
            return 1f;
        }
        return elapsed / (float) durationMs;
    }

    private float evaluateOffsetAt(long nowMs) {
        if (!animating || crossfadeMode) {
            return currentOffset;
        }
        float rawProgress = rawProgressAt(nowMs);
        if (rawProgress >= 1f) {
            return targetStart;
        }
        float eased = useAppleSpring
                ? AppleLyricMotionPolicy.springValue(rawProgress)
                : ZhiToolLyricMotion.easeOutCubic(rawProgress);
        return continuousFromOffset + (targetStart - continuousFromOffset) * eased;
    }

    private static int clampStart(int start, int maxStart) {
        return Math.max(0, Math.min(start, maxStart));
    }

    private static float clampUnit(float value) {
        if (!Float.isFinite(value) || value <= 0f) return 0f;
        return Math.min(1f, value);
    }
}
