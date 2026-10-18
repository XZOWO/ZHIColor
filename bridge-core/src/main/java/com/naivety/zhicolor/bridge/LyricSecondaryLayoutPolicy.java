package com.naivety.zhicolor.bridge;

/** The translation toggle never owns the backing-vocal channel. */
final class LyricSecondaryLayoutPolicy {
    private LyricSecondaryLayoutPolicy() {}

    static float translationAmount(boolean allLines, float enabledAmount, float focusAmount) {
        return unit(enabledAmount) * (allLines ? 1f : unit(focusAmount));
    }

    static float groupHeight(float main, float translationContribution,
            float translationAmount, float secondaryContribution, float secondaryAmount) {
        return Math.max(0f, main)
                + Math.max(0f, translationContribution) * unit(translationAmount)
                + Math.max(0f, secondaryContribution) * unit(secondaryAmount);
    }

    static int alignment(int configured, int sourceAlignment) {
        return configured == LyricUiConfig.ALIGN_DEFAULT
                ? Math.max(0, Math.min(2, sourceAlignment)) : configured;
    }

    private static float unit(float value) {
        return Math.max(0f, Math.min(1f, value));
    }
}
