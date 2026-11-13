package com.naivety.zhicolor.bridge.render;

import java.util.Arrays;
import java.util.List;

/** Source-syllable start events driving independent, interruptible UI-clock springs. */
public final class SyllableLiftMotion {
    public static final float LIFT_DP = 2f;
    private static final double DECAY = 3.5d; // damping / (2 * mass), m=1, k=14, c=7
    private static final double FREQUENCY = Math.sqrt(14d - DECAY * DECAY);
    private static final double REST_EPSILON = 0.001d;
    private static final long MAX_OBSERVATION_GAP_MS = 1_000L;
    private static final long POSITION_JUMP_TOLERANCE_MS = 500L;

    private final long[] starts;
    private final boolean[] valid, moving;
    private final double[] values, velocities, origins, originVelocities, targets;
    private final long[] beganAt;
    private final int[] characterStarts, characterEnds, characterRanges;
    private final int characterSegmentCount;
    private boolean initialized, running;
    private long lastPosition, lastNow;

    public SyllableLiftMotion(List<WordRange> ranges) {
        int count = ranges == null ? 0 : ranges.size();
        starts = new long[count];
        valid = new boolean[count];
        moving = new boolean[count];
        values = new double[count];
        velocities = new double[count];
        origins = new double[count];
        originVelocities = new double[count];
        targets = new double[count];
        beganAt = new long[count];
        int[] sourceStarts = new int[count], sourceEnds = new int[count];
        int[] boundaries = new int[count * 2];
        int boundaryCount = 0;
        for (int i = 0; i < count; i++) {
            WordRange range = ranges.get(i);
            if (range == null || range.start < 0 || range.end <= range.start) continue;
            valid[i] = true;
            starts[i] = range.timeMillis;
            sourceStarts[i] = range.start;
            sourceEnds[i] = range.end;
            boundaries[boundaryCount++] = range.start;
            boundaries[boundaryCount++] = range.end;
        }
        Arrays.sort(boundaries, 0, boundaryCount);
        characterStarts = new int[boundaryCount];
        characterEnds = new int[boundaryCount];
        characterRanges = new int[boundaryCount];
        int segments = 0;
        // Build disjoint lookup intervals once. Malformed overlaps retain the first source range.
        for (int at = 0; at + 1 < boundaryCount; at++) {
            int left = boundaries[at], right = boundaries[at + 1];
            if (right <= left) continue;
            int source = -1;
            for (int i = 0; i < count; i++) {
                if (valid[i] && sourceStarts[i] <= left && sourceEnds[i] > left) {
                    source = i;
                    break;
                }
            }
            if (source < 0) continue;
            if (segments > 0 && characterRanges[segments - 1] == source
                    && characterEnds[segments - 1] == left) {
                characterEnds[segments - 1] = right;
            } else {
                characterStarts[segments] = left;
                characterEnds[segments] = right;
                characterRanges[segments++] = source;
            }
        }
        characterSegmentCount = segments;
    }

    /** First observation and discontinuities settle immediately; ordinary retargets keep velocity. */
    public void sample(long position, long now, boolean selected, boolean animate) {
        boolean continuous = initialized && now >= lastNow;
        long elapsed = continuous ? now - lastNow : 0L;
        if (elapsed < 0L || elapsed > MAX_OBSERVATION_GAP_MS) continuous = false;
        if (continuous) {
            double positionDelta = (double) position - lastPosition;
            continuous = positionDelta >= -POSITION_JUMP_TOLERANCE_MS
                    && positionDelta <= Math.max(POSITION_JUMP_TOLERANCE_MS, elapsed + 160d);
        }
        running = false;
        for (int i = 0; i < starts.length; i++) {
            double target = valid[i] && selected && position >= starts[i] ? 1d : 0d;
            if (!animate || !continuous) {
                settle(i, target, now);
                continue;
            }
            advance(i, now);
            if (targets[i] != target) {
                origins[i] = values[i];
                originVelocities[i] = velocities[i];
                targets[i] = target;
                beganAt[i] = now;
                moving[i] = values[i] != target || velocities[i] != 0d;
            }
            running |= moving[i];
        }
        initialized = true;
        lastPosition = position;
        lastNow = now;
    }

    private void advance(int index, long now) {
        if (!moving[index]) return;
        double seconds = Math.max(0d, ((double) now - beganAt[index]) / 1_000d);
        double relative = origins[index] - targets[index];
        double sineCoefficient = (originVelocities[index] + DECAY * relative) / FREQUENCY;
        double phase = FREQUENCY * seconds;
        double cosine = Math.cos(phase), sine = Math.sin(phase), envelope = Math.exp(-DECAY * seconds);
        double displacement = relative * cosine + sineCoefficient * sine;
        values[index] = targets[index] + envelope * displacement;
        velocities[index] = envelope * ((-relative * FREQUENCY * sine
                + sineCoefficient * FREQUENCY * cosine) - DECAY * displacement);
        if (Math.abs(values[index] - targets[index]) <= REST_EPSILON
                && Math.abs(velocities[index]) <= REST_EPSILON) {
            settle(index, targets[index], now);
        }
    }

    private void settle(int index, double target, long now) {
        values[index] = origins[index] = targets[index] = target;
        velocities[index] = originVelocities[index] = 0d;
        beganAt[index] = now;
        moving[index] = false;
    }

    /** UTF-16 character index; uncovered characters never inherit another syllable's lift. */
    public float amountForCharacter(int index) {
        int low = 0, high = characterSegmentCount - 1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            if (index < characterStarts[middle]) high = middle - 1;
            else if (index >= characterEnds[middle]) low = middle + 1;
            else return amountForRange(characterRanges[middle]);
        }
        return 0f;
    }

    /** Original source-list index, suitable for a renderer's cached character mapping. */
    public float amountForRange(int index) {
        return index >= 0 && index < values.length && valid[index] ? (float) values[index] : 0f;
    }

    public boolean isRunning() { return running; }

    public void reset() {
        for (int i = 0; i < starts.length; i++) settle(i, 0d, 0L);
        initialized = running = false;
        lastPosition = lastNow = 0L;
    }
}
