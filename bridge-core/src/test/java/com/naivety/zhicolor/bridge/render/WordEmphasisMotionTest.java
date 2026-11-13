package com.naivety.zhicolor.bridge.render;

import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

public final class WordEmphasisMotionTest {
    private static final long START = 1_000L;

    private static WordEmphasisMotion one(String text, long duration) {
        return new WordEmphasisMotion(text, Collections.singletonList(
                new WordRange(START, 0, text.length(), START + duration)));
    }

    private static void begin(WordEmphasisMotion motion) {
        begin(motion, "en");
    }

    private static void begin(WordEmphasisMotion motion, String language) {
        motion.sample(START - 1L, 0L, true, true, language);
        motion.sample(START, 1L, true, true, language);
    }

    /** Hold playback paused; only the bounded UI animation clock advances. */
    private static void advance(WordEmphasisMotion motion, long from, long to) {
        advance(motion, from, to, "en");
    }

    private static void advance(WordEmphasisMotion motion, long from, long to, String language) {
        for (long elapsed = from + 100L; elapsed < to; elapsed += 100L)
            motion.sample(START, elapsed + 1L, true, true, language);
        motion.sample(START, to + 1L, true, true, language);
    }

    private static double critical(double seconds, double period) {
        double omega = 2d * Math.PI / period;
        return 1d - (1d + omega * seconds) * Math.exp(-omega * seconds);
    }

    @Test public void adjacentRealSyllablesFormOneWordWithoutChangingCanonicalRanges() {
        WordRange be = new WordRange(START, 0, 2, 1_500L);
        WordRange cause = new WordRange(1_500L, 2, 7, 2_600L);
        WordRange okay = new WordRange(4_000L, 8, 12, 6_000L);
        WordEmphasisMotion motion = new WordEmphasisMotion("because okay", Arrays.asList(be, cause, okay));
        assertEquals(0, motion.wordStartForCharacter(0));
        assertEquals(0, motion.wordStartForCharacter(6));
        assertEquals(7, motion.wordEndForCharacter(6));
        assertEquals(-1, motion.wordStartForCharacter(7));
        assertEquals(8, motion.wordStartForCharacter(8));
        assertEquals(12, motion.wordEndForCharacter(11));
        begin(motion); advance(motion, 0L, 600L);
        assertTrue(motion.amountForCharacter(2) > 0f); // Before cause's own start.
        assertEquals(motion.glowAmountForCharacter(0), motion.glowAmountForCharacter(6), 0f);
        assertEquals(0f, motion.amountForCharacter(8), 0f);
        assertEquals(START, be.timeMillis);
        assertEquals(1_500L, be.endTimeMillis);
        assertEquals(2, cause.start);
        assertEquals(2_600L, cause.endTimeMillis);
    }

    @Test public void realSourceRangeAcrossWhitespaceKeepsOneClockAndPivot() {
        WordEmphasisMotion motion = one("a b", 2_000L);
        assertEquals(0, motion.wordStartForCharacter(2));
        assertEquals(3, motion.wordEndForCharacter(0));
        begin(motion); advance(motion, 0L, 600L);
        assertTrue(motion.glowAmountForCharacter(0) > 0f);
        assertEquals(motion.glowAmountForCharacter(0), motion.glowAmountForCharacter(2), 0f);
    }

    @Test public void missingSuffixOrInternalTimingGapNeverGetsInventedCoverage() {
        WordEmphasisMotion partial = new WordEmphasisMotion("hello", Collections.singletonList(
                new WordRange(START, 0, 3, 3_000L)));
        assertEquals(3, partial.wordEndForCharacter(0));
        assertEquals(-1, partial.wordStartForCharacter(3));
        assertEquals(0f, partial.amountForCharacter(4), 0f);
        WordEmphasisMotion gap = new WordEmphasisMotion("because", Arrays.asList(
                new WordRange(START, 0, 2, 1_700L), new WordRange(1_700L, 3, 7, 3_000L)));
        assertEquals(-1, gap.wordStartForCharacter(2));
        assertEquals(2, gap.wordEndForCharacter(0));
        assertEquals(3, gap.wordStartForCharacter(3));
    }

