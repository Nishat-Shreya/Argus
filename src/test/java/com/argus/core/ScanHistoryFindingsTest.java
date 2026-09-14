package com.argus.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.RecordComponent;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * T's driver for plan §0.3, §7.2: {@link ScanHistory#listFindings(long)} -- real
 * {@code @TempDir} SQLite via {@code ScanArchive.save(new ScanRun(...))}, never {@code :memory:}
 * (the {@code ScanHistoryTest} precedent).
 */
class ScanHistoryFindingsTest {

    private static final Instant STARTED = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant FINISHED = Instant.parse("2026-01-01T00:05:00Z");

    @TempDir
    Path tempDir;

    @Test
    void listFindingsReturnsAllFindingsOfOneScanWithPortsAndStatesForProbesAndNullForSubdomains()
            throws Exception {
        Path file = tempDir.resolve("argus.db");
        ScanArchive archive = ScanArchive.at(file);
        List<Object> findings = List.of(
                new PortResult("host", 80, PortState.OPEN),
                new PortResult("host", 22, PortState.CLOSED),
                new PortResult("host", 443, PortState.FILTERED),
                new Subdomain("a.example.com"),
                new Subdomain("b.example.com"));
        long scanId = archive.save(
                new ScanRun("example.com", STARTED, FINISHED, ScanCompletion.COMPLETED, findings));

        ScanHistory history = ScanHistory.at(file);
        List<FindingSnapshot> snapshots = history.listFindings(scanId);

        assertEquals(5, snapshots.size());
        for (FindingSnapshot snapshot : snapshots) {
            assertTrue(snapshot.id() != 0);
            if ("PORT".equals(snapshot.type())) {
                assertTrue(snapshot.port() != null);
                assertTrue(snapshot.state() != null);
            } else {
                assertEquals("SUBDOMAIN", snapshot.type());
                assertEquals(null, snapshot.port());
                assertEquals(null, snapshot.state());
            }
        }
    }

    @Test
    void listFindingsOnAScanWithZeroFindingsReturnsAnEmptyListAndDoesNotThrow() throws Exception {
        Path file = tempDir.resolve("argus.db");
        ScanArchive archive = ScanArchive.at(file);
        long scanId = archive.save(new ScanRun("example.com", STARTED, FINISHED,
                ScanCompletion.COMPLETED, List.of()));

        ScanHistory history = ScanHistory.at(file);
        assertEquals(List.of(), history.listFindings(scanId));
    }

    @Test
    void listFindingsOnAnUnknownIdThrowsScanArchiveExceptionNamingTheId() throws Exception {
        Path file = tempDir.resolve("argus.db");
        ScanArchive.at(file).save(new ScanRun("example.com", STARTED, FINISHED,
                ScanCompletion.COMPLETED, List.of()));

        ScanHistory history = ScanHistory.at(file);
        ScanArchiveException exception =
                assertThrows(ScanArchiveException.class, () -> history.listFindings(999L));
        assertTrue(exception.getMessage().contains("999"));
    }

    @Test
    void twoScansInOneFileEachIdReturnsOnlyItsOwnFindingsNoCrossScanBleed() throws Exception {
        Path file = tempDir.resolve("argus.db");
        ScanArchive archive = ScanArchive.at(file);
        long firstId = archive.save(new ScanRun("example.com", STARTED, FINISHED,
                ScanCompletion.COMPLETED, List.of(new PortResult("host", 80, PortState.OPEN))));
        long secondId = archive.save(new ScanRun("example.com", STARTED.plusSeconds(60),
                FINISHED.plusSeconds(60), ScanCompletion.COMPLETED,
                List.of(new Subdomain("a.example.com"), new Subdomain("b.example.com"))));

        ScanHistory history = ScanHistory.at(file);
        List<FindingSnapshot> firstFindings = history.listFindings(firstId);
        List<FindingSnapshot> secondFindings = history.listFindings(secondId);

        assertEquals(1, firstFindings.size());
        assertEquals(80, firstFindings.get(0).port());
        assertEquals(2, secondFindings.size());
        for (FindingSnapshot snapshot : secondFindings) {
            assertEquals("SUBDOMAIN", snapshot.type());
        }
    }

    @Test
    void theReturnedComponentTypesContainNoPersistenceLayerType() {
        for (RecordComponent component : FindingSnapshot.class.getRecordComponents()) {
            Package pkg = component.getType().getPackage();
            assertTrue(pkg == null || !pkg.getName().startsWith("com.argus.db"),
                    "FindingSnapshot." + component.getName() + " must not name a persistence "
                            + "layer type: " + component.getType());
        }
    }
}
