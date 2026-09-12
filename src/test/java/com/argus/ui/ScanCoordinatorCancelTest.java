package com.argus.ui;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.argus.core.Subdomain;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Section 6.7: {@code ScanCoordinatorCancelTest}. */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class ScanCoordinatorCancelTest {

    private static final ScanPlan PLAN = ScanPlan.of("example.com");

    private static List<Object> findingsFor(String prefix, int count) {
        List<Object> findings = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            findings.add(new Subdomain(prefix + i + ".example.com"));
        }
        return findings;
    }

    @Test
    void cancelCallsCancelOnEveryJob() throws Exception {
        CountDownLatch startedLatch = new CountDownLatch(1);
        CountDownLatch releaseLatch = new CountDownLatch(1);
        FakeScanJob jobA = new FakeScanJob(
                "job-a", findingsFor("a", 5), null, startedLatch, releaseLatch);
        FakeScanJob jobB = new FakeScanJob("job-b", findingsFor("b", 5));
        RecordingScanEventListener listener = new RecordingScanEventListener();
        // Pool size is jobs.size() = 2, but only job-a is gated; job-b may or may not run
        // before cancel() lands -- we only assert cancel() was called on both.
        ScanCoordinator coordinator = new ScanCoordinator(listener, plan -> List.of(jobA, jobB));

        coordinator.start(PLAN);
        assertTrue(startedLatch.await(10, TimeUnit.SECONDS));
        coordinator.cancel();
        releaseLatch.countDown();

        assertTrue(listener.finishedLatch().await(25, TimeUnit.SECONDS));
        assertTrue(jobA.wasCancelled());
        assertTrue(jobB.wasCancelled());
    }

    @Test
    void cancelDeliversPartialFindingsAndFinishesWithCancelled() throws Exception {
        CountDownLatch startedLatch = new CountDownLatch(1);
        CountDownLatch releaseLatch = new CountDownLatch(1);
        FakeScanJob job = new FakeScanJob(
                "job-a", findingsFor("a", 500), null, startedLatch, releaseLatch);
        RecordingScanEventListener listener = new RecordingScanEventListener();
        ScanCoordinator coordinator = new ScanCoordinator(listener, plan -> List.of(job));

        coordinator.start(PLAN);
        assertTrue(startedLatch.await(10, TimeUnit.SECONDS));
        coordinator.cancel();
        releaseLatch.countDown();

        assertTrue(listener.finishedLatch().await(25, TimeUnit.SECONDS));

        List<FindingRow> delivered = listener.findings();
        assertTrue(delivered.size() < 500,
                "expected fewer than 500 delivered, got " + delivered.size());
        List<FindingRow> expected = FindingRows.of(findingsFor("a", 500));
        for (FindingRow row : delivered) {
            assertTrue(expected.contains(row), "unexpected finding: " + row);
        }
        assertEquals(ScanOutcome.Result.CANCELLED, listener.outcome().result());
    }

    @Test
    void cancelIsIdempotentAndSafeFromMultipleThreads() throws Exception {
        FakeScanJob job = new FakeScanJob("job-a", findingsFor("a", 5));
        RecordingScanEventListener listener = new RecordingScanEventListener();
        ScanCoordinator coordinator = new ScanCoordinator(listener, plan -> List.of(job));
        coordinator.start(PLAN);

        int cancellerCount = 3;
        CountDownLatch ready = new CountDownLatch(cancellerCount);
        CountDownLatch go = new CountDownLatch(1);
        List<Thread> cancellers = new ArrayList<>();
        for (int i = 0; i < cancellerCount; i++) {
            Thread canceller = new Thread(() -> assertDoesNotThrow(() -> {
                ready.countDown();
                go.await();
                coordinator.cancel();
            }));
            cancellers.add(canceller);
            canceller.start();
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        go.countDown();
        for (Thread canceller : cancellers) {
            canceller.join(10_000);
            assertFalse(canceller.isAlive());
        }

        assertTrue(listener.finishedLatch().await(25, TimeUnit.SECONDS));
        long finishedCount = listener.events().stream().filter(e -> e.startsWith("finished:")).count();
        assertEquals(1, finishedCount);
    }

    @Test
    void cancelWhilePausedTerminatesEverything() throws Exception {
        CountDownLatch startedLatch = new CountDownLatch(1);
        CountDownLatch releaseLatch = new CountDownLatch(1);
        FakeScanJob job = new FakeScanJob(
                "job-a", findingsFor("a", 50), null, startedLatch, releaseLatch);
        RecordingScanEventListener listener = new RecordingScanEventListener();
        ScanCoordinator coordinator = new ScanCoordinator(listener, plan -> List.of(job), 4, 8);

        coordinator.start(PLAN);
        assertTrue(startedLatch.await(10, TimeUnit.SECONDS));
        coordinator.pause();
        releaseLatch.countDown();

        // let the pipeline fill to capacity while paused
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        boolean full = false;
        while (System.nanoTime() < deadline) {
            if (coordinator.currentPipelineSizeForTest() == 4) {
                full = true;
                break;
            }
            Thread.onSpinWait();
        }
        assertTrue(full, "expected the pipeline to fill while paused");

        coordinator.cancel();

        assertTrue(listener.finishedLatch().await(25, TimeUnit.SECONDS));
        ScanCoordinatorTest.awaitPoolTerminatedAndThreadsFinished(coordinator);
        long finishedCount = listener.events().stream().filter(e -> e.startsWith("finished:")).count();
        assertEquals(1, finishedCount);
        assertEquals(ScanOutcome.Result.CANCELLED, listener.outcome().result());
    }

    @Test
    void cancelBeforeStartIsANoOp() throws Exception {
        FakeScanJob job = new FakeScanJob("job-a", findingsFor("a", 5));
        RecordingScanEventListener listener = new RecordingScanEventListener();
        ScanCoordinator coordinator = new ScanCoordinator(listener, plan -> List.of(job));

        assertDoesNotThrow(coordinator::cancel);

        coordinator.start(PLAN);
        assertTrue(listener.finishedLatch().await(25, TimeUnit.SECONDS));
        assertEquals(5, listener.findings().size());
        assertEquals(ScanOutcome.Result.COMPLETED, listener.outcome().result());
    }

    @Test
    void cancelDoesNotBlockItsCaller() throws Exception {
        CountDownLatch startedLatch = new CountDownLatch(1);
        CountDownLatch releaseLatch = new CountDownLatch(1);
        FakeScanJob job = new FakeScanJob(
                "job-a", findingsFor("a", 5), null, startedLatch, releaseLatch);
        RecordingScanEventListener listener = new RecordingScanEventListener();
        ScanCoordinator coordinator = new ScanCoordinator(listener, plan -> List.of(job));

        coordinator.start(PLAN);
        assertTrue(startedLatch.await(10, TimeUnit.SECONDS));

        // cancel() must return even though the job is still in flight (blocked on releaseLatch)
        assertDoesNotThrow(coordinator::cancel);
        assertEquals(1, releaseLatch.getCount(), "job should still be in flight when cancel() returns");

        releaseLatch.countDown();
        assertTrue(listener.finishedLatch().await(25, TimeUnit.SECONDS));
    }

    @Test
    void noListenerCallbackArrivesAfterOnScanFinished() throws Exception {
        CountDownLatch startedLatch = new CountDownLatch(1);
        CountDownLatch releaseLatch = new CountDownLatch(1);
        FakeScanJob job = new FakeScanJob(
                "job-a", findingsFor("a", 5), null, startedLatch, releaseLatch);
        RecordingScanEventListener listener = new RecordingScanEventListener();
        ScanCoordinator coordinator = new ScanCoordinator(listener, plan -> List.of(job));

        coordinator.start(PLAN);
        assertTrue(startedLatch.await(10, TimeUnit.SECONDS));
        coordinator.cancel();
        releaseLatch.countDown();

        assertTrue(listener.finishedLatch().await(25, TimeUnit.SECONDS));
        List<String> events = listener.events();
        assertTrue(events.get(events.size() - 1).startsWith("finished:"));
    }
}
