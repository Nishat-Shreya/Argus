package com.argus.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.argus.core.ScanCompletion;
import com.argus.core.ScanPipeline;
import com.argus.core.Subdomain;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Section 6.5: {@code ScanCoordinator} happy path, fake jobs. Real threads, no toolkit, no
 * network, no sockets, no {@code Thread.sleep}.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class ScanCoordinatorTest {

    private static final ScanPlan PLAN = ScanPlan.of("example.com");

    private static List<Object> findingsFor(String prefix, int count) {
        List<Object> findings = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            findings.add(new Subdomain(prefix + i + ".example.com"));
        }
        return findings;
    }

    private static Set<String> subjectsOf(List<FindingRow> rows) {
        Set<String> subjects = new HashSet<>();
        for (FindingRow row : rows) {
            subjects.add(row.subject());
        }
        return subjects;
    }

    @Test
    void deliversEveryFindingExactlyOnce() throws Exception {
        FakeScanJob jobA = new FakeScanJob("job-a", findingsFor("a", 250));
        FakeScanJob jobB = new FakeScanJob("job-b", findingsFor("b", 250));
        RecordingScanEventListener listener = new RecordingScanEventListener();
        ScanCoordinator coordinator =
                new ScanCoordinator(listener, plan -> List.of(jobA, jobB));

        coordinator.start(PLAN);
        assertTrue(listener.finishedLatch().await(25, TimeUnit.SECONDS));

        List<FindingRow> delivered = listener.findings();
        assertEquals(500, delivered.size());
        Set<String> subjects = subjectsOf(delivered);
        assertEquals(500, subjects.size(), "no duplicates");

        Set<String> expected = new HashSet<>();
        expected.addAll(subjectsOf(FindingRows.of(findingsFor("a", 250))));
        expected.addAll(subjectsOf(FindingRows.of(findingsFor("b", 250))));
        assertEquals(expected, subjects);
    }

    @Test
    void findingsArriveAsBatchesNotOneAtATime() throws Exception {
        FakeScanJob jobA = new FakeScanJob("job-a", findingsFor("a", 250));
        FakeScanJob jobB = new FakeScanJob("job-b", findingsFor("b", 250));
        RecordingScanEventListener listener = new RecordingScanEventListener();
        ScanCoordinator coordinator =
                new ScanCoordinator(listener, plan -> List.of(jobA, jobB));

        coordinator.start(PLAN);
        assertTrue(listener.finishedLatch().await(25, TimeUnit.SECONDS));

        boolean sawMultiRowBatch = listener.batches().stream().anyMatch(b -> b.size() > 1);
        assertTrue(sawMultiRowBatch, "expected at least one batch with > 1 row");
    }

    @Test
    void everyBatchIsAtMostTheBatchLimit() throws Exception {
        FakeScanJob jobA = new FakeScanJob("job-a", findingsFor("a", 250));
        FakeScanJob jobB = new FakeScanJob("job-b", findingsFor("b", 250));
        RecordingScanEventListener listener = new RecordingScanEventListener();
        ScanCoordinator coordinator = new ScanCoordinator(
                listener, plan -> List.of(jobA, jobB), ScanPipeline.DEFAULT_CAPACITY, 8);

        coordinator.start(PLAN);
        assertTrue(listener.finishedLatch().await(25, TimeUnit.SECONDS));

        for (List<FindingRow> batch : listener.batches()) {
            assertTrue(batch.size() <= 8, "batch exceeded limit: " + batch.size());
        }
    }

    @Test
    void workerStatusesTransitionPendingRunningDone() throws Exception {
        FakeScanJob jobA = new FakeScanJob("job-a", findingsFor("a", 5));
        FakeScanJob jobB = new FakeScanJob("job-b", findingsFor("b", 5));
        RecordingScanEventListener listener = new RecordingScanEventListener();
        ScanCoordinator coordinator =
                new ScanCoordinator(listener, plan -> List.of(jobA, jobB));

        coordinator.start(PLAN);
        assertTrue(listener.finishedLatch().await(25, TimeUnit.SECONDS));

        List<WorkerStatus> statuses = listener.workerStatuses();
        List<WorkerStatus> jobAStatuses =
                statuses.stream().filter(s -> s.jobName().equals("job-a")).toList();
        assertEquals(List.of(
                WorkerStatus.State.PENDING, WorkerStatus.State.RUNNING, WorkerStatus.State.DONE),
                jobAStatuses.stream().map(WorkerStatus::state).toList());

        for (WorkerStatus status : statuses) {
            if (status.state() == WorkerStatus.State.RUNNING
                    || status.state() == WorkerStatus.State.DONE) {
                assertTrue(Pattern.matches("argus-producer-\\d+", status.threadName()),
                        "unexpected thread name: " + status.threadName());
            }
        }

        WorkerStatus jobADone = jobAStatuses.get(jobAStatuses.size() - 1);
        assertEquals(5, jobADone.findingsPublished());
    }

    @Test
    void progressReachesTotalOverTotal() throws Exception {
        FakeScanJob jobA = new FakeScanJob("job-a", findingsFor("a", 250));
        FakeScanJob jobB = new FakeScanJob("job-b", findingsFor("b", 250));
        RecordingScanEventListener listener = new RecordingScanEventListener();
        ScanCoordinator coordinator =
                new ScanCoordinator(listener, plan -> List.of(jobA, jobB));

        coordinator.start(PLAN);
        assertTrue(listener.finishedLatch().await(25, TimeUnit.SECONDS));

        List<ScanProgress> updates = listener.progressUpdates();
        assertFalse(updates.isEmpty());
        ScanProgress last = updates.get(updates.size() - 1);
        assertEquals(2, last.totalJobs());
        assertEquals(2, last.completedJobs());
        assertEquals(1.0, last.fraction());
        assertEquals(500, last.findingsDelivered());
    }

    @Test
    void onScanStartedFiresFirstAndOnScanFinishedExactlyOnceAndLast() throws Exception {
        FakeScanJob jobA = new FakeScanJob("job-a", findingsFor("a", 5));
        RecordingScanEventListener listener = new RecordingScanEventListener();
        ScanCoordinator coordinator = new ScanCoordinator(listener, plan -> List.of(jobA));

        coordinator.start(PLAN);
        assertTrue(listener.finishedLatch().await(25, TimeUnit.SECONDS));

        List<String> events = listener.events();
        assertEquals("started", events.get(0));
        long finishedCount = events.stream().filter(e -> e.startsWith("finished:")).count();
        assertEquals(1, finishedCount);
        assertTrue(events.get(events.size() - 1).startsWith("finished:"),
                "onScanFinished must fire last, got: " + events);
    }

    @Test
    void outcomeIsCompletedWithZeroFailures() throws Exception {
        FakeScanJob jobA = new FakeScanJob("job-a", findingsFor("a", 5));
        RecordingScanEventListener listener = new RecordingScanEventListener();
        ScanCoordinator coordinator = new ScanCoordinator(listener, plan -> List.of(jobA));

        coordinator.start(PLAN);
        assertTrue(listener.finishedLatch().await(25, TimeUnit.SECONDS));

        ScanOutcome outcome = listener.outcome();
        assertEquals(ScanCompletion.COMPLETED, outcome.result());
        assertEquals(0, outcome.failedJobs());
    }

    @Test
    void noListenerCallbackHappensOnTheCallingThread() throws Exception {
        FakeScanJob jobA = new FakeScanJob("job-a", findingsFor("a", 5));
        RecordingScanEventListener listener = new RecordingScanEventListener();
        ScanCoordinator coordinator = new ScanCoordinator(listener, plan -> List.of(jobA));

        coordinator.start(PLAN);
        assertTrue(listener.finishedLatch().await(25, TimeUnit.SECONDS));

        Thread testThread = Thread.currentThread();
        for (Thread callbackThread : listener.callbackThreads()) {
            assertFalse(callbackThread.equals(testThread));
            assertTrue(callbackThread.getName().equals("argus-scan-consumer")
                            || callbackThread.getName().equals("argus-scan-supervisor"),
                    "unexpected callback thread: " + callbackThread.getName());
        }
    }

    @Test
    void startReturnsBeforeTheScanFinishes() throws Exception {
        CountDownLatch startedLatch = new CountDownLatch(1);
        CountDownLatch releaseLatch = new CountDownLatch(1);
        FakeScanJob gatedJob = new FakeScanJob(
                "job-a", findingsFor("a", 5), null, startedLatch, releaseLatch);
        RecordingScanEventListener listener = new RecordingScanEventListener();
        ScanCoordinator coordinator = new ScanCoordinator(listener, plan -> List.of(gatedJob));

        coordinator.start(PLAN);
        assertTrue(startedLatch.await(10, TimeUnit.SECONDS));
        assertFalse(listener.finishedLatch().await(200, TimeUnit.MILLISECONDS));
        assertTrue(coordinator.isRunning());

        releaseLatch.countDown();
        assertTrue(listener.finishedLatch().await(25, TimeUnit.SECONDS));
    }

    @Test
    void poolAndThreadsAreGoneAfterTheScan() throws Exception {
        FakeScanJob jobA = new FakeScanJob("job-a", findingsFor("a", 5));
        RecordingScanEventListener listener = new RecordingScanEventListener();
        ScanCoordinator coordinator = new ScanCoordinator(listener, plan -> List.of(jobA));

        coordinator.start(PLAN);
        assertTrue(listener.finishedLatch().await(25, TimeUnit.SECONDS));

        awaitPoolTerminatedAndThreadsFinished(coordinator);
        awaitNoCoordinatorThreadsAlive();
    }

    /**
     * Polls {@code isPoolTerminated()}/{@code areThreadsFinished()} for a bounded window: the
     * supervisor thread calls {@code onScanFinished} as its very last act before its own
     * {@code run()} method returns, so a listener observing that callback (e.g. via a latch)
     * can be resumed a moment before {@code Thread.isAlive()} for the supervisor itself
     * flips to false. That is a harmless scheduling gap, not a thread leak.
     */
    static void awaitPoolTerminatedAndThreadsFinished(ScanCoordinator coordinator) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (coordinator.isPoolTerminated() && coordinator.areThreadsFinished()) {
                return;
            }
            Thread.onSpinWait();
        }
        assertTrue(coordinator.isPoolTerminated());
        assertTrue(coordinator.areThreadsFinished());
    }

    /**
     * Polls {@code coordinator.currentPipelineSizeForTest()} until it stops changing for a
     * continuous window -- the deterministic way to observe "the producer has stopped making
     * progress because the paused consumer is not draining it", as opposed to comparing
     * against a single sample taken at an arbitrary instant (racy by construction: a batch
     * already in flight when pause() lands may still land a moment later).
     */
    static int awaitStablePipelineSize(ScanCoordinator coordinator) {
        int lastSize = coordinator.currentPipelineSizeForTest();
        long stableSince = System.nanoTime();
        long stableWindowNanos = TimeUnit.MILLISECONDS.toNanos(500);
        long overallDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < overallDeadline) {
            int currentSize = coordinator.currentPipelineSizeForTest();
            if (currentSize != lastSize) {
                lastSize = currentSize;
                stableSince = System.nanoTime();
            } else if (System.nanoTime() - stableSince >= stableWindowNanos) {
                return lastSize;
            }
            Thread.onSpinWait();
        }
        throw new AssertionError("pipeline size never stabilized");
    }

    /**
     * Polls {@code Thread.getAllStackTraces()} for a bounded window rather than taking a
     * single snapshot: a thread that has genuinely already returned from its run() method can
     * still take the scheduler a moment to fully remove from that global registry, which is an
     * unrelated scheduling/GC-visibility delay, not a leak. A real leak still fails this (it
     * never clears within the bound).
     */
    static void awaitNoCoordinatorThreadsAlive() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            boolean anyAlive = false;
            for (Thread t : Thread.getAllStackTraces().keySet()) {
                String name = t.getName();
                if (name.startsWith("argus-producer-") || name.equals("argus-scan-consumer")
                        || name.equals("argus-scan-supervisor")) {
                    anyAlive = true;
                    break;
                }
            }
            if (!anyAlive) {
                return;
            }
            Thread.onSpinWait();
        }
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            String name = t.getName();
            assertFalse(name.startsWith("argus-producer-"), "leaked: " + name);
            assertFalse(name.equals("argus-scan-consumer"), "leaked consumer thread");
            assertFalse(name.equals("argus-scan-supervisor"), "leaked supervisor thread");
        }
    }

    @Test
    void aSecondScanCanBeStartedAfterTheFirstFinishes() throws Exception {
        FakeScanJob job1 = new FakeScanJob("job-a", findingsFor("a", 5));
        RecordingScanEventListener listener1 = new RecordingScanEventListener();
        ScanCoordinator coordinator = new ScanCoordinator(listener1, plan -> List.of(job1));
        coordinator.start(PLAN);
        assertTrue(listener1.finishedLatch().await(25, TimeUnit.SECONDS));

        FakeScanJob job2 = new FakeScanJob("job-b", findingsFor("b", 5));
        RecordingScanEventListener listener2 = new RecordingScanEventListener();
        ScanCoordinator coordinator2 = new ScanCoordinator(listener2, plan -> List.of(job2));
        coordinator2.start(PLAN);
        assertTrue(listener2.finishedLatch().await(25, TimeUnit.SECONDS));

        assertEquals(5, listener2.findings().size());
        assertTrue(listener2.findings().stream().noneMatch(r -> r.subject().startsWith("a")));
    }

    @Test
    void startWhileRunningThrowsIllegalStateException() throws Exception {
        CountDownLatch startedLatch = new CountDownLatch(1);
        CountDownLatch releaseLatch = new CountDownLatch(1);
        FakeScanJob gatedJob = new FakeScanJob(
                "job-a", findingsFor("a", 5), null, startedLatch, releaseLatch);
        RecordingScanEventListener listener = new RecordingScanEventListener();
        ScanCoordinator coordinator = new ScanCoordinator(listener, plan -> List.of(gatedJob));

        coordinator.start(PLAN);
        assertTrue(startedLatch.await(10, TimeUnit.SECONDS));

        assertThrows(IllegalStateException.class, () -> coordinator.start(PLAN));

        releaseLatch.countDown();
        assertTrue(listener.finishedLatch().await(25, TimeUnit.SECONDS));
    }

    @Test
    void aJobThatPublishesNothingIsNotAnError() throws Exception {
        FakeScanJob emptyJob = new FakeScanJob("job-a", List.of());
        RecordingScanEventListener listener = new RecordingScanEventListener();
        ScanCoordinator coordinator = new ScanCoordinator(listener, plan -> List.of(emptyJob));

        coordinator.start(PLAN);
        assertTrue(listener.finishedLatch().await(25, TimeUnit.SECONDS));

        List<WorkerStatus> statuses = listener.workerStatuses();
        WorkerStatus last = statuses.get(statuses.size() - 1);
        assertEquals(WorkerStatus.State.DONE, last.state());
        assertEquals(0, last.findingsPublished());
        assertEquals(ScanCompletion.COMPLETED, listener.outcome().result());
    }
}
