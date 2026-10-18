package com.naivety.zhicolor.bridge;

import java.util.concurrent.atomic.AtomicLong;

/** Monotonic ownership gate for the two SystemUI lyric-loader executor paths. */
final class SystemUiLyricPublicationGate {
    private final AtomicLong epoch = new AtomicLong();

    synchronized long beginLoad() {
        return epoch.incrementAndGet();
    }

    synchronized void invalidate() {
        epoch.incrementAndGet();
    }

    long currentEpoch() {
        return epoch.get();
    }

    boolean canCommit(long candidateEpoch) {
        return candidateEpoch == epoch.get();
    }

    /** The callback only publishes short in-memory model identity; parsing and I/O stay outside. */
    synchronized boolean commitIfCurrent(long candidateEpoch, Runnable publication) {
        if (candidateEpoch != epoch.get()) return false;
        publication.run();
        return true;
    }
}
