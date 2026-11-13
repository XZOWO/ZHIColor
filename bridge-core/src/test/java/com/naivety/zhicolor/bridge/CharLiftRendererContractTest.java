package com.naivety.zhicolor.bridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Source contract for the per-character lift draw path. These invariants live in
 * {@code OfficialLyricTextRenderer}, which needs a real Canvas and cannot be
 * exercised by the JVM suite, so they are pinned here the same way
 * {@link LyricUiConfigContractTest} pins renderer config consumption.
 */
public final class CharLiftRendererContractTest {

    @Test
    public void liftIsGatedOffUnderAodLowFrameRate() throws Exception {
        String module = module();
        int gate = module.indexOf("private void beginCharLiftLine(");
        assertTrue("beginCharLiftLine is missing", gate >= 0);
        String body = module.substring(gate, gate + 3_000);
        assertTrue("lift must bail out on AOD low frame rate", body.contains("aodLowFrameRateMode"));
        assertTrue("ordinary lift must have its own toggle and strength gate",
                body.contains("boolean liftEnabled = charLiftEnabled && charLiftStrengthPercent > 0"));
        assertTrue("emphasis must have an independent enabled state",
                body.contains("boolean emphasisEnabled = uiConfig.wordEmphasisEnabled"));
        assertTrue("both channels off must stop the combined path",
                body.contains("(!liftEnabled && !emphasisEnabled)"));
        assertTrue(
                "lift must bail out for a timestamp-highlighted short row",
                body.contains("shouldUseTimestampHighlight"));
        assertTrue(
                "WORD_TIMED lift must remain allowed",
                body.contains("line.timingMode == LyricTimingMode.WORD_TIMED"));
        assertTrue(
                "line-only lift requires the enabled line-progress path",
                body.contains("line.timingMode == LyricTimingMode.LINE_TIMED && !lineTimedProgressEnabled"));
        assertTrue(body.contains("resolveLineProgressLift(model, line, text, compact).motion"));
        assertTrue(body.contains("line.resetLineProgressLift()"));
    }

    @Test
    public void lineOnlyRevealDoesNotDependOnASyntheticWordOrUseFullRowFallback() throws Exception {
        String module = module();
        int start = module.indexOf("private float resolveWordRevealWidthForSegment(");
        int end = module.indexOf("private boolean prepareSyllableLiftSegment(", start);
        String body = module.substring(start, end);
        assertTrue(body.indexOf("LineTimedProgress.revealWidth(") < body.indexOf("if (word == null)"));
        assertTrue(body.contains("drawLine == compactLiftLine"));
        assertTrue(module.contains("if (activeWord == null && line.timingMode != LyricTimingMode.LINE_TIMED)"));
        assertFalse(module.contains("if (activeLine && line.timingMode != LyricTimingMode.WORD_TIMED)"));
    }

    @Test
    public void lineTimedLiftUsesTheLineProgressDisplayEnd() throws Exception {
        String module = module();
        int start = module.indexOf("private long resolveCharLiftLineRevealEnd(");
        assertTrue("resolveCharLiftLineRevealEnd is missing", start >= 0);
        String body = module.substring(start, start + 1_000);
        assertTrue(
                "line-timed rows must select their display-end anchor",
                body.contains("line.timingMode == LyricTimingMode.LINE_TIMED"));
        assertTrue(
                "line-timed rows must end where their linear reveal ends",
                body.contains("return resolveLineDisplayEndMillis(model, line);"));
        assertTrue(
                "word-timed rows must retain their word-reveal anchor",
                body.contains("WordLyricRenderSupport.wordRevealEndMillis("));
    }

