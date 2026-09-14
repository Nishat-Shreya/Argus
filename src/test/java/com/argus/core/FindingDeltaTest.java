package com.argus.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** {@link FindingDelta} — the projection of {@code FindingChange} (plan §3.1). */
class FindingDeltaTest {

    @Test
    void preservesBothSides() {
        FindingSnapshot baseline = new FindingSnapshot(1L, "PORT", "host", 80, "OPEN");
        FindingSnapshot current = new FindingSnapshot(2L, "PORT", "host", 80, "FILTERED");

        FindingDelta delta = new FindingDelta(baseline, current);

        assertEquals(baseline, delta.baseline());
        assertEquals(current, delta.current());
    }

    @Test
    void aNullBaselineThrows() {
        FindingSnapshot current = new FindingSnapshot(2L, "PORT", "host", 80, "FILTERED");
        assertThrows(NullPointerException.class, () -> new FindingDelta(null, current));
    }

    @Test
    void aNullCurrentThrows() {
        FindingSnapshot baseline = new FindingSnapshot(1L, "PORT", "host", 80, "OPEN");
        assertThrows(NullPointerException.class, () -> new FindingDelta(baseline, null));
    }
}
