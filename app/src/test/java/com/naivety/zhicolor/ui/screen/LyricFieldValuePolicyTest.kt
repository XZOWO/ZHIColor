package com.naivety.zhicolor.ui.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricFieldValuePolicyTest {
    @Test fun emphasisChildVisibilityFollowsOnlyItsParentAndKeepsItsSelection() {
        val values = mutableMapOf<String, Any>(
            "char_lift_enabled" to false,
            "word_emphasis_enabled" to true,
            "chinese_tail_emphasis_enabled" to true,
        )
        assertTrue(LyricFieldValuePolicy.isVisible("word_emphasis_enabled", values))
        LyricFieldValuePolicy.update(values, "word_emphasis_enabled", false)
        assertFalse(LyricFieldValuePolicy.isVisible("word_emphasis_enabled", values))
        assertEquals(true, values["chinese_tail_emphasis_enabled"])
        assertEquals(false, values["char_lift_enabled"])
        LyricFieldValuePolicy.update(values, "word_emphasis_enabled", true)
        assertTrue(LyricFieldValuePolicy.isVisible("word_emphasis_enabled", values))
    }

    @Test fun hidingGlowControlsPreservesTheirValuesAndOtherControlsStayVisible() {
        val values = mutableMapOf<String, Any>(
            "glow_enabled" to true,
            "glow_intensity_percent" to 73,
            "glow_radius_percent" to 19,
            "glow_color" to "#123456",
        )
        LyricFieldValuePolicy.update(values, "glow_enabled", false)
        assertFalse(LyricFieldValuePolicy.isVisible("glow_enabled", values))
        assertEquals(73, values["glow_intensity_percent"])
        assertEquals(19, values["glow_radius_percent"])
        assertEquals("#123456", values["glow_color"])
        assertTrue(LyricFieldValuePolicy.isVisible(null, values))
        LyricFieldValuePolicy.update(values, "glow_enabled", true)
        assertTrue(LyricFieldValuePolicy.isVisible("glow_enabled", values))
        assertEquals(73, values["glow_intensity_percent"])
    }

    @Test fun editorLimitsRespectTheConfiguredOpacityHierarchy() {
        val values = mapOf<String, Any>(
            "active_opacity_percent" to 80,
            "active_translation_progress_opacity_percent" to 65,
        )
        assertEquals(80, LyricFieldValuePolicy.maximum("current_unrevealed_opacity_percent", values, 100))
        assertEquals(80, LyricFieldValuePolicy.maximum("active_translation_progress_opacity_percent", values, 100))
        assertEquals(65, LyricFieldValuePolicy.maximum("active_translation_opacity_percent", values, 100))
        assertEquals(75, LyricFieldValuePolicy.maximum("translation_font_ratio_percent", values, 75))
    }

    @Test fun loweringTheActiveOpacityPreservesAValidDraftBeforeSaving() {
        val values = mutableMapOf<String, Any>(
            "active_opacity_percent" to 100,
            "current_unrevealed_opacity_percent" to 80,
            "active_translation_progress_opacity_percent" to 90,
            "active_translation_opacity_percent" to 70,
            "inactive_opacity_percent" to 45,
        )
        LyricFieldValuePolicy.update(values, "active_opacity_percent", 60)
        assertEquals(60, values["active_opacity_percent"])
        assertEquals(60, values["current_unrevealed_opacity_percent"])
        assertEquals(60, values["active_translation_progress_opacity_percent"])
        assertEquals(60, values["active_translation_opacity_percent"])
        assertEquals(45, values["inactive_opacity_percent"])
    }

    @Test fun adjustingTranslationProgressOnlyLowersItsDependentsWhenNeeded() {
        val values = mutableMapOf<String, Any>(
            "active_opacity_percent" to 85,
            "current_unrevealed_opacity_percent" to 50,
            "active_translation_progress_opacity_percent" to 80,
            "active_translation_opacity_percent" to 60,
        )
        LyricFieldValuePolicy.update(values, "active_translation_progress_opacity_percent", 40)
        assertEquals(40, values["active_translation_opacity_percent"])
        assertEquals(50, values["current_unrevealed_opacity_percent"])
        LyricFieldValuePolicy.update(values, "active_translation_progress_opacity_percent", 75)
        assertEquals(40, values["active_translation_opacity_percent"])
        assertEquals(85, values["active_opacity_percent"])
    }
}
