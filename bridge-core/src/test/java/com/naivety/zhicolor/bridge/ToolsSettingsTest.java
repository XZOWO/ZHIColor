package com.naivety.zhicolor.bridge;

import com.naivety.zhicolor.bridge.diagnostics.BridgeDebugConfig;

import org.junit.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** JVM contracts for the two independent tool-card visibility preferences. */
public final class ToolsSettingsTest {
    @Test
    public void defaultsAndIndependentStoredFlagsKeepTheTwoCardsSeparate() {
        PreferenceTestContext context = new PreferenceTestContext();

        assertFalse(ToolsSettings.hideLockscreenMusicApp(context));
        assertFalse(ToolsSettings.hideControlCenterMusicApp(context));

        context.values(ToolsSettings.PREFERENCES)
                .put(ToolsSettings.HIDE_LOCKSCREEN_MUSIC_APP, true);
        assertTrue(ToolsSettings.hideLockscreenMusicApp(context));
        assertFalse(ToolsSettings.hideControlCenterMusicApp(context));

        context.values(ToolsSettings.PREFERENCES)
                .put(ToolsSettings.HIDE_CONTROL_CENTER_MUSIC_APP, true);
        assertTrue(ToolsSettings.hideLockscreenMusicApp(context));
        assertTrue(ToolsSettings.hideControlCenterMusicApp(context));

        context.values(ToolsSettings.PREFERENCES)
                .put(ToolsSettings.HIDE_LOCKSCREEN_MUSIC_APP, false);
        assertFalse(ToolsSettings.hideLockscreenMusicApp(context));
        assertTrue(ToolsSettings.hideControlCenterMusicApp(context));
    }

    @Test
    public void legacyLyricOnlyBackupRestoreLeavesBothToolFlagsAndRevisionUntouched() {
        PreferenceTestContext context = new PreferenceTestContext();
        Map<String, Object> tools = context.values(ToolsSettings.PREFERENCES);
        tools.put(ToolsSettings.HIDE_LOCKSCREEN_MUSIC_APP, true);
        tools.put(ToolsSettings.HIDE_CONTROL_CENTER_MUSIC_APP, false);
        tools.put(ToolsSettings.REVISION, 42L);
        Map<String, Object> before = new LinkedHashMap<>(tools);

        Map<String, Map<String, ?>> lyricOnlyBackup = new LinkedHashMap<>();
        lyricOnlyBackup.put(LyricUiSettings.PREFERENCES_NAME,
                Collections.singletonMap("lyric_restore_probe", true));
        lyricOnlyBackup.put(BridgeDebugConfig.PREFS_NAME, Collections.emptyMap());

        BridgeConfigBackupRepository.restoreAll(context,
                BridgeConfigBackupCodec.encode(lyricOnlyBackup));

        assertEquals(before, context.values(ToolsSettings.PREFERENCES));
        assertTrue(ToolsSettings.hideLockscreenMusicApp(context));
        assertFalse(ToolsSettings.hideControlCenterMusicApp(context));
        assertEquals(Long.valueOf(42L), context.values(ToolsSettings.PREFERENCES)
                .get(ToolsSettings.REVISION));
    }
}
