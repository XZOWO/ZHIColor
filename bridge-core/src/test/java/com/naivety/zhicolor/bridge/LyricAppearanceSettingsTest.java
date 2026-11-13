package com.naivety.zhicolor.bridge;

import android.content.SharedPreferences;
import org.junit.Test;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Collections;
import static org.junit.Assert.*;

public class LyricAppearanceSettingsTest {
    @Test public void freshAndLegacyModesDefaultToSystemShapedWithoutCopyingAdvancedOptions() {
        for (int legacy : new int[]{0,1}) {
            PreferenceTestContext context = new PreferenceTestContext();
            SharedPreferences controls = BridgeSettings.controls(context);
            controls.edit().putInt(BridgeSettings.STYLE,legacy)
                    .putBoolean("advanced_dynamic_background",false).putBoolean("advanced_motion",false)
                    .putInt("advanced_text_scale",160).apply();
            Map<String,Object> before = new LinkedHashMap<>(BridgeSettings.values(context));
            LyricAppearanceSettings settings = LyricAppearanceSettings.load(context);
            assertEquals(LyricAppearanceSettings.SYSTEM_SHAPED,settings.background);
            assertEquals(LyricAppearanceSettings.APPLE,settings.font);
            assertTrue(settings.systemShapedArtwork);
            assertFalse(settings.appleBackground);
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
        LyricAppearanceSettings.saveChoice(controls,LyricAppearanceSettings.BACKGROUND,LyricAppearanceSettings.APPLE);
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
        assertEquals(0,next.background);
        assertEquals(0,next.font);
    }

    @Test public void disabledModuleKeepsSelectionsButStopsBackground() {
        PreferenceTestContext context=new PreferenceTestContext();
        BridgeSettings.controls(context).edit().putBoolean(BridgeSettings.MASTER,false).apply();
        LyricAppearanceSettings settings=LyricAppearanceSettings.load(context);
        assertFalse(settings.appleBackground);
        assertEquals(LyricAppearanceSettings.SYSTEM_SHAPED,settings.background);
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

    @Test public void systemShapedBackgroundKeepsFontAndAppleAnimationChoicesIndependent() {
        PreferenceTestContext context = new PreferenceTestContext();
        SharedPreferences controls = BridgeSettings.controls(context);
        Map<String,Object> before = new LinkedHashMap<>(BridgeSettings.values(context));
        controls.edit().putBoolean(LyricAppearanceSettings.BACKGROUND_ANIMATION,false).apply();
        LyricAppearanceSettings.saveChoice(controls,LyricAppearanceSettings.BACKGROUND,LyricAppearanceSettings.SYSTEM_SHAPED);
        LyricAppearanceSettings settings = LyricAppearanceSettings.load(context);
        assertEquals(LyricAppearanceSettings.SYSTEM_SHAPED,settings.background);
        assertTrue(settings.systemShapedArtwork);
        assertFalse(settings.appleBackground);
        assertFalse(settings.backgroundAnimation);
        assertEquals(LyricAppearanceSettings.APPLE,settings.font);
        assertEquals(before,BridgeSettings.values(context));
        LyricAppearanceSettings.saveChoice(controls,LyricAppearanceSettings.BACKGROUND,LyricAppearanceSettings.APPLE);
        settings = LyricAppearanceSettings.load(context);
        assertFalse(settings.systemShapedArtwork);
        assertTrue(settings.appleBackground);
        assertFalse(settings.backgroundAnimation);
    }

    @Test public void systemShapedBackgroundSurvivesBackupAndSwitchReset() {
        PreferenceTestContext context = new PreferenceTestContext();
        BridgeSettings.initialize(context);
        LyricAppearanceSettings.saveChoice(BridgeSettings.controls(context),
                LyricAppearanceSettings.BACKGROUND,LyricAppearanceSettings.SYSTEM_SHAPED);
        PreferenceTestContext restored = new PreferenceTestContext();
        BridgeConfigBackupRepository.restoreAll(restored,BridgeConfigBackupRepository.exportAll(context));
        LyricAppearanceSettings settings = LyricAppearanceSettings.load(restored);
        assertEquals(LyricAppearanceSettings.SYSTEM_SHAPED,settings.background);
        assertTrue(settings.systemShapedArtwork);
        assertFalse(settings.appleBackground);
        BridgeSettings.restoreDefaults(restored);
        settings = LyricAppearanceSettings.load(restored);
        assertEquals(LyricAppearanceSettings.SYSTEM_SHAPED,settings.background);
        assertTrue(settings.systemShapedArtwork);
        assertFalse(settings.appleBackground);
    }

    @Test public void disabledModuleKeepsSystemShapedChoiceWithoutForcingArtwork() {
        PreferenceTestContext context = new PreferenceTestContext();
        SharedPreferences controls = BridgeSettings.controls(context);
        LyricAppearanceSettings.saveChoice(controls,LyricAppearanceSettings.BACKGROUND,LyricAppearanceSettings.SYSTEM_SHAPED);
        controls.edit().putBoolean(BridgeSettings.MASTER,false).apply();
        LyricAppearanceSettings settings = LyricAppearanceSettings.load(context);
        assertEquals(LyricAppearanceSettings.SYSTEM_SHAPED,settings.background);
        assertFalse(settings.systemShapedArtwork);
        assertFalse(settings.appleBackground);
        controls.edit().putBoolean(BridgeSettings.MASTER,true).apply();
        assertTrue(LyricAppearanceSettings.load(context).systemShapedArtwork);
    }

    @Test(expected = IllegalArgumentException.class) public void fontCannotSelectSystemShapedArtwork() {
        PreferenceTestContext context = new PreferenceTestContext();
        LyricAppearanceSettings.saveChoice(BridgeSettings.controls(context),
                LyricAppearanceSettings.FONT,LyricAppearanceSettings.SYSTEM_SHAPED);
    }

    @Test(expected = IllegalArgumentException.class) public void unknownBackgroundChoiceIsRejected() {
        PreferenceTestContext context = new PreferenceTestContext();
        LyricAppearanceSettings.saveChoice(BridgeSettings.controls(context),LyricAppearanceSettings.BACKGROUND,3);
    }

    @Test public void unknownStoredChoicesFallBackToTheirOwnPreset() {
        PreferenceTestContext context = new PreferenceTestContext();
        SharedPreferences controls = BridgeSettings.controls(context);
        for (int invalid : new int[]{-1,3,Integer.MAX_VALUE}) {
            controls.edit().putInt(LyricAppearanceSettings.BACKGROUND,invalid)
                    .putInt(LyricAppearanceSettings.FONT,LyricAppearanceSettings.SYSTEM_SHAPED).apply();
            LyricAppearanceSettings settings = LyricAppearanceSettings.load(context);
            assertEquals(LyricAppearanceSettings.SYSTEM_SHAPED,settings.background);
            assertEquals(LyricAppearanceSettings.APPLE,settings.font);
            assertFalse(settings.appleBackground);
            assertTrue(settings.systemShapedArtwork);
        }
    }
}
