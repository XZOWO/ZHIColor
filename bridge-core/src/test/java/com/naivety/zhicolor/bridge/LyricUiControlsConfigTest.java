package com.naivety.zhicolor.bridge;

import android.content.SharedPreferences;

import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.*;

public final class LyricUiControlsConfigTest {
    @Test
    public void schemaThreeRetainsEveryExplicitAlignmentAndVisualSetting() {
        for (int alignment = 0; alignment <= 2; alignment++) {
            Map<String, Object> old = new HashMap<>();
            old.put(LyricUiConfigCodec.SCHEMA, 3);
            old.put(LyricUiConfigCodec.ALIGNMENT, alignment);
            old.put(LyricUiConfigCodec.INACTIVE_OPACITY, 39);
            old.put(LyricUiConfigCodec.INACTIVE_TRANSLATION_OPACITY, 83);
            old.put(LyricUiConfigCodec.INACTIVE_ROW_FADE_ENABLED, false);
            old.put(LyricUiConfigCodec.BLUR_ENABLED, true);
            LyricUiConfig migrated = LyricUiConfigCodec.decode(old, null, true);

            assertEquals(alignment, migrated.alignment);
            assertEquals(39, migrated.inactiveOpacityPercent);
            assertEquals(83, migrated.inactiveTranslationOpacityPercent);
            assertFalse(migrated.inactiveRowFadeEnabled);
            assertTrue(migrated.blurEnabled);
            assertEquals(LyricUiConfig.TRANSLATION_CURRENT_ONLY, migrated.translationDisplayMode);
            assertTrue(migrated.secondaryProgressEnabled);
        }
        Map<String, Object> oldWithoutAlignment = new HashMap<>();
        oldWithoutAlignment.put(LyricUiConfigCodec.SCHEMA, 3);
        assertEquals(LyricUiConfig.ALIGN_START,
                LyricUiConfigCodec.decode(oldWithoutAlignment, null, true).alignment);
        assertEquals(LyricUiConfig.ALIGN_DEFAULT, LyricUiConfig.defaults().alignment);
    }

    @Test
    public void translationAndSecondaryProgressRemainIndependentAcrossRoundTrip() {
        for (int displayMode = 0; displayMode <= 1; displayMode++) {
            for (boolean translation : new boolean[]{false, true}) {
                for (boolean secondary : new boolean[]{false, true}) {
                    LyricUiConfig source = LyricUiConfig.defaults().buildUpon()
                            .translationDisplayMode(displayMode)
                            .translationProgressEnabled(translation)
                            .secondaryProgressEnabled(secondary)
                            .build();
                    LyricUiConfig decoded = LyricUiConfigCodec.decode(
                            LyricUiConfigCodec.encode(source), null, false);
                    assertEquals(displayMode, decoded.translationDisplayMode);
                    assertEquals(translation, decoded.translationProgressEnabled);
                    assertEquals(secondary, decoded.secondaryProgressEnabled);
                    assertEquals(source, decoded);
                    assertEquals(source.hashCode(), decoded.hashCode());
                }
            }
        }
    }

    @Test
    public void partialUpdatesAndTranslationDefaultsKeepTheNewControls() {
        LyricUiConfig source = LyricUiConfig.defaults().buildUpon()
                .translationDisplayMode(LyricUiConfig.TRANSLATION_ALL_LINES)
                .secondaryProgressEnabled(false)
                .translationProgressEnabled(true)
                .build();
        Map<String, Object> partial = new HashMap<>();
        partial.put(LyricUiConfigCodec.SCHEMA, LyricUiConfig.SCHEMA_VERSION);
        partial.put(LyricUiConfigCodec.ACTIVE_OPACITY, 90);
        LyricUiConfig updated = LyricUiSettings.withGlobalTranslationDefault(
                LyricUiConfigCodec.decode(partial, source, false), false);

        assertEquals(LyricUiConfig.TRANSLATION_ALL_LINES, updated.translationDisplayMode);
        assertFalse(updated.secondaryProgressEnabled);
        assertTrue(updated.translationProgressEnabled);
        assertFalse(updated.defaultTranslationEnabled);
        assertEquals(90, updated.activeOpacityPercent);
    }

