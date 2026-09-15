package com.argus.ui;

import com.argus.core.ScanAlert;

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

    void deliver(ScanAlert alert);

    @Override
    void close();
}
