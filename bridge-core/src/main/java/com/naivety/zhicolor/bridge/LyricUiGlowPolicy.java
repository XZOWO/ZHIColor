package com.naivety.zhicolor.bridge;

/** Keeps the glow switch independent from its saved intensity. */
final class LyricUiGlowPolicy {
    private LyricUiGlowPolicy() {
    }

    static int canonicalIntensityPercent(int intensityPercent) {
        return Math.max(0, Math.min(100, intensityPercent));
    }

    /** Only schema 1-5 used intensity as the visible switch. */
    static boolean enabledForIntensity(int intensityPercent) {
        return intensityPercent > 0;
    }
}
