package com.naivety.zhicolor.bridge;

/**
 * Computes active lyric anchor and viewport padding for system-centered vs top-anchored modes.
 */
final class LyricPositionPolicy {
    static final float APPLE_TOP_INSET_RATIO = 0.12f;
    static final float APPLE_MIN_TOP_INSET_DP = 54f;

    private LyricPositionPolicy() {}

    static int appleTopInsetPx(int recyclerHeightPx, int minTopInsetPx) {
        if (recyclerHeightPx <= 0) {
            return 0;
        }
        int clampedMin = Math.max(0, minTopInsetPx);
        int proportional = Math.round(recyclerHeightPx * APPLE_TOP_INSET_RATIO);
        return Math.min(recyclerHeightPx / 3, Math.max(clampedMin, proportional));
    }

    static int appleBottomInsetPx(
            int recyclerHeightPx,
            int topInsetPx,
            int minLastRowHeightPx,
            int originalBottomPaddingPx) {
        int safeOriginal = Math.max(0, originalBottomPaddingPx);
        if (recyclerHeightPx <= 0) {
            return safeOriginal;
        }
        int targetBottom = Math.max(
                0,
                recyclerHeightPx - Math.max(0, topInsetPx) - Math.max(0, minLastRowHeightPx));
        return Math.max(safeOriginal, targetBottom);
    }

    static int desiredCenterPx(
            boolean applePosition,
            int recyclerHeightPx,
            int systemCenterOffsetPx,
            int appleTopInsetPx,
            float mainAnchorCenterInItem) {
        if (recyclerHeightPx <= 0) {
            return 0;
        }
        if (!applePosition) {
            return recyclerHeightPx / 2 + systemCenterOffsetPx;
        }
        float safeAnchor = Float.isFinite(mainAnchorCenterInItem) && mainAnchorCenterInItem > 0f
                ? mainAnchorCenterInItem
                : 0f;
        return Math.max(0, appleTopInsetPx) + Math.round(safeAnchor);
    }

    static int scrollerTopOffsetPx(
            boolean applePosition,
            int targetIndex,
            int desiredCenterPx,
            int paddingTopPx,
            float mainAnchorCenterInItem) {
        if (applePosition && targetIndex == 0) {
            // Item 0 uses topMargin = appleTopInsetPx, so decoratedTop - topMargin == 0 at the top anchor.
            return 0;
        }
        float safeAnchor = Float.isFinite(mainAnchorCenterInItem) && mainAnchorCenterInItem > 0f
                ? mainAnchorCenterInItem
                : 0f;
        int offset = Math.round(desiredCenterPx - paddingTopPx - safeAnchor);
        return applePosition ? Math.max(0, offset) : offset;
    }
}
