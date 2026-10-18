package com.naivety.zhicolor.bridge.render;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class LyricSecondaryWindowPolicyTest {
    private static final LyricSecondaryWindowPolicy.TextWidth CHARACTERS =
            (text, start, end) -> text.codePointCount(start, end);

    @Test
    public void longChineseWordReachesLaterRowsUsingItsOwnExplicitWordClock() {
        String text = "一二三四五六七八九十甲乙丙丁戊己庚辛壬癸";
        WordLine line = wordLine(200L, 12_000L, text,
                new WordRange(2_000L, 0, text.length(), 7_000L));
        List<LyricDrawLine> rows = fixedRows(text, 4);

        assertEquals(0, target(line, rows, 1_999L, 2));
        assertEquals(0, target(line, rows, 2_000L, 2));
        assertEquals(2, target(line, rows, 4_000L, 2));
        assertEquals(3, target(line, rows, 6_500L, 2));
        assertEquals(3, target(line, rows, 7_000L, 2));
        assertEquals("丙丁戊己庚辛壬癸", text.substring(rows.get(3).start));
    }

    @Test
    public void wordCrossingRowsUsesMeasuredIntersectionWidths() {
        String text = "aaaaBBBBccccDDDDeeee";
        WordLine line = wordLine(0L, 10_000L, text,
                new WordRange(1_000L, 2, 14, 3_000L));
        List<LyricDrawLine> rows = fixedRows(text, 4);
        LyricSecondaryWindowPolicy.TextWidth glyphWidths = (value, start, end) -> {
            float result = 0f;
            for (int i = start; i < end; i++) {
                char letter = value.charAt(i);
                result += letter == 'B' ? 2f : letter == 'D' ? 3f : 1f;
            }
            return result;
        };
        // The intersections measure 2, 8, 4, 6: the word starts/ends inside a row.
        assertEquals(1, LyricSecondaryWindowPolicy.targetStart(line, rows, glyphWidths, 1_800L, 1));
        assertEquals(2, LyricSecondaryWindowPolicy.targetStart(line, rows, glyphWidths, 2_000L, 1));
        assertEquals(3, LyricSecondaryWindowPolicy.targetStart(line, rows, glyphWidths, 2_500L, 1));
    }

    @Test
    public void gapAndEmptyTimedTokensHoldTheLastSungWordInsteadOfDrifting() {
        String text = "aaaabbbbccccdddd";
        WordLine line = wordLine(0L, 6_000L, text,
                new WordRange(1_000L, 0, 4, 1_500L),
                new WordRange(2_000L, 4, 4, 2_100L),
                new WordRange(4_000L, 12, 16, 5_000L));
        List<LyricDrawLine> rows = fixedRows(text, 4);

        assertEquals(0, target(line, rows, 999L, 2));
        assertEquals(0, target(line, rows, 3_500L, 2));
        assertEquals(2, target(line, rows, 4_000L, 2));
        assertEquals(2, target(line, rows, 5_500L, 2));
    }

    @Test
    public void whitespaceWordClockDoesNotAdvanceAcrossAnInstrumentalGap() {
        String text = "aaaa    bbbbcccc";
        WordLine line = wordLine(0L, 6_000L, text,
                new WordRange(1_000L, 0, 4, 1_500L),
                new WordRange(2_000L, 4, 8, 2_100L),
                new WordRange(4_000L, 8, 16, 5_000L));
        List<LyricDrawLine> rows = fixedRows(text, 4);

        assertEquals(0, target(line, rows, 3_500L, 1));
        assertEquals(2, target(line, rows, 4_000L, 1));
        assertEquals(3, target(line, rows, 4_750L, 1));
    }

    @Test
    public void lineTimingUsesItsOwnIntervalAndWidthsRatherThanEqualRowDurations() {
        WordLine line = new WordLine(10_000L, "abcdefghij", new ArrayList<>(),
                14_000L, LyricTimingMode.LINE_TIMED);
        List<LyricDrawLine> rows = Arrays.asList(
                row(0, 1, 1f), row(1, 2, 1f), row(2, 8, 6f), row(8, 10, 2f));

        assertEquals(0, target(line, rows, 9_000L, 2));
        assertEquals(1, target(line, rows, 11_000L, 2));
        assertEquals(1, target(line, rows, 13_000L, 2));
        assertEquals(2, target(line, rows, 14_000L, 2));
        assertEquals(2, target(line, rows, 90_000L, 2));
    }

    @Test
    public void emptyWordListsAndEmptyRangesStillExposeLongTextOnTheVocalClock() {
        String text = "aaaabbbbccccdddd";
        WordLine empty = wordLine(2_000L, 6_000L, text);
        WordLine emptyRange = wordLine(2_000L, 6_000L, text,
                new WordRange(4_000L, 3, 3, 4_500L));
        List<LyricDrawLine> rows = fixedRows(text, 4);

        assertEquals(2, target(empty, rows, 5_500L, 2));
        assertEquals(2, target(emptyRange, rows, 5_500L, 2));
        assertEquals(0, target(emptyRange, rows, 1_000L, 2));
        assertEquals(4f, reveal(empty, rows, 2, 5_500L), 0f);
        assertEquals(2f, reveal(empty, rows, 3, 5_500L), 0f);
        assertEquals(4f, reveal(emptyRange, rows, 2, 5_500L), 0f);
        assertEquals(2f, reveal(emptyRange, rows, 3, 5_500L), 0f);
    }

    @Test
    public void seekingBothDirectionsIsDeterministicAndDoesNotMutateTheLayout() {
        String text = "aaaabbbbccccdddd";
        WordLine line = wordLine(1_000L, 8_000L, text,
                new WordRange(2_000L, 0, 16, 6_000L));
        List<LyricDrawLine> rows = fixedRows(text, 4);
        line.mainLineWindowStart = 17;
        line.mainLineWindowPreviousStart = 13;
        line.mainLineWindowLayoutKey = 91;
        long[] positions = {5_500L, 2_100L, 4_500L, 1_500L, 5_500L};
        int[] expected = {2, 0, 2, 0, 2};
        for (int i = 0; i < positions.length; i++) {
            assertEquals(expected[i], target(line, rows, positions[i], 2));
        }
        assertEquals(17, line.mainLineWindowStart);
        assertEquals(13, line.mainLineWindowPreviousStart);
        assertEquals(91, line.mainLineWindowLayoutKey);
        assertEquals(0, rows.get(0).start);
        assertEquals(4, rows.get(0).end);
        assertEquals(4f, rows.get(0).width, 0f);
    }

    @Test
    public void actualEnglishWrappingKeepsSourceTextWhileReachingTheFinalWord() {
        String text = "I don't wanna know,  baby!";
        WordLine line = wordLine(1_000L, 7_000L, text,
                new WordRange(1_000L, 0, 1, 1_500L),
                new WordRange(2_000L, 2, 7, 2_500L),
                new WordRange(3_000L, 8, 13, 3_500L),
                new WordRange(4_000L, 14, 18, 4_500L),
                new WordRange(5_000L, 21, 25, 6_000L));
        LyricDrawLayoutEngine engine = new LyricDrawLayoutEngine(CHARACTERS::measure);
        engine.build(line, text, 7f, false, false, 100, null);

        assertTrue(engine.lines().size() > 2);
        assertEquals(engine.lines().size() - 2, target(line, engine.lines(), 5_500L, 2));
        assertEquals("I don't wanna know,  baby!", line.text);
        assertEquals("baby!", line.text.substring(engine.lines().get(engine.lines().size() - 1).start));
    }

    @Test
    public void unavailableMeasurementsFallBackWithoutLosingLaterRows() {
        String text = "aaaabbbbccccdddd";
        WordLine line = wordLine(0L, 8_000L, text,
                new WordRange(1_000L, 0, 16, 5_000L));
        List<LyricDrawLine> rows = fixedRows(text, 4);

        assertEquals(3, LyricSecondaryWindowPolicy.targetStart(line, rows,
                (value, start, end) -> Float.NaN, 4_500L, 1));
        assertEquals(3, LyricSecondaryWindowPolicy.targetStart(line, rows, null, 4_500L, 1));
    }

    @Test
    public void zeroWidthLineRowsUseEvenDurationsAndAllWindowsStayClamped() {
        WordLine line = new WordLine(1_000L, "abcd", new ArrayList<>(),
                5_000L, LyricTimingMode.LINE_TIMED);
        List<LyricDrawLine> rows = Arrays.asList(
                row(0, 1, 0f), row(1, 2, Float.NaN), row(2, 3, -1f), row(3, 4, 0f));

        assertEquals(1, target(line, rows, 3_500L, 2));
        assertEquals(2, target(line, rows, 5_000L, 2));
        assertEquals(0, target(line, rows, 3_500L, 0));
        assertEquals(0, target(line, rows, 3_500L, 256));
        assertEquals(0, target(line, Collections.emptyList(), 3_500L, 2));
        assertEquals(0, target(null, rows, 3_500L, 2));
    }

    @Test
    public void wrapTrimmedSpacesDoNotSeparateTheWindowFromItsHighlightFront() {
        String text = "aaaa bbbb cccc dddd";
        WordLine line = wordLine(0L, 8_000L, text,
                new WordRange(1_000L, 0, text.length(), 5_000L));
        LyricDrawLayoutEngine engine = new LyricDrawLayoutEngine(CHARACTERS::measure);
        engine.build(line, text, 4f, false, false, 100, null);
        List<LyricDrawLine> rows = engine.lines();

        assertEquals(4, rows.size());
        assertEquals(2, target(line, rows, 3_250L, 2));
        assertEquals(4f, reveal(line, rows, 0, 3_250L), 0f);
        assertEquals(4f, reveal(line, rows, 1, 3_250L), 0f);
        assertEquals(1f, reveal(line, rows, 2, 3_250L), 0f);
        assertEquals(0f, reveal(line, rows, 3, 3_250L), 0f);
    }

    @Test
    public void emptyTokenAtALaterTextOffsetDoesNotPrematurelyHighlightRows() {
        String text = "aaaabbbbccccdddd";
        WordLine line = wordLine(0L, 8_000L, text,
                new WordRange(1_000L, 0, 4, 1_500L),
                new WordRange(2_000L, 12, 12, 2_100L),
                new WordRange(4_000L, 12, 16, 5_000L));
        List<LyricDrawLine> rows = fixedRows(text, 4);

        assertEquals(0, target(line, rows, 3_000L, 2));
        assertEquals(4f, reveal(line, rows, 0, 3_000L), 0f);
        assertEquals(0f, reveal(line, rows, 1, 3_000L), 0f);
        assertEquals(0f, reveal(line, rows, 2, 3_000L), 0f);
        assertEquals(0f, reveal(line, rows, 3, 3_000L), 0f);
    }

    @Test
    public void rowHighlightUsesTheSameMeasuredPartialWordIntersectionsAsTheWindow() {
        String text = "aaaaBBBBccccDDDDeeee";
        WordLine line = wordLine(0L, 10_000L, text,
                new WordRange(1_000L, 2, 14, 3_000L));
        List<LyricDrawLine> rows = Arrays.asList(row(0, 4, 4f), row(4, 8, 8f),
                row(8, 12, 4f), row(12, 16, 12f), row(16, 20, 4f));
        LyricSecondaryWindowPolicy.TextWidth glyphWidths = (value, start, end) -> {
            float result = 0f;
            for (int i = start; i < end; i++) {
                char letter = value.charAt(i);
                result += letter == 'B' ? 2f : letter == 'D' ? 3f : 1f;
            }
            return result;
        };

        assertEquals(3, LyricSecondaryWindowPolicy.targetStart(line, rows, glyphWidths, 2_500L, 1));
        assertEquals(4f, LyricSecondaryWindowPolicy.rowRevealWidth(line, rows, 0, glyphWidths, 2_500L), 0f);
        assertEquals(8f, LyricSecondaryWindowPolicy.rowRevealWidth(line, rows, 1, glyphWidths, 2_500L), 0f);
        assertEquals(4f, LyricSecondaryWindowPolicy.rowRevealWidth(line, rows, 2, glyphWidths, 2_500L), 0f);
        assertEquals(1f, LyricSecondaryWindowPolicy.rowRevealWidth(line, rows, 3, glyphWidths, 2_500L), 0f);
        assertEquals(0f, LyricSecondaryWindowPolicy.rowRevealWidth(line, rows, 4, glyphWidths, 2_500L), 0f);
    }

    @Test
    public void lineHighlightUsesTheSameOwnClockAndWidthWeightingAsTheWindow() {
        WordLine line = new WordLine(10_000L, "abcdefghij", new ArrayList<>(),
                14_000L, LyricTimingMode.LINE_TIMED);
        List<LyricDrawLine> rows = Arrays.asList(
                row(0, 1, 1f), row(1, 2, 1f), row(2, 8, 6f), row(8, 10, 2f));

        assertEquals(1, target(line, rows, 11_000L, 2));
        assertEquals(1f, reveal(line, rows, 0, 11_000L), 0f);
        assertEquals(1f, reveal(line, rows, 1, 11_000L), 0f);
        assertEquals(0.5f, reveal(line, rows, 2, 11_000L), 0f);
        assertEquals(0f, reveal(line, rows, 3, 11_000L), 0f);
        assertEquals(0f, reveal(line, rows, 0, 9_000L), 0f);
        assertEquals(2f, reveal(line, rows, 3, 99_000L), 0f);
    }

    @Test
    public void revealSupportsReverseSeekAndMissingMeasurementsWithoutChangingRows() {
        String text = "aaaabbbbccccdddd";
        WordLine line = wordLine(0L, 8_000L, text,
                new WordRange(1_000L, 0, 16, 5_000L));
        List<LyricDrawLine> rows = Arrays.asList(row(0, 4, 8f), row(4, 8, 8f),
                row(8, 12, 8f), row(12, 16, 8f));

        assertEquals(8f, LyricSecondaryWindowPolicy.rowRevealWidth(line, rows, 2, null, 4_500L), 0f);
        assertEquals(4f, LyricSecondaryWindowPolicy.rowRevealWidth(line, rows, 2, null, 3_500L), 0f);
        assertEquals(0f, LyricSecondaryWindowPolicy.rowRevealWidth(line, rows, 2, null, 2_000L), 0f);
        assertEquals(0f, reveal(line, rows, -1, 3_500L), 0f);
        assertEquals(0f, reveal(line, rows, 4, 3_500L), 0f);
        assertEquals(0f, reveal(line, rows, 0, 999L), 0f);
        assertEquals(8f, rows.get(2).width, 0f);
        assertEquals(8, rows.get(2).start);
        assertEquals(12, rows.get(2).end);
    }

    private static float reveal(WordLine line, List<LyricDrawLine> rows, int index, long position) {
        return LyricSecondaryWindowPolicy.rowRevealWidth(line, rows, index, CHARACTERS, position);
    }

    private static int target(WordLine line, List<LyricDrawLine> rows, long position, int visibleRows) {
        return LyricSecondaryWindowPolicy.targetStart(line, rows, CHARACTERS, position, visibleRows);
    }

    private static WordLine wordLine(long begin, long end, String text, WordRange... words) {
        return new WordLine(begin, text, new ArrayList<>(Arrays.asList(words)), end,
                LyricTimingMode.WORD_TIMED);
    }

    private static List<LyricDrawLine> fixedRows(String text, int length) {
        ArrayList<LyricDrawLine> rows = new ArrayList<>();
        for (int start = 0; start < text.length(); start += length) {
            int end = Math.min(text.length(), start + length);
            rows.add(row(start, end, end - start));
        }
        return rows;
    }

    private static LyricDrawLine row(int start, int end, float width) {
        LyricDrawLine row = new LyricDrawLine();
        row.start = start;
        row.end = end;
        row.width = width;
        return row;
    }
}
