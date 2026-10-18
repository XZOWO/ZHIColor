package com.naivety.zhicolor.bridge;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class SystemUiLyricPublicationGateTest {
    @Test
    public void currentEpochRunsRequestedShortPublication() {
        SystemUiLyricPublicationGate gate = new SystemUiLyricPublicationGate();
        long current = gate.beginLoad();
        AtomicInteger publications = new AtomicInteger();
        assertTrue(gate.commitIfCurrent(current, publications::incrementAndGet));
        org.junit.Assert.assertEquals(1, publications.get());
    }

    @Test
    public void supersededEpochDoesNotRunPublication() {
        SystemUiLyricPublicationGate gate = new SystemUiLyricPublicationGate();
        long older = gate.beginLoad();
        long newer = gate.beginLoad();
        AtomicBoolean published = new AtomicBoolean();
        assertFalse(gate.commitIfCurrent(older, () -> published.set(true)));
        assertFalse(published.get());
        assertTrue(gate.commitIfCurrent(newer, () -> published.set(true)));
        assertTrue(published.get());
    }

    @Test
    public void invalidatedParseCannotRepublishClearedModel() throws Exception {
        SystemUiLyricPublicationGate gate = new SystemUiLyricPublicationGate();
        long older = gate.beginLoad();
        CountDownLatch parseStarted = new CountDownLatch(1);
        CountDownLatch releaseParse = new CountDownLatch(1);
        AtomicBoolean committed = new AtomicBoolean(true);
        AtomicBoolean published = new AtomicBoolean();
        Thread parser = new Thread(() -> {
            parseStarted.countDown();
            await(releaseParse);
            committed.set(gate.commitIfCurrent(older, () -> published.set(true)));
        });
        parser.start();
        assertTrue(parseStarted.await(2, TimeUnit.SECONDS));
        gate.invalidate();
        releaseParse.countDown();
        parser.join(2_000L);
        assertFalse(parser.isAlive());
        assertFalse(committed.get());
        assertFalse(published.get());
    }

    @Test
    public void invalidateCannotSplitEpochCheckFromIdentityWrite() throws Exception {
        assertEpochChangeWaitsForPublication(false);
    }

    @Test
    public void newerLoadCannotSplitEpochCheckFromIdentityWrite() throws Exception {
        assertEpochChangeWaitsForPublication(true);
    }

    @Test
    public void olderParseCannotCommitAfterNewerLoadBegins() throws Exception {
        SystemUiLyricPublicationGate gate = new SystemUiLyricPublicationGate();
        long older = gate.beginLoad();
        CountDownLatch newerStarted = new CountDownLatch(1);
        CountDownLatch releaseOlder = new CountDownLatch(1);
        AtomicBoolean olderCommitted = new AtomicBoolean(true);

        Thread olderParse = new Thread(() -> {
            try {
                releaseOlder.await(2, TimeUnit.SECONDS);
                olderCommitted.set(gate.canCommit(older));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        olderParse.start();
        long newer = gate.beginLoad();
        newerStarted.countDown();
        assertTrue(newerStarted.await(1, TimeUnit.SECONDS));
        releaseOlder.countDown();
        olderParse.join(2_000L);

        assertFalse(olderCommitted.get());
        assertTrue(gate.canCommit(newer));
    }

    private static void assertEpochChangeWaitsForPublication(boolean beginNewLoad) throws Exception {
        SystemUiLyricPublicationGate gate = new SystemUiLyricPublicationGate();
        long current = gate.beginLoad();
        CountDownLatch enteredPublication = new CountDownLatch(1);
        CountDownLatch releasePublication = new CountDownLatch(1);
        AtomicBoolean identityWritten = new AtomicBoolean();
        AtomicBoolean epochChanged = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread publisher = new Thread(() -> {
            try {
                assertTrue(gate.commitIfCurrent(current, () -> {
                    enteredPublication.countDown();
                    // Only this test pauses the callback to expose a check/write race.
                    await(releasePublication);
                    assertFalse(epochChanged.get());
                    identityWritten.set(true);
                }));
            } catch (Throwable error) {
                failure.set(error);
            }
        });
        Thread invalidator = new Thread(() -> {
            try {
                if (beginNewLoad) gate.beginLoad(); else gate.invalidate();
                assertTrue(identityWritten.get());
                epochChanged.set(true);
            } catch (Throwable error) {
                failure.compareAndSet(null, error);
            }
        });
        publisher.start();
        try {
            assertTrue(enteredPublication.await(2, TimeUnit.SECONDS));
            invalidator.start();
            long waitUntil = System.nanoTime() + TimeUnit.SECONDS.toNanos(2L);
            while (invalidator.isAlive() && invalidator.getState() != Thread.State.BLOCKED
                    && System.nanoTime() < waitUntil) Thread.yield();
            org.junit.Assert.assertEquals(Thread.State.BLOCKED, invalidator.getState());
            assertFalse(epochChanged.get());
        } finally {
            releasePublication.countDown();
            publisher.join(2_000L);
            invalidator.join(2_000L);
        }
        assertFalse(publisher.isAlive());
        assertFalse(invalidator.isAlive());
        if (failure.get() != null) throw new AssertionError(failure.get());
        assertTrue(identityWritten.get());
        assertTrue(epochChanged.get());
        assertFalse(gate.commitIfCurrent(current, () -> {
            throw new AssertionError("stale publication ran after epoch change");
        }));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(2, TimeUnit.SECONDS)) throw new AssertionError("latch timed out");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError(error);
        }
    }
}