    @Test public void durationAndUtf16LengthUseNativeThresholdAndFactor() {
        for (WordEmphasisMotion motion : Arrays.asList(one("a", 1_000L), one("abcdefgh", 2_000L),
                one("😀😀😀😀", 2_000L))) {
            begin(motion); advance(motion, 0L, 800L);
            assertEquals(0f, motion.amountForCharacter(0), 0f);
            assertEquals(0f, motion.glowAmountForCharacter(0), 0f);
            assertFalse(motion.isRunning());
        }
        WordEmphasisMotion half = one("a", 1_500L);
        begin(half); advance(half, 0L, 600L);
        assertEquals(0.5d * critical(0.2d, 1.5d), half.amountForCharacter(0), 1e-6d);
        WordEmphasisMotion capped = one("a", 5_000L);
        begin(capped); advance(capped, 0L, 600L);
        assertEquals(critical(0.2d, 3d), capped.amountForCharacter(0), 1e-6d);
    }

    @Test public void explicitEndAllowsNonFinalWordButMissingEndNeverBorrowsRest() {
        WordEmphasisMotion nonFinal = new WordEmphasisMotion("go stop", Arrays.asList(
                new WordRange(START, 0, 2, 3_000L), new WordRange(5_000L, 3, 7, 7_000L)));
        begin(nonFinal); advance(nonFinal, 0L, 600L);
        assertTrue(nonFinal.amountForCharacter(0) > 0f);
        WordEmphasisMotion noEnd = new WordEmphasisMotion("go stop", Arrays.asList(
                new WordRange(START, 0, 2), new WordRange(50_000L, 3, 7, 52_000L)));
        begin(noEnd); advance(noEnd, 0L, 5_000L);
        assertEquals(0f, noEnd.amountForCharacter(0), 0f);
        assertFalse(noEnd.isRunning());
        WordEmphasisMotion lastEndMissing = new WordEmphasisMotion("hello", Arrays.asList(
                new WordRange(START, 0, 2, 1_500L), new WordRange(1_500L, 2, 5)));
        begin(lastEndMissing); advance(lastEndMissing, 0L, 800L);
        assertEquals(0f, lastEndMissing.amountForCharacter(0), 0f);
        assertFalse(lastEndMissing.isRunning());
    }

    @Test public void glyphsStaggerWhileWordGlowStartsImmediatelyAndIsShared() {
        WordEmphasisMotion motion = one("ab", 2_000L);
        begin(motion); advance(motion, 0L, 200L);
        assertEquals(0f, motion.amountForCharacter(0), 0f);
        assertTrue(motion.glowAmountForCharacter(0) > 0f);
        assertEquals(motion.glowAmountForCharacter(0), motion.glowAmountForCharacter(1), 0f);
        advance(motion, 200L, 600L);
        assertEquals(critical(0.2d, 2d), motion.amountForCharacter(0), 1e-6d);
        assertEquals(0f, motion.amountForCharacter(1), 0f);
        advance(motion, 600L, 1_000L);
        assertTrue(motion.amountForCharacter(0) > motion.amountForCharacter(1));
        assertTrue(motion.amountForCharacter(1) > 0f);
    }

    @Test public void wordGlowDeglowsAtDurationWithMassOneStiffnessFourteenDampingSeven() {
        WordEmphasisMotion motion = one("a", 2_000L);
        begin(motion); advance(motion, 0L, 2_000L);
        double omega = Math.PI, value = critical(2d, 2d);
        double velocity = omega * omega * 2d * Math.exp(-omega * 2d);
        assertEquals(value, motion.glowAmountForCharacter(0), 1e-6d);
        advance(motion, 2_000L, 2_200L);
        double t = 0.2d, frequency = Math.sqrt(1.75d);
        double expected = Math.exp(-3.5d * t) * (value * Math.cos(frequency * t)
                + (velocity + 3.5d * value) / frequency * Math.sin(frequency * t));
        assertEquals(expected, motion.glowAmountForCharacter(0), 1e-6d);
        // The glyph does not return until attackDelay + 2*D/glyphCount = 4.4 seconds.
        assertEquals(critical(1.8d, 2d), motion.amountForCharacter(0), 1e-6d);
    }

    @Test public void eachGlyphReturnsOnItsOwnDelayWithTheSameCriticalSpring() {
        WordEmphasisMotion motion = one("ab", 2_000L);
        begin(motion); advance(motion, 0L, 2_400L);
        double omega = Math.PI, value = critical(2d, 2d);
        double velocity = omega * omega * 2d * Math.exp(-omega * 2d);
        assertEquals(value, motion.amountForCharacter(0), 1e-6d);
        advance(motion, 2_400L, 2_600L);
        double t = 0.2d;
        double expected = (value + (velocity + omega * value) * t) * Math.exp(-omega * t);
        assertEquals(expected, motion.amountForCharacter(0), 1e-6d);
        assertEquals(critical(1.8d, 2d), motion.amountForCharacter(1), 1e-6d);
    }

