package com.argus.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Tests S1-S7 of the P2-06 plan (§7.4) — the monitor, with no pool and no sources. */
@Timeout(30)
class SourcePermitsTest {

    @Test
    void acquireReleaseAcquireAgainSucceeds() throws InterruptedException {
        SourcePermits permits = new SourcePermits(List.of("censys"), 1);

        permits.acquire("censys");
        permits.release("censys");
        permits.acquire("censys");

        assertEquals(0, permits.availableForTest("censys"));
        permits.release("censys");
        assertEquals(1, permits.availableForTest("censys"));
    }

    @Test
    void capHoldsAndBlocksSecondAcquirer() throws Exception {
        SourcePermits permits = new SourcePermits(List.of("censys"), 1);
        permits.acquire("censys");
        assertEquals(0, permits.availableForTest("censys"));

        CountDownLatch acquired = new CountDownLatch(1);
        Thread b = new Thread(() -> {
            try {
                permits.acquire("censys");
                acquired.countDown();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        b.start();

        while (permits.waitingForTest("censys") != 1 || b.getState() != Thread.State.WAITING) {
            Thread.onSpinWait();
        }
        assertEquals(0, permits.availableForTest("censys"));

        permits.release("censys");
        assertTrue(acquired.await(10, TimeUnit.SECONDS));
        b.join();
    }

    @Test
    void spuriousWakeupGuardIsAWhileNotAnIf() throws Exception {
        SourcePermits permits = new SourcePermits(List.of("censys"), 1);
        permits.acquire("censys");

        Thread b = new Thread(() -> {
            try {
                permits.acquire("censys");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        b.start();

        while (permits.waitingForTest("censys") != 1 || b.getState() != Thread.State.WAITING) {
            Thread.onSpinWait();
        }

        permits.spuriousWakeupForTest();

        // A buggy "if" guard would let the spuriously-woken thread fall through, decrement
        // available and terminate almost immediately. A bounded join is a hang detector here
        // (not a timing assertion, per the standing rules): with the correct "while" guard the
        // thread re-parks and this join always times out.
        b.join(500);
        assertTrue(b.isAlive());
        assertEquals(1, permits.waitingForTest("censys"));
        assertEquals(0, permits.availableForTest("censys"));

        permits.release("censys");
        b.join(10_000);
    }

    @Test
    void permitsArePerName() throws InterruptedException {
        SourcePermits permits = new SourcePermits(List.of("censys", "shodan"), 1);

        permits.acquire("censys");
        permits.acquire("shodan");

        assertEquals(0, permits.availableForTest("censys"));
        assertEquals(0, permits.availableForTest("shodan"));

        permits.release("censys");
        permits.release("shodan");
    }

    @Test
    void acquireIsInterruptibleAndConsumesNoPermit() throws Exception {
        SourcePermits permits = new SourcePermits(List.of("censys"), 1);
        permits.acquire("censys");

        AtomicBoolean interrupted = new AtomicBoolean(false);
        Thread b = new Thread(() -> {
            try {
                permits.acquire("censys");
            } catch (InterruptedException e) {
                interrupted.set(true);
            }
        });
        b.start();

        while (permits.waitingForTest("censys") != 1 || b.getState() != Thread.State.WAITING) {
            Thread.onSpinWait();
        }

        b.interrupt();
        b.join(10_000);

        assertTrue(interrupted.get());
        assertEquals(0, permits.availableForTest("censys"));
        permits.release("censys");
        assertEquals(1, permits.availableForTest("censys"));
    }

    @Test
    void releaseWithoutMatchingAcquireThrows() {
        SourcePermits permits = new SourcePermits(List.of("censys"), 1);

        assertThrows(IllegalStateException.class, () -> permits.release("censys"));
        assertEquals(1, permits.availableForTest("censys"));
    }

    @Test
    void unknownSourceNameThrowsOnAcquireAndRelease() {
        SourcePermits permits = new SourcePermits(List.of("censys"), 1);

        assertThrows(IllegalArgumentException.class, () -> permits.acquire("nope"));
        assertThrows(IllegalArgumentException.class, () -> permits.release("nope"));
    }
}
