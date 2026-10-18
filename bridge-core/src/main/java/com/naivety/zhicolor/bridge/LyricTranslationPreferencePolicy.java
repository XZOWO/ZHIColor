package com.naivety.zhicolor.bridge;

/** A card selection remains authoritative until its saved override is cleared. */
final class LyricTranslationPreferencePolicy {
    private LyricTranslationPreferencePolicy() {}

    static boolean resolve(Boolean cardOverride, Boolean playerDefault, boolean globalDefault) {
        if (cardOverride != null) return cardOverride;
        return playerDefault == null ? globalDefault : playerDefault;
    }
}
