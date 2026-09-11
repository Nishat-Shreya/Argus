package com.argus.core;

import java.util.List;
import java.util.Optional;

/**
 * The consumer half of a scan pipeline — this is the type com.argus.ui holds (P1-06).
 * Zero JavaFX by construction (invariant 2); the Platform.runLater() call lives in ui.
 *
 * Recommended UI pattern (implemented in P1-06, NOT here):
 *
 *   // on a background thread, never on the FX Application Thread
 *   while (true) {
 *       Optional&lt;T&gt; first = feed.take();          // parks until an item or end-of-stream
 *       if (first.isEmpty()) break;               // end of stream
 *       List&lt;T&gt; batch = new ArrayList&lt;&gt;();
 *       batch.add(first.get());
 *       batch.addAll(feed.drainAvailable(BATCH_LIMIT - 1));   // coalesce the burst
 *       Platform.runLater(() -&gt; table.getItems().addAll(batch));
 *   }
 */
public interface ScanFeed<T> {

    /**
     * BLOCKING. Removes and returns the next finding, parking while the pipeline is empty
     * and open. Returns an empty Optional if and only if the pipeline is closed AND drained
     * — the end-of-stream signal, which is stable and repeatable.
     *
     * @throws InterruptedException if interrupted while parked; nothing is consumed
     */
    Optional<T> take() throws InterruptedException;

    /**
     * NON-BLOCKING. Removes up to {@code maxItems} findings that are already queued and
     * returns them in FIFO order. Returns an empty list if nothing is ready — an empty list
     * is NOT an end-of-stream signal; use {@link #isDrained()}.
     *
     * @throws IllegalArgumentException if maxItems &lt; 1
     */
    List<T> drainAvailable(int maxItems);

    /** True once the pipeline is closed and empty: no further item will ever be produced. */
    boolean isDrained();

    /** Findings currently queued. Always {@code <= capacity}. A progress/backlog readout. */
    int size();
}