    @Test public void graphemeClusterSharesAmountAndUtf16LookupRemainsSafe() {
        WordEmphasisMotion motion = one("á", 2_000L);
        begin(motion); advance(motion, 0L, 600L);
        assertTrue(motion.amountForCharacter(0) > 0f);
        assertEquals(motion.amountForCharacter(0), motion.amountForCharacter(1), 0f);
        assertEquals(0f, motion.amountForCharacter(-1), 0f);
        assertEquals(0f, motion.glowAmountForCharacter(2), 0f);
        assertEquals(-1, motion.wordEndForCharacter(2));
    }

    @Test public void languageAndProducerGatesPreventBothEmphasisChannels() {
        for (String[] item : new String[][]{{"hello", "zh-Hans"}, {"hello", "ar"},
                {"hi中", "en"}, {"hiˇ", "en"}, {"عرب", ""}}) {
            WordEmphasisMotion motion = one(item[0], 2_000L);
            motion.sample(START - 1L, 0L, true, true, item[1]);
            motion.sample(START, 1L, true, true, item[1]);
            motion.sample(START, 601L, true, true, item[1]);
            assertEquals(0f, motion.amountForCharacter(0), 0f);
            assertEquals(0f, motion.glowAmountForCharacter(0), 0f);
            assertFalse(motion.isRunning());
        }
    }

    @Test public void repeatedFramesNeverRestartAndPausedPlaybackFinishesBothChannels() {
        WordEmphasisMotion motion = one("a", 2_000L);
        begin(motion); advance(motion, 0L, 800L);
        float amount = motion.amountForCharacter(0), glow = motion.glowAmountForCharacter(0);
        for (int i = 0; i < 50; i++) motion.sample(START, 801L, true, true, "en");
        assertEquals(amount, motion.amountForCharacter(0), 0f);
        assertEquals(glow, motion.glowAmountForCharacter(0), 0f);
        advance(motion, 800L, 20_000L);
        assertEquals(0f, motion.amountForCharacter(0), 0f);
        assertEquals(0f, motion.glowAmountForCharacter(0), 0f);
        assertFalse(motion.isRunning());
    }

    @Test public void firstMidWordLargeSeekLongGapAndDisabledAnimationDoNotReplayOldEffects() {
        WordEmphasisMotion middle = one("a", 2_000L);
        middle.sample(1_500L, 0L, true, true, "en");
        middle.sample(1_600L, 100L, true, true, "en");
        assertFalse(middle.isRunning());
        for (int discontinuity = 0; discontinuity < 3; discontinuity++) {
            WordEmphasisMotion motion = one("a", 2_000L);
            begin(motion); advance(motion, 0L, 800L);
            assertTrue(motion.amountForCharacter(0) > 0f);
            long position = discontinuity == 0 ? 9_000L : START;
            long now = discontinuity == 1 ? 1_902L : 901L;
            motion.sample(position, now, true, discontinuity != 2, "en");
            assertEquals(0f, motion.amountForCharacter(0), 0f);
            assertEquals(0f, motion.glowAmountForCharacter(0), 0f);
            assertFalse(motion.isRunning());
            motion.sample(position, now + 100L, true, true, "en");
            assertFalse(motion.isRunning());
        }
    }

    @Test public void deselectionAndSmallRewindKeepPositionAndVelocityBeforeReturning() {
        for (boolean rewind : new boolean[]{false, true}) {
            WordEmphasisMotion motion = one("a", 2_000L), control = one("a", 2_000L);
            begin(motion); begin(control);
            advance(motion, 0L, 800L); advance(control, 0L, 800L);
            float value = motion.amountForCharacter(0), glow = motion.glowAmountForCharacter(0);
            long position = rewind ? START - 1L : START;
            motion.sample(position, 801L, rewind, true, "en");
            assertEquals(value, motion.amountForCharacter(0), 0f);
            assertEquals(glow, motion.glowAmountForCharacter(0), 0f);
            motion.sample(position, 802L, rewind, true, "en");
            control.sample(START, 802L, true, true, "en");
            assertEquals(control.amountForCharacter(0), motion.amountForCharacter(0), 2e-5f);
            for (long now = 902L; now < 10_000L; now += 100L)
                motion.sample(position, now, false, true, "en");
            assertEquals(0f, motion.amountForCharacter(0), 0f);
            assertEquals(0f, motion.glowAmountForCharacter(0), 0f);
            assertFalse(motion.isRunning());
        }
    }

