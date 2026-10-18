package com.naivety.zhicolor.bridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import com.naivety.zhicolor.bridge.bootstrap.SystemUiRuntimeBootstrap;
import com.naivety.zhicolor.bridge.diagnostics.BridgePerformanceSampler;
import com.naivety.zhicolor.bridge.diagnostics.StructuredBridgeLog;
import com.naivety.zhicolor.bridge.players.kuwo.KuWoArtworkSnapshotStore;
import com.naivety.zhicolor.bridge.players.kuwo.KuWoCoverPolicy;
import com.naivety.zhicolor.bridge.players.kuwo.KuWoMediaIdentityPolicy;
import com.naivety.zhicolor.bridge.players.kuwo.KuWoPluginMediaModelReader;
import com.naivety.zhicolor.bridge.players.kuwo.KuWoSameTrackLyricRetention;
import com.naivety.zhicolor.bridge.players.kuwo.KuWoSystemUiRuntime;
import com.naivety.zhicolor.bridge.render.OfficialLyricFrameResolver;
import com.naivety.zhicolor.bridge.render.LyricDrawLayoutEngine;
import com.naivety.zhicolor.bridge.systemui.lyrics.LyricsRecyclerPolicy;
import com.naivety.zhicolor.bridge.systemui.lyrics.LyricsRecyclerFieldAccessor;
import com.naivety.zhicolor.bridge.systemui.lyrics.SystemUiLoadLyricPolicy;

public final class BridgeArchitectureGuardTest {
    private static final List<String> FORBIDDEN_V4_MARKERS = Arrays.asList(
            "io.github.andrealtb.coloroslyrics.provider.",
            "EXTERNAL_LYRIC_DIRECT_V4",
            "lyricprovider/",
            "ExternalLyricProtocol",
            "ExternalLyricIngress",
            "ExternalLyricProviderRegistry",
            "ExternalLyricSenderPolicy",
            "senderKind",
            "lockscreen-lyrics-module",
            "PlayerRuntimeBootstrap",
            "PlayerAdapterRegistry");

    @Test
    public void compositionTypesLiveInTargetPackages() {
        assertEqualsPackage(
                "com.naivety.zhicolor.bridge",
                NativeLyricModelAssembler.class);
        assertEqualsPackage(
                "com.naivety.zhicolor.bridge",
                SupplementalTranslationIndex.class);
        assertEqualsPackage(
                "com.naivety.zhicolor.bridge",
                LyricModelTraceSupport.class);
        assertEqualsPackage(
                "com.naivety.zhicolor.bridge",
                OfficialLyricDrawCoordinator.class);
        assertEqualsPackage(
                "com.naivety.zhicolor.bridge.bootstrap",
                SystemUiRuntimeBootstrap.class);
        assertEqualsPackage(
                "com.naivety.zhicolor.bridge.diagnostics",
                StructuredBridgeLog.class);
        assertEqualsPackage(
                "com.naivety.zhicolor.bridge.diagnostics",
                BridgePerformanceSampler.class);
        assertEqualsPackage(
                "com.naivety.zhicolor.bridge.players.kuwo",
                KuWoMediaIdentityPolicy.class);
        assertEqualsPackage(
                "com.naivety.zhicolor.bridge.players.kuwo",
                KuWoCoverPolicy.class);
        assertEqualsPackage(
                "com.naivety.zhicolor.bridge.players.kuwo",
                KuWoArtworkSnapshotStore.class);
        assertEqualsPackage(
                "com.naivety.zhicolor.bridge.players.kuwo",
                KuWoSameTrackLyricRetention.class);
        assertEqualsPackage(
                "com.naivety.zhicolor.bridge.players.kuwo",
                KuWoPluginMediaModelReader.class);
        assertEqualsPackage(
                "com.naivety.zhicolor.bridge.players.kuwo",
                KuWoSystemUiRuntime.class);
        assertEqualsPackage(
                "com.naivety.zhicolor.bridge.render",
                OfficialLyricFrameResolver.class);
        assertEqualsPackage(
                "com.naivety.zhicolor.bridge.render",
                LyricDrawLayoutEngine.class);
        assertEqualsPackage(
                "com.naivety.zhicolor.bridge.systemui.lyrics",
                SystemUiLoadLyricPolicy.class);
        assertEqualsPackage(
                "com.naivety.zhicolor.bridge.systemui.lyrics",
                LyricsRecyclerPolicy.class);
        assertEqualsPackage(
                "com.naivety.zhicolor.bridge.systemui.lyrics",
                LyricsRecyclerFieldAccessor.class);
    }

    @Test
    public void appMainContainsNoForeignProviderApplicationIds() throws Exception {
        File main = locateFromProject("app/src/main");
        assumeTrue("app/src/main is visible to unit tests", main.isDirectory());
        for (File file : Files.walk(main.toPath())
                .filter(Files::isRegularFile)
                .map(java.nio.file.Path::toFile)
                .collect(Collectors.toList())) {
            String source = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            for (String marker : FORBIDDEN_V4_MARKERS) {
                assertFalse(file + " contains forbidden Phase 5 marker " + marker,
                        source.contains(marker));
            }
        }
    }

    @Test
    public void gradleAndScopeExposeOnlySystemUiBridge() throws Exception {
        String settings = readProjectFile("settings.gradle.kts");
        String appGradle = readProjectFile("app/build.gradle.kts");
        List<String> scope = Files.readAllLines(
                locateFromProject("app/src/main/resources/META-INF/xposed/scope.list").toPath(),
                StandardCharsets.UTF_8).stream()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .collect(Collectors.toList());
        assertFalse(settings.contains("external-lyric-protocol"));
        assertFalse(appGradle.contains("external-lyric-protocol"));
        assertEquals(Arrays.asList("system", "com.android.systemui"), scope);
    }

    @Test
    public void systemUiRuntimeContainsNoNetworkClientOrLeakyClassCache() throws Exception {
        String module = readProjectFile(
                "bridge-core/src/main/java/com/naivety/zhicolor/bridge/LockscreenLyricsModule.java");
        String coordinator = readProjectFile(
                "bridge-core/src/main/java/com/naivety/zhicolor/bridge/"
                        + "OfficialLyricDrawCoordinator.java");
        String accessor = readProjectFile(
                "bridge-core/src/main/java/com/naivety/zhicolor/bridge/systemui/lyrics/"
                        + "LyricsRecyclerFieldAccessor.java");
        assertFalse(module.contains("java.net.URL"));
        assertFalse(module.contains("HttpURLConnection"));
        assertFalse(module.contains("openConnection("));
        assertFalse(module.contains("WeakReference<MediaController>"));
        assertFalse(module.contains("WeakHashMap<Class<?>, Method"));
        assertFalse(accessor.contains("WeakHashMap<Class<?>, Field"));
        assertTrue(accessor.contains("WeakReference<Field>"));
        assertFalse(module.contains("canvas.restore();"));
        assertFalse(module.contains("target.canvas.restore();"));
        assertTrue(coordinator.contains("failedBindings.put(textView, model)"));
        assertTrue(coordinator.contains("return proceed.proceed();"));
    }

    private static void assertEqualsPackage(String expected, Class<?> type) {
        assertEquals(expected, type.getPackage().getName());
    }

    private static String readProjectFile(String relativePath) throws Exception {
        File file = locateFromProject(relativePath);
        assumeTrue(relativePath + " is visible to unit tests", file.isFile());
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static File locateFromProject(String relativePath) {
        File direct = new File(relativePath);
        if (direct.exists()) {
            return direct;
        }
        return new File(".." + File.separator + relativePath);
    }
}
