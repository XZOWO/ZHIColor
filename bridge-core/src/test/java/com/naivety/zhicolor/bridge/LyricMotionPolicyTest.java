package com.naivety.zhicolor.bridge;

import org.junit.Test;
import static org.junit.Assert.*;

public class LyricMotionPolicyTest {
    @Test public void softModePreservesVisiblePositionAndFocusTransitions() {
        assertTrue(LyricMotionPolicy.animate(LyricUiConfig.MOTION_REDUCED, false, true));
        assertTrue(LyricMotionPolicy.scrollMillis(LyricUiConfig.MOTION_REDUCED) > 100L);
        assertTrue(LyricMotionPolicy.focusMillis(LyricUiConfig.MOTION_REDUCED) > 100L);
        assertTrue(LyricMotionPolicy.scrollMillis(LyricUiConfig.MOTION_REDUCED)
                < LyricMotionPolicy.scrollMillis(LyricUiConfig.MOTION_STANDARD));
    }

    @Test public void offAodAndSystemDisableSnapRegardlessOfMode() {
        for (int mode = 0; mode <= 2; mode++) {
            assertFalse(LyricMotionPolicy.animate(mode, true, true));
            assertFalse(LyricMotionPolicy.animate(mode, false, false));
        }
        assertFalse(LyricMotionPolicy.animate(LyricUiConfig.MOTION_OFF, false, true));
        assertEquals(0L, LyricMotionPolicy.scrollMillis(LyricUiConfig.MOTION_OFF));
        assertEquals(0L, LyricMotionPolicy.focusMillis(LyricUiConfig.MOTION_OFF));
    }

    @Test public void standardKeepsZhiToolDurationsWhileSoftRemovesSecondaryDelay() {
        assertEquals(620L, LyricMotionPolicy.scrollMillis(LyricUiConfig.MOTION_STANDARD));
        assertEquals(300L, LyricMotionPolicy.focusMillis(LyricUiConfig.MOTION_STANDARD));
        assertEquals(248L, LyricMotionPolicy.secondaryDelayMillis(LyricUiConfig.MOTION_STANDARD));
        assertEquals(0L, LyricMotionPolicy.secondaryDelayMillis(LyricUiConfig.MOTION_REDUCED));
        assertTrue(LyricMotionPolicy.songMillis(LyricUiConfig.MOTION_REDUCED) > 0L);
    }
}
