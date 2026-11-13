package com.naivety.zhicolor.bridge.render;

import java.util.Locale;
import org.junit.Test;
import static org.junit.Assert.*;

public final class LyricAnimationLanguageTest {
    @Test public void explicitSourceLanguageUsesNormalizedPrimaryLanguage() {
        for (String language : new String[]{"zh", "zh-Hans", "ZH_hant_TW", "ja-JP",
                "ar-EG", "he-IL", "iw", " IW_il "}) {
            assertFalse(language, LyricAnimationLanguage.allowsEmphasis(language, "hello"));
        }
        for (String language : new String[]{"en-US", "ko-KR", "ru", "th-TH", "und"}) {
            assertTrue(language, LyricAnimationLanguage.allowsEmphasis(language, "hello"));
        }
    }

    @Test public void nativeProducerTextGatePrecedesExplicitSourceLanguage() {
        for (String text : new String[]{"你好", "hello 和中文", "かな", "カナ", "ㄅㄆㄇ",
                "hello ˉ", "hello ˊ", "hello ˇ", "hello ˋ", "hello ˙",
                new String(Character.toChars(0x20000))}) {
            assertFalse(text, LyricAnimationLanguage.allowsEmphasis("en-US", text));
        }
        assertFalse(LyricAnimationLanguage.allowsEmphasis("ko", "日本語"));
    }

    @Test public void explicitSourceLanguageOverridesOnlyAndroidUnknownLanguageFallback() {
        assertTrue(LyricAnimationLanguage.allowsEmphasis("en", "العربية עברית"));
        assertFalse(LyricAnimationLanguage.allowsEmphasis("zh-Hans", "Only Latin"));
        assertFalse(LyricAnimationLanguage.allowsEmphasis("ja", "한국어"));
    }

    @Test public void missingLanguageConservativelyChecksEachActualTextScript() {
        for (String text : new String[]{"汉字", "かな", "カナ", "العربية", "עברית",
                "English 和中文", "hello かな", "한글 カナ", "hello العربية",
                "123 עברית", new String(Character.toChars(0x20000))}) {
            assertFalse(text, LyricAnimationLanguage.allowsEmphasis(null, text));
            assertFalse(text, LyricAnimationLanguage.allowsEmphasis(" \t", text));
        }
    }

    @Test public void missingLanguageAllowsOtherScriptsAndMixedAllowedScripts() {
        for (String text : new String[]{"hello", "한국어", "Русский", "ภาษาไทย",
                "hello 한국어 Русский ภาษาไทย", "hello 😀"}) {
            assertTrue(text, LyricAnimationLanguage.allowsEmphasis("", text));
        }
    }

    @Test public void emptyTextNeverEmphasizesEvenWithExplicitLanguage() {
        for (String language : new String[]{null, "", "en-US", "zh"}) {
            assertFalse(LyricAnimationLanguage.allowsEmphasis(language, null));
            assertFalse(LyricAnimationLanguage.allowsEmphasis(language, ""));
            assertFalse(LyricAnimationLanguage.allowsEmphasis(language, " \t\n"));
        }
    }

    @Test public void capabilityNeverUsesSystemLocale() {
        Locale original = Locale.getDefault();
        try {
            for (Locale locale : new Locale[]{Locale.forLanguageTag("zh-Hans"),
                    Locale.forLanguageTag("ar-EG"), Locale.forLanguageTag("tr-TR")}) {
                Locale.setDefault(locale);
                assertTrue(LyricAnimationLanguage.allowsEmphasis(null, "hello 한국어"));
                assertFalse(LyricAnimationLanguage.allowsEmphasis(null, "汉字"));
                assertFalse(LyricAnimationLanguage.allowsEmphasis("en", "汉字"));
                assertFalse(LyricAnimationLanguage.allowsEmphasis("IW_il", "hello"));
                assertTrue(LyricAnimationLanguage.allowsChineseTailEmphasis(null, "hello 中文"));
                assertFalse(LyricAnimationLanguage.allowsChineseTailEmphasis("ja", "漢字"));
            }
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test public void chineseTailExtensionRequiresChineseSourceOrUnambiguousMissingLanguage() {
        for (String language : new String[]{"zh", "zh-Hans", " ZH_hant_TW "})
            assertTrue(language, LyricAnimationLanguage.allowsChineseTailEmphasis(language, "hello 中文"));
        for (String language : new String[]{"en", "ja-JP", "ko-KR", "ar", "he", "iw", "und"})
            assertFalse(language, LyricAnimationLanguage.allowsChineseTailEmphasis(language, "中文"));
        assertTrue(LyricAnimationLanguage.allowsChineseTailEmphasis(null, "中文"));
        assertTrue(LyricAnimationLanguage.allowsChineseTailEmphasis("", "Stay 与你"));
        assertFalse(LyricAnimationLanguage.allowsChineseTailEmphasis(null, "日本かな"));
        assertFalse(LyricAnimationLanguage.allowsChineseTailEmphasis("", "カナ漢字"));
        assertFalse(LyricAnimationLanguage.allowsChineseTailEmphasis(null, "hello"));
        assertFalse(LyricAnimationLanguage.allowsChineseTailEmphasis("zh", ""));
        assertFalse(LyricAnimationLanguage.allowsChineseTailEmphasis("zh", null));
    }

    @Test public void chineseExtensionDoesNotRelaxTheNativeAMProducerGate() {
        assertTrue(LyricAnimationLanguage.allowsChineseTailEmphasis("zh-Hans", "中文"));
        assertFalse(LyricAnimationLanguage.allowsEmphasis("zh-Hans", "中文"));
        assertFalse(LyricAnimationLanguage.allowsEmphasis("en", "hello 中"));
        assertTrue(LyricAnimationLanguage.allowsEmphasis("en", "hello"));
    }
}