    @Test public void exactInitialStartMayTriggerAndResetRearmsFuturePlayback() {
        WordEmphasisMotion motion = one("a", 2_000L);
        motion.sample(START, 1L, true, true, "en");
        assertTrue(motion.isRunning());
        advance(motion, 0L, 600L);
        assertTrue(motion.amountForCharacter(0) > 0f);
        motion.reset();
        assertFalse(motion.isRunning());
        assertEquals(0f, motion.glowAmountForCharacter(0), 0f);
        begin(motion); advance(motion, 0L, 600L);
        assertTrue(motion.amountForCharacter(0) > 0f);
        WordEmphasisMotion empty = new WordEmphasisMotion(null, null);
        empty.sample(0L, 0L, true, true, "en");
        assertFalse(empty.isRunning());
        assertEquals(-1, empty.wordStartForCharacter(0));
    }

    @Test public void chineseTailUsesOneSecondThresholdAndPiecewiseLinearFactor() {
        long[] durations = {999L, 1_000L, 1_250L, 1_500L, 1_800L, 2_100L, 4_000L};
        double[] factors = {0d, 0.2d, 0.4d, 0.6d, 0.8d, 1d, 1d};
        for (int i = 0; i < durations.length; i++) {
            WordEmphasisMotion motion = one("爱", durations[i]);
            begin(motion, "zh-Hans"); advance(motion, 0L, 500L, "zh-Hans");
            double duration = durations[i] / 1_000d;
            double period = Math.min(duration, 3d);
            assertEquals(factors[i] * critical(0.5d, period),
                    motion.glowAmountForCharacter(0), 1e-6d);
            double attackDelay = Math.min(duration * 0.4d, 0.4d);
            assertEquals(factors[i] * critical(0.5d - attackDelay, period),
                    motion.amountForCharacter(0), 1e-6d);
            assertEquals(durations[i] >= 1_000L, motion.isRunning());
        }
    }

    @Test public void onlyTheLastTimedHanCharacterScalesAndTrailingPunctuationDoesNot() {
        String text = "我爱你！ 」 \t";
        WordEmphasisMotion motion = new WordEmphasisMotion(text, Arrays.asList(
                new WordRange(START, 0, 1, 3_000L), new WordRange(START, 1, 2, 3_000L),
                new WordRange(START, 2, text.length(), 2_500L)));
        begin(motion, "zh"); advance(motion, 0L, 500L, "zh");
        assertEquals(0f, motion.amountForCharacter(0), 0f);
        assertEquals(0f, motion.glowAmountForCharacter(1), 0f);
        assertTrue(motion.amountForCharacter(2) > 0f);
        assertEquals(2, motion.wordStartForCharacter(2));
        assertEquals(3, motion.wordEndForCharacter(2));
        assertFalse(motion.isChineseTailForCharacter(0));
        assertFalse(motion.isChineseTailForCharacter(1));
        assertTrue(motion.isChineseTailForCharacter(2));
        for (int index = 3; index < text.length(); index++) {
            assertEquals(0f, motion.amountForCharacter(index), 0f);
            assertEquals(0f, motion.glowAmountForCharacter(index), 0f);
            assertFalse(motion.isChineseTailForCharacter(index));
        }
    }

    @Test public void separateTimedPunctuationNeverSuppliesTheHanEndClock() {
        WordEmphasisMotion motion = new WordEmphasisMotion("爱。」～", Arrays.asList(
                new WordRange(START, 0, 1, 2_000L),
                new WordRange(2_000L, 1, 4, 20_000L)));
        begin(motion, "zh"); advance(motion, 0L, 500L, "zh");
        assertEquals(0.2d * critical(0.5d, 1d), motion.glowAmountForCharacter(0), 1e-6d);
        assertEquals(1, motion.wordEndForCharacter(0));
        for (int index = 1; index < 4; index++) {
            assertEquals(0f, motion.amountForCharacter(index), 0f);
            assertEquals(0f, motion.glowAmountForCharacter(index), 0f);
        }
    }

