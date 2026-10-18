package com.naivety.zhicolor.bridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.naivety.zhicolor.bridge.render.WordLine;
import com.naivety.zhicolor.bridge.render.WordLyricModel;

/**
 * Behavior coverage for the Phase 6 slice 5 native lyric payload assembler:
 * inline word-timed parsing, official display aliases, indexed supplemental
 * translation merging, nearby-translation propagation, and the lyrics-core
 * fallback for line-timed payloads.
 */
public final class NativeLyricModelAssemblerTest {
    @Test
    public void asynchronousVocalTranslationReplacesOnlyItsOwnLane() throws Exception {
        String raw = "[00:01.000]lead\n";
        org.json.JSONObject vocal = new org.json.JSONObject().put("text", "echo")
                .put("words", timedWords(4000L, "echo"));
        org.json.JSONObject row = new org.json.JSONObject().put("begin", 1000L).put("text", "lead")
                .put("translation", "主句翻译").put("secondary", vocal);
        org.json.JSONArray rows = new org.json.JSONArray().put(row);
        String before = rows.toString();
        WordLine first = assembler().assemble(raw, true, raw, true, "", before).model.lines.get(0);
        assertEquals("", first.secondaryLine.translation);
        vocal.put("translation", "副唱翻译");
        String after = rows.toString();
        org.junit.Assert.assertNotEquals(before, after);
        WordLine second = assembler().assemble(raw, true, raw, true, "", after).model.lines.get(0);
        assertEquals("副唱翻译", second.secondaryLine.translation);
        assertNull(second.secondaryLine.translationLine);
        assertEquals("主句翻译", second.translation);
        assertEquals("", first.secondaryLine.translation);
    }
    @Test
    public void structuredEnglishKeepsOriginalSeparatorsAndOwnVocalTranslation() throws Exception {
        org.json.JSONObject main = new org.json.JSONObject().put("begin", 1000L).put("end", 8000L)
                .put("text", "He  did it, didn't he?").put("translation", "主句翻译")
                .put("words", timedWords(1000L, "He", "did", "it", "didn't", "he"))
                .put("secondary", new org.json.JSONObject().put("text", "Don't stop!")
                        .put("words", timedWords(4000L, "Don", "t", "stop"))
                        .put("translation", "不要 停！")
                        .put("translationWords", timedWords(6500L, "不要", "停")));
        String raw = "[00:01.000]He  did it, didn't he?\n";
        WordLine line = assembler().assemble(raw, true, raw, true, "",
                new org.json.JSONArray().put(main).toString()).model.lines.get(0);
        assertEquals("He  did it, didn't he?", line.text);
        assertEquals("did", line.text.substring(line.words.get(1).start, line.words.get(1).end));
        assertEquals("主句翻译", line.translation);
        WordLine vocal = line.secondaryLine;
        assertNotNull(vocal);
        assertEquals("Don't stop!", vocal.text);
        assertEquals("不要 停！", vocal.translation);
        assertNotNull(vocal.translationLine);
        assertEquals("不要 停！", vocal.translationLine.text);
        assertEquals(6500L, vocal.translationLine.firstProgressStartMillis());
        assertEquals(6700L, vocal.translationLine.wordEndMillis(0));
        assertEquals(0f, vocal.translationLine.wordProgress(0, 5000L), 0f);
        assertTrue(line.explicitEndTimeProvided);
        assertTrue(vocal.explicitEndTimeProvided);
    }

    @Test
    public void missingVocalTranslationNeverCopiesMainTranslationOrClock() throws Exception {
        String raw = "[00:01.000]lead\n";
        org.json.JSONObject row = new org.json.JSONObject().put("begin", 1000L).put("text", "lead")
                .put("translation", "主句翻译")
                .put("secondary", new org.json.JSONObject().put("text", "echo")
                        .put("words", timedWords(4000L, "echo")));
        WordLine main = assembler().assemble(raw, true, raw, true, "",
                new org.json.JSONArray().put(row).toString()).model.lines.get(0);
        assertEquals("主句翻译", main.translation);
        assertEquals("", main.secondaryLine.translation);
        assertNull(main.secondaryLine.translationLine);
    }

