package com.naivety.zhicolor.bridge;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class LyricAnimationSampleClockTest {
    @Test public void parentRedrawsReuseTheLastCompleteSampleUntilTheNextDeadline() {
        LyricAnimationSampleClock clock = new LyricAnimationSampleClock();
        assertEquals(0L, clock.sample(0L, 60));
        assertEquals(0L, clock.sample(8_333_333L, 60));
        assertEquals(16_666_666L, clock.sample(16_666_666L, 60));
        assertEquals(16_666_666L, clock.sample(25_000_000L, 60));
    }
    @Test public void followSystemAndRateChangesStartAtTheCurrentTime() {
        LyricAnimationSampleClock clock = new LyricAnimationSampleClock();
        assertEquals(1L, clock.sample(1L, 60));
        assertEquals(10L, clock.sample(10L, 0));
        assertEquals(11L, clock.sample(11L, 0));
        assertEquals(12L, clock.sample(12L, 120));
    }
}
