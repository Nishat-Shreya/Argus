package com.argus.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.argus.core.AnnotationArchive;
import com.argus.core.FindingSnapshot;
import com.argus.core.FindingTag;
import com.argus.core.PortResult;
import com.argus.core.PortState;
import com.argus.core.ScanArchive;
import com.argus.core.ScanArchiveException;
import com.argus.core.ScanCompletion;
import com.argus.core.ScanHistory;
import com.argus.core.ScanRun;
import com.argus.core.Subdomain;
import com.argus.core.TagArchive;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Plan §6.7: the item's keystone test -- the end-to-end proof that the P1-09 -&gt; P2-09 id chain
 * reaches a tag, and that the filter is correct over real persisted data. This file imports
 * {@code com.argus.db} nowhere; it stays at the {@code core}/{@code ui} boundary.
 */
class TagRoundTripTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-01-01T12:00:00Z");

    @TempDir
    Path tempDir;

    @Test
    void tagsRoundTripFromAPersistedScanThroughAddFilterAndRemove() throws Exception {
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

        List<NoteRow> rows = NoteRows.of(findings);
        assertEquals(3, rows.size());

        TagArchive tags = TagArchive.at(dbFile);

        tags.add(rows.get(0).findingId(), "prod");
        tags.add(rows.get(1).findingId(), "prod");
        FindingTag externalTag = tags.add(rows.get(1).findingId(), "external");

        List<FindingTag> forScan = tags.listForScan(scanId);
        assertEquals(3, forScan.size());
        Map<Long, List<FindingTag>> grouped = Tags.byFinding(forScan);
        assertEquals(1, grouped.get(rows.get(0).findingId()).size());
        assertEquals(2, grouped.get(rows.get(1).findingId()).size());
        assertFalse(grouped.containsKey(rows.get(2).findingId()));

        List<String> choices = Tags.filterChoices(forScan);
        assertEquals(List.of(Tags.ALL_TAGS, "external", "prod"), choices);

        assertEquals(List.of(rows.get(0), rows.get(1)), TagFilter.apply(rows, grouped, "prod"));
        assertEquals(List.of(rows.get(1)), TagFilter.apply(rows, grouped, "external"));
        assertEquals(rows, TagFilter.apply(rows, grouped, Tags.ALL_TAGS));

        // NOCASE identity end to end (plan §0.1/3, R7): "PROD" attaches to the same tag and
        // reports the first writer's stored casing.
        FindingTag reassigned = tags.add(rows.get(2).findingId(), "PROD");
        assertEquals(forScan.stream().filter(t -> t.name().equals("prod")).findFirst()
                .orElseThrow().tagId(), reassigned.tagId());
        assertEquals("prod", reassigned.name());

        assertTrue(tags.remove(rows.get(0).findingId(), reassigned.tagId()));
        Map<Long, List<FindingTag>> afterRemoval = Tags.byFinding(tags.listForScan(scanId));
        assertEquals(List.of(rows.get(2)),
                TagFilter.apply(rows, afterRemoval, "prod").stream()
                        .filter(row -> row.findingId() == rows.get(2).findingId())
                        .toList());
        assertFalse(TagFilter.apply(rows, afterRemoval, "prod").contains(rows.get(0)));
        assertFalse(tags.remove(rows.get(0).findingId(), reassigned.tagId()));

        assertNotEquals(0L, externalTag.tagId());

        // tags and annotations coexist on the same finding, unaffected by each other.
        AnnotationArchive notes = AnnotationArchive.at(dbFile);
        notes.add(rows.get(1).findingId(), "false positive", FIXED_INSTANT);
        assertEquals(1, notes.listForFinding(rows.get(1).findingId()).size());
        assertEquals(2, tags.listForFinding(rows.get(1).findingId()).size());
    }

    @Test
    void addAgainstAnUnknownFindingIdFails() throws Exception {
        Path dbFile = tempDir.resolve("argus.db");
        ScanArchive archive = ScanArchive.at(dbFile);
        ScanRun run = new ScanRun("example.com", FIXED_INSTANT, FIXED_INSTANT.plusSeconds(1),
                ScanCompletion.COMPLETED, List.of(new Subdomain("a.example.com")));
        long scanId = archive.save(run);

        ScanHistory history = ScanHistory.at(dbFile);
        long realFindingId = history.listFindings(scanId).get(0).id();

        TagArchive tags = TagArchive.at(dbFile);
        assertThrows(ScanArchiveException.class,
                () -> tags.add(realFindingId + 10_000, "prod"));
    }
}
