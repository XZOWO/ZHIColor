package com.naivety.zhicolor.bridge.render;

/**
 * A timed range within a {@link WordLine}. {@code timeMillis} is the absolute
 * playback position at which the substring {@code [start, end)} should light
 * up as active. Promoted from {@code LockscreenLyricsModule} in step 3.1.
 */
public final class WordRange {

    public final long timeMillis;
    /** Explicit source word end, or -1 when the source only supplied word starts. */
    public final long endTimeMillis;
    public final int start;
    public final int end;

    public WordRange(long timeMillis, int start, int end) {
        this(timeMillis, start, end, -1L);
    }

    public WordRange(long timeMillis, int start, int end, long endTimeMillis) {
        this.timeMillis = timeMillis;
        this.endTimeMillis = endTimeMillis > timeMillis ? endTimeMillis : -1L;
        this.start = start;
        this.end = end;
    }
}
