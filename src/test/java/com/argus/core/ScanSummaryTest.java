package com.argus.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/** T1's driver: {@link ScanSummary} — the boundary projection of a {@code ScanRecord} (plan
 *  §3.1, §7.1). No {@code com.argus.db} type reaches {@code ui} through this record. */
class ScanSummaryTest {

    private static final Instant STARTED = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant FINISHED = Instant.parse("2026-01-01T00:05:00Z");

    @Test
    void completeIsTrueForACompletedDerivedSummary() {
        ScanSummary summary = new ScanSummary(1L, "example.com", STARTED, FINISHED, "COMPLETED");
        assertTrue(summary.complete());
    }

    @Test
    void completeIsFalseForCancelled() {
        ScanSummary summary = new ScanSummary(1L, "example.com", STARTED, FINISHED, "CANCELLED");
        assertFalse(summary.complete());
    }

    @Test
    void completeIsFalseForFailed() {
        ScanSummary summary = new ScanSummary(1L, "example.com", STARTED, FINISHED, "FAILED");
        assertFalse(summary.complete());
    }

    @Test
    void completeIsFalseForRunning() {
        ScanSummary summary = new ScanSummary(1L, "example.com", STARTED, null, "RUNNING");
        assertFalse(summary.complete());
    }

    @Test
    void aNullFinishedAtIsAccepted() {
        ScanSummary summary = new ScanSummary(1L, "example.com", STARTED, null, "RUNNING");
        assertNull(summary.finishedAt());
    }

    @Test
    void aNullTargetThrows() {
        assertThrows(NullPointerException.class,
                () -> new ScanSummary(1L, null, STARTED, FINISHED, "COMPLETED"));
    }

    @Test
    void aNullStatusThrows() {
        assertThrows(NullPointerException.class,
                () -> new ScanSummary(1L, "example.com", STARTED, FINISHED, null));
    }

    @Test
    void aNullStartedAtThrows() {
        assertThrows(NullPointerException.class,
                () -> new ScanSummary(1L, "example.com", null, FINISHED, "COMPLETED"));
    }

    @Test
    void idIsPreservedAsAPlainLong() {
        ScanSummary summary = new ScanSummary(42L, "example.com", STARTED, FINISHED, "COMPLETED");
        assertEquals(42L, summary.id());
    }
}
