package com.naivety.zhicolor.bridge;

import com.naivety.zhicolor.bridge.render.WordLine;
import com.naivety.zhicolor.bridge.render.WordLyricModel;
import com.naivety.zhicolor.bridge.render.WordLyricRenderSupport;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.List;

/** Copies the verified ColorOS 17 row-index model without changing its list or caller state. */
final class OfficialLyricOverlapModelAdapter {
    private OfficialLyricOverlapModelAdapter() {}

    static Object withFocusedOverlapRow(Object nativeModel, WordLyricModel model, long position)
            throws ReflectiveOperationException {
        if (nativeModel == null || model == null || model.overlapIndexAt(position) < 0) {
            return nativeModel;
        }
        int focusIndex = model.focusIndexAt(position);
        WordLine focus = model.lineAt(focusIndex);
        WordLine incoming = model.lineAt(model.overlapIndexAt(position));
        int focusAdapter = model.adapterIndexOfLine(focus);
        int incomingAdapter = model.adapterIndexOfLine(incoming);
        if (focusAdapter < 0 || incomingAdapter < 0 || focusAdapter == incomingAdapter) {
            return nativeModel;
        }
        Field linesField = nativeModel.getClass().getDeclaredField("a");
        Field indexField = nativeModel.getClass().getDeclaredField("b");
        if (!List.class.isAssignableFrom(linesField.getType()) || indexField.getType() != int.class) {
            return nativeModel;
        }
        linesField.setAccessible(true);
        indexField.setAccessible(true);
        Object value = linesField.get(nativeModel);
        if (!(value instanceof List<?>)) return nativeModel;
        List<?> nativeLines = (List<?>) value;
        if (!matches(nativeLines, focusAdapter, focus)
                || !matches(nativeLines, incomingAdapter, incoming)
                || indexField.getInt(nativeModel) != incomingAdapter) {
            return nativeModel;
        }
        Constructor<?> constructor = nativeModel.getClass().getDeclaredConstructor(List.class, int.class);
        constructor.setAccessible(true);
        return constructor.newInstance(nativeLines, focusAdapter);
    }

    private static boolean matches(List<?> nativeLines, int index, WordLine line)
            throws ReflectiveOperationException {
        if (index < 0 || index >= nativeLines.size() || line == null) return false;
        Object nativeLine = nativeLines.get(index);
        if (nativeLine == null) return false;
        Field text = nativeLine.getClass().getDeclaredField("b");
        if (text.getType() != String.class) return false;
        text.setAccessible(true);
        return WordLyricRenderSupport.matchesWordLineText(line,
                WordLyricRenderSupport.normalizeLine((String) text.get(nativeLine)));
    }

    static boolean hasSameLines(Object nativeModel, Object currentLines)
            throws ReflectiveOperationException {
        Field lines = nativeModel.getClass().getDeclaredField("a");
        lines.setAccessible(true);
        Object originalLines = lines.get(nativeModel);
        return originalLines instanceof List<?> && originalLines.equals(currentLines);
    }
}