    @Test
    public void explicitEndsDistinguishEnhancedClockFromInferredPlainLrc() {
        String plain = "[00:01.000]hello\n[00:05.000]next\n";
        WordLine inferred = assembler().assemble(plain, true, plain, true, "").model.lines.get(0);
        org.junit.Assert.assertFalse(inferred.explicitEndTimeProvided);
        String enhanced = "[00:01.000]<00:01.000>hel<00:02.000>lo<00:06.000>\n";
        WordLine explicit = assembler().assemble(enhanced, true, enhanced, true, "").model.lines.get(0);
        assertTrue(explicit.explicitEndTimeProvided);
    }

    private static org.json.JSONArray timedWords(long begin, String... words) throws Exception {
        org.json.JSONArray result = new org.json.JSONArray();
        for (int index = 0; index < words.length; index++) {
            result.put(new org.json.JSONObject().put("begin", begin + index * 500L)
                    .put("end", begin + index * 500L + 200L).put("text", words[index]));
        }
        return result;
    }
    @Test
    public void structuredVocalsRetainOwnClockAndRealTranslationTogether() throws Exception {
        String raw = "[00:01.000]lead\n[00:08.000]next\n";
        org.json.JSONObject vocal = new org.json.JSONObject().put("text", "echo voice")
                .put("begin", 3500L).put("end", 4700L).put("words", new org.json.JSONArray()
                        .put(new org.json.JSONObject().put("begin", 3500L).put("end", 3700L).put("text", "echo "))
                        .put(new org.json.JSONObject().put("begin", 4500L).put("end", 4700L).put("text", "voice")));
        org.json.JSONArray metadata = new org.json.JSONArray().put(new org.json.JSONObject()
                .put("begin", 1000L).put("text", "lead").put("translation", "真正的翻译")
                .put("alignment", 2).put("alignmentProvided", true).put("secondary", vocal));
        WordLine lead = assembler().assemble(raw, true, raw, true,
                "[00:01.000]真正的翻译\n", metadata.toString()).model.lines.get(0);
        assertEquals("真正的翻译", lead.translation);
        assertEquals(2, lead.sourceAlignment);
        assertTrue(lead.sourceAlignmentProvided);
        WordLine secondary = lead.secondaryLine;
        assertNotNull(secondary);
        assertEquals("echo voice", secondary.text);
        assertEquals(com.naivety.zhicolor.bridge.render.LyricTimingMode.WORD_TIMED, secondary.timingMode);
        assertEquals(3500L, secondary.firstProgressStartMillis());
        assertEquals(-1, secondary.findWordIndex(2000L));
        assertEquals(0f, secondary.wordProgress(0, 3000L), 0f);
        assertEquals(0.5f, secondary.wordProgress(0, 3600L), 0.001f);
        assertEquals(3700L, secondary.wordEndMillis(0));
        assertEquals(1f, secondary.wordProgress(0, 4000L), 0f);
        assertEquals(0f, secondary.wordProgress(1, 4000L), 0f);
        assertEquals(4700L, secondary.wordEndMillis(1));
    }

    @Test
    public void staticVocalDoesNotBorrowMainWordTiming() throws Exception {
        String raw = "[00:01.000]<00:01.000>lead<00:02.000>voice<00:03.000>\n";
        org.json.JSONArray metadata = new org.json.JSONArray().put(new org.json.JSONObject()
                .put("begin", 1000L).put("text", "leadvoice")
                .put("secondary", new org.json.JSONObject().put("text", "static vocal")));
        WordLine lead = assembler().assemble(raw, true, raw, true, "", metadata.toString()).model.lines.get(0);
        assertNotNull(lead.secondaryLine);
        assertTrue(lead.secondaryLine.words.isEmpty());
        assertEquals(com.naivety.zhicolor.bridge.render.LyricTimingMode.LINE_TIMED,
                lead.secondaryLine.timingMode);
        assertEquals(-1, lead.secondaryLine.findWordIndex(2000L));
        assertEquals("", lead.translation);
        assertEquals(1, lead.sourceAlignment);
        org.junit.Assert.assertFalse(lead.sourceAlignmentProvided);
    }

