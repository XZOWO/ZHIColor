package com.naivety.zhicolor.bridge;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class LyricTranslationPreferencePolicyTest {
    @Test public void cardOverrideWinsOverPlayerAndGlobalDefaults() {
        assertFalse(LyricTranslationPreferencePolicy.resolve(false, true, true));
        assertTrue(LyricTranslationPreferencePolicy.resolve(true, false, false));
    }

    @Test public void configuredPlayerDefaultWinsWithoutCardOverride() {
        assertFalse(LyricTranslationPreferencePolicy.resolve(null, false, true));
        assertTrue(LyricTranslationPreferencePolicy.resolve(null, true, false));
    }

    @Test public void clearingPlayerOverrideRestoresGlobalDefault() {
        assertFalse(LyricTranslationPreferencePolicy.resolve(null, null, false));
        assertTrue(LyricTranslationPreferencePolicy.resolve(null, null, true));
    }
}
