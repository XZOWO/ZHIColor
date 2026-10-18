package com.naivety.zhicolor.bridge.systemui.lyrics;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.ref.WeakReference;
import java.util.WeakHashMap;

/** Caches verified private-field access used by the official lyrics Recycler hot path. */
public final class LyricsRecyclerFieldAccessor {
    private static final String LEGACY_CURRENT_INDEX_FIELD = "n";

    private final Object lock = new Object();
    private final WeakHashMap<Class<?>, WeakReference<Field>> currentIndexFields =
            new WeakHashMap<>();
    private final WeakHashMap<Class<?>, Boolean> missingCurrentIndexFields =
            new WeakHashMap<>();

    public int readCurrentIndex(Object recycler, int fallback) {
        if (recycler == null) {
            return fallback;
        }
        Field field = resolveCurrentIndexField(recycler.getClass());
        if (field == null) {
            return fallback;
        }
        try {
            return field.getType() == int.class
                    ? field.getInt(recycler)
                    : ((Number) field.get(recycler)).intValue();
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private Field resolveCurrentIndexField(Class<?> recyclerClass) {
        synchronized (lock) {
            WeakReference<Field> reference = currentIndexFields.get(recyclerClass);
            Field cached = reference == null ? null : reference.get();
            if (cached != null) {
                return cached;
            }
            if (missingCurrentIndexFields.containsKey(recyclerClass)) {
                return null;
            }
        }
        Field resolved = findCurrentIndexField(recyclerClass);
        synchronized (lock) {
            if (resolved == null) {
                missingCurrentIndexFields.put(recyclerClass, Boolean.TRUE);
            } else {
                currentIndexFields.put(recyclerClass, new WeakReference<>(resolved));
            }
        }
        return resolved;
    }

    private static Field findCurrentIndexField(Class<?> recyclerClass) {
        Field legacy = findInstanceField(recyclerClass, LEGACY_CURRENT_INDEX_FIELD);
        if (legacy != null
                && (legacy.getType() == int.class || legacy.getType() == Integer.class)) {
            return makeAccessible(legacy);
        }
        // 17.000.002 moved the index to c; n is now the shadow Y offset.
        // Never infer this layout from c alone: earlier builds also contain c.
        return isColorOs17Layout(recyclerClass)
                ? makeAccessible(findInstanceField(recyclerClass, "c"))
                : null;
    }

    /** Complete field fingerprint from the inspected SystemUIPlugin 17.000.002. */
    public static boolean isColorOs17Layout(Class<?> recyclerClass) {
        Field phase = findInstanceField(recyclerClass, "w");
        return hasExactInstanceField(recyclerClass, "c", int.class)
                && hasExactInstanceField(recyclerClass, "i", int.class)
                && hasExactInstanceField(recyclerClass, "j", int.class)
                && hasExactInstanceField(recyclerClass, "k", long.class)
                && hasExactInstanceField(recyclerClass, "n", float.class)
                && hasExactInstanceField(recyclerClass, "r", float.class)
                && phase != null
                && phase.getType().isEnum()
                && "LyricsSwitchPhase".equals(phase.getType().getSimpleName());
    }

    private static boolean hasExactInstanceField(
            Class<?> recyclerClass, String name, Class<?> expectedType) {
        Field field = findInstanceField(recyclerClass, name);
        return field != null && field.getType() == expectedType;
    }

    private static Field findInstanceField(Class<?> recyclerClass, String name) {
        Class<?> current = recyclerClass;
        while (current != null) {
            try {
                Field field = current.getDeclaredField(name);
                return Modifier.isStatic(field.getModifiers()) ? null : field;
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    private static Field makeAccessible(Field field) {
        if (field == null) {
            return null;
        }
        try {
            field.setAccessible(true);
            return field;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
