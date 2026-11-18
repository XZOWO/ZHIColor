package com.naivety.zhicolor.bridge;

import android.content.Context;
import android.content.SharedPreferences;

/** Independent choices carried by the existing controls snapshot and backup namespace. */
public final class LyricAppearanceSettings {
    public static final String BACKGROUND = "lyric_background";
    public static final String BACKGROUND_ANIMATION = "lyric_background_animation";
    public static final String FONT = "lyric_font";
    public static final String POSITION = "appearance_position";
    public static final String ROMANIZATION = "lyric_romanization";
    public static final int SYSTEM = 0, APPLE = 1, SYSTEM_SHAPED = 2;
    public final int background, font, position;
    public final boolean appleBackground, systemShapedArtwork, applePosition, backgroundAnimation, romanization;

    private LyricAppearanceSettings() {
        background = SYSTEM_SHAPED;
        font = APPLE;
        position = APPLE;
        backgroundAnimation = true;
        appleBackground = false;
        systemShapedArtwork = true;
        applePosition = true;
        romanization = true;
    }

    static LyricAppearanceSettings defaults() {
        return new LyricAppearanceSettings();
    }

    LyricAppearanceSettings(SharedPreferences preferences) {
        background = backgroundChoice(preferences.getInt(BACKGROUND, SYSTEM_SHAPED));
        font = twoChoice(preferences.getInt(FONT, APPLE));
        position = twoChoice(preferences.getInt(POSITION, APPLE));
        backgroundAnimation = preferences.getBoolean(BACKGROUND_ANIMATION, true);
        boolean enabled = preferences.getBoolean(BridgeSettings.MASTER, true);
        appleBackground = enabled && background == APPLE;
        systemShapedArtwork = enabled && background == SYSTEM_SHAPED;
        applePosition = enabled && position == APPLE;
        romanization = preferences.getBoolean(ROMANIZATION,
                preferences.getBoolean("advanced_romanization", true));
    }

    private static int twoChoice(int value) { return value == SYSTEM ? SYSTEM : APPLE; }
    private static boolean isTwoChoiceKey(String key) {
        return FONT.equals(key) || POSITION.equals(key);
    }
    private static int backgroundChoice(int value) {
        return value == SYSTEM || value == APPLE ? value : SYSTEM_SHAPED;
    }

    public static LyricAppearanceSettings load(Context context) {
        return new LyricAppearanceSettings(BridgeSettings.controls(context));
    }

    public static void setChoice(Context context, String key, int value) {
        saveChoice(BridgeSettings.controls(context), key, value);
        BridgeSettings.sync(context);
    }

    static void saveChoice(SharedPreferences preferences, String key, int value) {
        if ((!BACKGROUND.equals(key) && !isTwoChoiceKey(key))
                || (value != SYSTEM && value != APPLE && !(BACKGROUND.equals(key) && value == SYSTEM_SHAPED)))
            throw new IllegalArgumentException("Unknown appearance choice");
        preferences.edit().putInt(key, value).apply();
    }

    public static void setBackgroundAnimation(Context context, boolean enabled) {
        BridgeSettings.controls(context).edit().putBoolean(BACKGROUND_ANIMATION, enabled).apply();
        BridgeSettings.sync(context);
    }

    public static void setRomanization(Context context, boolean enabled) {
        BridgeSettings.controls(context).edit().putBoolean(ROMANIZATION, enabled).apply();
        BridgeSettings.sync(context);
    }
}
