package com.naivety.zhicolor.bridge.render;

import org.junit.Test;
import static org.junit.Assert.*;

public final class WordEmphasisGeometryTest {
    @Test public void outwardCompensationKeepsNominalPrecedingCellEdgeFixed() {
        for (float width : new float[]{8f, 24f, 64f, 137.5f}) {
            for (float requested : new float[]{1f, 1.05f, 1.14f, 1.8f, 3f}) {
                float scale = WordEmphasisGeometry.fitScale(requested, width, 1_000f, true);
                float offset = WordEmphasisGeometry.outwardOffset(scale, width, true);
                double nearEdge = width * 0.5d - scale * (double) width * 0.5d + offset;
                double farEdge = width * 0.5d + scale * (double) width * 0.5d + offset;
                assertEquals(0d, nearEdge, 0.00002d);
                assertEquals((scale - 1d) * width, farEdge - width, 0.00002d);
            }
        }
    }

    @Test public void precedingTextCapsFullWidthExpansionToOutwardRoom() {
        for (float width : new float[]{0.25f, 8f, 33f, 120f}) {
            for (float room : new float[]{0f, 0.01f, 0.7f, 5f, 40f}) {
                for (float requested : new float[]{1f, 1.14f, 2f, 100f}) {
                    float scale = WordEmphasisGeometry.fitScale(requested, width, room, true);
                    assertTrue(scale >= 1f);
                    assertTrue(scale <= requested);
                    assertTrue((scale - 1d) * width <= room);
                }
            }
        }
        assertEquals(1.25f, WordEmphasisGeometry.fitScale(2f, 20f, 5f, true), 0f);
        assertEquals(2.5f, WordEmphasisGeometry.outwardOffset(1.25f, 20f, true), 0f);
    }

    @Test public void firstCellRemainsCenteredAndNeedsOnlyHalfWidthRoom() {
        assertEquals(1.5f, WordEmphasisGeometry.fitScale(2f, 20f, 5f, false), 0f);
        assertEquals(0f, WordEmphasisGeometry.outwardOffset(1.5f, 20f, false), 0f);
        for (float width : new float[]{0.25f, 8f, 33f, 120f}) {
            for (float room : new float[]{0f, 0.01f, 0.7f, 5f, 40f}) {
                float scale = WordEmphasisGeometry.fitScale(100f, width, room, false);
                assertTrue((scale - 1d) * width * 0.5d <= room);
                assertEquals(0f, WordEmphasisGeometry.outwardOffset(scale, width, false), 0f);
            }
        }
    }

    @Test public void loneGlyphFitsBothInkEdgesIncludingTheLeftHostBoundary() {
        for (float leftExtent : new float[]{8f, 18f, 24f}) {
            for (float rightExtent : new float[]{7f, 20f, 28f}) {
                for (float leftRoom : new float[]{0f, 0.1f, 2f, 50f}) {
                    for (float rightRoom : new float[]{0f, 0.2f, 3f, 50f}) {
                        float scale = WordEmphasisGeometry.fitCenteredScale(1.14f,
                                leftExtent, rightExtent, leftRoom, rightRoom);
                        assertTrue(scale >= 1f && scale <= 1.14f);
                        assertTrue((scale - 1d) * leftExtent <= leftRoom);
                        assertTrue((scale - 1d) * rightExtent <= rightRoom);
                    }
                }
            }
        }
        assertEquals(1f, WordEmphasisGeometry.fitCenteredScale(1.14f, 20f, 20f, 0f, 100f), 0f);
        assertEquals(1.05f, WordEmphasisGeometry.fitCenteredScale(1.14f, 20f, 10f, 1f, 100f), 0.000001f);
        assertEquals(1.1f, WordEmphasisGeometry.fitCenteredScale(1.14f, 10f, 20f, 100f, 2f), 0.000001f);
        assertEquals(0f, WordEmphasisGeometry.outwardOffset(1.14f, 40f, false), 0f);
    }

