package com.naivety.zhicolor.bridge.systemui.lyrics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class LyricsRecyclerFieldAccessorTest {
    private final LyricsRecyclerFieldAccessor accessor = new LyricsRecyclerFieldAccessor();

    @Test
    public void readsInheritedPrimitiveCurrentIndex() {
        PrimitiveChild recycler = new PrimitiveChild();
        assertEquals(7, accessor.readCurrentIndex(recycler, -1));

        recycler.setCurrentIndex(11);
        assertEquals(11, accessor.readCurrentIndex(recycler, -1));
    }

    @Test
    public void readsBoxedCurrentIndex() {
        assertEquals(5, accessor.readCurrentIndex(new BoxedRecycler(), -1));
    }

    @Test
    public void rejectsMissingOrWrongTypedField() {
        assertEquals(-1, accessor.readCurrentIndex(new MissingRecycler(), -1));
        assertEquals(-1, accessor.readCurrentIndex(new WrongTypeRecycler(), -1));
        assertEquals(-1, accessor.readCurrentIndex(null, -1));
    }

    @Test
    public void readsColorOs17IndexUsingTheCompleteInheritedFingerprint() {
        ColorOs17Child recycler = new ColorOs17Child();
        assertTrue(LyricsRecyclerFieldAccessor.isColorOs17Layout(recycler.getClass()));
        assertEquals(13, accessor.readCurrentIndex(recycler, -1));

        recycler.c = 17;
        assertEquals(17, accessor.readCurrentIndex(recycler, -1));
        assertEquals(1.5f, recycler.n, 0f);
    }

    @Test
    public void doesNotMistakeAnOldCFieldForTheCurrentIndex() {
        LegacyWithC recycler = new LegacyWithC();
        assertEquals(7, accessor.readCurrentIndex(recycler, -1));
        assertFalse(LyricsRecyclerFieldAccessor.isColorOs17Layout(recycler.getClass()));
        assertEquals(-1, accessor.readCurrentIndex(new COnlyRecycler(), -1));
    }

    @Test
    public void rejectsPartialWrongTypedOrStaticColorOs17Fingerprints() {
        assertEquals(-1, accessor.readCurrentIndex(new MissingPhaseRecycler(), -1));
        assertEquals(-1, accessor.readCurrentIndex(new WrongDurationRecycler(), -1));
        assertEquals(-1, accessor.readCurrentIndex(new WrongPhaseRecycler(), -1));
        assertEquals(-1, accessor.readCurrentIndex(new StaticIndexRecycler(), -1));
        assertEquals(-1, accessor.readCurrentIndex(new StaticLegacyRecycler(), -1));
    }

    private static class PrimitiveBase {
        private int n = 7;

        void setCurrentIndex(int value) {
            n = value;
        }
    }

    private static final class PrimitiveChild extends PrimitiveBase {
    }

    private static final class BoxedRecycler {
        private Integer n = 5;
    }

    private static final class MissingRecycler {
    }

    private static final class WrongTypeRecycler {
        @SuppressWarnings("unused")
        private String n = "5";
    }

    private enum LyricsSwitchPhase { IDLE }

    private static class ColorOs17Recycler {
        int c = 13;
        int i = 600;
        int j = 12;
        long k = 300L;
        float n = 1.5f;
        float r = 0.9f;
        LyricsSwitchPhase w = LyricsSwitchPhase.IDLE;
    }

    private static final class ColorOs17Child extends ColorOs17Recycler {
    }

    private static final class LegacyWithC {
        int c = 99;
        int n = 7;
    }

    private static final class COnlyRecycler {
        int c = 13;
    }

    private static final class MissingPhaseRecycler {
        int c = 13;
        int i = 600;
        int j = 12;
        long k = 300L;
        float n = 1.5f;
        float r = 0.9f;
    }

    private static final class WrongDurationRecycler extends ColorOs17Recycler {
        int k = 300;
    }

    private static final class WrongPhaseRecycler extends ColorOs17Recycler {
        Object w = new Object();
    }

    private static final class StaticIndexRecycler extends ColorOs17Recycler {
        static int c = 13;
    }

    private static final class StaticLegacyRecycler {
        static int n = 5;
    }
}
