package com.argus.core;

import java.io.IOException;
import java.lang.System.Logger.Level;
import java.net.URI;
import java.util.List;
import java.util.Objects;

/**
 * Passive subdomain discovery from Certificate Transparency data via crt.name. No API key
 * (the free tier allows 100 requests per IP per day). crt.name is the only source: a failure
 * is reported as crt.name's own error, never retried against, or replaced by, another service.
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
        URI uri = crtNameUri(normalizedDomain);

        HttpFetchResult result;
        try {
            result = fetcher.fetch(HttpRequestSpec.get(uri));
        } catch (IOException e) {
            throw new SubdomainEnumerationException(
                    "crt.name request failed for " + normalizedDomain, e);
        }

        if (!result.isSuccess()) {
            throw new SubdomainEnumerationException(
                    describeFailure(result), result.statusCode());
        }

        List<String> rawTokens = CrtNameResponseParser.parse(result.body());
        List<Subdomain> subdomains = SubdomainNormalizer.normalize(rawTokens, normalizedDomain);
        LOGGER.log(Level.DEBUG,
                () -> "enumerated " + subdomains.size() + " names for " + normalizedDomain);
        return subdomains;
    }

    /**
     * Package-private, tested directly: the exact crt.name URI for a normalized domain. The
     * domain is already validated LDH, so it needs no escaping. crt.name only accepts an apex
     * (eTLD+1) and answers anything else with an HTTP 400 that says so -- surfaced as-is by
     * {@link #describeFailure}, not worked around.
     */
    static URI crtNameUri(String domain) {
        return URI.create("https://crt.name/v1/search?apex=" + domain + "&format=json");
    }

    private static final int MAX_DETAIL_CHARS = 200;

    /**
     * The actual reason for a non-2xx answer: the status, plus crt.name's own one-line
     * explanation when it sent plain text (for example {@code invalid apex: not an apex (eTLD+1
     * is kuet.ac.bd)}). An HTML error page (a gateway's 502 body) is not echoed -- the status
     * says it -- and a long body is flattened to one line and cut short.
     */
    private static String describeFailure(HttpFetchResult result) {
        StringBuilder message =
                new StringBuilder("crt.name returned HTTP ").append(result.statusCode());
        String detail = result.body() == null ? "" : result.body().strip();
        if (!detail.isEmpty() && !detail.startsWith("<")) {
            detail = detail.replaceAll("\\s+", " ");
            if (detail.length() > MAX_DETAIL_CHARS) {
                detail = detail.substring(0, MAX_DETAIL_CHARS) + "...";
            }
            message.append(": ").append(detail);
        }
        if (result.statusCode() == 429) {
            message.append(" (the free tier allows 100 requests per IP per day)");
        }
        return message.toString();
    }
}
