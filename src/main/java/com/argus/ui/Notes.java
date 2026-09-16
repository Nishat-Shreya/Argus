package com.argus.ui;

import com.argus.core.FindingNote;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Every wording, formatting and grouping rule for the findings-detail screen (plan §3.3). Pure,
 * toolkit-free. {@code ZoneId} is passed in, never read here — the {@code ScanChoices} /
 * {@code Timelines} contract.
 *
 * {@link #hiddenNote}'s wording lives here; the count stays {@code ScanChoices.hiddenCount} —
 * one counting authority (the {@code Reports.hiddenNote} pattern, P3-04).
 */
final class Notes {

    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private Notes() {
    }

    /** Order preserved; {@code noteId} carried through; timestamps formatted at {@code zone}. */
    static List<NoteEntry> entries(List<FindingNote> notes, ZoneId zone) {
        Objects.requireNonNull(notes, "notes");
        Objects.requireNonNull(zone, "zone");
        List<NoteEntry> entries = new ArrayList<>();
        for (FindingNote note : notes) {
            String timestamp = TIMESTAMP_FORMAT.format(note.createdAt().atZone(zone));
            entries.add(new NoteEntry(note.id(), timestamp, note.body()));
        }
        return List.copyOf(entries);
    }

    /** Groups by {@code findingId}, preserving each finding's note order. Immutable. */
    static Map<Long, List<FindingNote>> byFinding(List<FindingNote> notes) {
        Objects.requireNonNull(notes, "notes");
        Map<Long, List<FindingNote>> grouped = new LinkedHashMap<>();
        for (FindingNote note : notes) {
            grouped.computeIfAbsent(note.findingId(), key -> new ArrayList<>()).add(note);
        }
        Map<Long, List<FindingNote>> immutable = new LinkedHashMap<>();
        for (Map.Entry<Long, List<FindingNote>> entry : grouped.entrySet()) {
            immutable.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        return Map.copyOf(immutable);
    }

    /** {@code "" | "1 note" | "N notes"}. */
    static String noteCountLabel(int count) {
        if (count == 0) {
            return "";
        }
        if (count == 1) {
            return "1 note";
        }
        return count + " notes";
    }

    /** {@code ""} when nothing is hidden, else the exact text {@code hiddenLabel} shows. Reworded
     *  from the diff/report/timeline screens' phrasing (plan R8): a {@code CANCELLED} scan's
     *  recorded findings are real and annotatable in principle, but this screen reuses
     *  {@code ScanChoices.selectable} byte-identical for consistency, so only completed scans are
     *  actually selectable here. */
    static String hiddenNote(int hiddenCount) {
        if (hiddenCount == 0) {
            return "";
        }
        return hiddenCount + " scans hidden — only completed scans can be annotated";
    }

    static String emptyNotesText() {
        return "no notes on this finding yet";
    }

    /** The raw-data pane's lines. Blank port/state render as {@code "—"}, never as an empty
     *  line. */
    static List<String> detailLines(NoteRow row) {
        Objects.requireNonNull(row, "row");
        List<String> lines = new ArrayList<>();
        lines.add("type: " + row.type());
        lines.add("subject: " + row.subject());
        lines.add("port: " + emDashIfBlank(row.port()));
        lines.add("state: " + emDashIfBlank(row.state()));
        return List.copyOf(lines);
    }

    /** True iff the field is blank — the click-to-expand collapse-on-focus-lost rule (plan
     *  §4.3): an operator who clicks away mid-sentence does not have their text folded away. */
    static boolean shouldCollapseOnFocusLost(String fieldText) {
        return fieldText == null || fieldText.isBlank();
    }

    private static String emDashIfBlank(String value) {
        return value.isEmpty() ? "—" : value;
    }
}
