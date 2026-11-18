package com.naivety.zhicolor.bridge;

import android.view.ViewGroup;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Native background visibility remains authoritative when the initial section bind was missed. */
final class LyricBackgroundSurfacePolicy {
    private LyricBackgroundSurfacePolicy() { }

    static boolean visible(boolean enabled, boolean sectionVisible, boolean backgroundVisible) {
        return enabled && (sectionVisible || backgroundVisible);
    }

    static boolean running(boolean visible, boolean interactive, boolean displayOn) {
        return visible && interactive && displayOn;
    }

    static Method findSectionBindMethod(
            Class<?>[] candidates,
            Class<?> lyricType,
            Class<?> backgroundControllerType) throws NoSuchMethodException {
        if (candidates != null && lyricType != null && backgroundControllerType != null) {
            for (Class<?> candidate : candidates) {
                if (candidate == null
                        || !hasInstanceField(candidate, "n", lyricType)
                        || !hasInstanceField(candidate, "x", backgroundControllerType)) {
                    continue;
                }
                try {
                    Method bind = candidate.getDeclaredMethod("q", ViewGroup.class);
                    if (!Modifier.isStatic(bind.getModifiers())
                            && bind.getReturnType() == void.class) {
                        bind.setAccessible(true);
                        return bind;
                    }
                } catch (NoSuchMethodException ignored) {
                }
            }
        }
        throw new NoSuchMethodException("Immersive section bind method q(ViewGroup)");
    }

    static List<Method> findBackgroundControllerMethods(Class<?> background)
            throws ReflectiveOperationException {
        if (background == null) {
            throw new NoSuchMethodException("Immersive background controller class missing");
        }
        Field rendererField = instanceField(background, "i");
        Field modelField = instanceField(background, "j");
        Class<?> rendererType = rendererField.getType();
        Class<?> modelType = modelField.getType();

        ArrayList<Method> matches = new ArrayList<>();
        int transitionCount = 0;
        int modelCount = 0;
        for (Method method : background.getDeclaredMethods()) {
            if (Modifier.isStatic(method.getModifiers()) || method.getReturnType() != void.class) {
                continue;
            }
            String name = method.getName();
            Class<?>[] params = method.getParameterTypes();
            if (params.length == 3
                    && params[0] == modelType
                    && params[1] == rendererType
                    && params[2] == rendererType
                    && ("d".equals(name) || "e".equals(name))) {
                method.setAccessible(true);
                matches.add(method);
                transitionCount++;
            } else if (params.length == 1
                    && params[0] == modelType
                    && ("f".equals(name) || "g".equals(name) || "h".equals(name))) {
                method.setAccessible(true);
                matches.add(method);
                modelCount++;
            }
        }
        if (transitionCount != 1 || modelCount != 2) {
            throw new NoSuchMethodException(
                    "Immersive background controller methods on " + background.getName());
        }
        matches.sort(Comparator.comparing(Method::getName));
        return matches;
    }

    private static boolean hasInstanceField(Class<?> owner, String name, Class<?> expectedType) {
        try {
            return instanceField(owner, name).getType() == expectedType;
        } catch (NoSuchFieldException ignored) {
            return false;
        }
    }

    private static Field instanceField(Class<?> owner, String name) throws NoSuchFieldException {
        Field field = owner.getDeclaredField(name);
        if (Modifier.isStatic(field.getModifiers())) {
            throw new NoSuchFieldException(owner.getName() + "#" + name + " is static");
        }
        return field;
    }
}
