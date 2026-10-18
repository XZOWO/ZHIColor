package com.naivety.zhicolor.bridge;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import com.naivety.zhicolor.bridge.diagnostics.BridgeDebugConfig;

/** Reads, validates, restores and clears every Bridge-owned preference namespace. */
final class BridgeConfigBackupRepository {
    private static final String[] PREFERENCE_NAMES = {
            LyricUiSettings.PREFERENCES_NAME,
            BridgeDebugConfig.PREFS_NAME,
            BridgeSettings.CONTROL_PREFS,
            ToolsSettings.PREFERENCES
    };

    private BridgeConfigBackupRepository() {
    }

    static String exportAll(Context context) {
        return BridgeConfigBackupCodec.encode(snapshot(context));
    }

    static void restoreAll(Context context, String encoded) {
        BridgeConfigBackupCodec.Backup backup = BridgeConfigBackupCodec.decode(encoded);
        Set<String> allowed = new LinkedHashSet<>(Arrays.asList(PREFERENCE_NAMES));
        Set<String> provided = backup.namespaceNames();
        if (!provided.contains(LyricUiSettings.PREFERENCES_NAME)
                || !provided.contains(BridgeDebugConfig.PREFS_NAME)
                || !allowed.containsAll(provided)) {
            throw new IllegalArgumentException("Backup contains missing or unknown config domains");
        }
        Map<String, Map<String, ?>> previous = snapshot(context);
        try {
            // Old two-domain backups cannot describe controls/tools; preserve those domains.
            // Every included domain is an exact replacement, including explicitly empty maps.
            for (String name : provided) {
                if (!write(context, name, backup.namespace(name))) {
                    throw new IllegalStateException("Could not restore Bridge preferences");
                }
            }
        } catch (RuntimeException error) {
            for (String name : provided) write(context, name, previous.get(name));
            throw error;
        }
    }

    static void clearAll(Context context) {
        Map<String, Map<String, ?>> previous = snapshot(context, PREFERENCE_NAMES);
        try {
            for (String name : PREFERENCE_NAMES) {
                if (!context.getSharedPreferences(name, Context.MODE_PRIVATE)
                        .edit()
                        .clear()
                        .commit()) {
                    throw new IllegalStateException("Could not reset Bridge preferences");
                }
            }
        } catch (RuntimeException error) {
            for (String name : PREFERENCE_NAMES) write(context, name, previous.get(name));
            throw error;
        }
    }

    private static Map<String, Map<String, ?>> snapshot(Context context) {
        return snapshot(context, PREFERENCE_NAMES);
    }

    private static Map<String, Map<String, ?>> snapshot(Context context, String[] preferenceNames) {
        LinkedHashMap<String, Map<String, ?>> namespaces = new LinkedHashMap<>();
        for (String name : preferenceNames) {
            LinkedHashMap<String, Object> values = new LinkedHashMap<>();
            for (Map.Entry<String, ?> entry : context.getSharedPreferences(
                    name,
                    Context.MODE_PRIVATE).getAll().entrySet()) {
                Object value = entry.getValue();
                if (value instanceof Set) {
                    value = new LinkedHashSet<>((Set<?>) value);
                }
                values.put(entry.getKey(), value);
            }
            namespaces.put(name, values);
        }
        return namespaces;
    }

    @SuppressWarnings("unchecked")
    private static boolean write(Context context, String name, Map<String, ?> values) {
        SharedPreferences.Editor editor = context.getSharedPreferences(
                name,
                Context.MODE_PRIVATE).edit().clear();
        if (values != null) {
            for (Map.Entry<String, ?> entry : values.entrySet()) {
                Object value = entry.getValue();
                if (value instanceof Boolean) {
                    editor.putBoolean(entry.getKey(), (Boolean) value);
                } else if (value instanceof Integer) {
                    editor.putInt(entry.getKey(), (Integer) value);
                } else if (value instanceof Long) {
                    editor.putLong(entry.getKey(), (Long) value);
                } else if (value instanceof Float) {
                    editor.putFloat(entry.getKey(), (Float) value);
                } else if (value instanceof String) {
                    editor.putString(entry.getKey(), (String) value);
                } else if (value instanceof Set) {
                    editor.putStringSet(
                            entry.getKey(),
                            new LinkedHashSet<>((Set<String>) value));
                } else {
                    throw new IllegalArgumentException("Unsupported Bridge preference type");
                }
            }
        }
        return editor.commit();
    }
}
