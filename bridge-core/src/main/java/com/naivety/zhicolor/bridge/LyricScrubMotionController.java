package com.naivety.zhicolor.bridge;

import com.naivety.zhicolor.bridge.render.WordLine;
import com.naivety.zhicolor.bridge.render.WordLyricModel;
import com.naivety.zhicolor.bridge.render.ZhiToolLyricMotion;

/**
 * Apple Music style continuous smooth scrubbing scroll and animation handoff controller.
 *
 * <p>Pure-Java engine with zero Android dependencies:
 * <ul>
 *   <li>Maps scrubbed millisecond timestamps continuously across adapter rows (smoothly
 *       interpolating through lines, duets, and interlude breaks with no discrete stair-stepping).</li>
 *   <li>Maintains a self-calibrating virtual scroll coordinate map ({@link VirtualScrollMap}) that
 *       matches {@code LinearLayoutManager} decorated top/bottom margins (including Apple top inset)
 *       with zero drift as rows measure on screen.</li>
 *   <li>Drives continuous vertical motion via an analytical critically-damped spring during both
 *       active dragging ({@code DRAGGING}) and velocity-preserving release settle ({@code SETTLING}).</li>
 *   <li>Coordinates Apple Music {@code enterTrackingMode} / {@code leaveTrackingMode} blur clearing
 *       ({@code 200ms} out, {@code 330ms} in) and proximity-timed release handoff for secondary
 *       vocals, waiting dots, and focus scale.</li>
 * </ul>
 */
public final class LyricScrubMotionController {
    /** Fast, silky critically-damped tracking spring response while the finger is dragging. */
    public static final float DRAG_SPRING_RESPONSE_SECONDS = 0.20f;
    /** Smooth Apple Music settle spring response after releasing the progress bar. */
    public static final float SETTLE_APPLE_RESPONSE_SECONDS = 0.42f;
    public static final float SETTLE_STANDARD_RESPONSE_SECONDS = 0.36f;
    public static final float SETTLE_REDUCED_RESPONSE_SECONDS = 0.48f;

    /** Apple Music tracking-mode blur transition durations (enter clear, leave restore). */
    public static final long TRACKING_BLUR_EXIT_MS = 200L;
    public static final long TRACKING_BLUR_ENTER_MS = 330L;

    /** Distance/velocity thresholds for declaring settle complete. */
    public static final float SETTLE_POSITION_EPSILON_PX = 0.75f;
    public static final float SETTLE_VELOCITY_EPSILON_PX_PER_SEC = 18f;
    /** Safety upper bound on settle duration so a spring can never stall. */
    public static final long MAX_SETTLE_DURATION_MS = 950L;
    /** Proximity threshold (in row pitches) for releasing secondary/blur/scale handoff on swipe. */
    public static final float HANDOFF_PROXIMITY_ROWS = 1.8f;
    /** Upper delay before releasing handoff during an ultra-long seek settle. */
    public static final long HANDOFF_MAX_DELAY_MS = 320L;

    private static final double MIN_STEP_SECONDS = 0.001d;
    private static final double MAX_STEP_SECONDS = 0.050d;

    public enum MotionPhase {
        IDLE,
        DRAGGING,
        SETTLING
    }

