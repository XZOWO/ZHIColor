package com.naivety.zhicolor.bridge.render;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LyricViewportMotionPolicyTest {
    private static final float EPSILON = 0.00001f;

    @Test
    public void focusAndAdjacentRowPreserveConfiguredAppearance() {
        LyricViewportMotionPolicy.State state = sample(450f, 550f, 500f, 100f, 0f, 1_000f);
        assertEquals(1f, state.opacity(true, true, 0.65f), EPSILON);
        assertEquals(0f, state.blurRadius(true, true, 12f), EPSILON);

        state = sample(550f, 650f, 500f, 100f, 0f, 1_000f);
        assertEquals(0.65f, state.opacity(false, true, 0.65f), EPSILON);
        assertEquals(0f, state.blurRadius(false, true, 12f), EPSILON);
    }

    @Test
    public void fourthAndFifthRowsRemainVisibleInsideTheFullScreen() {
        // These row centres are four and five row heights from the focus.
        for (float top : new float[]{850f, 950f}) {
            LyricViewportMotionPolicy.State state = sample(top, top + 100f,
                    500f, 100f, 0f, 1_100f);
            assertTrue(state.isVisible());
            assertTrue(state.opacity(false, true, 0.65f) > 0f);
            assertTrue(state.blurRadius(false, true, 12f) < 12f);
        }
    }

    @Test
    public void effectsFollowPixelsAndAvailableSpaceInsteadOfLyricCount() {
        LyricViewportMotionPolicy.State smaller = sample(750f, 850f,
                500f, 100f, 0f, 900f);
        LyricViewportMotionPolicy.State larger = sample(750f, 850f,
                500f, 100f, 0f, 1_500f);
        assertTrue(larger.opacity(false, true, 0.65f)
                > smaller.opacity(false, true, 0.65f));
        assertTrue(larger.blurRadius(false, true, 12f)
                < smaller.blurRadius(false, true, 12f));
    }

    @Test
    public void topAndBottomUseTheirOwnDistanceWhenFocusIsNotCentred() {
        // Both row centres sit 200 px from the focus, but the upper edge is much closer.
        LyricViewportMotionPolicy.State upper = sample(50f, 150f,
                300f, 100f, 0f, 1_000f);
        LyricViewportMotionPolicy.State lower = sample(450f, 550f,
                300f, 100f, 0f, 1_000f);
        assertTrue(upper.opacity(false, true, 0.65f)
                < lower.opacity(false, true, 0.65f));
        assertTrue(upper.blurRadius(false, true, 12f)
                > lower.blurRadius(false, true, 12f));
    }

    @Test
    public void actualTopClipAndBottomPaddingBoundVisibility() {
        assertFalse(sample(0f, 80f, 500f, 100f, 80f, 900f).isVisible());
        assertTrue(sample(0f, 81f, 500f, 100f, 80f, 900f).isVisible());
        assertTrue(sample(899f, 999f, 500f, 100f, 80f, 900f).isVisible());
        assertFalse(sample(900f, 1_000f, 500f, 100f, 80f, 900f).isVisible());
    }

    @Test
    public void tallWrappedRowDoesNotDisappearWhenItsCentreLeavesTheViewport() {
        LyricViewportMotionPolicy.State partlyVisible = sample(880f, 1_180f,
                500f, 100f, 80f, 1_000f);
        assertTrue(partlyVisible.isVisible());
        assertTrue(partlyVisible.opacity(false, true, 0.65f) > 0f);
        assertFalse(sample(1_000f, 1_300f,
                500f, 100f, 80f, 1_000f).isVisible());
    }

    @Test
    public void movingTowardEitherEdgeFadesAndBlursWithoutAnInteriorCutoff() {
        for (int direction : new int[]{-1, 1}) {
            float previousOpacity = 0.65f;
            float previousBlur = 0f;
            for (int distance = 100; distance <= 540; distance += 5) {
                float centre = 500f + direction * distance;
                LyricViewportMotionPolicy.State state = sample(centre - 50f, centre + 50f,
                        500f, 100f, 0f, 1_000f);
                float opacity = state.opacity(false, true, 0.65f);
                float blur = state.blurRadius(false, true, 12f);
                assertTrue(state.isVisible());
                assertTrue(opacity > 0f);
                assertTrue(opacity <= previousOpacity + EPSILON);
                assertTrue(blur + EPSILON >= previousBlur);
                previousOpacity = opacity;
                previousBlur = blur;
            }
        }
    }

    @Test
    public void disabledEffectsRespectTheirOwnSwitches() {
        LyricViewportMotionPolicy.State state = sample(900f, 1_000f,
                500f, 100f, 0f, 1_000f);
        assertEquals(1f, state.opacity(false, false, 0.65f), EPSILON);
        assertEquals(0f, state.blurRadius(false, false, 12f), EPSILON);
        assertTrue(state.blurRadius(false, true, 12f) > 0f);
        assertTrue(state.opacity(false, true, 0.65f) < 0.65f);
    }

    @Test
    public void missingLayoutGeometryDoesNotBlankTheLyrics() {
        LyricViewportMotionPolicy.State state = sample(0f, 0f,
                Float.NaN, 0f, 0f, 0f);
        assertFalse(state.hasGeometry());
        assertTrue(state.isVisible());
        assertEquals(0.65f, state.opacity(false, true, 0.65f), EPSILON);
        assertEquals(0f, state.blurRadius(false, true, 12f), EPSILON);
    }

    @Test
    public void reusedOutputDoesNotKeepAnOffscreenRowsHiddenState() {
        LyricViewportMotionPolicy.State state = sample(-200f, -100f,
                500f, 100f, 0f, 1_000f);
        assertFalse(state.isVisible());
        LyricViewportMotionPolicy.resolve(550f, 650f,
                500f, 100f, 0f, 1_000f, state);
        assertTrue(state.isVisible());
        assertEquals(0.65f, state.opacity(false, true, 0.65f), EPSILON);
    }

    @Test
    public void expandingSecondaryContentDoesNotChangeMainDistanceEffects() {
        LyricViewportMotionPolicy.State mainOnly = sample(750f, 850f,
                500f, 100f, 0f, 1_000f);
        LyricViewportMotionPolicy.State expanded = new LyricViewportMotionPolicy.State();
        for (float bottom : new float[]{850f, 950f, 1_300f}) {
            LyricViewportMotionPolicy.resolveAnchored(750f, 850f, 750f, bottom,
                    500f, 100f, 0f, 1_000f, expanded);
            assertEquals(mainOnly.distanceProgress(), expanded.distanceProgress(), EPSILON);
            assertEquals(mainOnly.opacity(false, true, 0.65f),
                    expanded.opacity(false, true, 0.65f), EPSILON);
            assertEquals(mainOnly.blurRadius(false, true, 12f),
                    expanded.blurRadius(false, true, 12f), EPSILON);
        }
    }

    @Test
    public void secondaryPixelsRemainVisibleAfterMainLyricHasLeftViewport() {
        LyricViewportMotionPolicy.State state = new LyricViewportMotionPolicy.State();
        LyricViewportMotionPolicy.resolveAnchored(-120f, -20f, -120f, 180f,
                500f, 100f, 0f, 1_000f, state);
        assertTrue(state.hasGeometry());
        assertTrue(state.isVisible());
        assertEquals(1f, state.distanceProgress(), EPSILON);
        assertTrue(state.opacity(false, true, 0.65f) > 0f);
    }

    @Test
    public void entireContentMustLeaveViewportBeforeRowBecomesInvisible() {
        LyricViewportMotionPolicy.State state = new LyricViewportMotionPolicy.State();
        for (float contentBottom : new float[]{180f, 1f, 0.01f}) {
            LyricViewportMotionPolicy.resolveAnchored(-120f, -20f, -120f, contentBottom,
                    500f, 100f, 0f, 1_000f, state);
            assertTrue(state.isVisible());
            assertTrue(state.opacity(false, true, 0.65f) > 0f);
        }
        LyricViewportMotionPolicy.resolveAnchored(-300f, -200f, -300f, 0f,
                500f, 100f, 0f, 1_000f, state);
        assertFalse(state.isVisible());
        assertEquals(0f, state.opacity(false, true, 0.65f), EPSILON);
        LyricViewportMotionPolicy.resolveAnchored(1_000f, 1_100f, 1_000f, 1_300f,
                500f, 100f, 0f, 1_000f, state);
        assertFalse(state.isVisible());
    }

    private static LyricViewportMotionPolicy.State sample(
            float rowTop, float rowBottom, float focusCenter, float focusHeight,
            float viewportTop, float viewportBottom) {
        LyricViewportMotionPolicy.State state = new LyricViewportMotionPolicy.State();
        LyricViewportMotionPolicy.resolve(rowTop, rowBottom,
                focusCenter, focusHeight, viewportTop, viewportBottom, state);
        return state;
    }
}
