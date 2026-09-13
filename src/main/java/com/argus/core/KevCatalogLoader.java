package com.argus.core;

import java.io.IOException;
import java.net.URI;
import java.util.Objects;

/**
 * Fetches and parses the public CISA KEV catalog. No API key, no vault — the feed is
 * unauthenticated (the first network-backed core class since crt.sh for which that is true).
 *
 * BLOCKING: {@link #load()} performs network I/O and must not run on the JavaFX Application
 * Thread (invariant 3). Cancellation is thread interrupt — {@link InterruptedException}
 * propagates UNWRAPPED ({@code HttpClient.send} is interruptible, unlike {@code Socket.connect}).
 *
 * Stateless apart from one injected final {@link HttpFetcher}. No cache, no retry, no backoff.
 */
public final class KevCatalogLoader {

    public static final URI CATALOG_URI = URI.create(
            "https://www.cisa.gov/sites/default/files/feeds/known_exploited_vulnerabilities.json");

    private final HttpFetcher fetcher;

    public KevCatalogLoader() {
        this(new JdkHttpFetcher());
    }

    /** Package-private test seam: inject a stub fetcher, never touch the network. */
    KevCatalogLoader(HttpFetcher fetcher) {
        this.fetcher = Objects.requireNonNull(fetcher, "fetcher");
    }

    public KevCatalog load() throws KevCatalogException, InterruptedException {
        HttpFetchResult result;
        try {
            result = fetcher.fetch(HttpRequestSpec.get(CATALOG_URI));
        } catch (IOException e) {
            throw new KevCatalogException("the CISA KEV feed request failed", e);
        }

        int status = result.statusCode();
        if (status == 404) {
            throw new KevCatalogException("the CISA KEV feed is unavailable", 404);
        }
        if (status == 429) {
            throw new KevCatalogException("the CISA KEV feed rate limit was exceeded", 429);
        }
        if (!result.isSuccess()) {
            throw new KevCatalogException("the CISA KEV feed returned HTTP " + status, status);
        }

        return KevCatalogParser.parse(result.body());
    }
}
