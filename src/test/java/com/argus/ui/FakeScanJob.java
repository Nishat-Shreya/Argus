package com.argus.ui;

import com.argus.core.ScanSink;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Test double for {@link ScanJob}: scripted findings, optional latch gate
 * ({@code startedLatch}/{@code releaseLatch}), optional throw, records whether {@code cancel()}
 * was called and on which thread {@code run()} ran. The {@code FakeSocketConnector} shape
 * (P1-01 §5 step 10).
 */
final class FakeScanJob implements ScanJob {

    private final String name;
    private final List<Object> findings;
    private final Exception toThrow;
    private final CountDownLatch startedLatch;
    private final CountDownLatch releaseLatch;

    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final List<Thread> runThreads = new CopyOnWriteArrayList<>();
    private final List<IllegalStateException> illegalStateExceptionsSeen =
            new CopyOnWriteArrayList<>();

    FakeScanJob(String name, List<Object> findings) {
        this(name, findings, null, new CountDownLatch(0), new CountDownLatch(0));
    }

    FakeScanJob(String name, List<Object> findings, Exception toThrow) {
        this(name, findings, toThrow, new CountDownLatch(0), new CountDownLatch(0));
    }

    FakeScanJob(String name, List<Object> findings, Exception toThrow,
            CountDownLatch startedLatch, CountDownLatch releaseLatch) {
        this.name = name;
        this.findings = new ArrayList<>(findings);
        this.toThrow = toThrow;
        this.startedLatch = startedLatch;
        this.releaseLatch = releaseLatch;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public int run(ScanSink<Object> sink) throws Exception {
        runThreads.add(Thread.currentThread());
        startedLatch.countDown();
        releaseLatch.await();

        if (cancelled.get()) {
            return 0;
        }
        int published = 0;
        for (Object finding : findings) {
            if (cancelled.get()) {
                break;
            }
            try {
                sink.publish(finding);
                published++;
            } catch (IllegalStateException e) {
                illegalStateExceptionsSeen.add(e);
                throw e;
            }
        }
        if (toThrow != null) {
            throw toThrow;
        }
        return published;
    }

    @Override
    public void cancel() {
        cancelled.set(true);
    }

    boolean wasCancelled() {
        return cancelled.get();
    }

    List<Thread> runThreads() {
        return List.copyOf(runThreads);
    }

    List<IllegalStateException> illegalStateExceptionsSeen() {
        return List.copyOf(illegalStateExceptionsSeen);
    }
}
