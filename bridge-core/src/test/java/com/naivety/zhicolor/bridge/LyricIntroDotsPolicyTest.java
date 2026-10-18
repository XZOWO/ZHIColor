package com.naivety.zhicolor.bridge;

import static org.junit.Assert.*;
import com.naivety.zhicolor.bridge.render.LyricTimingMode;
import com.naivety.zhicolor.bridge.render.WordLine;
import com.naivety.zhicolor.bridge.render.WordLyricModel;
import com.naivety.zhicolor.bridge.render.WordRange;
import java.util.ArrayList;
import java.util.Arrays;
import org.junit.Test;

public class LyricIntroDotsPolicyTest {
    private static WordLine line(long begin, String text) {
        return new WordLine(begin, text, new ArrayList<>(), begin + 3_000L, LyricTimingMode.LINE_TIMED);
    }

    @Test public void exactFirstLyricBoundaryRemovesDotsWithoutTailState() {
        assertTrue(LyricIntroDotsPolicy.isVisible(5_000L, 4_999L));
        assertFalse(LyricIntroDotsPolicy.isVisible(5_000L, 5_000L));
        assertEquals(0f, LyricIntroDotsPolicy.exitAmount(5_000L, 5_000L, true), 0f);
    }

    @Test public void introThresholdAndUnknownClockDoNotInventAnIntro() {
        assertFalse(LyricIntroDotsPolicy.isVisible(-1L, 0L));
        assertFalse(LyricIntroDotsPolicy.isVisible(1_199L, 0L));
        assertTrue(LyricIntroDotsPolicy.isVisible(1_200L, 0L));
        assertFalse(LyricIntroDotsPolicy.isVisible(5_000L, -1L));
    }

    @Test public void finalHalfSecondUsesZhiToolSmoothShrinkAndFade() {
        assertEquals(1f, LyricIntroDotsPolicy.exitAmount(5_000L, 4_500L, true), 0f);
        assertEquals(0.5f, LyricIntroDotsPolicy.exitAmount(5_000L, 4_750L, true), 0.00001f);
        assertTrue(LyricIntroDotsPolicy.exitAmount(5_000L, 4_999L, true) < 0.0001f);
    }

    @Test public void breathingUsesPlaybackClockAndExactTwoPointFourSecondPeriod() {
        assertEquals(0.9f, LyricIntroDotsPolicy.scale(0L, true), 0.00001f);
        assertEquals(1.2f, LyricIntroDotsPolicy.scale(1_200L, true), 0.00001f);
        assertEquals(LyricIntroDotsPolicy.scale(400L, true),
                LyricIntroDotsPolicy.scale(2_800L, true), 0.00001f);
    }

    @Test public void pauseSnapshotAodAndDisabledMotionNeverRequestAnimationFrames() {
        assertFalse(LyricIntroDotsPolicy.shouldAnimate(5_000L, 2_000L, false, true));
        assertFalse(LyricIntroDotsPolicy.shouldAnimate(5_000L, 2_000L, true, false));
        assertFalse(LyricIntroDotsPolicy.shouldAnimate(5_000L, 5_000L, true, true));
        assertTrue(LyricIntroDotsPolicy.shouldAnimate(5_000L, 2_000L, true, true));
        assertEquals(1f, LyricIntroDotsPolicy.scale(2_000L, false), 0f);
        assertEquals(1f, LyricIntroDotsPolicy.exitAmount(5_000L, 4_999L, false), 0f);
    }

    @Test public void seekBackAndTrackReplacementResolveFromTheirOwnTimeline() {
        assertFalse(LyricIntroDotsPolicy.isVisible(5_000L, 8_000L));
        assertTrue(LyricIntroDotsPolicy.isVisible(5_000L, 1_000L));
        assertFalse(LyricIntroDotsPolicy.isVisible(0L, 1_000L));
        assertTrue(LyricIntroDotsPolicy.isVisible(9_000L, 1_000L));
    }

    @Test public void firstRealMainSkipsSpacersMusicNotesAndMetadata() {
        WordLyricModel model = new WordLyricModel();
        WordLine lyric = line(5_000L, "Hello world");
        model.lines.addAll(Arrays.asList(line(0L, "\u200b"), line(100L, "♪ ♪"),
                line(200L, "作词：测试"), lyric));
        assertSame(lyric, LyricIntroDotsPolicy.firstLyricLine(model));
        assertEquals(5_000L, LyricIntroDotsPolicy.beginMillis(lyric));
    }

    @Test public void wordClockIgnoresInvalidOrEmptyLeadingRanges() {
        WordLine lyric = new WordLine(0L, "Hello", new ArrayList<>(Arrays.asList(
                null, new WordRange(-1L, 0, 1), new WordRange(10L, 0, 0),
                new WordRange(5_000L, 0, 5))), 8_000L, LyricTimingMode.WORD_TIMED);
        assertEquals(5_000L, LyricIntroDotsPolicy.beginMillis(lyric));
        assertEquals(4_000L, LyricIntroDotsPolicy.beginMillis(
                new WordLine(4_000L, "Hello", null, 8_000L, LyricTimingMode.WORD_TIMED)));
    }

