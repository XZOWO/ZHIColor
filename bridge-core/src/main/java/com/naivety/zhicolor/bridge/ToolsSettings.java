package com.naivety.zhicolor.bridge;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;

/** Independent tool preferences; lyric presets and their resets do not own this namespace. */
public final class ToolsSettings {
    public static final String PREFERENCES = "zhicolor_tools";
    public static final String HIDE_LOCKSCREEN_MUSIC_APP = "hide_lockscreen_music_app";
    public static final String HIDE_CONTROL_CENTER_MUSIC_APP = "hide_control_center_music_app";
    public static final String CHANGED = "com.naivety.zhicolor.action.TOOLS_SETTINGS_CHANGED";
    public static final String SNAPSHOT_METHOD = "tools_snapshot";
    public static final String REVISION = "revision";

    private ToolsSettings() { }

    public static boolean hideLockscreenMusicApp(Context context) {
        return preferences(context).getBoolean(HIDE_LOCKSCREEN_MUSIC_APP, false);
    }

    public static void setHideLockscreenMusicApp(Context context, boolean hidden) {
        setHidden(context, HIDE_LOCKSCREEN_MUSIC_APP, hidden);
    }

    public static boolean hideControlCenterMusicApp(Context context) {
        return preferences(context).getBoolean(HIDE_CONTROL_CENTER_MUSIC_APP, false);
    }

    public static void setHideControlCenterMusicApp(Context context, boolean hidden) {
        setHidden(context, HIDE_CONTROL_CENTER_MUSIC_APP, hidden);
    }

    private static void setHidden(Context context, String key, boolean hidden) {
        SharedPreferences preferences = preferences(context);
        long revision = Math.max(System.currentTimeMillis(), preferences.getLong(REVISION, 0L) + 1L);
        preferences.edit().putBoolean(key, hidden)
                .putLong(REVISION, revision).apply();
        context.sendBroadcast(new Intent(CHANGED).setPackage("com.android.systemui")
                .putExtras(snapshot(context)));
    }

    public static Bundle snapshot(Context context) {
        SharedPreferences preferences = preferences(context);
        Bundle snapshot = new Bundle();
        snapshot.putBoolean(HIDE_LOCKSCREEN_MUSIC_APP,
                preferences.getBoolean(HIDE_LOCKSCREEN_MUSIC_APP, false));
        snapshot.putBoolean(HIDE_CONTROL_CENTER_MUSIC_APP,
                preferences.getBoolean(HIDE_CONTROL_CENTER_MUSIC_APP, false));
        snapshot.putLong(REVISION, preferences.getLong(REVISION, 0L));
        return snapshot;
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }
}
