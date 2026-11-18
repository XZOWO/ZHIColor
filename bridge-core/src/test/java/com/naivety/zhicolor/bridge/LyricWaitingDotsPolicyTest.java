package com.naivety.zhicolor.bridge;

import com.naivety.zhicolor.bridge.render.LyricTimingMode;
import com.naivety.zhicolor.bridge.render.WordLine;
import com.naivety.zhicolor.bridge.render.WordLyricModel;
import com.naivety.zhicolor.bridge.render.WordRange;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class LyricWaitingDotsPolicyTest {
    private static WordLine wordLine(long beginMs, long endMs, String text, boolean explicitEnd) {
        ArrayList<WordRange> words = new ArrayList<>();
        words.add(new WordRange(beginMs, 0, Math.max(1, text.length()), endMs));
        WordLine line = new WordLine(beginMs, text, words, endMs, LyricTimingMode.WORD_TIMED);
        line.explicitEndTimeProvided = explicitEnd;
        return line;
    }

    private static WordLine lrcLine(long beginMs, long endMs, String text, boolean explicitEnd) {
        WordLine line = new WordLine(beginMs, text, new ArrayList<>(), endMs, LyricTimingMode.LINE_TIMED);
        line.explicitEndTimeProvided = explicitEnd;
        return line;
    }

    @Test
    public void discoversIntroAndInterludeWithAmSevenSecondThresholdAndOffset() {
        WordLyricModel model = new WordLyricModel();
        WordLine first = wordLine(8_000L, 12_000L, "First verse", true);
        WordLine shortGapNext = wordLine(19_000L, 22_000L, "No break", true); // exact 7.0s -> ignored
        WordLine afterBreak = wordLine(30_000L, 35_000L, "After break", true); // 8.0s gap -> interlude
        model.lines.add(first);
        model.lines.add(shortGapNext);
        model.lines.add(afterBreak);

        List<LyricWaitingDotsPolicy.BreakWindow> breaks = LyricWaitingDotsPolicy.breaks(model);
        assertEquals(2, breaks.size());

        LyricWaitingDotsPolicy.BreakWindow intro = breaks.get(0);
        assertTrue(intro.intro);
        assertSame(first, intro.hostLine);
        assertEquals(0L, intro.startMillis);
        assertEquals(8_000L, intro.endMillis);

        LyricWaitingDotsPolicy.BreakWindow interlude = breaks.get(1);
        assertFalse(interlude.intro);
        assertSame(afterBreak, interlude.hostLine);
        assertSame(shortGapNext, interlude.previousLine);
        assertEquals(22_100L, interlude.startMillis);
        assertEquals(30_000L, interlude.endMillis);

        assertSame(intro, LyricWaitingDotsPolicy.activeBreak(model, 5_000L));
        assertNull(LyricWaitingDotsPolicy.activeBreak(model, 15_000L));
        assertNull(LyricWaitingDotsPolicy.activeBreak(model, 22_099L));
        assertSame(interlude, LyricWaitingDotsPolicy.activeBreak(model, 22_100L));
        assertNull(LyricWaitingDotsPolicy.activeBreak(model, 30_000L));
        assertNull(LyricWaitingDotsPolicy.activeBreak(model, 45_000L));
    }

    @Test
    public void skipsPlainLrcInterludesWithoutExplicitEndOrSpacerAndHonorsSecondaryVocals() {
        WordLyricModel lrcOnly = new WordLyricModel();
        lrcOnly.lines.add(lrcLine(1_000L, 15_000L, "Long held line", false));
        lrcOnly.lines.add(lrcLine(15_000L, 20_000L, "Next", false));
        assertTrue(LyricWaitingDotsPolicy.breaks(lrcOnly).isEmpty());

        WordLyricModel withSpacer = new WordLyricModel();
        withSpacer.lines.add(lrcLine(1_000L, 5_000L, "Line one", false));
        withSpacer.lines.add(lrcLine(5_000L, 14_000L, "♪ ♪", false));
        WordLine resume = lrcLine(14_000L, 18_000L, "Resume", false);
        withSpacer.lines.add(resume);
        List<LyricWaitingDotsPolicy.BreakWindow> spacerBreaks = LyricWaitingDotsPolicy.breaks(withSpacer);
        assertEquals(1, spacerBreaks.size());
        assertSame(resume, spacerBreaks.get(0).hostLine);
        assertEquals(5_100L, spacerBreaks.get(0).startMillis);

        WordLyricModel backingVocal = new WordLyricModel();
        WordLine main = wordLine(8_000L, 12_000L, "Lead vocal", true);
        main.secondaryLine = wordLine(10_000L, 16_000L, "Backing tail", true);
        WordLine next = wordLine(22_500L, 26_000L, "Later", true);
        backingVocal.lines.add(main);
        backingVocal.lines.add(next);
        // 22.5 - 16.0 = 6.5s <= 7.0s once backing vocal explicit end is respected
        assertEquals(1, LyricWaitingDotsPolicy.breaks(backingVocal).size()); // intro only
    }

    @Test
    public void evaluatesFourAppleMusicDotPhasesAccurately() {
        long start = 10_000L;
        long end = 20_000L; // breathTrigger = 18.2s, span = 8.2s, cycles = 2, breathDuration = 2.05s, fadeIn = 2.4s/dot

        // Paused right at start keeps subtle unlit dots visible rather than invisible alpha=0
        LyricWaitingDotsPolicy.DotFrameState pausedAtStart =
                LyricWaitingDotsPolicy.evaluateDots(start, end, start, true, false);
        assertTrue(pausedAtStart.anyVisible);
        assertEquals(0.1f, pausedAtStart.opacities[0], 0.001f);

        // Staggered entry fade during playback at start + 90ms: dot 0 leading dot 1, dot 2 still waiting
        LyricWaitingDotsPolicy.DotFrameState staggered =
                LyricWaitingDotsPolicy.evaluateDots(start, end, start + 90L, true, true);
        assertTrue(staggered.opacities[0] > staggered.opacities[1]);
        assertTrue(staggered.opacities[1] > 0f);
        assertEquals(0f, staggered.opacities[2], 0.0001f);

        // Cycle-quantized AM group breathing (breathTrigger=18.2s, span=8.2s -> cycles=2, halfCycle=2.05s, hold=0.2s):
        // Holds 1.0x for first 0.2s, rises to 1.2x by ~2.0s, drops to 0.9x by ~4.0s,
        // rises to 1.2x by ~6.0s, and rests at 0.9x before 18.2s exit.
        LyricWaitingDotsPolicy.DotFrameState atStart =
                LyricWaitingDotsPolicy.evaluateDots(start, end, start, true, true);
        assertEquals(1.0f, atStart.scales[0], 0.001f);
        assertEquals(atStart.scales[0], atStart.scales[1], 0.0001f);
        assertEquals(atStart.scales[0], atStart.scales[2], 0.0001f);

        LyricWaitingDotsPolicy.DotFrameState initialHold =
                LyricWaitingDotsPolicy.evaluateDots(start, end, start + 150L, true, true);
        assertEquals(1.0f, initialHold.scales[0], 0.001f);

        LyricWaitingDotsPolicy.DotFrameState firstPeak =
                LyricWaitingDotsPolicy.evaluateDots(start, end, start + 2_000L, true, true);
        assertEquals(1.20f, firstPeak.scales[0], 0.005f);
        assertEquals(firstPeak.scales[0], firstPeak.scales[1], 0.0001f);
        assertEquals(firstPeak.scales[0], firstPeak.scales[2], 0.0001f);

        // Countdown progressive fill during breath: at start + 1_400ms dot 0 leads dot 1 while all share group scale
        LyricWaitingDotsPolicy.DotFrameState fillProgress =
                LyricWaitingDotsPolicy.evaluateDots(start, end, start + 1_400L, true, true);
        assertTrue(fillProgress.opacities[0] > 0.1f);
        assertEquals(0.1f, fillProgress.opacities[1], 0.001f);
        assertEquals(fillProgress.scales[0], fillProgress.scales[1], 0.0001f);
        assertEquals(fillProgress.scales[0], fillProgress.scales[2], 0.0001f);

        // First trough holds 0.90x around halfCycle*2 (start + 4.0s) and second peak reaches 1.20x around start + 6.0s
        LyricWaitingDotsPolicy.DotFrameState firstTrough =
                LyricWaitingDotsPolicy.evaluateDots(start, end, start + 4_000L, true, true);
        assertEquals(0.90f, firstTrough.scales[0], 0.005f);
        assertEquals(firstTrough.scales[0], firstTrough.scales[1], 0.0001f);
        assertEquals(firstTrough.scales[0], firstTrough.scales[2], 0.0001f);

        LyricWaitingDotsPolicy.DotFrameState secondPeak =
                LyricWaitingDotsPolicy.evaluateDots(start, end, start + 6_000L, true, true);
        assertEquals(1.20f, secondPeak.scales[0], 0.005f);
        assertEquals(secondPeak.scales[0], secondPeak.scales[1], 0.0001f);
        assertEquals(secondPeak.scales[0], secondPeak.scales[2], 0.0001f);

        // Right before breathTrigger (end - 1850ms): all 3 dots filled to 1.0 and resting at 0.90x
        LyricWaitingDotsPolicy.DotFrameState preExit =
                LyricWaitingDotsPolicy.evaluateDots(start, end, end - 1_850L, true, true);
        assertEquals(1f, preExit.opacities[0], 0.001f);
        assertEquals(1f, preExit.opacities[1], 0.001f);
        assertEquals(1f, preExit.opacities[2], 0.001f);
        for (int i = 0; i < 3; i++) {
            assertEquals(0.90f, preExit.scales[i], 0.005f);
        }
        assertEquals(4.1, LyricWaitingDotsPolicy.breathWavePeriodSeconds(start, end), 0.001);

        // Phase 4 swell (end - 800ms = breathTrigger + 1.0s): opacity still 1.0, scale peaked at 1.2
        LyricWaitingDotsPolicy.DotFrameState swellPeak =
                LyricWaitingDotsPolicy.evaluateDots(start, end, end - 800L, true, true);
        assertEquals(1f, swellPeak.opacities[0], 0.001f);
        assertEquals(1.2f, swellPeak.scales[0], 0.01f);

        // Fade finishes by end - 500ms, shrink completes to 0.2 by end - 300ms
        LyricWaitingDotsPolicy.DotFrameState afterFade =
                LyricWaitingDotsPolicy.evaluateDots(start, end, end - 450L, true, true);
        assertEquals(0f, afterFade.opacities[0], 0.001f);
        assertTrue(afterFade.scales[0] < 0.8f && afterFade.scales[0] >= 0.2f);

        LyricWaitingDotsPolicy.DotFrameState afterShrink =
                LyricWaitingDotsPolicy.evaluateDots(start, end, end - 250L, true, true);
        assertFalse(afterShrink.anyVisible);
        assertEquals(0.2f, afterShrink.scales[0], 0.001f);
    }

    @Test
    public void motionOffKeepsStaticDotsAndFocusSeparationBlursOnlyWhenConfigured() {
        LyricWaitingDotsPolicy.DotFrameState staticDots =
                LyricWaitingDotsPolicy.evaluateDots(0L, 10_000L, 2_000L, false, false);
        assertTrue(staticDots.anyVisible);
        for (float scale : staticDots.scales) {
            assertEquals(1f, scale, 0f);
        }

        LyricUiConfig blurOn = LyricUiConfig.defaults().buildUpon()
                .blurEnabled(true).blurRadiusTenthsPx(80)
                .scaleEnabled(true).inactiveScalePercent(85).build();
        LyricUiConfig blurOff = LyricUiConfig.defaults().buildUpon()
                .blurEnabled(false).blurRadiusTenthsPx(80)
                .scaleEnabled(false).build();

        assertEquals(0f, LyricWaitingDotsPolicy.deselectedBodyBlurRadius(blurOff, false, 4f, 1f), 0f);
        assertEquals(0f, LyricWaitingDotsPolicy.deselectedBodyBlurRadius(blurOn, true, 4f, 1f), 0f);
        assertEquals(0f, LyricWaitingDotsPolicy.deselectedBodyBlurRadius(blurOn, false, 4f, 0f), 0f);
        float activeBlur = LyricWaitingDotsPolicy.deselectedBodyBlurRadius(blurOn, false, 0f, 1f);
        assertEquals(4f, activeBlur, 0.01f);
        assertEquals(2f, LyricWaitingDotsPolicy.deselectedBodyBlurRadius(blurOn, false, 0f, 0.5f), 0.01f);

        assertEquals(0.85f, LyricWaitingDotsPolicy.deselectedBodyScale(blurOn, 1f), 0.001f);
        assertEquals(1f, LyricWaitingDotsPolicy.deselectedBodyScale(blurOn, 0f), 0.001f);
        assertEquals(1f, LyricWaitingDotsPolicy.deselectedBodyScale(blurOff, 1f), 0.001f);
    }

    @Test
    public void dotHorizontalPositionsRespectAlignmentAndRtl() {
        float textSize = 36f;
        float pitch = textSize * LyricWaitingDotsPolicy.DOT_PITCH_EM;
        float left = 24f;
        float width = 300f;

        float c0 = LyricWaitingDotsPolicy.dotCenterX(0, LyricUiConfig.ALIGN_CENTER, false, left, width, textSize);
        float c1 = LyricWaitingDotsPolicy.dotCenterX(1, LyricUiConfig.ALIGN_CENTER, false, left, width, textSize);
        float c2 = LyricWaitingDotsPolicy.dotCenterX(2, LyricUiConfig.ALIGN_CENTER, false, left, width, textSize);
        assertEquals(left + width * 0.5f, c1, 0.001f);
        assertEquals(pitch, c1 - c0, 0.001f);
        assertEquals(pitch, c2 - c1, 0.001f);

        float ltr0 = LyricWaitingDotsPolicy.dotCenterX(0, LyricUiConfig.ALIGN_START, false, left, width, textSize);
        float ltr2 = LyricWaitingDotsPolicy.dotCenterX(2, LyricUiConfig.ALIGN_START, false, left, width, textSize);
        assertTrue(ltr0 > left && ltr2 > ltr0);

        float rtl0 = LyricWaitingDotsPolicy.dotCenterX(0, LyricUiConfig.ALIGN_START, true, left, width, textSize);
        float rtl2 = LyricWaitingDotsPolicy.dotCenterX(2, LyricUiConfig.ALIGN_START, true, left, width, textSize);
        assertTrue(rtl0 < left + width && rtl2 < rtl0);
    }

    @Test
    public void injectsStandaloneIntroAndInterludeRowsIntoOfficialListAndModel() throws Exception {
        String rawWordLyric =
                "[00:10.000]<00:10.000>First <00:12.000>line<00:15.000>\n"
                        + "[00:26.000]<00:26.000>Second <00:28.000>line<00:31.000>";
        String preDisplay = "[00:10.000]First line\n[00:26.000]Second line";
        String json = new org.json.JSONObject()
                .put(LyricInfoContract.JSON_LYRIC, preDisplay)
                .put(LyricInfoContract.JSON_RAW_LYRIC, rawWordLyric)
                .toString();

        LyricInfoContract.NormalizedPayload pre = LyricInfoContract.parseLyricInfo(json);
        LyricInfoContract.NormalizedPayload normalized =
                LyricInfoContract.normalizeOfficialLyricInfo(pre.lyricInfo);
        // OplusLyricNormalizer pre-rolled [00:10.000] down to [00:00.000]
        assertTrue(normalized.payload.lyric.startsWith("[00:00.000]First line"));

        LyricInfoContract.NormalizedPayload injected =
                LyricWaitingDotsPolicy.injectOfficialWaitingDotsRows(pre.payload, normalized);
        String expectedOfficial =
                "[00:00.000]" + LyricTextSanitizer.WAITING_DOTS_MARKER + "\n"
                        + "[00:10.000]First line\n"
                        + "[00:15.100]" + LyricTextSanitizer.WAITING_DOTS_MARKER + "\n"
                        + "[00:26.000]Second line\n"
                        + "[00:34.000]" + LyricTextSanitizer.ZERO_WIDTH_SPACE;
        assertEquals(expectedOfficial, injected.payload.lyric);

        WordLyricModel model = new NativeLyricModelAssembler(null).assemble(
                injected.payload.rawLyric,
                true,
                injected.payload.lyric,
                true,
                "",
                injected.payload.lineMetadata).model;
        assertEquals(5, model.officialLines.size());
        assertEquals(4, model.lines.size());
        assertTrue(model.lines.get(0).isWaitingDots);
        assertEquals(0L, model.lines.get(0).timeMillis);
        assertEquals(10_000L, model.lines.get(0).endTimeMillis);
        assertEquals("First line", model.lines.get(1).text);
        assertTrue(model.lines.get(2).isWaitingDots);
        assertEquals(15_100L, model.lines.get(2).timeMillis);
        assertEquals(26_000L, model.lines.get(2).endTimeMillis);
        assertEquals("Second line", model.lines.get(3).text);
        assertNull(model.officialLines.get(4)); // tail spacer stays null placeholder

        assertSame(model.lines.get(1), model.firstDisplayLine());
        assertTrue(model.isBeforeFirstProgressStart(2_000L));
        assertSame(model.lines.get(0), LyricWaitingDotsPolicy.activeWaitingDotsLine(model, 2_000L));
        assertNull(LyricWaitingDotsPolicy.activeWaitingDotsLine(model, 12_000L));
        assertSame(model.lines.get(2), LyricWaitingDotsPolicy.activeWaitingDotsLine(model, 18_000L));
        assertNull(LyricWaitingDotsPolicy.activeWaitingDotsLine(model, 26_000L));

        // Structured lineMetadata (e.g. Lyricon/QM/NCM JSON) with explicit line end triggers interlude
        // and propagates host alignment (e.g. left=0 / right=2) onto standalone waiting-dots rows
        org.json.JSONArray metadata = new org.json.JSONArray()
                .put(new org.json.JSONObject()
                        .put("begin", 10_000L).put("end", 15_000L).put("text", "First line")
                        .put("alignment", LyricUiConfig.ALIGN_START).put("alignmentProvided", true)
                        .put("words", new org.json.JSONArray()
                                .put(new org.json.JSONObject().put("begin", 10_000L).put("end", 15_000L).put("text", "First line"))))
                .put(new org.json.JSONObject()
                        .put("begin", 26_000L).put("end", 31_000L).put("text", "Second line")
                        .put("alignment", LyricUiConfig.ALIGN_END).put("alignmentProvided", true)
                        .put("words", new org.json.JSONArray()
                                .put(new org.json.JSONObject().put("begin", 26_000L).put("end", 31_000L).put("text", "Second line"))));
        String structuredJson = new org.json.JSONObject()
                .put(LyricInfoContract.JSON_LYRIC, preDisplay)
                .put(LyricInfoContract.JSON_RAW_LYRIC, preDisplay)
                .put(LyricInfoContract.JSON_LINE_METADATA, metadata)
                .toString();
        LyricInfoContract.NormalizedPayload structuredPre = LyricInfoContract.parseLyricInfo(structuredJson);
        LyricInfoContract.NormalizedPayload structuredInjected =
                LyricWaitingDotsPolicy.injectOfficialWaitingDotsRows(
                        structuredPre.payload,
                        LyricInfoContract.normalizeOfficialLyricInfo(structuredPre.lyricInfo));
        assertEquals(expectedOfficial, structuredInjected.payload.lyric);
        WordLyricModel structuredModel = new NativeLyricModelAssembler(null).assemble(
                structuredInjected.payload.rawLyric,
                true,
                structuredInjected.payload.lyric,
                true,
                "",
                structuredInjected.payload.lineMetadata).model;
        assertEquals(LyricUiConfig.ALIGN_START, structuredModel.lines.get(0).sourceAlignment);
        assertTrue(structuredModel.lines.get(0).sourceAlignmentProvided);
        assertEquals(LyricUiConfig.ALIGN_END, structuredModel.lines.get(2).sourceAlignment);
        assertTrue(structuredModel.lines.get(2).sourceAlignmentProvided);

        // Sentence-window snapshot payloads remain untouched
        String snapshotJson = new org.json.JSONObject()
                .put(LyricInfoContract.JSON_LYRIC, "[00:00.000]First line\n[00:01.000]Second line")
                .put(LyricInfoContract.JSON_RAW_LYRIC, rawWordLyric)
                .put("displayMode", "sentence-window-v1")
                .put("currentLine", 0)
                .toString();
        LyricInfoContract.NormalizedPayload snapPre = LyricInfoContract.parseLyricInfo(snapshotJson);
        LyricInfoContract.NormalizedPayload snapNorm =
                LyricInfoContract.normalizeOfficialLyricInfo(snapPre.lyricInfo);
        assertSame(snapNorm, LyricWaitingDotsPolicy.injectOfficialWaitingDotsRows(snapPre.payload, snapNorm));

        assertTrue(LyricWaitingDotsPolicy.isInBreakWindow(model.lines.get(0), 2_000L));
        assertFalse(LyricWaitingDotsPolicy.isInBreakWindow(model.lines.get(0), 10_000L));
        assertFalse(LyricWaitingDotsPolicy.isInBreakWindow(model.lines.get(1), 12_000L));

        // Animated bright mode starts collapsing 400ms ahead of endTimeMillis right after exit fade;
        // static/AOD mode keeps the row open through [timeMillis, endTimeMillis).
        assertTrue(LyricWaitingDotsPolicy.shouldExpandWaitingDotsLayout(model.lines.get(0), 9_599L, true));
        assertFalse(LyricWaitingDotsPolicy.shouldExpandWaitingDotsLayout(model.lines.get(0), 9_600L, true));
        assertTrue(LyricWaitingDotsPolicy.shouldExpandWaitingDotsLayout(model.lines.get(0), 9_600L, false));
        assertFalse(LyricWaitingDotsPolicy.shouldExpandWaitingDotsLayout(model.lines.get(0), 10_000L, false));
    }

    @Test
    public void waitingDotsLayoutValueSupportsInterruptibleCollapseAndAppleSpring() {
        WordLine dots = WordLine.waitingDots(10_000L, 20_000L);
        dots.waitingDotsLayout.reset(1f);
        dots.waitingDotsLayoutInitialized = true;
        assertEquals(1f, dots.waitingDotsLayout.value(100L), 0.0001f);

        dots.waitingDotsLayout.retarget(0f, 100L, 680L, true, true);
        assertEquals(0f, dots.waitingDotsLayout.target(), 0.0001f);
        assertEquals(1f, dots.waitingDotsLayout.value(100L), 0.0001f);
        assertTrue(dots.waitingDotsLayout.isRunning(100L));
        float mid = dots.waitingDotsLayout.value(440L);
        assertTrue(mid > 0f && mid < 1f);
        assertEquals(0f, dots.waitingDotsLayout.value(780L), 0.0001f);
        assertFalse(dots.waitingDotsLayout.isRunning(780L));

        assertEquals(0f, LyricWaitingDotsPolicy.expandCollapseCurve(LyricUiConfig.MOTION_APPLE, 0f), 0.0001f);
        assertEquals(1f, LyricWaitingDotsPolicy.expandCollapseCurve(LyricUiConfig.MOTION_APPLE, 1f), 0.0001f);
        float curveMid = LyricWaitingDotsPolicy.expandCollapseCurve(LyricUiConfig.MOTION_APPLE, 0.5f);
        assertTrue(curveMid > 0.5f && curveMid <= 1f);
    }
}