    @Test public void invalidCenteredGeometryDoesNotExpand() {
        for (float invalid : new float[]{Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
            assertEquals(1f, WordEmphasisGeometry.fitCenteredScale(invalid, 10f, 10f, 5f, 5f), 0f);
            assertEquals(1f, WordEmphasisGeometry.fitCenteredScale(1.14f, invalid, 10f, 5f, 5f), 0f);
            assertEquals(1f, WordEmphasisGeometry.fitCenteredScale(1.14f, 10f, invalid, 5f, 5f), 0f);
            assertEquals(1f, WordEmphasisGeometry.fitCenteredScale(1.14f, 10f, 10f, invalid, 5f), 0f);
            assertEquals(1f, WordEmphasisGeometry.fitCenteredScale(1.14f, 10f, 10f, 5f, invalid), 0f);
        }
        assertEquals(1f, WordEmphasisGeometry.fitCenteredScale(1.14f, 0f, 0f, 5f, 5f), 0f);
        assertEquals(1f, WordEmphasisGeometry.fitCenteredScale(1.14f, -1f, 10f, 5f, 5f), 0f);
        assertEquals(1f, WordEmphasisGeometry.fitCenteredScale(1.14f, 10f, -1f, 5f, 5f), 0f);
        assertEquals(1f, WordEmphasisGeometry.fitCenteredScale(0.9f, 10f, 10f, 5f, 5f), 0f);
        assertEquals(1.14f, WordEmphasisGeometry.fitCenteredScale(1.14f, 0f, 10f, 0f, 5f), 0f);
    }

    @Test public void zeroNegativeAndTightRoomConservativelyReduceExpansion() {
        for (boolean preceding : new boolean[]{false, true}) {
            assertEquals(1f, WordEmphasisGeometry.fitScale(1.14f, 30f, 0f, preceding), 0f);
            assertEquals(1f, WordEmphasisGeometry.fitScale(1.14f, 30f, -5f, preceding), 0f);
            assertEquals(1f, WordEmphasisGeometry.fitScale(1f, 30f, 10f, preceding), 0f);
            assertEquals(0f, WordEmphasisGeometry.outwardOffset(1f, 30f, preceding), 0f);
        }
        assertTrue(WordEmphasisGeometry.fitScale(1.14f, 30f, 1f, true) < 1.14f);
        assertEquals(1.14f, WordEmphasisGeometry.fitScale(1.14f, 30f, 20f, true), 0f);
    }

    @Test public void invalidScaleWidthAndRoomAlwaysReturnNeutralGeometry() {
        float[] nonFinite = {Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY};
        for (float invalid : nonFinite) {
            assertEquals(1f, WordEmphasisGeometry.fitScale(invalid, 20f, 5f, true), 0f);
            assertEquals(1f, WordEmphasisGeometry.fitScale(1.14f, invalid, 5f, true), 0f);
            assertEquals(1f, WordEmphasisGeometry.fitScale(1.14f, 20f, invalid, true), 0f);
            assertEquals(0f, WordEmphasisGeometry.outwardOffset(invalid, 20f, true), 0f);
            assertEquals(0f, WordEmphasisGeometry.outwardOffset(1.14f, invalid, true), 0f);
        }
        for (float invalid : new float[]{0f, -1f, -20f}) {
            assertEquals(1f, WordEmphasisGeometry.fitScale(1.14f, invalid, 5f, true), 0f);
            assertEquals(0f, WordEmphasisGeometry.outwardOffset(1.14f, invalid, true), 0f);
        }
        assertEquals(1f, WordEmphasisGeometry.fitScale(0.9f, 20f, 5f, true), 0f);
        assertEquals(0f, WordEmphasisGeometry.outwardOffset(0.9f, 20f, true), 0f);
    }

    @Test public void extremeFiniteFloatsStayFiniteWithoutOverflowOrRoomRoundingUp() {
        assertEquals(2f, WordEmphasisGeometry.fitScale(Float.MAX_VALUE, Float.MAX_VALUE,
                Float.MAX_VALUE, true), 0f);
        assertEquals(3f, WordEmphasisGeometry.fitScale(Float.MAX_VALUE, Float.MAX_VALUE,
                Float.MAX_VALUE, false), 0f);
        assertEquals(1f, WordEmphasisGeometry.fitScale(2f, Float.MAX_VALUE, Float.MIN_VALUE, true), 0f);
        assertEquals(0f, WordEmphasisGeometry.outwardOffset(Float.MAX_VALUE, Float.MAX_VALUE, true), 0f);
        float scale = WordEmphasisGeometry.fitScale(Float.MAX_VALUE, Float.MIN_VALUE,
                Float.MAX_VALUE, true);
        assertTrue(Float.isFinite(scale));
        assertEquals(Float.MAX_VALUE, scale, 0f);
    }

    @Test public void emphasisOnlyGlowConstantsMatchTheRendererContract() {
        assertEquals(7f, WordEmphasisGeometry.EMPHASIS_GLOW_RADIUS_DP, 0f);
        assertEquals(0.65f, WordEmphasisGeometry.EMPHASIS_GLOW_ALPHA, 0f);
    }

    @Test public void positiveAndNegativeBearingsKeepTheAdjacentInkEdgeFixed() {
        for (float width : new float[]{20f, 64f, 137.5f}) {
            for (float bearing : new float[]{-12f, -2.5f, 0f, 3f, 12f}) {
                for (float scale : new float[]{1f, 1.14f, 1.8f, 3f}) {
                    float offset = WordEmphasisGeometry.outwardOffset(scale, width, bearing, true);
                    double transformedInkEdge = width * 0.5d + offset
                            + scale * (bearing - width * 0.5d);
                    assertEquals(bearing, transformedInkEdge, 0.00002d);
                    assertEquals(0f, WordEmphasisGeometry.outwardOffset(scale, width, bearing, false), 0f);
                }
            }
        }
        assertEquals(WordEmphasisGeometry.outwardOffset(1.14f, 40f, true),
                WordEmphasisGeometry.outwardOffset(1.14f, 40f, 0f, true), 0f);
        assertEquals(0f, WordEmphasisGeometry.outwardOffset(1.14f, 40f, Float.NaN, true), 0f);
        assertEquals(0f, WordEmphasisGeometry.outwardOffset(1.14f, 40f, Float.POSITIVE_INFINITY, true), 0f);
        assertEquals(0f, WordEmphasisGeometry.outwardOffset(1.14f, 40f, Float.NEGATIVE_INFINITY, true), 0f);
    }

    @Test public void inkWidthCapAndBearingCompensationTogetherPreserveGapAndOutwardRoom() {
        float cellWidth = 40f, room = 5f;
        for (float bearing : new float[]{-3f, 0f, 4f}) {
            float inkWidth = cellWidth - 2f * bearing;
            float scale = WordEmphasisGeometry.fitScale(1.8f, inkWidth, room, true);
            float offset = WordEmphasisGeometry.outwardOffset(scale, cellWidth, bearing, true);
            double transformedLeft = cellWidth * 0.5d + offset
                    + scale * (bearing - cellWidth * 0.5d);
            double transformedRight = cellWidth * 0.5d + offset
                    + scale * (bearing + inkWidth - cellWidth * 0.5d);
            assertEquals(bearing, transformedLeft, 0.00001d);
            assertTrue(transformedRight - (bearing + inkWidth) <= room + 0.00001d);
        }
    }
}
