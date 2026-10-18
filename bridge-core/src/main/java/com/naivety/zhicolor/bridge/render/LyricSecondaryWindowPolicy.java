package com.naivety.zhicolor.bridge.render;

import java.util.List;

/** Selects the wrapped window of one vocal without using the parent lyric's clock. */
public final class LyricSecondaryWindowPolicy {
    public interface TextWidth {
        float measure(String text, int start, int end);
    }

    private LyricSecondaryWindowPolicy() {
    }

    /**
     * Word timing follows the reveal front even when one source word spans several
     * visual rows. Line timing follows the same width-weighted window as main text.
     * The input layout and all lyric/cache state remain unchanged, including on seek.
     */
    public static int targetStart(
            WordLine line,
            List<LyricDrawLine> rows,
            TextWidth width,
            long position,
            int visibleRows) {
        if (line == null || rows == null || visibleRows <= 0 || rows.size() <= visibleRows) {
            return 0;
        }
        int activeWord = activeWordIndex(line, position);
        if (activeWord == NO_WORD_CLOCK) {
            return lineTimedStart(line, rows, position, visibleRows);
        }
        if (activeWord < 0) return 0;

        WordRange word = line.words.get(activeWord);
        float progress = WordLyricRenderSupport.wordRevealProgress(null, line, activeWord, position);
        float total = intersectionWidth(line.text, word, rows, width, false);
        boolean useCharacterWidths = width == null || total <= 0f;
        if (useCharacterWidths) total = intersectionWidth(line.text, word, rows, width, true);
        float reveal = total * progress;
        float consumed = 0f;
        int lastRow = -1;
        for (int i = 0; i < rows.size(); i++) {
            float part = intersectionWidth(line.text, word, rows.get(i), width, useCharacterWidths);
            if (part <= 0f) continue;
            lastRow = i;
            consumed += part;
            if (reveal < consumed) return clamp(i, rows.size(), visibleRows);
        }
        // At a word's explicit end, hold its final row throughout the vocal gap.
        return clamp(lastRow, rows.size(), visibleRows);
    }

    /** Pixel width revealed in one row, using the same vocal clock/front as targetStart. */
    public static float rowRevealWidth(
            WordLine line,
            List<LyricDrawLine> rows,
            int rowIndex,
            TextWidth width,
            long position) {
        if (line == null || rows == null || rowIndex < 0 || rowIndex >= rows.size()) return 0f;
        LyricDrawLine row = rows.get(rowIndex);
        float fullWidth = rowWidth(row);
        if (fullWidth <= 0f) return 0f;
        int activeWord = activeWordIndex(line, position);
        if (activeWord == NO_WORD_CLOCK) {
            float total = 0f;
            float preceding = 0f;
            for (int i = 0; i < rows.size(); i++) {
                float part = rowWidth(rows.get(i));
                total += part;
                if (i < rowIndex) preceding += part;
            }
            return clampWidth(total * lineProgress(line, position) - preceding, fullWidth);
        }
        if (activeWord < 0) return 0f;
        WordRange word = line.words.get(activeWord);
        int wordStart = Math.max(0, word.start);
        int wordEnd = Math.min(line.text.length(), word.end);
        if (wordStart >= row.end) return fullWidth;
        if (wordEnd <= row.start) return 0f;

        float total = intersectionWidth(line.text, word, rows, width, false);
        boolean useCharacterWidths = width == null || total <= 0f;
        if (useCharacterWidths) total = intersectionWidth(line.text, word, rows, width, true);
        float preceding = 0f;
        for (int i = 0; i < rowIndex; i++) {
            preceding += intersectionWidth(line.text, word, rows.get(i), width, useCharacterWidths);
        }
        float part = intersectionWidth(line.text, word, row, width, useCharacterWidths);
        int rowStart = Math.max(0, row.start);
        int overlapStart = Math.max(wordStart, rowStart);
        float prefix = useCharacterWidths
                ? line.text.codePointCount(rowStart, overlapStart)
                : positiveWidth(width.measure(line.text, rowStart, overlapStart));
        float progress = WordLyricRenderSupport.wordRevealProgress(null, line, activeWord, position);
        float reveal = prefix + clampWidth(total * progress - preceding, part);
        if (useCharacterWidths) {
            int characters = line.text.codePointCount(rowStart, Math.min(line.text.length(), row.end));
            reveal = characters <= 0 ? 0f : fullWidth * reveal / characters;
        }
        return clampWidth(reveal, fullWidth);
    }