    /**
     * Maps a continuous timestamp in milliseconds to a smooth, non-decreasing fractional adapter
     * row position across {@code model}. Waiting-dots rows are bridged continuously so sweeping
     * across an interlude glides steadily without dead zones or abrupt jumps.
     */
    public static double continuousAdapterPosition(WordLyricModel model, long positionMs) {
        if (model == null || model.lines.isEmpty()) {
            return 0d;
        }
        int adapterCount = !model.officialLines.isEmpty()
                ? model.officialLines.size()
                : model.lines.size();
        if (adapterCount <= 1) {
            return 0d;
        }
        int[] rows = new int[adapterCount];
        long[] times = new long[adapterCount];
        int count = collectTimelineKeyframes(model, adapterCount, true, rows, times);
        if (count == 0) {
            count = collectTimelineKeyframes(model, adapterCount, false, rows, times);
        }
        if (count <= 0) {
            return 0d;
        }
        if (count == 1 || positionMs <= times[0]) {
            return rows[0];
        }
        if (positionMs >= times[count - 1]) {
            return rows[count - 1];
        }
        // Find the last keyframe with time <= positionMs, then anchor segment start at the first
        // keyframe sharing that timestamp so simultaneous duet lines never divide by zero.
        int lastLe = 0;
        for (int i = 1; i < count; i++) {
            if (times[i] <= positionMs) {
                lastLe = i;
            } else {
                break;
            }
        }
        int segStart = lastLe;
        while (segStart > 0 && times[segStart - 1] == times[lastLe]) {
            segStart--;
        }
        int segEnd = lastLe + 1;
        while (segEnd < count && times[segEnd] <= times[segStart]) {
            segEnd++;
        }
        if (segEnd >= count) {
            return rows[count - 1];
        }
        long span = times[segEnd] - times[segStart];
        if (span <= 0L) {
            return rows[segStart];
        }
        double frac = Math.max(0d, Math.min(1d, (positionMs - times[segStart]) / (double) span));
        return rows[segStart] + (rows[segEnd] - rows[segStart]) * frac;
    }

    private static int collectTimelineKeyframes(
            WordLyricModel model,
            int adapterCount,
            boolean skipWaitingDots,
            int[] outRows,
            long[] outTimes) {
        int count = 0;
        long lastTime = Long.MIN_VALUE;
        for (int i = 0; i < adapterCount; i++) {
            WordLine line = model.lineAtAdapterIndex(i);
            if (line == null) {
                continue;
            }
            if (skipWaitingDots && line.isWaitingDots) {
                continue;
            }
            long t = line.timeMillis;
            if (count > 0 && t < lastTime) {
                t = lastTime;
            }
            outRows[count] = i;
            outTimes[count] = t;
            lastTime = t;
            count++;
        }
        return count;
    }

    public static float settleResponseSeconds(int motionMode) {
        if (motionMode == LyricUiConfig.MOTION_REDUCED) {
            return SETTLE_REDUCED_RESPONSE_SECONDS;
        }
        if (motionMode == LyricUiConfig.MOTION_STANDARD) {
            return SETTLE_STANDARD_RESPONSE_SECONDS;
        }
        return SETTLE_APPLE_RESPONSE_SECONDS;
    }

    /**
     * Exact analytical step of a critically-damped harmonic oscillator (unconditionally stable for
     * any positive {@code dtSeconds}, zero overshoot, preserves velocity across target changes).
     */
    public static void stepCriticallyDampedSpring(
            float currentPosition,
            float currentVelocity,
            float targetPosition,
            double dtSeconds,
            float responseSeconds,
            float[] outPosAndVel) {
        if (!Float.isFinite(currentPosition) || !Float.isFinite(targetPosition)) {
            outPosAndVel[0] = Float.isFinite(targetPosition) ? targetPosition : 0f;
            outPosAndVel[1] = 0f;
            return;
        }
        float safeVel = Float.isFinite(currentVelocity) ? currentVelocity : 0f;
        double dt = Math.max(MIN_STEP_SECONDS, Math.min(MAX_STEP_SECONDS, dtSeconds));
        double response = Math.max(0.05d, responseSeconds);
        double omega = (2d * Math.PI) / response;
        double x0 = currentPosition - targetPosition;
        double exp = Math.exp(-omega * dt);
        double b = safeVel + omega * x0;
        double nextX = exp * (x0 + b * dt);
        double nextV = exp * (safeVel - omega * b * dt);
        outPosAndVel[0] = (float) (targetPosition + nextX);
        outPosAndVel[1] = (float) nextV;
    }

    /**
     * Self-calibrating virtual scroll coordinate map aligned with {@code LinearLayoutManager}'s
     * decorated item layout ({@code decoratedTop = child.getTop() - topMargin}).
     */
    public static final class VirtualScrollMap {
        private final int rowCount;
        private final int[] pitches;
        private final boolean[] measured;
        private final boolean[] waitingDots;
        private final int[] topOffsets;
        private final boolean[] topOffsetKnown;
        private int defaultPitchPx;
        private int firstRowTopMarginPx;
        private int defaultTopOffsetPx;
        private long measuredNormalPitchSum;
        private int measuredNormalPitchCount;

