package com.naivety.zhicolor.bridge;

import com.naivety.zhicolor.bridge.render.LyricTimingMode;
import com.naivety.zhicolor.bridge.render.WordLine;
import com.naivety.zhicolor.bridge.render.WordRange;
import com.naivety.zhicolor.bridge.render.ZhiToolLyricMotion;

import java.util.ArrayList;

import org.junit.Test;

import static org.junit.Assert.*;

public final class LyricAuxiliaryVisualPolicyTest {
    @Test public void exitStartsWithoutAColorOrPositionJumpEvenAfterActiveFlagChanges() {
        WordLine parent = exiting();
        assertEquals(1f, LyricAuxiliaryVisualPolicy.highlightAmount(parent, false, 0f), 0f);
        assertEquals(990L, LyricAuxiliaryVisualPolicy.renderPosition(parent, 1_000L));
        assertEquals(0xA0C0E000, LyricAuxiliaryVisualPolicy.baseColor(0x20406080, 0xA0C0E000,
                LyricAuxiliaryVisualPolicy.highlightAmount(parent, false, 0f)));
    }

    @Test public void auxiliaryColorAndPositionUseTheMainTailUntilItsExactDeadline() {
        WordLine parent = exiting();
        parent.highlightExit.sample(false, 1_165L, 275L, true, 1_320L, 300L);
        assertEquals(1_155L, LyricAuxiliaryVisualPolicy.renderPosition(parent, 1_165L));
        assertEquals(0.125f, LyricAuxiliaryVisualPolicy.highlightAmount(parent, false, 0f), 0f);
        parent.highlightExit.sample(false, 1_329L, 439L, true, 1_320L, 300L);
        assertTrue(LyricAuxiliaryVisualPolicy.highlightAmount(parent, false, 0f) > 0f);
        assertTrue(LyricAuxiliaryVisualPolicy.renderPosition(parent, 1_329L) < 1_320L);
        parent.highlightExit.sample(false, 1_330L, 440L, true, 1_320L, 300L);
        assertEquals(0f, LyricAuxiliaryVisualPolicy.highlightAmount(parent, false, 1f), 0f);
        assertEquals(1_330L, LyricAuxiliaryVisualPolicy.renderPosition(parent, 1_330L));
        assertEquals(0x20406080, LyricAuxiliaryVisualPolicy.baseColor(0x20406080, 0xA0C0E000,
                LyricAuxiliaryVisualPolicy.highlightAmount(parent, false, 1f)));
    }

    @Test public void entryUsesFocusAmountAndClampsItsEndpoints() {
        WordLine parent = line("main");
        parent.highlightExit.sample(true, 300L, 0L, true, 1_320L, 300L);
        assertEquals(0f, LyricAuxiliaryVisualPolicy.highlightAmount(parent, true, -1f), 0f);
        assertEquals(0.5f, LyricAuxiliaryVisualPolicy.highlightAmount(parent, true, 0.5f), 0f);
        assertEquals(1f, LyricAuxiliaryVisualPolicy.highlightAmount(parent, true, 2f), 0f);
        assertEquals(0x6080A040,
                LyricAuxiliaryVisualPolicy.baseColor(0x20406080, 0xA0C0E000, 0.5f));
        assertEquals(300L, LyricAuxiliaryVisualPolicy.renderPosition(parent, 300L));
    }

    @Test public void inactiveRowsDoNotInheritAFocusedOrMissingParentsHighlight() {
        assertEquals(0f, LyricAuxiliaryVisualPolicy.highlightAmount(line("main"), false, 1f), 0f);
        assertEquals(0f, LyricAuxiliaryVisualPolicy.highlightAmount(null, false, 1f), 0f);
        assertEquals(0.25f, LyricAuxiliaryVisualPolicy.highlightAmount(null, true, 0.25f), 0f);
        assertEquals(123L, LyricAuxiliaryVisualPolicy.renderPosition(null, 123L));
    }

    @Test public void enteringAuxiliaryInkNeverRunsAheadOfItsParentsCurrentHighlight() {
        WordLine parent = line("main");
        parent.highlightExit.sample(false, 990L, 0L, true, 1_320L, 300L);
        parent.highlightExit.sample(true, 1_000L, 10L, true, 1_320L, 300L);
        assertEquals(0f, LyricAuxiliaryVisualPolicy.highlightAmount(parent, true, 1f), 0f);
        parent.highlightExit.sample(true, 1_150L, 160L, true, 1_320L, 300L);
        assertEquals(0.875f, LyricAuxiliaryVisualPolicy.highlightAmount(parent, true, 1f), 0f);
        assertEquals(0.5f, LyricAuxiliaryVisualPolicy.highlightAmount(parent, true, 0.5f), 0f);
        assertEquals(1_150L, LyricAuxiliaryVisualPolicy.renderPosition(parent, 1_150L));
        parent.highlightExit.sample(false, 1_150L, 160L, true, 1_320L, 300L);
        assertEquals(0.875f, LyricAuxiliaryVisualPolicy.highlightAmount(parent, false, 0f), 0f);
    }

