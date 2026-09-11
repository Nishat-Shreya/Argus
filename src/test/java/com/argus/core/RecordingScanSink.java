package com.argus.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Test double for {@link ScanSink}: records every published item under its own lock, with an
 * optional "throw on the Nth publish" script so {@link ScanProducerTaskTest} can exercise the
 * exception/interrupt contract without a real {@link ScanPipeline}.
 */
final class RecordingScanSink implements ScanSink<Object> {

    private final Object lock = new Object();
    private final List<Object> published = new ArrayList<>();
    private int throwOnCallNumber = -1;
    private RuntimeException scriptedException;
    private boolean interruptOnCallNumberSet;
    private int interruptOnCallNumber = -1;

    /** The (1-based) publish() call at which to throw {@code exception}. */
    void throwOnPublishNumber(int callNumber, RuntimeException exception) {
        synchronized (lock) {
            this.throwOnCallNumber = callNumber;
            this.scriptedException = exception;
        }
    }

    /** The (1-based) publish() call at which the calling thread is interrupted. */
    void interruptOnPublishNumber(int callNumber) {
        synchronized (lock) {
            this.interruptOnCallNumberSet = true;
            this.interruptOnCallNumber = callNumber;
        }
    }

    @Override
    public void publish(Object item) throws InterruptedException {
        Objects.requireNonNull(item, "item");
        synchronized (lock) {
            int callNumber = published.size() + 1;
            if (interruptOnCallNumberSet && callNumber == interruptOnCallNumber) {
                throw new InterruptedException("scripted interrupt on call " + callNumber);
            }
            if (callNumber == throwOnCallNumber) {
                throw scriptedException;
            }
            published.add(item);
        }
    }

    List<Object> published() {
        synchronized (lock) {
            return List.copyOf(published);
        }
    }
}
