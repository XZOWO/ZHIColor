package com.naivety.zhicolor.bridge.render;

import java.util.ArrayList;
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
    private static final long FALLBACK_HAN_CHAR_STAGGER_MS = 150L;

    private final long[] starts;
    private final boolean[] valid, moving;
    private final double[] values, velocities, origins, originVelocities, targets;
    private final long[] beganAt;
    private final int[] characterStarts, characterEnds, characterRanges;
    private final int characterSegmentCount;
    private boolean initialized, running;
    private long lastPosition, lastNow;

    public SyllableLiftMotion(List<WordRange> ranges) {
        this(null, ranges, -1L);
    }

    public SyllableLiftMotion(String text, List<WordRange> ranges, long lineEndTimeMillis) {
        List<WordRange> effectiveRanges = expandRangesForLift(text, ranges, lineEndTimeMillis);
        int count = effectiveRanges == null ? 0 : effectiveRanges.size();
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
            WordRange range = effectiveRanges.get(i);
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

    /**
     * Keeps Western/Latin word/syllable ranges intact while splitting Chinese (Han)
     * multi-character ranges or identical-timestamp Han runs into per-character triggers
     * so Chinese characters always lift one by one.
     */
    static List<WordRange> expandRangesForLift(
            String text, List<WordRange> ranges, long lineEndTimeMillis) {
        if (text == null || text.isEmpty() || ranges == null || ranges.isEmpty()) {
            return ranges;
        }
        int textLength = text.length();
        int count = ranges.size();
        boolean anyHan = false;
        for (int i = 0; i < count; i++) {
            WordRange range = ranges.get(i);
            if (range != null && range.start >= 0 && range.end > range.start
                    && range.end <= textLength && containsHan(text, range.start, range.end)) {
                anyHan = true;
                break;
            }
        }
        if (!anyHan) {
            return ranges;
        }
        ArrayList<WordRange> expanded = new ArrayList<>(count);
        int i = 0;
        while (i < count) {
            WordRange range = ranges.get(i);
            if (range == null || range.start < 0 || range.end <= range.start
                    || range.end > textLength || !containsHan(text, range.start, range.end)) {
                expanded.add(range);
                i++;
                continue;
            }
            // Coalesce consecutive contiguous Han-containing ranges that share the exact same
            // start timestamp so grouped same-timestamp Chinese glyphs still lift one by one.
            int runEndIndex = i;
            int combinedEnd = range.end;
            long maxExplicitEnd = range.endTimeMillis;
            while (runEndIndex + 1 < count) {
                WordRange next = ranges.get(runEndIndex + 1);
                if (next == null || next.timeMillis != range.timeMillis
                        || next.start < combinedEnd || next.end <= next.start
                        || next.end > textLength
                        || !containsOnlyWhitespaceBetween(text, combinedEnd, next.start)
                        || !containsHan(text, next.start, next.end)) {
                    break;
                }
                combinedEnd = next.end;
                if (next.endTimeMillis > maxExplicitEnd) {
                    maxExplicitEnd = next.endTimeMillis;
                }
                runEndIndex++;
            }
            int[] unitBounds = partitionHanAwareLiftUnits(text, range.start, combinedEnd);
            int unitCount = unitBounds.length / 2;
            if (unitCount <= 1) {
                for (int k = i; k <= runEndIndex; k++) {
                    expanded.add(ranges.get(k));
                }
                i = runEndIndex + 1;
                continue;
            }
            long startMs = range.timeMillis;
            long spanEndMs = maxExplicitEnd > startMs ? maxExplicitEnd : -1L;
            if (spanEndMs <= startMs) {
                for (int nextIdx = runEndIndex + 1; nextIdx < count; nextIdx++) {
                    WordRange following = ranges.get(nextIdx);
                    if (following != null && following.timeMillis > startMs) {
                        spanEndMs = following.timeMillis;
                        break;
                    }
                }
            }
            if (spanEndMs <= startMs && lineEndTimeMillis > startMs) {
                spanEndMs = lineEndTimeMillis;
            }
            if (spanEndMs <= startMs) {
                spanEndMs = startMs + (long) unitCount * FALLBACK_HAN_CHAR_STAGGER_MS;
            }
            double duration = (double) (spanEndMs - startMs);
            for (int u = 0; u < unitCount; u++) {
                int uStart = unitBounds[u * 2];
                int uEnd = unitBounds[u * 2 + 1];
                long triggerMs = u == 0
                        ? startMs
                        : Math.min(spanEndMs, (long) Math.ceil(startMs + duration * u / unitCount));
                long nextTriggerMs = u + 1 < unitCount
                        ? Math.min(spanEndMs, (long) Math.ceil(startMs + duration * (u + 1) / unitCount))
                        : spanEndMs;
                expanded.add(new WordRange(triggerMs, uStart, uEnd, Math.max(triggerMs, nextTriggerMs)));
            }
            i = runEndIndex + 1;
        }
        return expanded;
    }

    private static int[] partitionHanAwareLiftUnits(String text, int start, int end) {
        int[] boundaries = CharLiftGeometry.graphemeBoundaries(text, start, end);
        if (boundaries.length < 2) {
            return new int[]{start, end};
        }
        int graphemeCount = boundaries.length - 1;
        int[] unitStarts = new int[graphemeCount];
        int[] unitEnds = new int[graphemeCount];
        boolean[] unitIsWestern = new boolean[graphemeCount];
        int unitCount = 0;
        int pendingLeadingStart = -1;
        for (int g = 0; g < graphemeCount; g++) {
            int gStart = boundaries[g];
            int gEnd = boundaries[g + 1];
            if (isWhitespaceOnly(text, gStart, gEnd)) {
                pendingLeadingStart = -1;
                continue;
            }
            if (containsHan(text, gStart, gEnd)) {
                int resolvedStart = pendingLeadingStart >= 0 ? pendingLeadingStart : gStart;
                pendingLeadingStart = -1;
                unitStarts[unitCount] = resolvedStart;
                unitEnds[unitCount] = gEnd;
                unitIsWestern[unitCount] = false;
                unitCount++;
            } else if (isLeadingPunctuation(text, gStart, gEnd)) {
                if (pendingLeadingStart < 0) {
                    pendingLeadingStart = gStart;
                }
            } else if (isTrailingPunctuation(text, gStart, gEnd)) {
                if (unitCount > 0) {
                    unitEnds[unitCount - 1] = gEnd;
                } else {
                    int resolvedStart = pendingLeadingStart >= 0 ? pendingLeadingStart : gStart;
                    pendingLeadingStart = -1;
                    unitStarts[unitCount] = resolvedStart;
                    unitEnds[unitCount] = gEnd;
                    unitIsWestern[unitCount] = false;
                    unitCount++;
                }
            } else {
                // Contiguous non-Han word/syllable letters stay grouped as one Western unit.
                if (unitCount > 0
                        && unitIsWestern[unitCount - 1]
                        && unitEnds[unitCount - 1] == gStart
                        && pendingLeadingStart < 0) {
                    unitEnds[unitCount - 1] = gEnd;
                } else {
                    int resolvedStart = pendingLeadingStart >= 0 ? pendingLeadingStart : gStart;
                    pendingLeadingStart = -1;
                    unitStarts[unitCount] = resolvedStart;
                    unitEnds[unitCount] = gEnd;
                    unitIsWestern[unitCount] = true;
                    unitCount++;
                }
            }
        }
        if (pendingLeadingStart >= 0) {
            if (unitCount > 0) {
                unitEnds[unitCount - 1] = end;
            } else {
                unitStarts[unitCount] = pendingLeadingStart;
                unitEnds[unitCount] = end;
                unitCount++;
            }
        }
        int[] packed = new int[unitCount * 2];
        for (int u = 0; u < unitCount; u++) {
            packed[u * 2] = unitStarts[u];
            packed[u * 2 + 1] = unitEnds[u];
        }
        return packed;
    }

    private static boolean containsHan(String text, int start, int end) {
        for (int offset = start; offset < end; ) {
            int codePoint = text.codePointAt(offset);
            if (Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN) {
                return true;
            }
            offset += Character.charCount(codePoint);
        }
        return false;
    }

    private static boolean containsOnlyWhitespaceBetween(String text, int start, int end) {
        return start >= end || isWhitespaceOnly(text, start, end);
    }

    private static boolean isWhitespaceOnly(String text, int start, int end) {
        for (int offset = start; offset < end; ) {
            int codePoint = text.codePointAt(offset);
            if (!Character.isWhitespace(codePoint) && !Character.isSpaceChar(codePoint)) {
                return false;
            }
            offset += Character.charCount(codePoint);
        }
        return true;
    }

    private static boolean isLeadingPunctuation(String text, int start, int end) {
        boolean any = false;
        for (int offset = start; offset < end; ) {
            int codePoint = text.codePointAt(offset);
            int type = Character.getType(codePoint);
            if (type != Character.START_PUNCTUATION && type != Character.INITIAL_QUOTE_PUNCTUATION) {
                return false;
            }
            any = true;
            offset += Character.charCount(codePoint);
        }
        return any;
    }

    private static boolean isTrailingPunctuation(String text, int start, int end) {
        boolean any = false;
        for (int offset = start; offset < end; ) {
            int codePoint = text.codePointAt(offset);
            int type = Character.getType(codePoint);
            if (type != Character.END_PUNCTUATION
                    && type != Character.FINAL_QUOTE_PUNCTUATION
                    && type != Character.OTHER_PUNCTUATION
                    && type != Character.DASH_PUNCTUATION
                    && type != Character.CONNECTOR_PUNCTUATION) {
                return false;
            }
            any = true;
            offset += Character.charCount(codePoint);
        }
        return any;
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