        public VirtualScrollMap(
                int rowCount,
                boolean[] waitingDotsFlags,
                int defaultPitchPx,
                int firstRowTopMarginPx,
                int defaultTopOffsetPx) {
            this.rowCount = Math.max(0, rowCount);
            this.pitches = new int[this.rowCount];
            this.measured = new boolean[this.rowCount];
            this.waitingDots = new boolean[this.rowCount];
            if (waitingDotsFlags != null) {
                int copy = Math.min(this.rowCount, waitingDotsFlags.length);
                System.arraycopy(waitingDotsFlags, 0, this.waitingDots, 0, copy);
            }
            this.topOffsets = new int[this.rowCount];
            this.topOffsetKnown = new boolean[this.rowCount];
            this.defaultPitchPx = Math.max(1, defaultPitchPx);
            this.firstRowTopMarginPx = Math.max(0, firstRowTopMarginPx);
            this.defaultTopOffsetPx = defaultTopOffsetPx;
        }

        public int rowCount() {
            return rowCount;
        }

        public int estimatedNormalPitchPx() {
            if (measuredNormalPitchCount > 0) {
                return Math.max(1, (int) Math.round(
                        measuredNormalPitchSum / (double) measuredNormalPitchCount));
            }
            return defaultPitchPx;
        }

        public void updateLayoutConfig(int defaultPitchPx, int firstRowTopMarginPx, int defaultTopOffsetPx) {
            if (defaultPitchPx > 0) {
                this.defaultPitchPx = defaultPitchPx;
            }
            this.firstRowTopMarginPx = Math.max(0, firstRowTopMarginPx);
            this.defaultTopOffsetPx = defaultTopOffsetPx;
        }

        public void updateMeasuredRow(
                int row,
                int decoratedPitchPx,
                int topOffsetPx,
                boolean hasTopOffset) {
            if (row < 0 || row >= rowCount) {
                return;
            }
            int safePitch = Math.max(0, decoratedPitchPx);
            if (!waitingDots[row]) {
                int normalizedPitch = row == 0
                        ? Math.max(1, safePitch - firstRowTopMarginPx)
                        : Math.max(1, safePitch);
                if (measured[row]) {
                    int prevNormalized = row == 0
                            ? Math.max(1, pitches[row] - firstRowTopMarginPx)
                            : Math.max(1, pitches[row]);
                    measuredNormalPitchSum += (normalizedPitch - prevNormalized);
                } else {
                    measuredNormalPitchSum += normalizedPitch;
                    measuredNormalPitchCount++;
                }
            }
            pitches[row] = safePitch;
            measured[row] = true;
            if (hasTopOffset) {
                topOffsets[row] = topOffsetPx;
                topOffsetKnown[row] = true;
            }
        }

        public void updateTopOffset(int row, int topOffsetPx) {
            if (row < 0 || row >= rowCount) {
                return;
            }
            topOffsets[row] = topOffsetPx;
            topOffsetKnown[row] = true;
        }

        public int pitchOf(int row) {
            if (row < 0 || row >= rowCount) {
                return 0;
            }
            if (measured[row]) {
                return pitches[row];
            }
            if (waitingDots[row]) {
                return 0;
            }
            int base = estimatedNormalPitchPx();
            return row == 0 ? base + firstRowTopMarginPx : base;
        }

        public int topOffsetOf(int row) {
            if (row < 0 || row >= rowCount) {
                return defaultTopOffsetPx;
            }
            if (topOffsetKnown[row]) {
                return topOffsets[row];
            }
            if (row == 0 && firstRowTopMarginPx > 0) {
                return 0;
            }
            return defaultTopOffsetPx;
        }

        public float cumulativeStart(int row) {
            int clamped = Math.max(0, Math.min(rowCount, row));
            long sum = 0L;
            for (int i = 0; i < clamped; i++) {
                sum += pitchOf(i);
            }
            return (float) sum;
        }

