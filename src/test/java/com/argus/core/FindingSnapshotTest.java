package com.argus.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** {@link FindingSnapshot} — the boundary projection of a {@code FindingRecord} (plan §3.1). */
class FindingSnapshotTest {

    @Test
    void preservesEveryComponent() {
        FindingSnapshot snapshot = new FindingSnapshot(1L, "PORT", "host", 80, "OPEN");

        assertEquals(1L, snapshot.id());
        assertEquals("PORT", snapshot.type());
        assertEquals("host", snapshot.subject());
        assertEquals(80, snapshot.port());
        assertEquals("OPEN", snapshot.state());
    }

    @Test
    void portAndStateMayBeNullForASubdomain() {
        FindingSnapshot snapshot = new FindingSnapshot(1L, "SUBDOMAIN", "a.example.com", null, null);

        assertNull(snapshot.port());
        assertNull(snapshot.state());
    }

    @Test
    void aNullTypeThrows() {
        assertThrows(NullPointerException.class,
                () -> new FindingSnapshot(1L, null, "host", 80, "OPEN"));
    }

    @Test
    void aNullSubjectThrows() {
        assertThrows(NullPointerException.class,
                () -> new FindingSnapshot(1L, "PORT", null, 80, "OPEN"));
    }
}
