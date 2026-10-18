package com.naivety.zhicolor.bridge;

import android.content.SharedPreferences;

import org.junit.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.*;

public final class BridgeTranslationSettingsTest {
    @Test public void changingTheGlobalDefaultInvalidatesCardMemoryButPreservesPlayerDefaults() {
        PreferenceTestContext app = new PreferenceTestContext();
        SharedPreferences appPreferences = BridgeSettings.prefs(app);
        String pkg = "com.apple.android.music";
        BridgeSettings.setTranslationEnabled(appPreferences, pkg, false);
        Map<String,Object> changed = new LinkedHashMap<>(BridgeSettings.values(app));
        changed.put(LyricUiConfigCodec.DEFAULT_TRANSLATION, true);

        boolean clearMemory = BridgeSettings.save(appPreferences, changed);
        assertTrue(clearMemory);
        assertTrue(LyricUiConfigRepository.load(appPreferences).defaultTranslationEnabled);
        assertTrue(BridgeSettings.hasTranslationOverride(app, pkg));
        assertFalse(BridgeSettings.translation(app, pkg));

        PreferenceTestContext systemUi = new PreferenceTestContext();
        SharedPreferences runtimePreferences = BridgeSettings.prefs(systemUi);
        runtimePreferences.edit()
                .putBoolean(LyricUiSettings.TRANSLATION_PREFERENCE_KEY, false)
                .putBoolean(LyricUiSettings.TRANSLATION_PREFERENCE_KEY + "." + pkg, true).apply();
        BridgeSettings.applyMainSnapshot(runtimePreferences, appPreferences.getAll(),
                clearMemory, Collections.emptySet());
        assertFalse(runtimePreferences.contains(LyricUiSettings.TRANSLATION_PREFERENCE_KEY));
        assertFalse(runtimePreferences.contains(LyricUiSettings.TRANSLATION_PREFERENCE_KEY + "." + pkg));
        assertFalse(BridgeSettings.translation(systemUi, pkg));
        assertTrue(BridgeSettings.translation(systemUi, "com.example.customplayer"));
    }

    @Test public void ordinaryVisualSavesDoNotInvalidateCardMemoryOrRewriteExplicitUserDefaults() {
        PreferenceTestContext context = new PreferenceTestContext();
        SharedPreferences p = BridgeSettings.prefs(context);
        LyricUiConfigRepository.save(p, LyricUiConfig.defaults().buildUpon()
                .defaultTranslationEnabled(true).build());
        p.edit().putBoolean(LyricUiSettings.KEY_DEFAULT_TRANSLATION_BUTTON, false).apply();
        Map<String,Object> changed = new LinkedHashMap<>(BridgeSettings.values(context));
        changed.put(LyricUiConfigCodec.MAIN_FONT_SIZE, 250);
        assertFalse(BridgeSettings.save(p, changed));
        assertTrue(BridgeSettings.translation(context, "com.apple.android.music"));
        assertFalse(BridgeSettings.translationButton(context, "com.apple.android.music"));
        assertEquals(250, LyricUiConfigRepository.load(p).mainFontTenthsSp);
        assertFalse(BridgeSettings.save(p, changed));
    }

    @Test public void changingOnlyTheButtonDoesNotFreezeTheInheritedTranslationDefault() {
        PreferenceTestContext context = new PreferenceTestContext();
        SharedPreferences p = BridgeSettings.prefs(context);
        String pkg = "com.apple.android.music";
        assertFalse(BridgeSettings.translation(context, pkg));
        assertTrue(BridgeSettings.translationButton(context, pkg));
        BridgeSettings.setTranslationButton(p, pkg, false);
        assertFalse(BridgeSettings.hasTranslationOverride(context, pkg));
        assertTrue(BridgeSettings.hasTranslationButtonOverride(context, pkg));
        LyricUiConfigRepository.save(p, LyricUiConfig.defaults().buildUpon().defaultTranslationEnabled(true).build());
        assertTrue(BridgeSettings.translation(context, pkg));
        assertFalse(BridgeSettings.translationButton(context, pkg));
    }