    @Test public void seekDiscardsAllAuxiliaryTailStateWithTheParent() {
        WordLine parent = exiting();
        parent.highlightExit.sample(false, 40_000L, 120L, true, 1_320L, 300L);
        assertEquals(0f, LyricAuxiliaryVisualPolicy.highlightAmount(parent, false, 0f), 0f);
        assertEquals(40_000L, LyricAuxiliaryVisualPolicy.renderPosition(parent, 40_000L));
    }

    @Test public void reentryCancelsTheTailAndResumesTheLiveClock() {
        WordLine parent = exiting();
        parent.highlightExit.sample(false, 1_100L, 210L, true, 1_320L, 300L);
        parent.highlightExit.sample(false, 1_110L, 220L, true, 1_320L, 300L);
        float before = LyricAuxiliaryVisualPolicy.highlightAmount(parent, false, 0f);
        parent.highlightExit.sample(true, 1_110L, 220L, true, 1_320L, 300L);
        assertEquals(before, LyricAuxiliaryVisualPolicy.highlightAmount(parent, true, 1f), 0f);
        assertEquals(1_110L, LyricAuxiliaryVisualPolicy.renderPosition(parent, 1_110L));
        parent.highlightExit.sample(true, 1_110L, 520L, true, 1_320L, 300L);
        assertEquals(1f, LyricAuxiliaryVisualPolicy.highlightAmount(parent, true, 1f), 0f);
    }

    @Test public void sharedExitPositionPreservesEachVocalsAndTranslationsOwnWordIndex() {
        WordLine parent = timed("main", new WordRange(900L, 0, 2, 1_200L),
                new WordRange(1_200L, 2, 4, 1_320L));
        WordLine vocal = timed("echo", new WordRange(800L, 0, 2, 1_100L),
                new WordRange(1_100L, 2, 4, 1_400L));
        WordLine translated = timed("word", new WordRange(1_050L, 0, 2, 1_100L),
                new WordRange(1_100L, 2, 4, 1_300L));
        parent.secondaryLine = vocal;
        vocal.translationLine = translated;
        parent.highlightExit.sample(true, 990L, 100L, true, 1_320L, 300L);
        parent.highlightExit.sample(false, 1_000L, 110L, true, 1_320L, 300L);
        parent.highlightExit.sample(false, 1_165L, 275L, true, 1_320L, 300L);
        long position = LyricAuxiliaryVisualPolicy.renderPosition(parent, 1_165L);

        assertEquals(1_155L, position);
        assertEquals(0, parent.findWordIndex(position));
        assertEquals(1, vocal.findWordIndex(position));
        assertEquals(1, vocal.translationLine.findWordIndex(position));
        assertEquals(0.85f, parent.wordProgress(parent.findWordIndex(position), position), 0.00001f);
        assertEquals(55f / 300f, vocal.wordProgress(vocal.findWordIndex(position), position), 0.00001f);
        assertEquals(0.275f, translated.wordProgress(translated.findWordIndex(position), position), 0.00001f);
        assertSame(vocal, parent.secondaryLine);
        assertSame(translated, vocal.translationLine);
        assertEquals(0f, vocal.highlightExit.highlightAmount(), 0f);
        assertEquals(0f, translated.highlightExit.highlightAmount(), 0f);
    }

    @Test public void colorEndpointsDoNotDependOnTheConfiguredColorOrOpacity() {
        assertEquals(0x20406080, LyricAuxiliaryVisualPolicy.baseColor(0x20406080, 0xA0C0E000, -1f));
        assertEquals(0xA0C0E000, LyricAuxiliaryVisualPolicy.baseColor(0x20406080, 0xA0C0E000, 2f));
    }

    @Test public void partlyExpandedAuxiliaryChannelsKeepTheirValueThenShareTheParentDeadline() {
        WordLine parent = exiting();
        ZhiToolLyricMotion.Value[] channels = channels(parent);
        float[] starts = beginPartialExpansion(channels);
        // The existing preparation pass may have already targeted zero with its old duration.
        for (ZhiToolLyricMotion.Value channel : channels) channel.retarget(0f, 110L, 380L, true);
        LyricAuxiliaryVisualPolicy.synchronizeExit(parent, 110L);
        assertEquals(330L, parent.highlightExit.exitDurationMillis());
        for (int i = 0; i < channels.length; i++) assertEquals(starts[i], channels[i].value(110L), 0f);

        parent.highlightExit.sample(false, 1_165L, 275L, true, 1_320L, 300L);
        for (int i = 0; i < channels.length; i++) {
            assertEquals(starts[i] * parent.highlightExit.highlightAmount(), channels[i].value(275L), 0.000001f);
        }
        for (ZhiToolLyricMotion.Value channel : channels) assertTrue(channel.isRunning(439L));
        parent.highlightExit.sample(false, 1_330L, 440L, true, 1_320L, 300L);
        assertEquals(0L, parent.highlightExit.exitDurationMillis());
        for (ZhiToolLyricMotion.Value channel : channels) {
            assertFalse(channel.isRunning(440L));
            assertEquals(0f, channel.value(440L), 0f);
        }
    }

