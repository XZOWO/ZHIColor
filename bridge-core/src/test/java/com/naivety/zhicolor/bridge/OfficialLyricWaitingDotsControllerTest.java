package com.naivety.zhicolor.bridge;

import com.naivety.zhicolor.bridge.render.LyricTimingMode;
import com.naivety.zhicolor.bridge.render.WordLine;
import com.naivety.zhicolor.bridge.render.WordLyricModel;
import com.naivety.zhicolor.bridge.render.WordRange;
import java.lang.reflect.Method;
import java.util.ArrayList;
import org.junit.Test;
import static org.junit.Assert.*;

public final class OfficialLyricWaitingDotsControllerTest {
    private static WordLine timedLine(long beginMs, long endMs, String text) {
        ArrayList<WordRange> words = new ArrayList<>();
        words.add(new WordRange(beginMs, 0, text.length(), endMs));
        WordLine line = new WordLine(beginMs, text, words, endMs, LyricTimingMode.WORD_TIMED);
        line.explicitEndTimeProvided = true;
        return line;
    }

    private static WordLyricModel songWithStandaloneDotsRows() {
        WordLyricModel model = new WordLyricModel();
        WordLine introDots = WordLine.waitingDots(0L, 10_000L);
        WordLine first = timedLine(10_000L, 15_000L, "First sung line");
        WordLine interludeDots = WordLine.waitingDots(15_100L, 26_000L);
        WordLine second = timedLine(26_000L, 32_000L, "Second after break");
        model.lines.add(introDots);
        model.lines.add(first);
        model.lines.add(interludeDots);
        model.lines.add(second);
        model.officialLines.addAll(model.lines);
        return model;
    }

    @Test
    public void standaloneDotsRowOwnsFocusAndScaleWhileRealLyricsStayUnfocused() throws Exception {
        WordLyricModel model = songWithStandaloneDotsRows();
        WordLine introDots = model.lines.get(0);
        WordLine first = model.lines.get(1);
        WordLine interludeDots = model.lines.get(2);
        WordLine second = model.lines.get(3);

        Method scaleIdx = LockscreenLyricsModule.class.getDeclaredMethod(
                "resolveOfficialScaleActiveIndex",
                WordLyricModel.class, WordLine.class, long.class, int.class);
        scaleIdx.setAccessible(true);
        Method focusAnchor = LockscreenLyricsModule.class.getDeclaredMethod(
                "resolveFocusAnchorLine",
                WordLyricModel.class, WordLine.class, long.class);
        focusAnchor.setAccessible(true);
        Method wordActive = LockscreenLyricsModule.class.getDeclaredMethod(
                "isOfficialWordProgressActive",
                WordLyricModel.class, WordLine.class, WordLine.class, long.class);
        wordActive.setAccessible(true);

        LockscreenLyricsModule module = new LockscreenLyricsModule();
        // During intro break: index 0 (intro dots) owns scale & focus; first lyric stays unfocused and inactive
        assertEquals(0, (int) scaleIdx.invoke(module, model, introDots, 3_000L, 0));
        assertSame(introDots, focusAnchor.invoke(null, model, introDots, 3_000L));
        assertFalse((boolean) wordActive.invoke(null, model, introDots, introDots, 3_000L));
        assertFalse((boolean) wordActive.invoke(null, model, introDots, first, 3_000L));

        // Intro finishes at 10_000ms: focus moves cleanly to row 1 (first lyric), no dots active
        assertEquals(1, (int) scaleIdx.invoke(module, model, first, 12_000L, 1));
        assertSame(first, focusAnchor.invoke(null, model, first, 12_000L));
        assertTrue((boolean) wordActive.invoke(null, model, first, first, 12_000L));
        assertFalse((boolean) wordActive.invoke(null, model, first, introDots, 12_000L));

        // Mid-song interlude: index 2 (interlude dots) owns scale & focus; neither adjacent lyric is active
        assertEquals(2, (int) scaleIdx.invoke(module, model, interludeDots, 18_000L, 2));
        assertSame(interludeDots, focusAnchor.invoke(null, model, interludeDots, 18_000L));
        assertFalse((boolean) wordActive.invoke(null, model, interludeDots, interludeDots, 18_000L));
        assertFalse((boolean) wordActive.invoke(null, model, interludeDots, first, 18_000L));
        assertFalse((boolean) wordActive.invoke(null, model, interludeDots, second, 18_000L));

        // Second verse resumes at 26_000ms: focus & word progress move to row 3
        assertEquals(3, (int) scaleIdx.invoke(module, model, second, 27_000L, 3));
        assertSame(second, focusAnchor.invoke(null, model, second, 27_000L));
        assertTrue((boolean) wordActive.invoke(null, model, second, second, 27_000L));
    }

    @Test
    public void officialIndexAndAdapterPositionStayOneToOneForDotsAndLyrics() {
        WordLyricModel model = songWithStandaloneDotsRows();
        assertSame(model.lines.get(0), model.lineAtAdapterIndexMatchingText(0, ""));
        assertSame(model.lines.get(1), model.lineAtAdapterIndexMatchingText(1, "First sung line"));
        assertSame(model.lines.get(2), model.lineAtAdapterIndexMatchingText(2, ""));
        assertSame(model.lines.get(3), model.lineAtAdapterIndexMatchingText(3, "Second after break"));
        assertEquals(0, model.adapterIndexAt(2_000L));
        assertEquals(1, model.adapterIndexAt(12_000L));
        assertEquals(2, model.adapterIndexAt(18_000L));
        assertEquals(3, model.adapterIndexAt(27_000L));
        assertSame(model.lines.get(0), model.findNearestWaitingDotsLine(5_000L));
        assertSame(model.lines.get(2), model.findNearestWaitingDotsLine(19_000L));
    }
}
