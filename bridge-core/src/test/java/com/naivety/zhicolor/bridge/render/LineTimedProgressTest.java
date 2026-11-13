package com.naivety.zhicolor.bridge.render;

import org.junit.Test;
import static org.junit.Assert.*;

public final class LineTimedProgressTest {
    @Test public void earlySelectedIncomingRowDoesNotFlashBeforeItsTimestamp() {
        assertEquals(0f, width(950L, 300f, 0f, 300f), 0f);
        assertEquals(0f, width(999L, 300f, 0f, 300f), 0f);
        assertEquals(0f, width(1000L, 300f, 0f, 300f), 0f);
        assertEquals(0.15f, width(1001L, 300f, 0f, 300f), 0.00001f);
        assertEquals(150f, width(2000L, 300f, 0f, 300f), 0f);
        assertEquals(300f, width(3000L, 300f, 0f, 300f), 0f);
    }

    @Test public void wrappedRowsShareOneContinuousFrontAcrossUnequalWidths() {
        float[] widths = {80f, 140f, 60f};
        float lastTotal = 0f;
        for (long position = 900L; position <= 3200L; position++) {
            float before = 0f, revealed = 0f;
            for (float segment : widths) {
                float visible = width(position, 280f, before, segment);
                assertTrue(visible >= 0f && visible <= segment);
                if (revealed < before) assertEquals(0f, visible, 0f);
                revealed += visible; before += segment;
            }
            assertTrue(revealed >= lastTotal);
            assertEquals(width(position, 280f, 0f, 280f), revealed, 0.00004f);
            lastTotal = revealed;
        }
    }

    @Test public void outgoingCompletionDoesNotResetAndSeekSamplesItsNewPosition() {
        float previous = width(2900L, 280f, 80f, 140f);
        for (long position = 2900L; position <= 3400L; position += 10L) {
            float current = width(position, 280f, 80f, 140f);
            assertTrue(current >= previous); previous = current;
        }
        assertEquals(140f, previous, 0f);
        assertEquals(0f, width(950L, 280f, 80f, 140f), 0f);
        assertEquals(60f, width(2000L, 280f, 80f, 140f), 0f);
    }

    @Test public void invalidGeometryAndIntervalsCannotRevealText() {
        for (float bad : new float[]{Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -1f}) {
            assertEquals(0f, width(2000L, bad, 0f, 100f), 0f);
            assertEquals(0f, width(2000L, 100f, bad, 100f), 0f);
            assertEquals(0f, width(2000L, 100f, 0f, bad), 0f);
        }
        assertEquals(0f, LineTimedProgress.revealWidth(2000L, 1000L, 1000L, 100f, 0f, 100f), 0f);
        assertEquals(0f, LineTimedProgress.revealWidth(2000L, 3000L, 1000L, 100f, 0f, 100f), 0f);
    }

    private static float width(long position, float total, float before, float segment) {
        return LineTimedProgress.revealWidth(position, 1000L, 3000L, total, before, segment);
    }
}
