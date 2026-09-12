package com.argus.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@link HttpRequestSpec} — the generalized HttpFetcher seam (plan §3.1/§6.1). Validation rules
 * and the invariant-7 redacting {@code toString()}; the Shodan query-string case is T10.
 */
class HttpRequestSpecTest {

    private static final URI SHODAN_URI =
            URI.create("https://api.shodan.io/shodan/host/1.2.3.4?key=SECRET123");

    @Test
    void getWithNoHeadersHasEmptyHeaderMap() {
        HttpRequestSpec spec = HttpRequestSpec.get(URI.create("https://example.com/"));
        assertTrue(spec.headers().isEmpty());
    }

    @Test
    void singleHeaderFactoryStoresTheHeader() {
        HttpRequestSpec spec =
                HttpRequestSpec.get(URI.create("https://example.com/"), "x-apikey", "k");
        assertEquals(Map.of("x-apikey", "k"), spec.headers());
    }

    @Test
    void headersMapIsDefensivelyCopiedAndUnmodifiable() {
        Map<String, String> callerMap = new LinkedHashMap<>();
        callerMap.put("x-apikey", "k");
        HttpRequestSpec spec = HttpRequestSpec.get(URI.create("https://example.com/"), callerMap);

        callerMap.put("x-apikey", "mutated");
        assertEquals("k", spec.headers().get("x-apikey"));

        assertThrows(UnsupportedOperationException.class,
                () -> spec.headers().put("new", "value"));
    }

    @Test
    void rejectsRestrictedHeaderNames() {
        URI uri = URI.create("https://example.com/");
        for (String restricted : java.util.List.of(
                "host", "Content-Length", "Connection", "Upgrade", "Expect")) {
            assertThrows(IllegalArgumentException.class,
                    () -> HttpRequestSpec.get(uri, restricted, "value"),
                    "expected rejection for: " + restricted);
        }
    }

    @Test
    void rejectsDuplicateHeaderNamesDifferingOnlyInCase() {
        URI uri = URI.create("https://example.com/");
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Key", "a");
        headers.put("key", "b");
        assertThrows(IllegalArgumentException.class, () -> HttpRequestSpec.get(uri, headers));
    }

    @Test
    void rejectsHeaderInjectionInValues() {
        URI uri = URI.create("https://example.com/");
        for (String malicious : java.util.List.of(
                "bad\rvalue", "bad\nvalue", "bad\r\nvalue", "bad\0value")) {
            assertThrows(IllegalArgumentException.class,
                    () -> HttpRequestSpec.get(uri, "x-apikey", malicious),
                    "expected rejection for value containing control characters");
        }
    }

    @Test
    void rejectsBlankNamesBlankValuesAndTooManyHeaders() {
        URI uri = URI.create("https://example.com/");

        assertThrows(IllegalArgumentException.class,
                () -> HttpRequestSpec.get(uri, "   ", "value"));
        assertThrows(IllegalArgumentException.class,
                () -> HttpRequestSpec.get(uri, "x-apikey", "   "));

        Map<String, String> tooMany = new LinkedHashMap<>();
        for (int i = 0; i <= HttpRequestSpec.MAX_HEADERS; i++) {
            tooMany.put("header-" + i, "value");
        }
        assertThrows(IllegalArgumentException.class, () -> HttpRequestSpec.get(uri, tooMany));
    }

    @Test
    void rejectsNullNonAbsoluteAndNonHttpUris() {
        assertThrows(IllegalArgumentException.class, () -> HttpRequestSpec.get(null));
        assertThrows(IllegalArgumentException.class,
                () -> HttpRequestSpec.get(URI.create("/path")));
        assertThrows(IllegalArgumentException.class,
                () -> HttpRequestSpec.get(URI.create("file:///etc/passwd")));
        assertThrows(IllegalArgumentException.class,
                () -> HttpRequestSpec.get(URI.create("ftp://x/")));

        assertDoesNotThrow(() -> HttpRequestSpec.get(URI.create("http://127.0.0.1:1/")));
        assertDoesNotThrow(() -> HttpRequestSpec.get(URI.create("https://x/")));
    }

    @Test
    void toStringRedactsHeaderValues() {
        HttpRequestSpec spec = HttpRequestSpec.get(
                URI.create("https://example.com/"), "x-apikey", "SECRET123");

        String text = spec.toString();
        assertTrue(text.contains("x-apikey"));
        assertFalse(text.contains("SECRET123"));
        assertTrue(text.contains("<redacted>"));
    }

    @Test
    void toStringRedactsTheWholeQueryString() {
        HttpRequestSpec spec = HttpRequestSpec.get(SHODAN_URI);

        String text = spec.toString();
        assertTrue(text.contains("/shodan/host/1.2.3.4"));
        assertFalse(text.contains("SECRET123"));
        assertFalse(text.contains("key="));
    }
}
