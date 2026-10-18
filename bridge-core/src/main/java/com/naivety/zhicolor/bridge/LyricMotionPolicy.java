package com.naivety.zhicolor.bridge;

import com.naivety.zhicolor.bridge.render.ZhiToolLyricMotion;

/** Reduced motion retains the transitions needed to follow the lyric's position. */
final class LyricMotionPolicy {
    private LyricMotionPolicy() {}

    static boolean animate(int mode, boolean lowFrameRate, boolean systemAnimatorsEnabled) {
        return mode != LyricUiConfig.MOTION_OFF && !lowFrameRate && systemAnimatorsEnabled;
    }

    static long scrollMillis(int mode) {
        return mode == LyricUiConfig.MOTION_OFF ? 0L
                : mode == LyricUiConfig.MOTION_REDUCED ? 420L : ZhiToolLyricMotion.SCROLL_MS;
    }

    static long focusMillis(int mode) {
        return mode == LyricUiConfig.MOTION_OFF ? 0L
                : mode == LyricUiConfig.MOTION_REDUCED ? 220L : ZhiToolLyricMotion.FOCUS_MS;
    }

    static long secondaryMillis(int mode) {
        return mode == LyricUiConfig.MOTION_REDUCED ? 240L : ZhiToolLyricMotion.SECONDARY_MS;
    }

    static long secondaryDelayMillis(int mode) {
        return mode == LyricUiConfig.MOTION_REDUCED ? 0L : ZhiToolLyricMotion.SECONDARY_DELAY_MS;
    }

    static long songMillis(int mode) {
        return mode == LyricUiConfig.MOTION_REDUCED ? 280L : ZhiToolLyricMotion.SONG_SWITCH_MS;
    }
}
