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
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Multithreaded TCP connect scan of one host. Single use: one instance performs one scan.
 * Discovery only — connect and close, no data is sent or read. Authorized targets only.
 * No JavaFX (invariant 2): callers in com.argus.ui must run scan() on a background thread.
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
            synchronized (lock) {
                if (pool != null) {
                    throw new IllegalStateException(
                            "PortScanner is single-use; scan() has already been called");
                }
                int effectiveThreads = Math.min(request.threadCount(), request.ports().size());
                pool = Executors.newFixedThreadPool(effectiveThreads, threadFactory);
                // A cancel() that arrived before this call leaves state CANCELLED; the submit
                // loop below then breaks on the first iteration instead of ever running.
                if (state == ScanState.NEW) {
                    state = ScanState.RUNNING;
                }
            }

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
                try {
                    PortResult result = future.get();
                    if (result != null) {
                        results.add(result);
                    }
                } catch (CancellationException e) {
                    // no result for this port
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof InterruptedException) {
                        // no result for this port
                    } else if (cause instanceof RuntimeException re) {
                        throw re;
                    } else if (cause instanceof Error err) {
                        throw err;
                    } else {
                        throw new IllegalStateException("Unexpected scan task failure", cause);
                    }
                }
            }
            results.sort(Comparator.comparingInt(PortResult::port));

            synchronized (lock) {
                if (state == ScanState.RUNNING) {
                    state = ScanState.FINISHED;
                }
            }

            return List.copyOf(results);
        } finally {
            shutdownPool();
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
            for (Future<PortResult> future : futures) {
                future.cancel(true);
            }
            if (pool != null) {
                pool.shutdownNow();
            }
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
