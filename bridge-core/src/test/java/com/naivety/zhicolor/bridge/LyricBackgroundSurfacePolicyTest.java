package com.naivety.zhicolor.bridge;

import org.junit.Test;
import static org.junit.Assert.*;

public class LyricBackgroundSurfacePolicyTest {
    @Test public void visibleBackgroundDoesNotRequireAnObservedSectionBind() {
        assertTrue(LyricBackgroundSurfacePolicy.visible(true, false, true));
    }
    @Test public void observedSectionRemainsSupported() {
        assertTrue(LyricBackgroundSurfacePolicy.visible(true, true, false));
    }
    @Test public void systemChoiceNeverTakesOverBackground() {
        assertFalse(LyricBackgroundSurfacePolicy.visible(false, true, true));
    }
    @Test public void hiddenSurfacesDoNotRun() {
        assertFalse(LyricBackgroundSurfacePolicy.visible(true, false, false));
        assertFalse(LyricBackgroundSurfacePolicy.running(false, true, true));
    }
    @Test public void nonInteractiveOrOffDozingDisplayPausesRendering() {
        assertFalse(LyricBackgroundSurfacePolicy.running(true, false, true));
        assertFalse(LyricBackgroundSurfacePolicy.running(true, false, false));
        assertFalse(LyricBackgroundSurfacePolicy.running(true, true, false));
    }
    @Test public void awakeVisibleBackgroundRunsWithoutLyricOwnership() {
        assertTrue(LyricBackgroundSurfacePolicy.running(
                LyricBackgroundSurfacePolicy.visible(true, false, true), true, true));
    }
    @Test public void screenWakeResumesAndSleepStopsAgain() {
        boolean visible = LyricBackgroundSurfacePolicy.visible(true, false, true);
        assertFalse(LyricBackgroundSurfacePolicy.running(visible, false, false));
        assertTrue(LyricBackgroundSurfacePolicy.running(visible, true, true));
        assertFalse(LyricBackgroundSurfacePolicy.running(visible, false, false));
    }
}
