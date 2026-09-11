package com.argus.core;

import java.io.IOException;
import java.lang.System.Logger.Level;
import java.net.URI;
import java.util.List;
import java.util.Objects;

/**
 * Passive subdomain discovery from Certificate Transparency logs via crt.sh. No API key.
 * Stateless, immutable and safe for concurrent use — unlike PortScanner, this class owns no
 * thread pool and is not single-use.
 * Blocking: com.argus.ui callers must run enumerate() on a background thread (invariant 3).
 * Discovery only — CT logs are read; no name is resolved, probed or connected to here.
 */
public final class SubdomainEnumerator {

    private static final System.Logger LOGGER =
            System.getLogger(SubdomainEnumerator.class.getName());

    private final HttpFetcher fetcher;

    public SubdomainEnumerator() {
        this(new JdkHttpFetcher());
    }

    /** Package-private seam for tests: inject a stub fetcher, never touch the network. */
    SubdomainEnumerator(HttpFetcher fetcher) {
        this.fetcher = Objects.requireNonNull(fetcher, "fetcher");
    }

    /**
     * @param domain e.g. "example.com"; normalized and validated first
     * @return ascending, duplicate-free; includes the apex; may be empty (not an error)
     * @throws IllegalArgumentException       malformed domain — thrown before any I/O
     * @throws SubdomainEnumerationException  HTTP status, transport, oversized or non-JSON body
     * @throws InterruptedException           the calling thread was interrupted (= cancellation)
     */
    public List<Subdomain> enumerate(String domain)
            throws SubdomainEnumerationException, InterruptedException {
        String normalizedDomain = DomainName.normalize(domain);
        URI uri = crtShUri(normalizedDomain);

        HttpFetchResult result;
        try {
            result = fetcher.fetch(uri);
        } catch (IOException e) {
            throw new SubdomainEnumerationException(
                    "crt.sh request failed for " + normalizedDomain, e);
        }

        if (!result.isSuccess()) {
            throw new SubdomainEnumerationException(
                    "crt.sh returned HTTP " + result.statusCode(), result.statusCode());
        }

        List<String> rawTokens = CrtShResponseParser.parse(result.body());
        List<Subdomain> subdomains = SubdomainNormalizer.normalize(rawTokens, normalizedDomain);
        LOGGER.log(Level.DEBUG,
                () -> "enumerated " + subdomains.size() + " names for " + normalizedDomain);
        return subdomains;
    }

    /** Package-private, tested directly: the exact crt.sh URI for a normalized domain. */
    static URI crtShUri(String domain) {
        return URI.create("https://crt.sh/?q=%25." + domain + "&output=json");
    }
}
