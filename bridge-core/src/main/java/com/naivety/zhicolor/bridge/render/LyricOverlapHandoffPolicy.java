package com.naivety.zhicolor.bridge.render;

/** A pending native focus handoff is valid only for its original adjacent pair. */
public final class LyricOverlapHandoffPolicy {
    public static final int CANCEL = -1;
    public static final int WAIT = 0;
    public static final int RELEASE = 1;

    private LyricOverlapHandoffPolicy() {}

    public static int action(int oldFocus, int incoming, int currentFocus, int currentOverlap) {
        if (currentFocus == incoming) return RELEASE;
        return currentFocus == oldFocus && currentOverlap == incoming ? WAIT : CANCEL;
    }

    public static long delayMillis(long position, long end, boolean advancing, float speed) {
        if (!advancing || !(speed > 0f) || Float.isInfinite(speed)) return -1L;
        return Math.max(1L, (long) Math.ceil(Math.max(0L, end - position) / (double) speed));
    }
}
