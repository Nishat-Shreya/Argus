package com.argus.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.argus.core.ScanArchiveException;
import com.argus.core.ScanCompletion;
import com.argus.core.ScanRun;
import com.argus.core.Subdomain;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Section 6.2: {@code ScanCoordinatorPersistenceTest} — the save-sequencing contract (§4.3). */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class ScanCoordinatorPersistenceTest {

    private static final ScanPlan PLAN = ScanPlan.of("example.com");

    private static List<Object> findingsFor(String prefix, int count) {
        List<Object> findings = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            findings.add(new Subdomain(prefix + i + ".example.com"));
        }
        return findings;
    }

    private static Set<String> namesOf(List<Object> findings) {
        Set<String> names = new HashSet<>();
        for (Object finding : findings) {
            names.add(((Subdomain) finding).name());
        }
        return names;
    }

    @Test
    void theSaverReceivesExactlyTheDeliveredFindings() throws Exception {
        FakeScanJob jobA = new FakeScanJob("job-a", findingsFor("a", 250));
        FakeScanJob jobB = new FakeScanJob("job-b", findingsFor("b", 250));
        RecordingScanEventListener listener = new RecordingScanEventListener();
        RecordingScanSaver saver = new RecordingScanSaver().returning(1L);
        ScanCoordinator coordinator =
                new ScanCoordinator(listener, plan -> List.of(jobA, jobB), saver);

        coordinator.start(PLAN);
        assertTrue(listener.finishedLatch().await(25, TimeUnit.SECONDS));

        List<ScanRun> runs = saver.runs();
        assertEquals(1, runs.size());
        ScanRun run = runs.get(0);
        assertEquals(500, run.findings().size());
        Set<String> expected = new HashSet<>();
        expected.addAll(namesOf(findingsFor("a", 250)));
        expected.addAll(namesOf(findingsFor("b", 250)));
        assertEquals(expected, namesOf(run.findings()));
    }

    @Test
    void theScanRunCarriesTargetAndOrderedTimestamps() throws Exception {
        FakeScanJob jobA = new FakeScanJob("job-a", findingsFor("a", 5));
        RecordingScanEventListener listener = new RecordingScanEventListener();
        RecordingScanSaver saver = new RecordingScanSaver().returning(1L);
        ScanCoordinator coordinator = new ScanCoordinator(listener, plan -> List.of(jobA), saver);

        coordinator.start(PLAN);
        assertTrue(listener.finishedLatch().await(25, TimeUnit.SECONDS));

        ScanRun run = saver.runs().get(0);
        assertEquals("example.com", run.target());
        assertFalse(run.finishedAt().isBefore(run.startedAt()));
    }

    @Test
    void theSaverIsCalledExactlyOncePerScan() throws Exception {
        FakeScanJob jobA = new FakeScanJob("job-a", findingsFor("a", 5));
        RecordingScanEventListener listener = new RecordingScanEventListener();
        RecordingScanSaver saver = new RecordingScanSaver().returning(1L);
        ScanCoordinator coordinator = new ScanCoordinator(listener, plan -> List.of(jobA), saver);

        coordinator.start(PLAN);
        assertTrue(listener.finishedLatch().await(25, TimeUnit.SECONDS));

        assertEquals(1, saver.callCount());
    }

    @Test
    void theDefaultSaverIsNoneAndIsNeverCalled() throws Exception {
        FakeScanJob jobA = new FakeScanJob("job-a", findingsFor("a", 5));
        RecordingScanEventListener listener = new RecordingScanEventListener();
        ScanCoordinator coordinator = new ScanCoordinator(listener, plan -> List.of(jobA));

        coordinator.start(PLAN);
        assertTrue(listener.finishedLatch().await(25, TimeUnit.SECONDS));

        assertNull(listener.outcome().savedScanId());
        assertFalse(listener.outcome().saved());
    }

    @Test
    void theSaverIsInvokedOnTheSupervisorThread() throws Exception {
        FakeScanJob jobA = new FakeScanJob("job-a", findingsFor("a", 5));
        RecordingScanEventListener listener = new RecordingScanEventListener();
        RecordingScanSaver saver = new RecordingScanSaver().returning(1L);
        ScanCoordinator coordinator = new ScanCoordinator(listener, plan -> List.of(jobA), saver);

        coordinator.start(PLAN);
        assertTrue(listener.finishedLatch().await(25, TimeUnit.SECONDS));

        assertEquals(List.of("argus-scan-supervisor"), saver.threadNames());
    }

    @Test
    void theSaveCompletesBeforeOnScanFinishedFiresAndTheReturnedIdIsCarried() throws Exception {
        FakeScanJob jobA = new FakeScanJob("job-a", findingsFor("a", 5));
        List<String> eventOrder = new CopyOnWriteArrayList<>();
        RecordingScanSaver saver = new RecordingScanSaver().returning(7L);
        CountDownLatch finishedLatch = new CountDownLatch(1);
        ScanOutcome[] captured = new ScanOutcome[1];

        ScanEventListener orderTrackingListener = new ScanEventListener() {
            @Override
            public void onScanStarted(ScanPlan plan, List<String> jobNames) {
            }

            @Override
            public void onLog(String line) {
            }

            @Override
            public void onWorkerStatus(WorkerStatus status) {
            }

            @Override
            public void onFindings(List<FindingRow> batch) {
            }

            @Override
            public void onProgress(ScanProgress progress) {
            }

            @Override
            public void onScanFinished(ScanOutcome outcome) {
                eventOrder.add("finished");
                captured[0] = outcome;
                finishedLatch.countDown();
            }
        };
        ScanSaver wrappedSaver = run -> {
            eventOrder.add("save");
            return saver.save(run);
        };
        ScanCoordinator coordinator =
                new ScanCoordinator(orderTrackingListener, plan -> List.of(jobA), wrappedSaver);

        coordinator.start(PLAN);
        assertTrue(finishedLatch.await(25, TimeUnit.SECONDS));

        assertEquals(List.of("save", "finished"), eventOrder);
        assertEquals(7L, captured[0].savedScanId());
    }

    @Test
    void aFailingJobStillSavesARunCarryingCompletedWithErrors() throws Exception {
        FakeScanJob jobA =
                new FakeScanJob("job-a", findingsFor("a", 5), new RuntimeException("boom"));
        RecordingScanEventListener listener = new RecordingScanEventListener();
        RecordingScanSaver saver = new RecordingScanSaver().returning(1L);
        ScanCoordinator coordinator = new ScanCoordinator(listener, plan -> List.of(jobA), saver);

        coordinator.start(PLAN);
        assertTrue(listener.finishedLatch().await(25, TimeUnit.SECONDS));

        assertEquals(ScanCompletion.COMPLETED_WITH_ERRORS, listener.outcome().result());
        assertEquals(ScanCompletion.COMPLETED_WITH_ERRORS, saver.runs().get(0).completion());
    }

    @Test
    void cancellationStillSavesARunCarryingPartialFindings() throws Exception {
        CountDownLatch startedLatch = new CountDownLatch(1);
        CountDownLatch releaseLatch = new CountDownLatch(1);
        FakeScanJob job = new FakeScanJob(
                "job-a", findingsFor("a", 500), null, startedLatch, releaseLatch);
        RecordingScanEventListener listener = new RecordingScanEventListener();
        RecordingScanSaver saver = new RecordingScanSaver().returning(1L);
        ScanCoordinator coordinator = new ScanCoordinator(listener, plan -> List.of(job), saver);

        coordinator.start(PLAN);
        assertTrue(startedLatch.await(10, TimeUnit.SECONDS));
        coordinator.cancel();
        releaseLatch.countDown();

        assertTrue(listener.finishedLatch().await(25, TimeUnit.SECONDS));

        assertEquals(ScanCompletion.CANCELLED, listener.outcome().result());
        assertEquals(1, saver.callCount());
        assertTrue(saver.runs().get(0).findings().size() < 500);
    }

    @Test
    void aFailingSaverDoesNotWedgeTheDashboard() throws Exception {
        FakeScanJob jobA = new FakeScanJob("job-a", findingsFor("a", 5));
        RecordingScanEventListener listener = new RecordingScanEventListener();
        RecordingScanSaver saver =
                new RecordingScanSaver().throwing(new ScanArchiveException("boom", new Exception()));
        ScanCoordinator coordinator = new ScanCoordinator(listener, plan -> List.of(jobA), saver);

        coordinator.start(PLAN);
        assertTrue(listener.finishedLatch().await(25, TimeUnit.SECONDS));

        ScanOutcome outcome = listener.outcome();
        assertFalse(outcome.saved());
        assertNull(outcome.savedScanId());
        long finishedCount =
                listener.events().stream().filter(e -> e.startsWith("finished:")).count();
        assertEquals(1, finishedCount);
        boolean sawNotSavedLog =
                listener.logLines().stream().anyMatch(l -> l.contains("NOT saved"));
        assertTrue(sawNotSavedLog, "expected a log line containing 'NOT saved': " + listener.logLines());

        // a second scan can still be started -- the failed save must not wedge the dashboard
        FakeScanJob jobB = new FakeScanJob("job-b", findingsFor("b", 5));
        RecordingScanEventListener listener2 = new RecordingScanEventListener();
        coordinator = new ScanCoordinator(listener2, plan -> List.of(jobB), ScanSaver.none());
        coordinator.start(PLAN);
        assertTrue(listener2.finishedLatch().await(25, TimeUnit.SECONDS));
    }

    @Test
    void twoConsecutiveScansDoNotLeakFindingsAcross() throws Exception {
        FakeScanJob job1 = new FakeScanJob("job-a", findingsFor("a", 5));
        RecordingScanEventListener listener1 = new RecordingScanEventListener();
        RecordingScanSaver saver1 = new RecordingScanSaver().returning(1L);
        ScanCoordinator coordinator = new ScanCoordinator(listener1, plan -> List.of(job1), saver1);
        coordinator.start(PLAN);
        assertTrue(listener1.finishedLatch().await(25, TimeUnit.SECONDS));

        FakeScanJob job2 = new FakeScanJob("job-b", findingsFor("b", 5));
        RecordingScanEventListener listener2 = new RecordingScanEventListener();
        RecordingScanSaver saver2 = new RecordingScanSaver().returning(2L);
        ScanCoordinator coordinator2 =
                new ScanCoordinator(listener2, plan -> List.of(job2), saver2);
        coordinator2.start(PLAN);
        assertTrue(listener2.finishedLatch().await(25, TimeUnit.SECONDS));

        ScanRun run2 = saver2.runs().get(0);
        assertTrue(run2.findings().stream()
                .noneMatch(f -> ((Subdomain) f).name().startsWith("a")));
    }
}
