package com.argus.ui;

import com.argus.core.ScanArchiveException;
import com.argus.core.ScanCompletion;
import com.argus.core.ScanFeed;
import com.argus.core.ScanPipeline;
import com.argus.core.ScanRun;
import java.lang.System.Logger.Level;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Owns one scan's lifecycle: the producer pool, the {@code ScanPipeline}, the consumer thread,
 * the supervisor thread and the pause gate. Toolkit-free — ZERO {@code javafx.*} imports, so
 * the whole thing is unit-tested headless with fake jobs and a recording listener.
 *
 * One instance serves the whole dashboard session: {@link #start} may be called again after a
 * scan ends, but never while one is running.
 *
 * Executes P1-03 §4.6's shutdown ordering. {@link #close()} is what {@code App.stop()} calls
 * (invariant 6).
 */
final class ScanCoordinator implements AutoCloseable {

    private static final System.Logger LOGGER = System.getLogger(ScanCoordinator.class.getName());

    static final int BATCH_LIMIT = 256;

    private static final long SHUTDOWN_GRACE_MILLIS = 5_000;
    private static final long FORCE_GRACE_MILLIS = 5_000;
    private static final long JOIN_GRACE_MILLIS = 5_000;
    private static final long STARTED_SIGNAL_GRACE_MILLIS = 3_000;

    private enum RunState { IDLE, RUNNING, STOPPING, CLOSED }

    private final ScanEventListener listener;
    private final ScanJobFactory factory;
    private final ScanSaver saver;
    private final int pipelineCapacity;
    private final int batchLimit;

    private final Object lock = new Object();

    private RunState state = RunState.IDLE;                                   // @GuardedBy("lock")
    private ExecutorService pool;                                             // @GuardedBy("lock")
    private ScanPipeline<Object> pipeline;                                    // @GuardedBy("lock")
    /**
     * One fresh gate per scan, not a coordinator-lifetime singleton: {@code PauseGate.stop()}
     * is permanent by design (§3.4), and the §4.6 shutdown sequence calls it at the end of
     * EVERY scan (step 6) so a consumer parked on a pause can never block a shutdown. Reusing
     * one instance across scans would therefore leave every scan after the first permanently
     * un-pausable.
     */
    private PauseGate pauseGate;                                              // @GuardedBy("lock")
    private Thread consumerThread;                                            // @GuardedBy("lock")
    private Thread supervisorThread;                                          // @GuardedBy("lock")
    private List<ScanJob> jobs;                                               // @GuardedBy("lock")
    private final List<Future<Integer>> futures = new ArrayList<>();          // @GuardedBy("lock")
    private final Map<String, WorkerStatus> workers = new LinkedHashMap<>();  // @GuardedBy("lock")
    private int completedJobs;                                                // @GuardedBy("lock")
    private int failedJobs;                                                   // @GuardedBy("lock")
    private int findingsDelivered;                                            // @GuardedBy("lock")
    private final List<Object> collectedFindings = new ArrayList<>();         // @GuardedBy("lock")
    private Instant scanStartedAt;                                            // @GuardedBy("lock")

    ScanCoordinator(ScanEventListener listener) {
        this(listener, new DefaultScanJobFactory());
    }

    ScanCoordinator(ScanEventListener listener, ScanJobFactory factory) {
        this(listener, factory, ScanSaver.none());
    }

    ScanCoordinator(ScanEventListener listener, ScanJobFactory factory, ScanSaver saver) {
        this(listener, factory, saver, ScanPipeline.DEFAULT_CAPACITY, BATCH_LIMIT);
    }

    ScanCoordinator(ScanEventListener listener, ScanJobFactory factory, int pipelineCapacity,
            int batchLimit) {
        this(listener, factory, ScanSaver.none(), pipelineCapacity, batchLimit);
    }

    ScanCoordinator(ScanEventListener listener, ScanJobFactory factory, ScanSaver saver,
            int pipelineCapacity, int batchLimit) {
        this.listener = Objects.requireNonNull(listener, "listener");
        this.factory = Objects.requireNonNull(factory, "factory");
        this.saver = Objects.requireNonNull(saver, "saver");
        this.pipelineCapacity = pipelineCapacity;
        this.batchLimit = batchLimit;
    }

    /**
     * Non-blocking. Starts the pool, the pipeline, the consumer and the supervisor.
     *
     * @throws IllegalStateException if a scan is already running, or this coordinator is closed
     */
    void start(ScanPlan plan) {
        Objects.requireNonNull(plan, "plan");
        List<ScanJob> planJobs = List.copyOf(factory.jobsFor(plan));

        ScanPipeline<Object> newPipeline;
        ExecutorService newPool;
        PauseGate newPauseGate;
        synchronized (lock) {
            if (state == RunState.CLOSED) {
                throw new IllegalStateException("ScanCoordinator is closed");
            }
            if (state != RunState.IDLE) {
                throw new IllegalStateException("a scan is already running");
            }
            state = RunState.RUNNING;
            jobs = planJobs;
            futures.clear();
            workers.clear();
            completedJobs = 0;
            failedJobs = 0;
            findingsDelivered = 0;
            collectedFindings.clear();
            scanStartedAt = Instant.now();
            newPipeline = new ScanPipeline<>(pipelineCapacity);
            pipeline = newPipeline;
            newPauseGate = new PauseGate();
            pauseGate = newPauseGate;
            newPool = Executors.newFixedThreadPool(Math.max(1, planJobs.size()),
                    new NamedDaemonThreadFactory("argus-producer-"));
            pool = newPool;
            for (ScanJob job : planJobs) {
                workers.put(job.name(),
                        new WorkerStatus(job.name(), null, WorkerStatus.State.PENDING, 0));
            }
        }

        Thread consumer = new Thread(
                () -> consumerLoop(newPipeline, newPauseGate), "argus-scan-consumer");
        consumer.setDaemon(true);

        List<String> jobNames = new ArrayList<>();
        for (ScanJob job : planJobs) {
            jobNames.add(job.name());
        }

        Thread supervisor = new Thread(
                () -> supervisorLoop(plan, planJobs, jobNames, newPipeline, newPool, newPauseGate,
                        consumer),
                "argus-scan-supervisor");
        supervisor.setDaemon(true);

        synchronized (lock) {
            consumerThread = consumer;
            supervisorThread = supervisor;
        }

        consumer.start();
        supervisor.start();
    }

    /** Non-blocking, idempotent. No-op when not running. */
    void pause() {
        PauseGate gate;
        synchronized (lock) {
            gate = pauseGate;
        }
        if (gate != null) {
            gate.pause();
        }
    }

    void resume() {
        PauseGate gate;
        synchronized (lock) {
            gate = pauseGate;
        }
        if (gate != null) {
            gate.resume();
        }
    }

    /**
     * Non-blocking, idempotent, safe from any thread. The scan winds down asynchronously and
     * {@code onScanFinished(CANCELLED)} still fires. Bounded by one connect timeout for
     * in-flight probes (P1-01 §4.3) — the UI must say so, not promise an instant stop.
     */
    void cancel() {
        List<ScanJob> jobsSnapshot;
        ExecutorService poolSnapshot;
        List<Future<Integer>> futuresSnapshot;
        PauseGate pauseGateSnapshot;
        synchronized (lock) {
            if (state != RunState.RUNNING) {
                return;
            }
            state = RunState.STOPPING;
            jobsSnapshot = jobs;
            poolSnapshot = pool;
            futuresSnapshot = List.copyOf(futures);
            pauseGateSnapshot = pauseGate;
        }
        // Three independent release valves for the cancel-while-paused deadlock (§4.4):
        // stop() unparks the consumer, job.cancel() + future.cancel() stop not-yet-started or
        // best-effort-cancellable work, and shutdownNow() interrupts in-flight producers.
        if (pauseGateSnapshot != null) {
            pauseGateSnapshot.stop();
        }
        if (jobsSnapshot != null) {
            for (ScanJob job : jobsSnapshot) {
                job.cancel();
            }
        }
        for (Future<Integer> future : futuresSnapshot) {
            future.cancel(true);
        }
        if (poolSnapshot != null) {
            poolSnapshot.shutdownNow();
        }
    }

    boolean isRunning() {
        synchronized (lock) {
            return state == RunState.RUNNING || state == RunState.STOPPING;
        }
    }

    boolean isPaused() {
        PauseGate gate;
        synchronized (lock) {
            gate = pauseGate;
        }
        return gate != null && gate.isPaused();
    }

    /** BLOCKING, bounded, idempotent. {@code cancel()} + join the supervisor. */
    @Override
    public void close() {
        cancel();
        Thread supervisor;
        synchronized (lock) {
            supervisor = supervisorThread;
        }
        if (supervisor != null) {
            try {
                supervisor.join(JOIN_GRACE_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        synchronized (lock) {
            state = RunState.CLOSED;
        }
    }

    // --- package-private observation seams for tests ---

    boolean isPoolTerminated() {
        ExecutorService currentPool;
        synchronized (lock) {
            currentPool = pool;
        }
        return currentPool == null || currentPool.isTerminated();
    }

    boolean areThreadsFinished() {
        Thread consumer;
        Thread supervisor;
        synchronized (lock) {
            consumer = consumerThread;
            supervisor = supervisorThread;
        }
        return (consumer == null || !consumer.isAlive())
                && (supervisor == null || !supervisor.isAlive());
    }

    /** Test-only seam: the current pipeline's queued-item count, or 0 when none exists yet. */
    int currentPipelineSizeForTest() {
        ScanPipeline<Object> currentPipeline;
        synchronized (lock) {
            currentPipeline = pipeline;
        }
        return currentPipeline == null ? 0 : currentPipeline.size();
    }

    // --- the consumer loop (§4.3) ---

    private void consumerLoop(ScanFeed<Object> feed, PauseGate pauseGate) {
        try {
            while (true) {
                pauseGate.awaitResume();           // parks here while paused; NO lock held
                Optional<Object> first = feed.take();
                if (first.isEmpty()) {
                    break;                          // end of stream: closed AND drained
                }
                List<Object> raw = new ArrayList<>();
                raw.add(first.get());
                raw.addAll(feed.drainAvailable(Math.max(1, batchLimit - 1)));
                List<FindingRow> rows = FindingRows.of(raw);   // mapping happens here, off FX

                ScanProgress snapshot;
                synchronized (lock) {
                    findingsDelivered += rows.size();
                    collectedFindings.addAll(raw);
                    snapshot = progressSnapshot();
                }
                listener.onFindings(rows);          // listener wraps this in ONE runLater
                listener.onProgress(snapshot);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // --- the supervisor loop and the §4.6 shutdown sequence (§4.4) ---

    private void supervisorLoop(ScanPlan plan, List<ScanJob> planJobs, List<String> jobNames,
            ScanPipeline<Object> pipelineRef, ExecutorService poolRef, PauseGate pauseGate,
            Thread consumer) {
        listener.onScanStarted(plan, jobNames);
        for (ScanJob job : planJobs) {
            listener.onWorkerStatus(snapshotOf(job.name()));
        }

        List<Future<Integer>> submitted = new ArrayList<>();
        List<CountDownLatch> startedSignals = new ArrayList<>();
        for (ScanJob job : planJobs) {
            CountDownLatch startedSignal = new CountDownLatch(1);
            Future<Integer> future;
            synchronized (lock) {
                if (state != RunState.RUNNING) {
                    break;
                }
                future = poolRef.submit(() -> {
                    updateWorkerStatus(job.name(), WorkerStatus.State.RUNNING,
                            Thread.currentThread().getName(), 0);
                    startedSignal.countDown();
                    return job.run(pipelineRef);
                });
                futures.add(future);
            }
            submitted.add(future);
            startedSignals.add(startedSignal);
        }

        for (int i = 0; i < submitted.size(); i++) {
            ScanJob job = planJobs.get(i);
            Future<Integer> future = submitted.get(i);
            CountDownLatch startedSignal = startedSignals.get(i);

            reportRunningIfStarted(job.name(), startedSignal);

            try {
                int published = future.get();
                WorkerStatus finished = updateWorkerStatus(
                        job.name(), WorkerStatus.State.DONE, null, published);
                synchronized (lock) {
                    completedJobs++;
                }
                listener.onLog("[" + job.name() + "] finished · " + published + " findings");
                listener.onWorkerStatus(finished);
            } catch (CancellationException e) {
                reportEndedDueToCancellation(job.name());
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof InterruptedException) {
                    reportEndedDueToCancellation(job.name());
                } else {
                    WorkerStatus failed = updateWorkerStatus(job.name(), WorkerStatus.State.FAILED,
                            null, currentPublished(job.name()));
                    synchronized (lock) {
                        completedJobs++;
                        failedJobs++;
                    }
                    String exceptionName = cause == null
                            ? e.getClass().getSimpleName() : cause.getClass().getSimpleName();
                    listener.onLog("[" + job.name() + "] failed · " + exceptionName);
                    listener.onWorkerStatus(failed);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            listener.onProgress(progressSnapshotSafe());
        }

        // §4.6 shutdown ordering, executed literally:
        shutdownPool(poolRef);                  // 1. shutdown -> awaitTermination -> shutdownNow
        pipelineRef.close();                    // 2. ONLY now: every producer has terminated
        pauseGate.stop();                       // 3. release the consumer if parked on a pause
        try {
            consumer.join(JOIN_GRACE_MILLIS);   // 4. it drains the remainder, then exits
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // One definitive, final progress snapshot, taken only now that the consumer has fully
        // joined. Without this, a per-batch snapshot captured under lock by the consumer (but
        // delivered to the listener AFTER releasing it, per §4.3) can race with the
        // supervisor's own per-job update and be recorded LAST despite carrying a stale
        // completedJobs -- this call makes "the last ScanProgress reflects reality" true by
        // construction rather than by timing (T-S5).
        listener.onProgress(progressSnapshotSafe());

        // 6. Derive the completion and build the ScanRun under lock; the run is immutable once
        // built, so it is safely published to the saver with no further locking.
        ScanRun run;
        int failedJobsSnapshot;
        synchronized (lock) {
            ScanCompletion completion;
            if (state == RunState.STOPPING) {
                completion = ScanCompletion.CANCELLED;
            } else if (failedJobs > 0) {
                completion = ScanCompletion.COMPLETED_WITH_ERRORS;
            } else {
                completion = ScanCompletion.COMPLETED;
            }
            failedJobsSnapshot = failedJobs;
            run = new ScanRun(plan.target(), scanStartedAt, Instant.now(), completion,
                    List.copyOf(collectedFindings));
        }

        // 7. Save, blocking, with NO LOCK HELD -- holding the monitor across file I/O + SQL
        // would block a concurrent cancel()/pause() for the duration of a disk write.
        Long savedScanId;
        try {
            savedScanId = saver.save(run);
            listener.onLog("scan saved · id " + savedScanId);
        } catch (ScanArchiveException e) {
            savedScanId = null;
            listener.onLog("scan NOT saved · " + e.getMessage());
        }

        // 8. Only now does the coordinator become available for the next scan -- so start()
        // cannot clear collectedFindings while it is still being written.
        synchronized (lock) {
            if (state != RunState.CLOSED) {
                state = RunState.IDLE;
            }
        }

        // 9. Still exactly once, still last.
        listener.onScanFinished(new ScanOutcome(run, failedJobsSnapshot, savedScanId));
    }

    /**
     * Emits the RUNNING transition with the job's real pool-thread name, but only if the job
     * actually started (it may never start if cancelled before the pool picked it up).
     * Waiting on {@code startedSignal} (rather than busy-polling) is safe because it happens
     * after {@code future} was already submitted to a pool sized to the job count — every
     * submitted job starts almost immediately unless cancelled first, in which case the bound
     * below still guarantees this method returns.
     */
    private void reportRunningIfStarted(String jobName, CountDownLatch startedSignal) {
        boolean started;
        try {
            started = startedSignal.await(STARTED_SIGNAL_GRACE_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            started = false;
        }
        if (started) {
            WorkerStatus running = snapshotOf(jobName);
            listener.onLog("[" + running.threadName() + "] " + jobName + " started");
            listener.onWorkerStatus(running);
        }
    }

    private void reportEndedDueToCancellation(String jobName) {
        WorkerStatus cancelled = updateWorkerStatus(
                jobName, WorkerStatus.State.CANCELLED, null, currentPublished(jobName));
        synchronized (lock) {
            completedJobs++;
        }
        listener.onWorkerStatus(cancelled);
    }

    private WorkerStatus snapshotOf(String jobName) {
        synchronized (lock) {
            return workers.get(jobName);
        }
    }

    private int currentPublished(String jobName) {
        synchronized (lock) {
            WorkerStatus status = workers.get(jobName);
            return status == null ? 0 : status.findingsPublished();
        }
    }

    private WorkerStatus updateWorkerStatus(String jobName, WorkerStatus.State state,
            String threadName, int findingsPublished) {
        synchronized (lock) {
            WorkerStatus existing = workers.get(jobName);
            String effectiveThreadName = threadName != null ? threadName
                    : existing == null ? null : existing.threadName();
            WorkerStatus updated =
                    new WorkerStatus(jobName, effectiveThreadName, state, findingsPublished);
            workers.put(jobName, updated);
            return updated;
        }
    }

    private ScanProgress progressSnapshot() {
        return new ScanProgress(completedJobs, jobs == null ? 0 : jobs.size(), findingsDelivered);
    }

    private ScanProgress progressSnapshotSafe() {
        synchronized (lock) {
            return progressSnapshot();
        }
    }

    /** {@code shutdown()} -&gt; bounded {@code awaitTermination()} -&gt; {@code shutdownNow()} (invariant 6). */
    private void shutdownPool(ExecutorService poolToShutdown) {
        poolToShutdown.shutdown();
        try {
            if (!poolToShutdown.awaitTermination(SHUTDOWN_GRACE_MILLIS, TimeUnit.MILLISECONDS)) {
                poolToShutdown.shutdownNow();
                if (!poolToShutdown.awaitTermination(FORCE_GRACE_MILLIS, TimeUnit.MILLISECONDS)) {
                    LOGGER.log(Level.WARNING, "scan producer pool did not terminate");
                }
            }
        } catch (InterruptedException e) {
            poolToShutdown.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Names threads {@code argus-producer-1}, {@code argus-producer-2}, ... Its own counter is
     * {@code synchronized} rather than an {@code AtomicInteger}, matching {@code PortScanner}'s
     * {@code NamedDaemonThreadFactory} (P1-01 §4.4) and this codebase's rule of explicit lock
     * discipline for every compound operation, even one this narrow.
     */
    private static final class NamedDaemonThreadFactory implements ThreadFactory {
        private final String prefix;
        private final Object counterLock = new Object();
        private int nextId = 1;

        NamedDaemonThreadFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(Runnable runnable) {
            int id;
            synchronized (counterLock) {
                id = nextId++;
            }
            Thread thread = new Thread(runnable, prefix + id);
            thread.setDaemon(true);
            return thread;
        }
    }
}
