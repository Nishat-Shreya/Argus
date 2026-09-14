package com.argus.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.argus.db.FindingRecord;
import com.argus.db.FindingType;
import org.junit.jupiter.api.Test;

/**
 * {@link FindingChange} — one finding whose {@code state} moved between two scans (plan §6.2).
 * Self-validating: the two records MUST share a key and MUST NOT share a state, so an engine bug
 * becomes a construction failure rather than a misleading UI row.
 */
class FindingChangeTest {

    // ---- 6.2.1 ----

    @Test
    void happyPathConstructsAndKeyReturnsTheSharedKey() {
        FindingRecord baseline = portRow(1L, 10L, "host", 80, "OPEN");
        FindingRecord current = portRow(2L, 20L, "host", 80, "FILTERED");

        FindingChange change = new FindingChange(baseline, current);

        assertEquals(FindingKey.of(baseline), change.key());
        assertEquals(baseline, change.baseline());
        assertEquals(current, change.current());
    }

    // ---- 6.2.2 ----

    @Test
    void differentKeysThrowsIae() {
        FindingRecord baseline = portRow(1L, 10L, "host", 80, "OPEN");
        FindingRecord current = portRow(2L, 20L, "host", 443, "OPEN");

        assertThrows(IllegalArgumentException.class, () -> new FindingChange(baseline, current));
    }

    // ---- 6.2.3 ----

    @Test
    void equalStatesThrowsIae() {
        FindingRecord baseline = portRow(1L, 10L, "host", 80, "OPEN");
        FindingRecord current = portRow(2L, 20L, "host", 80, "OPEN");

        assertThrows(IllegalArgumentException.class, () -> new FindingChange(baseline, current));
    }

    // ---- 6.2.4 ----

    @Test
    void twoSubdomainRecordsBothNullStateThrowsIae() {
        FindingRecord baseline = subdomainRow(1L, 10L, "a.example.com");
        FindingRecord current = subdomainRow(2L, 20L, "a.example.com");

        assertThrows(IllegalArgumentException.class, () -> new FindingChange(baseline, current));
    }

    // ---- 6.2.5 ----

    @Test
    void nullBaselineThrowsNpe() {
        FindingRecord current = portRow(1L, 10L, "host", 80, "OPEN");
        assertThrows(NullPointerException.class, () -> new FindingChange(null, current));
    }

    @Test
    void nullCurrentThrowsNpe() {
        FindingRecord baseline = portRow(1L, 10L, "host", 80, "OPEN");
        assertThrows(NullPointerException.class, () -> new FindingChange(baseline, null));
    }

    // ---- fixtures ----

    private static FindingRecord portRow(long id, long scanId, String host, int port,
            String state) {
        return new FindingRecord(id, scanId, FindingType.PORT, host, port, state);
    }

    private static FindingRecord subdomainRow(long id, long scanId, String name) {
        return new FindingRecord(id, scanId, FindingType.SUBDOMAIN, name, null, null);
    }
}
