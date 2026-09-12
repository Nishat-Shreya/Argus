package com.argus.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * Test double for {@link ScanEventListener}: records every callback under its own lock, with
 * latches for "finished" and "N findings seen".
 */
final class RecordingScanEventListener implements ScanEventListener {

    private final Object lock = new Object();

    private final List<String> events = new ArrayList<>();         // @GuardedBy("lock")
    private final List<String> logLines = new ArrayList<>();       // @GuardedBy("lock")
    private final List<WorkerStatus> workerStatuses = new ArrayList<>(); // @GuardedBy("lock")
    private final List<FindingRow> findings = new ArrayList<>();   // @GuardedBy("lock")
    private final List<ScanProgress> progressUpdates = new ArrayList<>(); // @GuardedBy("lock")
    private final List<List<FindingRow>> batches = new ArrayList<>();    // @GuardedBy("lock")
    private final List<Thread> callbackThreads = new ArrayList<>();      // @GuardedBy("lock")
    private ScanOutcome outcome;                                    // @GuardedBy("lock")

    private final CountDownLatch finishedLatch = new CountDownLatch(1);
    private volatile CountDownLatch findingsCountdownLatch;
    private volatile int findingsTarget;

    /** Arms a latch that counts down to zero once at least {@code count} findings are seen. */
    void awaitAtLeastFindings(int count) {
        this.findingsTarget = count;
        this.findingsCountdownLatch = new CountDownLatch(1);
    }

    CountDownLatch finishedLatch() {
        return finishedLatch;
    }

    CountDownLatch armedFindingsLatch() {
        return findingsCountdownLatch;
    }

    @Override
    public void onScanStarted(ScanPlan plan, List<String> jobNames) {
        synchronized (lock) {
            events.add("started");
            callbackThreads.add(Thread.currentThread());
        }
    }

    @Override
    public void onLog(String line) {
        synchronized (lock) {
            logLines.add(line);
            callbackThreads.add(Thread.currentThread());
        }
    }

    @Override
    public void onWorkerStatus(WorkerStatus status) {
        synchronized (lock) {
            events.add("worker:" + status.jobName() + ":" + status.state());
            workerStatuses.add(status);
            callbackThreads.add(Thread.currentThread());
        }
    }

    @Override
    public void onFindings(List<FindingRow> batch) {
        CountDownLatch latchToCount;
        synchronized (lock) {
            events.add("findings:" + batch.size());
            findings.addAll(batch);
            batches.add(batch);
            callbackThreads.add(Thread.currentThread());
            latchToCount = findingsCountdownLatch;
            if (latchToCount != null && findings.size() >= findingsTarget) {
                latchToCount.countDown();
            }
        }
    }

    @Override
    public void onProgress(ScanProgress progress) {
        synchronized (lock) {
            events.add("progress:" + progress.completedJobs() + "/" + progress.totalJobs());
            progressUpdates.add(progress);
            callbackThreads.add(Thread.currentThread());
        }
    }

    @Override
    public void onScanFinished(ScanOutcome outcome) {
        synchronized (lock) {
            events.add("finished:" + outcome.result());
            this.outcome = outcome;
            callbackThreads.add(Thread.currentThread());
        }
        finishedLatch.countDown();
    }

    List<String> events() {
        synchronized (lock) {
            return List.copyOf(events);
        }
    }

    List<String> logLines() {
        synchronized (lock) {
            return List.copyOf(logLines);
        }
    }

    List<WorkerStatus> workerStatuses() {
        synchronized (lock) {
            return List.copyOf(workerStatuses);
        }
    }

    List<FindingRow> findings() {
        synchronized (lock) {
            return List.copyOf(findings);
        }
    }

    List<ScanProgress> progressUpdates() {
        synchronized (lock) {
            return List.copyOf(progressUpdates);
        }
    }

    List<List<FindingRow>> batches() {
        synchronized (lock) {
            return List.copyOf(batches);
        }
    }

    List<Thread> callbackThreads() {
        synchronized (lock) {
            return List.copyOf(callbackThreads);
        }
    }

    ScanOutcome outcome() {
        synchronized (lock) {
            return outcome;
        }
    }
}
