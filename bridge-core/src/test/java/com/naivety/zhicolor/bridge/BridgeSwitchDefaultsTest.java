package com.naivety.zhicolor.bridge;

import android.content.SharedPreferences;
import com.naivety.zhicolor.bridge.diagnostics.BridgeDebugConfig;
import org.json.JSONObject;
import org.junit.Test;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import static org.junit.Assert.*;

public final class BridgeSwitchDefaultsTest {
    @Test public void resetRestoresSwitchPresetsAndPreservesEveryNonSwitchSetting() throws Exception {
        PreferenceTestContext context=new PreferenceTestContext();
        BridgeSettings.initialize(context);
        SharedPreferences preferences=BridgeSettings.prefs(context);
        LyricUiConfig custom=LyricUiConfig.defaults().buildUpon()
                .charLiftEnabled(false).charLiftStrengthPercent(175)
                .wordEmphasisEnabled(false).chineseTailEmphasisEnabled(false)
                .lineTimedProgressEnabled(true).glowEnabled(false).glowIntensityPercent(87)
                .mainFontTenthsSp(260).primaryColor("#12ABCD").glowColor("#345678")
                .motionMode(LyricUiConfig.MOTION_REDUCED).alignment(LyricUiConfig.ALIGN_END)
                .lineSpacingTenthsDp(35).screenTimeoutSeconds(57).build();
        LyricUiConfigRepository.save(preferences,custom);
        Map<String,Object> main=context.values(LyricUiSettings.PREFERENCES_NAME);
        main.put("future_saved_text","retain");
        main.put(LyricUiSettings.KEY_DEFAULT_TRANSLATION_BUTTON,false);
        main.put(LyricUiSettings.translationDefaultKeyForPackage("com.example.player"),true);
        main.put(LyricUiSettings.translationButtonKeyForPackage("com.example.player"),false);
        String cleanup="{\"schema\":1,\"copyright\":true,\"production\":true,\"titleArtist\":true,\"learned\":[{\"type\":\"exact\",\"value\":\"Credits\"}],\"tracks\":[{\"track\":\"track-key\",\"first\":\"first-line\"}],\"future\":\"retain\"}";
        main.put(LyricContentCleanupRepository.PREFERENCE_KEY,cleanup);
        Map<String,Object> controls=context.values(BridgeSettings.CONTROL_PREFS);
        controls.put(BridgeSettings.MASTER,false);
        controls.put(BridgeSettings.APPS,Collections.singleton("com.example.player"));
        controls.put(LyricAppearanceSettings.BACKGROUND,LyricAppearanceSettings.APPLE);
        controls.put(LyricAppearanceSettings.FONT,LyricAppearanceSettings.SYSTEM);
        controls.put(LyricAppearanceSettings.BACKGROUND_ANIMATION,false);
        controls.put(LyricAppearanceSettings.ROMANIZATION,false);
        controls.put("provider.com.apple.android.music","custom.provider");
        controls.put("future_control_value",73);
        context.values(BridgeDebugConfig.PREFS_NAME).put(BridgeDebugConfig.KEY_MASTER,true);
        context.values(BridgeDebugConfig.PREFS_NAME).put("debug_revision",42L);
        Map<String,Object> tools=context.values(ToolsSettings.PREFERENCES);
        tools.put(ToolsSettings.HIDE_LOCKSCREEN_MUSIC_APP,true);
        tools.put(ToolsSettings.HIDE_CONTROL_CENTER_MUSIC_APP,true);
        tools.put(ToolsSettings.REVISION,123L);
        tools.put("future_tool_value","retain");

        BridgeSettings.restoreDefaults(context);

        Map<String,Object> actual=BridgeSettings.values(context);
        Map<String,Object> defaults=LyricUiConfigCodec.encode(LyricUiConfig.defaults());
        for(Map.Entry<String,Object> entry:LyricUiConfigCodec.encode(custom).entrySet()) {
            Object expected=entry.getValue() instanceof Boolean?defaults.get(entry.getKey()):entry.getValue();
            assertEquals(entry.getKey(),expected,actual.get(entry.getKey()));
        }
        assertTrue(BridgeSettings.enabled(context));
        assertTrue(BridgeSettings.playerEnabled(context,"com.apple.android.music"));
        assertFalse(BridgeSettings.playerEnabled(context,"com.example.player"));
        assertEquals(LyricAppearanceSettings.APPLE,controls.get(LyricAppearanceSettings.BACKGROUND));
        assertEquals(LyricAppearanceSettings.SYSTEM,controls.get(LyricAppearanceSettings.FONT));
        assertEquals("custom.provider",controls.get("provider.com.apple.android.music"));
        assertEquals(73,controls.get("future_control_value"));
        assertEquals(Boolean.TRUE,controls.get(LyricAppearanceSettings.BACKGROUND_ANIMATION));
        assertEquals(Boolean.TRUE,controls.get(LyricAppearanceSettings.ROMANIZATION));
        assertEquals("retain",main.get("future_saved_text"));
        // Player translation modes are three-way choices, not switches owned by this reset.
        assertEquals(Boolean.TRUE,main.get(LyricUiSettings.translationDefaultKeyForPackage("com.example.player")));
        assertEquals(Boolean.FALSE,main.get(LyricUiSettings.translationButtonKeyForPackage("com.example.player")));
        assertTrue(BridgeSettings.defaultTranslationButton(context));
        JSONObject resetCleanup=new JSONObject((String)main.get(LyricContentCleanupRepository.PREFERENCE_KEY));
        JSONObject originalCleanup=new JSONObject(cleanup);
        assertFalse(resetCleanup.getBoolean("copyright"));
        assertFalse(resetCleanup.getBoolean("production"));
        assertFalse(resetCleanup.getBoolean("titleArtist"));
        assertEquals(originalCleanup.getJSONArray("learned").toString(),resetCleanup.getJSONArray("learned").toString());
        assertEquals(originalCleanup.getJSONArray("tracks").toString(),resetCleanup.getJSONArray("tracks").toString());
        assertEquals("retain",resetCleanup.getString("future"));
        assertFalse(BridgeSettings.debug(context).get(BridgeDebugConfig.KEY_MASTER));
        assertEquals(42L,context.values(BridgeDebugConfig.PREFS_NAME).get("debug_revision"));
        assertFalse(ToolsSettings.hideLockscreenMusicApp(context));
        assertFalse(ToolsSettings.hideControlCenterMusicApp(context));
        assertEquals(123L,tools.get(ToolsSettings.REVISION));
        assertEquals("retain",tools.get("future_tool_value"));
    }

