package com.naivety.zhicolor.bridge;

import android.animation.Animator;
import android.animation.AnimatorSet;
import android.animation.TimeInterpolator;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.TextView;

import com.naivety.zhicolor.bridge.render.ZhiToolLyricMotion;
import com.naivety.zhicolor.bridge.systemui.lyrics.LyricsRecyclerFieldAccessor;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Iterator;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.IntSupplier;

/** Tunes the verified native owners without creating or restarting animations. */
final class OfficialLyricMotionController {
    private static final ScrollStateStore SCROLL_STATES = new ScrollStateStore();
    private static int modelDiagnosticSamples;
    private static final TimeInterpolator FOCUS_INTERPOLATOR =
            ZhiToolLyricMotion::easeOutCubic;

    private OfficialLyricMotionController() {
    }

    static void configureScroll(View recycler, int motionMode, boolean animate) {
        configureScroll(recycler, motionMode, animate, () -> 0);
    }

    static void configureScroll(View recycler, int motionMode, boolean animate,
                                IntSupplier maximumRefreshRate) {
        if (recycler == null
                || !LyricsRecyclerFieldAccessor.isColorOs17Layout(recycler.getClass())) {
            return;
        }
        try {
            Field scrollerField = findInstanceField(recycler.getClass(), "l");
            Object scroller = scrollerField.get(recycler);
            if (scroller == null || !extendsLinearSmoothScroller(scroller.getClass())) {
                return;
            }
            // The 17.000.002 CenterSmoothScroller snapshots its constructor
            // duration in a: changing recycler.k alone leaves scrolling unchanged.
            Field durationField = findUniqueField(scroller.getClass(), long.class, false);
            Field interpolatorField = findInstanceField(
                    scroller.getClass(), "mDecelerateInterpolator");
            if (interpolatorField.getType() != DecelerateInterpolator.class) {
                return;
            }
            Field recyclerDurationField = findInstanceField(recycler.getClass(), "k");
            long scrollMillis = animate ? LyricMotionPolicy.scrollMillis(motionMode) : 1L;
            boolean firstConfiguration = SCROLL_STATES.configure(
                    recycler,
                    recyclerDurationField,
                    scroller,
                    durationField,
                    interpolatorField,
                    new ScrollInterpolator(scrollMillis, maximumRefreshRate),
                    animate ? LyricMotionPolicy.focusMillis(motionMode) : 0L,
                    scrollMillis);
            if (firstConfiguration) {
                android.util.Log.i("ZHIColorMotion", "native17 configured layout=j/k/r mode="
                        + motionMode + " animate=" + animate
                        + " rowMs=" + recyclerDurationField.getLong(recycler)
                        + " scrollMs=" + durationField.getLong(scroller));
            }
        } catch (ReflectiveOperationException ignored) {
            // Unknown scroller shapes retain their native animation.
        }
    }

    static void restoreAll() {
        SCROLL_STATES.restoreAll();
    }

    static void observeModelUpdate(View recycler, Object model, boolean requestedAnimate,
                                   boolean modulePositionOwner) {
        if (modelDiagnosticSamples >= 8) return;
        try {
            int target = findInstanceField(model.getClass(), "b").getInt(model);
            int previous = findInstanceField(recycler.getClass(), "c").getInt(recycler);
            if (target == previous) return;
            modelDiagnosticSamples++;
            android.util.Log.i("ZHIColorMotion", "native17 model old=" + previous
                    + " new=" + target + " requestedAnimate=" + requestedAnimate
                    + " nativeAod=" + findInstanceField(recycler.getClass(), "s").getBoolean(recycler)
                    + " modulePositionOwner=" + modulePositionOwner);
        } catch (ReflectiveOperationException ignored) {
            // Diagnostics never change the native model transaction.
        }
    }

    static void configureHolder(Object holder, int motionMode, boolean animate)
            throws ReflectiveOperationException {
        configureHolder(holder, motionMode, animate, () -> 0);
    }

    static void configureHolder(Object holder, int motionMode, boolean animate,
                                IntSupplier maximumRefreshRate)
            throws ReflectiveOperationException {
        if (holder == null) {
            return;
        }
        Field textField = findUniqueField(holder.getClass(), TextView.class, true);
        Field animatorField = findUniqueField(holder.getClass(), AnimatorSet.class, true);
        if (!(textField.get(holder) instanceof TextView)) {
            return;
        }
        AnimatorSet animator = (AnimatorSet) animatorField.get(holder);
        if (animator == null) {
            return;
        }
        long duration = animate ? LyricMotionPolicy.focusMillis(motionMode) : 0L;
        animator.setDuration(duration);
        animator.setInterpolator(new FocusInterpolator(duration, maximumRefreshRate));
        // The native adapter starts this set before its bind method returns.
        // Apply to its existing children as well, without restarting the owner.
        for (Animator child : animator.getChildAnimations()) {
            child.setDuration(duration);
            child.setInterpolator(new FocusInterpolator(duration, maximumRefreshRate));
        }
    }

