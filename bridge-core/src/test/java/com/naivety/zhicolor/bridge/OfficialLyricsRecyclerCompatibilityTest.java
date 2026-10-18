package com.naivety.zhicolor.bridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.lang.reflect.Field;

public class OfficialLyricsRecyclerCompatibilityTest {
    @Test
    public void oldAndNewLayoutUsesIntUWithoutChangingDuration() {
        UvCLayout recycler = new UvCLayout();

        OfficialLyricsRecyclerCompatibility.Binding binding =
                OfficialLyricsRecyclerCompatibility.resolve(recycler.getClass());

        assertNotNull(binding);
        assertEquals("u/v/C", binding.layoutName);
        assertEquals(Integer.valueOf(12), binding.readLineSpacing(recycler));
        assertTrue(binding.writeLineSpacing(recycler, -20));
        assertEquals(-20, recycler.u);
        assertEquals(340L, recycler.v);
        assertEquals(Long.valueOf(340L), binding.readAnimationDuration(recycler));
        assertTrue(binding.writeAnimationDuration(recycler, 300L));
        assertEquals(300L, recycler.v);
        assertEquals(-20, recycler.u);
        assertTrue(binding.writeInactiveScale(recycler, 0.82f));
        assertEquals(0.82f, recycler.C, 0.0001f);
    }

    @Test
    public void userLayoutUsesIntTAndNeverWritesLongU() {
        TuALayout recycler = new TuALayout();

        OfficialLyricsRecyclerCompatibility.Binding binding =
                OfficialLyricsRecyclerCompatibility.resolve(recycler.getClass());

        assertNotNull(binding);
        assertEquals("t/u/A", binding.layoutName);
        assertEquals(Integer.valueOf(8), binding.readLineSpacing(recycler));
        assertTrue(binding.writeLineSpacing(recycler, -20));
        assertEquals(-20, recycler.t);
        assertEquals(340L, recycler.u);
        assertEquals(Long.valueOf(340L), binding.readAnimationDuration(recycler));
        assertTrue(binding.writeAnimationDuration(recycler, 300L));
        assertEquals(300L, recycler.u);
        assertEquals(-20, recycler.t);
        assertTrue(binding.writeInactiveScale(recycler, 0.88f));
        assertEquals(0.88f, recycler.A, 0.0001f);
    }

    @Test
    public void ambiguousFieldNameWithoutVerifiedFingerprintIsRejected() {
        assertNull(OfficialLyricsRecyclerCompatibility.resolve(
                DangerousPartialLayout.class));
        assertNull(OfficialLyricsRecyclerCompatibility.resolve(
                UnknownLayout.class));
    }

    @Test
    public void primitiveAccessorsFailClosedForWrongReceiver() {
        OfficialLyricsRecyclerCompatibility.Binding binding =
                OfficialLyricsRecyclerCompatibility.resolve(UvCLayout.class);

        assertNotNull(binding);
        assertNull(binding.readLineSpacing(new Object()));
        assertFalse(binding.writeLineSpacing(new Object(), -20));
        assertNull(binding.readInactiveScale(new Object()));
        assertFalse(binding.writeInactiveScale(new Object(), 0.9f));
        assertNull(binding.readAnimationDuration(new Object()));
        assertFalse(binding.writeAnimationDuration(new Object(), 300L));
    }

    @Test
    public void colorOs17UsesJForSpacingKForDurationAndRForScale() {
        ColorOs17Child recycler = new ColorOs17Child();
        OfficialLyricsRecyclerCompatibility.Binding binding =
                OfficialLyricsRecyclerCompatibility.resolve(recycler.getClass());

        assertNotNull(binding);
        assertEquals("j/k/r", binding.layoutName);
        assertEquals(Integer.valueOf(18), binding.readLineSpacing(recycler));
        assertEquals(Long.valueOf(340L), binding.readAnimationDuration(recycler));
        assertTrue(binding.writeLineSpacing(recycler, -20));
        assertTrue(binding.writeAnimationDuration(recycler, 300L));
        assertTrue(binding.writeInactiveScale(recycler, 0.82f));
        assertEquals(-20, recycler.j);
        assertEquals(300L, recycler.k);
        assertEquals(0.82f, recycler.r, 0.0001f);
        assertEquals(600, recycler.i);
        assertEquals(9, recycler.c);
        assertEquals(1.5f, recycler.n, 0f);
    }

