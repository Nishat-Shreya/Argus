package com.argus.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/** The projection record for one persisted annotation (plan §3.2). */
class FindingNoteTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void preservesEveryComponent() {
        FindingNote note = new FindingNote(1L, 2L, "false positive", NOW);
        assertEquals(1L, note.id());
        assertEquals(2L, note.findingId());
        assertEquals("false positive", note.body());
        assertEquals(NOW, note.createdAt());
    }

    @Test
    void rejectsNullBody() {
        assertThrows(NullPointerException.class, () -> new FindingNote(1L, 2L, null, NOW));
    }

    @Test
    void rejectsNullCreatedAt() {
        assertThrows(NullPointerException.class, () -> new FindingNote(1L, 2L, "note", null));
    }
}
