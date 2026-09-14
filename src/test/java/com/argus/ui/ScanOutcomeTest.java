package com.argus.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.argus.core.ScanCompletion;
import com.argus.core.ScanRun;
import com.argus.core.Subdomain;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** T8's driver: {@link ScanOutcome}'s derived accessors (§3.8). */
class ScanOutcomeTest {

    private static final Instant STARTED = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant FINISHED = Instant.parse("2026-01-01T00:05:00Z");

    @Test
    void resultAndFindingsDeliveredAndSavedDeriveFromTheComponents() {
        ScanRun run = new ScanRun("example.com", STARTED, FINISHED, ScanCompletion.COMPLETED,
                List.of(new Subdomain("a.example.com"), new Subdomain("b.example.com")));
        ScanOutcome outcome = new ScanOutcome(run, 0, 42L);

        assertEquals(ScanCompletion.COMPLETED, outcome.result());
        assertEquals(2, outcome.findingsDelivered());
        assertTrue(outcome.saved());
        assertEquals(42L, outcome.savedScanId());
    }

    @Test
    void savedIsFalseWhenSavedScanIdIsNull() {
        ScanRun run = new ScanRun(
                "example.com", STARTED, FINISHED, ScanCompletion.COMPLETED_WITH_ERRORS, List.of());
        ScanOutcome outcome = new ScanOutcome(run, 1, null);

        assertEquals(ScanCompletion.COMPLETED_WITH_ERRORS, outcome.result());
        assertEquals(0, outcome.findingsDelivered());
        assertFalse(outcome.saved());
        assertEquals(1, outcome.failedJobs());
    }
}