    @Test
    public void colorOs17BindingRequiresItsFullPrimitiveAndPhaseFingerprint() {
        assertNull(OfficialLyricsRecyclerCompatibility.resolve(
                PartialColorOs17Layout.class));
        assertNull(OfficialLyricsRecyclerCompatibility.resolve(
                WrongColorOs17Duration.class));
        assertNull(OfficialLyricsRecyclerCompatibility.resolve(
                WrongColorOs17Phase.class));
        assertNull(OfficialLyricsRecyclerCompatibility.resolve(
                LegacyIndexWithColorOs17Names.class));
    }

    @Test
    public void semanticFieldsCanUseArbitraryObfuscatedNames() throws Exception {
        Field spacing = FutureLayout.class.getDeclaredField("q");
        Field duration = FutureLayout.class.getDeclaredField("r");
        Field scale = FutureLayout.class.getDeclaredField("s");

        OfficialLyricsRecyclerCompatibility.Binding binding =
                OfficialLyricsRecyclerCompatibility.fromResolvedFields(
                        FutureLayout.class,
                        spacing,
                        duration,
                        scale,
                        "dexkit:q/r/s");

        FutureLayout recycler = new FutureLayout();
        assertNotNull(binding);
        assertEquals("dexkit:q/r/s", binding.layoutName);
        assertTrue(binding.writeLineSpacing(recycler, -20));
        assertEquals(-20, recycler.q);
        assertEquals(340L, recycler.r);
        assertTrue(binding.writeInactiveScale(recycler, 0.84f));
        assertEquals(0.84f, recycler.s, 0.0001f);
    }

    @Test
    public void semanticFieldsRejectWrongPrimitiveRoles() throws Exception {
        assertNull(OfficialLyricsRecyclerCompatibility.fromResolvedFields(
                FutureLayout.class,
                FutureLayout.class.getDeclaredField("r"),
                FutureLayout.class.getDeclaredField("q"),
                FutureLayout.class.getDeclaredField("s"),
                "dexkit:wrong"));
    }

    private static final class UvCLayout {
        int u = 12;
        long v = 340L;
        float C = 0.9f;
    }

    private static final class TuALayout {
        int t = 8;
        long u = 340L;
        float A = 0.9f;
    }

    private static final class DangerousPartialLayout {
        long u = 340L;
        float A = 0.9f;
    }

    private static final class UnknownLayout {
        int q = 8;
        long r = 340L;
        float s = 0.9f;
    }

    private static final class FutureLayout {
        int q = 8;
        long r = 340L;
        float s = 0.9f;
    }

    private enum LyricsSwitchPhase { IDLE }

    private static class ColorOs17Layout {
        int c = 9;
        int i = 600;
        int j = 18;
        long k = 340L;
        float n = 1.5f;
        float r = 0.9f;
        LyricsSwitchPhase w = LyricsSwitchPhase.IDLE;
    }

    private static final class ColorOs17Child extends ColorOs17Layout {
    }

    private static final class PartialColorOs17Layout {
        int j = 18;
        long k = 340L;
        float r = 0.9f;
    }

    private static final class WrongColorOs17Duration extends ColorOs17Layout {
        int k = 340;
    }

    private static final class WrongColorOs17Phase extends ColorOs17Layout {
        Object w = new Object();
    }

    private static final class LegacyIndexWithColorOs17Names extends ColorOs17Layout {
        int n = 5;
    }
}
