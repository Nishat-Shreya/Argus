package com.argus.db;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class NewIntelResultTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void rejectsNullSummary() {
        assertThrows(NullPointerException.class, () -> new NewIntelResult(null, false, NOW));
    }

    @Test
    void rejectsBlankSummary() {
        assertThrows(IllegalArgumentException.class, () -> new NewIntelResult("  ", false, NOW));
    }

    @Test
    void rejectsNullCreatedAt() {
        assertThrows(NullPointerException.class, () -> new NewIntelResult("ok", false, null));
    }
}
