package com.naivety.zhicolor.bridge.render;

/** Continuous line-level reveal in accumulated wrapped-text advance coordinates. */
public final class LineTimedProgress {
    private LineTimedProgress() {}

    public static float revealWidth(long position, long begin, long end,
                                    float totalWidth, float beforeWidth, float segmentWidth) {
        if (!Float.isFinite(totalWidth) || totalWidth <= 0f
                || !Float.isFinite(beforeWidth) || beforeWidth < 0f
                || !Float.isFinite(segmentWidth) || segmentWidth <= 0f
                || end <= begin || position <= begin) return 0f;
        double progress = position >= end ? 1d
                : ((double) position - begin) / ((double) end - begin);
        return (float) Math.max(0d, Math.min(segmentWidth, totalWidth * progress - beforeWidth));
    }
}