    private static boolean extendsLinearSmoothScroller(Class<?> type) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            if ("androidx.recyclerview.widget.LinearSmoothScroller".equals(current.getName())) {
                return true;
            }
        }
        return false;
    }

    private static Field findInstanceField(Class<?> type, String name)
            throws ReflectiveOperationException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            Field field;
            try {
                field = current.getDeclaredField(name);
            } catch (NoSuchFieldException missing) {
                continue;
            }
            if (Modifier.isStatic(field.getModifiers())) {
                throw new NoSuchFieldException(name + " is static");
            }
            field.setAccessible(true);
            return field;
        }
        throw new NoSuchFieldException(name);
    }

    private static Field findUniqueField(Class<?> type, Class<?> fieldType, boolean inherited)
            throws ReflectiveOperationException {
        Field match = null;
        for (Class<?> current = type; current != null;
                current = inherited ? current.getSuperclass() : null) {
            for (Field field : current.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())
                        && fieldType.isAssignableFrom(field.getType())) {
                    if (match != null) {
                        throw new NoSuchFieldException("Ambiguous " + fieldType.getName());
                    }
                    match = field;
                }
            }
        }
        if (match == null) {
            throw new NoSuchFieldException(fieldType.getName());
        }
        match.setAccessible(true);
        return match;
    }

    private static final class FocusInterpolator implements TimeInterpolator {
        private final AnimationFractionSampler sampler;

        FocusInterpolator(long durationMillis, IntSupplier maximumRefreshRate) {
            sampler = new AnimationFractionSampler(durationMillis, maximumRefreshRate);
        }

        @Override public float getInterpolation(float input) {
            return FOCUS_INTERPOLATOR.getInterpolation(sampler.sample(input));
        }
    }

    private static final class ScrollInterpolator extends DecelerateInterpolator {
        private final AnimationFractionSampler sampler;

        ScrollInterpolator(long durationMillis, IntSupplier maximumRefreshRate) {
            super(1.5f);
            sampler = new AnimationFractionSampler(durationMillis, maximumRefreshRate);
        }

        @Override public float getInterpolation(float input) {
            return super.getInterpolation(sampler.sample(input));
        }
    }

    /** Reflection-only state storage keeps restoration testable without an Android runtime. */
    static final class ScrollStateStore {
        private final WeakHashMap<Object, ScrollSnapshot> originals = new WeakHashMap<>();

        synchronized boolean configure(
                Object recycler,
                Field recyclerDuration,
                Object scroller,
                Field scrollDuration,
                Field interpolator,
                Object configuredInterpolator,
                long rowMillis,
                long scrollMillis) throws ReflectiveOperationException {
            boolean firstConfiguration = !originals.containsKey(recycler);
            if (firstConfiguration) {
                originals.put(recycler, new ScrollSnapshot(
                        recyclerDuration,
                        recyclerDuration.getLong(recycler),
                        scroller,
                        scrollDuration,
                        scrollDuration.getLong(scroller),
                        interpolator,
                        interpolator.get(scroller)));
            }
            recyclerDuration.setLong(recycler, rowMillis);
            scrollDuration.setLong(scroller, scrollMillis);
            interpolator.set(scroller, configuredInterpolator);
            return firstConfiguration;
        }

        synchronized void restoreAll() {
            Iterator<Map.Entry<Object, ScrollSnapshot>> iterator = originals.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<Object, ScrollSnapshot> entry = iterator.next();
                Object recycler = entry.getKey();
                if (recycler == null || entry.getValue().restore(recycler)) {
                    iterator.remove();
                }
            }
        }
    }

    private static final class ScrollSnapshot {
        final Field recyclerDuration;
        final long rowMillis;
        final WeakReference<Object> scroller;
        final Field scrollDuration;
        final long scrollMillis;
        final Field interpolator;
        final Object originalInterpolator;

        ScrollSnapshot(
                Field recyclerDuration,
                long rowMillis,
                Object scroller,
                Field scrollDuration,
                long scrollMillis,
                Field interpolator,
                Object originalInterpolator) {
            this.recyclerDuration = recyclerDuration;
            this.rowMillis = rowMillis;
            this.scroller = new WeakReference<>(scroller);
            this.scrollDuration = scrollDuration;
            this.scrollMillis = scrollMillis;
            this.interpolator = interpolator;
            this.originalInterpolator = originalInterpolator;
        }

        boolean restore(Object recycler) {
            boolean restored = true;
            try {
                recyclerDuration.setLong(recycler, rowMillis);
            } catch (ReflectiveOperationException ignored) {
                restored = false;
            }
            Object owner = scroller.get();
            if (owner != null) {
                try {
                    scrollDuration.setLong(owner, scrollMillis);
                } catch (ReflectiveOperationException ignored) {
                    restored = false;
                }
                try {
                    interpolator.set(owner, originalInterpolator);
                } catch (ReflectiveOperationException ignored) {
                    restored = false;
                }
            }
            return restored;
        }
    }
}
