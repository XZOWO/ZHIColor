package com.naivety.zhicolor.bridge;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.function.IntSupplier;
import java.util.function.LongConsumer;

/** One deadline and one VSYNC callback for every lyric animation producer. */
final class LyricFrameDispatcher {
    interface Vsync { void post(LongConsumer callback); }

    private final IntSupplier cap;
    private final Vsync vsync;
    private final LinkedHashSet<Runnable> pending = new LinkedHashSet<>();
    private boolean posted;
    private int previousCap = -1;
    private long deadline = LyricRefreshRatePolicy.UNSET_DEADLINE_NANOS;

    LyricFrameDispatcher(IntSupplier cap, Vsync vsync) {
        this.cap = cap;
        this.vsync = vsync;
    }

    void request(Runnable action) {
        pending.add(action);
        schedule();
    }

    void cancel(Runnable action) { pending.remove(action); }

    private void schedule() {
        if (posted || pending.isEmpty()) return;
        posted = true;
        vsync.post(this::frame);
    }

    private void frame(long now) {
        posted = false;
        if (pending.isEmpty()) return;
        int hz = cap.getAsInt();
        if (hz != previousCap) {
            previousCap = hz;
            deadline = LyricRefreshRatePolicy.UNSET_DEADLINE_NANOS;
        }
        if (LyricRefreshRatePolicy.isFrameDue(now, deadline, hz)) {
            deadline = LyricRefreshRatePolicy.advanceDeadline(now, deadline, hz);
            ArrayList<Runnable> batch = new ArrayList<>(pending);
            pending.clear();
            for (Runnable action : batch) action.run();
        }
        schedule();
    }
}
