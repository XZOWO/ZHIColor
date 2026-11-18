package com.naivety.zhicolor.bridge;

import com.naivety.zhicolor.bridge.render.AppleLyricMotionPolicy;
import com.naivety.zhicolor.bridge.render.ZhiToolLyricMotion;

/** Reduced motion retains the transitions needed to follow the lyric's position. */
final class LyricMotionPolicy {
    private LyricMotionPolicy() {}

    static boolean animate(int mode, boolean lowFrameRate, boolean systemAnimatorsEnabled) {
        return mode != LyricUiConfig.MOTION_OFF && !lowFrameRate && systemAnimatorsEnabled;
    }

    static boolean fullMotion(int mode) {
        return mode == LyricUiConfig.MOTION_STANDARD || mode == LyricUiConfig.MOTION_APPLE;
    }

    static boolean appleMotion(int mode) {
        return mode == LyricUiConfig.MOTION_APPLE;
    }

    static long scrollMillis(int mode) {
        if (mode == LyricUiConfig.MOTION_OFF) return 0L;
        if (mode == LyricUiConfig.MOTION_REDUCED) return 420L;
        return appleMotion(mode) ? AppleLyricMotionPolicy.SCROLL_MS : ZhiToolLyricMotion.SCROLL_MS;
    }

    static long focusMillis(int mode) {
        if (mode == LyricUiConfig.MOTION_OFF) return 0L;
        if (mode == LyricUiConfig.MOTION_REDUCED) return 220L;
        return appleMotion(mode) ? AppleLyricMotionPolicy.FOCUS_MS : ZhiToolLyricMotion.FOCUS_MS;
    }

    static long secondaryMillis(int mode) {
        return mode == LyricUiConfig.MOTION_REDUCED ? 240L : ZhiToolLyricMotion.SECONDARY_MS;
    }

    static long secondaryDelayMillis(int mode) {
        if (mode == LyricUiConfig.MOTION_REDUCED) return 0L;
        return appleMotion(mode) ? 180L : ZhiToolLyricMotion.SECONDARY_DELAY_MS;
    }

    static long songMillis(int mode) {
        return mode == LyricUiConfig.MOTION_REDUCED ? 280L : ZhiToolLyricMotion.SONG_SWITCH_MS;
    }
}
