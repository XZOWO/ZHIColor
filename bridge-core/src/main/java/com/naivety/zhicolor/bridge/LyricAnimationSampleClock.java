package com.naivety.zhicolor.bridge;

/** Holds lyric animation time between accepted frames, including parent-driven redraws. */
final class LyricAnimationSampleClock {
    private long sample = Long.MIN_VALUE;
    private long deadline = LyricRefreshRatePolicy.UNSET_DEADLINE_NANOS;
    private int previousCap = -1;

    long sample(long nowNanos, int capHz) {
        if (previousCap != capHz || nowNanos < sample) {
            deadline = LyricRefreshRatePolicy.UNSET_DEADLINE_NANOS;
            previousCap = capHz;
        }
        if (LyricRefreshRatePolicy.isFrameDue(nowNanos, deadline, capHz)) {
            sample = nowNanos;
            deadline = LyricRefreshRatePolicy.advanceDeadline(nowNanos, deadline, capHz);
        }
        return sample;
    }
}
