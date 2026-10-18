package com.naivety.zhicolor.bridge;

import org.junit.Test;
import java.util.ArrayList;
import java.util.function.LongConsumer;
import static org.junit.Assert.assertEquals;

public class LyricFrameDispatcherTest {
    @Test public void allProducersShareTheCapAndDuplicateRequestsCoalesce() {
        ArrayList<LongConsumer> vsync = new ArrayList<>();
        LyricFrameDispatcher clock = new LyricFrameDispatcher(() -> 60, vsync::add);
        int[] calls = new int[3];
        Runnable[] producers = {() -> calls[0]++, () -> calls[1]++, () -> calls[2]++};
        for (int frame = 0; frame < 120; frame++) {
            for (Runnable producer : producers) { clock.request(producer); clock.request(producer); }
            assertEquals(1, vsync.size());
            vsync.remove(0).accept(frame * 8_333_333L);
        }
        for (int count : calls) assertEquals(60, count);
    }

    @Test public void capChangesAndCancelledOrIdleWorkDoNotKeepRunning() {
        ArrayList<LongConsumer> vsync = new ArrayList<>();
        int[] cap = {60}, calls = {0};
        LyricFrameDispatcher clock = new LyricFrameDispatcher(() -> cap[0], vsync::add);
        Runnable action = () -> calls[0]++;
        clock.request(action); clock.cancel(action);
        vsync.remove(0).accept(0L);
        assertEquals(0, calls[0]); assertEquals(0, vsync.size());
        clock.request(action); vsync.remove(0).accept(8_333_333L);
        cap[0] = 120;
        clock.request(action); vsync.remove(0).accept(16_666_666L);
        assertEquals(2, calls[0]); assertEquals(0, vsync.size());
    }
}
