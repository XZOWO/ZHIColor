package com.naivety.zhicolor.bridge;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;

import com.naivety.zhicolor.bridge.diagnostics.BridgeDebugConfig;

import org.junit.Test;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import javax.xml.parsers.DocumentBuilderFactory;

import static org.junit.Assert.*;

public final class PhoneDefaultsTest {
    @Test
    public void everyLyricDefaultMatchesPhoneExceptRequestedSchemaChanges() throws Exception {
        Map<String, Object> expected = lyricFixture();
        Map<String, Object> actual = new LinkedHashMap<>(
                LyricUiConfigCodec.encode(LyricUiConfig.defaults()));
        actual.remove(LyricUiConfigCodec.SCHEMA);

        assertEquals(39, expected.size());
        assertEquals(expected, actual);
    }

    @Test
    public void bridgeDefaultsMatchAppsProvidersAndMasterFromPhone() throws Exception {
        MemoryContext context = new MemoryContext();
        BridgeSettings.initialize(context);

        assertEquals(6, fixture("zhicolor_bridge_controls.xml").size());
        assertEquals(fixture("zhicolor_bridge_controls.xml"),
                context.values(BridgeSettings.CONTROL_PREFS));
        assertTrue(BridgeSettings.enabled(context));
        assertTrue(BridgeSettings.playerEnabled(context, "com.apple.android.music"));
    }

    @Test
    public void emptyPreferencesAndRestoreDefaultsReadTheSamePhoneValues() throws Exception {
        MemoryContext context = new MemoryContext();
        BridgeSettings.initialize(context);
        Map<String, Object> installed = new LinkedHashMap<>(BridgeSettings.values(context));
        Map<String, Object> controls = new LinkedHashMap<>(
                context.values(BridgeSettings.CONTROL_PREFS));
        context.values(LyricUiSettings.PREFERENCES_NAME).put(
                LyricUiConfigCodec.MAIN_FONT_SIZE, 190);
        context.values(BridgeSettings.CONTROL_PREFS).put(BridgeSettings.MASTER, false);
        context.values(BridgeSettings.CONTROL_PREFS).put(
                BridgeSettings.APPS, Collections.emptySet());
        context.values(BridgeSettings.CONTROL_PREFS).put(
                "provider.com.apple.android.music", "custom.provider");
        context.values(BridgeDebugConfig.PREFS_NAME).put(BridgeDebugConfig.KEY_MASTER, true);

        BridgeSettings.restoreDefaults(context);

        assertEquals(installed, BridgeSettings.values(context));
        assertEquals(controls, context.values(BridgeSettings.CONTROL_PREFS));
        Map<String, Object> expectedDebug = fixture("zhicolor_lyrics_debug.xml");
        expectedDebug.remove("debug_schema");
        expectedDebug.remove("debug_revision");
        assertEquals(8, expectedDebug.size());
        assertEquals(expectedDebug, BridgeSettings.debug(context));
        Map<String, Object> actual = new LinkedHashMap<>(BridgeSettings.values(context));
        actual.remove(LyricUiConfigCodec.SCHEMA);
        assertEquals(lyricFixture(), actual);
    }

    @Test
    public void initializationPreservesExplicitPreferencesAndEmptyAppSelection() {
        MemoryContext context = new MemoryContext();
        Map<String, Object> lyrics = context.values(LyricUiSettings.PREFERENCES_NAME);
        lyrics.put(LyricUiConfigCodec.SCHEMA, LyricUiConfig.SCHEMA_VERSION);
        lyrics.put(LyricUiConfigCodec.MAIN_FONT_SIZE, 190);
        lyrics.put(LyricUiConfigCodec.LINE_TIMED_PROGRESS, false);
        lyrics.put(LyricUiConfigCodec.CHAR_LIFT_ENABLED, false);
        lyrics.put(LyricUiConfigCodec.DEFAULT_TRANSLATION, true);
        lyrics.put(LyricUiConfigCodec.TRANSLATION_DISPLAY_MODE,
                LyricUiConfig.TRANSLATION_CURRENT_ONLY);
        Map<String, Object> controls = context.values(BridgeSettings.CONTROL_PREFS);
        controls.put(BridgeSettings.MASTER, false);
        controls.put(BridgeSettings.STYLE, 1);
        controls.put(BridgeSettings.APPS, Collections.emptySet());
        controls.put("provider.com.apple.android.music", "custom.provider");

        BridgeSettings.initialize(context);
        BridgeSettings.initialize(context);

        LyricUiConfig config = LyricUiConfigRepository.load(BridgeSettings.prefs(context));
        assertEquals(190, config.mainFontTenthsSp);
        assertFalse(config.lineTimedProgressEnabled);
        assertFalse(config.charLiftEnabled);
        assertTrue(config.defaultTranslationEnabled);
        assertEquals(LyricUiConfig.TRANSLATION_CURRENT_ONLY, config.translationDisplayMode);
        assertEquals(0, config.lineSpacingTenthsDp);
        assertFalse(BridgeSettings.enabled(context));
        assertEquals(1, BridgeSettings.style(context));
        assertFalse(BridgeSettings.playerEnabled(context, "com.apple.android.music"));
        assertEquals("custom.provider", controls.get("provider.com.apple.android.music"));
    }

    @Test
    public void missingCurrentSchemaFieldsUseCurrentDefaultsWithoutLegacySpacing() {
        MemoryContext context = new MemoryContext();
        context.values(LyricUiSettings.PREFERENCES_NAME).put(
                LyricUiConfigCodec.SCHEMA, LyricUiConfig.SCHEMA_VERSION);

        assertEquals(LyricUiConfig.defaults(),
                LyricUiConfigRepository.load(BridgeSettings.prefs(context)));
        assertEquals(LyricUiConfig.defaults(), LyricUiConfigCodec.decode(
                Collections.singletonMap(LyricUiConfigCodec.SCHEMA, LyricUiConfig.SCHEMA_VERSION),
                null, true));
    }

