package com.argus.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.argus.db.NewScan;
import com.argus.db.ScanStatus;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** T5's driver: {@link NewScans} — the {@code ScanCompletion -&gt; ScanStatus} mapping (§3.6). */
class NewScansTest {

    private static final Instant STARTED = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant FINISHED = Instant.parse("2026-01-01T00:05:00Z");

    @Test
    void completedMapsToCompleted() {
        assertEquals(ScanStatus.COMPLETED, NewScans.statusOf(ScanCompletion.COMPLETED));
    }

    @Test
    void cancelledMapsToCancelled() {
        assertEquals(ScanStatus.CANCELLED, NewScans.statusOf(ScanCompletion.CANCELLED));
    }

    @Test
    void completedWithErrorsMapsToFailed() {
        assertEquals(ScanStatus.FAILED, NewScans.statusOf(ScanCompletion.COMPLETED_WITH_ERRORS));
    }

    @Test
    void statusOfIsExhaustiveAndNeverProducesRunning() {
        for (ScanCompletion completion : ScanCompletion.values()) {
            assertNotEquals(ScanStatus.RUNNING, NewScans.statusOf(completion));
        }
    }

    @Test
    void ofCarriesTargetAndTimestampsThrough() {
        ScanRun run = new ScanRun("example.com", STARTED, FINISHED, ScanCompletion.COMPLETED,
                List.of());

        NewScan scan = NewScans.of(run);

        assertEquals("example.com", scan.target());
        assertEquals(STARTED, scan.startedAt());
        assertEquals(FINISHED, scan.finishedAt());
        assertEquals(ScanStatus.COMPLETED, scan.status());
        assertNotEquals(ScanStatus.RUNNING, scan.status());
    }
}