    @Test
    public void liftedDrawingNeverEscapesToTheCoordinator() throws Exception {
        String module = module();
        int start = module.indexOf("private void drawLiftedGraphemes(");
        assertTrue("drawLiftedGraphemes is missing", start >= 0);
        int end = module.indexOf("private float[] resolveGraphemePrefixWidths(", start);
        assertTrue("drawLiftedGraphemes body not found", end > start);
        String body = module.substring(start, end);
        assertTrue(
                "a lifted draw failure must be swallowed, not raised",
                body.contains("catch (RuntimeException"));
        assertTrue(
                "a lifted draw failure must stop further lifting",
                body.contains("charLiftUnavailable = true"));
        assertTrue(
                "the lifted pass must restore by count, never canvas.restore()",
                body.contains("canvas.restoreToCount("));
    }

    @Test
    public void sungCharactersAreLeftStandingWithNothingToDecay() throws Exception {
        String module = module();
        assertFalse(
                "the bell model's settle decay has no place in a step model",
                module.contains("revealDecay"));
        assertFalse(module.contains("CHAR_LIFT_SETTLE_TAIL_MS"));
        assertFalse(
                "incoming text must not sink after activation",
                module.contains("CharLiftGeometry.sinkRamp("));
        assertFalse(module.contains("charLift.ramp"));
        assertFalse(
                "highlight activation must not multiply the geometric displacement",
                module.contains("maxSink *= line.highlightExit.liftActivationAmount()"));
    }

    @Test
    public void sourceSyllableSpringIsSeparateFromProgressAndExitClocks() throws Exception {
        String module = module();
        int start = module.indexOf("private void beginCharLiftLine(");
        int end = module.indexOf("private long resolveCharLiftLineRevealEnd(", start);
        String body = module.substring(start, end);
        assertTrue(body.contains("line.syllableLift.sample(position, lyricAnimationTimeMillis(), selected, true)"));
        assertTrue(body.contains("SyllableLiftMotion.LIFT_DP"));
        assertFalse(body.contains("resolveLineFlowFront"));
        assertFalse(body.contains("finishOvershoot"));
        assertFalse(body.contains("findWordIndex"));
        assertFalse(module.contains("CharLiftGeometry.sinkFor("));
        assertFalse(module.contains("completionPosition += WordLyricRenderConstants.CHAR_LIFT_FINISH_MS"));
        assertTrue(module.contains("state.liftSelected = sourceSelected"));
        assertTrue(module.contains("state.liftSelected,"));
    }

    @Test
    public void finiteSpringOwnsFramesAndCacheResetDoesNotReplayIt() throws Exception {
        String module = module();
        assertTrue(module.contains("charLift.motion.isRunning() || charLift.emphasis.isRunning()"));
        assertFalse(module.contains("CharLiftGeometry.isWaveRunning("));
        int start = module.indexOf("void clearGlowCache() {");
        String body = module.substring(start, start + 900);
        assertFalse(body.contains("syllableLift.reset()"));
        assertTrue(module.contains("charLift.boundaries = cache.boundaries"));
    }

    @Test
    public void graphemeGeometryIsResolvedOnlyThroughTheCache() throws Exception {
        String module = module();
        assertEquals(
                "grapheme boundaries must be walked on a cache miss and nowhere else",
                2,
                countOf(module, "CharLiftGeometry.graphemeBoundaries("));
        assertTrue(module.contains("private final GraphemeLayoutCache[] graphemeLayoutCaches"));
    }

    @Test
    public void compactAndAllInkLayersShareSyllableTransforms() throws Exception {
        String module = module();
        int compact = module.indexOf("private void drawCompactLine(");
        int end = module.indexOf("private void fitSingleLineText(", compact);
        String body = module.substring(compact, end);
        assertTrue(body.contains("beginCharLiftLine(model, line, textView, line.text, liftPosition, sourceSelected, true)"));
        assertTrue(body.contains("drawSegment(canvas, model, line, line.text, compactLiftLine"));
        assertTrue(module.contains("drawSyllableLiftLayer(canvas, line, text, drawLine, x, y, basePaint,"));
        assertTrue(module.contains("segmentWidth, glowRevealWidth, 2)"));
        assertTrue(module.contains("canvas.translate(outwardOffset, -lift)"));
    }