    @Test
    public void completeMetadataPreservesSimultaneousMainsAndTextMatchedDirections() throws Exception {
        String raw = "[00:01.000]one\n[00:01.000]two\n";
        org.json.JSONArray metadata = new org.json.JSONArray()
                .put(structuredRow("two", 2, "译二"))
                .put(structuredRow("one", 0, "译一"));
        WordLyricModel model = assembler().assemble(raw, true, raw, true, "", metadata.toString()).model;
        assertEquals(2, model.lines.size());
        WordLine two = model.lines.get(0);
        WordLine one = model.lines.get(1);
        assertEquals("two", two.text);
        assertEquals("one", one.text);
        assertEquals(2, two.sourceAlignment);
        assertEquals(0, one.sourceAlignment);
        assertEquals("译二", two.translation);
        assertEquals("译一", one.translation);
        assertEquals(2, model.officialLines.size());
        org.junit.Assert.assertSame(one, model.officialLines.get(0));
        org.junit.Assert.assertSame(two, model.officialLines.get(1));
    }

    @Test
    public void metadataCannotRestoreRowsRemovedByContentCleanup() throws Exception {
        String retained = "[00:01.000]one\n";
        org.json.JSONArray metadata = new org.json.JSONArray()
                .put(structuredRow("two", 2, "译二"))
                .put(structuredRow("one", 0, "译一"));
        WordLyricModel model = assembler().assemble(retained, true, retained, true, "", metadata.toString()).model;
        assertEquals(1, model.lines.size());
        assertEquals("one", model.lines.get(0).text);
        assertEquals(0, model.lines.get(0).sourceAlignment);
        assertEquals(1, model.lines.get(0).words.size());
    }

    private static org.json.JSONObject structuredRow(String text, int alignment, String translation)
            throws Exception {
        return new org.json.JSONObject().put("begin", 1000L).put("end", 3000L)
                .put("text", text).put("words", new org.json.JSONArray())
                .put("translation", translation).put("alignment", alignment)
                .put("alignmentProvided", true);
    }
    private static final LyricParseTraceSink SILENT = new LyricParseTraceSink() {
        @Override
        public boolean traceEnabled() {
            return false;
        }

        @Override
        public void trace(String message) {
        }

        @Override
        public void onCoreParseFailure(boolean primarySource, Throwable failure) {
        }
    };

    private static NativeLyricModelAssembler assembler() {
        return new NativeLyricModelAssembler(SILENT);
    }

    @Test
    public void snapshotWithoutRawLyricBuildsModelAndSelectsExactCurrentRow() throws Exception {
        String lrc = "[00:00.000]previous\n[00:01.000]current\n[00:02.000]next\n";
        for (int index = 0; index < 3; index++) {
            org.json.JSONObject json = new org.json.JSONObject().put("lyric", lrc)
                    .put("displayMode", "sentence-window-v1").put("currentLine", index);
            LyricInfoContract.Payload payload = LyricInfoContract.parse(json.toString());
            String source = SentenceWindowContract.renderSource(payload);
            assertTrue(LyricInfoContract.containsTimedLrc(source));
            WordLyricModel model = assembler().assemble(source, true, payload.lyric, true, "").model;
            assertEquals(3, model.lines.size());
            assertEquals(index, model.indexOfLine(model.findActiveLine(payload.snapshotPositionMillis)));
            assertEquals(index, SentenceWindowContract.visualIndex(payload.snapshotPositionMillis, index, 2));
        }
        LyricInfoContract.Payload music = LyricInfoContract.parse(
                new org.json.JSONObject().put("lyric", lrc).toString());
        assertEquals("", SentenceWindowContract.renderSource(music));
        assertEquals(2, SentenceWindowContract.visualIndex(-1L, 0, 2));
    }

