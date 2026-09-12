package com.argus.ui;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.argus.core.Subdomain;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Section 6.6: {@code ScanCoordinatorPauseTest}. */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class ScanCoordinatorPauseTest {

    private static final ScanPlan PLAN = ScanPlan.of("example.com");

    /**
     * Polls {@code listener.findings().size()} until it stops changing for a continuous
     * window -- the deterministic way to observe "the consumer has actually parked and
     * delivery has genuinely stopped", as opposed to sampling once at an arbitrary instant.
     */
    private static int awaitStableFindingsCount(RecordingScanEventListener listener) {
        int lastSize = listener.findings().size();
        long stableSince = System.nanoTime();
        long stableWindowNanos = TimeUnit.MILLISECONDS.toNanos(500);
        long overallDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < overallDeadline) {
            int currentSize = listener.findings().size();
            if (currentSize != lastSize) {
                lastSize = currentSize;
                stableSince = System.nanoTime();
            } else if (System.nanoTime() - stableSince >= stableWindowNanos) {
                return lastSize;
            }
            Thread.onSpinWait();
        }
        throw new AssertionError("findings count never stabilized");
    }


    private static List<Object> findingsFor(String prefix, int count) {
        List<Object> findings = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            findings.add(new Subdomain(prefix + i + ".example.com"));
        }
        return findings;
    }

    @Test
    void pauseStopsFurtherDelivery() throws Exception {
        FakeScanJob job = new FakeScanJob("job-a", findingsFor("a", 300));
        RecordingScanEventListener listener = new RecordingScanEventListener();
        ScanCoordinator coordinator = new ScanCoordinator(listener, plan -> List.of(job), 4, 8);

        listener.awaitAtLeastFindings(8);
        coordinator.start(PLAN);
        assertTrue(listener.armedFindingsLatch().await(20, TimeUnit.SECONDS));

        coordinator.pause();
        // A batch already in flight when pause() lands may still complete, so wait for the
        // delivered count to settle (stop changing) rather than comparing against a single
        // sample taken right at the pause() call -- that single-sample comparison is racy by
        // construction, not a real assertion of "paused".
        int stableSize = awaitStableFindingsCount(listener);
        assertTrue(stableSize < 300, "not everything should have been delivered yet");

        // Now prove it really stays stable -- no further delivery while still paused.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            assertEquals(stableSize, listener.findings().size(),
                    "no further delivery should have happened while paused");
            Thread.onSpinWait();
        }

        coordinator.resume();
        assertTrue(listener.finishedLatch().await(25, TimeUnit.SECONDS));
        assertEquals(300, listener.findings().size());
    }

    @Test
    void resumeFlushesEverythingWithNothingLostOrDuplicated() throws Exception {
        FakeScanJob job = new FakeScanJob("job-a", findingsFor("a", 300));
        RecordingScanEventListener listener = new RecordingScanEventListener();
        ScanCoordinator coordinator = new ScanCoordinator(listener, plan -> List.of(job), 4, 8);

        listener.awaitAtLeastFindings(8);
        coordinator.start(PLAN);
        assertTrue(listener.armedFindingsLatch().await(20, TimeUnit.SECONDS));
        coordinator.pause();
        coordinator.resume();

        assertTrue(listener.finishedLatch().await(25, TimeUnit.SECONDS));

        List<FindingRow> delivered = listener.findings();
        assertEquals(300, delivered.size());
        Set<String> subjects = new HashSet<>();
        for (FindingRow row : delivered) {
            subjects.add(row.subject());
        }
        assertEquals(300, subjects.size(), "no duplicates");
    }

    @Test
    void pauseAppliesBackpressureToProducers() throws Exception {
        CountDownLatch startedLatch = new CountDownLatch(1);
        CountDownLatch releaseLatch = new CountDownLatch(1);
        FakeScanJob job = new FakeScanJob(
                "job-a", findingsFor("a", 50), null, startedLatch, releaseLatch);
        RecordingScanEventListener listener = new RecordingScanEventListener();
        ScanCoordinator coordinator = new ScanCoordinator(listener, plan -> List.of(job), 4, 8);

        coordinator.start(PLAN);
        assertTrue(startedLatch.await(10, TimeUnit.SECONDS), "job never started");
        coordinator.pause();          // paused before the job has published anything
        releaseLatch.countDown();     // now let it publish into the paused, capacity-4 pipeline

        // Wait until the pipeline size stops changing -- the producer has filled it and is
        // parked in publish(), and the paused consumer is not draining it further. A single
        // "equals capacity" sample taken right when capacity is first observed is racy (the
        // consumer may still be mid-drain from before pause() became visible to it), so wait
        // for genuine stabilization first, the same technique as awaitStableFindingsCount.
        int stableSize = ScanCoordinatorTest.awaitStablePipelineSize(coordinator);
        assertEquals(4, stableSize, "expected the producer to have filled the pipeline");

        // And it stays there -- never exceeds capacity while still paused.
        long stableDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (System.nanoTime() < stableDeadline) {
            assertEquals(4, coordinator.currentPipelineSizeForTest());
            Thread.onSpinWait();
        }

        coordinator.resume();
        assertTrue(listener.finishedLatch().await(25, TimeUnit.SECONDS));
        assertEquals(50, listener.findings().size());
    }

    @Test
    void pauseAndResumeAreIdempotentAndSafeFromAnyThread() throws Exception {
        FakeScanJob job = new FakeScanJob("job-a", findingsFor("a", 5));
        RecordingScanEventListener listener = new RecordingScanEventListener();
        ScanCoordinator coordinator = new ScanCoordinator(listener, plan -> List.of(job));

        coordinator.start(PLAN);

        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Thread t = new Thread(() -> {
                coordinator.pause();
                coordinator.resume();
                coordinator.pause();
                coordinator.resume();
            });
            threads.add(t);
            t.start();
        }
        for (Thread t : threads) {
            t.join(10_000);
            assertFalse(t.isAlive());
        }
        coordinator.resume();

        assertTrue(listener.finishedLatch().await(25, TimeUnit.SECONDS));
    }

    @Test
    void pauseBeforeStartIsANoOp() throws Exception {
        FakeScanJob job = new FakeScanJob("job-a", findingsFor("a", 5));
        RecordingScanEventListener listener = new RecordingScanEventListener();
        ScanCoordinator coordinator = new ScanCoordinator(listener, plan -> List.of(job));

        assertDoesNotThrow(coordinator::pause);
        assertFalse(coordinator.isPaused(), "pause() before any scan must be a true no-op");

        coordinator.start(PLAN);
        assertTrue(listener.finishedLatch().await(25, TimeUnit.SECONDS));
        assertEquals(5, listener.findings().size());
    }

    @Test
    void pauseAfterTheScanFinishesIsANoOp() throws Exception {
        FakeScanJob job = new FakeScanJob("job-a", findingsFor("a", 5));
        RecordingScanEventListener listener = new RecordingScanEventListener();
        ScanCoordinator coordinator = new ScanCoordinator(listener, plan -> List.of(job));

        coordinator.start(PLAN);
        assertTrue(listener.finishedLatch().await(25, TimeUnit.SECONDS));

        assertFalse(coordinator.isPaused());
        assertDoesNotThrow(coordinator::pause);
        assertTrue(coordinator.isPaused());
        coordinator.resume();
        assertFalse(coordinator.isPaused());

        ScanCoordinatorTest.awaitNoCoordinatorThreadsAlive();
    }

    @Test
    void isPausedReflectsTheGate() throws Exception {
        FakeScanJob job = new FakeScanJob("job-a", findingsFor("a", 5));
        RecordingScanEventListener listener = new RecordingScanEventListener();
        ScanCoordinator coordinator = new ScanCoordinator(listener, plan -> List.of(job));

        assertFalse(coordinator.isPaused(), "nothing paused yet, and no scan has ever run");

        coordinator.start(PLAN);
        coordinator.pause();
        assertTrue(coordinator.isPaused());
        coordinator.resume();
        assertFalse(coordinator.isPaused());

        assertTrue(listener.finishedLatch().await(25, TimeUnit.SECONDS));
    }
}
