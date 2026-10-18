package com.naivety.zhicolor.bridge;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class NativeLyricsAnchorPolicyTest {
    @Test
    public void immediateConsumerRequiresTheVerifiedLayoutAndExactSignature() {
        assertNotNull(NativeLyricsAnchorPolicy.immediatePositionMethod(Recycler.class));
        assertNull(NativeLyricsAnchorPolicy.immediatePositionMethod(UnverifiedRecycler.class));
        assertNull(NativeLyricsAnchorPolicy.immediatePositionMethod(WrongOffsetType.class));
        assertNull(NativeLyricsAnchorPolicy.immediatePositionMethod(StaticConsumer.class));
    }

    @Test
    public void unknownScrollerShapesRetainTheirNativeGeometry() {
        assertNull(NativeLyricsAnchorPolicy.smoothFitMethod(Recycler.class));
        assertNull(NativeLyricsAnchorPolicy.recyclerFromScroller(new Object()));
        assertNull(NativeLyricsAnchorPolicy.recyclerFromScroller(null));
    }

    @Test
    public void eachRecyclerRestoresItsOwnFirstOffsetAfterRepeatedTargets() {
        NativeLyricsAnchorPolicy.TopOffsetStateStore store =
                new NativeLyricsAnchorPolicy.TopOffsetStateStore();
        Recycler first = new Recycler(24);
        Recycler second = new Recycler(48);
        assertTrue(store.write(first, 150));
        assertTrue(store.write(first, 162));
        assertTrue(store.write(second, 190));
        assertEquals(162, first.q);
        assertEquals(190, second.q);
        store.restore(first);
        assertEquals(24, first.q);
        assertEquals(190, second.q);
        store.restoreAll();
        assertEquals(24, first.q);
        assertEquals(48, second.q);
        assertEquals(7, first.c);
        assertEquals(300L, first.k);
    }

    @Test
    public void restoredStateCanCaptureANewNativeOffsetOnTheNextEnable() {
        NativeLyricsAnchorPolicy.TopOffsetStateStore store =
                new NativeLyricsAnchorPolicy.TopOffsetStateStore();
        Recycler recycler = new Recycler(24);
        assertTrue(store.write(recycler, 24));
        assertTrue(store.write(recycler, 150));
        store.restoreAll();
        recycler.q = 36;
        assertTrue(store.write(recycler, 170));
        store.restoreAll();
        assertEquals(36, recycler.q);
    }

    @Test
    public void offsetWritesRejectOtherFirmwaresAndOtherFieldTypes() {
        NativeLyricsAnchorPolicy.TopOffsetStateStore store =
                new NativeLyricsAnchorPolicy.TopOffsetStateStore();
        UnverifiedRecycler other = new UnverifiedRecycler();
        WrongOffsetType changed = new WrongOffsetType();
        assertFalse(store.write(other, 120));
        assertFalse(store.write(changed, 120));
        assertFalse(store.write(null, 120));
        assertEquals(17, other.q);
        assertEquals(19L, changed.q);
    }

    @Test
    public void modelIndexReadsRequireTheVerifiedListAndIntegerFields() {
        assertEquals(3, NativeLyricsAnchorPolicy.modelTargetIndex(new Model()));
        assertEquals(-1, NativeLyricsAnchorPolicy.modelTargetIndex(new WrongModel()));
        assertEquals(-1, NativeLyricsAnchorPolicy.modelTargetIndex(new Object()));
        assertEquals(-1, NativeLyricsAnchorPolicy.modelTargetIndex(null));
    }

    private enum LyricsSwitchPhase { IDLE }

    private static class VerifiedLayout {
        int c = 7;
        int i;
        int j;
        long k = 300L;
        float n;
        float r = 0.9f;
        LyricsSwitchPhase w = LyricsSwitchPhase.IDLE;
    }

    private static final class Recycler extends VerifiedLayout {
        int q;

        Recycler() { this(24); }
        Recycler(int offset) { q = offset; }
        public void n(int index) {}
    }

    private static final class UnverifiedRecycler {
        int q = 17;
        public void n(int index) {}
    }

    private static final class WrongOffsetType extends VerifiedLayout {
        long q = 19L;
        public void n(int index) {}
    }

    private static final class StaticConsumer extends VerifiedLayout {
        int q;
        public static void n(int index) {}
    }

    private static final class Model {
        List<String> a = Arrays.asList("one", "two", "three", "four");
        int b = 3;
    }

    private static final class WrongModel {
        List<String> a = Arrays.asList("one");
        long b = 0L;
    }
}