    @Test public void noLyricOrImmediateStartDoesNotCreateDots() {
        assertNull(LyricIntroDotsPolicy.firstLyricLine(null));
        WordLyricModel empty = new WordLyricModel();
        assertNull(LyricIntroDotsPolicy.firstLyricLine(empty));
        empty.lines.add(line(0L, "Hello"));
        assertFalse(LyricIntroDotsPolicy.isVisible(
                LyricIntroDotsPolicy.beginMillis(LyricIntroDotsPolicy.firstLyricLine(empty)), 0L));
    }

    @Test public void secondVocalCannotEndTheMainIntroEarly() {
        WordLyricModel model = new WordLyricModel();
        WordLine main = line(5_000L, "Main lyric");
        main.secondaryLine = line(1_000L, "Background vocal");
        model.lines.add(main);
        assertSame(main, LyricIntroDotsPolicy.firstLyricLine(model));
        assertEquals(5_000L, LyricIntroDotsPolicy.beginMillis(main));
        assertTrue(LyricIntroDotsPolicy.isVisible(LyricIntroDotsPolicy.beginMillis(main), 2_000L));
    }

    @Test public void untimedFirstMainUsesItsOwnLineTime() {
        WordLine main = new WordLine(4_000L, "Main lyric", new ArrayList<>(Arrays.asList(
                new WordRange(0L, 0, 10))), 6_000L, LyricTimingMode.LINE_TIMED);
        assertEquals(4_000L, LyricIntroDotsPolicy.beginMillis(main));
        assertEquals(0L, LyricIntroDotsPolicy.beginMillis(line(0L, "Immediate lyric")));
    }

    @Test public void validWordStartRemainsAuthoritativeIfRowStartWasEstimatedLater() {
        WordLine main = new WordLine(5_000L, "Main lyric", new ArrayList<>(Arrays.asList(
                new WordRange(4_000L, 0, 10))), 6_000L, LyricTimingMode.WORD_TIMED);
        assertEquals(main.firstProgressStartMillis(), LyricIntroDotsPolicy.beginMillis(main));
        assertFalse(LyricIntroDotsPolicy.isVisible(LyricIntroDotsPolicy.beginMillis(main), 4_000L));
    }

    @Test public void logicalAlignmentKeepsAllThreeDotsInsideLtrAndRtlRows() {
        float inset = 40f * (0.45f + 0.14f) * 1.2f;
        assertEquals(10f + inset, LyricIntroDotsPolicy.centerX(0, false, 10f, 300f, 40f), 0.0001f);
        assertEquals(310f - inset, LyricIntroDotsPolicy.centerX(0, true, 10f, 300f, 40f), 0.0001f);
        assertEquals(160f, LyricIntroDotsPolicy.centerX(1, true, 10f, 300f, 40f), 0f);
        assertEquals(310f - inset, LyricIntroDotsPolicy.centerX(2, false, 10f, 300f, 40f), 0.0001f);
        assertEquals(10f + inset, LyricIntroDotsPolicy.centerX(2, true, 10f, 300f, 40f), 0.0001f);
    }

    @Test public void maximumBreathingBoundaryAlignsToTextRatherThanTheSmallPulse() {
        float size = 40f;
        float maximumHalfWidth = size * (LyricIntroDotsPolicy.SPACING_EM + LyricIntroDotsPolicy.RADIUS_EM)
                * LyricIntroDotsPolicy.MAX_SCALE;
        float leftCenter = LyricIntroDotsPolicy.centerX(0, false, 12f, 320f, size);
        float rightCenter = LyricIntroDotsPolicy.centerX(2, false, 12f, 320f, size);
        assertEquals(12f, leftCenter - maximumHalfWidth, 0.0001f);
        assertEquals(332f, rightCenter + maximumHalfWidth, 0.0001f);
        float smallHalfWidth = maximumHalfWidth * (0.9f / LyricIntroDotsPolicy.MAX_SCALE);
        assertTrue(leftCenter - smallHalfWidth > 12f);
        assertTrue(rightCenter + smallHalfWidth < 332f);
    }

    @Test public void changingMainFontScalesDotsAndReservedRowTogether() {
        assertEquals(2f * LyricIntroDotsPolicy.boundedTextSize(20f, 500f),
                LyricIntroDotsPolicy.boundedTextSize(40f, 500f), 0.0001f);
        assertEquals(2f * LyricIntroDotsPolicy.rowHeight(20f, 0f),
                LyricIntroDotsPolicy.rowHeight(40f, 0f), 0.0001f);
        float narrowSize = LyricIntroDotsPolicy.boundedTextSize(100f, 24f);
        assertEquals(24f, 2f * narrowSize * (LyricIntroDotsPolicy.SPACING_EM + LyricIntroDotsPolicy.RADIUS_EM)
                * LyricIntroDotsPolicy.MAX_SCALE, 0.0001f);
    }

