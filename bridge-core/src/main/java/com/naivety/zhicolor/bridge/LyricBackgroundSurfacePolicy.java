package com.naivety.zhicolor.bridge;

/** Native background visibility remains authoritative when the initial section bind was missed. */
final class LyricBackgroundSurfacePolicy {
    private LyricBackgroundSurfacePolicy() { }

    static boolean visible(boolean enabled, boolean sectionVisible, boolean backgroundVisible) {
        return enabled && (sectionVisible || backgroundVisible);
    }

    static boolean running(boolean visible, boolean interactive, boolean displayOn) {
        return visible && interactive && displayOn;
    }
}
