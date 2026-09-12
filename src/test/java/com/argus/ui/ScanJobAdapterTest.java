package com.argus.ui;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.argus.core.PortResult;
import com.argus.core.PortState;
import com.argus.core.ScanRequest;
import com.argus.core.ScanSink;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.util.concurrent.TimeUnit;

/** Section 6.10: {@code ScanJobAdapterTest} — the thin adapters. */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class ScanJobAdapterTest {

    private ServerSocket openServerSocket;
    private ServerSocket closedServerSocket;

    @AfterEach
    void closeSockets() throws Exception {
        if (openServerSocket != null && !openServerSocket.isClosed()) {
            openServerSocket.close();
        }
    }

    @Test
    void defaultFactoryBuildsOneSubdomainAndOnePortJob() {
        DefaultScanJobFactory factory = new DefaultScanJobFactory();
        ScanPlan plan = ScanPlan.of("example.com");

        List<ScanJob> jobs = factory.jobsFor(plan);

        assertEquals(2, jobs.size());
        assertTrue(jobs.get(0) instanceof SubdomainScanJob);
        assertTrue(jobs.get(1) instanceof PortScanJob);
        assertEquals("subdomain enumeration", jobs.get(0).name());
        assertEquals("port scan", jobs.get(1).name());
    }

    @Test
    void portScanJobNameAndCancelAreSafeBeforeRun() {
        ScanRequest request = ScanRequest.of("127.0.0.1", List.of(1));
        PortScanJob job = new PortScanJob(request);

        assertEquals("port scan", job.name());
        assertDoesNotThrow(job::cancel);
    }

    @Test
    void portScanJobPublishesLoopbackResults() throws Exception {
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        openServerSocket = new ServerSocket(0, 50, loopback);
        closedServerSocket = new ServerSocket(0, 50, loopback);
        int openPort = openServerSocket.getLocalPort();
        int closedPort = closedServerSocket.getLocalPort();
        closedServerSocket.close();

        ScanRequest request = ScanRequest.of("127.0.0.1", List.of(openPort, closedPort))
                .withThreadCount(2);
        PortScanJob job = new PortScanJob(request);

        List<Object> published = new CopyOnWriteArrayList<>();
        ScanSink<Object> sink = published::add;

        int count = job.run(sink);

        assertEquals(2, count);
        assertEquals(2, published.size());
        boolean sawOpen = false;
        boolean sawClosed = false;
        for (Object item : published) {
            PortResult result = (PortResult) item;
            if (result.port() == openPort) {
                assertEquals(PortState.OPEN, result.state());
                sawOpen = true;
            } else if (result.port() == closedPort) {
                assertEquals(PortState.CLOSED, result.state());
                sawClosed = true;
            }
        }
        assertTrue(sawOpen && sawClosed);
    }

    @Test
    void subdomainScanJobCancelIsADocumentedNoOp() {
        SubdomainScanJob job = new SubdomainScanJob("example.com");

        assertEquals("subdomain enumeration", job.name());
        assertDoesNotThrow(job::cancel);
    }

    @Test
    void aCancelledPortScanJobPublishesNothingFurther() throws Exception {
        ScanRequest request = ScanRequest.of("127.0.0.1", List.of(1, 2, 3));
        PortScanJob job = new PortScanJob(request);
        job.cancel();

        List<Object> published = new CopyOnWriteArrayList<>();
        ScanSink<Object> sink = published::add;

        int count = job.run(sink);

        assertEquals(0, count);
        assertTrue(published.isEmpty());
    }
}
