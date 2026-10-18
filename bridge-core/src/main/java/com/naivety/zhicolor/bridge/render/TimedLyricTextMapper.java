package com.naivety.zhicolor.bridge.render;

import com.naivety.zhicolor.bridge.LyricTextSanitizer;
import java.util.ArrayList;

/** Maps source word clocks onto the supplied display text without inventing separators. */
public final class TimedLyricTextMapper {
    private TimedLyricTextMapper() {}

    public static NormalizedWordLineText map(
            String sourceText, String tokenText, ArrayList<WordRange> sourceWords) {
        String display = clean(sourceText);
        String tokens = tokenText == null ? "" : tokenText;
        if (display.isEmpty()) display = clean(tokens);
        ArrayList<WordRange> mapped = new ArrayList<>();
        if (sourceWords == null || sourceWords.isEmpty()) {
            return new NormalizedWordLineText(display, mapped);
        }
        Key source = key(display);
        Key tokenKey = key(tokens);
        // A mismatched lyric keeps its text; an invented word clock would highlight wrong words.
        if (!source.value.equals(tokenKey.value)) {
            return new NormalizedWordLineText(display, mapped);
        }
        int lexicalCursor = 0;
        int displayCursor = 0;
        for (WordRange word : sourceWords) {
            if (word == null || word.start < 0 || word.end > tokens.length()
                    || word.start >= word.end) return new NormalizedWordLineText(display, new ArrayList<>());
            String token = tokens.substring(word.start, word.end);
            Key part = key(token);
            int count = part.starts.size();
            int start;
            int end;
            if (count > 0) {
                if (lexicalCursor + count > source.starts.size()) {
                    return new NormalizedWordLineText(display, new ArrayList<>());
                }
                start = source.starts.get(lexicalCursor);
                end = source.ends.get(lexicalCursor + count - 1);
                lexicalCursor += count;
            } else {
                String literal = clean(token);
                int nextGlyph = lexicalCursor < source.starts.size()
                        ? source.starts.get(lexicalCursor) : display.length();
                start = display.indexOf(literal, displayCursor);
                end = start + literal.length();
                if (literal.isEmpty() || start < displayCursor || end > nextGlyph) {
                    return new NormalizedWordLineText(display, new ArrayList<>());
                }
            }
            if (start < displayCursor || end <= start) {
                return new NormalizedWordLineText(display, new ArrayList<>());
            }
            mapped.add(new WordRange(word.timeMillis, start, end, word.endTimeMillis));
            displayCursor = end;
        }
        if (lexicalCursor != source.starts.size()) mapped.clear();
        return new NormalizedWordLineText(display, mapped);
    }

    /** Preserve every visible source separator, including non-breaking spaces and apostrophes. */
    public static String clean(String text) {
        return LyricTextSanitizer.removeIgnorableCharacters(text == null ? "" : text)
                .replace('\n', ' ').replace('\r', ' ');
    }

    private static Key key(String text) {
        Key result = new Key();
        StringBuilder value = new StringBuilder();
        for (int offset = 0; offset < text.length();) {
            int point = text.codePointAt(offset);
            int end = offset + Character.charCount(point);
            if (!separator(point)) {
                value.appendCodePoint(Character.toLowerCase(point));
                result.starts.add(offset);
                result.ends.add(end);
            }
            offset = end;
        }
        result.value = value.toString();
        return result;
    }

    private static boolean separator(int point) {
        int type = Character.getType(point);
        return Character.isWhitespace(point) || Character.isSpaceChar(point)
                || point == 0x200B || point == 0x2060 || point == 0xFEFF
                || type == Character.CONNECTOR_PUNCTUATION
                || type == Character.DASH_PUNCTUATION
                || type == Character.START_PUNCTUATION
                || type == Character.END_PUNCTUATION
                || type == Character.INITIAL_QUOTE_PUNCTUATION
                || type == Character.FINAL_QUOTE_PUNCTUATION
                || type == Character.OTHER_PUNCTUATION;
    }

    private static final class Key {
        String value;
        final ArrayList<Integer> starts = new ArrayList<>();
        final ArrayList<Integer> ends = new ArrayList<>();
    }
}
