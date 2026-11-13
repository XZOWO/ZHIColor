package com.naivety.zhicolor.bridge.render;

import java.util.ArrayList;
import java.util.Arrays;

/** Renderer-only grapheme triggers for line progress; never modifies source word timings. */
public final class LineProgressLiftMotion {
    public final SyllableLiftMotion motion;

    private final long begin, end;
    private final int textSizeKey, segmentCount;
    private final Object typeface;
    private final int[] segmentStarts, segmentEnds;
    private final float[] segmentWidths;
    private final boolean layoutShapeValid;

    public LineProgressLiftMotion(long begin, long end, int textSizeKey, Object typeface,
                                  int[] segmentStarts, int[] segmentEnds, float[] segmentWidths,
                                  int segmentCount, int[] graphemeBoundaries, float[] flowStarts,
                                  float totalWidth) {
        this.begin = begin;
        this.end = end;
        this.textSizeKey = textSizeKey;
        this.typeface = typeface;
        this.segmentCount = segmentCount;
        layoutShapeValid = segmentCount > 0 && segmentStarts != null && segmentEnds != null
                && segmentWidths != null && segmentStarts.length >= segmentCount
                && segmentEnds.length >= segmentCount && segmentWidths.length >= segmentCount;
        this.segmentStarts = layoutShapeValid ? Arrays.copyOf(segmentStarts, segmentCount) : new int[0];
        this.segmentEnds = layoutShapeValid ? Arrays.copyOf(segmentEnds, segmentCount) : new int[0];
        this.segmentWidths = layoutShapeValid ? Arrays.copyOf(segmentWidths, segmentCount) : new float[0];
        ArrayList<WordRange> visualRanges = new ArrayList<>();
        if (validLayout() && end > begin && Float.isFinite(totalWidth) && totalWidth > 0f
                && graphemeBoundaries != null && graphemeBoundaries.length > 1
                && flowStarts != null && flowStarts.length >= graphemeBoundaries.length - 1) {
            double duration = (double) end - begin;
            int previousEnd = -1;
            float previousFlow = -1f;
            for (int i = 0; i + 1 < graphemeBoundaries.length; i++) {
                int start = graphemeBoundaries[i], limit = graphemeBoundaries[i + 1];
                float flow = flowStarts[i];
                if (start < 0 || limit <= start || start < previousEnd
                        || !Float.isFinite(flow) || flow < 0f || flow > totalWidth
                        || flow < previousFlow) continue;
                double fraction = i == 0 ? 0d : flow / (double) totalWidth;
                // Ceil avoids firing before the continuous highlighter reaches this cell.
                long trigger = (long) Math.ceil((double) begin + duration * fraction);
                trigger = Math.max(begin, Math.min(end, trigger));
                visualRanges.add(new WordRange(trigger, start, limit));
                previousEnd = limit;
                previousFlow = flow;
            }
        }
        motion = new SyllableLiftMotion(visualRanges);
    }

    /** Exact active layout snapshot comparison; caller-owned spare capacity is ignored. */
    public boolean matches(long begin, long end, int textSizeKey, Object typeface,
                           int[] segmentStarts, int[] segmentEnds, float[] segmentWidths,
                           int segmentCount) {
        if (!layoutShapeValid || this.begin != begin || this.end != end
                || this.textSizeKey != textSizeKey || this.typeface != typeface
                || this.segmentCount != segmentCount || segmentStarts == null
                || segmentEnds == null || segmentWidths == null
                || segmentStarts.length < segmentCount || segmentEnds.length < segmentCount
                || segmentWidths.length < segmentCount) return false;
        for (int i = 0; i < segmentCount; i++) {
            if (this.segmentStarts[i] != segmentStarts[i] || this.segmentEnds[i] != segmentEnds[i]
                    || Float.floatToIntBits(this.segmentWidths[i])
                    != Float.floatToIntBits(segmentWidths[i])) return false;
        }
        return true;
    }

    private boolean validLayout() {
        if (!layoutShapeValid) return false;
        int previousEnd = -1;
        boolean positiveWidth = false;
        for (int i = 0; i < segmentCount; i++) {
            if (segmentStarts[i] < 0 || segmentEnds[i] <= segmentStarts[i]
                    || segmentStarts[i] < previousEnd || !Float.isFinite(segmentWidths[i])
                    || segmentWidths[i] < 0f) return false;
            previousEnd = segmentEnds[i];
            positiveWidth |= segmentWidths[i] > 0f;
        }
        return positiveWidth;
    }
}
