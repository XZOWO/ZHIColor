package com.naivety.zhicolor.bridge;

import com.naivety.zhicolor.bridge.systemui.lyrics.LyricsRecyclerFieldAccessor;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/** Verified ColorOS 17 item-top offset consumers; native transactions retain their owners. */
final class NativeLyricsAnchorPolicy {
    private static final TopOffsetStateStore OFFSETS = new TopOffsetStateStore();

    private NativeLyricsAnchorPolicy() {}

    static Method immediatePositionMethod(Class<?> recyclerClass) {
        if (topOffsetField(recyclerClass) == null) return null;
        try {
            Method method = recyclerClass.getDeclaredMethod("n", int.class);
            return instanceMethod(method, void.class) ? accessible(method) : null;
        } catch (ReflectiveOperationException | SecurityException ignored) {
            return null;
        }
    }

    static Method smoothFitMethod(Class<?> recyclerClass) {
        if (topOffsetField(recyclerClass) == null) return null;
        try {
            Field scroller = instanceField(recyclerClass, "l");
            Class<?> scrollerClass = scroller.getType();
            if (!extendsNamed(scrollerClass, "androidx.recyclerview.widget.LinearSmoothScroller")
                    || instanceField(scrollerClass, "b").getType() != recyclerClass
                    || instanceField(scrollerClass, "a").getType() != long.class) return null;
            Method method = scrollerClass.getDeclaredMethod("calculateDtToFit",
                    int.class, int.class, int.class, int.class, int.class);
            return instanceMethod(method, int.class) ? accessible(method) : null;
        } catch (ReflectiveOperationException | SecurityException ignored) {
            return null;
        }
    }

    static Object recyclerFromScroller(Object scroller) {
        if (scroller == null) return null;
        try {
            Field owner = instanceField(scroller.getClass(), "b");
            Object recycler = owner.get(scroller);
            if (recycler == null || owner.getType() != recycler.getClass()
                    || smoothFitMethod(recycler.getClass()) == null
                    || instanceField(recycler.getClass(), "l").get(recycler) != scroller) return null;
            return recycler;
        } catch (ReflectiveOperationException | SecurityException ignored) {
            return null;
        }
    }

    static int modelTargetIndex(Object model) {
        if (model == null) return -1;
        try {
            Field lines = instanceField(model.getClass(), "a");
            Field index = instanceField(model.getClass(), "b");
            if (!List.class.isAssignableFrom(lines.getType()) || index.getType() != int.class)
                return -1;
            return index.getInt(model);
        } catch (ReflectiveOperationException | SecurityException ignored) {
            return -1;
        }
    }

    static boolean writeTopOffset(Object recycler, int offset) {
        return OFFSETS.write(recycler, offset);
    }

    static void restore(Object recycler) {
        OFFSETS.restore(recycler);
    }

    static void restoreAll() {
        OFFSETS.restoreAll();
    }

    private static Field topOffsetField(Class<?> recyclerClass) {
        if (recyclerClass == null || !LyricsRecyclerFieldAccessor.isColorOs17Layout(recyclerClass))
            return null;
        try {
            Field field = instanceField(recyclerClass, "q");
            return field.getType() == int.class ? field : null;
        } catch (ReflectiveOperationException | SecurityException ignored) {
            return null;
        }
    }

    private static Field instanceField(Class<?> type, String name)
            throws ReflectiveOperationException {
        Field field = type.getDeclaredField(name);
        if (Modifier.isStatic(field.getModifiers())) throw new NoSuchFieldException(name);
        field.setAccessible(true);
        return field;
    }

    private static boolean instanceMethod(Method method, Class<?> result) {
        return method.getReturnType() == result && !Modifier.isStatic(method.getModifiers());
    }

    private static Method accessible(Method method) {
        method.setAccessible(true);
        return method;
    }

    private static boolean extendsNamed(Class<?> type, String name) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            if (name.equals(current.getName())) return true;
        }
        return false;
    }

    static final class TopOffsetStateStore {
        private final WeakHashMap<Object, Integer> originals = new WeakHashMap<>();

        synchronized boolean write(Object recycler, int offset) {
            if (recycler == null) return false;
            Field field = topOffsetField(recycler.getClass());
            if (field == null) return false;
            try {
                int original = field.getInt(recycler);
                if (original == offset) return true;
                if (!originals.containsKey(recycler)) originals.put(recycler, original);
                field.setInt(recycler, offset);
                return field.getInt(recycler) == offset;
            } catch (ReflectiveOperationException | SecurityException ignored) {
                return false;
            }
        }

        synchronized void restore(Object recycler) {
            Integer original = originals.get(recycler);
            if (original != null && restoreOffset(recycler, original)) originals.remove(recycler);
        }

        synchronized void restoreAll() {
            Iterator<Map.Entry<Object, Integer>> entries = originals.entrySet().iterator();
            while (entries.hasNext()) {
                Map.Entry<Object, Integer> entry = entries.next();
                if (restoreOffset(entry.getKey(), entry.getValue())) entries.remove();
            }
        }

        private boolean restoreOffset(Object recycler, int value) {
            if (recycler == null) return true;
            Field field = topOffsetField(recycler.getClass());
            if (field == null) return false;
            try {
                field.setInt(recycler, value);
                return field.getInt(recycler) == value;
            } catch (ReflectiveOperationException | SecurityException ignored) {
                return false;
            }
        }
    }
}
