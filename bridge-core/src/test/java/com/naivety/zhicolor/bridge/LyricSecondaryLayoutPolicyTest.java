package com.naivety.zhicolor.bridge;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

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
}
