package com.argus.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
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
 * {@link JdkHttpFetcher} against a real loopback {@code com.sun.net.httpserver.HttpServer} on an
 * ephemeral port — the direct analogue of P1-01's loopback {@code ServerSocket(0)}. Literal
 * {@code "127.0.0.1"}, never {@code "localhost"} (P1-01 R5). No connect/request-timeout test
 * against the real fetcher (plan §7.2: timeouts are injected in {@link SubdomainEnumeratorTest},
 * never waited for here).
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class JdkHttpFetcherTest {

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
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
    }

    @Test
    void fetchesBodyAndStatusForOk() throws Exception {
        String jsonBody = "[{\"name_value\":\"a.example.com\"}]";
        URI uri = startServer(exchange -> {
            byte[] bytes = jsonBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });

        HttpFetchResult result = new JdkHttpFetcher().fetch(uri);

        assertEquals(200, result.statusCode());
        assertEquals(jsonBody, result.body());
        assertTrue(result.isSuccess());
    }

    @Test
    void returnsNonSuccessStatusWithoutThrowing() throws Exception {
        String html = "<html><body>502 Bad Gateway</body></html>";
        URI uri = startServer(exchange -> {
            byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(502, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });

        HttpFetchResult result = new JdkHttpFetcher().fetch(uri);

        assertEquals(502, result.statusCode());
        assertFalse(result.isSuccess());
        assertEquals(html, result.body());
    }

    @Test
    void decodesUtf8BodyIndependentOfPlatformCharset() throws Exception {
        String body = "café.example.com — résumé";
        URI uri = startServer(exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });

        HttpFetchResult result = new JdkHttpFetcher().fetch(uri);

        assertEquals(body, result.body());
    }

    @Test
    void rejectsOversizedBody() throws Exception {
        URI uri = startServer(exchange -> {
            long total = (long) JdkHttpFetcher.MAX_BODY_BYTES + 1;
            exchange.sendResponseHeaders(200, total);
            try (OutputStream os = exchange.getResponseBody()) {
                byte[] chunk = new byte[8192];
                long written = 0;
                while (written < total) {
                    int toWrite = (int) Math.min(chunk.length, total - written);
                    os.write(chunk, 0, toWrite);
                    written += toWrite;
                }
            }
        });

        JdkHttpFetcher fetcher = new JdkHttpFetcher();
        assertThrows(IOException.class, () -> fetcher.fetch(uri));
    }

    @Test
    void sendsJsonAcceptAndArgusUserAgent() throws Exception {
        AtomicReference<Map<String, List<String>>> capturedHeaders = new AtomicReference<>();
        URI uri = startServer(exchange -> {
            capturedHeaders.set(exchange.getRequestHeaders());
            byte[] bytes = "[]".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });

        new JdkHttpFetcher().fetch(uri);

        Map<String, List<String>> headers = capturedHeaders.get();
        assertNotNull(headers);
        assertTrue(headers.containsKey("Accept"));
        assertTrue(headers.get("Accept").contains("application/json"));
        assertTrue(headers.containsKey("User-Agent"));
        assertTrue(headers.get("User-Agent").get(0).startsWith("Argus/"));
    }
}
