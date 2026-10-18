package com.naivety.zhicolor.bridge;

import com.naivety.zhicolor.bridge.render.NormalizedWordLineText;
import com.naivety.zhicolor.bridge.render.TimedLyricTextMapper;
import com.naivety.zhicolor.bridge.render.WordRange;
import java.util.ArrayList;
import org.junit.Test;
import static org.junit.Assert.*;

public final class TimedLyricTextMapperTest {
    @Test public void mapsBareEnglishWordsOntoExactSourceSpacingAndPunctuation() {
        String text = "He  did it, didn’t he?";
        NormalizedWordLineText result = map(text, "He", "did", "it", "didn't", "he");
        assertEquals(text, result.text);
        assertEquals(5, result.words.size());
        assertEquals("He", slice(result, 0));
        assertEquals("did", slice(result, 1));
        assertEquals("it", slice(result, 2));
        assertEquals("didn’t", slice(result, 3));
        assertEquals("he", slice(result, 4));
        assertEquals(4000L, result.words.get(3).timeMillis);
        assertEquals(4200L, result.words.get(3).endTimeMillis);
    }

    @Test public void splitContractionAndCjkDoNotGainInventedSpaces() {
        NormalizedWordLineText contraction = map("don't stop", "don", "t", "stop");
        assertEquals("don't stop", contraction.text);
        assertEquals("don", slice(contraction, 0));
        assertEquals("t", slice(contraction, 1));
        assertEquals("stop", slice(contraction, 2));
        NormalizedWordLineText cjk = map("你好，世界", "你", "好", "世界");
        assertEquals("你好，世界", cjk.text);
        assertEquals("世界", slice(cjk, 2));
    }

    @Test public void mismatchKeepsOriginalTextWithoutFabricatingClock() {
        NormalizedWordLineText result = map("I am here", "I", "was", "here");
        assertEquals("I am here", result.text);
        assertTrue(result.words.isEmpty());
    }

    @Test public void wordsOnlySourceRetainsExactProvidedConcatenation() {
        NormalizedWordLineText result = map("", "hello", "world");
        assertEquals("helloworld", result.text);
        assertEquals(2, result.words.size());
    }

    @Test public void unicodeSymbolsAndNbspKeepCorrectUtf16Ranges() {
        NormalizedWordLineText result = map("Hi\u00a0😀!", "Hi", "😀");
        assertEquals("Hi\u00a0😀!", result.text);
        assertEquals("😀", slice(result, 1));
        assertEquals(3, result.words.get(1).start);
        assertEquals(5, result.words.get(1).end);
    }

    private static String slice(NormalizedWordLineText result, int index) {
        WordRange range = result.words.get(index);
        return result.text.substring(range.start, range.end);
    }

    private static NormalizedWordLineText map(String display, String... tokens) {
        StringBuilder text = new StringBuilder();
        ArrayList<WordRange> ranges = new ArrayList<>();
        for (int index = 0; index < tokens.length; index++) {
            int start = text.length();
            text.append(tokens[index]);
            ranges.add(new WordRange((index + 1) * 1000L, start, text.length(),
                    (index + 1) * 1000L + 200L));
        }
        return TimedLyricTextMapper.map(display, text.toString(), ranges);
    }
}
