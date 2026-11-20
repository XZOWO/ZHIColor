package com.naivety.zhicolor.bridge.render;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class LyricWrappedWindowMotionTest {

    @Test
    public void threeLineLyricAdvancesContinuouslyAndReturnsSmoothlyAfterSingingFinishes() {
        LyricWrappedWindowMotion motion = new LyricWrappedWindowMotion();
        LyricWrappedWindowMotion.Sample sample = new LyricWrappedWindowMotion.Sample();
        int layoutKey = 42;

        // Upcoming lyric initializes quietly at [0, 1] without animating.
        motion.sample(layoutKey, 3, 2, 0, false, true, true, false, false, false, 1_000L, sample);
        assertFalse(sample.animating);
        assertEquals(0, sample.currentStart);
        assertEquals(2, sample.visibleCount);

        // Singing row 0 stays at 0, then singing row 1 starts continuous scroll 0 -> 1.
        motion.sample(layoutKey, 3, 2, 0, true, true, true, false, false, false, 2_000L, sample);
        assertFalse(sample.animating);
        motion.sample(layoutKey, 3, 2, 1, true, true, true, false, false, false, 3_000L, sample);
        assertTrue(sample.animating);
        assertEquals(LyricWrappedWindowMotion.MODE_CONTINUOUS_SCROLL, sample.mode);
        assertEquals(0f, sample.continuousOffset, 0.001f);

        // Mid-advance: row 1 is inside [0, 1] so it remains 100% solid (no ghosting).
        motion.sample(layoutKey, 3, 2, 1, true, true, true, false, false, false, 3_120L, sample);
        assertTrue(sample.animating);
        assertTrue(sample.continuousOffset > 0.1f && sample.continuousOffset < 0.99f);
        assertEquals(1f, LyricWrappedWindowMotion.continuousRowAlpha(1f - sample.continuousOffset, 2), 0.0001f);

        // Settles at [1, 2].
        motion.sample(layoutKey, 3, 2, 1, true, true, true, false, false, false, 3_350L, sample);
        assertFalse(sample.animating);
        assertEquals(1, sample.currentStart);

        // Singing finishes on the next frame: must NOT hard-cut to 0; glides smoothly 1 -> 0.
        motion.sample(layoutKey, 3, 2, 1, false, true, true, false, false, false, 3_366L, sample);
        assertTrue(sample.animating);
        assertEquals(LyricWrappedWindowMotion.MODE_CONTINUOUS_SCROLL, sample.mode);
        assertEquals(0, sample.currentStart);
        assertEquals(1f, sample.continuousOffset, 0.001f);

        float prev = sample.continuousOffset;
        for (long t = 3_416L; t < 3_366L + LyricWrappedWindowMotion.RETURN_ONE_ROW_APPLE_MS; t += 50L) {
            motion.sample(layoutKey, 3, 2, 0, false, true, true, false, false, false, t, sample);
            assertTrue("Should stay animating at t=" + t, sample.animating);
            assertTrue("Offset should decrease monotonically back to 0", sample.continuousOffset <= prev + 1e-4f);
            prev = sample.continuousOffset;
        }

        // Lands cleanly at [0, 1] and stops requesting frames.
        motion.sample(layoutKey, 3, 2, 0, false, true, true, false, false, false,
                3_366L + LyricWrappedWindowMotion.RETURN_ONE_ROW_APPLE_MS + 10L, sample);
        assertFalse(sample.animating);
        assertEquals(0, sample.currentStart);
        assertEquals(0f, sample.continuousOffset, 0.0001f);
        assertFalse(motion.isAnimating(4_000L));
    }

    @Test
    public void fourLineLyricSupportsMidFlightInterruptionAndContinuousTwoRowReturn() {
        LyricWrappedWindowMotion motion = new LyricWrappedWindowMotion();
        LyricWrappedWindowMotion.Sample sample = new LyricWrappedWindowMotion.Sample();
        int layoutKey = 77;

        motion.sample(layoutKey, 4, 2, 0, true, true, false, false, false, false, 1_000L, sample);
        // Start advancing 0 -> 1.
        motion.sample(layoutKey, 4, 2, 1, true, true, false, false, false, false, 1_100L, sample);
        motion.sample(layoutKey, 4, 2, 1, true, true, false, false, false, false, 1_200L, sample);
        float midOffset = sample.continuousOffset;
        assertTrue(midOffset > 0.2f && midOffset < 0.95f);

        // Interrupt mid-flight to target 2: must continue seamlessly from midOffset with no jump.
        motion.sample(layoutKey, 4, 2, 2, true, true, false, false, false, false, 1_200L, sample);
        assertTrue(sample.animating);
        assertEquals(LyricWrappedWindowMotion.MODE_CONTINUOUS_SCROLL, sample.mode);
        assertEquals(midOffset, sample.continuousOffset, 0.001f);

        // Settle at 2 ([2, 3]).
        motion.sample(layoutKey, 4, 2, 2, true, true, false, false, false, false, 1_600L, sample);
        assertFalse(sample.animating);
        assertEquals(2, sample.currentStart);

        // Finish singing -> 2-row continuous rewind back to 0.
        motion.sample(layoutKey, 4, 2, 2, false, true, false, false, false, false, 1_616L, sample);
        assertTrue(sample.animating);
        assertEquals(LyricWrappedWindowMotion.MODE_CONTINUOUS_SCROLL, sample.mode);
        assertEquals(2f, sample.continuousOffset, 0.001f);

        motion.sample(layoutKey, 4, 2, 0, false, true, false, false, false, false, 1_836L, sample);
        assertTrue(sample.animating);
        assertTrue("Midpoint should sweep smoothly through middle rows",
                sample.continuousOffset > 0.1f && sample.continuousOffset < 1.9f);

        motion.sample(layoutKey, 4, 2, 0, false, true, false, false, false, false,
                1_616L + LyricWrappedWindowMotion.RETURN_TWO_ROW_STANDARD_MS + 10L, sample);
        assertFalse(sample.animating);
        assertEquals(0, sample.currentStart);
    }

    @Test
    public void ultraLongSixLineLyricUsesCalmCrossfadeGlideWhenReturning() {
        LyricWrappedWindowMotion motion = new LyricWrappedWindowMotion();
        LyricWrappedWindowMotion.Sample sample = new LyricWrappedWindowMotion.Sample();
        int layoutKey = 99;

        motion.snap(layoutKey, 4, 2_000L);
        motion.sample(layoutKey, 6, 2, 4, true, true, true, false, false, false, 2_016L, sample);
        assertEquals(4, sample.currentStart);
        assertFalse(sample.animating);

        // Return 4 -> 0 (delta = 4 > MAX_CONTINUOUS_SCROLL_ROWS): calm masked glide-crossfade.
        motion.sample(layoutKey, 6, 2, 4, false, true, true, false, false, false, 2_032L, sample);
        assertTrue(sample.animating);
        assertEquals(LyricWrappedWindowMotion.MODE_CROSSFADE_GLIDE, sample.mode);
        assertEquals(4, sample.previousStart);
        assertEquals(0, sample.currentStart);
        assertEquals(1f, sample.outgoingAlpha, 0.001f);
        assertEquals(0f, sample.incomingAlpha, 0.001f);

        motion.sample(layoutKey, 6, 2, 0, false, true, true, false, false, false, 2_242L, sample);
        assertTrue(sample.animating);
        assertTrue(sample.outgoingAlpha > 0f && sample.outgoingAlpha < 0.5f);
        assertTrue(sample.incomingAlpha > 0.2f && sample.incomingAlpha < 0.9f);

        motion.sample(layoutKey, 6, 2, 0, false, true, true, false, false, false,
                2_032L + LyricWrappedWindowMotion.CROSSFADE_APPLE_MS + 10L, sample);
        assertFalse(sample.animating);
        assertEquals(0, sample.currentStart);
    }

    @Test
    public void sameFrameDuplicateInactiveHolderNeverResetsActiveWindow() {
        LyricWrappedWindowMotion motion = new LyricWrappedWindowMotion();
        LyricWrappedWindowMotion.Sample sample = new LyricWrappedWindowMotion.Sample();
        int layoutKey = 123;

        motion.snap(layoutKey, 2, 5_000L);
        // Order A: Active holder draws first at t=5016, stale duplicate inactive holder draws second at t=5016.
        motion.sample(layoutKey, 4, 2, 2, true, true, true, false, false, false, 5_016L, sample);
        assertEquals(2, sample.currentStart);
        assertFalse(sample.animating);

        motion.sample(layoutKey, 4, 2, 2, false, true, true, false, false, false, 5_016L, sample);
        assertEquals(2, sample.currentStart);
        assertFalse("Same-frame duplicate inactive draw must not start a rewind", sample.animating);

        // Order B: Duplicate inactive holder draws first at t=5032, active holder draws second at t=5032.
        motion.sample(layoutKey, 4, 2, 2, false, true, true, false, false, false, 5_032L, sample);
        assertEquals(2f, sample.continuousOffset, 0.001f);
        motion.sample(layoutKey, 4, 2, 2, true, true, true, false, false, false, 5_032L, sample);
        assertFalse("Active holder in same frame recovers immediately without animating", sample.animating);
        assertEquals(2, sample.currentStart);
    }

    @Test
    public void offscreenGapScrubAodAndWakeHoldBehaveCleanly() {
        LyricWrappedWindowMotion motion = new LyricWrappedWindowMotion();
        LyricWrappedWindowMotion.Sample sample = new LyricWrappedWindowMotion.Sample();
        int layoutKey = 55;

        // Off-screen > 1000ms before returning inactive: snaps quietly to 0 (no belated rewind).
        motion.snap(layoutKey, 1, 1_000L);
        motion.sample(layoutKey, 3, 2, 1, true, true, true, false, false, false, 1_016L, sample);
        motion.sample(layoutKey, 3, 2, 0, false, true, true, false, false, false, 3_500L, sample);
        assertFalse(sample.animating);
        assertEquals(0, sample.currentStart);

        // Active scrub snaps immediately.
        motion.snap(layoutKey, 2, 4_000L);
        motion.sample(layoutKey, 4, 2, 0, false, true, true, false, false, true, 4_016L, sample);
        assertFalse(sample.animating);
        assertEquals(0, sample.currentStart);

        // Hold during AOD wake blend preserves the AOD window.
        motion.snap(layoutKey, 1, 5_000L);
        motion.sample(layoutKey, 3, 2, 0, false, true, true, false, true, false, 5_016L, sample);
        assertFalse(sample.animating);
        assertEquals(1, sample.currentStart);

        // Motion off snaps immediately.
        motion.sample(layoutKey, 3, 2, 0, false, false, false, false, false, false, 5_032L, sample);
        assertFalse(sample.animating);
        assertEquals(0, sample.currentStart);
    }

    @Test
    public void edgeOpacityKeepsInnerRowsSolidAndDissolvesBeforeClipBoundary() {
        assertEquals(1f, LyricWrappedWindowMotion.continuousRowAlpha(0f, 2), 0.0001f);
        assertEquals(1f, LyricWrappedWindowMotion.continuousRowAlpha(0.5f, 2), 0.0001f);
        assertEquals(1f, LyricWrappedWindowMotion.continuousRowAlpha(1f, 2), 0.0001f);

        // Symmetric top and bottom edge dissolution before hitting hard clip boundary.
        float topMid = LyricWrappedWindowMotion.continuousRowAlpha(-0.5f, 2);
        float bottomMid = LyricWrappedWindowMotion.continuousRowAlpha(1.5f, 2);
        assertEquals(topMid, bottomMid, 0.0001f);
        assertTrue(topMid > 0.2f && topMid < 0.7f);

        assertEquals(0f, LyricWrappedWindowMotion.continuousRowAlpha(-0.92f, 2), 0.0001f);
        assertEquals(0f, LyricWrappedWindowMotion.continuousRowAlpha(1.92f, 2), 0.0001f);
        assertEquals(0f, LyricWrappedWindowMotion.continuousRowAlpha(-1f, 2), 0.0001f);
        assertEquals(0f, LyricWrappedWindowMotion.continuousRowAlpha(2f, 2), 0.0001f);
    }

    @Test
    public void legacyModeSnapsToZeroWhenInactiveAndUsesLegacyCrossfadeWhileActive() {
        LyricWrappedWindowMotion motion = new LyricWrappedWindowMotion();
        LyricWrappedWindowMotion.Sample sample = new LyricWrappedWindowMotion.Sample();
        int layoutKey = 88;

        motion.sampleLegacy(layoutKey, 3, 2, 0, true, true, false, 1_000L, sample);
        assertFalse(sample.animating);
        assertEquals(0, sample.currentStart);

        motion.sampleLegacy(layoutKey, 3, 2, 1, true, true, false, 2_000L, sample);
        assertTrue(sample.animating);
        assertEquals(LyricWrappedWindowMotion.MODE_LEGACY_CROSSFADE, sample.mode);
        assertEquals(0, sample.previousStart);
        assertEquals(1, sample.currentStart);

        motion.sampleLegacy(layoutKey, 3, 2, 1, true, true, false, 2_110L, sample);
        assertTrue(sample.animating);
        assertEquals(0.5f, sample.progress, 0.02f);
        assertEquals(0.5f, sample.outgoingAlpha, 0.02f);
        assertEquals(0.5f, sample.incomingAlpha, 0.02f);

        // Settles at 1, then turning inactive snaps immediately to 0 (legacy behavior).
        motion.sampleLegacy(layoutKey, 3, 2, 1, true, true, false, 2_300L, sample);
        assertFalse(sample.animating);
        assertEquals(1, sample.currentStart);

        motion.sampleLegacy(layoutKey, 3, 2, 1, false, true, false, 2_316L, sample);
        assertFalse(sample.animating);
        assertEquals(0, sample.currentStart);
    }
}
