package com.naivety.zhicolor.bridge;

import org.junit.Test;
import java.util.Map;
import static org.junit.Assert.*;

public class SystemShapedArtworkPolicyTest {
    private static final String SECTION = "com.oplus.systemui.plugins.shared.template.section.media.";
    private static final String MODEL = "com.oplus.systemui.plugins.shared.template.section.model.media.";
    private static final String DATA = "com.oplus.systemui.plugins.shared.data.media.model.";

    private static SystemShapedArtworkPolicy policy() {
        return new SystemShapedArtworkPolicy(new SystemShapedArtworkPolicy.NativeAccess(Map.ofEntries(
                Map.entry(SECTION + "MPImmersiveSectionViewModel$bgModel$1", Builder.class),
                Map.entry(SECTION + "q", Owner.class),
                Map.entry(MODEL + "b", FullScreen.class), Map.entry(MODEL + "c", FlowLight.class),
                Map.entry(DATA + "t", Media.class), Map.entry(DATA + "y", Artwork.class),
                Map.entry(DATA + "A", Icon.class), Map.entry(DATA + "b", Result.class),
                Map.entry(DATA + "a", Palette.class),
                Map.entry("android.graphics.drawable.Drawable", Drawable.class),
                Map.entry("com.oplus.systemui.plugins.r8.jj", ReadOnly.class),
                Map.entry("com.oplus.systemui.plugins.r8.Lg", State.class),
                Map.entry("kotlinx.coroutines.flow.p", Mutable.class))));
    }

    @Test public void defaultRetainsNativeModelAndForcedModeUsesExactOemPaletteWithoutGeometryChanges() {
        SystemShapedArtworkPolicy policy = policy();
        Builder builder = new Builder();
        FullScreen original = new FullScreen();
        assertSame(original, policy.modelBuilt(builder, original));
        policy.setEnabled(true);
        FlowLight forced = (FlowLight) policy.modelBuilt(builder, original);
        assertSame(original.a, forced.a);
        assertArrayEquals(new int[]{11, 44, 77, 77, 66}, forced.colors);
        assertEquals(1000, forced.a.width);
        assertEquals(1000, forced.a.height);
    }

    @Test public void togglingPublishesToNativeStateAndRestoresOriginalIdentity() {
        SystemShapedArtworkPolicy policy = policy();
        Builder builder = new Builder();
        FullScreen original = new FullScreen();
        Mutable state = builder.this$0.mutable();
        state.value = policy.modelBuilt(builder, original);
        policy.setEnabled(true);
        assertTrue(state.value instanceof FlowLight);
        assertEquals(1, state.writes);
        Object forced = state.value;
        policy.setEnabled(true);
        assertSame(forced, state.value);
        assertEquals(1, state.writes);
        policy.setEnabled(false);
        assertSame(original, state.value);
        assertEquals(2, state.writes);
    }

    @Test public void realNonFullscreenAndUnknownModelsKeepTheirNativeIdentity() {
        SystemShapedArtworkPolicy policy = policy();
        policy.setEnabled(true);
        Builder builder = new Builder();
        Object gradient = new Object();
        assertSame(gradient, policy.modelBuilt(builder, gradient));
        assertNull(policy.modelBuilt(builder, null));
        FullScreen original = new FullScreen();
        assertSame(original, policy.modelBuilt(new Object(), original));
    }

    @Test public void unverifiedArtworkGraphFailsOpenInsteadOfReplacingTheRenderer() {
        SystemShapedArtworkPolicy policy = policy();
        policy.setEnabled(true);
        Builder builder = new Builder();
        builder.L$0 = new Object();
        FullScreen original = new FullScreen();
        assertSame(original, policy.modelBuilt(builder, original));
    }

    @Test public void missingPaletteUsesTheNativeZeroFallback() {
        SystemShapedArtworkPolicy policy = policy();
        policy.setEnabled(true);
        Builder builder = new Builder();
        ((Media)builder.L$0).d.a.e = null;
        FlowLight forced = (FlowLight)policy.modelBuilt(builder, new FullScreen());
        assertArrayEquals(new int[5], forced.colors);
    }

    @Test public void newerUnobservedStateIsNeverOverwrittenBySettingToggle() {
        SystemShapedArtworkPolicy policy = policy();
        Builder builder = new Builder();
        policy.modelBuilt(builder, new FullScreen());
        Object newer = new Object();
        builder.this$0.mutable().value = newer;
        policy.setEnabled(true);
        assertSame(newer, builder.this$0.mutable().value);
        assertEquals(0, builder.this$0.mutable().writes);
    }

