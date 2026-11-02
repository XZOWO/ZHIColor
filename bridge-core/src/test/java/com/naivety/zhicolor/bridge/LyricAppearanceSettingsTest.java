package com.naivety.zhicolor.bridge;

import android.content.SharedPreferences;
import org.junit.Test;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Collections;
import static org.junit.Assert.*;

public class LyricAppearanceSettingsTest {
    @Test public void freshAndLegacyModesBothDefaultToAppleWithoutCopyingBrokenAdvancedOptions() {
        for (int legacy : new int[]{0,1}) {
            PreferenceTestContext context = new PreferenceTestContext();
            SharedPreferences controls = BridgeSettings.controls(context);
            controls.edit().putInt(BridgeSettings.STYLE,legacy)
                    .putBoolean("advanced_dynamic_background",false).putBoolean("advanced_motion",false)
                    .putInt("advanced_text_scale",160).apply();
            Map<String,Object> before = new LinkedHashMap<>(BridgeSettings.values(context));
            LyricAppearanceSettings settings = LyricAppearanceSettings.load(context);
            assertEquals(LyricAppearanceSettings.APPLE,settings.background);
            assertEquals(LyricAppearanceSettings.APPLE,settings.font);
            assertTrue(settings.appleBackground);
            assertEquals(before,BridgeSettings.values(context));
        }
    }

    @Test public void animationDefaultsOnAndDoesNotImportAdvancedMotionFlags() {
        PreferenceTestContext context = new PreferenceTestContext();
        BridgeSettings.controls(context).edit().putBoolean("advanced_dynamic_background",false).apply();
        assertTrue(LyricAppearanceSettings.load(context).backgroundAnimation);
    }

    @Test public void animationOffKeepsAppleAndPersistsAcrossSystemChoiceAndBackup() {
        PreferenceTestContext context = new PreferenceTestContext();
        SharedPreferences controls = BridgeSettings.controls(context);
        controls.edit().putBoolean(LyricAppearanceSettings.BACKGROUND_ANIMATION,false).apply();
        assertTrue(LyricAppearanceSettings.load(context).appleBackground);
        LyricAppearanceSettings.saveChoice(controls,LyricAppearanceSettings.BACKGROUND,0);
        LyricAppearanceSettings.saveChoice(controls,LyricAppearanceSettings.BACKGROUND,1);
        assertFalse(LyricAppearanceSettings.load(context).backgroundAnimation);
        BridgeSettings.initialize(context);
        PreferenceTestContext restored = new PreferenceTestContext();
        BridgeConfigBackupRepository.restoreAll(restored,BridgeConfigBackupRepository.exportAll(context));
        assertFalse(LyricAppearanceSettings.load(restored).backgroundAnimation);
        assertTrue(LyricAppearanceSettings.load(restored).appleBackground);
        BridgeSettings.restoreDefaults(restored);
        assertTrue(LyricAppearanceSettings.load(restored).backgroundAnimation);
    }

    @Test public void backgroundAndFontChoicesAreIndependentAndPreserveStandardPreferences() {
        PreferenceTestContext context = new PreferenceTestContext();
        SharedPreferences controls = BridgeSettings.controls(context);
        Map<String,Object> before = new LinkedHashMap<>(BridgeSettings.values(context));
        LyricAppearanceSettings.saveChoice(controls,LyricAppearanceSettings.BACKGROUND,0);
        LyricAppearanceSettings settings=LyricAppearanceSettings.load(context);
        assertFalse(settings.appleBackground);
        assertEquals(1,settings.font);
        LyricAppearanceSettings.saveChoice(controls,LyricAppearanceSettings.FONT,0);
        LyricAppearanceSettings.saveChoice(controls,LyricAppearanceSettings.BACKGROUND,1);
        settings=LyricAppearanceSettings.load(context);
        assertTrue(settings.appleBackground);
        assertEquals(0,settings.font);
        assertEquals(before,BridgeSettings.values(context));
    }

    @Test public void romanizationPreservesExistingChoiceAndNewChoiceWinsAfterBackupImport() {
        PreferenceTestContext app=new PreferenceTestContext();
        SharedPreferences controls=BridgeSettings.controls(app);
        controls.edit().putBoolean("advanced_romanization",false).apply();
        assertFalse(LyricAppearanceSettings.load(app).romanization);
        controls.edit().putBoolean(LyricAppearanceSettings.ROMANIZATION,true)
                .putInt(LyricAppearanceSettings.BACKGROUND,0).putInt(LyricAppearanceSettings.FONT,0).apply();
        BridgeSettings.initialize(app);
        String backup=BridgeConfigBackupRepository.exportAll(app);
        PreferenceTestContext restored=new PreferenceTestContext();
        BridgeConfigBackupRepository.restoreAll(restored,backup);
        LyricAppearanceSettings next=LyricAppearanceSettings.load(restored);
        assertTrue(next.romanization);
        assertEquals(0,next.background);
        assertEquals(0,next.font);
        BridgeSettings.restoreDefaults(restored);
        next=LyricAppearanceSettings.load(restored);
        assertTrue(next.romanization);
        assertEquals(1,next.background);
        assertEquals(1,next.font);
    }

    @Test public void disabledModuleKeepsSelectionsButStopsBackground() {
        PreferenceTestContext context=new PreferenceTestContext();
        BridgeSettings.controls(context).edit().putBoolean(BridgeSettings.MASTER,false).apply();
        LyricAppearanceSettings settings=LyricAppearanceSettings.load(context);
        assertFalse(settings.appleBackground);
        assertEquals(1,settings.background);
        assertEquals(1,settings.font);
    }

    @Test public void appearanceSnapshotDoesNotClearCardTranslationChoice() {
        PreferenceTestContext context=new PreferenceTestContext();
        SharedPreferences prefs=BridgeSettings.prefs(context);
        String card=LyricUiSettings.TRANSLATION_PREFERENCE_KEY+".com.apple.android.music";
        prefs.edit().putBoolean(card,false).apply();
        LyricAppearanceSettings.saveChoice(BridgeSettings.controls(context),LyricAppearanceSettings.FONT,0);
        BridgeSettings.applyMainSnapshot(prefs,LyricUiConfigCodec.encode(LyricUiConfig.defaults()),false,Collections.emptySet());
        assertTrue(prefs.contains(card));
        assertFalse(prefs.getBoolean(card,true));
    }
}
