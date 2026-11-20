package com.naivety.zhicolor.bridge.render;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Tracks per-row visual baselines for Apple-style staggered line transitions so every visible
 * row animates strictly proportionally to its own true screen-space displacement (including
 * scroll, expanding/collapsing secondary vocals, waiting dots, and scroll-boundary clamping)
 * while preserving continuous position across mid-flight interruptions.
 */
public final class AppleLyricCascadeTracker {
    public static final long SAME_TARGET_DEBOUNCE_MS = 120L;
    private static final float START_DIRECTION_DEADZONE_PX = 0.5f;
    private static final float START_CARRY_DEADZONE_PX = 0.5f;
    private static final float TAIL_SNAP_EPSILON_PX = 0.05f;

    private static final class RowAnchor {
        final float startTopPx;
        final float carryTranslationYPx;

        RowAnchor(float startTopPx, float carryTranslationYPx) {
            this.startTopPx = startTopPx;
            this.carryTranslationYPx = carryTranslationYPx;
        }
    }

    private final Map<Integer, RowAnchor> rowAnchors = new HashMap<>();
    private boolean active;
    private int targetPosition = -1;
    private long startTimeMillis;
    private long durationMillis = AppleLyricMotionPolicy.SCROLL_MS;
    private float dampingRatio = AppleLyricMotionPolicy.DEFAULT_DAMPING_RATIO;
    private float responseSeconds = AppleLyricMotionPolicy.DEFAULT_RESPONSE_SECONDS;
    private float directionDeltaPx;

    public boolean isActive() {
        return active;
    }

    public int targetPosition() {
        return targetPosition;
    }

    public long startTimeMillis() {
        return startTimeMillis;
    }

    public void reset() {
        active = false;
        targetPosition = -1;
        startTimeMillis = 0L;
        durationMillis = AppleLyricMotionPolicy.SCROLL_MS;
        dampingRatio = AppleLyricMotionPolicy.DEFAULT_DAMPING_RATIO;
        responseSeconds = AppleLyricMotionPolicy.DEFAULT_RESPONSE_SECONDS;
        directionDeltaPx = 0f;
        rowAnchors.clear();
    }

    /**
     * Begins (or smoothly retargets) a per-row cascade transition using a snapshot of every
     * currently visible child's layout top and current translationY.
     *
     * @param targetPosition       adapter position being scrolled to
     * @param directionDeltaPx     positive for forward scroll (content moving up), negative for
     *                             reverse scroll (content moving down); governs stagger order only
     * @param startTimeMillis      animation clock timestamp
     * @param durationMillis       scroll duration matching the RecyclerView interpolator
     * @param dampingRatio         spring damping ratio matching the RecyclerView interpolator
     * @param responseSeconds      spring response seconds matching the RecyclerView interpolator
     * @param visiblePositions     adapter positions of visible children
     * @param visibleTopsPx        current layout top (getTop()) of each visible child
     * @param visibleCarryTyPx     current translationY (getTranslationY()) of each visible child
     * @param visibleCount         number of valid entries in the visible* arrays
     * @return true if the cascade is active after this call
     */
    public boolean start(
            int targetPosition,
            float directionDeltaPx,
            long startTimeMillis,
            long durationMillis,
            float dampingRatio,
            float responseSeconds,
            int[] visiblePositions,
            float[] visibleTopsPx,
            float[] visibleCarryTyPx,
            int visibleCount) {
        if (durationMillis <= 0L
                || visiblePositions == null
                || visibleTopsPx == null
                || visibleCarryTyPx == null
                || visibleCount <= 0) {
            reset();
            return false;
        }
        int safeCount = Math.min(
                visibleCount,
                Math.min(visiblePositions.length, Math.min(visibleTopsPx.length, visibleCarryTyPx.length)));
        if (safeCount <= 0) {
            reset();
            return false;
        }
        float safeDirection = Float.isFinite(directionDeltaPx) ? directionDeltaPx : 0f;
        boolean hasCarry = false;
        for (int i = 0; i < safeCount; i++) {
            float carry = visibleCarryTyPx[i];
            if (visiblePositions[i] >= 0 && Float.isFinite(carry) && Math.abs(carry) >= START_CARRY_DEADZONE_PX) {
                hasCarry = true;
                break;
            }
        }
        if (Math.abs(safeDirection) < START_DIRECTION_DEADZONE_PX && !hasCarry) {
            if (!active) {
                return false;
            }
            // Keep an already running cascade untouched on a zero-delta duplicate callback.
            return true;
        }
        if (active
                && this.targetPosition == targetPosition
                && startTimeMillis >= this.startTimeMillis
                && startTimeMillis - this.startTimeMillis < SAME_TARGET_DEBOUNCE_MS) {
            return true;
        }
        rowAnchors.clear();
        for (int i = 0; i < safeCount; i++) {
            int pos = visiblePositions[i];
            if (pos < 0) {
                continue;
            }
            float top = Float.isFinite(visibleTopsPx[i]) ? visibleTopsPx[i] : 0f;
            float carry = Float.isFinite(visibleCarryTyPx[i]) ? visibleCarryTyPx[i] : 0f;
            rowAnchors.put(pos, new RowAnchor(top, carry));
        }
        if (rowAnchors.isEmpty()) {
            reset();
            return false;
        }
        this.targetPosition = targetPosition;
        this.startTimeMillis = startTimeMillis;
        this.durationMillis = durationMillis;
        this.dampingRatio = dampingRatio;
        this.responseSeconds = responseSeconds;
        this.directionDeltaPx = safeDirection;
        this.active = true;
        return true;
    }