    private static final int NO_WORD_CLOCK = -2;

    private static int activeWordIndex(WordLine line, long position) {
        if (line.timingMode != LyricTimingMode.WORD_TIMED
                || line.words == null || line.words.isEmpty()) return NO_WORD_CLOCK;
        int activeWord = -1;
        boolean hasWordClock = false;
        for (int i = 0; i < line.words.size(); i++) {
            WordRange word = line.words.get(i);
            if (!hasVisibleText(line.text, word)) continue;
            hasWordClock = true;
            if (word.timeMillis > position) break;
            activeWord = i;
        }
        return hasWordClock ? activeWord : NO_WORD_CLOCK;
    }

    private static int lineTimedStart(
            WordLine line, List<LyricDrawLine> rows, long position, int visibleRows) {
        if (line.endTimeMillis <= line.timeMillis) return 0;
        float progress = lineProgress(line, position);
        float totalWidth = 0f;
        for (LyricDrawLine row : rows) totalWidth += rowWidth(row);
        int activeRow = rows.size() - 1;
        if (totalWidth <= 0f) {
            activeRow = Math.min(rows.size() - 1, (int) Math.floor(progress * rows.size()));
        } else {
            float reveal = progress * totalWidth;
            float consumed = 0f;
            for (int i = 0; i < rows.size(); i++) {
                consumed += rowWidth(rows.get(i));
                if (reveal < consumed) {
                    activeRow = i;
                    break;
                }
            }
        }
        return clamp(activeRow - visibleRows + 1, rows.size(), visibleRows);
    }

    private static float lineProgress(WordLine line, long position) {
        if (line.endTimeMillis <= line.timeMillis) return 0f;
        return (float) Math.max(0d, Math.min(1d,
                ((double) position - line.timeMillis)
                        / ((double) line.endTimeMillis - line.timeMillis)));
    }

    private static boolean hasVisibleText(String text, WordRange word) {
        if (text == null || word == null) return false;
        int start = Math.max(0, word.start);
        int end = Math.min(text.length(), word.end);
        for (int i = start; i < end;) {
            int codePoint = text.codePointAt(i);
            if (!Character.isWhitespace(codePoint) && !Character.isSpaceChar(codePoint)) return true;
            i += Character.charCount(codePoint);
        }
        return false;
    }

    private static float intersectionWidth(
            String text, WordRange word, List<LyricDrawLine> rows,
            TextWidth width, boolean useCharacterWidths) {
        float total = 0f;
        for (LyricDrawLine row : rows) {
            total += intersectionWidth(text, word, row, width, useCharacterWidths);
        }
        return total;
    }

    private static float intersectionWidth(
            String text, WordRange word, LyricDrawLine row,
            TextWidth width, boolean useCharacterWidths) {
        if (row == null) return 0f;
        int start = Math.max(0, Math.max(row.start, word.start));
        int end = Math.min(text.length(), Math.min(row.end, word.end));
        if (start >= end) return 0f;
        if (useCharacterWidths || width == null) return text.codePointCount(start, end);
        return positiveWidth(width.measure(text, start, end));
    }

    private static float rowWidth(LyricDrawLine row) {
        return row == null ? 0f : positiveWidth(row.width);
    }

    private static float positiveWidth(float width) {
        return Float.isFinite(width) && width > 0f ? width : 0f;
    }

    private static float clampWidth(float width, float fullWidth) {
        return Math.max(0f, Math.min(fullWidth, width));
    }

    private static int clamp(int start, int totalRows, int visibleRows) {
        return Math.max(0, Math.min(start, Math.max(0, totalRows - visibleRows)));
    }
}
