package com.naivety.zhicolor.bridge;

import android.graphics.Typeface;
import android.graphics.fonts.Font;
import android.graphics.fonts.FontFamily;
import android.graphics.fonts.FontStyle;
import java.io.File;

/** Avoids OEM theme redirection of the sans-serif family alias. No font is downloaded. */
final class AppleLyricTypeface {
    private static Typeface regular, bold;
    private static boolean initialized;

    static synchronized Typeface resolve(boolean strong) {
        if (!initialized) {
            initialized = true;
            FontFamily latin = family(new String[]{"Roboto-Regular.ttf", "RobotoStatic-Regular.ttf",
                    "Roboto-VF.ttf", "RobotoVariable.ttf"}, new String[]{"Roboto-Bold.ttf", "RobotoStatic-Bold.ttf"}, -1);
            FontFamily chinese = family(new String[]{"NotoSansCJK-Regular.ttc", "NotoSansSC-Regular.otf",
                    "NotoSansSC-VF.ttf", "NotoSansSC-Regular.ttf"},
                    new String[]{"NotoSansCJK-Bold.ttc", "NotoSansSC-Bold.otf", "NotoSansSC-Bold.ttf"}, 2);
            FontFamily primary = latin != null ? latin : chinese;
            if (primary != null) {
                regular = make(primary, latin != null ? chinese : null, 400);
                bold = make(primary, latin != null ? chinese : null, 700);
            }
            if (regular == null) regular = Typeface.DEFAULT;
            if (bold == null) bold = Typeface.create(regular, Typeface.BOLD);
        }
        return strong ? bold : regular;
    }

    private static Typeface make(FontFamily primary, FontFamily fallback, int weight) {
        try {
            Typeface.CustomFallbackBuilder builder = new Typeface.CustomFallbackBuilder(primary);
            if (fallback != null) builder.addCustomFallback(fallback);
            return builder.setStyle(new FontStyle(weight, FontStyle.FONT_SLANT_UPRIGHT)).build();
        } catch (RuntimeException failure) { return null; }
    }

    private static FontFamily family(String[] regularNames, String[] boldNames, int ttcIndex) {
        Font regular = font(regularNames, ttcIndex);
        if (regular == null) return null;
        try {
            FontFamily.Builder family = new FontFamily.Builder(regular);
            Font bold = font(boldNames, ttcIndex);
            if (bold != null && !bold.getStyle().equals(regular.getStyle())) family.addFont(bold);
            return family.build();
        } catch (RuntimeException failure) { return null; }
    }

    private static Font font(String[] names, int ttcIndex) {
        for (String name : names) {
            File file = new File("/system/fonts", name);
            if (!file.isFile() || !file.canRead()) continue;
            try {
                Font.Builder builder = new Font.Builder(file);
                if (name.endsWith(".ttc") && ttcIndex >= 0) builder.setTtcIndex(ttcIndex);
                return builder.build();
            } catch (Exception failure) { /* Try the next on-device system font. */ }
        }
        return null;
    }
}
