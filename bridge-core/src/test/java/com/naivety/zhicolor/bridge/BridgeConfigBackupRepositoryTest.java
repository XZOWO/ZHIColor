package com.naivety.zhicolor.bridge;

import com.naivety.zhicolor.bridge.diagnostics.BridgeDebugConfig;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;

import static org.junit.Assert.*;

public final class BridgeConfigBackupRepositoryTest {
    @Test public void fullBackupPreservesControlsProvidersEmptyAppSelectionAndTools() {
        PreferenceTestContext source = configured();
        source.values(BridgeSettings.CONTROL_PREFS).put(BridgeSettings.APPS, Collections.emptySet());
        BridgeConfigBackupCodec.Backup backup = BridgeConfigBackupCodec.decode(BridgeConfigBackupRepository.exportAll(source));
        assertEquals(new LinkedHashSet<>(Arrays.asList(LyricUiSettings.PREFERENCES_NAME,
                BridgeDebugConfig.PREFS_NAME, BridgeSettings.CONTROL_PREFS, ToolsSettings.PREFERENCES)), backup.namespaceNames());
        PreferenceTestContext target = configured();
        target.values(BridgeSettings.CONTROL_PREFS).put("obsolete", true);
        target.values(ToolsSettings.PREFERENCES).put("obsolete", true);
        BridgeConfigBackupRepository.restoreAll(target, BridgeConfigBackupRepository.exportAll(source));
        assertEquals(source.domains, target.domains);
        assertFalse(target.values(ToolsSettings.PREFERENCES).containsKey("obsolete"));
        assertEquals(Collections.emptySet(), target.values(BridgeSettings.CONTROL_PREFS).get(BridgeSettings.APPS));
    }

    @Test public void oldTwoDomainBackupRestoresLyricsAndDebugWithoutErasingControlsOrTools() {
        PreferenceTestContext target = configured();
        Map<String, Object> controls = new LinkedHashMap<>(target.values(BridgeSettings.CONTROL_PREFS));
        Map<String, Object> tools = new LinkedHashMap<>(target.values(ToolsSettings.PREFERENCES));
        Map<String, Map<String, ?>> old = new LinkedHashMap<>();
        old.put(LyricUiSettings.PREFERENCES_NAME, Collections.singletonMap("restored", true));
        old.put(BridgeDebugConfig.PREFS_NAME, Collections.emptyMap());
        BridgeConfigBackupRepository.restoreAll(target, BridgeConfigBackupCodec.encode(old));
        assertEquals(Collections.singletonMap("restored", true), target.values(LyricUiSettings.PREFERENCES_NAME));
        assertTrue(target.values(BridgeDebugConfig.PREFS_NAME).isEmpty());
        assertEquals(controls, target.values(BridgeSettings.CONTROL_PREFS));
        assertEquals(tools, target.values(ToolsSettings.PREFERENCES));
    }

    @Test public void explicitlyEmptyToolDomainIsAnExactReplacement() {
        PreferenceTestContext target = configured();
        Map<String, Map<String, ?>> backup = new LinkedHashMap<>();
        backup.put(LyricUiSettings.PREFERENCES_NAME, Collections.emptyMap());
        backup.put(BridgeDebugConfig.PREFS_NAME, Collections.emptyMap());
        backup.put(ToolsSettings.PREFERENCES, Collections.emptyMap());
        BridgeConfigBackupRepository.restoreAll(target, BridgeConfigBackupCodec.encode(backup));
        assertTrue(target.values(ToolsSettings.PREFERENCES).isEmpty());
        assertTrue(target.values(BridgeSettings.CONTROL_PREFS).containsKey("provider.com.example.player"));
    }

    @Test public void failureInTheLastDomainRestoresEveryEarlierDomain() {
        PreferenceTestContext target = configured();
        String before = BridgeConfigBackupRepository.exportAll(target);
        PreferenceTestContext source = configured();
        for (Map<String, Object> values : source.domains.values()) values.clear();
        target.failNextCommit = ToolsSettings.PREFERENCES;
        try { BridgeConfigBackupRepository.restoreAll(target, BridgeConfigBackupRepository.exportAll(source)); fail("Expected failure"); }
        catch (IllegalStateException expected) { assertEquals(before, BridgeConfigBackupRepository.exportAll(target)); }
    }

    @Test public void resetClearsAllFourDomainsAndRollsBackIfAnyCommitFails() {
        PreferenceTestContext target = configured();
        String before = BridgeConfigBackupRepository.exportAll(target);
        target.failNextCommit = ToolsSettings.PREFERENCES;
        try { BridgeConfigBackupRepository.clearAll(target); fail("Expected failure"); }
        catch (IllegalStateException expected) { assertEquals(before, BridgeConfigBackupRepository.exportAll(target)); }
        BridgeConfigBackupRepository.clearAll(target);
        for (Map<String, Object> values : target.domains.values()) assertTrue(values.isEmpty());
    }

    @Test public void unknownDomainsAreRejectedWithoutChangingAnyPreferences() {
        PreferenceTestContext target = configured();
        String before = BridgeConfigBackupRepository.exportAll(target);
        Map<String, Map<String, ?>> backup = new LinkedHashMap<>();
        backup.put(LyricUiSettings.PREFERENCES_NAME, Collections.emptyMap());
        backup.put(BridgeDebugConfig.PREFS_NAME, Collections.emptyMap());
        backup.put("foreign_domain", Collections.emptyMap());
        try { BridgeConfigBackupRepository.restoreAll(target, BridgeConfigBackupCodec.encode(backup)); fail("Expected rejection"); }
        catch (IllegalArgumentException expected) { assertEquals(before, BridgeConfigBackupRepository.exportAll(target)); }
    }

    private static PreferenceTestContext configured() {
        PreferenceTestContext context = new PreferenceTestContext();
        context.values(LyricUiSettings.PREFERENCES_NAME).put(LyricUiConfigCodec.DEFAULT_TRANSLATION, false);
        context.values(BridgeDebugConfig.PREFS_NAME).put(BridgeDebugConfig.KEY_MASTER, true);
        context.values(BridgeSettings.CONTROL_PREFS).put(BridgeSettings.MASTER, false);
        context.values(BridgeSettings.CONTROL_PREFS).put(BridgeSettings.STYLE, 1);
        context.values(BridgeSettings.CONTROL_PREFS).put(BridgeSettings.APPS, new LinkedHashSet<>(Collections.singleton("com.example.player")));
        context.values(BridgeSettings.CONTROL_PREFS).put("provider.com.example.player", "com.example.provider");
        context.values(ToolsSettings.PREFERENCES).put(ToolsSettings.HIDE_LOCKSCREEN_MUSIC_APP, true);
        context.values(ToolsSettings.PREFERENCES).put(ToolsSettings.REVISION, 12345L);
        return context;
    }
}
