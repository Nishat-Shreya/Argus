package com.argus.ui;

import com.argus.core.PortResult;
import com.argus.core.PortScanner;
import com.argus.core.ScanProducerTask;
import com.argus.core.ScanRequest;
import com.argus.core.ScanSink;

/** Wraps one single-use {@link PortScanner}. {@code cancel()} delegates to {@code PortScanner.cancel()}. */
final class PortScanJob implements ScanJob {

    private final PortScanner scanner;

    PortScanJob(ScanRequest request) {
        this.scanner = new PortScanner(request);
    }

    @Override
    public String name() {
        return "port scan";
    }

    @Override
    public int run(ScanSink<Object> sink) throws Exception {
        return new ScanProducerTask<PortResult>(scanner::scan, sink).call();
    }

    @Override
    public void cancel() {
        scanner.cancel();
    }
}
