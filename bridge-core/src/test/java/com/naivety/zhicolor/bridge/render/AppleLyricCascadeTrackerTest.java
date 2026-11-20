package com.naivety.zhicolor.bridge.render;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class AppleLyricCascadeTrackerTest {
    private static final long DUR = AppleLyricMotionPolicy.SCROLL_MS;
    private static final float DAMPING = AppleLyricMotionPolicy.DEFAULT_DAMPING_RATIO;
    private static final float RESPONSE = AppleLyricMotionPolicy.DEFAULT_RESPONSE_SECONDS;

    private static float spring(long elapsedMs) {
        if (elapsedMs <= 0L) return 0f;
        if (elapsedMs >= DUR) return 1f;
        return AppleLyricMotionPolicy.springValue(elapsedMs / (float) DUR, DAMPING, RESPONSE);
    }

    @Test
    public void normalSingleLineForwardSwitchMatchesDelayedAppleSpring() {
        AppleLyricCascadeTracker tracker = new AppleLyricCascadeTracker();
        int[] pos = {5, 6, 7, 8};
        float[] startTops = {200f, 300f, 400f, 500f};
        float[] carry = {0f, 0f, 0f, 0f};
        assertTrue(tracker.start(6, 100f, 1000L, DUR, DAMPING, RESPONSE, pos, startTops, carry, 4));

        float[] curTops = new float[4];
        float[] out = new float[4];
        float[] prevVis = {200f, 300f, 400f, 500f};
        for (long dt = 0L; dt <= DUR + 140L; dt += 16L) {
            float p = spring(dt);
            for (int i = 0; i < 4; i++) {
                curTops[i] = startTops[i] - 100f * p;
            }
            tracker.sample(1000L + dt, pos, curTops, 4, out);
            assertEquals("target row has 0 translation", 0f, out[1], 0.001f);
            for (int i = 0; i < 4; i++) {
                float vis = curTops[i] + out[i];
                assertTrue("row " + pos[i] + " must move monotonically upward at dt=" + dt
                        + " prev=" + prevVis[i] + " cur=" + vis,
                        vis <= prevVis[i] + 0.02f);
                prevVis[i] = vis;
            }
            // Row 7 (delay 35ms) and Row 8 (delay 70ms) match exact delayed spring
            float expectedVis7 = 400f - 100f * spring(dt - 35L);
            float expectedVis8 = 500f - 100f * spring(dt - 70L);
            assertEquals(expectedVis7, curTops[2] + out[2], 0.05f);
            assertEquals(expectedVis8, curTops[3] + out[3], 0.05f);
        }
        assertFalse(tracker.sample(1000L + DUR + 180L, pos,
                new float[]{100f, 200f, 300f, 400f}, 4, out));
        for (float v : out) {
            assertEquals(0f, v, 0.0001f);
        }
    }

    @Test
    public void expandingSecondaryVocalOnTargetNeverCausesReverseSpringOnLowerRows() {
        // Active row 6 scrolls up 110px while expanding a +90px secondary vocal below main.
        // Rows 7..10 therefore only need to rise 20px (NOT 110px).
        // Previously, hardcoded scrollDeltaPx=110 pushed lower rows DOWN by +35px before bouncing up.
        AppleLyricCascadeTracker tracker = new AppleLyricCascadeTracker();
        int[] pos = {6, 7, 8, 9, 10};
        float[] startTops = {310f, 400f, 500f, 600f, 700f};
        float[] carry = {0f, 0f, 0f, 0f, 0f};
        assertTrue(tracker.start(6, 110f, 5000L, DUR, DAMPING, RESPONSE, pos, startTops, carry, 5));

        float[] curTops = new float[5];
        float[] out = new float[5];
        float[] prevVis = {310f, 400f, 500f, 600f, 700f};
        for (long dt = 0L; dt <= DUR + 180L; dt += 16L) {
            float p = spring(dt);
            curTops[0] = startTops[0] - 110f * p;
            for (int i = 1; i < 5; i++) {
                curTops[i] = startTops[i] - 110f * p + 90f * p; // net -20px
            }
            tracker.sample(5000L + dt, pos, curTops, 5, out);
            for (int i = 0; i < 5; i++) {
                float vis = curTops[i] + out[i];
                assertTrue("row " + pos[i] + " must NEVER dip below startTop (reverse spring!) at dt="
                        + dt + " vis=" + vis + " start=" + startTops[i],
                        vis <= startTops[i] + 0.01f);
                assertTrue("row " + pos[i] + " must move monotonically upward at dt=" + dt
                        + " prev=" + prevVis[i] + " cur=" + vis,
                        vis <= prevVis[i] + 0.02f);
                prevVis[i] = vis;
            }
            // Row 10 (delay 140ms) amplitude must strictly match its 20px travel
            float expectedVis10 = 700f - 20f * spring(dt - 140L);
            assertEquals(expectedVis10, curTops[4] + out[4], 0.05f);
        }
    }

    @Test
    public void tallSecondaryVocalLargerThanScrollMovesLowerRowsSmoothlyDownwardWithoutReversal() {
        // Active row 6 scrolls up 80px while expanding a +120px secondary vocal -> lower rows move DOWN +40px.
        AppleLyricCascadeTracker tracker = new AppleLyricCascadeTracker();
        int[] pos = {6, 7, 8};
        float[] startTops = {280f, 380f, 480f};
        float[] carry = {0f, 0f, 0f};
        assertTrue(tracker.start(6, 80f, 2000L, DUR, DAMPING, RESPONSE, pos, startTops, carry, 3));

        float[] curTops = new float[3];
        float[] out = new float[3];
        float[] prevVis = {280f, 380f, 480f};
        for (long dt = 0L; dt <= DUR + 100L; dt += 16L) {
            float p = spring(dt);
            curTops[0] = startTops[0] - 80f * p;
            curTops[1] = startTops[1] + 40f * p;
            curTops[2] = startTops[2] + 40f * p;
            tracker.sample(2000L + dt, pos, curTops, 3, out);
            // Lower rows must move strictly monotonically DOWN from startTop to startTop+40
            for (int i = 1; i < 3; i++) {
                float vis = curTops[i] + out[i];
                assertTrue("lower row " + pos[i] + " must never jump upward at dt=" + dt,
                        vis >= startTops[i] - 0.01f);
                assertTrue("lower row " + pos[i] + " must glide monotonically downward at dt=" + dt,
                        vis >= prevVis[i] - 0.02f);
                assertTrue("lower row " + pos[i] + " must not overshoot beyond +40px at dt=" + dt,
                        vis <= startTops[i] + 40.02f);
                prevVis[i] = vis;
            }
        }
    }

    @Test
    public void collapsingWaitingDotsWithReducedRecyclerScrollHasZeroReverseSpring() {
        // Waiting dots row 5 collapses -110px while RecyclerView scroll is reduced to -30px
        // (total real upward movement of row 6..8 is -140px).
        AppleLyricCascadeTracker tracker = new AppleLyricCascadeTracker();
        int[] pos = {5, 6, 7, 8};
        float[] startTops = {200f, 340f, 440f, 540f};
        float[] carry = {0f, 0f, 0f, 0f};
        assertTrue(tracker.start(6, 140f, 3000L, DUR, DAMPING, RESPONSE, pos, startTops, carry, 4));

        float[] curTops = new float[4];
        float[] out = new float[4];
        float[] prevVis = {200f, 340f, 440f, 540f};
        for (long dt = 0L; dt <= DUR + 100L; dt += 16L) {
            float p = spring(dt);
            curTops[0] = 200f - 30f * p;
            curTops[1] = 340f - 30f * p - 110f * p;
            curTops[2] = 440f - 30f * p - 110f * p;
            curTops[3] = 540f - 30f * p - 110f * p;
            tracker.sample(3000L + dt, pos, curTops, 4, out);
            for (int i = 1; i < 4; i++) {
                float vis = curTops[i] + out[i];
                assertTrue("row " + pos[i] + " must move monotonically up at dt=" + dt,
                        vis <= prevVis[i] + 0.02f);
                prevVis[i] = vis;
            }
        }
    }

    @Test
    public void scrollBoundaryClampNearEndOfSongNeverProducesReverseSpringOrSnap() {
        // RecyclerView hits bottom boundary after only 25px (or 0px) even though rawDt requested 140px.
        AppleLyricCascadeTracker tracker = new AppleLyricCascadeTracker();
        int[] pos = {10, 11, 12};
        float[] startTops = {340f, 440f, 540f};
        float[] carry = {0f, 0f, 0f};
        assertTrue(tracker.start(10, 140f, 4000L, DUR, DAMPING, RESPONSE, pos, startTops, carry, 3));

        float[] curTops = new float[3];
        float[] out = new float[3];
        float[] prevVis = {340f, 440f, 540f};
        for (long dt = 0L; dt <= DUR + 100L; dt += 16L) {
            float clampedMove = Math.max(-25f, -140f * spring(dt));
            for (int i = 0; i < 3; i++) {
                curTops[i] = startTops[i] + clampedMove;
            }
            tracker.sample(4000L + dt, pos, curTops, 3, out);
            for (int i = 0; i < 3; i++) {
                float vis = curTops[i] + out[i];
                assertTrue("clamped row " + pos[i] + " must stay within [start-25, start] at dt=" + dt
                        + " vis=" + vis,
                        vis <= startTops[i] + 0.02f && vis >= startTops[i] - 25.02f);
                assertTrue("clamped row " + pos[i] + " must move monotonically at dt=" + dt,
                        vis <= prevVis[i] + 0.02f);
                prevVis[i] = vis;
            }
        }
        // Complete bottom clamp (0px actual movement) must yield exactly 0 translationY everywhere
        tracker.reset();
        assertTrue(tracker.start(10, 140f, 5000L, DUR, DAMPING, RESPONSE, pos, startTops, carry, 3));
        tracker.sample(5100L, pos, startTops, 3, out);
        assertEquals(0f, out[0], 0.0001f);
        assertEquals(0f, out[1], 0.0001f);
        assertEquals(0f, out[2], 0.0001f);
    }

    @Test
    public void midFlightInterruptionPreservesExactVisualContinuityIncludingNewTargetRow() {
        AppleLyricCascadeTracker tracker = new AppleLyricCascadeTracker();
        int[] pos = {5, 6, 7};
        float[] tops0 = {200f, 300f, 400f};
        float[] carry0 = {0f, 0f, 0f};
        assertTrue(tracker.start(5, 100f, 1000L, DUR, DAMPING, RESPONSE, pos, tops0, carry0, 3));

        // Sample at t = 200ms mid-flight
        float p200 = spring(200L);
        float[] topsAt200 = {200f - 100f * p200, 300f - 100f * p200, 400f - 100f * p200};
        float[] tyAt200 = new float[3];
        assertTrue(tracker.sample(1200L, pos, topsAt200, 3, tyAt200));
        assertTrue("row 6 should have positive carry mid-flight", tyAt200[1] > 5f);
        assertTrue("row 7 should have larger positive carry mid-flight", tyAt200[2] > tyAt200[1]);

        float vis6Before = topsAt200[1] + tyAt200[1];
        float vis7Before = topsAt200[2] + tyAt200[2];

        // Interrupt at t = 1200ms targeting row 6 (whose delay drops from 35ms to 0ms!)
        // while row 6 also expands a +80px secondary vocal so row 7 only rises 20px.
        assertTrue(tracker.start(6, 100f, 1200L, DUR, DAMPING, RESPONSE, pos, topsAt200, tyAt200, 3));
        float[] out = new float[3];
        assertTrue(tracker.sample(1200L, pos, topsAt200, 3, out));
        assertEquals("row 6 must not snap at interrupt boundary", vis6Before, topsAt200[1] + out[1], 0.01f);
        assertEquals("row 7 must not snap at interrupt boundary", vis7Before, topsAt200[2] + out[2], 0.01f);

        float prevVis6 = vis6Before;
        float prevVis7 = vis7Before;
        for (long dt = 16L; dt <= DUR + 60L; dt += 16L) {
            float p = spring(dt);
            float[] cur = {
                    topsAt200[0] - 100f * p,
                    topsAt200[1] - 100f * p,
                    topsAt200[2] - 20f * p
            };
            tracker.sample(1200L + dt, pos, cur, 3, out);
            float vis6 = cur[1] + out[1];
            float vis7 = cur[2] + out[2];
            assertTrue("row 6 must glide smoothly after interrupt at dt=" + dt, vis6 <= prevVis6 + 0.02f);
            assertTrue("row 7 must glide smoothly after interrupt at dt=" + dt, vis7 <= prevVis7 + 0.02f);
            prevVis6 = vis6;
            prevVis7 = vis7;
        }
    }

    @Test
    public void newlyEnteringRowInheritsSameSideNeighborDeltaNotTargetPreExpansionDelta() {
        AppleLyricCascadeTracker tracker = new AppleLyricCascadeTracker();
        // At t=0 only rows 6 (target) and 7 are visible.
        assertTrue(tracker.start(6, 110f, 1000L, DUR, DAMPING, RESPONSE,
                new int[]{6, 7}, new float[]{310f, 400f}, new float[]{0f, 0f}, 2));

        // At dt=160ms, row 6 moved -110*p and expanded +90*p so row 7 moved -20*p,
        // and row 8 just scrolled into view below row 7!
        float p160 = spring(160L);
        int[] posWith8 = {6, 7, 8};
        float[] topsWith8 = {
                310f - 110f * p160,
                400f - 20f * p160,
                500f - 20f * p160
        };
        float[] out = new float[3];
        assertTrue(tracker.sample(1160L, posWith8, topsWith8, 3, out));
        // Row 8 must inherit row 7's -20px displacement (NOT row 6's -110px!), so its visual position
        // equals 500 - 20 * S(160 - 70) with zero reverse spring.
        float expectedVis8 = 500f - 20f * spring(160L - 70L);
        assertEquals(expectedVis8, topsWith8[2] + out[2], 0.05f);
    }

    @Test
    public void reverseScrollAndSameTargetDebounceBehaveCorrectly() {
        AppleLyricCascadeTracker tracker = new AppleLyricCascadeTracker();
        int[] pos = {2, 3, 4, 5};
        float[] startTops = {100f, 200f, 300f, 400f};
        float[] carry = {0f, 0f, 0f, 0f};
        assertTrue(tracker.start(3, -100f, 1000L, DUR, DAMPING, RESPONSE, pos, startTops, carry, 4));
        // Duplicate call for same target within 120ms must preserve original t=1000 baselines
        assertTrue(tracker.start(3, -100f, 1050L, DUR, DAMPING, RESPONSE,
                pos, new float[]{110f, 210f, 310f, 410f}, carry, 4));
        assertEquals(1000L, tracker.startTimeMillis());

        float p = spring(100L);
        float[] curTops = {100f + 100f * p, 200f + 100f * p, 300f + 100f * p, 400f + 100f * p};
        float[] out = new float[4];
        assertTrue(tracker.sample(1100L, pos, curTops, 4, out));
        assertEquals("bottom row moves first with 0 lag in reverse scroll", 0f, out[3], 0.001f);
        assertTrue("rows above bottom lag upward (negative ty) in reverse scroll",
                out[2] < 0f && out[1] < out[2] && out[0] < out[1]);
    }
}