    @Test public void changingOnlyTranslationDoesNotFreezeTheInheritedButtonDefault() {
        PreferenceTestContext context = new PreferenceTestContext();
        SharedPreferences p = BridgeSettings.prefs(context);
        String pkg = "com.example.customplayer";
        BridgeSettings.setTranslationEnabled(p, pkg, true);
        assertTrue(BridgeSettings.hasTranslationOverride(context, pkg));
        assertFalse(BridgeSettings.hasTranslationButtonOverride(context, pkg));
        p.edit().putBoolean(LyricUiSettings.KEY_DEFAULT_TRANSLATION_BUTTON, false).apply();
        assertTrue(BridgeSettings.translation(context, pkg));
        assertFalse(BridgeSettings.translationButton(context, pkg));
    }

    @Test public void eachPlayerControlCanResumeFollowingItsGlobalDefaultIndependently() {
        PreferenceTestContext context = new PreferenceTestContext();
        SharedPreferences p = BridgeSettings.prefs(context);
        String pkg = "com.apple.android.music";
        BridgeSettings.setTranslationEnabled(p, pkg, true);
        BridgeSettings.setTranslationButton(p, pkg, false);
        BridgeSettings.followTranslationDefault(p, pkg);
        assertFalse(BridgeSettings.translation(context, pkg));
        assertFalse(BridgeSettings.hasTranslationOverride(context, pkg));
        assertTrue(BridgeSettings.hasTranslationButtonOverride(context, pkg));
        BridgeSettings.followTranslationButtonDefault(p, pkg);
        assertTrue(BridgeSettings.translationButton(context, pkg));
        assertFalse(BridgeSettings.hasTranslationButtonOverride(context, pkg));
    }

    @Test public void exactVisualSnapshotDeletesOldPlayerDefaultsButRetainsCardMemory() {
        PreferenceTestContext context = new PreferenceTestContext();
        SharedPreferences p = BridgeSettings.prefs(context);
        String remembered = LyricUiSettings.TRANSLATION_PREFERENCE_KEY + ".com.apple.android.music";
        p.edit().putBoolean(remembered, true)
                .putBoolean(LyricUiSettings.translationDefaultKeyForPackage("com.apple.android.music"), true)
                .putBoolean(LyricUiSettings.translationButtonKeyForPackage("com.apple.android.music"), false)
                .putString("obsolete_config", "old").apply();
        Map<String, Object> incoming = new LinkedHashMap<>(LyricUiConfigCodec.encode(LyricUiConfig.defaults()));
        BridgeSettings.applyMainSnapshot(p, incoming, false, Collections.emptySet());
        assertTrue(p.getBoolean(remembered, false));
        assertFalse(p.contains("obsolete_config"));
        assertFalse(p.contains(LyricUiSettings.translationDefaultKeyForPackage("com.apple.android.music")));
        assertFalse(p.contains(LyricUiSettings.translationButtonKeyForPackage("com.apple.android.music")));
        assertEquals(incoming.size() + 1, p.getAll().size());
    }

    @Test public void playerTranslationChangeClearsOnlyThatPlayersCardMemory() {
        PreferenceTestContext context = new PreferenceTestContext();
        SharedPreferences p = BridgeSettings.prefs(context);
        String apple = LyricUiSettings.TRANSLATION_PREFERENCE_KEY + ".com.apple.android.music";
        String custom = LyricUiSettings.TRANSLATION_PREFERENCE_KEY + ".com.example.customplayer";
        p.edit().putBoolean(apple, true).putBoolean(custom, false).apply();
        BridgeSettings.applyMainSnapshot(p, Collections.emptyMap(), false,
                Collections.singleton("com.apple.android.music"));
        assertFalse(p.contains(apple));
        assertTrue(p.contains(custom));
        assertFalse(p.getBoolean(custom, true));
    }

