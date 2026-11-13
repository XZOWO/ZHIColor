package com.naivety.zhicolor.bridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class LyricUiGlowPolicyTest {
    @Test
    public void disabledSwitchPreservesDormantIntensity() {
        assertEquals(100, LyricUiGlowPolicy.canonicalIntensityPercent(100));
        assertFalse(LyricUiGlowPolicy.enabledForIntensity(0));
    }

    @Test
    public void intensityRemainsClampedIndependentlyOfSwitch() {
        assertEquals(65, LyricUiGlowPolicy.canonicalIntensityPercent(65));
        assertEquals(100, LyricUiGlowPolicy.canonicalIntensityPercent(999));
        assertEquals(0, LyricUiGlowPolicy.canonicalIntensityPercent(-1));
        assertTrue(LyricUiGlowPolicy.enabledForIntensity(65));
    }
}
