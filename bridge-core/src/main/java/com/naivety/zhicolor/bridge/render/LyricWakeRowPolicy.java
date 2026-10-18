package com.naivety.zhicolor.bridge.render;

/** Blends a row's AOD focus and bright-screen singing state without inventing highlight. */
public final class LyricWakeRowPolicy {
    private LyricWakeRowPolicy() {
    }

    /** Multiplier for the row's active ink; inactive ink remains independently visible. */
    public static float activation(boolean aodFocused, boolean brightActive, float aodAmount) {
        return activation(aodFocused, brightActive, aodAmount, 1f);
    }

    /** Incoming ink and AOD handoff are independent, continuous contributions. */
    public static float activation(boolean aodFocused, boolean brightActive, float aodAmount,
            float brightActivation) {
        float aod = clampUnit(aodAmount);
        float bright = brightActive ? clampUnit(brightActivation) : 0f;
        if (aodFocused && brightActive) return aod + (1f - aod) * bright;
        if (aodFocused) return aod;
        return bright * (1f - aod);
    }

    /** Full-line contribution inside the active mask, before applying {@link #activation}. */
    public static float fillAmount(boolean aodFocused, boolean brightActive, float aodAmount) {
        if (!aodFocused) return 0f;
        return brightActive ? clampUnit(aodAmount) : 1f;
    }

    public static float fillAmount(boolean aodFocused, boolean brightActive, float aodAmount,
            float brightActivation) {
        if (!aodFocused) return 0f;
        float activation = activation(true, brightActive, aodAmount, brightActivation);
        return activation <= 0f ? 0f : clampUnit(aodAmount) / activation;
    }

    private static float clampUnit(float amount) {
        return Float.isNaN(amount) ? 0f : Math.max(0f, Math.min(1f, amount));
    }
}
