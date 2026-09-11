package com.argus.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The backlog's acceptance test (plan §6.6): round-trip a scan + findings against a temp DB
 * file, all in one transaction.
 */
class ScanSessionRoundTripTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @TempDir
    Path tempDir;

    private ScanRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        Database database = TempDatabases.open(tempDir);
        repository = new ScanRepository(database);
    }

    @Test
    void saveSessionThenLoadSessionReturnsAnEqualSession() throws Exception {
        NewScan scan = NewScan.running("example.com", NOW);
        List<NewFinding> findings = List.of(
                NewFinding.port("host", 80, "OPEN"),
                NewFinding.subdomain("a.example.com"));

        ScanSession saved = repository.saveSession(scan, findings);
        ScanSession loaded = repository.loadSession(saved.scan().id()).orElseThrow();

        assertEquals(saved.scan().target(), loaded.scan().target());
        assertEquals(saved.scan().startedAt(), loaded.scan().startedAt());
        assertEquals(saved.scan().finishedAt(), loaded.scan().finishedAt());
        assertEquals(saved.scan().status(), loaded.scan().status());

        Set<List<Object>> savedShape = shapeOf(saved.findings());
        Set<List<Object>> loadedShape = shapeOf(loaded.findings());
        assertEquals(savedShape, loadedShape);
    }

    @Test
    void saveSessionAssignsIdsToEveryFinding() throws Exception {
        NewScan scan = NewScan.running("example.com", NOW);
        List<NewFinding> findings = List.of(
                NewFinding.port("host", 80, "OPEN"),
                NewFinding.port("host", 443, "OPEN"),
                NewFinding.subdomain("a.example.com"));

        ScanSession saved = repository.saveSession(scan, findings);

        Set<Long> ids = new HashSet<>();
        for (FindingRecord finding : saved.findings()) {
            assertTrue(finding.id() > 0);
            assertEquals(saved.scan().id(), finding.scanId());
            ids.add(finding.id());
        }
        assertEquals(findings.size(), ids.size());
    }

    @Test
    void saveSessionWithNoFindingsStillStoresTheScan() throws Exception {
        NewScan scan = NewScan.running("example.com", NOW);
        ScanSession saved = repository.saveSession(scan, List.of());

        ScanSession loaded = repository.loadSession(saved.scan().id()).orElseThrow();
        assertEquals(List.of(), loaded.findings());
    }

    @Test
    void saveSessionIsAtomic() throws Exception {
        NewScan scan = NewScan.running("example.com", NOW);
        List<NewFinding> duplicateBatch = List.of(
                NewFinding.port("host", 80, "OPEN"),
                NewFinding.port("host", 80, "CLOSED"));

        assertThrows(PersistenceException.class,
                () -> repository.saveSession(scan, duplicateBatch));

        assertEquals(List.of(), repository.findAll());
    }

    @Test
    void loadSessionOfAnUnknownIdReturnsEmpty() throws Exception {
        assertEquals(Optional.empty(), repository.loadSession(9999L));
    }

    @Test
    void aSessionSurvivesReopeningTheDatabaseFile() throws Exception {
        NewScan scan = NewScan.running("example.com", NOW);
        List<NewFinding> findings = List.of(NewFinding.port("host", 80, "OPEN"));
        ScanSession saved = repository.saveSession(scan, findings);

        Database reopened = Database.open(tempDir.resolve("argus.db"));
        ScanRepository reopenedRepository = new ScanRepository(reopened);
        ScanSession loaded = reopenedRepository.loadSession(saved.scan().id()).orElseThrow();

        assertEquals(saved.scan().target(), loaded.scan().target());
        assertEquals(1, loaded.findings().size());
    }

    @Test
    void twoSessionsForTheSameTargetCoexist() throws Exception {
        ScanSession first = repository.saveSession(
                NewScan.running("example.com", NOW),
                List.of(NewFinding.port("host", 80, "OPEN")));
        ScanSession second = repository.saveSession(
                NewScan.running("example.com", NOW.plusSeconds(60)),
                List.of(NewFinding.port("host", 443, "OPEN")));

        ScanSession loadedFirst = repository.loadSession(first.scan().id()).orElseThrow();
        ScanSession loadedSecond = repository.loadSession(second.scan().id()).orElseThrow();

        assertEquals(1, loadedFirst.findings().size());
        assertEquals(80, loadedFirst.findings().get(0).port());
        assertEquals(1, loadedSecond.findings().size());
        assertEquals(443, loadedSecond.findings().get(0).port());
    }

    private static Set<List<Object>> shapeOf(List<FindingRecord> findings) {
        Set<List<Object>> shape = new HashSet<>();
        for (FindingRecord f : findings) {
            shape.add(List.of(f.type(), f.subject(), f.port() == null ? -1 : f.port(),
                    f.state() == null ? "" : f.state()));
        }
        return shape;
    }
}