    @Test
    public void assemblesWordTimedPayloadWithAliasesAndSupplementalTranslations() {
        String raw = "[00:01.000] <00:01.000>Hel <00:01.300>lo<00:01.800>\n"
                + "[00:01.000]你好\n"
                + "[00:05.000] <00:05.000>Wor<00:05.400>ld<00:05.900>\n"
                + "[00:05.000]世界\n"
                + "[00:09.000] <00:09.000>Wie <00:09.200>der <00:09.400>sehen<00:09.900>\n";
        String display = "[00:01.000]Hello\n"
                + "[00:01.000]你好\n"
                + "[00:05.000]World\n"
                + "[00:05.000]世界\n";
        String translation = "[00:09.030]再次见到你\n";

        NativeLyricModelAssembler.AssemblyResult result = assembler().assemble(
                raw,
                LyricInfoContract.containsTimedLrc(raw),
                display,
                LyricInfoContract.containsTimedLrc(display),
                translation);

        WordLyricModel model = result.model;
        assertEquals(3, model.lines.size());
        assertEquals("inline-lrc", model.parserName);

        WordLine hello = model.lines.get(0);
        assertEquals(1_000L, hello.timeMillis);
        assertEquals("Hello", hello.displayText);
        assertEquals("你好", hello.translation);
        assertEquals("Hel lo", hello.text);

        WordLine world = model.lines.get(1);
        assertEquals(5_000L, world.timeMillis);
        assertEquals("World", world.displayText);
        assertEquals("世界", world.translation);
        assertEquals("Wor ld", world.text);

        WordLine wiedersehen = model.lines.get(2);
        assertEquals(9_000L, wiedersehen.timeMillis);
        assertEquals("再次见到你", wiedersehen.translation);

        assertEquals(2, result.aliasesApplied);
        assertEquals("Hello", result.firstAlias);
        assertEquals(0, result.supplementalDisplayAdded);
        assertEquals(1, result.supplementalTranslationAdded);

        assertEquals(2, model.officialLines.size());
        assertEquals(hello, model.officialLines.get(0));
        assertEquals(world, model.officialLines.get(1));
    }

    @Test
    public void propagatesNearbyTranslationsAcrossRepeatedLines() {
        String raw = "[00:10.000] <00:10.000>Re <00:10.200>peat<00:10.800>\n"
                + "[00:10.000]重复\n"
                + "[00:20.000] <00:20.000>Mid <00:20.200>dle<00:20.800>\n"
                + "[00:40.000] <00:40.000>Re <00:40.200>peat<00:40.800>\n";

        NativeLyricModelAssembler.AssemblyResult result = assembler().assemble(
                raw,
                LyricInfoContract.containsTimedLrc(raw),
                raw,
                LyricInfoContract.containsTimedLrc(raw),
                "");

        assertEquals(3, result.model.lines.size());
        assertEquals("重复", result.model.lines.get(0).translation);
        assertEquals("重复", result.model.lines.get(2).translation);
        assertTrue(result.propagatedTranslations >= 1);
    }

    @Test
    public void fallsBackToLyricsCoreForLineTimedPayload() {
        String raw = "[00:01.00]Hello\n[00:01.00]你好\n[00:05.00]World\n";

        NativeLyricModelAssembler.AssemblyResult result = assembler().assemble(
                raw,
                LyricInfoContract.containsTimedLrc(raw),
                "",
                false,
                "");

        assertEquals("lyrics-core", result.model.parserName);
        assertTrue(result.model.lines.size() >= 2);
        WordLine hello = result.model.lines.get(0);
        assertEquals(1_000L, hello.timeMillis);
        assertEquals("你好", hello.translation);
        assertEquals(0, result.aliasesApplied);
        assertEquals(0, result.supplementalDisplayAdded);
        assertEquals(0, result.supplementalTranslationAdded);
    }

