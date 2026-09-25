package com.argus.ui;

import com.argus.core.ScanAlert;
import com.argus.core.ScanCompletionNotice;

/**
 * One place a {@link ScanAlert} can be delivered. The sibling of P3-02's {@link DesktopNotifier},
 * one level up: {@code DesktopNotifier} takes an already-composed balloon, an {@code
 * AlertChannel} takes the alert itself and composes for its own medium.
 *
 * THREADING CONTRACT (binding): {@link #deliver(ScanAlert)} is called ON THE FX APPLICATION
 * THREAD and MUST return promptly and MUST NOT throw. A channel that performs I/O moves that
 * I/O to its own background thread (invariant 3). {@link #close()} is called from
 * {@code App.stop()}, also on the FX thread. Idempotent, never throws.
 */
interface AlertChannel extends AutoCloseable {

    /** Called only when a scan found something new (webhook, desktop). */
    void deliver(ScanAlert alert);

    /**
     * Called after EVERY successfully completed scan, whether or not it found anything new. Same
     * threading contract as {@link #deliver(ScanAlert)}. A no-op by default so a channel that
     * only cares about new findings (webhook, desktop) is unaffected; only the email channel
     * overrides it.
     */
    default void scanCompleted(ScanCompletionNotice notice) {
    }

    @Override
    void close();
}
