package com.argus.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Section 6.5 of the P3-03 plan: {@link JdkWebhookTransport} against a real loopback
 * {@code com.sun.net.httpserver.HttpServer} on an ephemeral port — the P1-02 precedent. Literal
 * {@code "127.0.0.1"}, never {@code "localhost"} (P1-01 R5).
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class JdkWebhookTransportTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private URI startServer(HttpHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", handler);
        server.start();
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/hook");
    }

    @Test
    void j1RequestShapeIsCorrect() throws Exception {
        AtomicReference<String> capturedMethod = new AtomicReference<>();
        AtomicReference<Map<String, List<String>>> capturedHeaders = new AtomicReference<>();
        AtomicReference<String> capturedBody = new AtomicReference<>();

        URI uri = startServer(exchange -> {
            capturedMethod.set(exchange.getRequestMethod());
            capturedHeaders.set(exchange.getRequestHeaders());
            capturedBody.set(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8));
            byte[] bytes = new byte[0];
            exchange.sendResponseHeaders(204, -1);
            exchange.getResponseBody().close();
        });

        String jsonBody = "{\"source\":\"argus\"}";
        int status = new JdkWebhookTransport().post(uri, jsonBody);

        assertEquals(204, status);
        assertEquals("POST", capturedMethod.get());
        assertEquals(jsonBody, capturedBody.get());
        Map<String, List<String>> headers = capturedHeaders.get();
        assertTrue(headers.containsKey("Content-type"));
        assertTrue(headers.get("Content-type").get(0)
                .contains(JdkWebhookTransport.CONTENT_TYPE));
        assertTrue(headers.containsKey("User-agent"));
        assertTrue(headers.get("User-agent").get(0).startsWith("Argus/"));
    }

    @Test
    void j2NoContentStatusIsReturned() throws Exception {
        URI uri = startServer(exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(204, -1);
            exchange.getResponseBody().close();
        });

        int status = new JdkWebhookTransport().post(uri, "{}");
        assertEquals(204, status);
    }

    @Test
    void j3ServerErrorStatusIsReturnedNotThrown() throws Exception {
        URI uri = startServer(exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] bytes = "boom".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });

        int status = new JdkWebhookTransport().post(uri, "{}");
        assertEquals(500, status);
    }

    @Test
    void j4RedirectIsReturnedNotFollowed() throws Exception {
        AtomicReference<Integer> targetHits = new AtomicReference<>(0);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("Location", "/target");
            exchange.sendResponseHeaders(302, -1);
            exchange.getResponseBody().close();
        });
        server.createContext("/target", exchange -> {
            targetHits.set(targetHits.get() + 1);
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200, -1);
            exchange.getResponseBody().close();
        });
        server.start();
        URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/hook");

        int status = new JdkWebhookTransport().post(uri, "{}");

        assertEquals(302, status);
        assertEquals(0, targetHits.get());
    }

    @Test
    void j5UnboundPortThrowsWithoutHanging() throws Exception {
        int unboundPort;
        try (ServerSocket probe = new ServerSocket(0)) {
            unboundPort = probe.getLocalPort();
        }
        URI uri = URI.create("http://127.0.0.1:" + unboundPort + "/hook");

        assertThrows(IOException.class, () -> new JdkWebhookTransport().post(uri, "{}"));
    }
}
