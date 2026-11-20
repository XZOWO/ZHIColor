package com.naivety.zhicolor.bridge;

import com.naivety.zhicolor.bridge.render.LyricTimingMode;
import com.naivety.zhicolor.bridge.render.WordLine;
import com.naivety.zhicolor.bridge.render.WordRange;
import org.junit.Test;
import java.util.ArrayList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class LyricSecondaryLayoutPolicyTest {
    @Test public void currentOnlyCollapsesInactiveTranslationAndItsSpace() {
        float hidden = LyricSecondaryLayoutPolicy.translationAmount(false, 1f, 0f);
        assertEquals(0f, hidden, 0f);
        assertEquals(40f, LyricSecondaryLayoutPolicy.groupHeight(40f, 20f, hidden, 0f, 0f), 0f);
        float opening = LyricSecondaryLayoutPolicy.translationAmount(false, 1f, 0.5f);
        assertEquals(50f, LyricSecondaryLayoutPolicy.groupHeight(40f, 20f, opening, 0f, 0f), 0f);
    }

    @Test public void allLinesHaveStaticTranslationsEvenWhenUnfocused() {
        assertEquals(1f, LyricSecondaryLayoutPolicy.translationAmount(true, 1f, 0f), 0f);
        assertEquals(1f, LyricSecondaryLayoutPolicy.translationAmount(true, 1f, 0.5f), 0f);
        assertEquals(60f, LyricSecondaryLayoutPolicy.groupHeight(40f, 20f, 1f, 20f, 0f), 0f);
        assertEquals(70f, LyricSecondaryLayoutPolicy.groupHeight(40f, 20f, 1f, 20f, 0.5f), 0f);
    }

    @Test public void translationToggleDoesNotRemoveBackingVocalsOrTheirSpace() {
        for (boolean all : new boolean[]{false, true}) {
            float disabled = LyricSecondaryLayoutPolicy.translationAmount(all, 0f, 1f);
            assertEquals(60f, LyricSecondaryLayoutPolicy.groupHeight(40f, 20f, disabled, 20f, 1f), 0f);
        }
    }

    @Test public void simultaneousTranslationAndBackingVocalsReserveBothRows() {
        assertEquals(80f, LyricSecondaryLayoutPolicy.groupHeight(40f, 20f, 1f, 20f, 1f), 0f);
    }

    @Test public void defaultUsesPerLineDirectionWhileExplicitChoicesOverrideIt() {
        for (int source = 0; source < 3; source++) {
            assertEquals(source, LyricSecondaryLayoutPolicy.alignment(LyricUiConfig.ALIGN_DEFAULT, source));
            for (int explicit = 0; explicit < 3; explicit++) {
                assertEquals(explicit, LyricSecondaryLayoutPolicy.alignment(explicit, source));
            }
        }
    }

    @Test public void secondaryPositionPrefersExplicitMetadataAndFallsBackToStrictStartComparison() {
        WordLine explicitTop = wordTimedLine(1000L, 1200L, 2000L);
        explicitTop.backgroundPosition = "top";
        assertTrue(LyricSecondaryLayoutPolicy.isSecondaryAboveMain(explicitTop));

        WordLine explicitAbove = wordTimedLine(1000L, 1200L, 2000L);
        explicitAbove.backgroundPosition = " Above ";
        assertTrue(LyricSecondaryLayoutPolicy.isSecondaryAboveMain(explicitAbove));

        WordLine explicitBottom = wordTimedLine(2000L, 2000L, 1000L);
        explicitBottom.backgroundPosition = "bottom";
        assertFalse(LyricSecondaryLayoutPolicy.isSecondaryAboveMain(explicitBottom));

        WordLine earlierWordVocal = wordTimedLine(2000L, 2100L, 1800L);
        earlierWordVocal.backgroundPosition = "unknown";
        assertTrue(LyricSecondaryLayoutPolicy.isSecondaryAboveMain(earlierWordVocal));

        WordLine simultaneousWordVocal = wordTimedLine(2000L, 2100L, 2100L);
        assertFalse(LyricSecondaryLayoutPolicy.isSecondaryAboveMain(simultaneousWordVocal));

        WordLine laterWordVocal = wordTimedLine(2000L, 2100L, 2400L);
        assertFalse(LyricSecondaryLayoutPolicy.isSecondaryAboveMain(laterWordVocal));

        WordLine emptyWordsParent = new WordLine(2000L, "main", new ArrayList<>(), 3000L, LyricTimingMode.WORD_TIMED);
        emptyWordsParent.secondaryLine = wordTimedLine(1000L, 1000L, 1000L).secondaryLine;
        assertFalse(LyricSecondaryLayoutPolicy.isSecondaryAboveMain(emptyWordsParent));

        WordLine earlierLineTimed = new WordLine(2000L, "main", new ArrayList<>(), 3000L, LyricTimingMode.LINE_TIMED);
        earlierLineTimed.secondaryLine = new WordLine(1500L, "bg", new ArrayList<>(), 2500L, LyricTimingMode.LINE_TIMED);
        assertTrue(LyricSecondaryLayoutPolicy.isSecondaryAboveMain(earlierLineTimed));

        WordLine equalLineTimed = new WordLine(2000L, "main", new ArrayList<>(), 3000L, LyricTimingMode.LINE_TIMED);
        equalLineTimed.secondaryLine = new WordLine(2000L, "bg", new ArrayList<>(), 2500L, LyricTimingMode.LINE_TIMED);
        assertFalse(LyricSecondaryLayoutPolicy.isSecondaryAboveMain(equalLineTimed));

        WordLine secondaryOnlyTop = wordTimedLine(1000L, 1200L, 2000L);
        secondaryOnlyTop.backgroundPosition = "unknown";
        secondaryOnlyTop.secondaryLine.backgroundPosition = "top";
        assertTrue(LyricSecondaryLayoutPolicy.isSecondaryAboveMain(secondaryOnlyTop));

        WordLine secondaryOnlyBottom = wordTimedLine(2000L, 2100L, 1500L);
        secondaryOnlyBottom.backgroundPosition = "unknown";
        secondaryOnlyBottom.secondaryLine.backgroundPosition = "bottom";
        assertFalse(LyricSecondaryLayoutPolicy.isSecondaryAboveMain(secondaryOnlyBottom));
    }

    @Test public void secondaryLayoutMismatchDetectsUninitializedTargetChangeAndStalledPump() {
        assertTrue(LyricSecondaryLayoutPolicy.hasSecondaryLayoutMismatch(false, 0f, 0f, false, false));
        assertTrue(LyricSecondaryLayoutPolicy.hasSecondaryLayoutMismatch(true, 0f, 1f, false, false));
        assertTrue(LyricSecondaryLayoutPolicy.hasSecondaryLayoutMismatch(true, 1f, 0f, false, false));
        assertTrue(LyricSecondaryLayoutPolicy.hasSecondaryLayoutMismatch(true, 1f, 1f, true, true));
        assertFalse(LyricSecondaryLayoutPolicy.hasSecondaryLayoutMismatch(true, 1f, 1f, true, false));
        assertFalse(LyricSecondaryLayoutPolicy.hasSecondaryLayoutMismatch(true, 1f, 1f, false, true));
        assertFalse(LyricSecondaryLayoutPolicy.hasSecondaryLayoutMismatch(true, 0f, 0f, false, false));
    }

    @Test public void appleStyleShowsVocalOnParentFocusAndUsesNinetyPercentFloor() {
        WordLine parent = wordTimedLine(1000L, 1000L, 3000L);
        assertFalse(LyricSecondaryLayoutPolicy.isVocalVisible(parent, true, 1200L, false));
        assertTrue(LyricSecondaryLayoutPolicy.isVocalVisible(parent, true, 2700L, false));
        assertTrue(LyricSecondaryLayoutPolicy.isVocalVisible(parent, true, 1000L, true));
        assertFalse(LyricSecondaryLayoutPolicy.isVocalVisible(parent, false, 3200L, true));

        assertEquals(0.9f, LyricSecondaryLayoutPolicy.secondaryScale(true, false, 0f), 0.0001f);
        assertEquals(1.0f, LyricSecondaryLayoutPolicy.secondaryScale(true, false, 1f), 0.0001f);
        assertEquals(0.5f, LyricSecondaryLayoutPolicy.secondaryScale(false, false, 0f), 0.0001f);
        assertEquals(1.0f, LyricSecondaryLayoutPolicy.secondaryScale(true, true, 0f), 0.0001f);
        assertEquals(1.0f, LyricSecondaryLayoutPolicy.secondaryScale(false, true, 0f), 0.0001f);
    }

    @Test public void topSecondaryMainOffsetRespectsSyncedAndLaggingSlotHeights() {
        assertEquals(36f, LyricSecondaryLayoutPolicy.mainTopWithSecondaryAbove(36f, false, 74f), 0.001f);
        assertEquals(110f, LyricSecondaryLayoutPolicy.mainTopWithSecondaryAbove(36f, true, 74f), 0.001f);
        assertEquals(36f, LyricSecondaryLayoutPolicy.mainTopWithSecondaryAbove(36f, true, -10f), 0.001f);

        // Synced slot height (availableHeight = 242 >= baseTop(36) + main(96) + above(74)) keeps full shift.
        assertEquals(74f, LyricSecondaryLayoutPolicy.effectiveAboveMainOffset(74f, 242f, 132f), 0.001f);
        // Lagging collapsed slot height (availableHeight = 168) clamps shift so main text is never sliced off.
        assertEquals(36f, LyricSecondaryLayoutPolicy.effectiveAboveMainOffset(74f, 168f, 132f), 0.001f);
        // Severely tight slot height clamps to zero rather than negative.
        assertEquals(0f, LyricSecondaryLayoutPolicy.effectiveAboveMainOffset(74f, 120f, 132f), 0.001f);
        // Zero/unmeasured availableHeight preserves raw offset.
        assertEquals(74f, LyricSecondaryLayoutPolicy.effectiveAboveMainOffset(74f, 0f, 132f), 0.001f);
    }

    @Test public void idleMainTopCorrectionOnlyCompensatesCenteredTopSecondaryExpansion() {
        // Apple top-anchored mode keeps item top pinned at topInset so top secondary is never clipped.
        assertEquals(0, LyricSecondaryLayoutPolicy.idleMainTopCorrectionPx(true, true, 36f, 110f));
        // Bottom secondary never shifts mainTop.
        assertEquals(0, LyricSecondaryLayoutPolicy.idleMainTopCorrectionPx(false, false, 36f, 110f));
        // Centered mode with top secondary compensates mainTop shift so main glyphs stay dead-center.
        assertEquals(74, LyricSecondaryLayoutPolicy.idleMainTopCorrectionPx(false, true, 36f, 110f));
        assertEquals(-40, LyricSecondaryLayoutPolicy.idleMainTopCorrectionPx(false, true, 110f, 70f));
        // Missing geometry never produces a bogus correction.
        assertEquals(0, LyricSecondaryLayoutPolicy.idleMainTopCorrectionPx(false, true, Float.NaN, 110f));
        assertEquals(0, LyricSecondaryLayoutPolicy.idleMainTopCorrectionPx(false, true, 36f, Float.NaN));
    }

    private static WordLine wordTimedLine(long mainStart, long firstWordStart, long vocalStart) {
        ArrayList<WordRange> mainWords = new ArrayList<>();
        mainWords.add(new WordRange(firstWordStart, 0, 4, firstWordStart + 500L));
        WordLine main = new WordLine(
                mainStart, "main", mainWords, Math.max(mainStart, firstWordStart) + 1000L, LyricTimingMode.WORD_TIMED);

        ArrayList<WordRange> vocalWords = new ArrayList<>();
        vocalWords.add(new WordRange(vocalStart, 0, 2, vocalStart + 400L));
        WordLine vocal = new WordLine(
                vocalStart, "bg", vocalWords, vocalStart + 600L, LyricTimingMode.WORD_TIMED);
        main.secondaryLine = vocal;
        return main;
    }
}
