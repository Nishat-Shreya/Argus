package com.argus.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.argus.core.PortResult;
import com.argus.core.PortState;
import com.argus.core.ScanArchive;
import com.argus.core.Subdomain;
import com.argus.db.Database;
import com.argus.db.FindingRecord;
import com.argus.db.FindingType;
import com.argus.db.ScanRepository;
import com.argus.db.ScanSession;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Section 6.3: the backlog's "round-trips through the real DB and is loadable afterward"
 * acceptance test. This file imports {@code com.argus.db} from TEST scope, which is deliberate
 * and outside the {@code uiDoesNotImportDb()} ruling (P1-09 §0.2), scoped to production
 * {@code ui} sources only.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class ScanCoordinatorPersistenceRoundTripTest {

    private static final ScanPlan PLAN = ScanPlan.of("example.com");

    @TempDir
    Path tempDir;

    @Test
    void aCompletedScanRoundTripsThroughTheRealDatabase() throws Exception {
        ScanArchive archive = ScanArchive.at(tempDir.resolve("argus.db"));
        FakeScanJob portJob = new FakeScanJob("port-job", List.of(
                new PortResult("example.com", 80, PortState.OPEN),
                new PortResult("example.com", 443, PortState.CLOSED)));
        FakeScanJob subdomainJob = new FakeScanJob("subdomain-job", List.of(
                new Subdomain("api.example.com"),
                new Subdomain("*.example.com")));
        RecordingScanEventListener listener = new RecordingScanEventListener();
        ScanCoordinator coordinator = new ScanCoordinator(
                listener, plan -> List.of(portJob, subdomainJob), archive::save);

        coordinator.start(PLAN);
        assertTrue(listener.finishedLatch().await(25, TimeUnit.SECONDS));

        Long savedScanId = listener.outcome().savedScanId();
        assertNotEquals(null, savedScanId);

        ScanRepository repository = new ScanRepository(Database.open(archive.databaseFile()));
        ScanSession session = repository.loadSession(savedScanId).orElseThrow();
        assertEquals("example.com", session.scan().target());

        Set<String> published = new HashSet<>();
        for (FindingRecord finding : session.findings()) {
            if (finding.type() == FindingType.PORT) {
                published.add("port:" + finding.subject() + ":" + finding.port() + ":"
                        + finding.state());
            } else {
                published.add("subdomain:" + finding.subject());
            }
        }
        assertEquals(Set.of(
                "port:example.com:80:OPEN",
                "port:example.com:443:CLOSED",
                "subdomain:api.example.com",
                "subdomain:*.example.com"), published);
    }

    @Test
    void twoScansAgainstTheSameArchiveAreIndependentAndBothComplete() throws Exception {
        ScanArchive archive = ScanArchive.at(tempDir.resolve("argus.db"));

        FakeScanJob firstJob =
                new FakeScanJob("job-a", List.of(new Subdomain("a.example.com")));
        RecordingScanEventListener firstListener = new RecordingScanEventListener();
        ScanCoordinator firstCoordinator =
                new ScanCoordinator(firstListener, plan -> List.of(firstJob), archive::save);
        firstCoordinator.start(PLAN);
        assertTrue(firstListener.finishedLatch().await(25, TimeUnit.SECONDS));

        FakeScanJob secondJob =
                new FakeScanJob("job-b", List.of(new Subdomain("b.example.com")));
        RecordingScanEventListener secondListener = new RecordingScanEventListener();
        ScanCoordinator secondCoordinator =
                new ScanCoordinator(secondListener, plan -> List.of(secondJob), archive::save);
        secondCoordinator.start(PLAN);
        assertTrue(secondListener.finishedLatch().await(25, TimeUnit.SECONDS));

        Long firstId = firstListener.outcome().savedScanId();
        Long secondId = secondListener.outcome().savedScanId();
        assertNotEquals(firstId, secondId);

        ScanRepository repository = new ScanRepository(Database.open(archive.databaseFile()));
        ScanSession firstSession = repository.loadSession(firstId).orElseThrow();
        ScanSession secondSession = repository.loadSession(secondId).orElseThrow();

        assertEquals(1, firstSession.findings().size());
        assertEquals("a.example.com", firstSession.findings().get(0).subject());
        assertEquals(1, secondSession.findings().size());
        assertEquals("b.example.com", secondSession.findings().get(0).subject());
    }
}
