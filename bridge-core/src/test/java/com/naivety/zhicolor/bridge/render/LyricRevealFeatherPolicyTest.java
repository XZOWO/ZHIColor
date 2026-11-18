package com.naivety.zhicolor.bridge.render;

import org.junit.Test;

import static org.junit.Assert.*;

public final class LyricRevealFeatherPolicyTest {
    @Test public void longSegmentEndTapersSmoothlyToClipRightWithoutPopOrFreeze() {
        LyricRevealFeatherPolicy.Bounds bounds = new LyricRevealFeatherPolicy.Bounds();
        float left = 100f;
        float width = 320f;
        float back = 24f;
        float front = 32f;
        float overhang = 8f;
        float prevStart = -Float.MAX_VALUE;
        float prevEnd = -Float.MAX_VALUE;

        for (int step = 1; step < 1280; step++) {
            float reveal = step * 0.25f;
            LyricRevealFeatherPolicy.resolve(left, width, reveal, back, front, overhang, overhang, bounds);
            assertTrue(bounds.visible);
            assertFalse(bounds.solid);
            assertEquals(left - overhang, bounds.clipLeft, 0f);
            assertEquals(left + width + overhang, bounds.clipRight, 0f);
            assertTrue("featherEnd must lead featherStart at reveal=" + reveal,
                    bounds.featherEnd > bounds.featherStart);
            assertTrue("featherStart must advance monotonically at reveal=" + reveal,
                    bounds.featherStart > prevStart);
            assertTrue("featherEnd must advance monotonically at reveal=" + reveal,
                    bounds.featherEnd > prevEnd);
            prevStart = bounds.featherStart;
            prevEnd = bounds.featherEnd;
        }

        // Right before completion, featherStart has reached clipRight without a 0.5px jump.
        LyricRevealFeatherPolicy.resolve(left, width, width - 0.001f, back, front, overhang, overhang, bounds);
        assertFalse(bounds.solid);
        assertEquals(bounds.clipRight, bounds.featherStart, 0.01f);

        LyricRevealFeatherPolicy.resolve(left, width, width, back, front, overhang, overhang, bounds);
        assertTrue(bounds.visible);
        assertTrue(bounds.solid);
        assertEquals(bounds.clipRight, bounds.featherStart, 0f);
    }

    @Test public void shortWrappedSegmentNeverPinsBothFeatherBoundsSimultaneously() {
        LyricRevealFeatherPolicy.Bounds bounds = new LyricRevealFeatherPolicy.Bounds();
        float left = 40f;
        float width = 22f; // shorter than back + front
        float back = 24f;
        float front = 32f;
        float prevStart = -Float.MAX_VALUE;
        float prevEnd = -Float.MAX_VALUE;

        for (int step = 1; step < 88; step++) {
            float reveal = step * 0.25f;
            LyricRevealFeatherPolicy.resolve(left, width, reveal, back, front, 4f, 4f, bounds);
            assertTrue(bounds.visible);
            assertFalse(bounds.solid);
            assertTrue("short segment featherStart must keep advancing at reveal=" + reveal,
                    bounds.featherStart > prevStart);
            assertTrue("short segment featherEnd must keep advancing at reveal=" + reveal,
                    bounds.featherEnd > prevEnd);
            assertTrue(bounds.featherEnd > bounds.featherStart);
            prevStart = bounds.featherStart;
            prevEnd = bounds.featherEnd;
        }
        assertEquals(left + width + 4f, prevStart, 0.75f);
    }

    @Test public void invalidOrZeroInputsStaySafeAndHidden() {
        LyricRevealFeatherPolicy.Bounds bounds = new LyricRevealFeatherPolicy.Bounds();
        for (float bad : new float[]{0f, -5f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
            LyricRevealFeatherPolicy.resolve(50f, 120f, bad, 20f, 25f, 4f, 4f, bounds);
            assertFalse(bounds.visible);
            assertFalse(bounds.solid);
            assertEquals(0f, bounds.visibleWidth, 0f);

            LyricRevealFeatherPolicy.resolve(50f, bad, 40f, 20f, 25f, 4f, 4f, bounds);
            assertFalse(bounds.visible);
            assertFalse(bounds.solid);
        }
        LyricRevealFeatherPolicy.resolve(50f, 120f, 200f, 20f, 25f, 4f, 4f, bounds);
        assertTrue(bounds.visible);
        assertTrue(bounds.solid);
        assertEquals(120f, bounds.visibleWidth, 0f);
    }
}
