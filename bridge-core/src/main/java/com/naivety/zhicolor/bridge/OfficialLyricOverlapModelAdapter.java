package com.naivety.zhicolor.bridge;

import com.naivety.zhicolor.bridge.render.WordLine;
import com.naivety.zhicolor.bridge.render.WordLyricModel;
import com.naivety.zhicolor.bridge.render.WordLyricRenderSupport;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.List;

/** Copies the verified ColorOS 17 row-index model without changing its list or caller state. */
final class OfficialLyricOverlapModelAdapter {
    static final long OVERLAP_PRE_ARRIVAL_LEAD_MS = 350L;

    private OfficialLyricOverlapModelAdapter() {}

    /**
     * Prevents ColorOS from scrolling past the last real lyric row onto an upstream trailing
     * blank/zero-width placeholder row after the final line finishes singing (matching AM).
     */
    static Object clampTrailingPlaceholderRow(Object nativeModel, WordLyricModel model)
            throws ReflectiveOperationException {
        if (nativeModel == null || model == null || model.officialLines.isEmpty()) {
            return nativeModel;
        }
        int lastRealAdapter = lastRealOfficialAdapterIndex(model);
        if (lastRealAdapter < 0) {
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
        int requestedIndex = indexField.getInt(nativeModel);
        if (requestedIndex <= lastRealAdapter
                || requestedIndex >= nativeLines.size()
                || lastRealAdapter >= nativeLines.size()) {
            return nativeModel;
        }
        WordLine lastRealLine = model.rawOfficialLineAt(lastRealAdapter);
        if (!matches(nativeLines, lastRealAdapter, lastRealLine)) {
            return nativeModel;
        }
        Constructor<?> constructor = nativeModel.getClass().getDeclaredConstructor(List.class, int.class);
        constructor.setAccessible(true);
        return constructor.newInstance(nativeLines, lastRealAdapter);
    }

    static int lastRealOfficialAdapterIndex(WordLyricModel model) {
        if (model == null || model.officialLines.isEmpty()) {
            return -1;
        }
        for (int i = model.officialLines.size() - 1; i >= 0; i--) {
            WordLine candidate = model.officialLines.get(i);
            if (candidate != null && !candidate.isWaitingDots) {
                return i;
            }
        }
        return -1;
    }

    static Object withFocusedOverlapRow(Object nativeModel, WordLyricModel model, long position)
            throws ReflectiveOperationException {
        if (nativeModel == null || model == null) {
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
        int requestedAdapter = indexField.getInt(nativeModel);

        int focusIndex = -1;
        int incomingIndex = model.overlapIndexAt(position);
        if (incomingIndex >= 0) {
            focusIndex = model.focusIndexAt(position);
        } else if (position >= 0L) {
            // Clock-skew guard: if SystemUI fires the incoming overlapping line slightly before
            // the local playback clock reaches its start while the earlier overlapping line is
            // still singing, keep scroll focused on the earlier line instead of ping-ponging.
            WordLine requestedLine = model.lineAtOfficialDisplayIndex(requestedAdapter);
            int requestedLineIndex = model.indexOfLine(requestedLine);
            if (requestedLineIndex >= 1) {
                WordLine earlier = model.lineAt(requestedLineIndex - 1);
                if (earlier != null
                        && model.hasVerifiedOverlapAfter(earlier)
                        && position >= earlier.timeMillis
                        && position < earlier.endTimeMillis
                        && requestedLine.timeMillis > position
                        && requestedLine.timeMillis - position <= OVERLAP_PRE_ARRIVAL_LEAD_MS) {
                    focusIndex = requestedLineIndex - 1;
                    incomingIndex = requestedLineIndex;
                }
            }
        }
        if (focusIndex < 0 || incomingIndex < 0) {
            return nativeModel;
        }
        WordLine focus = model.lineAt(focusIndex);
        WordLine incoming = model.lineAt(incomingIndex);
        int focusAdapter = model.adapterIndexOfLine(focus);
        int incomingAdapter = model.adapterIndexOfLine(incoming);
        if (focusAdapter < 0 || incomingAdapter < 0 || focusAdapter == incomingAdapter) {
            return nativeModel;
        }
        if (!matches(nativeLines, focusAdapter, focus)
                || !matches(nativeLines, incomingAdapter, incoming)
                || requestedAdapter != incomingAdapter) {
            return nativeModel;
        }
        Constructor<?> constructor = nativeModel.getClass().getDeclaredConstructor(List.class, int.class);
        constructor.setAccessible(true);
        return constructor.newInstance(nativeLines, focusAdapter);
    }

    static boolean shouldWaitForPreArrivalOverlap(
            WordLyricModel model, int focusIndex, int incomingIndex, long position) {
        if (model == null || focusIndex < 0 || incomingIndex != focusIndex + 1 || position < 0L) {
            return false;
        }
        WordLine focus = model.lineAt(focusIndex);
        WordLine incoming = model.lineAt(incomingIndex);
        if (focus == null || incoming == null || !model.hasVerifiedOverlapAfter(focus)) {
            return false;
        }
        return position >= focus.timeMillis
                && position < focus.endTimeMillis
                && incoming.timeMillis > position
                && incoming.timeMillis - position <= OVERLAP_PRE_ARRIVAL_LEAD_MS;
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

    static Object withTargetRow(Object nativeModel, int targetAdapterIndex)
            throws ReflectiveOperationException {
        if (nativeModel == null || targetAdapterIndex < 0) {
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
        if (targetAdapterIndex >= nativeLines.size()
                || indexField.getInt(nativeModel) == targetAdapterIndex) {
            return nativeModel;
        }
        Constructor<?> constructor = nativeModel.getClass().getDeclaredConstructor(List.class, int.class);
        constructor.setAccessible(true);
        return constructor.newInstance(nativeLines, targetAdapterIndex);
    }

    static Object createForCurrentList(
            Class<?> modelClass,
            Object currentList,
            int targetAdapterIndex) throws ReflectiveOperationException {
        if (modelClass == null || !(currentList instanceof List<?>) || targetAdapterIndex < 0) {
            return null;
        }
        List<?> lines = (List<?>) currentList;
        if (lines.isEmpty() || targetAdapterIndex >= lines.size()) {
            return null;
        }
        Constructor<?> constructor = modelClass.getDeclaredConstructor(List.class, int.class);
        constructor.setAccessible(true);
        return constructor.newInstance(lines, targetAdapterIndex);
    }

    static boolean hasSameLines(Object nativeModel, Object currentLines)
            throws ReflectiveOperationException {
        Field lines = nativeModel.getClass().getDeclaredField("a");
        lines.setAccessible(true);
        Object originalLines = lines.get(nativeModel);
        return originalLines instanceof List<?> && originalLines.equals(currentLines);
    }
}
