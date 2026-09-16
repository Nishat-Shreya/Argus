package com.argus.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.argus.db.AnnotationRecord;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** THE db/core annotation mapping (plan §3.2): field-for-field, order preserved. */
class FindingNotesTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void mapsEveryFieldOfOneRecord() {
        AnnotationRecord record = new AnnotationRecord(1L, 2L, "false positive", NOW);
        FindingNote note = FindingNotes.of(record);
        assertEquals(1L, note.id());
        assertEquals(2L, note.findingId());
        assertEquals("false positive", note.body());
        assertEquals(NOW, note.createdAt());
    }

    @Test
    void rejectsNullRecord() {
        assertThrows(NullPointerException.class, () -> FindingNotes.of((AnnotationRecord) null));
    }

    @Test
    void mapsAListPreservingOrder() {
        AnnotationRecord first = new AnnotationRecord(1L, 5L, "first", NOW);
        AnnotationRecord second = new AnnotationRecord(2L, 5L, "second", NOW.plusSeconds(1));

        List<FindingNote> notes = FindingNotes.of(List.of(first, second));

        assertEquals(2, notes.size());
        assertEquals("first", notes.get(0).body());
        assertEquals("second", notes.get(1).body());
    }

    @Test
    void mapsAnEmptyListToAnEmptyList() {
        assertTrue(FindingNotes.of(List.<AnnotationRecord>of()).isEmpty());
    }

    @Test
    void rejectsNullList() {
        assertThrows(NullPointerException.class, () -> FindingNotes.of((List<AnnotationRecord>) null));
    }
}