    @Test
    public void explicitTranslationLaneIsNotFilteredByBridgeContentText() {
        String raw = "[00:01.000]Hello\n[00:05.000]World\n";
        String translation = "[00:01.000]以下歌词翻译由 Salt Player 提供\n";

        NativeLyricModelAssembler.AssemblyResult result = assembler().assemble(
                raw,
                LyricInfoContract.containsTimedLrc(raw),
                raw,
                LyricInfoContract.containsTimedLrc(raw),
                translation);

        assertEquals(2, result.model.lines.size());
        assertEquals("以下歌词翻译由 Salt Player 提供",
                result.model.lines.get(0).translation);
    }

    @Test
    public void untimedPayloadProducesEmptyModel() {
        NativeLyricModelAssembler.AssemblyResult result = assembler().assemble(
                "plain text without timing",
                false,
                "",
                false,
                "");
        assertNotNull(result.model);
        assertTrue(result.model.lines.isEmpty());
        assertEquals(0, result.aliasesApplied);
        assertEquals(0, result.supplementalDisplayAdded);
        assertEquals(0, result.supplementalTranslationAdded);
        assertEquals(0, result.propagatedTranslations);
        assertNull(result.model.findActiveLine(1_000L));
    }

    @Test
    public void skipsSupplementalMergeWhenDisplayEqualsRaw() {
        String raw = "[00:01.000] <00:01.000>Hel <00:01.300>lo<00:01.800>\n";
        NativeLyricModelAssembler.AssemblyResult result = assembler().assemble(
                raw,
                true,
                raw,
                true,
                "");
        assertEquals(1, result.model.lines.size());
        assertEquals(0, result.supplementalDisplayAdded);
    }

    @Test
    public void qqFrenchWordTimedLinesKeepDelayedFirstWordInsideTheLine() {
        String raw = "[00:07.228]<00:07.668>L'océan<00:08.084>sera"
                + "<00:08.508>mon<00:08.832>cavalier<00:09.592>\n"
                + "[00:11.209]<00:11.713>Couronnée<00:12.656>de"
                + "<00:12.976>lumière<00:13.488>\n"
                + "[01:08.208]<01:08.664>Condamnée<01:09.263>douce-"
                + "<01:10.023>amère<01:10.656>\n"
                + "[01:40.737]<01:41.272>Même<01:41.472>après"
                + "<01:41.760>la<01:41.959>fin<01:42.199>du"
                + "<01:42.415>spectacle<01:42.776>\n"
                + "[01:50.938]<01:51.482>Décidée<01:51.947>et"
                + "<01:52.362>sincère<01:53.274>";

        NativeLyricModelAssembler.AssemblyResult result = assembler().assemble(
                raw,
                true,
                raw,
                true,
                "");

        assertEquals(5, result.model.lines.size());
        assertEquals("L'océan sera mon cavalier", result.model.lines.get(0).text);
        assertEquals(4, result.model.lines.get(0).words.size());
        assertEquals("Couronnée de lumière", result.model.lines.get(1).text);
        assertEquals("Condamnée douce-amère", result.model.lines.get(2).text);
        assertEquals("Même après la fin du spectacle", result.model.lines.get(3).text);
        assertEquals("Décidée et sincère", result.model.lines.get(4).text);
    }

    @Test
    public void kugouTitleAliasDoesNotBecomeFirstLyricTranslation() {
        String raw = "[ti:回家的路 The Long Way Home]\n"
                + "[00:00.670]<00:00.670>当<00:00.903>你<00:01.150>走"
                + "<00:01.723>上<00:02.074>回<00:02.425>家"
                + "<00:02.776>的<00:03.127>路<00:03.479>";
        String display = "[00:00.700]回家的路 The Long Way Home - HOYO-MiX\n"
                + "[00:00.670]当你走上回家的路\n"
                + "[00:08.670]" + LyricTextSanitizer.ZERO_WIDTH_SPACE;

        NativeLyricModelAssembler.AssemblyResult result = assembler().assemble(
                raw,
                true,
                display,
                true,
                "");

        assertEquals(1, result.model.lines.size());
        WordLine firstLyric = result.model.lines.get(0);
        assertEquals("当你走上回家的路", firstLyric.text);
        assertEquals("", firstLyric.translation);
        assertEquals(3, result.model.officialLines.size());
        assertNull(result.model.officialLines.get(0));
        assertEquals(firstLyric, result.model.officialLines.get(1));
        assertNull(result.model.officialLines.get(2));
    }