    @Test
    public void failedResetRestoresControlsAlongsideLyricsAndDebug() {
        MemoryContext context = new MemoryContext();
        BridgeSettings.initialize(context);
        context.values(BridgeDebugConfig.PREFS_NAME).put(BridgeDebugConfig.KEY_MASTER, true);
        context.values(ToolsSettings.PREFERENCES).put(ToolsSettings.HIDE_LOCKSCREEN_MUSIC_APP, true);
        context.values(ToolsSettings.PREFERENCES).put(ToolsSettings.REVISION, 12345L);
        Map<String, Map<String, Object>> before = context.snapshot();
        context.failNextCommit = BridgeSettings.CONTROL_PREFS;

        try {
            BridgeSettings.restoreDefaults(context);
            fail("reset must report a failed preference commit");
        } catch (IllegalStateException expected) {
            assertEquals(before, context.snapshot());
        }
    }

    private static Map<String, Object> lyricFixture() throws Exception {
        Map<String, Object> expected = fixture("zhicolor_lyric_ui.xml");
        expected.remove(LyricUiConfigCodec.SCHEMA);
        expected.remove("line_spacing_zero_default_migrated");
        assertEquals(Boolean.TRUE, expected.put(LyricUiConfigCodec.DEFAULT_TRANSLATION, false));
        // The removed pan control has no current schema field. The independent
        // secondary size starts at the phone's previous shared text ratio.
        assertEquals(Boolean.TRUE, expected.remove("passive_vertical_pan_enabled"));
        assertNull(expected.put(LyricUiConfigCodec.SECONDARY_FONT_RATIO,
                expected.get(LyricUiConfigCodec.TRANSLATION_FONT_RATIO)));
        return expected;
    }

    private static Map<String, Object> fixture(String name) throws Exception {
        Map<String, Object> values = new LinkedHashMap<>();
        try (InputStream stream = PhoneDefaultsTest.class.getResourceAsStream(
                "/phone-defaults/" + name)) {
            assertNotNull(name, stream);
            NodeList children = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                    .parse(stream).getDocumentElement().getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                Node node = children.item(i);
                if (!(node instanceof Element)) continue;
                Element entry = (Element) node;
                Object value;
                switch (entry.getTagName()) {
                    case "boolean": value = Boolean.parseBoolean(entry.getAttribute("value")); break;
                    case "int": value = Integer.parseInt(entry.getAttribute("value")); break;
                    case "long": value = Long.parseLong(entry.getAttribute("value")); break;
                    case "set":
                        Set<String> items = new LinkedHashSet<>();
                        NodeList strings = entry.getElementsByTagName("string");
                        for (int j = 0; j < strings.getLength(); j++) items.add(strings.item(j).getTextContent());
                        value = items;
                        break;
                    default: value = entry.getTextContent();
                }
                values.put(entry.getAttribute("name"), value);
            }
        }
        return values;
    }

    private static final class MemoryContext extends ContextWrapper {
        final Map<String, Map<String, Object>> domains = new LinkedHashMap<>();
        String failNextCommit;

        MemoryContext() { super(null); }

        Map<String, Object> values(String name) {
            return domains.computeIfAbsent(name, ignored -> new LinkedHashMap<>());
        }

        Map<String, Map<String, Object>> snapshot() {
            Map<String, Map<String, Object>> result = new LinkedHashMap<>();
            for (Map.Entry<String, Map<String, Object>> entry : domains.entrySet()) {
                result.put(entry.getKey(), new LinkedHashMap<>(entry.getValue()));
            }
            return result;
        }

        @Override public SharedPreferences getSharedPreferences(String name, int mode) {
            Map<String, Object> values = values(name);
            return (SharedPreferences) Proxy.newProxyInstance(
                    SharedPreferences.class.getClassLoader(), new Class<?>[]{SharedPreferences.class},
                    (proxy, method, args) -> {
                        String operation = method.getName();
                        if (operation.equals("getAll")) return new LinkedHashMap<>(values);
                        if (operation.equals("contains")) return values.containsKey(args[0]);
                        if (operation.equals("edit")) return editor(name, values);
                        if (operation.startsWith("get")) return values.getOrDefault(args[0], args[1]);
                        return null;
                    });
        }

        private SharedPreferences.Editor editor(String namespace, Map<String, Object> values) {
            Map<String, Object> pending = new LinkedHashMap<>();
            Set<String> removed = new LinkedHashSet<>();
            boolean[] clear = {false};
            return (SharedPreferences.Editor) Proxy.newProxyInstance(
                    SharedPreferences.Editor.class.getClassLoader(),
                    new Class<?>[]{SharedPreferences.Editor.class}, (proxy, method, args) -> {
                        String operation = method.getName();
                        if (operation.startsWith("put")) {
                            Object value = args[1] instanceof Set
                                    ? new LinkedHashSet<>((Set<?>) args[1]) : args[1];
                            pending.put((String) args[0], value);
                            removed.remove(args[0]);
                        } else if (operation.equals("remove")) {
                            pending.remove(args[0]);
                            removed.add((String) args[0]);
                        } else if (operation.equals("clear")) {
                            clear[0] = true;
                        } else if (operation.equals("apply") || operation.equals("commit")) {
                            if (clear[0]) values.clear();
                            for (String key : removed) values.remove(key);
                            values.putAll(pending);
                            if (operation.equals("apply")) return null;
                            if (namespace.equals(failNextCommit)) {
                                failNextCommit = null;
                                return false;
                            }
                            return true;
                        }
                        return proxy;
                    });
        }
    }
}
