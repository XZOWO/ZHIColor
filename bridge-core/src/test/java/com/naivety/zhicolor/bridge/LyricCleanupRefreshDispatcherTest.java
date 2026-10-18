package com.naivety.zhicolor.bridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class LyricCleanupRefreshDispatcherTest {
    @Test
    public void requestReturnsWhileVendorReloadIsBlockedOnDaemonWorker() throws Exception {
        QueueExecutor main = new QueueExecutor();
        LyricCleanupRefreshDispatcher dispatcher = new LyricCleanupRefreshDispatcher(main);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<Thread> worker = new AtomicReference<>();
        Thread caller = Thread.currentThread();
        try {
            dispatcher.request(() -> true, () -> {
                worker.set(Thread.currentThread());
                started.countDown();
                try {
                    assertTrue(release.await(2, TimeUnit.SECONDS));
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(error);
                } finally {
                    finished.countDown();
                }
                return true;
            }, () -> { throw new AssertionError("Unexpected fallback"); });
            assertTrue(started.await(1, TimeUnit.SECONDS));
            assertFalse(finished.await(20, TimeUnit.MILLISECONDS));
            assertTrue(worker.get() != caller);
            assertTrue(worker.get().isDaemon());
        } finally {
            release.countDown();
        }
        assertTrue(finished.await(1, TimeUnit.SECONDS));
    }

    @Test
    public void repeatedBroadcastsQueueOneWorkerAndOnlyLatestConfigurationRuns() {
        QueueExecutor worker = new QueueExecutor();
        QueueExecutor main = new QueueExecutor();
        LyricCleanupRefreshDispatcher dispatcher = new LyricCleanupRefreshDispatcher(worker, main);
        List<String> reloaded = new ArrayList<>();
        for (String config : Arrays.asList("old", "old", "new", "new")) {
            dispatcher.request(() -> true, () -> {
                reloaded.add(config);
                return true;
            }, () -> { throw new AssertionError("Unexpected fallback"); });
        }
        assertEquals(1, worker.size());
        assertTrue(reloaded.isEmpty());
        worker.runNext();
        assertEquals(Arrays.asList("new"), reloaded);
        assertEquals(0, main.size());
    }

    @Test
    public void queuedReloadIsDroppedWhenCapturedTrackOrContextIsStale() {
        QueueExecutor worker = new QueueExecutor();
        QueueExecutor main = new QueueExecutor();
        LyricCleanupRefreshDispatcher dispatcher = new LyricCleanupRefreshDispatcher(worker, main);
        AtomicBoolean current = new AtomicBoolean(true);
        AtomicInteger calls = new AtomicInteger();
        dispatcher.request(current::get, () -> {
            calls.incrementAndGet();
            return false;
        }, calls::incrementAndGet);
        current.set(false);
        worker.runNext();
        assertEquals(0, calls.get());
        assertEquals(0, main.size());
    }

    @Test
    public void pendingRequestsReplaceEachOtherDuringSerialVendorReload() {
        QueueExecutor worker = new QueueExecutor();
        QueueExecutor main = new QueueExecutor();
        LyricCleanupRefreshDispatcher dispatcher = new LyricCleanupRefreshDispatcher(worker, main);
        List<String> reloaded = new ArrayList<>();
        dispatcher.request(() -> true, () -> {
            reloaded.add("first");
            dispatcher.request(() -> true, () -> {
                reloaded.add("discarded");
                return true;
            }, () -> { throw new AssertionError("Unexpected fallback"); });
            dispatcher.request(() -> true, () -> {
                reloaded.add("latest");
                return true;
            }, () -> { throw new AssertionError("Unexpected fallback"); });
            return true;
        }, () -> { throw new AssertionError("Unexpected fallback"); });
        worker.runNext();
        assertEquals(Arrays.asList("first", "latest"), reloaded);
        assertEquals(0, worker.size());
        assertEquals(0, main.size());
    }

    @Test
    public void failedReloadFallsBackOnlyOnTheMainExecutor() {
        QueueExecutor worker = new QueueExecutor();
        QueueExecutor main = new QueueExecutor();
        LyricCleanupRefreshDispatcher dispatcher = new LyricCleanupRefreshDispatcher(worker, main);
        AtomicInteger calls = new AtomicInteger();
        AtomicBoolean mainExecuting = new AtomicBoolean(false);
        dispatcher.request(() -> true, () -> false, () -> {
            assertTrue(mainExecuting.get());
            calls.incrementAndGet();
        });
        worker.runNext();
        assertEquals(0, calls.get());
        assertEquals(1, main.size());
        mainExecuting.set(true);
        main.runNext();
        mainExecuting.set(false);
        assertEquals(1, calls.get());
    }

    @Test
    public void newRequestInvalidatesAlreadyQueuedFallback() {
        QueueExecutor worker = new QueueExecutor();
        QueueExecutor main = new QueueExecutor();
        LyricCleanupRefreshDispatcher dispatcher = new LyricCleanupRefreshDispatcher(worker, main);
        AtomicInteger oldFallback = new AtomicInteger();
        dispatcher.request(() -> true, () -> false, oldFallback::incrementAndGet);
        worker.runNext();
        assertEquals(1, main.size());
        AtomicInteger latestReload = new AtomicInteger();
        dispatcher.request(() -> true, () -> {
            latestReload.incrementAndGet();
            return true;
        }, () -> { throw new AssertionError("Unexpected fallback"); });
        main.runNext();
        worker.runNext();
        assertEquals(0, oldFallback.get());
        assertEquals(1, latestReload.get());
    }

    @Test
    public void fallbackRechecksTrackAfterItWasPosted() {
        QueueExecutor worker = new QueueExecutor();
        QueueExecutor main = new QueueExecutor();
        LyricCleanupRefreshDispatcher dispatcher = new LyricCleanupRefreshDispatcher(worker, main);
        AtomicBoolean current = new AtomicBoolean(true);
        AtomicInteger fallback = new AtomicInteger();
        dispatcher.request(current::get, () -> false, fallback::incrementAndGet);
        worker.runNext();
        current.set(false);
        main.runNext();
        assertEquals(0, fallback.get());
    }

    @Test
    public void reloadExceptionPostsOneFallbackAndDoesNotStrandTheNextRequest() {
        QueueExecutor worker = new QueueExecutor();
        QueueExecutor main = new QueueExecutor();
        LyricCleanupRefreshDispatcher dispatcher = new LyricCleanupRefreshDispatcher(worker, main);
        AtomicInteger calls = new AtomicInteger();
        dispatcher.request(() -> true, () -> {
            throw new IllegalStateException("vendor failure");
        }, calls::incrementAndGet);
        worker.runNext();
        main.runNext();
        dispatcher.request(() -> true, () -> {
            calls.incrementAndGet();
            return true;
        }, () -> { throw new AssertionError("Unexpected fallback"); });
        worker.runNext();
        assertEquals(2, calls.get());
        assertEquals(0, main.size());
    }

    private static final class QueueExecutor implements java.util.concurrent.Executor {
        private final Queue<Runnable> tasks = new ArrayDeque<>();

        @Override
        public synchronized void execute(Runnable task) {
            tasks.add(task);
        }

        synchronized int size() {
            return tasks.size();
        }

        void runNext() {
            Runnable task;
            synchronized (this) {
                task = tasks.remove();
            }
            task.run();
        }
    }
}