        public float targetScrollYForRow(int row) {
            if (rowCount <= 0) {
                return 0f;
            }
            int clamped = Math.max(0, Math.min(rowCount - 1, row));
            return cumulativeStart(clamped) - topOffsetOf(clamped);
        }

        public float continuousTargetScrollY(double continuousRow) {
            if (rowCount <= 0) {
                return 0f;
            }
            double clamped = Math.max(0d, Math.min(rowCount - 1, continuousRow));
            int lo = (int) Math.floor(clamped);
            int hi = Math.min(rowCount - 1, lo + 1);
            float frac = (float) (clamped - lo);
            float yLo = targetScrollYForRow(lo);
            if (lo == hi || frac <= 0f) {
                return yLo;
            }
            float yHi = targetScrollYForRow(hi);
            return yLo + (yHi - yLo) * frac;
        }

        /** Converts a visible child's {@code decoratedTop - recycler.paddingTop} to virtual Y. */
        public float observedScrollY(int refRow, int decoratedTopMinusPaddingTop) {
            if (rowCount <= 0) {
                return 0f;
            }
            int clamped = Math.max(0, Math.min(rowCount - 1, refRow));
            return cumulativeStart(clamped) - decoratedTopMinusPaddingTop;
        }

        /**
         * Resolves {@code (anchorRow, offsetFromPaddingTop)} for {@code scrollToPositionWithOffset}
         * so the RecyclerView lands at the exact continuous {@code scrollY}.
         */
        public void computePositionAndOffset(float scrollY, int[] outRowAndOffset) {
            if (rowCount <= 0) {
                outRowAndOffset[0] = 0;
                outRowAndOffset[1] = 0;
                return;
            }
            float probeY = scrollY + Math.max(0, defaultTopOffsetPx);
            int chosen = 0;
            long cumulative = 0L;
            long chosenStart = 0L;
            for (int i = 0; i < rowCount; i++) {
                if (cumulative <= probeY) {
                    chosen = i;
                    chosenStart = cumulative;
                } else {
                    break;
                }
                cumulative += pitchOf(i);
            }
            outRowAndOffset[0] = chosen;
            outRowAndOffset[1] = Math.round(chosenStart - scrollY);
        }
    }

    /** Immutable output of a single VSYNC motion step for one RecyclerView session. */
    public static final class StepResult {
        public final MotionPhase phase;
        public final boolean active;
        public final float nextScrollY;
        public final float deltaScrollY;
        public final boolean holdSecondaryEffects;
        public final boolean justReleasedHandoff;
        public final boolean justFinishedSettle;
        public final int settleTargetRow;

        StepResult(
                MotionPhase phase,
                boolean active,
                float nextScrollY,
                float deltaScrollY,
                boolean holdSecondaryEffects,
                boolean justReleasedHandoff,
                boolean justFinishedSettle,
                int settleTargetRow) {
            this.phase = phase;
            this.active = active;
            this.nextScrollY = nextScrollY;
            this.deltaScrollY = deltaScrollY;
            this.holdSecondaryEffects = holdSecondaryEffects;
            this.justReleasedHandoff = justReleasedHandoff;
            this.justFinishedSettle = justFinishedSettle;
            this.settleTargetRow = settleTargetRow;
        }
    }

    /** Per-RecyclerView continuous scrub spring session. */
    public static final class ScrollSession {
        private final VirtualScrollMap map;
        private MotionPhase phase = MotionPhase.IDLE;
        private float positionY;
        private float velocityY;
        private float targetY;
        private float lastAppliedRecyclerY;
        private boolean hasLastAppliedRecyclerY;
        private boolean positionInitialized;
        private long lastStepTimeMs = -1L;
        private long settleStartedAtMs = -1L;
        private int settleTargetRow = -1;
        private boolean handoffReleased = true;
        private boolean hasDragTarget;
        private double dragContinuousRow;
        private float settleResponseSec = SETTLE_APPLE_RESPONSE_SECONDS;
        private boolean animateEnabled = true;
        private final float[] springScratch = new float[2];

        public ScrollSession(VirtualScrollMap map) {
            this.map = map;
        }

