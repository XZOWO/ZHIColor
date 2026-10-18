package com.naivety.zhicolor.bridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import android.graphics.Typeface;

import com.naivety.zhicolor.bridge.render.LyricDrawLayoutEngine;
import com.naivety.zhicolor.bridge.render.LyricDrawLine;
import com.naivety.zhicolor.bridge.render.WordLine;

import org.junit.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

public class OfficialLyricTypefaceResolverTest {
    @Test
    public void currentAndInactiveRowsUseTheSameActiveMetrics() throws Exception {
        OfficialLyricTypefaceResolver resolver = new OfficialLyricTypefaceResolver();
        Typeface active = fontToken();
        Typeface inactive = fontToken();
        ColorOs17.C = active;
        ColorOs17.D = inactive;
        ColorOs17 surface = new ColorOs17();

        assertSame(active, resolver.resolve(surface, inactive));
        assertSame(active, resolver.resolve(surface, active));
        assertSame(active, resolver.resolve(surface, inactive));
    }

    @Test
    public void actualSystemFontChangeUpdatesTheSharedSource() throws Exception {
        OfficialLyricTypefaceResolver resolver = new OfficialLyricTypefaceResolver();
        Typeface before = fontToken();
        Typeface after = fontToken();
        ColorOs17.C = before;
        ColorOs17 surface = new ColorOs17();
        assertSame(before, resolver.resolve(surface, fontToken()));

        ColorOs17.C = after;
        assertSame(after, resolver.resolve(surface, before));
        assertSame(after, resolver.resolve(surface, fontToken()));
    }

    @Test
    public void nullActiveFontCanInitializeAfterTheFirstFrame() throws Exception {
        OfficialLyricTypefaceResolver resolver = new OfficialLyricTypefaceResolver();
        Typeface fallback = fontToken();
        Typeface active = fontToken();
        ColorOs17.C = null;
        ColorOs17 surface = new ColorOs17();

        assertSame(fallback, resolver.resolve(surface, fallback));
        assertSame(fallback, resolver.resolve(surface, fontToken()));
        ColorOs17.C = active;
        assertSame(active, resolver.resolve(surface, fallback));
        ColorOs17.C = null;
        assertSame(active, resolver.resolve(surface, fallback));
    }

    @Test
    public void inheritedCompleteLayoutUsesItsStaticPair() throws Exception {
        OfficialLyricTypefaceResolver resolver = new OfficialLyricTypefaceResolver();
        Typeface active = fontToken();
        ColorOs17.C = active;
        assertSame(active, resolver.resolve(new ColorOs17Child(), fontToken()));
    }

    @Test
    public void incompleteOrWrongFieldsNeverAdoptAnUnverifiedC() throws Exception {
        OfficialLyricTypefaceResolver resolver = new OfficialLyricTypefaceResolver();
        Typeface observed = fontToken();
        Unverified.C = fontToken();
        WrongStatic.C = fontToken();
        WrongType.C = fontToken();

        assertFallbackPinned(resolver, new Unverified(), observed);
        assertFallbackPinned(resolver, new WrongStatic(), observed);
        assertFallbackPinned(resolver, new WrongType(), observed);
    }

    @Test
    public void fallbackIsIsolatedPerSurfaceAndAcceptsLateObservedFont() throws Exception {
        OfficialLyricTypefaceResolver resolver = new OfficialLyricTypefaceResolver();
        Object first = new Object();
        Object second = new Object();
        Typeface one = fontToken();
        Typeface two = fontToken();
        assertNull(resolver.resolve(first, null));
        assertSame(one, resolver.resolve(first, one));
        assertSame(two, resolver.resolve(second, two));
        assertSame(one, resolver.resolve(first, two));
        assertSame(two, resolver.resolve(second, one));
        assertSame(two, resolver.resolve(null, two));
        assertSame(one, resolver.resolve(null, one));
    }

    @Test
    public void boundaryWordKeepsItsRowAcrossFocusButRealFontChangesRewrap() throws Exception {
        OfficialLyricTypefaceResolver resolver = new OfficialLyricTypefaceResolver();
        Typeface active = fontToken();
        Typeface inactive = fontToken();
        Typeface replacement = fontToken();
        ColorOs17.C = active;
        ColorOs17.D = inactive;
        ColorOs17 surface = new ColorOs17();
        Typeface[] measuredFont = { inactive };
        LyricDrawLayoutEngine engine = new LyricDrawLayoutEngine((text, start, end) ->
                (end - start) * (measuredFont[0] == active ? 10f : 7f));
        String text = "one two three";
        WordLine nativeInactiveLine = new WordLine(0L, text, new ArrayList<>());
        WordLine line = new WordLine(0L, text, new ArrayList<>());

        // The fixture crosses the word-wrap threshold when the vendor changes C/D.
        engine.build(nativeInactiveLine, text, 100f, false, false, 240, inactive);
        List<String> nativeInactiveRows = rowBoundaries(engine);
        measuredFont[0] = resolver.resolve(surface, inactive);
        engine.build(line, text, 100f, false, false, 240, measuredFont[0]);
        List<String> beforeFocus = rowBoundaries(engine);
        assertEquals(2, beforeFocus.size());
        assertFalse(nativeInactiveRows.equals(beforeFocus));

        measuredFont[0] = resolver.resolve(surface, active);
        engine.build(line, text, 100f, false, false, 240, measuredFont[0]);
        assertEquals(beforeFocus, rowBoundaries(engine));
        measuredFont[0] = resolver.resolve(surface, inactive);
        engine.build(line, text, 100f, false, false, 240, measuredFont[0]);
        assertEquals(beforeFocus, rowBoundaries(engine));

        ColorOs17.C = replacement;
        measuredFont[0] = resolver.resolve(surface, inactive);
        engine.build(line, text, 100f, false, false, 240, measuredFont[0]);
        assertEquals(1, engine.lines().size());
        assertFalse(beforeFocus.equals(rowBoundaries(engine)));
    }

    private static List<String> rowBoundaries(LyricDrawLayoutEngine engine) {
        ArrayList<String> rows = new ArrayList<>();
        for (LyricDrawLine row : engine.lines()) rows.add(row.start + ":" + row.end);
        return rows;
    }

    private static void assertFallbackPinned(OfficialLyricTypefaceResolver resolver,
            Object surface, Typeface observed) throws Exception {
        assertSame(observed, resolver.resolve(surface, observed));
        assertSame(observed, resolver.resolve(surface, fontToken()));
    }

    /** Identity-only font tokens: these tests never call Android native font methods. */
    private static Typeface fontToken() throws Exception {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field field = unsafeClass.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        Object unsafe = field.get(null);
        return (Typeface) unsafeClass.getMethod("allocateInstance", Class.class)
                .invoke(unsafe, Typeface.class);
    }

    private enum LyricsSwitchPhase { IDLE }

    private static class CompleteFields {
        int c, i, j;
        long k;
        float n, r;
        LyricsSwitchPhase w = LyricsSwitchPhase.IDLE;
    }

    private static class ColorOs17 extends CompleteFields {
        static Typeface C;
        static Typeface D;
    }

    private static final class ColorOs17Child extends ColorOs17 {}

    private static final class Unverified {
        static Typeface C;
        static Typeface D;
    }

    private static final class WrongStatic extends CompleteFields {
        static Typeface C;
        Typeface D;
    }

    private static final class WrongType extends CompleteFields {
        static Typeface C;
        static float D;
    }
}
