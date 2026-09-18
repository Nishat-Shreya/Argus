package com.argus.db;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class NewScheduledScanTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void rejectsNullTarget() {
        assertThrows(NullPointerException.class,
                () -> new NewScheduledScan(null, "15", NOW, NOW));
    }

    @Test
    void rejectsBlankTarget() {
        assertThrows(IllegalArgumentException.class,
                () -> new NewScheduledScan("  ", "15", NOW, NOW));
    }

    @Test
    void rejectsBlankCronExpression() {
        assertThrows(IllegalArgumentException.class,
                () -> new NewScheduledScan("example.com", " ", NOW, NOW));
    }

    @Test
    void rejectsNullCreatedAt() {
        assertThrows(NullPointerException.class,
                () -> new NewScheduledScan("example.com", "15", null, NOW));
    }

    @Test
    void rejectsNullNextRunAt() {
        assertThrows(NullPointerException.class,
                () -> new NewScheduledScan("example.com", "15", NOW, null));
    }
}
