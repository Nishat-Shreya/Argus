package com.argus.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pure value-type validation for the db package's record types — no database, no I/O
 * (plan §6.1). These pin the compact-constructor rules so a later refactor cannot quietly
 * relax them.
 */
class ValueTypeTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void runningScanHasNoFinishTime() {
        NewScan scan = NewScan.running("example.com", NOW);
        assertEquals(ScanStatus.RUNNING, scan.status());
        assertNull(scan.finishedAt());
    }

    @Test
    void finishedScanRequiresATerminalStatus() {
        assertThrows(IllegalArgumentException.class,
                () -> NewScan.finished("example.com", NOW, NOW.plusSeconds(1), ScanStatus.RUNNING));
    }

    @Test
    void scanRejectsNullOrBlankTarget() {
        assertThrows(NullPointerException.class, () -> NewScan.running(null, NOW));
        assertThrows(IllegalArgumentException.class, () -> NewScan.running("", NOW));
        assertThrows(IllegalArgumentException.class, () -> NewScan.running("   ", NOW));
    }

    @Test
    void scanRejectsFinishTimeBeforeStartTime() {
        assertThrows(IllegalArgumentException.class, () -> NewScan.finished(
                "example.com", NOW, NOW.minusSeconds(1), ScanStatus.COMPLETED));
    }

    @Test
    void scanTruncatesInstantsToMilliseconds() {
        Instant withNanos = NOW.plusNanos(123_456);
        NewScan scan = NewScan.running("example.com", withNanos);
        assertEquals(withNanos.truncatedTo(ChronoUnit.MILLIS), scan.startedAt());
    }

    @Test
    void scanStatusTerminalityIsExplicit() {
        assertFalse(ScanStatus.RUNNING.isTerminal());
        assertTrue(ScanStatus.COMPLETED.isTerminal());
        assertTrue(ScanStatus.FAILED.isTerminal());
        assertTrue(ScanStatus.CANCELLED.isTerminal());
    }

    @Test
    void portFindingCarriesHostPortAndState() {
        NewFinding finding = NewFinding.port("10.0.0.1", 443, "OPEN");
        assertEquals(FindingType.PORT, finding.type());
        assertEquals("10.0.0.1", finding.subject());
        assertEquals(443, finding.port());
        assertEquals("OPEN", finding.state());
    }

    @Test
    void subdomainFindingHasNoPortOrState() {
        NewFinding finding = NewFinding.subdomain("a.example.com");
        assertNull(finding.port());
        assertNull(finding.state());
    }

    @Test
    void subdomainFindingKeepsTheWildcardPrefix() {
        NewFinding finding = NewFinding.subdomain("*.example.com");
        assertEquals("*.example.com", finding.subject());
    }

    @Test
    void portFindingRejectsOutOfRangePortOrBlankState() {
        assertThrows(IllegalArgumentException.class, () -> NewFinding.port("host", 0, "OPEN"));
        assertThrows(IllegalArgumentException.class, () -> NewFinding.port("host", 65536, "OPEN"));
        assertThrows(IllegalArgumentException.class, () -> NewFinding.port("host", 80, ""));
    }

    @Test
    void canonicalConstructorRejectsMismatchedTypeAndColumns() {
        assertThrows(IllegalArgumentException.class,
                () -> new NewFinding(FindingType.SUBDOMAIN, "x", 80, null));
        assertThrows(IllegalArgumentException.class,
                () -> new NewFinding(FindingType.PORT, "x", null, null));
    }

    @Test
    void scanSessionFindingsAreUnmodifiable() {
        ScanRecord scanRecord = new ScanRecord(
                1L, "example.com", NOW, null, ScanStatus.RUNNING);
        List<FindingRecord> mutable = new ArrayList<>();
        mutable.add(new FindingRecord(1L, 1L, FindingType.SUBDOMAIN, "a.example.com", null, null));
        ScanSession session = new ScanSession(scanRecord, mutable);

        mutable.add(new FindingRecord(2L, 1L, FindingType.SUBDOMAIN, "b.example.com", null, null));
        assertEquals(1, session.findings().size());

        assertThrows(UnsupportedOperationException.class, () -> session.findings().add(
                new FindingRecord(3L, 1L, FindingType.SUBDOMAIN, "c.example.com", null, null)));
    }
}
