package com.naivety.zhicolor.bridge.render;

/** The adjacent two-line handoff used by ZHITool FullLyricView.updateScroll. */
public final class LyricOverlapPolicy {
    private LyricOverlapPolicy() {}

    public static boolean overlaps(int latestIndex, long previousEnd, long position,
            boolean previousEndProvided) {
        return latestIndex >= 1 && previousEndProvided && previousEnd > position;
    }

    public static int focusIndex(int latestIndex, long previousEnd, long position,
            boolean previousEndProvided) {
        return overlaps(latestIndex, previousEnd, position, previousEndProvided)
                ? latestIndex - 1 : latestIndex;
    }

    public static int overlapIndex(int latestIndex, long previousEnd, long position,
            boolean previousEndProvided) {
        return overlaps(latestIndex, previousEnd, position, previousEndProvided)
                ? latestIndex : -1;
    }
}