        public VirtualScrollMap map() {
            return map;
        }

        public MotionPhase phase() {
            return phase;
        }

        public boolean isActive() {
            return phase != MotionPhase.IDLE;
        }

        public boolean shouldHoldSecondaryEffects() {
            return phase == MotionPhase.DRAGGING
                    || (phase == MotionPhase.SETTLING && !handoffReleased);
        }

        public int settleTargetRow() {
            return settleTargetRow;
        }

        public float positionY() {
            return positionY;
        }

        public float velocityY() {
            return velocityY;
        }

        public void enterDragging(
                float observedScrollY,
                boolean hasObservedScrollY,
                long nowMs,
                boolean animateEnabled) {
            this.animateEnabled = animateEnabled;
            if (hasObservedScrollY) {
                if (!positionInitialized) {
                    positionY = observedScrollY;
                    targetY = observedScrollY;
                    velocityY = 0f;
                    positionInitialized = true;
                } else {
                    reconcileObservedShiftLocked(observedScrollY);
                }
                lastAppliedRecyclerY = observedScrollY;
                hasLastAppliedRecyclerY = true;
            }
            phase = MotionPhase.DRAGGING;
            handoffReleased = false;
            settleTargetRow = -1;
            settleStartedAtMs = -1L;
            lastStepTimeMs = nowMs;
        }

        public void updateDragTarget(double continuousRow, long nowMs) {
            dragContinuousRow = continuousRow;
            hasDragTarget = true;
            if (phase == MotionPhase.IDLE) {
                phase = MotionPhase.DRAGGING;
                handoffReleased = false;
                lastStepTimeMs = nowMs;
            }
            if (map.rowCount() > 0) {
                targetY = map.continuousTargetScrollY(continuousRow);
            }
        }

        public void beginSettle(
                int targetAdapterRow,
                long nowMs,
                int motionMode,
                boolean animateEnabled) {
            this.animateEnabled = animateEnabled;
            int clamped = map.rowCount() > 0
                    ? Math.max(0, Math.min(map.rowCount() - 1, targetAdapterRow))
                    : 0;
            settleTargetRow = clamped;
            settleResponseSec = settleResponseSeconds(motionMode);
            settleStartedAtMs = nowMs;
            if (lastStepTimeMs < 0L) {
                lastStepTimeMs = nowMs;
            }
            phase = MotionPhase.SETTLING;
            handoffReleased = false;
            targetY = map.targetScrollYForRow(clamped);
        }

        public void recordAppliedRecyclerY(float actualObservedScrollY, boolean clampedAtBoundary) {
            recordAppliedRecyclerY(Float.NaN, 0, actualObservedScrollY, clampedAtBoundary);
        }

        public void recordAppliedRecyclerY(
                float beforeObservedScrollY,
                int appliedDy,
                float afterObservedScrollY,
                boolean hitBoundary) {
            if (!Float.isFinite(afterObservedScrollY)) {
                return;
            }
            if (hitBoundary) {
                lastAppliedRecyclerY = afterObservedScrollY;
                hasLastAppliedRecyclerY = true;
                if (Math.abs(afterObservedScrollY - positionY) > 2f) {
                    positionY = afterObservedScrollY;
                    if ((targetY < positionY && velocityY < 0f)
                            || (targetY > positionY && velocityY > 0f)) {
                        velocityY = 0f;
                    }
                }
                return;
            }
            if (Float.isFinite(beforeObservedScrollY)) {
                // Absorb any virtual coordinate-frame shift caused by newly laid-out rows measuring
                // their true height during scrollBy, while preserving spring sub-pixel state & velocity.
                float coordShift = (afterObservedScrollY - beforeObservedScrollY) - appliedDy;
                if (Math.abs(coordShift) > 0.5f) {
                    positionY += coordShift;
                    targetY += coordShift;
                }
            }
            lastAppliedRecyclerY = afterObservedScrollY;
            hasLastAppliedRecyclerY = true;
        }

