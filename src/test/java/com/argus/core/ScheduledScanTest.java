package com.argus.core;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class ScheduledScanTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void rejectsNullTarget() {
        assertThrows(NullPointerException.class,
                () -> new ScheduledScan(1L, null, 15, true, NOW, null, NOW));
    }

    @Test
    void rejectsNullCreatedAt() {
        assertThrows(NullPointerException.class,
                () -> new ScheduledScan(1L, "example.com", 15, true, null, null, NOW));
    }
}
