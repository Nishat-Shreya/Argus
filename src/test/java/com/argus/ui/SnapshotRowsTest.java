package com.argus.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.argus.core.FindingSnapshot;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Section 7.2: {@code SnapshotRows} — toolkit-free mapping from persisted
 *  {@code FindingSnapshot} onto {@link FindingRow} (plan §3.4). */
class SnapshotRowsTest {

    @Test
    void mapsAProbeToLowercaseTypePortStringAndLowercaseState() {
        FindingSnapshot probe = new FindingSnapshot(1L, "PORT", "host.example.com", 443, "OPEN");

        List<FindingRow> rows = SnapshotRows.of(List.of(probe));

        assertEquals(1, rows.size());
        assertEquals("port", rows.get(0).type());
        assertEquals("host.example.com", rows.get(0).subject());
        assertEquals("443", rows.get(0).port());
        assertEquals("open", rows.get(0).state());
    }

    @Test
    void mapsANonProbeToBlankPortAndBlankState() {
        FindingSnapshot subdomain = new FindingSnapshot(1L, "SUBDOMAIN", "a.example.com", null, null);

        List<FindingRow> rows = SnapshotRows.of(List.of(subdomain));

        assertEquals("", rows.get(0).port());
        assertEquals("", rows.get(0).state());
    }

    @Test
    void ordersProbesByPortNUMERICALLYNotAsStrings() {
        FindingSnapshot p8080 = new FindingSnapshot(1L, "PORT", "host", 8080, "OPEN");
        FindingSnapshot p80 = new FindingSnapshot(2L, "PORT", "host", 80, "OPEN");
        FindingSnapshot p443 = new FindingSnapshot(3L, "PORT", "host", 443, "OPEN");

        List<FindingRow> rows = SnapshotRows.of(List.of(p8080, p80, p443));

        assertEquals("80", rows.get(0).port());
        assertEquals("443", rows.get(1).port());
        assertEquals("8080", rows.get(2).port());
    }

    @Test
    void ordersProbesBySubjectBeforePort() {
        FindingSnapshot bHost = new FindingSnapshot(1L, "PORT", "b.example.com", 22, "OPEN");
        FindingSnapshot aHostHighPort = new FindingSnapshot(2L, "PORT", "a.example.com", 8080, "OPEN");
        FindingSnapshot aHostLowPort = new FindingSnapshot(3L, "PORT", "a.example.com", 22, "OPEN");

        List<FindingRow> rows = SnapshotRows.of(List.of(bHost, aHostHighPort, aHostLowPort));

        assertEquals("a.example.com", rows.get(0).subject());
        assertEquals("22", rows.get(0).port());
        assertEquals("a.example.com", rows.get(1).subject());
        assertEquals("8080", rows.get(1).port());
        assertEquals("b.example.com", rows.get(2).subject());
    }

    @Test
    void putsProbesBeforeNonProbesAndOrdersNonProbesByTypeThenSubject() {
        FindingSnapshot probe = new FindingSnapshot(1L, "PORT", "host", 80, "OPEN");
        FindingSnapshot bSubdomain = new FindingSnapshot(2L, "SUBDOMAIN", "b.example.com", null, null);
        FindingSnapshot aSubdomain = new FindingSnapshot(3L, "SUBDOMAIN", "a.example.com", null, null);

        List<FindingRow> rows = SnapshotRows.of(List.of(bSubdomain, aSubdomain, probe));

        assertEquals("port", rows.get(0).type());
        assertEquals("a.example.com", rows.get(1).subject());
        assertEquals("b.example.com", rows.get(2).subject());
    }

    @Test
    void isIndependentOfTheInputRowOrder() {
        FindingSnapshot a = new FindingSnapshot(1L, "PORT", "host", 80, "OPEN");
        FindingSnapshot b = new FindingSnapshot(2L, "PORT", "host", 443, "OPEN");
        FindingSnapshot c = new FindingSnapshot(3L, "SUBDOMAIN", "a.example.com", null, null);

        List<FindingRow> forward = SnapshotRows.of(List.of(a, b, c));
        List<FindingRow> shuffled = SnapshotRows.of(List.of(c, b, a));

        assertEquals(forward, shuffled);
    }

    @Test
    void anUnseenTypeTokenFlowsThroughVerbatimLowercased() {
        FindingSnapshot certificate = new FindingSnapshot(1L, "CERTIFICATE", "host", null, null);

        List<FindingRow> rows = SnapshotRows.of(List.of(certificate));

        assertEquals("certificate", rows.get(0).type());
    }

    @Test
    void anUnseenStateTokenFlowsThroughVerbatimLowercased() {
        FindingSnapshot throttled = new FindingSnapshot(1L, "PORT", "host", 80, "THROTTLED");

        List<FindingRow> rows = SnapshotRows.of(List.of(throttled));

        assertEquals("throttled", rows.get(0).state());
    }

    @Test
    void aHalfNullRowIsANonProbeAndIsNeitherDroppedNorThrown() {
        FindingSnapshot halfNull = new FindingSnapshot(1L, "PORT", "host", 80, null);

        List<FindingRow> rows = SnapshotRows.of(List.of(halfNull));

        assertEquals(1, rows.size());
        assertEquals("", rows.get(0).state());
    }

    @Test
    void anEmptyFindingListYieldsAnEmptyRowList() {
        assertTrue(SnapshotRows.of(List.of()).isEmpty());
    }

    @Test
    void rejectsNull() {
        assertThrows(NullPointerException.class, () -> SnapshotRows.of(null));
    }
}
