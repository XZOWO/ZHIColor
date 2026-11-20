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

    @Test public void rewritesAndConstructsTargetRowForScrubbing() throws Exception {
        List<NativeLine> lines = Arrays.asList(new NativeLine("voice A"), new NativeLine("voice B"));
        NativeModel original = new NativeModel(lines, 0);
        NativeModel rewritten = (NativeModel) OfficialLyricOverlapModelAdapter.withTargetRow(original, 1);
        assertNotSame(original, rewritten);
        assertSame(lines, rewritten.a);
        assertEquals(1, rewritten.b);
        assertSame(original, OfficialLyricOverlapModelAdapter.withTargetRow(original, 0));
        assertSame(original, OfficialLyricOverlapModelAdapter.withTargetRow(original, 5));

        NativeModel created = (NativeModel) OfficialLyricOverlapModelAdapter.createForCurrentList(
                NativeModel.class, lines, 1);
        assertNotNull(created);
        assertSame(lines, created.a);
        assertEquals(1, created.b);
        assertNull(OfficialLyricOverlapModelAdapter.createForCurrentList(NativeModel.class, lines, 2));
    }

    @Test public void preArrivalClockSkewKeepsFocusOnEarlierOverlappingLineAndWaits() throws Exception {
        NativeModel earlyIncoming = new NativeModel(
                Arrays.asList(new NativeLine("voice A"), new NativeLine("voice B")), 1);
        // 150ms before voice B starts (2000ms) while voice A [1000..4000] is still singing.
        NativeModel held = (NativeModel) OfficialLyricOverlapModelAdapter.withFocusedOverlapRow(
                earlyIncoming, model(), 1850);
        assertNotSame(earlyIncoming, held);
        assertEquals(0, held.b);
        assertTrue(OfficialLyricOverlapModelAdapter.shouldWaitForPreArrivalOverlap(model(), 0, 1, 1850));
        // Outside the 500ms pre-arrival guard (e.g., 1200ms -> 800ms early), leave untouched.
        assertSame(earlyIncoming, OfficialLyricOverlapModelAdapter.withFocusedOverlapRow(
                earlyIncoming, model(), 1200));
        assertFalse(OfficialLyricOverlapModelAdapter.shouldWaitForPreArrivalOverlap(model(), 0, 1, 1200));
    }

    @Test public void clampTrailingPlaceholderRowKeepsLastRealLyricAnchored() throws Exception {
        WordLyricModel m = new WordLyricModel();
        WordLine a = new WordLine(1000, "First line", new ArrayList<>(), 3000, LyricTimingMode.LINE_TIMED);
        WordLine b = new WordLine(3500, "Final line", new ArrayList<>(), 6000, LyricTimingMode.LINE_TIMED);
        m.lines.add(a);
        m.lines.add(b);
        // Official list has a leading null metadata slot, two real rows, and an upstream trailing spacer.
        m.officialLines.add(null);
        m.officialLines.add(a);
        m.officialLines.add(b);
        m.officialLines.add(null);

        List<NativeLine> nativeLines = Arrays.asList(
                new NativeLine("Title - Artist"),
                new NativeLine("First line"),
                new NativeLine("Final line"),
                new NativeLine(LyricTextSanitizer.ZERO_WIDTH_SPACE));
        NativeModel trailingAdvance = new NativeModel(nativeLines, 3);
        NativeModel clamped = (NativeModel) OfficialLyricOverlapModelAdapter.clampTrailingPlaceholderRow(
                trailingAdvance, m);
        assertNotSame(trailingAdvance, clamped);
        assertEquals(2, clamped.b);

        // Leading metadata slot (index 0) and active real rows (1, 2) are untouched.
        NativeModel leading = new NativeModel(nativeLines, 0);
        assertSame(leading, OfficialLyricOverlapModelAdapter.clampTrailingPlaceholderRow(leading, m));
        NativeModel lastReal = new NativeModel(nativeLines, 2);
        assertSame(lastReal, OfficialLyricOverlapModelAdapter.clampTrailingPlaceholderRow(lastReal, m));
    }
}
