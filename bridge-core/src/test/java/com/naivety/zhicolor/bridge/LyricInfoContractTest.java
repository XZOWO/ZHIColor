package com.naivety.zhicolor.bridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

import org.json.JSONObject;
import org.json.JSONArray;
import org.junit.Test;

public final class LyricInfoContractTest {
    @Test public void structuredSimultaneousMainRowsSurviveOfficialNormalization() throws Exception {
        JSONObject object = new JSONObject();
        object.put("lyric", "[00:01.000]Voice A\n[00:01.000]Voice B\n[00:01.000]翻译\n[00:05.000]Next");
        object.put("lineMetadata", new JSONArray("[{\"begin\":1000,\"end\":4000,\"text\":\"Voice A\"},"
                + "{\"begin\":1000,\"end\":4500,\"text\":\"Voice B\"}]"));
        String lyric = LyricInfoContract.normalizeOfficialLyricInfo(object.toString()).payload.lyric;
        assertTrue(lyric.contains("[00:01.000]Voice A\n[00:01.000]Voice B"));
        assertFalse(lyric.contains("翻译"));
    }

    @Test public void ordinaryTranslationRowsKeepSinglePrimaryNormalization() throws Exception {
        JSONObject object = new JSONObject();
        object.put("lyric", "[00:01.000]Voice A\n[00:01.000]翻译\n[00:05.000]Next");
        String lyric = LyricInfoContract.normalizeOfficialLyricInfo(object.toString()).payload.lyric;
        assertTrue(lyric.contains("Voice A"));
        assertFalse(lyric.contains("翻译"));
    }

    @Test public void unverifiedOrMismatchedMainRowsDoNotDisableLaneFiltering() throws Exception {
        JSONObject object = new JSONObject();
        object.put("lyric", "[00:01.000]Voice A\n[00:01.000]翻译\n[00:05.000]Next");
        object.put("lineMetadata", new JSONArray("[{\"begin\":1000,\"end\":4000,\"text\":\"Voice A\"},"
                + "{\"begin\":1000,\"end\":1000,\"text\":\"翻译\"}]"));
        assertFalse(LyricInfoContract.normalizeOfficialLyricInfo(object.toString()).payload.lyric.contains("翻译"));
        object.put("lineMetadata", new JSONArray("[{\"begin\":1000,\"end\":4000,\"text\":\"Voice A\"},"
                + "{\"begin\":1000,\"end\":5000,\"text\":\"Missing singer\"}]"));
        assertFalse(LyricInfoContract.normalizeOfficialLyricInfo(object.toString()).payload.lyric.contains("翻译"));
    }

    @Test
    public void replacingTranslationRemovesLegacyAliases() throws Exception {
        JSONObject object = new JSONObject();
        object.put("lyric", "[00:01.000]Hello");
        object.put("translatedLyric", "[00:01.000]旧翻译");

        LyricInfoContract.replaceTranslationLyric(
                object,
                "[00:01.000]新翻译");

        LyricInfoContract.Payload payload = LyricInfoContract.parse(object.toString());
        assertEquals("[00:01.000]新翻译", payload.translationLyric);
        assertEquals(false, object.has("translatedLyric"));
    }
}
