package com.argus.ui;

import com.argus.core.ScanArchiveException;
import com.argus.core.ScanRun;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test double for {@link ScanSaver}: records every {@link ScanRun} it was handed, the invoking
 * thread name and the call count; configurable to return an id or throw
 * {@link ScanArchiveException}.
 */
final class RecordingScanSaver implements ScanSaver {

    private final Object lock = new Object();
    private final List<ScanRun> runs = new ArrayList<>();          // @GuardedBy("lock")
    private final List<String> threadNames = new ArrayList<>();    // @GuardedBy("lock")
    private final AtomicInteger callCount = new AtomicInteger();
    private final CountDownLatch saveCalledLatch = new CountDownLatch(1);

    private Long idToReturn;
    private ScanArchiveException toThrow;

    RecordingScanSaver returning(long id) {
        this.idToReturn = id;
        return this;
    }

    RecordingScanSaver throwing(ScanArchiveException e) {
        this.toThrow = e;
        return this;
    }

    @Override
    public Long save(ScanRun run) throws ScanArchiveException {
        synchronized (lock) {
            runs.add(run);
            threadNames.add(Thread.currentThread().getName());
        }
        callCount.incrementAndGet();
        saveCalledLatch.countDown();
        if (toThrow != null) {
            throw toThrow;
        }
        return idToReturn;
    }

    List<ScanRun> runs() {
        synchronized (lock) {
            return List.copyOf(runs);
        }
    }

    List<String> threadNames() {
        synchronized (lock) {
            return List.copyOf(threadNames);
        }
    }

    int callCount() {
        return callCount.get();
    }

    CountDownLatch saveCalledLatch() {
        return saveCalledLatch;
    }
}
