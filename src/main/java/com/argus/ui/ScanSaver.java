package com.argus.ui;

import com.argus.core.ScanArchiveException;
import com.argus.core.ScanRun;

/**
 * How {@code ScanCoordinator} persists a finished run. One method so the coordinator can be
 * unit-tested with a recording/failing fake and so {@code ui} never names a {@code db} type.
 *
 * THREADING: called on the supervisor thread, never on the FX thread. Implementations block.
 */
@FunctionalInterface
interface ScanSaver {

    /** @return the persisted scan id, or null if this saver persists nothing */
    Long save(ScanRun run) throws ScanArchiveException;

    /** The no-op saver: what every P1-06 test gets by default, so no test writes to disk. */
    static ScanSaver none() {
        return run -> null;
    }
}
