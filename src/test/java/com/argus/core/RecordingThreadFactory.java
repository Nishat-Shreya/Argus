package com.argus.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadFactory;

/**
 * Test double: delegates thread creation to a real {@link ThreadFactory} (normally
 * {@code PortScanner}'s named-daemon factory) and records every {@link Thread} it creates, so
 * lifecycle tests can assert on naming, daemon status and post-scan liveness.
 */
final class RecordingThreadFactory implements ThreadFactory {

    private final ThreadFactory delegate;
    private final List<Thread> createdThreads = Collections.synchronizedList(new ArrayList<>());

    RecordingThreadFactory(ThreadFactory delegate) {
        this.delegate = delegate;
    }

    @Override
    public Thread newThread(Runnable runnable) {
        Thread thread = delegate.newThread(runnable);
        createdThreads.add(thread);
        return thread;
    }

    List<Thread> createdThreads() {
        return List.copyOf(createdThreads);
    }
}