    /**
     * Samples per-row translationY offsets for all currently visible children at nowMillis.
     *
     * @return true if the cascade is still active and needs subsequent frames; false once settled
     */
    public boolean sample(
            long nowMillis,
            int[] visiblePositions,
            float[] visibleTopsPx,
            int visibleCount,
            float[] outTranslationsYPx) {
        int safeCount = (visiblePositions == null || visibleTopsPx == null || outTranslationsYPx == null)
                ? 0
                : Math.min(
                        Math.max(0, visibleCount),
                        Math.min(visiblePositions.length, Math.min(visibleTopsPx.length, outTranslationsYPx.length)));
        if (!active || safeCount <= 0) {
            if (outTranslationsYPx != null && safeCount > 0) {
                Arrays.fill(outTranslationsYPx, 0, safeCount, 0f);
            }
            if (!active) {
                return false;
            }
            reset();
            return false;
        }
        long elapsed = Math.max(0L, nowMillis - startTimeMillis);
        long maxStepDelay = directionDeltaPx >= 0f
                ? AppleLyricMotionPolicy.LINE_DELAY_MS
                : AppleLyricMotionPolicy.REVERSE_LINE_DELAY_MS;
        long maxWindow = durationMillis + AppleLyricMotionPolicy.MAX_CASCADE_STEPS * maxStepDelay;
        if (elapsed >= maxWindow) {
            Arrays.fill(outTranslationsYPx, 0, safeCount, 0f);
            reset();
            return false;
        }

        int lastVisiblePos = -1;
        for (int i = 0; i < safeCount; i++) {
            int pos = visiblePositions[i];
            if (pos > lastVisiblePos) {
                lastVisiblePos = pos;
            }
        }

        // Pass 1: infer virtual startTopPx for any row that scrolled into view after t = 0
        // from its nearest already-tracked same-side neighbor, so entering rows never pop or
        // inherit an active target row's pre-expansion delta.
        for (int i = 0; i < safeCount; i++) {
            int pos = visiblePositions[i];
            if (pos < 0 || rowAnchors.containsKey(pos)) {
                continue;
            }
            float currentTop = Float.isFinite(visibleTopsPx[i]) ? visibleTopsPx[i] : 0f;
            float neighborDelta = findSameSideTrackedNeighborDelta(
                    pos, visiblePositions, visibleTopsPx, safeCount);
            float inferredStartTop = currentTop - neighborDelta;
            rowAnchors.put(pos, new RowAnchor(inferredStartTop, 0f));
        }

        // Pass 2: compute each visible row's exact translationY from its own actual displacement.
        boolean anyMoved = false;
        long maxDelay = 0L;
        for (int i = 0; i < safeCount; i++) {
            int pos = visiblePositions[i];
            RowAnchor anchor = pos >= 0 ? rowAnchors.get(pos) : null;
            if (anchor == null) {
                outTranslationsYPx[i] = 0f;
                continue;
            }
            long delay = AppleLyricMotionPolicy.cascadeDelayMillis(
                    pos, targetPosition, lastVisiblePos, directionDeltaPx);
            if (delay > maxDelay) {
                maxDelay = delay;
            }
            float currentTop = Float.isFinite(visibleTopsPx[i]) ? visibleTopsPx[i] : anchor.startTopPx;
            float actualDeltaY = currentTop - anchor.startTopPx;
            float ty = AppleLyricMotionPolicy.cascadeRowTranslationY(
                    actualDeltaY,
                    anchor.carryTranslationYPx,
                    elapsed,
                    durationMillis,
                    delay,
                    dampingRatio,
                    responseSeconds);
            if (elapsed >= durationMillis && Math.abs(ty) < TAIL_SNAP_EPSILON_PX) {
                ty = 0f;
            }
            outTranslationsYPx[i] = ty;
            if (ty != 0f) {
                anyMoved = true;
            }
        }

        if (!anyMoved && elapsed >= durationMillis + maxDelay) {
            reset();
            return false;
        }
        return true;
    }

    private float findSameSideTrackedNeighborDelta(
            int pos,
            int[] visiblePositions,
            float[] visibleTopsPx,
            int count) {
        int bestPos = -1;
        int bestDistance = Integer.MAX_VALUE;
        float bestDelta = 0f;
        boolean belowTarget = pos > targetPosition;
        boolean aboveTarget = pos < targetPosition;
        for (int j = 0; j < count; j++) {
            int candidatePos = visiblePositions[j];
            if (candidatePos < 0 || candidatePos == pos) {
                continue;
            }
            // Only inherit from strictly same-side neighbors so an entering row below targetPos
            // already sees targetPos's secondary-vocal / translation height expansion reflected
            // in its neighbor's delta.
            if (belowTarget && candidatePos <= targetPosition) {
                continue;
            }
            if (aboveTarget && candidatePos >= targetPosition) {
                continue;
            }
            RowAnchor anchor = rowAnchors.get(candidatePos);
            if (anchor == null) {
                continue;
            }
            int distance = Math.abs(candidatePos - pos);
            if (distance < bestDistance || (distance == bestDistance && candidatePos > bestPos)) {
                float candidateTop = Float.isFinite(visibleTopsPx[j])
                        ? visibleTopsPx[j]
                        : anchor.startTopPx;
                bestDistance = distance;
                bestPos = candidatePos;
                bestDelta = candidateTop - anchor.startTopPx;
            }
        }
        return bestPos >= 0 && Float.isFinite(bestDelta) ? bestDelta : 0f;
    }
}
