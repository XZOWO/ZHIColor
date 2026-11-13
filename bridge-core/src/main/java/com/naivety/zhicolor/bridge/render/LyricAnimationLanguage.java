package com.naivety.zhicolor.bridge.render;

import java.util.Locale;

/** Source-language capability for emphasis; ordinary syllable lift is independent. */
public final class LyricAnimationLanguage {
    private LyricAnimationLanguage() {
    }

    public static boolean allowsEmphasis(String sourceLanguage, String text) {
        if (text == null || text.trim().isEmpty()) return false;
        // The native producer selects a no-emphasis route for any of these characters,
        // including mixed text, before applying the source-language capability.
        for (int offset = 0; offset < text.length(); ) {
            int codePoint = text.codePointAt(offset);
            Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
            if (script == Character.UnicodeScript.HAN
                    || script == Character.UnicodeScript.HIRAGANA
                    || script == Character.UnicodeScript.KATAKANA
                    || script == Character.UnicodeScript.BOPOMOFO
                    || codePoint == 0x02C9 || codePoint == 0x02CA || codePoint == 0x02C7
                    || codePoint == 0x02CB || codePoint == 0x02D9) return false;
            offset += Character.charCount(codePoint);
        }
        String tag = sourceLanguage == null ? "" : sourceLanguage.trim().replace('_', '-');
        if (!tag.isEmpty()) {
            String language = Locale.forLanguageTag(tag).getLanguage().toLowerCase(Locale.ROOT);
            return !language.equals("ar") && !language.equals("he")
                    && !language.equals("iw") && !language.equals("zh")
                    && !language.equals("ja");
        }
        // Android fallback when real source metadata is absent, not an Apple language rule.
        for (int offset = 0; offset < text.length(); ) {
            int codePoint = text.codePointAt(offset);
            Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
            if (script == Character.UnicodeScript.ARABIC
                    || script == Character.UnicodeScript.HEBREW) return false;
            offset += Character.charCount(codePoint);
        }
        return true;
    }

    /** Product extension for Chinese tail characters; separate from the native AM capability. */
    public static boolean allowsChineseTailEmphasis(String sourceLanguage, String text) {
        if (text == null || text.trim().isEmpty()) return false;
        boolean han = false, kana = false;
        for (int offset = 0; offset < text.length(); ) {
            int codePoint = text.codePointAt(offset);
            Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
            han |= script == Character.UnicodeScript.HAN;
            kana |= script == Character.UnicodeScript.HIRAGANA
                    || script == Character.UnicodeScript.KATAKANA;
            offset += Character.charCount(codePoint);
        }
        if (!han) return false;
        String tag = sourceLanguage == null ? "" : sourceLanguage.trim().replace('_', '-');
        if (!tag.isEmpty()) return Locale.forLanguageTag(tag).getLanguage().equalsIgnoreCase("zh");
        return !kana;
    }
}
