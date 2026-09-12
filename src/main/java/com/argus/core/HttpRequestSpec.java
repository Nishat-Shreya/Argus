package com.argus.core;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * One outbound HTTP GET: a URI plus request headers. The unit the HttpFetcher seam accepts.
 *
 * SECURITY (invariant 7): a spec may carry secret material in BOTH places — an API key header
 * (VirusTotal, AbuseIPDB, Censys) and a key inside the query string (Shodan). toString()
 * therefore redacts the whole query string and every header value. Never log a raw URI or a
 * raw header map; never put either into an exception message.
 */
record HttpRequestSpec(URI uri, Map<String, String> headers) {

    /** Header names java.net.http refuses to let us set; rejected here with a clear message. */
    static final Set<String> RESTRICTED_HEADERS =
            Set.of("connection", "content-length", "expect", "host", "upgrade");

    static final int MAX_HEADERS = 8;

    /** RFC 7230 token characters — the characters a header name may be made of. */
    private static final Pattern TOKEN = Pattern.compile("[!#$%&'*+\\-.^_`|~0-9A-Za-z]+");

    HttpRequestSpec {
        if (uri == null) {
            throw new IllegalArgumentException("uri must not be null");
        }
        if (!uri.isAbsolute()) {
            throw new IllegalArgumentException("uri must be absolute: " + redactedUri(uri));
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new IllegalArgumentException("uri scheme must be http or https");
        }
        if (headers == null) {
            throw new IllegalArgumentException(
                    "headers must not be null; use get(uri) for none");
        }
        if (headers.size() > MAX_HEADERS) {
            throw new IllegalArgumentException(
                    "too many headers (max " + MAX_HEADERS + "): " + headers.size());
        }

        Map<String, String> copy = new LinkedHashMap<>();
        Set<String> seenLowercase = new HashSet<>();
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            String name = entry.getKey();
            String value = entry.getValue();
            validateHeaderName(name);
            validateHeaderValue(name, value);
            if (!seenLowercase.add(name.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException(
                        "duplicate header name (case-insensitive): " + name);
            }
            copy.put(name, value);
        }
        headers = Collections.unmodifiableMap(copy);
    }

    private static void validateHeaderName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("header name must not be null or blank");
        }
        if (!TOKEN.matcher(name).matches()) {
            throw new IllegalArgumentException("header name has invalid characters: " + name);
        }
        if (RESTRICTED_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("header name is restricted: " + name);
        }
    }

    private static void validateHeaderValue(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "header value must not be null or blank: " + name);
        }
        if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(
                    "header value contains a control character: " + name);
        }
    }

    /** No extra headers — what SubdomainEnumerator uses. */
    static HttpRequestSpec get(URI uri) {
        return new HttpRequestSpec(uri, Map.of());
    }

    /** One header, the common case for an API-key source. */
    static HttpRequestSpec get(URI uri, String headerName, String headerValue) {
        Map<String, String> single = new LinkedHashMap<>();
        single.put(headerName, headerValue);
        return new HttpRequestSpec(uri, single);
    }

    static HttpRequestSpec get(URI uri, Map<String, String> headers) {
        return new HttpRequestSpec(uri, headers);
    }

    /** Header names only, sorted, for diagnostics. NEVER the values. */
    List<String> headerNames() {
        List<String> names = new ArrayList<>(headers.keySet());
        Collections.sort(names);
        return List.copyOf(names);
    }

    /** {@code HttpRequestSpec[uri=https://api.shodan.io/shodan/host/1.2.3.4?<redacted>, headers=[]]} */
    @Override
    public String toString() {
        StringBuilder headerPart = new StringBuilder("[");
        boolean first = true;
        for (String name : headerNames()) {
            if (!first) {
                headerPart.append(", ");
            }
            headerPart.append(name).append("=<redacted>");
            first = false;
        }
        headerPart.append(']');
        return "HttpRequestSpec[uri=" + redactedUri(uri) + ", headers=" + headerPart + "]";
    }

    /** The URI with its query string (if any) replaced — never the raw query, never a value. */
    private static String redactedUri(URI uri) {
        StringBuilder sb = new StringBuilder();
        if (uri.getScheme() != null) {
            sb.append(uri.getScheme()).append("://");
        }
        if (uri.getRawAuthority() != null) {
            sb.append(uri.getRawAuthority());
        }
        if (uri.getRawPath() != null) {
            sb.append(uri.getRawPath());
        }
        if (uri.getRawQuery() != null) {
            sb.append("?<redacted>");
        }
        return sb.toString();
    }
}
