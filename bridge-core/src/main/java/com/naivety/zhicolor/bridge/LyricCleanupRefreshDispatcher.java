package com.naivety.zhicolor.bridge;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;

/** Serial cleanup reloads; only the latest outstanding request may reload or fall back. */
final class LyricCleanupRefreshDispatcher {
    private final Executor worker;
    private final Executor main;
    private final Object lock = new Object();
    private long generation;
    private Request latest;
    private Request pending;
    private boolean workerScheduled;

    LyricCleanupRefreshDispatcher(Executor main) {
        this(Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "ZHIColor-cleanup-refresh");
            thread.setDaemon(true);
            return thread;
        }), main);
    }

    LyricCleanupRefreshDispatcher(Executor worker, Executor main) {
        this.worker = Objects.requireNonNull(worker);
        this.main = Objects.requireNonNull(main);
    }

    void request(BooleanSupplier isCurrent, BooleanSupplier reload, Runnable fallback) {
        boolean schedule;
        synchronized (lock) {
            latest = new Request(++generation, isCurrent, reload, fallback);
            pending = latest;
            schedule = !workerScheduled;
            workerScheduled = true;
        }
        if (schedule) worker.execute(this::drain);
    }

    private void drain() {
        while (true) {
            Request request;
            synchronized (lock) {
                request = pending;
                pending = null;
                if (request == null) {
                    workerScheduled = false;
                    return;
                }
            }
            if (!isCurrent(request)) {
                complete(request);
                continue;
            }
            boolean reloaded;
            try {
                reloaded = request.reload.getAsBoolean();
            } catch (RuntimeException error) {
                reloaded = false;
            }
            if (!reloaded && isCurrent(request)) {
                main.execute(() -> {
                    try {
                        if (isCurrent(request)) request.fallback.run();
                    } finally {
                        complete(request);
                    }
                });
            } else {
                complete(request);
            }
        }
    }

    private boolean isCurrent(Request request) {
        synchronized (lock) {
            if (request.generation != generation || latest != request) return false;
        }
        boolean current;
        try {
            current = request.isCurrent.getAsBoolean();
        } catch (RuntimeException error) {
            return false;
        }
        synchronized (lock) {
            return current && request.generation == generation && latest == request;
        }
    }

    private void complete(Request request) {
        synchronized (lock) {
            if (latest == request) latest = null;
        }
    }

    private static final class Request {
        final long generation;
        final BooleanSupplier isCurrent;
        final BooleanSupplier reload;
        final Runnable fallback;

        Request(long generation, BooleanSupplier isCurrent,
                BooleanSupplier reload, Runnable fallback) {
            this.generation = generation;
            this.isCurrent = Objects.requireNonNull(isCurrent);
            this.reload = Objects.requireNonNull(reload);
            this.fallback = Objects.requireNonNull(fallback);
        }
    }
}
