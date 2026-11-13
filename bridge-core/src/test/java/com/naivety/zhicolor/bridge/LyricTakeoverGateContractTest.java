package com.naivety.zhicolor.bridge;

import static org.junit.Assert.*;

import io.github.libxposed.api.XposedInterface;
import java.io.File;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import org.junit.Test;

public final class LyricTakeoverGateContractTest {
    @Test public void masterOffPassesAllGuardedHooksThroughWithoutTouchingArguments() throws Throwable {
        Field flag = ZhiBridgeRuntime.class.getDeclaredField("enabled");
        flag.setAccessible(true);
        boolean previous = flag.getBoolean(null);
        try {
            flag.setBoolean(null, false);
            for (String name : List.of("onTextViewOnDraw", "onTextViewSetText", "onOplusHistoryWhitelistLookup",
                    "onAodMediaSupportLookup", "onLyricsRecyclerSetCurrentLyric")) {
                NativeChain chain = new NativeChain();
                int[] modifications = {0};
                Object result = ZhiBridgeRuntime.guard(c -> { modifications[0]++; return new Object(); }, name)
                        .intercept(chain);
                assertSame(chain.nativeResult, result);
                assertEquals(1, chain.nativeCalls);
                assertEquals(0, chain.argumentReads);
                assertEquals(0, modifications[0]);
            }
        } finally { flag.setBoolean(null, previous); }
    }

    @Test public void disableRestoresNativeModelsAndCancelsLateCustomWork() throws Exception {
        String source = read("LockscreenLyricsModule.java");
        int start = source.indexOf("public void refreshFromZhiRuntime(boolean enabled)");
        int end = source.indexOf("private SystemUiDexKitAdapter.Targets", start);
        String body = source.substring(start, end);
        for (String expected : List.of("systemUiLyricPublicationGate.invalidate()", "translationLayoutGeneration++",
                "lyricRecyclerForceAlignGeneration++",
                "cancelActiveLyricRefreshCallbacks(true)", "NativeLyricsAnchorPolicy.restoreAll()",
                "OfficialLyricMotionController.restoreAll()", "restoreNativeLyricContentDefaults()",
                "restoreNativeTranslationActionDefaults()", "translationToggleActionBinder.restoreAll()",
                "releaseScreenTimeoutWakeLock", "rebindNativeLyricRows()")) assertTrue(expected, body.contains(expected));
        assertTrue(source.contains("return ZhiBridgeRuntime.enabled()\n                                ? backgroundHost.nativeBackgroundModelBuilt"));
        for (String method : List.of("private void redrawLyricRenderTargets(",
                "private void refreshLyricViewsAfterTranslationToggle(int generation, boolean preserveAnchor)",
                "private void configureOfficialLyricLineSpacing(", "private void configureOfficialLyricScaleOwnership(",
                "private void applyTranslationActionViewVisibility(", "private void reapplyCurrentTranslationButtonSetting()")) {
            int at = source.indexOf(method);
            assertTrue(method, at >= 0);
            assertTrue(method, source.substring(at, Math.min(source.length(), at + 250))
                    .contains("if (!ZhiBridgeRuntime.enabled()) return"));
        }
    }

    @Test public void disabledTakeoverCannotRunRecyclerAlignmentOrRecoveryRetries() throws Exception {
        String source = read("LockscreenLyricsModule.java");
        int forceAlignStart = source.lastIndexOf("private boolean forceAlignLyricsRecyclerToIndex(");
        int retryStart = source.indexOf("private void alignLyricsRecyclerAfterLayout(", forceAlignStart);
        int followStart = source.indexOf("private void followAdaptiveNativeLineTimedRecycler(", retryStart);
        int resetStart = source.indexOf("private void resetAdaptiveLineTimedRecyclerFollow(", followStart);
        assertTrue(forceAlignStart >= 0 && retryStart > forceAlignStart);
        assertTrue(retryStart >= 0 && followStart > retryStart);
        assertTrue(followStart >= 0 && resetStart > followStart);

        String forceAlign = source.substring(forceAlignStart, retryStart);
        String retry = source.substring(retryStart, followStart);
        String follow = source.substring(followStart, resetStart);
        assertTrue(forceAlign, forceAlign.contains("if (!ZhiBridgeRuntime.enabled()"));
        assertTrue(retry, retry.contains("if (!ZhiBridgeRuntime.enabled()")
                && retry.contains("!generationMatches"));
        assertTrue(follow, follow.contains("if (!ZhiBridgeRuntime.enabled()"));
    }

    private static final class NativeChain implements XposedInterface.Chain {
        final Object nativeResult = new Object();
        int nativeCalls, argumentReads;
        public Executable getExecutable() { throw new AssertionError("native hook metadata read"); }
        public Object getThisObject() { argumentReads++; throw new AssertionError("native target read"); }
        public List<Object> getArgs() { argumentReads++; throw new AssertionError("native args read"); }
        public Object getArg(int index) { argumentReads++; throw new AssertionError("native argument read"); }
        public Object proceed() { nativeCalls++; return nativeResult; }
        public Object proceed(Object[] args) { throw new AssertionError("native args changed"); }
        public Object proceedWith(Object target) { throw new AssertionError("native target changed"); }
        public Object proceedWith(Object target, Object[] args) { throw new AssertionError("native invocation changed"); }
    }

    private static String read(String name) throws Exception {
        String relative = "bridge-core/src/main/java/com/naivety/zhicolor/bridge/" + name;
        File file = new File(relative);
        if (!file.isFile()) file = new File("..", relative);
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}