    @Test public void introHandoffKeepsRowHeightAndFollowingLyricSpacingStable() {
        float bodyHeight = 76f;
        float prefix = LyricIntroDotsPolicy.rowHeight(32f, 13f);
        float previousAnchor = Float.NEGATIVE_INFINITY;
        for (int frame = 0; frame <= 500; frame++) {
            float amount = LyricIntroDotsPolicy.exitAmount(5_000L, 4_500L + frame, true);
            float anchor = LyricMainAnchorPolicy.focusCenter(18f, 40f, prefix, amount);
            assertTrue(anchor >= previousAnchor);
            assertEquals(137f, bodyHeight + prefix, 0f);
            previousAnchor = anchor;
        }
        assertEquals(61f, prefix, 0f);
        assertEquals(prefix + 38f, previousAnchor, 0.0001f);
        assertTrue(LyricIntroDotsPolicy.hasIntro(5_000L));
        assertFalse(LyricIntroDotsPolicy.hasIntro(0L));
    }

    @Test public void spaceOnlyWordDoesNotEndTheIntroBeforeTheFirstSungWord() {
        WordLine main = new WordLine(0L, " Hello", new ArrayList<>(Arrays.asList(
                new WordRange(1_000L, 0, 1), new WordRange(5_000L, 1, 6))),
                8_000L, LyricTimingMode.WORD_TIMED);
        assertEquals(5_000L, LyricIntroDotsPolicy.beginMillis(main));
        assertTrue(LyricIntroDotsPolicy.isVisible(LyricIntroDotsPolicy.beginMillis(main), 2_000L));
    }

    @Test public void zeroWidthAndUnicodeSpaceWordsDoNotBeginSinging() {
        WordLine main = new WordLine(0L, "\u200b\u00a0\u3000Hello", new ArrayList<>(Arrays.asList(
                new WordRange(100L, 0, 1), new WordRange(200L, 1, 2),
                new WordRange(300L, 2, 3), new WordRange(5_000L, 3, 8))),
                8_000L, LyricTimingMode.WORD_TIMED);
        assertEquals(5_000L, LyricIntroDotsPolicy.beginMillis(main));
    }

    @Test public void visibleIntroUsesRealMainEvenWhenNativeCurrentRowIsAPlaceholder() {
        WordLyricModel model = new WordLyricModel();
        WordLine placeholder = line(0L, "♪ ♪");
        WordLine main = line(5_000L, "Hello");
        model.lines.addAll(Arrays.asList(placeholder, line(100L, "作词：测试"), main));
        assertSame(model.lines.get(1), model.findActiveLine(2_000L));
        assertSame(main, LyricIntroDotsPolicy.visibleLine(model, 2_000L));
        assertNull(LyricIntroDotsPolicy.visibleLine(model, 5_000L));
        assertSame(main, LyricIntroDotsPolicy.boundFirstLyricLine(model, placeholder, "Hello", 2_000L));
        assertNull(LyricIntroDotsPolicy.boundFirstLyricLine(model, placeholder, "♪ ♪", 2_000L));
        assertNull(LyricIntroDotsPolicy.boundFirstLyricLine(model, placeholder, "作词：测试", 2_000L));
    }

    @Test public void anotherRepeatedLyricHolderCannotAcquireTheFirstRowsDots() {
        WordLyricModel model = new WordLyricModel();
        WordLine first = line(5_000L, "Hello");
        WordLine repeated = line(9_000L, "Hello");
        model.lines.addAll(Arrays.asList(first, repeated));
        assertSame(first, LyricIntroDotsPolicy.boundFirstLyricLine(model, first, "Hello", 2_000L));
        assertNull(LyricIntroDotsPolicy.boundFirstLyricLine(model, repeated, "Hello", 2_000L));
        assertNull(LyricIntroDotsPolicy.boundFirstLyricLine(model, null, "Hello", 2_000L));
    }

    @Test public void laterPrefixLyricCannotBeReboundToTheFirstMain() {
        WordLyricModel model = new WordLyricModel();
        WordLine first = line(5_000L, "Hello world again");
        WordLine later = line(20_000L, "Hello world");
        model.lines.addAll(Arrays.asList(first, later));
        assertFalse(model.hasDuplicateRenderableText("Hello world"));
        assertNull(LyricIntroDotsPolicy.boundFirstLyricLine(model, later, "Hello world", 20_000L));
        assertNull(LyricIntroDotsPolicy.boundFirstLyricLine(model, later, "Hello world", 2_000L));
    }

    @Test public void completedIntroCannotOverrideNormalOrCachedFrames() {
        WordLyricModel model = new WordLyricModel();
        WordLine first = line(5_000L, "Hello world again");
        model.lines.add(first);
        assertSame(first, LyricIntroDotsPolicy.boundFirstLyricLine(model, null, "Hello world again", 4_999L));
        assertNull(LyricIntroDotsPolicy.boundFirstLyricLine(model, first, "Hello world again", 5_000L));
        assertNull(LyricIntroDotsPolicy.boundFirstLyricLine(model, null, "Hello world", 20_000L));
        assertNull(LyricIntroDotsPolicy.boundFirstLyricLine(model, null, "Hello world again", -1L));
    }
}
