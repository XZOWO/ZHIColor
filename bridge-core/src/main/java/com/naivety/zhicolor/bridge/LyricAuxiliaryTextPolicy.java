package com.naivety.zhicolor.bridge;

import com.naivety.zhicolor.bridge.render.WordLine;

/** Keep translation and romanization independent even for legacy provider display aliases. */
public final class LyricAuxiliaryTextPolicy {
    private LyricAuxiliaryTextPolicy() { }

    public static String translation(WordLine line) {
        if (line == null) return "";
        if (line.advancedTranslationProvided) return value(line.advancedTranslation);
        String text = value(line.translation);
        return !value(line.romanization).isEmpty() && text.equals(line.romanization) ? "" : text;
    }

    static String romanization(WordLine line, boolean enabled) {
        return enabled && line != null ? value(line.romanization) : "";
    }

    private static String value(String text) { return text == null ? "" : text; }
}