        public void cancel() {
            phase = MotionPhase.IDLE;
            velocityY = 0f;
            handoffReleased = true;
            hasDragTarget = false;
            hasLastAppliedRecyclerY = false;
            positionInitialized = false;
            settleTargetRow = -1;
            settleStartedAtMs = -1L;
            lastStepTimeMs = -1L;
        }

        private void reconcileObservedShiftLocked(float observedScrollY) {
            if (!Float.isFinite(observedScrollY)) {
                return;
            }
            if (hasLastAppliedRecyclerY) {
                // Preserve sub-pixel spring state while transparently absorbing any coordinate
                // shift caused by off-screen rows measuring their true wrapped heights.
                float shift = observedScrollY - lastAppliedRecyclerY;
                if (Math.abs(shift) > 0.5f) {
                    positionY += shift;
                }
                lastAppliedRecyclerY = observedScrollY;
            } else {
                positionY = observedScrollY;
                lastAppliedRecyclerY = observedScrollY;
                hasLastAppliedRecyclerY = true;
            }
        }

        public StepResult step(
                long nowMs,
                float observedScrollY,
                boolean hasObservedScrollY,
                boolean targetLaidOut) {
            if (phase == MotionPhase.IDLE) {
                return new StepResult(
                        MotionPhase.IDLE, false, positionY, 0f, false, false, false, settleTargetRow);
            }
            if (hasObservedScrollY) {
                if (!positionInitialized) {
                    positionY = observedScrollY;
                    velocityY = 0f;
                    lastAppliedRecyclerY = observedScrollY;
                    hasLastAppliedRecyclerY = true;
                    positionInitialized = true;
                } else {
                    reconcileObservedShiftLocked(observedScrollY);
                }
            }
            float referenceRecyclerY = hasObservedScrollY
                    ? observedScrollY
                    : (hasLastAppliedRecyclerY ? lastAppliedRecyclerY : positionY);
            if (phase == MotionPhase.DRAGGING) {
                targetY = hasDragTarget
                        ? map.continuousTargetScrollY(dragContinuousRow)
                        : positionY;
                if (!positionInitialized) {
                    positionY = targetY;
                    referenceRecyclerY = targetY;
                    lastAppliedRecyclerY = targetY;
                    hasLastAppliedRecyclerY = true;
                    positionInitialized = true;
                }
                double dt = lastStepTimeMs >= 0L && nowMs > lastStepTimeMs
                        ? (nowMs - lastStepTimeMs) / 1_000d
                        : 1d / 60d;
                lastStepTimeMs = nowMs;
                if (!animateEnabled) {
                    positionY = targetY;
                    velocityY = 0f;
                } else {
                    stepCriticallyDampedSpring(
                            positionY,
                            velocityY,
                            targetY,
                            dt,
                            DRAG_SPRING_RESPONSE_SECONDS,
                            springScratch);
                    positionY = springScratch[0];
                    velocityY = springScratch[1];
                }
                return new StepResult(
                        MotionPhase.DRAGGING,
                        true,
                        positionY,
                        positionY - referenceRecyclerY,
                        true,
                        false,
                        false,
                        -1);
            }

            // Phase == SETTLING
            targetY = map.targetScrollYForRow(settleTargetRow);
            if (!positionInitialized) {
                positionY = targetY;
                referenceRecyclerY = targetY;
                lastAppliedRecyclerY = targetY;
                hasLastAppliedRecyclerY = true;
                positionInitialized = true;
            }
            double dt = lastStepTimeMs >= 0L && nowMs > lastStepTimeMs
                    ? (nowMs - lastStepTimeMs) / 1_000d
                    : 1d / 60d;
            lastStepTimeMs = nowMs;
            boolean finished;
            if (!animateEnabled) {
                positionY = targetY;
                velocityY = 0f;
                finished = true;
            } else {
                stepCriticallyDampedSpring(
                        positionY,
                        velocityY,
                        targetY,
                        dt,
                        settleResponseSec,
                        springScratch);
                positionY = springScratch[0];
                velocityY = springScratch[1];
                long settleAge = settleStartedAtMs >= 0L && nowMs >= settleStartedAtMs
                        ? nowMs - settleStartedAtMs
                        : 0L;
                float remaining = Math.abs(targetY - positionY);
                finished = (remaining <= SETTLE_POSITION_EPSILON_PX
                        && Math.abs(velocityY) <= SETTLE_VELOCITY_EPSILON_PX_PER_SEC)
                        || settleAge >= MAX_SETTLE_DURATION_MS;
                if (finished) {
                    positionY = targetY;
                    velocityY = 0f;
                }
            }

            boolean justReleasedHandoff = false;
            if (!handoffReleased) {
                float remaining = Math.abs(targetY - positionY);
                float proximityPx = Math.max(
                        120f,
                        map.estimatedNormalPitchPx() * HANDOFF_PROXIMITY_ROWS);
                long settleAge = settleStartedAtMs >= 0L && nowMs >= settleStartedAtMs
                        ? nowMs - settleStartedAtMs
                        : 0L;
                if (finished
                        || (targetLaidOut && remaining <= proximityPx)
                        || remaining <= proximityPx * 0.65f
                        || settleAge >= HANDOFF_MAX_DELAY_MS) {
                    handoffReleased = true;
                    justReleasedHandoff = true;
                }
            }
            if (finished) {
                int completedRow = settleTargetRow;
                phase = MotionPhase.IDLE;
                hasDragTarget = false;
                return new StepResult(
                        MotionPhase.IDLE,
                        false,
                        positionY,
                        positionY - referenceRecyclerY,
                        false,
                        justReleasedHandoff,
                        true,
                        completedRow);
            }
            return new StepResult(
                    MotionPhase.SETTLING,
                    true,
                    positionY,
                    positionY - referenceRecyclerY,
                    !handoffReleased,
                    justReleasedHandoff,
                    false,
                    settleTargetRow);
        }
    }