    @Test public void repeatedPreparationWithZeroTargetsDoesNotRestartTheRetimedExit() {
        WordLine parent = exiting();
        ZhiToolLyricMotion.Value[] channels = channels(parent);
        float[] starts = beginPartialExpansion(channels);
        LyricAuxiliaryVisualPolicy.synchronizeExit(parent, 110L);
        for (ZhiToolLyricMotion.Value channel : channels) {
            channel.retarget(0f, 200L, 620L, true);
            channel.retarget(0f, 260L, 380L, true);
        }
        for (int i = 0; i < channels.length; i++) {
            assertEquals(starts[i] * 0.125f, channels[i].value(275L), 0.000001f);
            assertTrue(channels[i].isRunning(439L));
            assertFalse(channels[i].isRunning(440L));
        }
    }

    @Test public void stillFocusedOrSingingLanesKeepTheirIncomingAnimationAndDeadline() {
        WordLine parent = exiting();
        parent.secondaryFocus = true;
        parent.vocalVisible = true;
        ZhiToolLyricMotion.Value[] channels = channels(parent);
        beginPartialExpansion(channels);
        ZhiToolLyricMotion.Value[] controls = new ZhiToolLyricMotion.Value[4];
        for (int i = 0; i < controls.length; i++) controls[i] = new ZhiToolLyricMotion.Value();
        beginPartialExpansion(controls);
        LyricAuxiliaryVisualPolicy.synchronizeExit(parent, 110L);
        for (int i = 0; i < channels.length; i++) {
            assertEquals(controls[i].value(275L), channels[i].value(275L), 0f);
            assertEquals(controls[i].isRunning(440L), channels[i].isRunning(440L));
            assertEquals(1f, channels[i].value(700L), 0f);
        }
    }

    @Test public void synchronizationDoesNothingOutsideAnExit() {
        WordLine parent = line("main");
        for (ZhiToolLyricMotion.Value channel : channels(parent)) channel.reset(0.4f);
        LyricAuxiliaryVisualPolicy.synchronizeExit(parent, 110L);
        LyricAuxiliaryVisualPolicy.synchronizeExit(null, 110L);
        assertEquals(0L, parent.highlightExit.exitDurationMillis());
        for (ZhiToolLyricMotion.Value channel : channels(parent)) assertEquals(0.4f, channel.value(440L), 0f);
    }

    @Test public void focusAndVocalVisibilityProtectTheirOwnChannelsIndependently() {
        for (int mask = 1; mask <= 2; mask++) {
            WordLine parent = exiting();
            parent.secondaryFocus = mask == 1;
            parent.vocalVisible = mask == 2;
            ZhiToolLyricMotion.Value[] channels = channels(parent);
            float[] starts = beginPartialExpansion(channels);
            LyricAuxiliaryVisualPolicy.synchronizeExit(parent, 110L);
            for (int i = 0; i < channels.length; i++) {
                boolean protectedChannel = i < 2 ? parent.secondaryFocus : parent.vocalVisible;
                float expected = protectedChannel
                        ? ZhiToolLyricMotion.easeOutCubic(265f / (300L + i * 100L))
                        : starts[i] * 0.125f;
                assertEquals(expected, channels[i].value(275L), 0.000001f);
            }
        }
    }

    private static ZhiToolLyricMotion.Value[] channels(WordLine parent) {
        return new ZhiToolLyricMotion.Value[]{parent.secondaryLayout, parent.visualSecondary,
                parent.vocalLayout, parent.vocalAppearance};
    }

    private static float[] beginPartialExpansion(ZhiToolLyricMotion.Value[] channels) {
        float[] starts = new float[channels.length];
        for (int i = 0; i < channels.length; i++) {
            channels[i].reset(0f);
            channels[i].retarget(1f, 10L, 300L + i * 100L, true);
            starts[i] = channels[i].value(110L);
            assertTrue(starts[i] > 0f && starts[i] < 1f);
        }
        return starts;
    }

    private static WordLine exiting() {
        WordLine parent = line("main");
        parent.highlightExit.sample(true, 990L, 100L, true, 1_320L, 300L);
        parent.highlightExit.sample(false, 1_000L, 110L, true, 1_320L, 300L);
        return parent;
    }

    private static WordLine line(String text) {
        return new WordLine(0L, text, new ArrayList<>(), 2_000L, LyricTimingMode.LINE_TIMED);
    }

    private static WordLine timed(String text, WordRange... words) {
        ArrayList<WordRange> ranges = new ArrayList<>();
        for (WordRange word : words) ranges.add(word);
        return new WordLine(words[0].timeMillis, text, ranges,
                words[words.length - 1].endTimeMillis, LyricTimingMode.WORD_TIMED);
    }
}
