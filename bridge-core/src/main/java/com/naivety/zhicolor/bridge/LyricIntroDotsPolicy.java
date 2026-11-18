package com.naivety.zhicolor.bridge;

import com.naivety.zhicolor.bridge.render.WordLine;
import com.naivety.zhicolor.bridge.render.WordLyricModel;

/**
 * Compatibility facade over {@link LyricWaitingDotsPolicy} for intro-dot queries.
 */
public final class LyricIntroDotsPolicy {
    public static final long MIN_INTRO_MILLIS = LyricWaitingDotsPolicy.MIN_GAP_MILLIS;
    public static final float MAX_SCALE = LyricWaitingDotsPolicy.MAX_SCALE;

    private LyricIntroDotsPolicy() {}

    public static WordLine firstLyricLine(WordLyricModel model) {
        if (model == null) return null;
        for (WordLine line : model.lines) {
            if (LyricWaitingDotsPolicy.isSingableLine(line)) return line;
        }
        return null;
    }

    public static long beginMillis(WordLine firstLine) {
        return LyricWaitingDotsPolicy.beginMillis(firstLine);
    }

    public static WordLine visibleLine(WordLyricModel model, long positionMillis) {
        LyricWaitingDotsPolicy.BreakWindow active =
                LyricWaitingDotsPolicy.activeBreak(model, positionMillis);
        return active != null && active.intro ? active.hostLine : null;
    }

    /** Resolve the actual bound main text without accepting an earlier placeholder slot. */
    public static WordLine boundFirstLyricLine(WordLyricModel model, WordLine indexedLine,
            String normalizedText, long positionMillis) {
        return LyricWaitingDotsPolicy.boundIntroLine(model, indexedLine, normalizedText, positionMillis);
    }

    public static boolean isVisible(long firstBeginMillis, long positionMillis) {
        return hasIntro(firstBeginMillis)
                && positionMillis >= 0L && positionMillis < firstBeginMillis;
    }

    public static boolean hasIntro(long firstBeginMillis) {
        return firstBeginMillis > MIN_INTRO_MILLIS;
    }

    public static boolean shouldAnimate(long firstBeginMillis, long positionMillis,
            boolean playbackAdvancing, boolean motionEnabled) {
        return playbackAdvancing && motionEnabled && isVisible(firstBeginMillis, positionMillis);
    }

    public static float rowHeight(float mainTextSize, float lineSpacing) {
        return LyricWaitingDotsPolicy.rowHeight(mainTextSize, lineSpacing);
    }

    public static float boundedTextSize(float mainTextSize, float availableWidth) {
        return LyricWaitingDotsPolicy.boundedTextSize(mainTextSize, availableWidth);
    }
}
