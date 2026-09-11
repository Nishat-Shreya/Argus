package com.argus.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Schema-fitness check for P2-08's diff engine (plan §6.8, §7.9) — proves the schema answers the
 * diff question with two indexed {@code findByScan} queries, not that it builds
 * {@code ScanDiffEngine} itself. The diff is computed here with plain Java set arithmetic:
 * {@code (type, subject, port)} is the identity, {@code state} is the only comparable attribute.
 */
class FindingDiffQueryTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    /** Identity key: (type, subject, port). {@code -1} stands in for "no port", mirroring the
     *  schema's {@code ifnull(port, -1)} identity index. */
    private record Identity(FindingType type, String subject, int port) {
        static Identity of(FindingRecord record) {
            return new Identity(record.type(), record.subject(),
                    record.port() == null ? -1 : record.port());
        }
    }

    @TempDir
    Path tempDir;

    private ScanRepository scanRepository;
    private FindingDao findingDao;

    @BeforeEach
    void setUp() throws Exception {
        Database database = TempDatabases.open(tempDir);
        scanRepository = new ScanRepository(database);
        findingDao = new FindingDao(database);
    }

    @Test
    void identicalScansProduceAnEmptyDiff() throws Exception {
        List<NewFinding> shared = List.of(
                NewFinding.port("host", 80, "OPEN"),
                NewFinding.subdomain("a.example.com"));
        long olderId = newScanWith(shared);
        long newerId = newScanWith(shared);

        Diff diff = diff(olderId, newerId);
        assertTrue(diff.added.isEmpty());
        assertTrue(diff.removed.isEmpty());
        assertTrue(diff.changed.isEmpty());
    }

    @Test
    void aFindingOnlyInTheNewerScanIsAnAddition() throws Exception {
        long olderId = newScanWith(List.of(NewFinding.port("host", 80, "OPEN")));
        long newerId = newScanWith(List.of(
                NewFinding.port("host", 80, "OPEN"),
                NewFinding.port("host", 443, "OPEN")));

        Diff diff = diff(olderId, newerId);
        assertEquals(Set.of(new Identity(FindingType.PORT, "host", 443)), diff.added);
        assertTrue(diff.removed.isEmpty());
        assertTrue(diff.changed.isEmpty());
    }

    @Test
    void aFindingOnlyInTheOlderScanIsARemoval() throws Exception {
        long olderId = newScanWith(List.of(
                NewFinding.port("host", 80, "OPEN"),
                NewFinding.port("host", 443, "OPEN")));
        long newerId = newScanWith(List.of(NewFinding.port("host", 80, "OPEN")));

        Diff diff = diff(olderId, newerId);
        assertEquals(Set.of(new Identity(FindingType.PORT, "host", 443)), diff.removed);
        assertTrue(diff.added.isEmpty());
        assertTrue(diff.changed.isEmpty());
    }

    @Test
    void sameHostAndPortWithADifferentStateIsAChange() throws Exception {
        long olderId = newScanWith(List.of(NewFinding.port("host", 80, "OPEN")));
        long newerId = newScanWith(List.of(NewFinding.port("host", 80, "FILTERED")));

        Diff diff = diff(olderId, newerId);
        assertTrue(diff.added.isEmpty());
        assertTrue(diff.removed.isEmpty());
        assertEquals(Set.of(new Identity(FindingType.PORT, "host", 80)), diff.changed);
    }

    private long newScanWith(List<NewFinding> findings) throws Exception {
        ScanRecord scan = scanRepository.insert(NewScan.running("example.com", NOW));
        findingDao.insertAll(scan.id(), findings);
        return scan.id();
    }

    private Diff diff(long olderScanId, long newerScanId) throws Exception {
        Map<Identity, String> older = byIdentity(findingDao.findByScan(olderScanId));
        Map<Identity, String> newer = byIdentity(findingDao.findByScan(newerScanId));

        Set<Identity> added = new HashSet<>(newer.keySet());
        added.removeAll(older.keySet());

        Set<Identity> removed = new HashSet<>(older.keySet());
        removed.removeAll(newer.keySet());

        Set<Identity> changed = new HashSet<>();
        for (Identity identity : older.keySet()) {
            if (newer.containsKey(identity)
                    && !java.util.Objects.equals(older.get(identity), newer.get(identity))) {
                changed.add(identity);
            }
        }

        return new Diff(added, removed, changed);
    }

    private static Map<Identity, String> byIdentity(List<FindingRecord> findings) {
        Map<Identity, String> result = new HashMap<>();
        for (FindingRecord finding : findings) {
            result.put(Identity.of(finding), finding.state());
        }
        return result;
    }

    private record Diff(Set<Identity> added, Set<Identity> removed, Set<Identity> changed) { }
}
