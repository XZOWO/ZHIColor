package com.naivety.zhicolor.bridge;

import static org.junit.Assert.*;
import com.naivety.zhicolor.bridge.render.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class OfficialLyricOverlapModelAdapterTest {
    public static final class NativeLine {
        public final String b;
        NativeLine(String text) { b = text; }
    }
    public static final class NativeModel {
        public final List<?> a;
        public final int b;
        public NativeModel(List<?> lines, int index) { a = lines; b = index; }
    }
    private static WordLyricModel model() {
        WordLyricModel m = new WordLyricModel();
        WordLine a = new WordLine(1000, "voice A", new ArrayList<>(), 4000, LyricTimingMode.LINE_TIMED);
        a.explicitEndTimeProvided = true;
        m.lines.add(a);
        m.lines.add(new WordLine(2000, "voice B", new ArrayList<>(), 5000, LyricTimingMode.LINE_TIMED));
        return m;
    }

    @Test public void copiesOnlyFocusIndexAndKeepsOriginalModelAndList() throws Exception {
        NativeModel original = new NativeModel(Arrays.asList(new NativeLine("voice A"), new NativeLine("voice B")), 1);
        NativeModel copy = (NativeModel) OfficialLyricOverlapModelAdapter.withFocusedOverlapRow(original, model(), 2500);
        assertNotSame(original, copy);
        assertSame(original.a, copy.a);
        assertEquals(1, original.b);
        assertEquals(0, copy.b);
        assertTrue(OfficialLyricOverlapModelAdapter.hasSameLines(original, copy.a));
    }

    @Test public void leavesNormalAndExpiredCallsUntouched() throws Exception {
        NativeModel original = new NativeModel(Arrays.asList(new NativeLine("voice A"), new NativeLine("voice B")), 1);
        assertSame(original, OfficialLyricOverlapModelAdapter.withFocusedOverlapRow(original, model(), 4500));
        assertSame(original, OfficialLyricOverlapModelAdapter.withFocusedOverlapRow(original, model(), 1500));
    }

    @Test public void rejectsStaleContentAndUnexpectedIncomingIndex() throws Exception {
        NativeModel stale = new NativeModel(Arrays.asList(new NativeLine("old A"), new NativeLine("old B")), 1);
        assertSame(stale, OfficialLyricOverlapModelAdapter.withFocusedOverlapRow(stale, model(), 2500));
        NativeModel alreadyFocused = new NativeModel(Arrays.asList(new NativeLine("voice A"), new NativeLine("voice B")), 0);
        assertSame(alreadyFocused, OfficialLyricOverlapModelAdapter.withFocusedOverlapRow(alreadyFocused, model(), 2500));
        assertFalse(OfficialLyricOverlapModelAdapter.hasSameLines(stale, alreadyFocused.a));
    }
}