    @Test public void resetOrImportCannotReintroduceRememberedCardChoicesFromAnOldSnapshot() {
        PreferenceTestContext context = new PreferenceTestContext();
        SharedPreferences p = BridgeSettings.prefs(context);
        String remembered = LyricUiSettings.TRANSLATION_PREFERENCE_KEY + ".com.apple.android.music";
        p.edit().putBoolean(remembered, true).putBoolean(LyricUiSettings.TRANSLATION_PREFERENCE_KEY, true).apply();
        Map<String, Object> incoming = new LinkedHashMap<>(LyricUiConfigCodec.encode(LyricUiConfig.defaults()));
        incoming.put(remembered, true);
        BridgeSettings.applyMainSnapshot(p, incoming, true, Collections.emptySet());
        assertFalse(p.contains(remembered));
        assertFalse(p.contains(LyricUiSettings.TRANSLATION_PREFERENCE_KEY));
        assertFalse(BridgeSettings.translation(context, "com.apple.android.music"));
    }

    @Test public void cleanupRulesAreValidatedBeforeTheirDialogCanReportSuccess() {
        assertEquals("produced by:", BridgeSettings.validateCleanupRule("prefix", " Produced By: "));
        assertEquals("a", BridgeSettings.validateCleanupRule("exact", "A"));
        for (String[] invalid : new String[][]{{"prefix", "a"}, {"exact", "  "},
                {"unknown", "valid text"}, {"prefix", "x".repeat(41)}, {"exact", "x".repeat(161)}}) {
            try { BridgeSettings.validateCleanupRule(invalid[0], invalid[1]); fail("Invalid rule accepted"); }
            catch (IllegalArgumentException expected) { assertNotNull(expected.getMessage()); }
        }
    }

    @Test public void persistedTranslationResetReachesEachRuntimeProcessOnceAfterOfflineChanges() {
        Map<String,Object> incoming=new LinkedHashMap<>();
        incoming.put(BridgeSettings.TRANSLATION_RESET_REVISION,30L);
        incoming.put(BridgeSettings.TRANSLATION_PLAYER_RESET_REVISION+"com.apple.android.music",40L);
        incoming.put(BridgeSettings.TRANSLATION_PLAYER_RESET_REVISION+"com.example.customplayer",20L);
        Map<String,Object> systemUi=new LinkedHashMap<>();
        systemUi.put(BridgeSettings.TRANSLATION_RESET_REVISION,10L);
        systemUi.put(BridgeSettings.TRANSLATION_PLAYER_RESET_REVISION+"com.example.customplayer",20L);
        Map<String,Object> systemServer=new LinkedHashMap<>();

        assertTrue(BridgeSettings.hasNewTranslationReset(incoming,systemUi));
        assertTrue(BridgeSettings.hasNewTranslationReset(incoming,systemServer));
        assertEquals(Collections.singleton("com.apple.android.music"),BridgeSettings.newPlayerTranslationResets(incoming,systemUi));
        assertEquals(2,BridgeSettings.newPlayerTranslationResets(incoming,systemServer).size());
        systemUi.clear();systemUi.putAll(incoming);
        systemServer.clear();systemServer.putAll(incoming);
        assertFalse(BridgeSettings.hasNewTranslationReset(incoming,systemUi));
        assertFalse(BridgeSettings.hasNewTranslationReset(incoming,systemServer));
        assertTrue(BridgeSettings.newPlayerTranslationResets(incoming,systemUi).isEmpty());
        assertTrue(BridgeSettings.newPlayerTranslationResets(incoming,systemServer).isEmpty());
    }

    @Test public void ordinarySnapshotsDoNotReplayOldTranslationResetRevisions() {
        Map<String,Object> previous=new LinkedHashMap<>();
        previous.put(BridgeSettings.TRANSLATION_RESET_REVISION,40L);
        previous.put(BridgeSettings.TRANSLATION_PLAYER_RESET_REVISION+"com.apple.android.music",50L);
        Map<String,Object> old=new LinkedHashMap<>();
        old.put(BridgeSettings.TRANSLATION_RESET_REVISION,30L);
        old.put(BridgeSettings.TRANSLATION_PLAYER_RESET_REVISION+"com.apple.android.music",40L);
        assertFalse(BridgeSettings.hasNewTranslationReset(old,previous));
        assertTrue(BridgeSettings.newPlayerTranslationResets(old,previous).isEmpty());
        assertFalse(BridgeSettings.hasNewTranslationReset(Collections.emptyMap(),previous));
        assertTrue(BridgeSettings.newPlayerTranslationResets(Collections.emptyMap(),previous).isEmpty());
    }
}