    /**
     * Smoothly animates the Apple Music {@code enterTrackingMode} / {@code leaveTrackingMode}
     * inactive blur multiplier between {@code 1.0} (full blur) and {@code 0.0} (crisp clear).
     */
    public static final class TrackingBlurTransition {
        private float startValue = 1f;
        private float targetValue = 1f;
        private long startedAtMs = -1L;
        private long durationMs;

        public synchronized void enterTracking(long nowMs, boolean animate) {
            retargetLocked(0f, nowMs, TRACKING_BLUR_EXIT_MS, animate);
        }

        public synchronized void leaveTracking(long nowMs, boolean animate) {
            retargetLocked(1f, nowMs, TRACKING_BLUR_ENTER_MS, animate);
        }

        public synchronized void reset() {
            startValue = 1f;
            targetValue = 1f;
            startedAtMs = -1L;
            durationMs = 0L;
        }

        public synchronized float sample(long nowMs) {
            if (startedAtMs < 0L || durationMs <= 0L) {
                return targetValue;
            }
            long elapsed = Math.max(0L, nowMs - startedAtMs);
            if (elapsed >= durationMs) {
                startValue = targetValue;
                startedAtMs = -1L;
                durationMs = 0L;
                return targetValue;
            }
            float progress = elapsed / (float) durationMs;
            float eased = ZhiToolLyricMotion.easeOutCubic(progress);
            return Math.max(0f, Math.min(1f, startValue + (targetValue - startValue) * eased));
        }

        public synchronized boolean isAnimating(long nowMs) {
            if (startedAtMs < 0L || durationMs <= 0L) {
                return false;
            }
            if (nowMs >= startedAtMs + durationMs) {
                startValue = targetValue;
                startedAtMs = -1L;
                durationMs = 0L;
                return false;
            }
            return true;
        }

        public synchronized float target() {
            return targetValue;
        }

        private void retargetLocked(float nextTarget, long nowMs, long nextDurationMs, boolean animate) {
            float current = sample(nowMs);
            if (!animate || nextDurationMs <= 0L || Math.abs(nextTarget - current) <= 0.001f) {
                startValue = nextTarget;
                targetValue = nextTarget;
                startedAtMs = -1L;
                durationMs = 0L;
                return;
            }
            startValue = current;
            targetValue = nextTarget;
            startedAtMs = nowMs;
            durationMs = nextDurationMs;
        }
    }
}
