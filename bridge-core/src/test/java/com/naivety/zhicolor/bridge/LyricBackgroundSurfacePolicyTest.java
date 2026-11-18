package com.naivety.zhicolor.bridge;

import android.graphics.drawable.Drawable;
import android.view.ViewGroup;

import org.junit.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.Assert.*;

public class LyricBackgroundSurfacePolicyTest {
    @Test public void visibleBackgroundDoesNotRequireAnObservedSectionBind() {
        assertTrue(LyricBackgroundSurfacePolicy.visible(true, false, true));
    }
    @Test public void observedSectionRemainsSupported() {
        assertTrue(LyricBackgroundSurfacePolicy.visible(true, true, false));
    }
    @Test public void systemChoiceNeverTakesOverBackground() {
        assertFalse(LyricBackgroundSurfacePolicy.visible(false, true, true));
    }
    @Test public void hiddenSurfacesDoNotRun() {
        assertFalse(LyricBackgroundSurfacePolicy.visible(true, false, false));
        assertFalse(LyricBackgroundSurfacePolicy.running(false, true, true));
    }
    @Test public void awakeVisibleBackgroundRunsWithoutLyricOwnership() {
        assertTrue(LyricBackgroundSurfacePolicy.running(
                LyricBackgroundSurfacePolicy.visible(true, false, true), true, true));
    }
    @Test public void nonInteractiveOrOffDozingDisplayPausesRendering() {
        assertFalse(LyricBackgroundSurfacePolicy.running(true, false, true));
        assertFalse(LyricBackgroundSurfacePolicy.running(true, false, false));
        assertFalse(LyricBackgroundSurfacePolicy.running(true, true, false));
    }
    @Test public void screenWakeResumesAndSleepStopsAgain() {
        boolean visible = LyricBackgroundSurfacePolicy.visible(true, false, true);
        assertFalse(LyricBackgroundSurfacePolicy.running(visible, false, false));
        assertTrue(LyricBackgroundSurfacePolicy.running(visible, true, true));
        assertFalse(LyricBackgroundSurfacePolicy.running(visible, false, false));
    }

    private static final class FakeLyricView {}
    private interface FakeRenderer {}
    private interface FakeBgModel {}

    private static final class FakeControllerV1 {
        FakeRenderer i;
        FakeBgModel j;
        void d(FakeBgModel model, FakeRenderer previous, FakeRenderer next) {}
        void f(FakeBgModel model) {}
        void g(FakeBgModel model) {}
    }

    private static final class FakeControllerV2 {
        FakeRenderer i;
        FakeBgModel j;
        void d(boolean visible) {}
        void e(FakeBgModel model, FakeRenderer previous, FakeRenderer next) {}
        void f(Drawable drawable, boolean anim) {}
        void g(FakeBgModel model) {}
        void h(FakeBgModel model) {}
    }

    private static final class FakeSectionV1 {
        FakeLyricView n;
        FakeControllerV1 x;
        void q(ViewGroup host) {}
    }

    private static final class FakeListenerReplacingY0 {
        FakeSectionV2 a;
        void a(int from, int to) {}
        void b(int from, int to) {}
    }

    private static final class FakeSectionV2 {
        FakeLyricView n;
        FakeControllerV2 x;
        void q(ViewGroup host) {}
    }

    @Test public void resolvesImmersiveSectionAcrossInitialAndRebuiltLayouts() throws Exception {
        Method v1 = LyricBackgroundSurfacePolicy.findSectionBindMethod(
                new Class<?>[]{FakeSectionV1.class, FakeListenerReplacingY0.class},
                FakeLyricView.class, FakeControllerV1.class);
        assertEquals(FakeSectionV1.class, v1.getDeclaringClass());
        assertEquals("q", v1.getName());

        Method v2 = LyricBackgroundSurfacePolicy.findSectionBindMethod(
                new Class<?>[]{FakeListenerReplacingY0.class, FakeSectionV2.class},
                FakeLyricView.class, FakeControllerV2.class);
        assertEquals(FakeSectionV2.class, v2.getDeclaringClass());
        assertEquals("q", v2.getName());
    }

    @Test public void resolvesBackgroundControllerMethodsAcrossBothLayouts() throws Exception {
        List<Method> v1Methods = LyricBackgroundSurfacePolicy.findBackgroundControllerMethods(
                FakeControllerV1.class);
        assertEquals(Arrays.asList("d", "f", "g"),
                v1Methods.stream().map(Method::getName).collect(Collectors.toList()));

        List<Method> v2Methods = LyricBackgroundSurfacePolicy.findBackgroundControllerMethods(
                FakeControllerV2.class);
        assertEquals(Arrays.asList("e", "g", "h"),
                v2Methods.stream().map(Method::getName).collect(Collectors.toList()));
    }
}
