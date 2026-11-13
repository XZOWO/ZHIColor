package com.naivety.zhicolor.bridge.render;

/** Allocation-free bounds for emphasis expansion inside the renderer's actual text geometry. */
public final class WordEmphasisGeometry {
    public static final float EMPHASIS_GLOW_RADIUS_DP = 7f;
    public static final float EMPHASIS_GLOW_ALPHA = 0.65f;

    private WordEmphasisGeometry() {
    }

    /**
     * Room is the minimum of host clipping space and any following punctuation gap.
     * For preceding text, the renderer supplies actual ink width as the expansion width.
     */
    public static float fitScale(float requestedScale, float cellWidth, float outwardRoom,
                                 boolean hasPrecedingText) {
        if (!Float.isFinite(requestedScale) || requestedScale < 1f
                || !Float.isFinite(cellWidth) || cellWidth <= 0f
                || !Float.isFinite(outwardRoom)) return 1f;
        double room = Math.max(0d, outwardRoom);
        double cap = 1d + (hasPrecedingText ? 1d : 2d) * room / cellWidth;
        double bounded = Math.min(requestedScale, cap);
        float result = (float) bounded;
        // Float rounding must never expand past the caller's available room.
        if ((double) result > bounded) result = Math.nextDown(result);
        double expansion = (result - 1d) * cellWidth * (hasPrecedingText ? 1d : 0.5d);
        if (expansion > room) result = Math.nextDown(result);
        return Math.max(1f, result);
    }

    /** A lone glyph stays centered, so each ink edge must fit its own clipping room. */
    public static float fitCenteredScale(float requestedScale, float leftExtent, float rightExtent,
                                         float leftRoom, float rightRoom) {
        if (!Float.isFinite(requestedScale) || requestedScale < 1f
                || !Float.isFinite(leftExtent) || leftExtent < 0f
                || !Float.isFinite(rightExtent) || rightExtent < 0f
                || !Float.isFinite(leftRoom) || !Float.isFinite(rightRoom)
                || (leftExtent == 0f && rightExtent == 0f)) return 1f;
        float scale = requestedScale;
        if (leftExtent > 0f) scale = fitScale(scale, leftExtent, leftRoom, true);
        if (rightExtent > 0f) scale = fitScale(scale, rightExtent, rightRoom, true);
        return scale;
    }

    /** Magnitude only: the renderer chooses outward direction from actual prefix geometry. */
    public static float outwardOffset(float scale, float cellWidth, boolean hasPrecedingText) {
        return outwardOffset(scale, cellWidth, 0f, hasPrecedingText);
    }

    /** Keeps the adjacent ink edge fixed, including positive or negative side bearings. */
    public static float outwardOffset(float scale, float cellWidth, float leftSideBearing,
                                       boolean hasPrecedingText) {
        if (!hasPrecedingText || !Float.isFinite(scale) || scale < 1f
                || !Float.isFinite(cellWidth) || cellWidth <= 0f
                || !Float.isFinite(leftSideBearing)) return 0f;
        double offset = ((double) scale - 1d) * (cellWidth * 0.5d - leftSideBearing);
        return offset >= -Float.MAX_VALUE && offset <= Float.MAX_VALUE ? (float) offset : 0f;
    }
}
