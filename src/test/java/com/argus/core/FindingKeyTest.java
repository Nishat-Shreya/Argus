package com.argus.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.argus.db.FindingRecord;
import com.argus.db.FindingType;
import org.junit.jupiter.api.Test;

/**
 * {@link FindingKey} — the scan-independent identity {@code (type, subject, port)} (plan §6.1).
 * §6.1.2 is the single most load-bearing assertion in the item: it is what makes cross-scan
 * matching possible at all.
 */
class FindingKeyTest {

    // ---- 6.1.1 ----

    @Test
    void ofCopiesTypeSubjectAndPortVerbatim() {
        FindingRecord record = portRow(1L, 10L, "host", 80, "OPEN");

        FindingKey key = FindingKey.of(record);

        assertEquals(FindingType.PORT, key.type());
        assertEquals("host", key.subject());
        assertEquals(80, key.port());
    }

    // ---- 6.1.2 ----

    @Test
    void differingIdAndScanIdStillProduceEqualKeysWithEqualHashCodes() {
        FindingRecord a = portRow(1L, 10L, "host", 80, "OPEN");
        FindingRecord b = portRow(2L, 20L, "host", 80, "OPEN");

        FindingKey keyA = FindingKey.of(a);
        FindingKey keyB = FindingKey.of(b);

        assertEquals(keyA, keyB);
        assertEquals(keyA.hashCode(), keyB.hashCode());
    }

    // ---- 6.1.3 ----

    @Test
    void differingStateStillProducesEqualKeys() {
        FindingRecord a = portRow(1L, 10L, "host", 80, "OPEN");
        FindingRecord b = portRow(1L, 10L, "host", 80, "FILTERED");

        assertEquals(FindingKey.of(a), FindingKey.of(b));
    }

    // ---- 6.1.4 ----

    @Test
    void subdomainRecordYieldsAKeyWithANullPortAndTwoSuchKeysAreEqual() {
        FindingRecord a = subdomainRow(1L, 10L, "a.example.com");
        FindingRecord b = subdomainRow(2L, 20L, "a.example.com");

        FindingKey keyA = FindingKey.of(a);
        FindingKey keyB = FindingKey.of(b);

        assertNull(keyA.port());
        assertEquals(keyA, keyB);
    }

    // ---- 6.1.5 ----

    @Test
    void portAndSubdomainOfTheSameSubjectAreNotEqualKeys() {
        FindingKey portKey = new FindingKey(FindingType.PORT, "host", 80);
        FindingKey subdomainKey = new FindingKey(FindingType.SUBDOMAIN, "host", null);

        assertNotEquals(portKey, subdomainKey);
    }

    // ---- 6.1.6 ----

    @Test
    void differentPortsAreNotEqualKeys() {
        FindingKey port80 = new FindingKey(FindingType.PORT, "host", 80);
        FindingKey port443 = new FindingKey(FindingType.PORT, "host", 443);

        assertNotEquals(port80, port443);
    }

    // ---- 6.1.7: pins the no-case-folding ruling (§4.4/R3) ----

    @Test
    void subjectComparisonIsCaseSensitiveNoFolding() {
        FindingKey upper = new FindingKey(FindingType.PORT, "Host", 80);
        FindingKey lower = new FindingKey(FindingType.PORT, "host", 80);

        assertNotEquals(upper, lower);
    }

    // ---- 6.1.8 ----

    @Test
    void nullTypeThrowsNpe() {
        assertThrows(NullPointerException.class, () -> new FindingKey(null, "host", 80));
    }

    @Test
    void nullSubjectThrowsNpe() {
        assertThrows(NullPointerException.class,
                () -> new FindingKey(FindingType.PORT, null, 80));
    }

    @Test
    void blankSubjectThrowsIae() {
        assertThrows(IllegalArgumentException.class,
                () -> new FindingKey(FindingType.PORT, "   ", 80));
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
