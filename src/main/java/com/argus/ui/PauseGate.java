package com.argus.ui;

/**
 * A resumable gate. One private final lock, guarded while-loops, notifyAll only — the same
 * monitor discipline as {@code ScanPipeline} (P1-03 §4.3), for the same reasons.
 *
 * {@link #stop()} is the anti-deadlock release valve: it permanently opens the gate so a
 * cancel or an app-close can never leave a thread parked here. There is no un-stop.
 */
final class PauseGate {

    private final Object lock = new Object();
    private boolean paused;    // @GuardedBy("lock")
    private boolean stopped;   // @GuardedBy("lock")

    /** BLOCKING. Returns immediately unless paused. Never call on the FX thread. */
    void awaitResume() throws InterruptedException {
        synchronized (lock) {
            while (paused && !stopped) {
                lock.wait();
            }
        }
    }

    void pause() {
        synchronized (lock) {
            paused = true;
            lock.notifyAll();
        }
    }

    void resume() {
        synchronized (lock) {
            paused = false;
            lock.notifyAll();
        }
    }

    /** Permanently opens the gate and releases every parked thread. Idempotent, never blocks. */
    void stop() {
        synchronized (lock) {
            stopped = true;
            lock.notifyAll();
        }
    }

    boolean isPaused() {
        synchronized (lock) {
            return paused;
        }
    }

    boolean isStopped() {
        synchronized (lock) {
            return stopped;
        }
    }

    /**
     * Test-only seam: {@code notifyAll()} with no state change, to prove the guard is a
     * {@code while}, not an {@code if}. The P1-03 §3.3 / R2 precedent.
     */
    void spuriousWakeupForTest() {
        synchronized (lock) {
            lock.notifyAll();
        }
    }
}
