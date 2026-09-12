package com.argus.ui;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.argus.core.Subdomain;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Section 6.9: {@code ScanCoordinatorShutdownTest} — the §4.6 ordering proof. */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class ScanCoordinatorShutdownTest {

    private static final ScanPlan PLAN = ScanPlan.of("example.com");

    private static List<Object> findingsFor(String prefix, int count) {
        List<Object> findings = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            findings.add(new Subdomain(prefix + i + ".example.com"));
        }
        return findings;
    }

    @Test
    void closeOnAnIdleCoordinatorIsANoOp() {
        RecordingScanEventListener listener = new RecordingScanEventListener();
        ScanCoordinator coordinator = new ScanCoordinator(listener, plan -> List.of());

        assertDoesNotThrow(coordinator::close);
        ScanCoordinatorTest.awaitNoCoordinatorThreadsAlive();
    }

    @Test
    void closeDuringAScanTerminatesEverything() throws Exception {
        CountDownLatch startedLatch = new CountDownLatch(1);
        CountDownLatch releaseLatch = new CountDownLatch(1);
        FakeScanJob job = new FakeScanJob(
                "job-a", findingsFor("a", 5), null, startedLatch, releaseLatch);
        RecordingScanEventListener listener = new RecordingScanEventListener();
        ScanCoordinator coordinator = new ScanCoordinator(listener, plan -> List.of(job));

        coordinator.start(PLAN);
        assertTrue(startedLatch.await(10, TimeUnit.SECONDS));

        coordinator.close();

        assertTrue(coordinator.isPoolTerminated());
        assertTrue(coordinator.areThreadsFinished());
    }

    @Test
    void closeWhileAProducerIsParkedOnAFullPipelineTerminates() throws Exception {
        CountDownLatch startedLatch = new CountDownLatch(1);
        CountDownLatch releaseLatch = new CountDownLatch(1);
        FakeScanJob job = new FakeScanJob(
                "job-a", findingsFor("a", 50), null, startedLatch, releaseLatch);
        RecordingScanEventListener listener = new RecordingScanEventListener();
        ScanCoordinator coordinator = new ScanCoordinator(listener, plan -> List.of(job), 2, 8);

        coordinator.start(PLAN);
        assertTrue(startedLatch.await(10, TimeUnit.SECONDS));
        coordinator.pause();
        releaseLatch.countDown();

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        boolean full = false;
        while (System.nanoTime() < deadline) {
            if (coordinator.currentPipelineSizeForTest() == 2) {
                full = true;
                break;
            }
            Thread.onSpinWait();
        }
        assertTrue(full, "expected the producer to fill the pipeline while paused");

        coordinator.close();

        assertTrue(coordinator.isPoolTerminated());
        assertTrue(coordinator.areThreadsFinished());
    }

    @Test
    void noProducerEverSeesIllegalStateExceptionFromPublish() throws Exception {
        CountDownLatch startedLatch = new CountDownLatch(1);
        CountDownLatch releaseLatch = new CountDownLatch(1);
        FakeScanJob job = new FakeScanJob(
                "job-a", findingsFor("a", 50), null, startedLatch, releaseLatch);
        RecordingScanEventListener listener = new RecordingScanEventListener();
        ScanCoordinator coordinator = new ScanCoordinator(listener, plan -> List.of(job), 2, 8);

        coordinator.start(PLAN);
        assertTrue(startedLatch.await(10, TimeUnit.SECONDS));
        coordinator.pause();
        releaseLatch.countDown();

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline
                && coordinator.currentPipelineSizeForTest() != 2) {
            Thread.onSpinWait();
        }

        coordinator.close();

        assertTrue(job.illegalStateExceptionsSeen().isEmpty(),
                "a producer saw IllegalStateException from publish() -- pipeline closed too early");
    }

    @Test
    void closeIsIdempotent() {
        RecordingScanEventListener listener = new RecordingScanEventListener();
        ScanCoordinator coordinator = new ScanCoordinator(listener, plan -> List.of());

        assertDoesNotThrow(coordinator::close);
        assertDoesNotThrow(coordinator::close);
        assertDoesNotThrow(coordinator::close);
    }

    @Test
    void startAfterCloseThrowsIllegalStateException() {
        RecordingScanEventListener listener = new RecordingScanEventListener();
        FakeScanJob job = new FakeScanJob("job-a", findingsFor("a", 5));
        ScanCoordinator coordinator = new ScanCoordinator(listener, plan -> List.of(job));

        coordinator.close();

        assertThrows(IllegalStateException.class, () -> coordinator.start(PLAN));
    }

    @Test
    void closeIsSafeToCallFromAnyThread() throws Exception {
        CountDownLatch startedLatch = new CountDownLatch(1);
        CountDownLatch releaseLatch = new CountDownLatch(1);
        FakeScanJob job = new FakeScanJob(
                "job-a", findingsFor("a", 5), null, startedLatch, releaseLatch);
        RecordingScanEventListener listener = new RecordingScanEventListener();
        ScanCoordinator coordinator = new ScanCoordinator(listener, plan -> List.of(job));

        coordinator.start(PLAN);
        assertTrue(startedLatch.await(10, TimeUnit.SECONDS));
        releaseLatch.countDown();

        Thread cancelThread = new Thread(coordinator::cancel);
        Thread closeThread = new Thread(coordinator::close);
        cancelThread.start();
        closeThread.start();
        cancelThread.join(15_000);
        closeThread.join(15_000);

        assertFalse(cancelThread.isAlive());
        assertFalse(closeThread.isAlive());
    }

    @Test
    void everythingAlreadyPublishedIsStillDelivered() throws Exception {
        CountDownLatch startedLatch = new CountDownLatch(1);
        CountDownLatch releaseLatch = new CountDownLatch(1);
        // Capacity well above the finding count: the producer never blocks on publish(), so it
        // always runs to completion and its future resolves NORMALLY -- there is nothing left
        // running for close()'s cancel() to interrupt, only a paused consumer holding whatever
        // is still queued. That isolates exactly what T-D8 is about ("closing never discards")
        // from cancel()'s separate, already-tested "stops in-flight work" behaviour.
        int totalFindings = 30;
        FakeScanJob job = new FakeScanJob(
                "job-a", findingsFor("a", totalFindings), null, startedLatch, releaseLatch);
        RecordingScanEventListener listener = new RecordingScanEventListener();
        ScanCoordinator coordinator = new ScanCoordinator(listener, plan -> List.of(job), 100, 8);

        coordinator.start(PLAN);
        assertTrue(startedLatch.await(10, TimeUnit.SECONDS));
        coordinator.pause();
        releaseLatch.countDown();

        // Wait until the pipeline stabilizes with every finding accounted for (queued or
        // already delivered) -- that is the deterministic signal that the producer has
        // finished publishing all of them and returned, not merely paused mid-flight.
        int stableSize = ScanCoordinatorTest.awaitStablePipelineSize(coordinator);
        assertEquals(totalFindings, stableSize + listener.findings().size(),
                "expected every finding to be accounted for (queued + delivered)");

        coordinator.close();

        assertEquals(totalFindings, listener.findings().size());
        List<String> events = listener.events();
        int findingsIndex = -1;
        int finishedIndex = -1;
        for (int i = 0; i < events.size(); i++) {
            if (events.get(i).startsWith("findings:") && findingsIndex == -1) {
                findingsIndex = i;
            }
            if (events.get(i).startsWith("finished:")) {
                finishedIndex = i;
            }
        }
        assertTrue(findingsIndex >= 0 && finishedIndex >= 0 && findingsIndex < finishedIndex,
                "findings must be delivered before onScanFinished: " + events);
    }
}
