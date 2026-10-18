package com.naivety.zhicolor.bridge;

import org.junit.Test;
import static org.junit.Assert.*;

public class LyricMainAnchorPolicyTest {
    @Test public void secondaryExpandsDownwardWithoutMovingMain() {
        float top = LyricMainAnchorPolicy.mainTop(40f, 88f);
        float center = LyricMainAnchorPolicy.focusCenter(top, 40f, 0f, 0f);
        for (float extra : new float[] {0f, 12f, 50f, 130f}) {
            int slot = LyricMainAnchorPolicy.slotHeight(88, 40f, 40f + extra);
            assertEquals(88f + extra, slot, 0.001f);
            assertEquals(44f, center, 0.001f);
            assertTrue(top + 40f + extra <= slot);
        }
    }

    @Test public void smallMainStillRetainsMinimumSlotPaddingWhenVocalAppears() {
        float top = LyricMainAnchorPolicy.mainTop(20f, 88f);
        int expanded = LyricMainAnchorPolicy.slotHeight(88, 20f, 120f);
        assertEquals(34f, top, 0.001f);
        assertEquals(188, expanded);
        assertEquals(34f, expanded - top - 120f, 0.001f);
    }

    @Test public void wrappedMainUsesItsOwnHeightAndKeepsRoomForTranslation() {
        float main = 142.3f;
        int collapsed = LyricUiLayoutPolicy.requiredSlotHeight(main, 24, 4, 88);
        float top = LyricMainAnchorPolicy.mainTop(main, collapsed);
        int expanded = LyricMainAnchorPolicy.slotHeight(collapsed, main, main + 73.2f);
        assertEquals(171, collapsed);
        assertEquals(245, expanded);
        assertEquals(collapsed * 0.5f,
                LyricMainAnchorPolicy.focusCenter(top, main, 0f, 0f), 0.001f);
        assertTrue(top + main + 73.2f <= expanded);
    }

    @Test public void introFocusBelongsToDotsThenMovesContinuouslyToFirstLyric() {
        float top = 24f;
        assertEquals(40f, LyricMainAnchorPolicy.focusCenter(top, 40f, 80f, 1f), 0.001f);
        assertEquals(82f, LyricMainAnchorPolicy.focusCenter(top, 40f, 80f, .5f), 0.001f);
        assertEquals(124f, LyricMainAnchorPolicy.focusCenter(top, 40f, 80f, 0f), 0.001f);
        assertEquals(80f, LyricMainAnchorPolicy.focusHeight(40f, 80f, 1f), 0.001f);
        assertEquals(40f, LyricMainAnchorPolicy.focusHeight(40f, 0f, 0f), 0.001f);
    }

    @Test public void fixedPrefixDoesNotChangeTheFirstToSecondMainDistance() {
        float prefix = 80f;
        float slot = 88f;
        float firstAnchor = LyricMainAnchorPolicy.focusCenter(24f, 40f, prefix, 0f);
        float nextAnchor = prefix + slot + 44f;
        assertEquals(slot, nextAnchor - firstAnchor, 0f);
    }

    @Test public void restoredBodyGeometryDoesNotDependOnIntroOrSecondarySlotHeight() {
        for (float main : new float[] {28f, 60f, 150f}) {
            int collapsed = LyricUiLayoutPolicy.requiredSlotHeight(main, 24, 4, 88);
            float top = LyricMainAnchorPolicy.mainTop(main, collapsed);
            for (float extra : new float[] {0f, 40f, 180f}) {
                assertTrue(LyricMainAnchorPolicy.slotHeight(collapsed, main, main + extra)
                        >= top + main + extra);
                assertEquals(collapsed / 2f,
                        LyricMainAnchorPolicy.focusCenter(top, main, 0f, 0f), 0.001f);
            }
        }
    }
}