    @Test public void mixedChineseTailAllowsChineseAndMissingLanguageButNotOtherExplicitLanguages() {
        String text = "Stay 与你。";
        for (String language : new String[]{"zh-Hans", ""}) {
            WordEmphasisMotion motion = new WordEmphasisMotion(text, Arrays.asList(
                    new WordRange(START, 0, 4, 3_000L), new WordRange(START, 5, 6, 3_000L),
                    new WordRange(START, 6, 8, 2_500L)));
            begin(motion, language); advance(motion, 0L, 500L, language);
            assertEquals(0f, motion.amountForCharacter(0), 0f);
            assertEquals(0f, motion.amountForCharacter(5), 0f);
            assertTrue(motion.amountForCharacter(6) > 0f);
            assertEquals(0f, motion.amountForCharacter(7), 0f);
        }
        for (String language : new String[]{"en", "ja", "ko", "ar", "he"}) {
            WordEmphasisMotion motion = one("爱", 1_500L);
            begin(motion, language); advance(motion, 0L, 500L, language);
            assertFalse(motion.isRunning());
            assertEquals(0f, motion.glowAmountForCharacter(0), 0f);
        }
        WordEmphasisMotion japanese = new WordEmphasisMotion("かな愛", Arrays.asList(
                new WordRange(START, 0, 2, 2_000L), new WordRange(START, 2, 3, 2_500L)));
        begin(japanese, ""); advance(japanese, 0L, 500L, "");
        assertFalse(japanese.isRunning());
    }

    @Test public void chineseTailNeverDividesMultiHanTokensOrInventsMissingEndOrCoverage() {
        WordEmphasisMotion multi = one("热爱。", 2_000L);
        WordEmphasisMotion noEnd = new WordEmphasisMotion("爱。", Collections.singletonList(
                new WordRange(START, 0, 2)));
        WordEmphasisMotion uncovered = new WordEmphasisMotion("热爱", Collections.singletonList(
                new WordRange(START, 0, 1, 3_000L)));
        for (WordEmphasisMotion motion : Arrays.asList(multi, noEnd, uncovered)) {
            begin(motion, "zh"); advance(motion, 0L, 5_000L, "zh");
            assertEquals(0f, motion.amountForCharacter(0), 0f);
            assertEquals(0f, motion.glowAmountForCharacter(1), 0f);
            assertFalse(motion.isRunning());
        }
    }

    @Test public void trailingTildesAreDecorationsButEnglishOrEmojiEndsRemainSungContent() {
        for (String text : new String[]{"爱~", "爱～"}) {
            WordEmphasisMotion motion = one(text, 1_500L);
            begin(motion, "zh"); advance(motion, 0L, 500L, "zh");
            assertTrue(motion.amountForCharacter(0) > 0f);
            assertEquals(0f, motion.amountForCharacter(1), 0f);
        }
        for (String text : new String[]{"爱A", "爱😀"}) {
            WordEmphasisMotion motion = new WordEmphasisMotion(text, Arrays.asList(
                    new WordRange(START, 0, 1, 2_500L),
                    new WordRange(START, 1, text.length(), 3_000L)));
            begin(motion, "zh"); advance(motion, 0L, 500L, "zh");
            assertFalse(motion.isRunning());
            assertEquals(0f, motion.glowAmountForCharacter(0), 0f);
        }
    }

    @Test public void englishRetainsAMFactorAndSupplementaryHanIsOneTailGlyph() {
        WordEmphasisMotion english = one("a", 1_200L);
        begin(english); advance(english, 0L, 500L);
        assertEquals(0.2d * critical(0.5d, 1.2d), english.glowAmountForCharacter(0), 1e-6d);
        String han = new String(Character.toChars(0x20000));
        WordEmphasisMotion chinese = one(han, 1_500L);
        begin(chinese, "zh"); advance(chinese, 0L, 500L, "zh");
        assertTrue(chinese.amountForCharacter(0) > 0f);
        assertEquals(chinese.amountForCharacter(0), chinese.amountForCharacter(1), 0f);
        assertEquals(2, chinese.wordEndForCharacter(0));
        assertTrue(chinese.isChineseTailForCharacter(0));
        assertTrue(chinese.isChineseTailForCharacter(1));
        assertFalse(english.isChineseTailForCharacter(0));
    }

