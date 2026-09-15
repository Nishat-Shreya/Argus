package com.argus.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import org.junit.jupiter.api.Test;

/**
 * Section 6.2 of the P3-03 plan: {@link WebhookEndpoint}. A valid webhook destination, plus the
 * "the path is a secret" redaction rule that {@code HttpRequestSpec} does not provide.
 */
class WebhookEndpointTest {

    @Test
    void e1HappyPathParses() {
        String raw = "https://hooks.example.com/services/T1/B2/abc";
        WebhookEndpoint endpoint = WebhookEndpoint.parse(raw);
        assertEquals(URI.create(raw), endpoint.uri());
    }

    @Test
    void e2HttpsRequired() {
        assertThrows(IllegalArgumentException.class,
                () -> WebhookEndpoint.parse("http://hooks.example.com/x"));
    }

    @Test
    void e3LoopbackHttpExceptionIsAccepted() {
        assertEquals(URI.create("http://127.0.0.1:8080/hook"),
                WebhookEndpoint.parse("http://127.0.0.1:8080/hook").uri());
        assertEquals(URI.create("http://[::1]:8080/hook"),
                WebhookEndpoint.parse("http://[::1]:8080/hook").uri());
        assertEquals(URI.create("http://localhost:8080/hook"),
                WebhookEndpoint.parse("http://localhost:8080/hook").uri());
    }

    @Test
    void e4Malformed() {
        assertThrows(IllegalArgumentException.class, () -> WebhookEndpoint.parse(""));
        assertThrows(IllegalArgumentException.class, () -> WebhookEndpoint.parse("   "));
        assertThrows(IllegalArgumentException.class, () -> WebhookEndpoint.parse(null));
        assertThrows(IllegalArgumentException.class, () -> WebhookEndpoint.parse("not a url"));
        assertThrows(IllegalArgumentException.class, () -> WebhookEndpoint.parse("ftp://h/x"));
        assertThrows(IllegalArgumentException.class, () -> WebhookEndpoint.parse("/relative/x"));
        assertThrows(IllegalArgumentException.class, () -> WebhookEndpoint.parse("https:///x"));
    }

    @Test
    void e5UserinfoIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> WebhookEndpoint.parse("https://u:p@hooks.example.com/x"));
    }

    @Test
    void e6FragmentIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> WebhookEndpoint.parse("https://hooks.example.com/x#frag"));
    }

    @Test
    void e7OverLengthIsRejected() {
        String longPath = "a".repeat(2048);
        assertThrows(IllegalArgumentException.class,
                () -> WebhookEndpoint.parse("https://hooks.example.com/" + longPath));
    }

    @Test
    void e8ToStringRedactsThePath() {
        WebhookEndpoint endpoint =
                WebhookEndpoint.parse("https://hooks.example.com/services/SUPERSECRET");
        String text = endpoint.toString();
        assertTrue(text.contains("hooks.example.com"));
        assertFalse(text.contains("SUPERSECRET"));
    }

    @Test
    void e9ErrorsNeverEchoTheInput() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> WebhookEndpoint.parse("http://x/SUPERSECRET"));
        assertFalse(e.getMessage().contains("SUPERSECRET"));
    }

    @Test
    void e10EqualUrisAreEqualEndpoints() {
        WebhookEndpoint a = WebhookEndpoint.parse("https://hooks.example.com/x");
        WebhookEndpoint b = WebhookEndpoint.parse("https://hooks.example.com/x");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }
}
