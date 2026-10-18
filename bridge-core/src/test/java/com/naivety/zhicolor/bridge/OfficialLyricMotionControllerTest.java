package com.naivety.zhicolor.bridge;

import java.lang.reflect.Field;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

public class OfficialLyricMotionControllerTest {
    @Test
    public void restoresBothDurationsAndTheExactNativeInterpolator() throws Exception {
        OfficialLyricMotionController.ScrollStateStore store =
                new OfficialLyricMotionController.ScrollStateStore();
        Recycler recycler = new Recycler(340L, 440L);
        Object nativeInterpolator = recycler.l.mDecelerateInterpolator;

        configure(store, recycler, new Object(), 300L, 620L);
        assertEquals(300L, recycler.k);
        assertEquals(620L, recycler.l.a);
        store.restoreAll();

        assertEquals(340L, recycler.k);
        assertEquals(440L, recycler.l.a);
        assertSame(nativeInterpolator, recycler.l.mDecelerateInterpolator);
        assertEquals(27, recycler.other);
    }

    @Test
    public void reconfigurationAndAnimationToggleNeverReplaceTheFirstSnapshot() throws Exception {
        OfficialLyricMotionController.ScrollStateStore store =
                new OfficialLyricMotionController.ScrollStateStore();
        Recycler recycler = new Recycler(340L, 440L);
        Object nativeInterpolator = recycler.l.mDecelerateInterpolator;

        configure(store, recycler, new Object(), 300L, 620L);
        configure(store, recycler, new Object(), 0L, 1L);
        assertEquals(0L, recycler.k);
        assertEquals(1L, recycler.l.a);
        configure(store, recycler, new Object(), 300L, 620L);
        store.restoreAll();

        assertEquals(340L, recycler.k);
        assertEquals(440L, recycler.l.a);
        assertSame(nativeInterpolator, recycler.l.mDecelerateInterpolator);
    }

    @Test
    public void everyRecyclerRestoresItsOwnNativeState() throws Exception {
        OfficialLyricMotionController.ScrollStateStore store =
                new OfficialLyricMotionController.ScrollStateStore();
        Recycler first = new Recycler(340L, 440L);
        Recycler second = new Recycler(280L, 380L);
        Object firstInterpolator = first.l.mDecelerateInterpolator;
        Object secondInterpolator = second.l.mDecelerateInterpolator;
        configure(store, first, new Object(), 300L, 620L);
        configure(store, second, new Object(), 0L, 1L);

        store.restoreAll();
        assertEquals(340L, first.k);
        assertEquals(440L, first.l.a);
        assertSame(firstInterpolator, first.l.mDecelerateInterpolator);
        assertEquals(280L, second.k);
        assertEquals(380L, second.l.a);
        assertSame(secondInterpolator, second.l.mDecelerateInterpolator);
    }

    @Test
    public void successfulRestoreClearsTheSnapshotForTheNextEnableCycle() throws Exception {
        OfficialLyricMotionController.ScrollStateStore store =
                new OfficialLyricMotionController.ScrollStateStore();
        Recycler recycler = new Recycler(340L, 440L);
        configure(store, recycler, new Object(), 300L, 620L);
        store.restoreAll();
        recycler.k = 360L;
        recycler.l.a = 460L;
        Object replacementNativeInterpolator = new Object();
        recycler.l.mDecelerateInterpolator = replacementNativeInterpolator;

        store.restoreAll();
        assertEquals(360L, recycler.k);
        configure(store, recycler, new Object(), 0L, 1L);
        store.restoreAll();

        assertEquals(360L, recycler.k);
        assertEquals(460L, recycler.l.a);
        assertSame(replacementNativeInterpolator, recycler.l.mDecelerateInterpolator);
    }

    private static void configure(
            OfficialLyricMotionController.ScrollStateStore store,
            Recycler recycler,
            Object interpolator,
            long rowMillis,
            long scrollMillis) throws ReflectiveOperationException {
        Field rowDuration = Recycler.class.getDeclaredField("k");
        Field scrollDuration = Scroller.class.getDeclaredField("a");
        Field scrollInterpolator = Scroller.class.getDeclaredField("mDecelerateInterpolator");
        rowDuration.setAccessible(true);
        scrollDuration.setAccessible(true);
        scrollInterpolator.setAccessible(true);
        store.configure(recycler, rowDuration, recycler.l, scrollDuration,
                scrollInterpolator, interpolator, rowMillis, scrollMillis);
    }

    private static final class Recycler {
        long k;
        final Scroller l;
        int other = 27;

        Recycler(long rowMillis, long scrollMillis) {
            k = rowMillis;
            l = new Scroller(scrollMillis);
        }
    }

    private static final class Scroller {
        long a;
        Object mDecelerateInterpolator = new Object();

        Scroller(long scrollMillis) {
            a = scrollMillis;
        }
    }
}