    @Test public void chineseTailKeepsPauseSeekAndFiniteSettleLifecycle() {
        WordEmphasisMotion motion = one("爱", 1_500L);
        begin(motion, "zh"); advance(motion, 0L, 500L, "zh");
        float amount = motion.amountForCharacter(0), glow = motion.glowAmountForCharacter(0);
        for (int i = 0; i < 20; i++) motion.sample(START, 501L, true, true, "zh");
        assertEquals(amount, motion.amountForCharacter(0), 0f);
        assertEquals(glow, motion.glowAmountForCharacter(0), 0f);
        advance(motion, 500L, 12_000L, "zh");
        assertFalse(motion.isRunning());
        assertEquals(0f, motion.amountForCharacter(0), 0f);
        assertEquals(0f, motion.glowAmountForCharacter(0), 0f);
        motion.reset(); begin(motion, "zh"); advance(motion, 0L, 500L, "zh");
        motion.sample(9_000L, 601L, true, true, "zh");
        assertFalse(motion.isRunning());
        assertEquals(0f, motion.amountForCharacter(0), 0f);
        assertTrue(motion.isChineseTailForCharacter(0));
        WordEmphasisMotion middle = one("爱", 1_500L);
        middle.sample(1_500L, 0L, true, true, "zh");
        middle.sample(1_500L, 200L, true, true, "zh");
        assertFalse(middle.isRunning());
    }

    @Test public void chineseTailIdentityQueryRejectsUncoveredOrInvalidIndicesWithoutChangingState() {
        WordEmphasisMotion motion = new WordEmphasisMotion("中爱。", Arrays.asList(
                new WordRange(START, 0, 1, 3_100L), new WordRange(START, 1, 3, 2_500L)));
        assertFalse(motion.isChineseTailForCharacter(-1));
        assertFalse(motion.isChineseTailForCharacter(0));
        assertTrue(motion.isChineseTailForCharacter(1));
        assertFalse(motion.isChineseTailForCharacter(2));
        assertFalse(motion.isChineseTailForCharacter(3));
        assertFalse(motion.isChineseTailForCharacter(Integer.MAX_VALUE));
        assertFalse(motion.isRunning());
        assertEquals(0f, motion.amountForCharacter(1), 0f);
        WordEmphasisMotion uncovered = new WordEmphasisMotion("爱", Collections.emptyList());
        assertFalse(uncovered.isChineseTailForCharacter(0));
        WordEmphasisMotion empty = new WordEmphasisMotion(null, null);
        assertFalse(empty.isChineseTailForCharacter(0));
    }
    @Test public void chineseChildCanBeDisabledWithoutDisablingWesternEmphasis() {
        WordEmphasisMotion chinese = one("爱", 1_500L), english = one("love", 2_000L);
        for (WordEmphasisMotion motion : Arrays.asList(chinese, english)) {
            String language = motion == chinese ? "zh" : "en";
            motion.sample(START - 1L, 0L, true, true, language, false);
            motion.sample(START, 1L, true, true, language, false);
            for (long now = 101L; now <= 801L; now += 100L)
                motion.sample(START, now, true, true, language, false);
        }
        assertEquals(0f, chinese.amountForCharacter(0), 0f);
        assertEquals(0f, chinese.glowAmountForCharacter(0), 0f);
        assertTrue(english.amountForCharacter(0) > 0f);
        assertTrue(english.glowAmountForCharacter(0) > 0f);
    }

    @Test public void turningOffChineseChildHidesActiveScaleAndGlowImmediately() {
        WordEmphasisMotion motion = one("爱", 1_500L);
        begin(motion, "zh");
        advance(motion, 0L, 500L, "zh");
        assertTrue(motion.amountForCharacter(0) > 0f);
        assertTrue(motion.glowAmountForCharacter(0) > 0f);
        motion.sample(START, 502L, true, true, "zh", false);
        assertEquals(0f, motion.amountForCharacter(0), 0f);
        assertEquals(0f, motion.glowAmountForCharacter(0), 0f);
    }

    @Test public void reEnablingChineseChildDoesNotReplayAnAlreadyStartedWord() {
        WordEmphasisMotion motion = one("爱", 1_500L);
        motion.sample(START - 1L, 0L, true, true, "zh", false);
        motion.sample(START, 1L, true, true, "zh", false);
        motion.sample(START, 101L, true, true, "zh", true);
        motion.sample(START, 201L, true, true, "zh", true);
        assertEquals(0f, motion.amountForCharacter(0), 0f);
        assertEquals(0f, motion.glowAmountForCharacter(0), 0f);
    }

    @Test public void resettingParentClearsChineseScaleAndGlowDespiteChildPreference() {
        WordEmphasisMotion motion = one("爱", 1_500L);
        begin(motion, "zh");
        advance(motion, 0L, 500L, "zh");
        assertTrue(motion.amountForCharacter(0) > 0f);
        motion.reset();
        assertEquals(0f, motion.amountForCharacter(0), 0f);
        assertEquals(0f, motion.glowAmountForCharacter(0), 0f);
        assertFalse(motion.isRunning());
    }

}