    @Test public void nativeCollectorMayChangeSettingsDuringPublish() {
        SystemShapedArtworkPolicy policy = policy();
        Builder builder = new Builder();
        FullScreen original = new FullScreen();
        Mutable state = builder.this$0.mutable();
        state.value = policy.modelBuilt(builder, original);
        state.afterWrite = () -> {
            state.afterWrite = null;
            policy.setEnabled(false);
        };
        policy.setEnabled(true);
        assertSame(original, state.value);
        assertEquals(2, state.writes);
    }

    @Test public void pluginResetDropsOldOwnersButKeepsSelectionForNewModels() {
        SystemShapedArtworkPolicy policy = policy();
        policy.setEnabled(true);
        Builder old = new Builder();
        old.this$0.mutable().value = policy.modelBuilt(old, new FullScreen());
        policy.reset();
        policy.setEnabled(false);
        assertEquals(0, old.this$0.mutable().writes);
        policy.setEnabled(true);
        policy.reset();
        assertTrue(policy.modelBuilt(new Builder(), new FullScreen()) instanceof FlowLight);
    }

    @Test public void everyNewTrackReplacesRestorationRecord() {
        SystemShapedArtworkPolicy policy = policy();
        policy.setEnabled(true);
        Builder builder = new Builder();
        FullScreen old = new FullScreen(), next = new FullScreen();
        Mutable state = builder.this$0.mutable();
        state.value = policy.modelBuilt(builder, old);
        state.value = policy.modelBuilt(builder, next);
        policy.setEnabled(false);
        assertSame(next, state.value);
    }

    @Test public void equalModelRebuildCanToggleWhileStateFlowRetainsPreviousInstance() {
        SystemShapedArtworkPolicy policy = policy();
        Builder builder = new Builder();
        FullScreen original = new FullScreen();
        builder.this$0.mutable().value = policy.modelBuilt(builder, original);
        FullScreen equalRebuild = new FullScreen(original.a);
        assertEquals(original, policy.modelBuilt(builder, equalRebuild));
        policy.setEnabled(true);
        assertTrue(builder.this$0.mutable().value instanceof FlowLight);
        policy.setEnabled(false);
        assertEquals(original, builder.this$0.mutable().value);
    }

    @Test public void repeatedSelectionRepairsLateNativeEmissionWithoutWaitingForTrackChange() {
        SystemShapedArtworkPolicy policy = policy();
        Builder builder = new Builder();
        FullScreen original = new FullScreen();
        Object selectedBeforeFlowEmission = policy.modelBuilt(builder, original);
        policy.setEnabled(true);
        assertNull(builder.this$0.mutable().value);
        builder.this$0.mutable().value = selectedBeforeFlowEmission;
        policy.setEnabled(true);
        assertTrue(builder.this$0.mutable().value instanceof FlowLight);
    }

    static final class Builder {
        final Owner this$0 = new Owner();
        Object L$0 = new Media();
    }
    static final class Owner {
        final ReadOnly m = new ReadOnly();
        Mutable mutable() { return (Mutable)m.b; }
    }
    interface State {}
    static final class ReadOnly { final State b = new Mutable(); }
    static final class Mutable implements State {
        Object value; int writes; Runnable afterWrite;
        public Object getValue() { return value; }
        public void g(Object next) {
            value = next; writes++;
            if (afterWrite != null) afterWrite.run();
        }
    }
    static final class Media { final Artwork d = new Artwork(); }
    static final class Artwork { final Icon a = new Icon(); }
    static final class Icon { Result e = new Result(); }
    static final class Result { final Palette b = new Palette(); }
    static final class Palette { final int a=11, d=44, g=77, f=66; }
    static final class Drawable { final int width=1000, height=1000; }
    static final class FullScreen {
        final Drawable a;
        FullScreen() { this(new Drawable()); }
        FullScreen(Drawable a) { this.a = a; }
        @Override public boolean equals(Object other) {
            return other instanceof FullScreen && ((FullScreen)other).a == a;
        }
        @Override public int hashCode() { return System.identityHashCode(a); }
    }
    static final class FlowLight {
        final Drawable a; final int[] colors;
        FlowLight(Drawable a, int b, int c, int d, int e, int f) {
            this.a = a; colors = new int[]{b,c,d,e,f};
        }
    }
}
