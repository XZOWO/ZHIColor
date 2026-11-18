package com.naivety.zhicolor.bridge;

import com.naivety.zhicolor.bridge.render.LyricTimingMode;
import com.naivety.zhicolor.bridge.render.WordLine;
import com.naivety.zhicolor.bridge.render.WordLyricModel;
import com.naivety.zhicolor.bridge.render.WordRange;
import java.util.ArrayList;
import org.junit.Test;
import static org.junit.Assert.*;

public class LyricIntroDotsPolicyTest {
    private static WordLyricModel modelWithLines(WordLine... rows) {
        WordLyricModel model = new WordLyricModel();
        for (WordLine row : rows) model.lines.add(row);
        return model;
    }

    @Test
    public void visibilityStartsAtZeroAndHidesAtFirstLineStartWhenGapExceedsSevenSeconds() {
        assertTrue(LyricIntroDotsPolicy.isVisible(8_000L, 0L));
        assertTrue(LyricIntroDotsPolicy.isVisible(8_000L, 7_999L));
        assertFalse(LyricIntroDotsPolicy.isVisible(8_000L, 8_000L));
    }

    @Test
    public void shortIntrosAtOrBelowSevenSecondsAreDisabled() {
        assertFalse(LyricIntroDotsPolicy.hasIntro(-1L));
        assertFalse(LyricIntroDotsPolicy.hasIntro(1_200L));
        assertFalse(LyricIntroDotsPolicy.hasIntro(7_000L));
        assertFalse(LyricIntroDotsPolicy.isVisible(7_000L, 0L));
        assertTrue(LyricIntroDotsPolicy.hasIntro(7_001L));
        assertTrue(LyricIntroDotsPolicy.isVisible(7_001L, 0L));
        assertFalse(LyricIntroDotsPolicy.isVisible(8_000L, -1L));
    }

    @Test
    public void animationRequiresAdvancingPlaybackAndMotion() {
        assertTrue(LyricIntroDotsPolicy.shouldAnimate(10_000L, 1_000L, true, true));
        assertFalse(LyricIntroDotsPolicy.shouldAnimate(10_000L, 1_000L, false, true));
        assertFalse(LyricIntroDotsPolicy.shouldAnimate(10_000L, 1_000L, true, false));
        assertFalse(LyricIntroDotsPolicy.shouldAnimate(10_000L, 10_000L, true, true));
    }

    @Test
    public void rowHeightAndWidthCapScaleCleanly() {
        assertEquals(2f * LyricIntroDotsPolicy.rowHeight(20f, 0f),
                LyricIntroDotsPolicy.rowHeight(40f, 0f), 0.0001f);
        float capped = LyricIntroDotsPolicy.boundedTextSize(100f, 24f);
        float expectedSpan = 2f * LyricWaitingDotsPolicy.DOT_PITCH_EM
                + LyricWaitingDotsPolicy.DOT_DIAMETER_EM * LyricIntroDotsPolicy.MAX_SCALE;
        assertEquals(24f, capped * expectedSpan, 0.0001f);
    }

    @Test
    public void leadingSpaceAndPlaceholderWordsDoNotHideFirstWordStart() {
        ArrayList<WordRange> words = new ArrayList<>();
        words.add(new WordRange(500L, 0, 1, 1_000L));
        words.add(new WordRange(9_000L, 1, 6, 9_500L));
        WordLine main = new WordLine(500L, " Hello", words, 10_000L, LyricTimingMode.WORD_TIMED);
        assertEquals(9_000L, LyricIntroDotsPolicy.beginMillis(main));
        assertTrue(LyricIntroDotsPolicy.isVisible(LyricIntroDotsPolicy.beginMillis(main), 2_000L));
    }

    @Test
    public void placeholderAndMetadataRowsAreSkippedWhenFindingFirstLyricLine() {
        WordLine meta = new WordLine(0L, "作词：测试", new ArrayList<>(), 2_000L, LyricTimingMode.LINE_TIMED);
        WordLine placeholder = new WordLine(2_000L, "♪ ♪", new ArrayList<>(), 9_000L, LyricTimingMode.LINE_TIMED);
        WordLine main = new WordLine(9_000L, "Hello", new ArrayList<>(), 12_000L, LyricTimingMode.LINE_TIMED);
        WordLyricModel model = modelWithLines(meta, placeholder, main);

        assertSame(main, LyricIntroDotsPolicy.firstLyricLine(model));
        assertSame(main, LyricIntroDotsPolicy.visibleLine(model, 2_000L));
        assertNull(LyricIntroDotsPolicy.visibleLine(model, 9_000L));
        assertSame(main, LyricIntroDotsPolicy.boundFirstLyricLine(model, placeholder, "Hello", 2_000L));
        assertNull(LyricIntroDotsPolicy.boundFirstLyricLine(model, placeholder, "♪ ♪", 2_000L));
        assertNull(LyricIntroDotsPolicy.boundFirstLyricLine(model, placeholder, "作词：测试", 2_000L));
    }

    @Test
    public void repeatedFirstLyricTextNeverBindsSecondOccurrenceAsIntro() {
        WordLine first = new WordLine(9_000L, "Hello", new ArrayList<>(), 12_000L, LyricTimingMode.LINE_TIMED);
        WordLine repeated = new WordLine(20_000L, "Hello", new ArrayList<>(), 24_000L, LyricTimingMode.LINE_TIMED);
        WordLyricModel model = modelWithLines(first, repeated);
        assertSame(first, LyricIntroDotsPolicy.boundFirstLyricLine(model, first, "Hello", 2_000L));
        assertNull(LyricIntroDotsPolicy.boundFirstLyricLine(model, repeated, "Hello", 2_000L));
        assertNull(LyricIntroDotsPolicy.boundFirstLyricLine(model, null, "Hello", 2_000L));
    }
}
