package com.argus.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.argus.core.AnnotationArchive;
import com.argus.core.FindingNote;
import com.argus.core.FindingSnapshot;
import com.argus.core.ScanArchive;
import com.argus.core.ScanArchiveException;
import com.argus.core.ScanCompletion;
import com.argus.core.ScanHistory;
import com.argus.core.ScanRun;
import com.argus.core.PortResult;
import com.argus.core.PortState;
import com.argus.core.Subdomain;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Plan §6.8: the item's keystone test — the end-to-end proof that the P1-09 -&gt; P2-09 id chain
 * actually reaches an annotation. This file imports {@code com.argus.db} nowhere; it stays at
 * the {@code core}/{@code ui} boundary, since {@code FindingSnapshot.id} already carries
 * everything needed (plan §0.3).
 */
class AnnotationRoundTripTest {

    private static final ZoneId FIXED_ZONE = ZoneId.of("UTC");
    private static final Instant FIXED_INSTANT = Instant.parse("2026-01-01T12:00:00Z");

    @TempDir
    Path tempDir;

    @Test
    void anAnnotationRoundTripsFromAPersistedScanThroughAddListRenderAndDelete() throws Exception {
        Path dbFile = tempDir.resolve("argus.db");
        ScanArchive archive = ScanArchive.at(dbFile);

        ScanRun run = new ScanRun("example.com", FIXED_INSTANT, FIXED_INSTANT.plusSeconds(30),
                ScanCompletion.COMPLETED, List.of(
                        new PortResult("example.com", 80, PortState.OPEN),
                        new PortResult("example.com", 443, PortState.CLOSED),
                        new Subdomain("api.example.com")));
        long scanId = archive.save(run);

        ScanHistory history = ScanHistory.at(dbFile);
        List<FindingSnapshot> findings = history.listFindings(scanId);
        assertEquals(3, findings.size());
        assertEquals(3, findings.stream().map(FindingSnapshot::id).distinct().count());
        assertTrue(findings.stream().allMatch(f -> f.id() != 0));

        List<NoteRow> rows = NoteRows.of(findings);
        assertEquals(3, rows.size());
        for (FindingSnapshot finding : findings) {
            long findingId = finding.id();
            assertTrue(rows.stream().anyMatch(r -> r.findingId() == findingId));
        }

        NoteRow targetRow = rows.get(0);
        AnnotationArchive notes = AnnotationArchive.at(dbFile);

        FindingNote added = notes.add(targetRow.findingId(), "false positive", FIXED_INSTANT);
        assertNotEquals(0L, added.id());

        List<FindingNote> forFinding = notes.listForFinding(targetRow.findingId());
        assertEquals(1, forFinding.size());
        assertEquals(FIXED_INSTANT, forFinding.get(0).createdAt());
        assertEquals("false positive", forFinding.get(0).body());

        List<FindingNote> forScan = notes.listForScan(scanId);
        assertEquals(1, forScan.size());
        Map<Long, List<FindingNote>> grouped = Notes.byFinding(forScan);
        assertEquals(1, grouped.size());
        assertEquals(List.of(added), grouped.get(targetRow.findingId()));

        List<NoteEntry> entries = Notes.entries(forFinding, FIXED_ZONE);
        assertEquals(1, entries.size());
        assertEquals("2026-01-01 12:00:00", entries.get(0).timestamp());

        FindingNote second = notes.add(targetRow.findingId(), "already patched",
                FIXED_INSTANT.plusSeconds(60));
        List<FindingNote> both = notes.listForFinding(targetRow.findingId());
        assertEquals(2, both.size());
        assertEquals("false positive", both.get(0).body());
        assertEquals("already patched", both.get(1).body());

        assertTrue(notes.delete(second.id()));
        assertEquals(1, notes.listForFinding(targetRow.findingId()).size());
        assertTrue(notes.delete(added.id()));
        assertTrue(notes.listForScan(scanId).isEmpty());
        assertFalse(notes.delete(added.id()));
    }

    @Test
    void addingAgainstAnUnknownFindingIdFails() throws Exception {
        Path dbFile = tempDir.resolve("argus.db");
        ScanArchive archive = ScanArchive.at(dbFile);
        ScanRun run = new ScanRun("example.com", FIXED_INSTANT, FIXED_INSTANT.plusSeconds(1),
                ScanCompletion.COMPLETED, List.of(new Subdomain("a.example.com")));
        long scanId = archive.save(run);

        ScanHistory history = ScanHistory.at(dbFile);
        long realFindingId = history.listFindings(scanId).get(0).id();

        AnnotationArchive notes = AnnotationArchive.at(dbFile);
        assertThrows(ScanArchiveException.class,
                () -> notes.add(realFindingId + 10_000, "note", FIXED_INSTANT));
    }
}
