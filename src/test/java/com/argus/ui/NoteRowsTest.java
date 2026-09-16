package com.argus.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.argus.core.FindingSnapshot;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Plan §6.5: {@code NoteRows} — {@code FindingSnapshot -> NoteRow}, ordering DELEGATED to
 *  {@code SnapshotRows.ordered(...)} (the C5 extract-method, plan R1) so this screen and the
 *  Timeline/Report screens share one ordering authority. */
class NoteRowsTest {

    @Test
    void findingIdIsPreservedFromFindingSnapshotId() {
        FindingSnapshot probe = new FindingSnapshot(42L, "PORT", "host", 80, "OPEN");
        List<NoteRow> rows = NoteRows.of(List.of(probe));
        assertEquals(42L, rows.get(0).findingId());
    }

    @Test
    void agreesElementForElementWithSnapshotRowsAfterDroppingTheFindingId() {
        FindingSnapshot probe1 = new FindingSnapshot(1L, "PORT", "b.example.com", 22, "OPEN");
        FindingSnapshot probe2 = new FindingSnapshot(2L, "PORT", "a.example.com", 8080, "OPEN");
        FindingSnapshot subdomain = new FindingSnapshot(3L, "SUBDOMAIN", "a.example.com", null, null);
        List<FindingSnapshot> mixed = List.of(probe1, probe2, subdomain);

        List<NoteRow> noteRows = NoteRows.of(mixed);
        List<FindingRow> snapshotRows = SnapshotRows.of(mixed);

        assertEquals(snapshotRows.size(), noteRows.size());
        for (int i = 0; i < noteRows.size(); i++) {
            NoteRow noteRow = noteRows.get(i);
            FindingRow findingRow = new FindingRow(
                    noteRow.type(), noteRow.subject(), noteRow.port(), noteRow.state());
            assertEquals(snapshotRows.get(i), findingRow);
        }
    }

    @Test
    void anEmptyInputYieldsAnEmptyList() {
        assertTrue(NoteRows.of(List.of()).isEmpty());
    }

    @Test
    void aNonNullPortWithNullStateIsANonProbeAndIsNeitherDroppedNorThrown() {
        FindingSnapshot halfNull = new FindingSnapshot(1L, "PORT", "host", 80, null);
        List<NoteRow> rows = NoteRows.of(List.of(halfNull));
        assertEquals(1, rows.size());
        assertEquals("", rows.get(0).state());
    }

    @Test
    void anUnseenTypeTokenSurvivesVerbatimLowercased() {
        FindingSnapshot certificate = new FindingSnapshot(1L, "CERTIFICATE", "host", null, null);
        List<NoteRow> rows = NoteRows.of(List.of(certificate));
        assertEquals("certificate", rows.get(0).type());
    }

    @Test
    void portsSortNumericallyNotAsStrings() {
        FindingSnapshot p8080 = new FindingSnapshot(1L, "PORT", "host", 8080, "OPEN");
        FindingSnapshot p80 = new FindingSnapshot(2L, "PORT", "host", 80, "OPEN");
        FindingSnapshot p443 = new FindingSnapshot(3L, "PORT", "host", 443, "OPEN");

        List<NoteRow> rows = NoteRows.of(List.of(p8080, p80, p443));

        assertEquals("80", rows.get(0).port());
        assertEquals("443", rows.get(1).port());
        assertEquals("8080", rows.get(2).port());
    }

    @Test
    void rejectsNull() {
        assertThrows(NullPointerException.class, () -> NoteRows.of(null));
    }
}