    @Test public void editingOtherParametersDoesNotReenableAnExplicitlyDisabledGlow() {
        PreferenceTestContext context=new PreferenceTestContext();
        SharedPreferences preferences=BridgeSettings.prefs(context);
        Map<String,Object> values=new LinkedHashMap<>(BridgeSettings.values(context));
        values.put(LyricUiConfigCodec.GLOW_ENABLED,false);
        values.put(LyricUiConfigCodec.GLOW_INTENSITY,87);
        BridgeSettings.save(preferences,values);
        LyricUiConfig stored=LyricUiConfigRepository.load(preferences);
        assertFalse(stored.glowEnabled);
        assertEquals(87,stored.glowIntensityPercent);
        values.put(LyricUiConfigCodec.MAIN_FONT_SIZE,250);
        BridgeSettings.save(preferences,values);
        assertFalse(LyricUiConfigRepository.load(preferences).glowEnabled);
        BridgeSettings.save(preferences,Collections.singletonMap(LyricUiConfigCodec.GLOW_INTENSITY,65));
        assertFalse(LyricUiConfigRepository.load(preferences).glowEnabled);
        assertEquals(65,LyricUiConfigRepository.load(preferences).glowIntensityPercent);
        BridgeSettings.save(preferences,Collections.singletonMap(LyricUiConfigCodec.GLOW_ENABLED,true));
        assertTrue(LyricUiConfigRepository.load(preferences).glowEnabled);
        assertEquals(65,LyricUiConfigRepository.load(preferences).glowIntensityPercent);
    }

    @Test public void resetIsIdempotentAndDoesNotTurnZeroIntensityIntoAnotherValue() {
        PreferenceTestContext context=new PreferenceTestContext();
        BridgeSettings.initialize(context);
        LyricUiConfigRepository.save(BridgeSettings.prefs(context),LyricUiConfig.defaults().buildUpon()
                .glowEnabled(false).glowIntensityPercent(0).build());
        BridgeSettings.restoreDefaults(context);
        String once=BridgeConfigBackupRepository.exportAll(context);
        BridgeSettings.restoreDefaults(context);
        assertEquals(once,BridgeConfigBackupRepository.exportAll(context));
        LyricUiConfig config=LyricUiConfigRepository.load(BridgeSettings.prefs(context));
        assertTrue(config.glowEnabled);
        assertEquals(0,config.glowIntensityPercent);
        assertFalse(config.lineTimedProgressEnabled);
    }
}
