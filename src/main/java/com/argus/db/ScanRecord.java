package com.argus.db;

import java.time.Instant;

/** A persisted {@code scans} row. {@code id} is the SQLite rowid. */
public record ScanRecord(long id, String target, Instant startedAt, Instant finishedAt,
        ScanStatus status) {

    public boolean isRunning() {
        return status == ScanStatus.RUNNING;
    }
}
