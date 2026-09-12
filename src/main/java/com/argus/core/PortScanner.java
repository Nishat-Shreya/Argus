package com.argus.core;

import java.io.IOException;
import java.lang.System.Logger.Level;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.NoRouteToHostException;
import java.net.PortUnreachableException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Multithreaded TCP connect scan of one host. Single use: one instance performs one scan.
 * Discovery only — connect and close, no data is sent or read. Authorized targets only.
 * No JavaFX (invariant 2): callers in com.argus.ui must run scan() on a background thread.
 *
 * Two scan entry points share one instance's single-use contract (P1-08): the batch
 * {@link #scan()}, which blocks until every port is probed and returns one ascending-by-port
 * list, and the streaming {@link #scan(ScanSink)}, which publishes each {@link PortResult} into
 * a caller-supplied {@link ScanSink} as its probe completes. Calling either method twice, in
 * any combination, throws {@link IllegalStateException} — there is one worker pool per
 * instance, created once. {@link #pause()}/{@link #resume()} gate only the streaming overload's
 * submit loop (see {@link #pause()}); {@link #cancel()} applies identically to both.
 */
public final class PortScanner {

    private static final System.Logger LOGGER = System.getLogger(PortScanner.class.getName());
    private static final long SHUTDOWN_GRACE_MILLIS = 5_000;
    private static final long FORCE_GRACE_MILLIS = 5_000;

    /** NEW -&gt; RUNNING -&gt; FINISHED | CANCELLED. Every transition happens under {@link #lock}. */
    private enum ScanState { NEW, RUNNING, FINISHED, CANCELLED }

    private final ScanRequest request;
    private final SocketConnector connector;
    private final ThreadFactory threadFactory;

    private final Object lock = new Object();
    private ScanState state = ScanState.NEW;                             // @GuardedBy("lock")
    private ExecutorService pool;                                        // @GuardedBy("lock")
    private final List<Future<PortResult>> futures = new ArrayList<>();  // @GuardedBy("lock")
    private boolean paused;                                              // @GuardedBy("lock")
    private boolean awaitingResume;                                      // @GuardedBy("lock")

    public PortScanner(ScanRequest request) {
        this(request, new TcpSocketConnector(), defaultThreadFactory());
    }

    /** Package-private seam for tests: inject a fake connector and/or a recording thread factory. */
    PortScanner(ScanRequest request, SocketConnector connector, ThreadFactory threadFactory) {
        this.request = Objects.requireNonNull(request, "request");
        this.connector = Objects.requireNonNull(connector, "connector");
        this.threadFactory = Objects.requireNonNull(threadFactory, "threadFactory");
    }

    /** Daemon threads named {@code argus-scan-1}, {@code argus-scan-2}, … (§4.4 of the plan). */
    static ThreadFactory defaultThreadFactory() {
        return new NamedDaemonThreadFactory();
    }

    /**
     * Names threads {@code argus-scan-1}, {@code argus-scan-2}, … Its own counter is
     * {@code synchronized} rather than an {@code AtomicInteger} or {@code volatile}, matching
     * this codebase's rule of explicit lock discipline for every compound operation, even one
     * this narrow.
     */
    private static final class NamedDaemonThreadFactory implements ThreadFactory {
        private final Object counterLock = new Object();
        private int nextId = 1;

        @Override
        public Thread newThread(Runnable runnable) {
            int id;
            synchronized (counterLock) {
                id = nextId++;
            }
            Thread thread = new Thread(runnable, "argus-scan-" + id);
            thread.setDaemon(true);
            return thread;
        }
    }

    /**
     * Runs the scan and blocks until every port has been probed. Results are ascending by
     * port. Never returns without the pool having been shut down (invariant 6). Blocking:
     * {@code com.argus.ui} callers must run this off the FX Application Thread (invariant 3).
     */
    public List<PortResult> scan() throws UnknownHostException, InterruptedException {
        InetAddress address = InetAddress.getByName(request.host());

        try {
            startPool();

            for (int port : request.ports()) {
                Callable<PortResult> task = () -> probeOne(address, port);
                synchronized (lock) {
                    if (state != ScanState.RUNNING) {
                        break;
                    }
                    futures.add(pool.submit(task));
                }
            }

            List<Future<PortResult>> snapshot;
            synchronized (lock) {
                snapshot = List.copyOf(futures);
            }

            List<PortResult> results = new ArrayList<>();
            for (Future<PortResult> future : snapshot) {
                PortResult result = harvest(future);
                if (result != null) {
                    results.add(result);
                }
            }
            results.sort(Comparator.comparingInt(PortResult::port));

            markFinishedIfRunning();

            return List.copyOf(results);
        } finally {
            shutdownPool();
        }
    }

    /**
     * Streaming scan: publishes each {@link PortResult} into {@code sink} as its probe
     * completes, then returns when every submitted probe has been accounted for. Publication
     * order is completion order and is NOT specified — callers that need ascending ports use
     * {@link #scan()}.
     *
     * Single-use, shared with {@code scan()}: calling either method twice, in any combination,
     * throws {@link IllegalStateException}.
     *
     * BLOCKING on two counts: probe completion, and {@code sink.publish()} back-pressure. Never
     * call on the FX Application Thread (invariant 3).
     *
     * Latency bounds (§4.6 of the plan — {@code java.net.Socket.connect} is not interruptible
     * by {@code Thread.interrupt}, JEP 353 / SEI CERT THI04-J): {@link #pause()} means "start no
     * further ports", not "suspend the scan". When {@code pause()} returns, up to {@code
     * effectiveThreadCount()} probes are still running and will complete — each within one
     * {@code connectTimeout} — and their results will still be published; pause is not a
     * publication mute. {@link #cancel()}'s latency is unchanged: bounded by one {@code
     * connectTimeout} for an in-flight probe, immediate for anything not yet started.
     *
     * @throws NullPointerException  if sink is null (thrown before any I/O and before any pool
     *                               thread is created)
     * @throws UnknownHostException  if the request host does not resolve (before any probe)
     * @throws InterruptedException  if the calling thread is interrupted while waiting for a
     *                               probe, while parked in the pause gate, or inside publish();
     *                               findings already published stay published
     * @throws IllegalStateException if the sink is a closed pipeline (P1-03's contract is
     *                               propagated, never softened into a silent drop)
     */
    public void scan(ScanSink<? super PortResult> sink)
            throws UnknownHostException, InterruptedException {
        Objects.requireNonNull(sink, "sink");
        InetAddress address = InetAddress.getByName(request.host());

        try {
            startPool();

            ExecutorService currentPool;
            synchronized (lock) {
                currentPool = pool;
            }
            CompletionService<PortResult> completionService =
                    new ExecutorCompletionService<>(currentPool);

            int window = effectiveThreadCount();
            Iterator<Integer> portIterator = request.ports().iterator();
            int outstanding = 0;
            boolean submitting = true;

            while (submitting || outstanding > 0) {
                while (submitting && outstanding < window && portIterator.hasNext()) {
                    int port = portIterator.next();
                    Callable<PortResult> task = () -> probeOne(address, port);
                    if (!awaitResumeThenSubmit(completionService, task)) {
                        submitting = false;
                        break;
                    }
                    outstanding++;
                }
                if (!portIterator.hasNext()) {
                    submitting = false;
                }
                if (outstanding == 0) {
                    break;
                }

                Future<PortResult> done = completionService.take(); // BLOCKING, outside lock
                outstanding--;
                PortResult result = harvest(done);
                if (result != null) {
                    sink.publish(result); // BLOCKING, outside lock
                }
            }

            markFinishedIfRunning();
        } finally {
            shutdownPool();
        }
    }

    /**
     * The gate and the submit are one critical section (§4.2 of the plan), which is what makes
     * pause-vs-submit and cancel-vs-submit atomic: a port is submitted, or the loop discovers
     * the scan is no longer RUNNING — never both, never neither, and never a lost wakeup, since
     * the wait is guarded by a {@code while}, not an {@code if}.
     *
     * @return true if the task was submitted; false if the scan is no longer RUNNING (cancelled
     *         while running or while parked in the gate), in which case the caller must stop
     *         submitting
     */
    private boolean awaitResumeThenSubmit(CompletionService<PortResult> completionService,
            Callable<PortResult> task) throws InterruptedException {
        synchronized (lock) {
            while (state == ScanState.RUNNING && paused) {
                awaitingResume = true;
                try {
                    lock.wait();
                } finally {
                    awaitingResume = false;
                }
            }
            if (state != ScanState.RUNNING) {
                return false;
            }
            futures.add(completionService.submit(task));
            return true;
        }
    }

    /**
     * Stops the STREAMING submit loop ({@link #scan(ScanSink)}) from starting further ports.
     * Safe from any thread, idempotent, returns promptly, never blocks.
     *
     * Has NO effect on a scan started through the batch {@link #scan()} overload — a {@code
     * pause()} call is silently ignored by {@code scan()}, by design (§4.7 of the plan): gating
     * the shipped batch loop too would give every existing caller (including {@code
     * com.argus.ui.PortScanJob} today) a new way to block indefinitely. Pause only applies to
     * {@link #scan(ScanSink)}. It also cannot suspend a probe that has already begun: {@code
     * java.net.Socket.connect} is not interruptible (P1-01 §4.3), so at most one probe per
     * worker thread is still outstanding when {@code pause()} returns, each bounded by {@code
     * connectTimeout} (see the latency paragraph on {@link #scan(ScanSink)}).
     */
    public void pause() {
        synchronized (lock) {
            paused = true;
            lock.notifyAll();
        }
    }

    /** Releases a submit loop parked in {@link #pause()}. Idempotent; a no-op if not paused. */
    public void resume() {
        synchronized (lock) {
            paused = false;
            lock.notifyAll();
        }
    }

    public boolean isPaused() {
        synchronized (lock) {
            return paused;
        }
    }

    /** True iff the streaming submit loop is currently parked inside the pause gate. */
    boolean isAwaitingResumeForTest() {
        synchronized (lock) {
            return awaitingResume;
        }
    }

    /** {@code notifyAll()} with no state change — proves the pause guard is a while, not an if. */
    void spuriousWakeupForTest() {
        synchronized (lock) {
            lock.notifyAll();
        }
    }

    /** {@code min(threadCount, ports.size())} — never more worker threads than ports. */
    private int effectiveThreadCount() {
        return Math.min(request.threadCount(), request.ports().size());
    }

    /**
     * Creates the (single-use) worker pool and flips {@code state} to RUNNING, unless a
     * {@code cancel()} arrived first, in which case {@code state} stays CANCELLED and the
     * caller's submit loop is expected to notice via the same {@link #lock} and never submit.
     * Extracted verbatim from {@code scan()} so both overloads start identically (P1-08).
     */
    private void startPool() {
        synchronized (lock) {
            if (pool != null) {
                throw new IllegalStateException(
                        "PortScanner is single-use; scan() has already been called");
            }
            pool = Executors.newFixedThreadPool(effectiveThreadCount(), threadFactory);
            // A cancel() that arrived before this call leaves state CANCELLED; the submit
            // loop below then breaks on the first iteration instead of ever running.
            if (state == ScanState.NEW) {
                state = ScanState.RUNNING;
            }
        }
    }

    /**
     * Resolves one submitted probe's {@link Future} into its {@link PortResult}, or {@code null}
     * if the probe was cancelled or interrupted before producing one. Extracted verbatim from
     * {@code scan()} so both overloads harvest results identically (P1-08) — this is what makes
     * "streamed result set == batch result set" true by construction.
     */
    private PortResult harvest(Future<PortResult> future) throws InterruptedException {
        try {
            return future.get();
        } catch (CancellationException e) {
            return null; // no result for this port
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof InterruptedException) {
                return null; // no result for this port
            } else if (cause instanceof RuntimeException re) {
                throw re;
            } else if (cause instanceof Error err) {
                throw err;
            } else {
                throw new IllegalStateException("Unexpected scan task failure", cause);
            }
        }
    }

    /** Flips RUNNING -&gt; FINISHED; a no-op if the scan was cancelled or never started. */
    private void markFinishedIfRunning() {
        synchronized (lock) {
            if (state == ScanState.RUNNING) {
                state = ScanState.FINISHED;
            }
        }
    }

    /**
     * Requests cancellation. Safe from any thread, idempotent, returns promptly. Not-yet-started
     * ports are never probed. In-flight probes are not guaranteed to stop instantly — worst-case
     * cancellation latency is one {@code connectTimeout}, because a blocking
     * {@code java.net.Socket.connect} is not reliably interruptible (§4.3 of the plan). The
     * check-then-act against the submit loop happens under the same {@link #lock} on both sides
     * (§4.2), so a port is either submitted or cancelled — never both, never neither.
     */
    public void cancel() {
        synchronized (lock) {
            if (state == ScanState.FINISHED || state == ScanState.CANCELLED) {
                return;
            }
            state = ScanState.CANCELLED;
            // Futures are cancelled BEFORE shutdownNow(): shutdownNow() removes pending tasks
            // from the pool's queue without cancelling their Futures, so a not-yet-started
            // task's Future would never be cancelled and would never reach an
            // ExecutorCompletionService's completion queue — take() would then block forever
            // (§4.5 of the plan, R5). Do not reorder these two lines.
            for (Future<PortResult> future : futures) {
                future.cancel(true);
            }
            if (pool != null) {
                pool.shutdownNow();
            }
            lock.notifyAll(); // releases a submit loop parked in the pause gate (§4.2)
        }
    }

    public boolean isCancelled() {
        synchronized (lock) {
            return state == ScanState.CANCELLED;
        }
    }

    /** Test/lifecycle observation: true once the pool has terminated (or was never created). */
    boolean isPoolTerminated() {
        ExecutorService currentPool;
        synchronized (lock) {
            currentPool = pool;
        }
        return currentPool == null || currentPool.isTerminated();
    }

    /**
     * Classification table (catch order matters — {@code SocketTimeoutException} extends
     * {@code InterruptedIOException}, and {@code ConnectException}/{@code
     * NoRouteToHostException}/{@code PortUnreachableException} extend {@code SocketException}).
     */
    private PortResult probeOne(InetAddress address, int port) {
        if (Thread.currentThread().isInterrupted()) {
            return null;
        }
        try {
            connector.probe(address, port, request.connectTimeoutMillis());
            return new PortResult(request.host(), port, PortState.OPEN);
        } catch (SocketTimeoutException e) {
            return new PortResult(request.host(), port, PortState.FILTERED);
        } catch (ConnectException e) {
            return new PortResult(request.host(), port, PortState.CLOSED);
        } catch (NoRouteToHostException e) {
            return new PortResult(request.host(), port, PortState.FILTERED);
        } catch (PortUnreachableException e) {
            return new PortResult(request.host(), port, PortState.FILTERED);
        } catch (IOException e) {
            return new PortResult(request.host(), port, PortState.FILTERED);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /** {@code shutdown()} -&gt; bounded {@code awaitTermination()} -&gt; {@code shutdownNow()} fallback (invariant 6). */
    private void shutdownPool() {
        ExecutorService poolToShutdown;
        synchronized (lock) {
            poolToShutdown = pool;
        }
        if (poolToShutdown == null) {
            return;
        }
        poolToShutdown.shutdown();
        try {
            if (!poolToShutdown.awaitTermination(SHUTDOWN_GRACE_MILLIS, TimeUnit.MILLISECONDS)) {
                poolToShutdown.shutdownNow();
                if (!poolToShutdown.awaitTermination(FORCE_GRACE_MILLIS, TimeUnit.MILLISECONDS)) {
                    LOGGER.log(Level.WARNING, "Scan pool did not terminate");
                }
            }
        } catch (InterruptedException e) {
            poolToShutdown.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
