package com.argus.db;

/**
 * Lifecycle state of a scan session. Stored as {@code name()}; the DDL does not constrain the
 * vocabulary with a closed {@code CHECK (status IN (...))} — see plan §7.6 — so a future terminal
 * status (e.g. a paused state) does not force a table rebuild.
 */
public enum ScanStatus {
    RUNNING, COMPLETED, FAILED, CANCELLED;

    /** True for every state except {@code RUNNING} — i.e. {@code finishedAt} must be set. */
    public boolean isTerminal() {
        return this != RUNNING;
    }
}
