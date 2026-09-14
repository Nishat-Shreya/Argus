package com.argus.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.argus.core.ScanCompletion;
import com.argus.core.Subdomain;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Section 6.8: {@code ScanCoordinatorFailureTest}. */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class ScanCoordinatorFailureTest {

    private static final ScanPlan PLAN = ScanPlan.of("example.com");

    private static List<Object> findingsFor(String prefix, int count) {
        List<Object> findings = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            findings.add(new Subdomain(prefix + i + ".example.com"));
        }
        return findings;
    }

    @Test
    void oneFailingJobDoesNotStopTheOther() throws Exception {
        FakeScanJob jobA = new FakeScanJob("job-a", findingsFor("a", 5), new RuntimeException("boom"));
        FakeScanJob jobB = new FakeScanJob("job-b", findingsFor("b", 7));
        RecordingScanEventListener listener = new RecordingScanEventListener();
        ScanCoordinator coordinator = new ScanCoordinator(listener, plan -> List.of(jobA, jobB));

        coordinator.start(PLAN);
        assertTrue(listener.finishedLatch().await(25, TimeUnit.SECONDS));

        List<FindingRow> delivered = listener.findings();
        List<FindingRow> jobBExpected = FindingRows.of(findingsFor("b", 7));
        for (FindingRow row : jobBExpected) {
            assertTrue(delivered.contains(row), "missing job-b finding: " + row);
        }
    }

    @Test
    void aFailedJobIsReportedAsFailed() throws Exception {
        FakeScanJob jobA = new FakeScanJob("job-a", findingsFor("a", 5), new RuntimeException("boom"));
        FakeScanJob jobB = new FakeScanJob("job-b", findingsFor("b", 5));
        RecordingScanEventListener listener = new RecordingScanEventListener();
        ScanCoordinator coordinator = new ScanCoordinator(listener, plan -> List.of(jobA, jobB));

        coordinator.start(PLAN);
        assertTrue(listener.finishedLatch().await(25, TimeUnit.SECONDS));

        List<WorkerStatus> statuses = listener.workerStatuses();
        WorkerStatus.State jobAFinal = lastStatusFor(statuses, "job-a");
        WorkerStatus.State jobBFinal = lastStatusFor(statuses, "job-b");
        assertEquals(WorkerStatus.State.FAILED, jobAFinal);
        assertEquals(WorkerStatus.State.DONE, jobBFinal);
    }

    @Test
    void outcomeIsCompletedWithErrors() throws Exception {
        FakeScanJob jobA = new FakeScanJob("job-a", findingsFor("a", 5), new RuntimeException("boom"));
        RecordingScanEventListener listener = new RecordingScanEventListener();
        ScanCoordinator coordinator = new ScanCoordinator(listener, plan -> List.of(jobA));

        coordinator.start(PLAN);
        assertTrue(listener.finishedLatch().await(25, TimeUnit.SECONDS));

        ScanOutcome outcome = listener.outcome();
        assertEquals(ScanCompletion.COMPLETED_WITH_ERRORS, outcome.result());
        assertEquals(1, outcome.failedJobs());
    }

    @Test
    void theFailureLogLineNamesTheClassAndNotTheStackTrace() throws Exception {
        FakeScanJob jobA = new FakeScanJob("job-a", findingsFor("a", 5), new RuntimeException("boom"));
        RecordingScanEventListener listener = new RecordingScanEventListener();
        ScanCoordinator coordinator = new ScanCoordinator(listener, plan -> List.of(jobA));

        coordinator.start(PLAN);
        assertTrue(listener.finishedLatch().await(25, TimeUnit.SECONDS));

        List<String> logLines = listener.logLines();
        String failureLine = logLines.stream()
                .filter(l -> l.contains("failed"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no failure log line found: " + logLines));
        assertTrue(failureLine.contains("RuntimeException"));
        assertFalse(failureLine.contains("boom"), "must not echo exception.getMessage()");
        assertFalse(failureLine.contains("at com."), "must not contain a stack trace frame");
    }

    @Test
    void anUnknownHostFailureIsHandledNotPropagated() throws Exception {
        FakeScanJob jobA = new FakeScanJob(
                "job-a", findingsFor("a", 5), new UnknownHostException("nope"));
        RecordingScanEventListener listener = new RecordingScanEventListener();
        ScanCoordinator coordinator = new ScanCoordinator(listener, plan -> List.of(jobA));

        coordinator.start(PLAN);
        assertTrue(listener.finishedLatch().await(25, TimeUnit.SECONDS));

        WorkerStatus.State jobAFinal = lastStatusFor(listener.workerStatuses(), "job-a");
        assertEquals(WorkerStatus.State.FAILED, jobAFinal);
    }

    @Test
    void aJobThrowingRuntimeExceptionIsAlsoContained() throws Exception {
        FakeScanJob jobA = new FakeScanJob(
                "job-a", findingsFor("a", 5), new IllegalStateException("bad state"));
        RecordingScanEventListener listener = new RecordingScanEventListener();
        ScanCoordinator coordinator = new ScanCoordinator(listener, plan -> List.of(jobA));

        coordinator.start(PLAN);
        assertTrue(listener.finishedLatch().await(25, TimeUnit.SECONDS));

        assertEquals(WorkerStatus.State.FAILED, lastStatusFor(listener.workerStatuses(), "job-a"));
        assertEquals(1, listener.outcome().failedJobs());
    }

    @Test
    void everyThreadStillTerminatesAfterAFailure() throws Exception {
        FakeScanJob jobA = new FakeScanJob("job-a", findingsFor("a", 5), new RuntimeException("boom"));
        FakeScanJob jobB = new FakeScanJob("job-b", findingsFor("b", 5));
        RecordingScanEventListener listener = new RecordingScanEventListener();
        ScanCoordinator coordinator = new ScanCoordinator(listener, plan -> List.of(jobA, jobB));

        coordinator.start(PLAN);
        assertTrue(listener.finishedLatch().await(25, TimeUnit.SECONDS));

        ScanCoordinatorTest.awaitPoolTerminatedAndThreadsFinished(coordinator);
        long finishedCount = listener.events().stream().filter(e -> e.startsWith("finished:")).count();
        assertEquals(1, finishedCount);
    }

    private static WorkerStatus.State lastStatusFor(List<WorkerStatus> statuses, String jobName) {
        WorkerStatus.State last = null;
        for (WorkerStatus status : statuses) {
            if (status.jobName().equals(jobName)) {
                last = status.state();
            }
        }
        return last;
    }
}
