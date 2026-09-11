package com.argus.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Bounded producer-consumer pipeline between scan worker threads and a draining consumer.
 *
 * Implemented with an intrinsic monitor — synchronized + Object.wait()/notifyAll() with a
 * guarded while loop on both sides — deliberately, not with java.util.concurrent.BlockingQueue
 * (architecture invariant 4). Do not "simplify" this class into an ArrayBlockingQueue wrapper.
 *
 * Thread-safe for any number of producers and consumers. Findings are FIFO per producer;
 * no ordering is defined across producers, and none is needed (architecture.md: the contract
 * is no-lost/no-duplicated, which is order-independent).
 *
 * notifyAll() is used exclusively; notify() never appears in this class. One monitor carries
 * two distinct wait conditions (not-full, not-empty) plus the close broadcast, so a
 * single-target notify() could hand the wakeup to a thread whose condition is still false,
 * which then re-waits and drops the signal — the classic lost-wakeup with mixed waiter kinds
 * (SEI CERT THI02-J). notifyAll() on every state change that could satisfy any waiter is what
 * keeps that impossible.
 *
 * Lifecycle: construct -> N producers publish -> the coordinator that started them closes ->
 * consumers drain the remainder and observe end-of-stream. The canonical shutdown ordering
 * (owned by the coordinator, not by this class, which creates no thread and owns no
 * ExecutorService):
 *
 *   1. scanner.cancel()                                     // optional, user-cancelled
 *   2. pool.shutdown() / awaitTermination() / shutdownNow()  // invariant 6
 *   3. pipeline.close()                                      // ONLY after every producer ends
 *   4. consumer drains the remainder; take() returns Optional.empty(); its loop exits
 *   5. consumerThread.join(timeout)                          // no thread leak
 *
 * close() before every producer has terminated is a usage error: a producer's next publish()
 * throws IllegalStateException, which is the correct loud signal for a mis-ordered shutdown,
 * not something this class should soften into a silent drop.
 */
public final class ScanPipeline<T> implements ScanSink<T>, ScanFeed<T>, AutoCloseable {

    public static final int DEFAULT_CAPACITY = 1024;

    private final Object lock = new Object();

    private final ArrayDeque<T> items = new ArrayDeque<>();   // @GuardedBy("lock")
    private boolean closed;                                   // @GuardedBy("lock")
    private long publishedCount;                               // @GuardedBy("lock")
    private long consumedCount;                                 // @GuardedBy("lock")

    private final int capacity;                                // immutable

    public ScanPipeline() {
        this(DEFAULT_CAPACITY);
    }

    public ScanPipeline(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be >= 1: " + capacity);
        }
        this.capacity = capacity;
    }

    public int capacity() {
        return capacity;
    }

    // --- ScanSink ---

    @Override
    public void publish(T item) throws InterruptedException {
        Objects.requireNonNull(item, "item");
        synchronized (lock) {
            while (items.size() >= capacity && !closed) {
                lock.wait();                       // releases the monitor; re-tests on wake
            }
            if (closed) {
                throw new IllegalStateException("pipeline is closed");
            }
            items.addLast(item);
            publishedCount++;                      // compound op, under the lock — never volatile
            lock.notifyAll();                      // a consumer may be parked on not-empty
        }
    }

    // --- ScanFeed ---

    @Override
    public Optional<T> take() throws InterruptedException {
        synchronized (lock) {
            while (items.isEmpty() && !closed) {
                lock.wait();
            }
            if (items.isEmpty()) {                 // closed AND drained -> end of stream
                return Optional.empty();
            }
            T item = items.removeFirst();
            consumedCount++;
            lock.notifyAll();                      // a producer may be parked on not-full
            return Optional.of(item);
        }
    }

    @Override
    public List<T> drainAvailable(int maxItems) {
        if (maxItems < 1) {
            throw new IllegalArgumentException("maxItems must be >= 1: " + maxItems);
        }
        synchronized (lock) {
            List<T> batch = new ArrayList<>();
            while (batch.size() < maxItems && !items.isEmpty()) {
                batch.add(items.removeFirst());
            }
            if (!batch.isEmpty()) {
                consumedCount += batch.size();
                lock.notifyAll();                  // producers may be parked on not-full
            }
            return List.copyOf(batch);
        }
    }

    @Override
    public boolean isDrained() {
        synchronized (lock) {
            return closed && items.isEmpty();
        }
    }

    @Override
    public int size() {
        synchronized (lock) {
            return items.size();
        }
    }

    /**
     * Signals "no more items will be produced". Idempotent, safe from any thread, never
     * blocks, never discards already-queued findings. Releases every parked consumer
     * (they see end-of-stream once the queue empties) and every parked producer (their
     * publish fails with IllegalStateException) — this is what makes deadlock impossible
     * when one side goes away.
     */
    @Override
    public void close() {
        synchronized (lock) {
            if (closed) {
                return;                            // idempotent
            }
            closed = true;
            lock.notifyAll();                      // wakes EVERY parked consumer AND producer
        }
    }

    public boolean isClosed() {
        synchronized (lock) {
            return closed;
        }
    }

    // --- package-private test seams ---

    /** Findings ever accepted by publish(). Guarded by the same lock; never a volatile counter. */
    long publishedCount() {
        synchronized (lock) {
            return publishedCount;
        }
    }

    /** Findings ever handed out by take()/drainAvailable(). */
    long consumedCount() {
        synchronized (lock) {
            return consumedCount;
        }
    }

    /**
     * Test-only: performs a notifyAll() with NO state change, i.e. an artificial spurious
     * wakeup. Exists so that ScanPipelineGuardedWaitTest can prove the wait guards are
     * `while` loops and not `if` statements. Not part of the public API.
     */
    void spuriousWakeupForTest() {
        synchronized (lock) {
            lock.notifyAll();
        }
    }
}
