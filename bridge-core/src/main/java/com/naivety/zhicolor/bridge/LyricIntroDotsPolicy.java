package com.naivety.zhicolor.bridge;

import com.naivety.zhicolor.bridge.render.LyricTimingMode;
import com.naivety.zhicolor.bridge.render.WordLine;
import com.naivety.zhicolor.bridge.render.WordLyricModel;
import com.naivety.zhicolor.bridge.render.WordLyricRenderSupport;
import com.naivety.zhicolor.bridge.render.WordRange;

/** Playback-position driven intro dots, following FullLyricView's intro timing. */
public final class LyricIntroDotsPolicy {
    public static final long MIN_INTRO_MILLIS = 1_200L;
    public static final long EXIT_LEAD_MILLIS = 500L;
    public static final double PULSE_PERIOD_MILLIS = 2_400.0;
    public static final float RADIUS_EM = 0.14f;
    public static final float SPACING_EM = 0.45f;
    public static final float MAX_SCALE = 1.2f;

    private LyricIntroDotsPolicy() {}

    public static WordLine firstLyricLine(WordLyricModel model) {
        if (model == null) return null;
        for (WordLine line : model.lines) {
            if (isLyricLine(line)) return line;
        }
        return null;
    }

    private static boolean isLyricLine(WordLine line) {
        if (line == null || line.timeMillis < 0L || line.normalizedText.isEmpty()
                || LyricMetadataFilter.isNonLyricInfoLine(line.text, line.timeMillis)) return false;
        // Empty spacer rows and punctuation/music-note placeholders do not begin singing.
        for (int i = 0; i < line.normalizedText.length();) {
            int codePoint = line.normalizedText.codePointAt(i);
            if (Character.isLetterOrDigit(codePoint)) return true;
            i += Character.charCount(codePoint);
        }
        return false;
    }

    public static long beginMillis(WordLine firstLine) {
        if (firstLine == null) return -1L;
        if (firstLine.timingMode == LyricTimingMode.WORD_TIMED && firstLine.words != null) {
            for (WordRange word : firstLine.words) {
                if (word != null && word.timeMillis >= 0L && word.end > word.start
                        && word.start >= 0 && word.end <= firstLine.text.length()
                        && hasVisibleWord(firstLine.text, word.start, word.end)) {
                    return word.timeMillis;
                }
            }
        }
        return firstLine.timeMillis;
    }

    private static boolean hasVisibleWord(String text, int start, int end) {
        String word = LyricTextSanitizer.removeIgnorableCharacters(text.substring(start, end));
        for (int i = 0; i < word.length();) {
            int codePoint = word.codePointAt(i);
            if (!Character.isWhitespace(codePoint) && !Character.isSpaceChar(codePoint)) return true;
            i += Character.charCount(codePoint);
        }
        return false;
    }

    public static WordLine visibleLine(WordLyricModel model, long positionMillis) {
        WordLine first = firstLyricLine(model);
        return isVisible(beginMillis(first), positionMillis) ? first : null;
    }

    /** Resolve the actual bound main text without accepting an earlier placeholder slot. */
    public static WordLine boundFirstLyricLine(WordLyricModel model, WordLine indexedLine,
            String normalizedText, long positionMillis) {
        WordLine first = visibleLine(model, positionMillis);
        if (first == null || !WordLyricRenderSupport.matchesWordLineText(first, normalizedText)) return null;
        if (indexedLine != null && indexedLine != first && isLyricLine(indexedLine)) return null;
        if (indexedLine != first && model.hasDuplicateRenderableText(normalizedText)) return null;
        return first;
    }

    public static boolean isVisible(long firstBeginMillis, long positionMillis) {
        return hasIntro(firstBeginMillis)
                && positionMillis >= 0L && positionMillis < firstBeginMillis;
    }

    public static boolean hasIntro(long firstBeginMillis) {
        return firstBeginMillis >= MIN_INTRO_MILLIS;
    }

    public static boolean shouldAnimate(long firstBeginMillis, long positionMillis,
            boolean playbackAdvancing, boolean motionEnabled) {
        return playbackAdvancing && motionEnabled && isVisible(firstBeginMillis, positionMillis);
    }

    public static float exitAmount(long firstBeginMillis, long positionMillis, boolean motionEnabled) {
        if (!isVisible(firstBeginMillis, positionMillis)) return 0f;
        if (!motionEnabled) return 1f;
        long remaining = firstBeginMillis - positionMillis;
        float progress = Math.min(1f, remaining / (float) EXIT_LEAD_MILLIS);
        return progress * progress * (3f - 2f * progress);
    }

    public static float scale(long positionMillis, boolean motionEnabled) {
        if (!motionEnabled) return 1f;
        double phase = Math.max(0L, positionMillis) % (long) PULSE_PERIOD_MILLIS;
        float pulse = (float) (0.5 - 0.5 * Math.cos(2.0 * Math.PI * phase / PULSE_PERIOD_MILLIS));
        return 0.9f + 0.3f * pulse;
    }

    public static float centerX(int alignment, boolean rtl, float left, float width, float textSize) {
        // Keep the breathing group's outer edge within the existing row at maximum scale.
        float inset = Math.min(width * 0.5f, textSize * (SPACING_EM + RADIUS_EM) * MAX_SCALE);
        if (alignment == LyricUiConfig.ALIGN_CENTER) return left + width * 0.5f;
        boolean right = alignment == LyricUiConfig.ALIGN_END ? !rtl : rtl;
        return left + (right ? width - inset : inset);
    }

    /** Stable prefix geometry; only the focus anchor and dot ink animate during handoff. */
    public static float rowHeight(float mainTextSize, float lineSpacing) {
        return Math.max(0f, mainTextSize) * 1.5f + Math.max(0f, lineSpacing);
    }

    public static float boundedTextSize(float mainTextSize, float availableWidth) {
        float maximum = Math.max(0f, availableWidth) / (2f * (SPACING_EM + RADIUS_EM) * MAX_SCALE);
        return Math.max(0f, Math.min(mainTextSize, maximum));
    }
}
