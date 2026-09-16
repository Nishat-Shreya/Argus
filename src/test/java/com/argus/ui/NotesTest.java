package com.argus.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.argus.core.FindingNote;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Plan §6.5: every wording, formatting and grouping rule for the findings-detail screen. */
class NotesTest {

    private static final ZoneId FIXED_ZONE = ZoneId.of("UTC");
    private static final Instant FIXED_INSTANT = Instant.parse("2026-01-01T14:32:07Z");

    @Test
    void noteCountLabelWording() {
        assertEquals("", Notes.noteCountLabel(0));
        assertEquals("1 note", Notes.noteCountLabel(1));
        assertEquals("4 notes", Notes.noteCountLabel(4));
    }

    @Test
    void entriesFormatTimestampsAtAFixedZoneAndPreserveOrderAndId() {
        FindingNote first = new FindingNote(1L, 10L, "first", FIXED_INSTANT);
        FindingNote second = new FindingNote(2L, 10L, "second", FIXED_INSTANT.plusSeconds(3600));

        List<NoteEntry> entries = Notes.entries(List.of(first, second), FIXED_ZONE);

        assertEquals(2, entries.size());
        assertEquals(1L, entries.get(0).noteId());
        assertEquals("2026-01-01 14:32:07", entries.get(0).timestamp());
        assertEquals("first", entries.get(0).body());
        assertEquals(2L, entries.get(1).noteId());
        assertEquals("2026-01-01 15:32:07", entries.get(1).timestamp());
    }

    @Test
    void entriesReadsNoClockAndNoZoneOfItsOwn() {
        FindingNote note = new FindingNote(1L, 10L, "note", FIXED_INSTANT);
        List<NoteEntry> utc = Notes.entries(List.of(note), ZoneId.of("UTC"));
        List<NoteEntry> plusFive = Notes.entries(List.of(note), ZoneId.of("+05:00"));
        assertFalse(utc.get(0).timestamp().equals(plusFive.get(0).timestamp()));
    }

    @Test
    void byFindingGroupsPreservesOrderAndIsImmutable() {
        FindingNote a1 = new FindingNote(1L, 10L, "a1", FIXED_INSTANT);
        FindingNote a2 = new FindingNote(2L, 10L, "a2", FIXED_INSTANT.plusSeconds(1));
        FindingNote b1 = new FindingNote(3L, 20L, "b1", FIXED_INSTANT);

        Map<Long, List<FindingNote>> grouped = Notes.byFinding(List.of(a1, a2, b1));

        assertEquals(2, grouped.size());
        assertEquals(List.of(a1, a2), grouped.get(10L));
        assertEquals(List.of(b1), grouped.get(20L));
        assertThrows(UnsupportedOperationException.class,
                () -> grouped.put(99L, List.of()));
    }

    @Test
    void byFindingOfAnEmptyListIsAnEmptyMap() {
        assertTrue(Notes.byFinding(List.of()).isEmpty());
    }

    @Test
    void hiddenNoteWording() {
        assertEquals("", Notes.hiddenNote(0));
        assertEquals("3 scans hidden — only completed scans can be annotated", Notes.hiddenNote(3));
    }

    @Test
    void emptyNotesTextIsExact() {
        assertEquals("no notes on this finding yet", Notes.emptyNotesText());
    }

    @Test
    void detailLinesForAProbeRow() {
        NoteRow probe = new NoteRow(1L, "port", "host.example.com", "443", "open");
        List<String> lines = Notes.detailLines(probe);
        assertEquals(List.of("type: port", "subject: host.example.com", "port: 443",
                "state: open"), lines);
    }

    @Test
    void detailLinesForASubdomainRowRenderBlankPortAndStateAsAnEmDash() {
        NoteRow subdomain = new NoteRow(2L, "subdomain", "a.example.com", "", "");
        List<String> lines = Notes.detailLines(subdomain);
        assertEquals(List.of("type: subdomain", "subject: a.example.com", "port: —", "state: —"),
                lines);
    }

    @Test
    void shouldCollapseOnFocusLost() {
        assertTrue(Notes.shouldCollapseOnFocusLost(""));
        assertTrue(Notes.shouldCollapseOnFocusLost("   "));
        assertFalse(Notes.shouldCollapseOnFocusLost("x"));
    }
}