    @Test
    public void lineProgressLiftOwnsPersistentLayoutsAndKeepsTheExitHighlightClock() throws Exception {
        String module = module();
        assertTrue(module.contains("compact ? line.compactLineProgressLift : line.lineProgressLift"));
        assertTrue(module.contains("cached.matches(line.timeMillis, end, textSizeKey, typeface,"));
        assertTrue(module.contains("liftSelected |= exitingHighlight"));
        assertTrue(module.contains("sourceSelected |= line.highlightExit.isExiting()"));
        assertTrue(module.contains("beforeWidth += widths[segment++]"));
        assertTrue(module.contains("charLift.motion.sample(position, lyricAnimationTimeMillis(), selected, true)"));
    }

    @Test
    public void clearingTheGlowCacheAlsoClearsLiftState() throws Exception {
        String module = module();
        int start = module.indexOf("void clearGlowCache() {");
        assertTrue("clearGlowCache is missing", start >= 0);
        String body = module.substring(start, start + 900);
        assertTrue(body.contains("for (GraphemeLayoutCache cache : graphemeLayoutCaches)"));
        assertTrue(body.contains("charLift.reset()"));
        assertTrue(
                "a rebind must give the effect another chance",
                body.contains("charLiftUnavailable = false"));
    }

    @Test
    public void emphasisHasIndependentScaleGlowAndFiniteScheduling() throws Exception {
        String module = module();
        assertTrue(module.contains("if (emphasisEnabled) line.wordEmphasis.sample("));
        assertTrue(module.contains("line.sourceLanguage, uiConfig.chineseTailEmphasisEnabled)"));
        assertTrue(module.contains("charLift.emphasis.amountForCharacter"));
        assertTrue(module.contains("charLift.emphasis.glowAmountForCharacter"));
        assertTrue(module.contains("canvas.scale(scale, scale, pivotX, pivotY)"));
        assertTrue(module.contains("emphasisGlowSegmentCaches"));
        assertTrue(module.contains("charLift.emphasisGlowRadius = dp(textView.getContext(), WordEmphasisGeometry.EMPHASIS_GLOW_RADIUS_DP)"));
        assertTrue(module.contains("line.wordEmphasis.reset()"));
    }

    @Test
    public void emphasisAloneKeepsScaleGlowAndFrameSchedulingWithoutOrdinaryLift() throws Exception {
        String source = module();
        int start = source.indexOf("private void beginCharLiftLine(");
        int end = source.indexOf("private LineProgressLiftMotion resolveLineProgressLift(", start);
        String body = source.substring(start, end);
        assertTrue(body.contains("if (liftEnabled) line.syllableLift.sample("));
        assertTrue(body.contains("else line.syllableLift.reset()"));
        assertTrue(body.contains("if (emphasisEnabled) line.wordEmphasis.sample("));
        assertTrue(body.contains("else line.wordEmphasis.reset()"));
        assertTrue(body.contains("? LyricWakeTransitionPolicy.liftAmount(1f, frameAodAmount) : 0f"));
        assertTrue(body.contains("charLift.maxLift = liftEnabled ?"));
        assertTrue(body.contains("|| charLift.emphasisStrength > CHAR_LIFT_FRAME_EPSILON"));
        assertTrue(source.contains("charLift.motion.isRunning() || charLift.emphasis.isRunning()"));
        assertTrue(source.contains("if (!uiConfig.glowEnabled || uiConfig.glowIntensityPercent <= 0) return"));
        assertTrue(source.contains("canvas.scale(scale, scale, pivotX, pivotY)"));
    }

    private static int countOf(String source, String needle) {
        int count = 0;
        for (int at = source.indexOf(needle); at >= 0; at = source.indexOf(needle, at + 1)) {
            count++;
        }
        return count;
    }

    private static String module() throws Exception {
        return readProjectFile(
                "bridge-core/src/main/java/com/naivety/zhicolor/bridge/LockscreenLyricsModule.java");
    }

    private static String readProjectFile(String relativePath) throws Exception {
        File direct = new File(relativePath);
        File file = direct.isFile()
                ? direct
                : new File(".." + File.separator + relativePath);
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
}
