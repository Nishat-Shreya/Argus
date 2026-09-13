package com.argus.core;

import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Fans one {@link IntelSubject} out across every injected {@link IntelSource} that supports its
 * kind, concurrently, and collects a total per-source {@link IntelReport} — one
 * {@link IntelSourceOutcome} per injected source, in injected order, regardless of completion
 * order. Per-source failures become data; an unconfigured source is excluded rather than
 * reported as an outage; interruption aborts the whole fan-out with no partial report.
 *
 * <p><b>Construction:</b> sources are injected, never constructed here (no {@link Vault}
 * dependency in this class — see plan §3.1). The list is defensively copied; two sources with
 * the same {@link IntelSource#name()} is a construction error.
 *
 * <p><b>Concurrency contract</b> (plan §4): at most {@link #MAX_CONCURRENT_REQUESTS_PER_SOURCE}
 * request is ever in flight against one source name at a time, through this client instance,
 * even across concurrent {@link #enrich(IntelSubject)} calls — enforced by {@link SourcePermits}.
 * That cap is what keeps Censys's free-tier concurrency limit of 1 satisfied by construction
 * (plan §0.1). <b>The cap's scope is one client instance</b> — construct one
 * {@code ThreatIntelClient} for the app, not one per scan (plan §3.4, R11).
 *
 * <p>Two independent lock clusters exist in this class's collaborators — this class's own
 * {@link #lock} (guarding only {@link #closed}) and {@link SourcePermits}'s internal lock — and
 * neither is ever acquired while holding the other, so no lock-ordering question exists.
 *
 * <p><b>Deadlock argument:</b> a pool task acquires exactly one permit for the duration of one
 * {@code query()} call and never acquires a second while holding the first; no task waits on
 * another task's completion. Therefore no cycle can form. A corollary rule this class depends
 * on: {@link #enrich(IntelSubject)} must never be called from one of this client's own pool
 * threads.
 *
 * <p><b>Thread safety:</b> {@link #enrich(IntelSubject)} is safe to call concurrently from
 * multiple threads on one instance; beyond the {@code closed} flag it holds no per-call mutable
 * state. It is BLOCKING — {@code com.argus.ui} callers must run it off the FX Application Thread
 * (invariant 3).
 *
 * <p>No JavaFX (invariant 2). No rate limiting, pacing, backoff or retry of any kind (plan §3.5)
 * — a 429/503 from a source surfaces honestly as a {@code FAILED} outcome with its status code.
 */
public final class ThreatIntelClient implements AutoCloseable {

    /** Uniform across every source (plan §3.4, R6) — not configurable. */
    public static final int MAX_CONCURRENT_REQUESTS_PER_SOURCE = 1;

    /** A literal cap, never {@code availableProcessors()} (plan §4.4). */
    static final int MAX_POOL_THREADS = 8;

    private static final long SHUTDOWN_GRACE_MILLIS = 5_000;
    private static final long FORCE_GRACE_MILLIS = 5_000;

    private static final System.Logger LOGGER =
            System.getLogger(ThreatIntelClient.class.getName());

    private final List<IntelSource> sources;
    private final List<String> sourceNames;
    private final SourcePermits permits;
    private final ExecutorService pool;

    private final Object lock = new Object();
    private boolean closed; // @GuardedBy("lock")

    public ThreatIntelClient(List<IntelSource> sources) {
        this(sources, defaultThreadFactory());
    }

    /** Package-private seam for tests: inject a recording thread factory. */
    ThreatIntelClient(List<IntelSource> sources, ThreadFactory threadFactory) {
        Objects.requireNonNull(sources, "sources must not be null");
        Objects.requireNonNull(threadFactory, "threadFactory must not be null");
        List<IntelSource> copy = List.copyOf(sources); // NPE on a null element, too
        if (copy.isEmpty()) {
            throw new IllegalArgumentException("sources must not be empty");
        }
        List<String> names = new ArrayList<>(copy.size());
        Set<String> seenNames = new HashSet<>();
        for (IntelSource source : copy) {
            String name = source.name();
            if (!seenNames.add(name)) {
                throw new IllegalArgumentException("duplicate source name: " + name);
            }
            names.add(name);
        }
        this.sources = copy;
        this.sourceNames = List.copyOf(names);
        this.permits = new SourcePermits(names, MAX_CONCURRENT_REQUESTS_PER_SOURCE);
        this.pool = Executors.newFixedThreadPool(
                Math.min(copy.size(), MAX_POOL_THREADS), threadFactory);
    }

    /** Daemon threads named {@code argus-intel-1}, {@code argus-intel-2}, … */
    static ThreadFactory defaultThreadFactory() {
        return new NamedDaemonThreadFactory();
    }

    private static final class NamedDaemonThreadFactory implements ThreadFactory {
        private final Object counterLock = new Object();
        private int nextId = 1;

        @Override
        public Thread newThread(Runnable runnable) {
            int id;
            synchronized (counterLock) {
                id = nextId++;
            }
            Thread thread = new Thread(runnable, "argus-intel-" + id);
            thread.setDaemon(true);
            return thread;
        }
    }

    /** Injected order, unmodifiable. */
    public List<String> sourceNames() {
        return sourceNames;
    }

    /**
     * Fans the subject out across every source that supports its kind, concurrently, with at
     * most {@link #MAX_CONCURRENT_REQUESTS_PER_SOURCE} in flight per source name. Returns one
     * outcome per injected source, in injected order. BLOCKING — never call on the FX
     * Application Thread (invariant 3). Never call from a pool thread of this client.
     *
     * @throws NullPointerException  subject is null (before any submission)
     * @throws IllegalStateException this client is closed
     * @throws InterruptedException  the calling thread was interrupted, or any source reported
     *                               interruption; outstanding work is cancelled and NO partial
     *                               report is returned. Never wrapped.
     */
    public IntelReport enrich(IntelSubject subject) throws InterruptedException {
        Objects.requireNonNull(subject, "subject must not be null");

        List<Future<IntelSourceOutcome>> futures = new ArrayList<>(sources.size());
        synchronized (lock) {
            if (closed) {
                throw new IllegalStateException("ThreatIntelClient is closed");
            }
            for (IntelSource source : sources) {
                if (source.supports(subject)) {
                    futures.add(pool.submit(() -> queryOne(source, subject)));
                } else {
                    futures.add(null); // sentinel: never submitted, never queried
                }
            }
        }

        List<IntelSourceOutcome> outcomes = new ArrayList<>(sources.size());
        for (int i = 0; i < sources.size(); i++) {
            Future<IntelSourceOutcome> future = futures.get(i);
            if (future == null) {
                outcomes.add(IntelSourceOutcome.unsupported(sources.get(i).name()));
                continue;
            }
            try {
                outcomes.add(harvest(future));
            } catch (InterruptedException e) {
                cancelAll(futures);
                throw e;
            }
        }
        return new IntelReport(subject, outcomes);
    }

    /**
     * Runs on a pool thread. Holds exactly one {@link SourcePermits} permit for the duration of
     * the {@code query()} call, never across anything else, and releases it in a {@code finally}
     * regardless of outcome (plan §4.2).
     */
    private IntelSourceOutcome queryOne(IntelSource source, IntelSubject subject)
            throws InterruptedException {
        String name = source.name();
        permits.acquire(name);
        try {
            IntelResult result;
            try {
                result = source.query(subject);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            }
            if (result == null) {
                return IntelSourceOutcome.failed(name,
                        new IntelSourceException(name, "source returned null result"));
            }
            return IntelSourceOutcome.ok(name, result);
        } catch (MissingApiKeyException e) {
            // MUST be caught before IntelSourceException: MissingApiKeyException extends it.
            return IntelSourceOutcome.notConfigured(name, e);
        } catch (IntelSourceException e) {
            return IntelSourceOutcome.failed(name, e);
        } catch (RuntimeException e) {
            return IntelSourceOutcome.failed(name, new IntelSourceException(
                    name, "source threw " + e.getClass().getSimpleName(), e));
        } finally {
            permits.release(name);
        }
    }

    /**
     * Resolves one submitted task's {@link Future}. An {@code InterruptedException}, whether
     * from the calling thread itself or unwrapped from an {@link ExecutionException} raised by
     * a worker, propagates unwrapped (plan §4.5) — an {@link Error} propagates the same way,
     * never caught.
     */
    private IntelSourceOutcome harvest(Future<IntelSourceOutcome> future)
            throws InterruptedException {
        try {
            return future.get();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof InterruptedException ie) {
                throw ie;
            } else if (cause instanceof Error err) {
                throw err;
            } else if (cause instanceof RuntimeException re) {
                throw re;
            } else {
                throw new IllegalStateException("Unexpected intel task failure", cause);
            }
        }
    }

    private void cancelAll(List<Future<IntelSourceOutcome>> futures) {
        for (Future<IntelSourceOutcome> future : futures) {
            if (future != null) {
                future.cancel(true);
            }
        }
    }

    /**
     * Idempotent. {@code shutdown()} -&gt; bounded {@code awaitTermination()} -&gt;
     * {@code shutdownNow()} fallback (invariant 6). Does not cancel any in-flight
     * {@link #enrich(IntelSubject)} call: {@code shutdown()} lets running tasks finish, so a
     * concurrent in-flight call on another thread still returns its complete report.
     */
    @Override
    public void close() {
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
        }
        pool.shutdown();
        try {
            if (!pool.awaitTermination(SHUTDOWN_GRACE_MILLIS, TimeUnit.MILLISECONDS)) {
                pool.shutdownNow();
                if (!pool.awaitTermination(FORCE_GRACE_MILLIS, TimeUnit.MILLISECONDS)) {
                    LOGGER.log(Level.WARNING, "Intel client pool did not terminate");
                }
            }
        } catch (InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    boolean isPoolTerminatedForTest() {
        return pool.isTerminated();
    }

    /** The number of callers currently parked waiting for a permit on {@code sourceName}. */
    int waitingForTest(String sourceName) {
        return permits.waitingForTest(sourceName);
    }
}
