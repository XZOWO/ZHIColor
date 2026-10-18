package com.naivety.zhicolor.bridge;

import com.naivety.zhicolor.bridge.render.LyricTimingMode;
import com.naivety.zhicolor.bridge.render.WordLine;
import com.naivety.zhicolor.bridge.render.WordLyricModel;
import com.naivety.zhicolor.bridge.render.WordRange;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import org.junit.Test;
import static org.junit.Assert.*;

public final class OfficialLyricSecondaryControllerTest {
    private static Object renderer() throws Exception {
        Class<?> type = Class.forName(LockscreenLyricsModule.class.getName() + "$OfficialLyricTextRenderer");
        Constructor<?> constructor = type.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    private static Object call(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(target, args);
    }

    private static boolean prepare(Object renderer, WordLyricModel model, int focused,
            long position, boolean playing) throws Exception {
        return (Boolean) call(renderer, "prepareSecondaryLayout", new Class<?>[]{
                WordLyricModel.class, int.class, long.class, boolean.class, boolean.class},
                model, focused, position, false, playing);
    }

    private static WordLyricModel model() {
        WordLyricModel model = new WordLyricModel();
        WordLine main = new WordLine(1000L, "main", new ArrayList<>(), 7000L, LyricTimingMode.LINE_TIMED);
        main.translation = "translation";
        ArrayList<WordRange> words = new ArrayList<>();
        words.add(new WordRange(4000L, 0, 2, 4200L));
        words.add(new WordRange(5000L, 2, 4, 5200L));
        main.secondaryLine = new WordLine(4000L, "echo", words, 5200L, LyricTimingMode.WORD_TIMED);
        model.lines.add(main);
        model.lines.add(new WordLine(7000L, "next", new ArrayList<>()));
        return model;
    }

    @Test public void translationCanBeOffWhileVocalLayoutAndItsOwnClockRemainActive() throws Exception {
        Object renderer = renderer();
        WordLyricModel model = model();
        call(renderer, "setTranslationEnabledImmediately", new Class<?>[]{boolean.class}, false);
        assertFalse(prepare(renderer, model, 0, 4100L, true));
        WordLine main = model.lines.get(0);
        assertEquals(0f, (Float) call(renderer, "rowTranslationAmount",
                new Class<?>[]{WordLine.class, float.class}, main, 0f), 0f);
        assertEquals(1f, main.vocalLayout.value(0L), 0f);
        assertEquals(0.5f, main.secondaryLine.wordProgress(0, 4100L), 0.0001f);
    }

    @Test public void sameParentSeekCrossesVocalThresholdAndLeavesNoUnusedVocalSlot() throws Exception {
        Object renderer = renderer();
        WordLyricModel model = model();
        WordLine main = model.lines.get(0);
        assertFalse(prepare(renderer, model, 0, 3699L, false));
        assertEquals(0f, main.vocalLayout.value(0L), 0f);
        prepare(renderer, model, 0, 3700L, false);
        assertEquals(1f, main.vocalLayout.value(0L), 0f);
        prepare(renderer, model, 0, 2000L, false);
        assertEquals(0f, main.vocalLayout.value(0L), 0f);
        assertEquals(1f, main.secondaryLayout.value(0L), 0f);
    }

    @Test public void focusChangeCollapsesBothSecondarySlotsAndAllModeKeepsTranslation() throws Exception {
        Object renderer = renderer();
        call(renderer, "setConfig", new Class<?>[]{LyricUiConfig.class},
                LyricUiConfig.defaults().buildUpon()
                        .translationDisplayMode(LyricUiConfig.TRANSLATION_CURRENT_ONLY).build());
        WordLyricModel model = model();
        prepare(renderer, model, 0, 4100L, true);
        prepare(renderer, model, 1, 7000L, true);
        WordLine old = model.lines.get(0);
        assertEquals(0f, old.vocalLayout.value(0L), 0f);
        assertEquals(0f, (Float) call(renderer, "rowTranslationAmount",
                new Class<?>[]{WordLine.class, float.class}, old, 1f), 0f);
        call(renderer, "setConfig", new Class<?>[]{LyricUiConfig.class},
                LyricUiConfig.defaults().buildUpon().translationDisplayMode(1).build());
        assertEquals(1f, (Float) call(renderer, "rowTranslationAmount",
                new Class<?>[]{WordLine.class, float.class}, old, 1f), 0f);
    }

    @Test public void vocalTranslationRespectsToggleAndRangeWithoutBorrowingMainTranslation() throws Exception {
        Object renderer = renderer();
        call(renderer, "setConfig", new Class<?>[]{LyricUiConfig.class},
                LyricUiConfig.defaults().buildUpon()
                        .translationDisplayMode(LyricUiConfig.TRANSLATION_CURRENT_ONLY).build());
        WordLyricModel model = model();
        WordLine main = model.lines.get(0);
        prepare(renderer, model, 0, 4100L, true);
        Class<?>[] signature = {WordLine.class, float.class};
        assertEquals(0f, (Float) call(renderer, "secondaryTranslationAmount", signature, main, 1f), 0f);
        main.secondaryLine.translation = "echo translation";
        assertEquals(1f, (Float) call(renderer, "secondaryTranslationAmount", signature, main, 1f), 0f);
        assertEquals(0f, (Float) call(renderer, "secondaryTranslationAmount", signature, main, 0f), 0f);
        prepare(renderer, model, 1, 7000L, false);
        assertEquals(0f, (Float) call(renderer, "secondaryTranslationAmount", signature, main, 1f), 0f);
        call(renderer, "setConfig", new Class<?>[]{LyricUiConfig.class},
                LyricUiConfig.defaults().buildUpon().translationDisplayMode(1).build());
        assertEquals(1f, (Float) call(renderer, "secondaryTranslationAmount", signature, main, 1f), 0f);
    }

    @Test public void nestedTranslationUsesItsOwnTimingAndNeverFirstParentRowAsItsEnd() throws Exception {
        Object renderer = renderer();
        WordLyricModel model = model();
        WordLine vocal = model.lines.get(0).secondaryLine;
        vocal.translationLine = new WordLine(4500L, "own translation", new ArrayList<>(),
                5500L, LyricTimingMode.LINE_TIMED);
        Class<?>[] signature = {WordLyricModel.class, WordLine.class, long.class};
        assertEquals(0.5f, (Float) call(renderer, "resolveTranslationRevealProgress",
                signature, model, vocal, 5000L), 0.0001f);
        assertEquals(5200L, ((Long) call(renderer, "resolveLineDisplayEndMillis",
                new Class<?>[]{WordLyricModel.class, WordLine.class}, model, vocal)).longValue());
        call(renderer, "setStyleOptions", new Class<?>[]{boolean.class, boolean.class, boolean.class, boolean.class},
                false, false, false, true);
        call(renderer, "setConfig", new Class<?>[]{LyricUiConfig.class},
                LyricUiConfig.defaults().buildUpon().secondaryProgressEnabled(false)
                        .translationProgressEnabled(true).build());
        assertTrue((Boolean) call(renderer, "shouldDrawTranslationProgress", new Class<?>[]{WordLine.class}, vocal));
        call(renderer, "setStyleOptions", new Class<?>[]{boolean.class, boolean.class, boolean.class, boolean.class},
                false, false, false, false);
        assertFalse((Boolean) call(renderer, "shouldDrawTranslationProgress", new Class<?>[]{WordLine.class}, vocal));
    }

    @Test public void bothSingingParentsKeepTheirOwnVocalWithoutTakingSecondFocus() throws Exception {
        Object renderer = renderer();
        WordLyricModel model = model();
        WordLine first = model.lines.get(0);
        first.explicitEndTimeProvided = true;
        WordLine second = new WordLine(3000L, "second", new ArrayList<>(), 8000L,
                LyricTimingMode.LINE_TIMED);
        second.secondaryLine = new WordLine(4000L, "second echo", new ArrayList<>(), 6000L,
                LyricTimingMode.LINE_TIMED);
        model.lines.set(1, second);
        assertEquals(0, model.focusIndexAt(4100L));
        prepare(renderer, model, 0, 4100L, true);
        assertEquals(1f, first.vocalLayout.value(0L), 0f);
        assertEquals(1f, second.vocalLayout.value(0L), 0f);
        assertEquals(0f, second.secondaryLayout.value(0L), 0f);
        prepare(renderer, model, 1, 7000L, true);
        assertEquals(0f, first.vocalLayout.value(0L), 0f);
    }
}
