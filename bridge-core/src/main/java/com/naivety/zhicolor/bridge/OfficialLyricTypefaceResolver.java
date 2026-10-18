package com.naivety.zhicolor.bridge;

import android.graphics.Typeface;

import com.naivety.zhicolor.bridge.systemui.lyrics.LyricsRecyclerFieldAccessor;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.WeakHashMap;

/** Keeps glyph metrics independent of the vendor's current/inactive font swap. */
final class OfficialLyricTypefaceResolver {
    private static final Binding UNSUPPORTED = new Binding(null);

    private final WeakHashMap<Class<?>, Binding> bindings = new WeakHashMap<>();
    private final WeakHashMap<Object, Typeface> surfaceSources = new WeakHashMap<>();

    synchronized Typeface resolve(Object recycler, Typeface observed) {
        if (recycler == null) return observed;
        Class<?> recyclerClass = recycler.getClass();
        Binding binding = bindings.get(recyclerClass);
        if (binding == null) {
            binding = bind(recyclerClass);
            bindings.put(recyclerClass, binding);
        }
        // The field lookup is cached, but the value can change with the system font.
        // A briefly uninitialized font must not become a permanent negative result.
        Typeface active = binding.readActive();
        if (active != null) {
            surfaceSources.put(recycler, active);
            return active;
        }
        Typeface previous = surfaceSources.get(recycler);
        if (previous != null) return previous;
        if (observed != null) surfaceSources.put(recycler, observed);
        return observed;
    }

    private static Binding bind(Class<?> recyclerClass) {
        if (!LyricsRecyclerFieldAccessor.isColorOs17Layout(recyclerClass)) {
            return UNSUPPORTED;
        }
        // C/D are a pair only in this complete inspected layout. Other plugin
        // layouts reuse these names for unrelated fields, including float scales.
        Field active = staticTypefaceField(recyclerClass, "C");
        Field inactive = staticTypefaceField(recyclerClass, "D");
        if (active == null || inactive == null
                || active.getDeclaringClass() != inactive.getDeclaringClass()) {
            return UNSUPPORTED;
        }
        try {
            active.setAccessible(true);
            return new Binding(active);
        } catch (Throwable ignored) {
            return UNSUPPORTED;
        }
    }

    private static Field staticTypefaceField(Class<?> type, String name) {
        Class<?> current = type;
        while (current != null) {
            try {
                Field field = current.getDeclaredField(name);
                return field.getType() == Typeface.class
                        && Modifier.isStatic(field.getModifiers()) ? field : null;
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    private static final class Binding {
        final Field active;

        Binding(Field active) {
            this.active = active;
        }

        Typeface readActive() {
            if (active == null) return null;
            try {
                return (Typeface) active.get(null);
            } catch (Throwable ignored) {
                return null;
            }
        }
    }
}
