package com.argus.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.argus.db.ScanRecord;
import com.argus.db.ScanStatus;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** {@link ScanSummaries} — the package-private {@code ScanRecord -&gt; ScanSummary} mapper
 *  (plan §3.2). */
class ScanSummariesTest {

    private static final Instant STARTED = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant FINISHED = Instant.parse("2026-01-01T00:05:00Z");

    @Test
    void roundTripsEveryComponentWithStatusAsName() {
        ScanRecord record = new ScanRecord(7L, "example.com", STARTED, FINISHED,
                ScanStatus.COMPLETED);

        ScanSummary summary = ScanSummaries.of(record);

        assertEquals(7L, summary.id());
        assertEquals("example.com", summary.target());
        assertEquals(STARTED, summary.startedAt());
        assertEquals(FINISHED, summary.finishedAt());
        assertEquals("COMPLETED", summary.status());
    }

    @Test
    void listOverloadPreservesOrderAndReturnsAnUnmodifiableList() {
        ScanRecord first = new ScanRecord(1L, "a.example.com", STARTED, FINISHED,
                ScanStatus.COMPLETED);
        ScanRecord second = new ScanRecord(2L, "b.example.com", STARTED, FINISHED,
                ScanStatus.FAILED);

        List<ScanSummary> summaries = ScanSummaries.of(List.of(first, second));

        assertEquals(2, summaries.size());
        assertEquals(1L, summaries.get(0).id());
        assertEquals(2L, summaries.get(1).id());
        assertThrows(UnsupportedOperationException.class,
                () -> summaries.add(ScanSummaries.of(first)));
    }

    @Test
    void aNullFinishedAtMapsWithoutThrowing() {
        ScanRecord record = new ScanRecord(1L, "example.com", STARTED, null, ScanStatus.RUNNING);

        ScanSummary summary = ScanSummaries.of(record);

        assertNull(summary.finishedAt());
        assertEquals("RUNNING", summary.status());
    }
}
