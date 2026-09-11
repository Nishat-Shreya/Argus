package com.argus.core;

/**
 * The producer half of a scan pipeline. Scan workers receive this, never the pipeline itself:
 * a producer can publish and nothing else — it cannot take items away from the consumer and
 * cannot close the stream.
 *
 * Implementations are thread-safe; many producer threads may publish concurrently.
 * BLOCKING: publish() may park the calling thread. Never call it on the FX Application
 * Thread (invariant 3).
 */
public interface ScanSink<T> {

    /**
     * Appends one finding, blocking while the pipeline is at capacity (backpressure).
     *
     * @throws NullPointerException     if item is null
     * @throws IllegalStateException    if the pipeline is closed, or is closed while parked
     * @throws InterruptedException     if the calling thread is interrupted while parked;
     *                                  the item is NOT published
     */
    void publish(T item) throws InterruptedException;
}
