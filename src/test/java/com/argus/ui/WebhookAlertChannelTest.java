package com.argus.ui;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.argus.core.ScanAlert;
import com.argus.core.WebhookEndpoint;
import com.argus.core.WebhookSender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Section 6.9 of the P3-03 plan: {@link WebhookAlertChannel} against a REAL {@link WebhookSender}
 * (it is a {@code public final} class with no fake-transport seam reachable from {@code ui}) and
 * a loopback {@code HttpServer} — the P1-02 precedent for keeping {@code mvn verify} network-free.
 * No sleeps; latches only.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class WebhookAlertChannelTest {

    private static final ScanAlert ALERT =
            new ScanAlert("example.com", 1L, 2L, 1, List.of("a.example.com"));
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private WebhookEndpoint startEchoServer(CountDownLatch hitLatch,
            AtomicReference<String> capturedBody) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", exchange -> {
            capturedBody.set(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(204, -1);
            exchange.getResponseBody().close();
            hitLatch.countDown();
        });
        server.start();
        return WebhookEndpoint.parse(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/hook");
    }

    @Test
    void h1HappyPathSenderReceivesTheAlert() throws Exception {
        CountDownLatch hitLatch = new CountDownLatch(1);
        AtomicReference<String> capturedBody = new AtomicReference<>();
        WebhookEndpoint endpoint = startEchoServer(hitLatch, capturedBody);

        WebhookAlertChannel channel =
                new WebhookAlertChannel(() -> Optional.of(endpoint), new WebhookSender());
        try {
            channel.deliver(ALERT);
            assertTrue(hitLatch.await(10, TimeUnit.SECONDS));

            JsonNode node = MAPPER.readTree(capturedBody.get());
            assertEquals("example.com", node.path("target").asText());
            assertEquals(1L, node.path("baselineScanId").asLong());
            assertEquals(2L, node.path("currentScanId").asLong());
            assertEquals(1, node.path("addedCount").asInt());
            assertEquals("a.example.com", node.path("addedSubjects").get(0).asText());
        } finally {
            channel.close();
        }
    }

    @Test
    void h2ResolutionHappensOffTheFxThreadOnADaemonArgusWebhookThread() throws Exception {
        CountDownLatch resolvedLatch = new CountDownLatch(1);
        AtomicReference<String> threadName = new AtomicReference<>();
        AtomicBoolean daemon = new AtomicBoolean();
        Supplier<Optional<WebhookEndpoint>> supplier = () -> {
            threadName.set(Thread.currentThread().getName());
            daemon.set(Thread.currentThread().isDaemon());
            resolvedLatch.countDown();
            return Optional.empty();
        };

        WebhookAlertChannel channel = new WebhookAlertChannel(supplier, new WebhookSender());
        try {
            channel.deliver(ALERT);
            assertTrue(resolvedLatch.await(10, TimeUnit.SECONDS));
            assertEquals(WebhookAlertChannel.THREAD_NAME, threadName.get());
            assertTrue(daemon.get());
        } finally {
            channel.close();
        }
    }

    @Test
    void h3NotConfiguredIsASilentNoOp() throws Exception {
        CountDownLatch resolvedLatch = new CountDownLatch(1);
        Supplier<Optional<WebhookEndpoint>> supplier = () -> {
            resolvedLatch.countDown();
            return Optional.empty();
        };

        WebhookAlertChannel channel = new WebhookAlertChannel(supplier, new WebhookSender());
        try {
            assertDoesNotThrow(() -> channel.deliver(ALERT));
            assertTrue(resolvedLatch.await(10, TimeUnit.SECONDS));
        } finally {
            channel.close();
        }
    }

    @Test
    void h4DeliveryFailureIsContainedAndTheChannelKeepsWorking() throws Exception {
        int unboundPort;
        try (ServerSocket probe = new ServerSocket(0)) {
            unboundPort = probe.getLocalPort();
        }
        WebhookEndpoint deadEndpoint =
                WebhookEndpoint.parse("http://127.0.0.1:" + unboundPort + "/hook");

        CountDownLatch hitLatch = new CountDownLatch(1);
        AtomicReference<String> capturedBody = new AtomicReference<>();
        WebhookEndpoint workingEndpoint = startEchoServer(hitLatch, capturedBody);

        AtomicInteger callCount = new AtomicInteger();
        Supplier<Optional<WebhookEndpoint>> supplier = () ->
                Optional.of(callCount.incrementAndGet() == 1 ? deadEndpoint : workingEndpoint);

        WebhookAlertChannel channel = new WebhookAlertChannel(supplier, new WebhookSender());
        try {
            assertDoesNotThrow(() -> channel.deliver(ALERT));
            assertDoesNotThrow(() -> channel.deliver(ALERT));

            assertTrue(hitLatch.await(10, TimeUnit.SECONDS),
                    "the channel must still deliver after a prior failure");
        } finally {
            channel.close();
        }
    }

    @Test
    void h5SupplierThrowingIsContained() throws Exception {
        CountDownLatch secondResolved = new CountDownLatch(1);
        AtomicInteger callCount = new AtomicInteger();
        Supplier<Optional<WebhookEndpoint>> supplier = () -> {
            if (callCount.incrementAndGet() == 1) {
                throw new RuntimeException("boom");
            }
            secondResolved.countDown();
            return Optional.empty();
        };

        WebhookAlertChannel channel = new WebhookAlertChannel(supplier, new WebhookSender());
        try {
            assertDoesNotThrow(() -> channel.deliver(ALERT));
            assertDoesNotThrow(() -> channel.deliver(ALERT));

            assertTrue(secondResolved.await(10, TimeUnit.SECONDS),
                    "the channel must still process deliveries after a prior supplier failure");
        } finally {
            channel.close();
        }
    }

    @Test
    void h6ShutdownTerminatesThePoolAndFurtherDeliveriesAreSilentNoOps() {
        WebhookAlertChannel channel =
                new WebhookAlertChannel(Optional::empty, new WebhookSender());
        channel.close();

        assertTrue(channel.isPoolTerminatedForTest());
        assertDoesNotThrow(() -> channel.deliver(ALERT));
    }

    @Test
    void h7CloseIsIdempotentAndDeliverNullThrowsBeforeSubmission() {
        AtomicBoolean supplierCalled = new AtomicBoolean(false);
        Supplier<Optional<WebhookEndpoint>> supplier = () -> {
            supplierCalled.set(true);
            return Optional.empty();
        };
        WebhookAlertChannel channel = new WebhookAlertChannel(supplier, new WebhookSender());

        assertThrows(NullPointerException.class, () -> channel.deliver(null));
        assertFalse(supplierCalled.get());

        channel.close();
        assertDoesNotThrow(channel::close);
    }
}
