package com.argus.ui;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Section 6.4: {@code PauseGate} — a resumable gate. One private final lock, guarded while
 * loops, notifyAll only — the same monitor discipline as {@code ScanPipeline} (P1-03 §4.3).
 * Parkedness is proven by spinning on {@code Thread.getState() == WAITING}, never sleep.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class PauseGateTest {

    /** Spins on state, not time — the {@code ScanPipelineBlockingTest} precedent. */
    private static void awaitParked(Thread t) {
        while (t.getState() != Thread.State.WAITING) {
            assertTrue(t.isAlive(), "thread returned when it should have parked");
            Thread.onSpinWait();
        }
    }

    private static Thread startAwaiter(PauseGate gate, CountDownLatch done,
            AtomicReference<Exception> failure) {
        Thread t = new Thread(() -> {
            try {
                gate.awaitResume();
            } catch (InterruptedException e) {
                failure.set(e);
            } finally {
                done.countDown();
            }
        }, "pause-gate-test-1");
        t.start();
        return t;
    }

    @Test
    void awaitResumeReturnsImmediatelyWhenNotPaused() throws Exception {
        PauseGate gate = new PauseGate();
        assertDoesNotThrow(gate::awaitResume);
    }

    @Test
    void awaitResumeParksWhilePaused() throws Exception {
        PauseGate gate = new PauseGate();
        gate.pause();
        CountDownLatch done = new CountDownLatch(1);
        Thread t = startAwaiter(gate, done, new AtomicReference<>());

        awaitParked(t);
        assertEquals(1, done.getCount());
        awaitParked(t);

        gate.resume();
        assertTrue(done.await(10, TimeUnit.SECONDS));
        t.join(10_000);
        assertFalse(t.isAlive());
    }

    @Test
    void resumeReleasesAParkedThread() throws Exception {
        PauseGate gate = new PauseGate();
        gate.pause();
        CountDownLatch done = new CountDownLatch(1);
        Thread t = startAwaiter(gate, done, new AtomicReference<>());

        awaitParked(t);
        gate.resume();

        assertTrue(done.await(10, TimeUnit.SECONDS));
        t.join(10_000);
        assertFalse(t.isAlive());
    }

    @Test
    void stopReleasesAParkedThreadEvenWhileStillPaused() throws Exception {
        PauseGate gate = new PauseGate();
        gate.pause();
        CountDownLatch done = new CountDownLatch(1);
        Thread t = startAwaiter(gate, done, new AtomicReference<>());

        awaitParked(t);
        gate.stop();

        assertTrue(done.await(10, TimeUnit.SECONDS));
        t.join(10_000);
        assertFalse(t.isAlive());
        assertTrue(gate.isPaused());
        assertTrue(gate.isStopped());
    }

    @Test
    void stopIsIdempotentAndPermanent() throws Exception {
        PauseGate gate = new PauseGate();
        gate.stop();
        gate.stop();
        gate.pause();
        gate.resume();

        assertDoesNotThrow(gate::awaitResume);
        assertTrue(gate.isStopped());
    }

    @Test
    void spuriousWakeupsDoNotReleaseAParkedThread() throws Exception {
        PauseGate gate = new PauseGate();
        gate.pause();
        CountDownLatch done = new CountDownLatch(1);
        Thread t = startAwaiter(gate, done, new AtomicReference<>());

        awaitParked(t);
        for (int i = 0; i < 100; i++) {
            gate.spuriousWakeupForTest();
            awaitParked(t);
            assertEquals(1, done.getCount(), "thread must still be parked");
        }

        gate.resume();
        assertTrue(done.await(10, TimeUnit.SECONDS));
        t.join(10_000);
        assertFalse(t.isAlive());
    }

    @Test
    void interruptingAParkedThreadThrowsInterruptedException() throws Exception {
        PauseGate gate = new PauseGate();
        gate.pause();
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Exception> failure = new AtomicReference<>();
        Thread t = startAwaiter(gate, done, failure);

        awaitParked(t);
        t.interrupt();

        assertTrue(done.await(10, TimeUnit.SECONDS));
        t.join(10_000);
        assertFalse(t.isAlive());
        assertTrue(failure.get() instanceof InterruptedException);

        // the gate is still usable afterwards
        gate.resume();
        assertDoesNotThrow(gate::awaitResume);
    }

    @Test
    void pauseAndResumeAreIdempotent() throws Exception {
        PauseGate gate = new PauseGate();
        gate.pause();
        gate.pause();
        assertTrue(gate.isPaused());
        gate.resume();
        gate.resume();
        assertFalse(gate.isPaused());
        assertDoesNotThrow(gate::awaitResume);
    }

    @Test
    void manyThreadsAreAllReleasedByOneResume() throws Exception {
        PauseGate gate = new PauseGate();
        gate.pause();
        int threadCount = 4;
        CountDownLatch done = new CountDownLatch(threadCount);
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            Thread t = startAwaiter(gate, done, new AtomicReference<>());
            threads.add(t);
        }
        for (Thread t : threads) {
            awaitParked(t);
        }

        gate.resume();
        assertTrue(done.await(10, TimeUnit.SECONDS));
        for (Thread t : threads) {
            t.join(10_000);
            assertFalse(t.isAlive());
        }
    }
}
