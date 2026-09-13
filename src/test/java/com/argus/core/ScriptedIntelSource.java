package com.argus.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

/**
 * Thread-safe scripted {@link IntelSource} test double for {@code ThreatIntelClient}'s
 * concurrency tests (P2-06 plan §7.1). Unlike {@link FakeIntelSource} (single-threaded, left
 * untouched — it is already used by {@link IntelSourceContractTest}), every mutable field here
 * is guarded by one private lock, so it is safe to script before submission and observe from a
 * thread other than the one executing {@link #query(IntelSubject)}.
 *
 * The lock is never held across the entry-gate await, matching the "never hold a lock across
 * blocking work" rule this codebase applies everywhere (P1-08, {@code SourcePermits}).
 */
final class ScriptedIntelSource implements IntelSource {

    private final String name;
    private final Set<IntelSubjectKind> supportedSubjects;

    private final Object lock = new Object();

    // scripted outcome — exactly one is set at a time                     // @GuardedBy("lock")
    private IntelResult scriptedResult;
    private boolean scriptedReturnsNull;
    private IntelSourceException scriptedException;
    private InterruptedException scriptedInterrupt;
    private RuntimeException scriptedRuntime;
    private Error scriptedError;

    // optional entry gate                                                 // @GuardedBy("lock")
    private CountDownLatch enteredSignal;
    private CountDownLatch releaseGate;

    private final List<IntelSubject> queriedSubjects = new ArrayList<>(); // @GuardedBy("lock")
    private int inFlight;                                                  // @GuardedBy("lock")
    private int maxInFlight;                                               // @GuardedBy("lock")
    private boolean sawInterrupt;                                          // @GuardedBy("lock")

    ScriptedIntelSource(String name, Set<IntelSubjectKind> supportedSubjects) {
        this.name = name;
        this.supportedSubjects = Set.copyOf(supportedSubjects);
    }

    void willReturn(IntelResult result) {
        synchronized (lock) {
            scriptedResult = Objects.requireNonNull(result, "result");
            scriptedReturnsNull = false;
            scriptedException = null;
            scriptedInterrupt = null;
            scriptedRuntime = null;
            scriptedError = null;
        }
    }

    void willReturnNull() {
        synchronized (lock) {
            scriptedResult = null;
            scriptedReturnsNull = true;
            scriptedException = null;
            scriptedInterrupt = null;
            scriptedRuntime = null;
            scriptedError = null;
        }
    }

    void willThrow(IntelSourceException e) {
        synchronized (lock) {
            scriptedResult = null;
            scriptedReturnsNull = false;
            scriptedException = Objects.requireNonNull(e, "e");
            scriptedInterrupt = null;
            scriptedRuntime = null;
            scriptedError = null;
        }
    }

    void willThrow(InterruptedException e) {
        synchronized (lock) {
            scriptedResult = null;
            scriptedReturnsNull = false;
            scriptedException = null;
            scriptedInterrupt = Objects.requireNonNull(e, "e");
            scriptedRuntime = null;
            scriptedError = null;
        }
    }

    void willThrow(RuntimeException e) {
        synchronized (lock) {
            scriptedResult = null;
            scriptedReturnsNull = false;
            scriptedException = null;
            scriptedInterrupt = null;
            scriptedRuntime = Objects.requireNonNull(e, "e");
            scriptedError = null;
        }
    }

    void willThrow(Error e) {
        synchronized (lock) {
            scriptedResult = null;
            scriptedReturnsNull = false;
            scriptedException = null;
            scriptedInterrupt = null;
            scriptedRuntime = null;
            scriptedError = Objects.requireNonNull(e, "e");
        }
    }

    /**
     * When set, {@code query()} counts down {@code entered} the instant it is invoked (so a
     * test can prove "this source is inside query()"), then blocks on {@code release} before
     * producing its scripted outcome. Either or both may be {@code null} to skip gating.
     */
    void gate(CountDownLatch entered, CountDownLatch release) {
        synchronized (lock) {
            this.enteredSignal = entered;
            this.releaseGate = release;
        }
    }

    List<IntelSubject> queriedSubjects() {
        synchronized (lock) {
            return List.copyOf(queriedSubjects);
        }
    }

    int callCount() {
        synchronized (lock) {
            return queriedSubjects.size();
        }
    }

    /** The high-water mark of concurrently in-flight query() calls on this instance. */
    int maxConcurrentForTest() {
        synchronized (lock) {
            return maxInFlight;
        }
    }

    boolean sawInterruptForTest() {
        synchronized (lock) {
            return sawInterrupt;
        }
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Set<IntelSubjectKind> supportedSubjects() {
        return supportedSubjects;
    }

    @Override
    public IntelResult query(IntelSubject subject)
            throws IntelSourceException, InterruptedException {
        Objects.requireNonNull(subject, "subject must not be null");
        if (!supports(subject)) {
            throw new IllegalArgumentException(
                    "unsupported subject kind for " + name + ": " + subject.kind());
        }

        CountDownLatch entered;
        CountDownLatch release;
        synchronized (lock) {
            queriedSubjects.add(subject);
            inFlight++;
            if (inFlight > maxInFlight) {
                maxInFlight = inFlight;
            }
            entered = enteredSignal;
            release = releaseGate;
        }
        try {
            if (entered != null) {
                entered.countDown();
            }
            if (release != null) {
                try {
                    release.await();
                } catch (InterruptedException e) {
                    synchronized (lock) {
                        sawInterrupt = true;
                    }
                    throw e;
                }
            }
            return produceScriptedOutcome();
        } finally {
            synchronized (lock) {
                inFlight--;
            }
        }
    }

    private IntelResult produceScriptedOutcome()
            throws IntelSourceException, InterruptedException {
        IntelResult result;
        boolean returnsNull;
        IntelSourceException exception;
        InterruptedException interrupt;
        RuntimeException runtime;
        Error error;
        synchronized (lock) {
            result = scriptedResult;
            returnsNull = scriptedReturnsNull;
            exception = scriptedException;
            interrupt = scriptedInterrupt;
            runtime = scriptedRuntime;
            error = scriptedError;
        }
        if (interrupt != null) {
            throw interrupt;
        }
        if (error != null) {
            throw error;
        }
        if (exception != null) {
            throw exception;
        }
        if (runtime != null) {
            throw runtime;
        }
        if (returnsNull) {
            return null;
        }
        if (result == null) {
            throw new IllegalStateException(
                    "ScriptedIntelSource has no scripted outcome for " + name);
        }
        return result;
    }
}
