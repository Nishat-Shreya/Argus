package com.argus.core;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A per-source-name in-flight request cap, enforced with a hand-rolled monitor rather than
 * {@link java.util.concurrent.Semaphore} — house style for every compound operation (invariant
 * 5): {@code acquire()} is a textbook check-then-act (observe availability, then decrement), so
 * it is done wholly under one lock, guarded by a {@code while} (never an {@code if}), and
 * released via {@code notifyAll()} (never {@code notify()}) because one monitor serves waiters
 * for every source name at once.
 *
 * The lock is never held across the caller's actual query — only across the acquire/release
 * bookkeeping — so nothing blocking (network I/O) is ever called while holding {@link #lock}.
 *
 * Deadlock argument: a caller acquires exactly one permit and never acquires a second while
 * holding the first (that discipline lives in {@link ThreatIntelClient}, not here); no task
 * waits on another task's completion. So no cycle can form.
 */
final class SourcePermits {

    private final Object lock = new Object();
    private final Map<String, Integer> available;   // @GuardedBy("lock")
    private final Map<String, Integer> waiting;      // @GuardedBy("lock")
    private final int permitsPerSource;

    SourcePermits(Collection<String> sourceNames, int permitsPerSource) {
        Objects.requireNonNull(sourceNames, "sourceNames must not be null");
        if (permitsPerSource <= 0) {
            throw new IllegalArgumentException("permitsPerSource must be positive: " + permitsPerSource);
        }
        this.permitsPerSource = permitsPerSource;
        Map<String, Integer> availableInit = new LinkedHashMap<>();
        Map<String, Integer> waitingInit = new LinkedHashMap<>();
        for (String name : sourceNames) {
            Objects.requireNonNull(name, "sourceName must not be null");
            availableInit.put(name, permitsPerSource);
            waitingInit.put(name, 0);
        }
        this.available = availableInit;
        this.waiting = waitingInit;
    }

    /** Blocking, interruptible. The permit is not consumed if interrupted while parked. */
    void acquire(String sourceName) throws InterruptedException {
        synchronized (lock) {
            requireKnown(sourceName);
            waiting.merge(sourceName, 1, Integer::sum);
            try {
                while (available.get(sourceName) == 0) {
                    lock.wait();
                }
            } finally {
                waiting.merge(sourceName, -1, Integer::sum);
            }
            available.merge(sourceName, -1, Integer::sum);
        }
    }

    /** @throws IllegalStateException on over-release (a release without a matching acquire) */
    void release(String sourceName) {
        synchronized (lock) {
            requireKnown(sourceName);
            if (available.get(sourceName) == permitsPerSource) {
                throw new IllegalStateException(
                        "release() without a matching acquire() for source: " + sourceName);
            }
            available.merge(sourceName, 1, Integer::sum);
            lock.notifyAll();
        }
    }

    int availableForTest(String sourceName) {
        synchronized (lock) {
            requireKnown(sourceName);
            return available.get(sourceName);
        }
    }

    int waitingForTest(String sourceName) {
        synchronized (lock) {
            requireKnown(sourceName);
            return waiting.get(sourceName);
        }
    }

    /** {@code notifyAll()} with no state change — proves the acquire guard is a while, not an if. */
    void spuriousWakeupForTest() {
        synchronized (lock) {
            lock.notifyAll();
        }
    }

    private void requireKnown(String sourceName) {
        if (!available.containsKey(sourceName)) {
            throw new IllegalArgumentException("unknown source name: " + sourceName);
        }
    }
}