    @Test
    public void kugouOpeningTitleSlotReusesDelayedRealFirstLineWithoutTranslation() {
        String raw = "[00:00.290]<00:00.290>Lyrics by<00:00.377>\n"
                + "[00:00.300]<00:00.300>Composed by<00:00.399>\n"
                + "[00:00.670]<00:00.670>当<00:00.903>你<00:01.150>走"
                + "<00:01.723>上<00:02.074>回<00:02.425>家"
                + "<00:02.776>的<00:03.127>路<00:03.479>";
        String display = "[00:00.700]回家的路 The Long Way Home - HOYO-MiX\n"
                + "[00:00.290]Lyrics by\n"
                + "[00:00.300]Composed by\n"
                + "[00:00.670]当你走上回家的路\n"
                + "[00:08.670]" + LyricTextSanitizer.ZERO_WIDTH_SPACE;

        NativeLyricModelAssembler.AssemblyResult result = assembler().assemble(
                raw, true, display, true, "");
        WordLine firstLyric = result.model.lines.get(2);

        assertEquals("当你走上回家的路", firstLyric.text);
        assertEquals("", firstLyric.translation);
        assertEquals(firstLyric, result.model.officialLines.get(0));
        assertNull(result.model.officialLines.get(3));
        assertEquals(0, result.model.adapterIndexOfLine(firstLyric));
    }

    @Test
    public void ordinaryOfficialTranslationAliasStillAttaches() {
        String raw = "[00:01.000]<00:01.000>Hello<00:01.800>";
        String display = "[00:01.030]你好";

        NativeLyricModelAssembler.AssemblyResult result = assembler().assemble(
                raw,
                true,
                display,
                true,
                "");

        assertEquals(1, result.model.lines.size());
        assertEquals("你好", result.model.lines.get(0).translation);
    }

    @Test
    public void zeroWidthTailPlaceholderKeepsOfficialSlotAlignment() {
        String raw = "[01:53.274]<01:53.458>La<01:53.658>ballerine"
                + "<01:53.970>solitaire<01:54.434>sera<01:55.250>la"
                + "<01:55.530>clef<01:57.346>";
        String display = "[01:53.270]La ballerine solitaire sera la clef\n"
                + "[02:01.270]" + LyricTextSanitizer.ZERO_WIDTH_SPACE;

        NativeLyricModelAssembler.AssemblyResult result = assembler().assemble(
                raw,
                true,
                display,
                true,
                "");

        assertEquals(1, result.model.lines.size());
        assertEquals(2, result.model.officialLines.size());
        assertEquals(result.model.lines.get(0), result.model.officialLines.get(0));
        assertNull(result.model.officialLines.get(1));
    }

    @Test
    public void nbspTailPlaceholderKeepsSlotWithoutCreatingTranslation() {
        String raw = "[00:01.000]<00:01.000>Hello<00:01.800>";
        String display = "[00:01.000]Hello\n[00:02.000]\u00A0";

        NativeLyricModelAssembler.AssemblyResult result = assembler().assemble(
                raw, true, display, true, "");

        assertEquals(1, result.model.lines.size());
        assertEquals(0, result.model.translationCount());
        assertEquals(2, result.model.officialLines.size());
        assertEquals(result.model.lines.get(0), result.model.officialLines.get(0));
        assertNull(result.model.officialLines.get(1));
    }
}