    @Test
    public void bothNewControlsInvalidateConfigIdentity() {
        LyricUiConfig source = LyricUiConfig.defaults();
        LyricUiConfig currentTranslation = source.buildUpon()
                .translationDisplayMode(LyricUiConfig.TRANSLATION_CURRENT_ONLY).build();
        LyricUiConfig noSecondaryProgress = source.buildUpon()
                .secondaryProgressEnabled(false).build();

        assertNotEquals(source, currentTranslation);
        assertNotEquals(source.hashCode(), currentTranslation.hashCode());
        assertNotEquals(source, noSecondaryProgress);
        assertNotEquals(source.hashCode(), noSecondaryProgress.hashCode());
    }

    @Test
    public void preferencesMigrationPersistsNewKeysWithoutChangingPlayerOverrides() {
        Map<String, Object> values = new HashMap<>();
        values.put(LyricUiConfigCodec.SCHEMA, 3);
        values.put(LyricUiConfigCodec.ALIGNMENT, LyricUiConfig.ALIGN_CENTER);
        values.put(LyricUiConfigCodec.TRANSLATION_PROGRESS, true);
        String translation = LyricUiSettings.translationDefaultKeyForPackage("com.tencent.qqmusic");
        String button = LyricUiSettings.translationButtonKeyForPackage("com.tencent.qqmusic");
        values.put(translation, false);
        values.put(button, false);

        LyricUiConfig migrated = LyricUiConfigRepository.load(preferences(values));

        assertEquals(LyricUiConfig.SCHEMA_VERSION, values.get(LyricUiConfigCodec.SCHEMA));
        assertEquals(LyricUiConfig.ALIGN_CENTER, migrated.alignment);
        assertEquals(0, values.get(LyricUiConfigCodec.TRANSLATION_DISPLAY_MODE));
        assertEquals(true, values.get(LyricUiConfigCodec.SECONDARY_PROGRESS));
        assertEquals(true, values.get(LyricUiConfigCodec.TRANSLATION_PROGRESS));
        assertEquals(false, values.get(translation));
        assertEquals(false, values.get(button));
    }

    @Test
    public void backupRoundTripKeepsNewControlsAndPerPlayerTranslationButtons() {
        LyricUiConfig source = LyricUiConfig.defaults().buildUpon()
                .translationDisplayMode(LyricUiConfig.TRANSLATION_ALL_LINES)
                .secondaryProgressEnabled(false)
                .translationProgressEnabled(true)
                .alignment(LyricUiConfig.ALIGN_DEFAULT)
                .build();
        Map<String, Object> main = new LinkedHashMap<>(LyricUiConfigCodec.encode(source));
        String translation = LyricUiSettings.translationDefaultKeyForPackage("com.tencent.qqmusic");
        String button = LyricUiSettings.translationButtonKeyForPackage("com.tencent.qqmusic");
        main.put(translation, false);
        main.put(button, true);
        Map<String, Map<String, ?>> domains = new LinkedHashMap<>();
        domains.put(LyricUiSettings.PREFERENCES_NAME, main);
        Map<String, Object> restored = new LinkedHashMap<>(BridgeConfigBackupCodec.decode(
                BridgeConfigBackupCodec.encode(domains)).namespace(LyricUiSettings.PREFERENCES_NAME));

        assertEquals(source, LyricUiConfigRepository.load(preferences(restored)));
        assertEquals(false, restored.get(translation));
        assertEquals(true, restored.get(button));
    }

    private static SharedPreferences preferences(Map<String, Object> values) {
        SharedPreferences.Editor editor = (SharedPreferences.Editor) Proxy.newProxyInstance(
                SharedPreferences.Editor.class.getClassLoader(),
                new Class<?>[]{SharedPreferences.Editor.class}, (proxy, method, args) -> {
                    String name = method.getName();
                    if (name.startsWith("put")) values.put((String) args[0], args[1]);
                    else if (name.equals("remove")) values.remove(args[0]);
                    else if (name.equals("clear")) values.clear();
                    else if (name.equals("apply")) return null;
                    else if (name.equals("commit")) return true;
                    return proxy;
                });
        return (SharedPreferences) Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),
                new Class<?>[]{SharedPreferences.class}, (proxy, method, args) -> {
                    String name = method.getName();
                    if (name.equals("getAll")) return new HashMap<>(values);
                    if (name.equals("contains")) return values.containsKey(args[0]);
                    if (name.equals("edit")) return editor;
                    if (name.startsWith("get")) return values.getOrDefault(args[0], args[1]);
                    return null;
                });
    }
}
