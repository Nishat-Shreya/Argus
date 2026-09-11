package com.argus.core;

import java.util.Collection;
import java.util.Objects;
import java.util.concurrent.Callable;

/**
 * Adapts an existing batch-returning scan into a pipeline producer, with no change to the
 * scanner's API: PortScanner.scan() and SubdomainEnumerator.enumerate(...) keep returning a
 * List and stay exactly as P1-01/P1-02 tested them.
 *
 * Submit one instance per scan worker to an ExecutorService owned by the caller; the Future
 * yields the number of findings published, or the scan's own exception.
 *
 *   Callable&lt;Integer&gt; ports = new ScanProducerTask&lt;&gt;(scanner::scan, pipeline);
 *   Callable&lt;Integer&gt; subs  = new ScanProducerTask&lt;&gt;(() -&gt; enumerator.enumerate(domain), pipeline);
 *
 * Does NOT close the sink: with N producers sharing one pipeline, closing is the
 * coordinator's job, exactly once, after every producer has terminated.
 */
public final class ScanProducerTask<T> implements Callable<Integer> {

    private final Callable<? extends Collection<? extends T>> source;
    private final ScanSink<? super T> sink;

    public ScanProducerTask(Callable<? extends Collection<? extends T>> source,
            ScanSink<? super T> sink) {
        this.source = Objects.requireNonNull(source, "source");
        this.sink = Objects.requireNonNull(sink, "sink");
    }

    /**
     * Runs the scan on the calling thread, then publishes each finding in iteration order.
     *
     * @return the number of findings published
     * @throws InterruptedException if the scan or a publish is interrupted; findings already
     *                              published stay in the pipeline (partial results, matching
     *                              PortScanner's partial-result contract)
     * @throws Exception            whatever the wrapped scan threw, unwrapped and unchanged
     */
    @Override
    public Integer call() throws Exception {
        Collection<? extends T> results = source.call();
        int published = 0;
        for (T item : results) {
            sink.publish(item);
            published++;
        }
        return published;
    }
}
