package com.argus.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Plan §6.1: the value type inserted by {@link AnnotationDao#insert}. */
class NewAnnotationTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void happyPathPreservesBodyAndCreatedAt() {
        NewAnnotation annotation = new NewAnnotation("false positive", NOW);
        assertEquals("false positive", annotation.body());
        assertEquals(NOW, annotation.createdAt());
    }

    @Test
    void rejectsNullBody() {
        assertThrows(NullPointerException.class, () -> new NewAnnotation(null, NOW));
    }

    @Test
    void rejectsNullCreatedAt() {
        assertThrows(NullPointerException.class, () -> new NewAnnotation("note", null));
    }

    @Test
    void rejectsEmptyBody() {
        assertThrows(IllegalArgumentException.class, () -> new NewAnnotation("", NOW));
    }

    @Test
    void rejectsWhitespaceOnlyBody() {
        assertThrows(IllegalArgumentException.class, () -> new NewAnnotation("   \n\t ", NOW));
    }

    @Test
    void truncatesCreatedAtToMillis() {
        Instant withNanos = NOW.plusNanos(123_456);
        NewAnnotation annotation = new NewAnnotation("note", withNanos);
        assertEquals(NOW.plusMillis(0), annotation.createdAt());
        assertEquals(withNanos.toEpochMilli(), annotation.createdAt().toEpochMilli());
        assertEquals(0, annotation.createdAt().getNano() % 1_000_000);
    }

    @Test
    void recordsWithEqualComponentsAreEqual() {
        NewAnnotation a = new NewAnnotation("note", NOW);
        NewAnnotation b = new NewAnnotation("note", NOW);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }
}
