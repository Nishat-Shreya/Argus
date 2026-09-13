package com.argus.core;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The second concrete {@link IntelSource}: Shodan host lookup — exposed services, banners and
 * vulnerabilities. Immutable and safe for concurrent use by multiple scan threads (invariant 5)
 * — exactly two final fields, neither a cache, both injected. The API key is a LOCAL read from
 * the vault on every {@link #query}, never a field: a cached key would outlive the vault's
 * unlock.
 *
 * BLOCKING: query() performs network I/O. com.argus.ui callers run it on a background thread
 * (invariant 3). Cancellation is thread interrupt — never wrapped, never swallowed.
 */
public final class ShodanSource implements IntelSource {

    /** The IntelSource.name() literal. Public so ui/P2-06 can reference it without a Vault. */
    public static final String NAME = "shodan";

    static final String API_BASE = "https://api.shodan.io/";
    static final String API_KEY_QUERY_PARAM = "key";

    private static final Set<IntelSubjectKind> SUPPORTED_SUBJECTS = Set.of(IntelSubjectKind.IP);

    private final Vault vault;
    private final HttpFetcher fetcher;

    /** @param vault an UNLOCKED vault (P1-05: unlocked-ness is a type). Not closed by this class. */
    public ShodanSource(Vault vault) {
        this(vault, new JdkHttpFetcher());
    }

    /** Package-private test seam: inject a stub fetcher (P1-02 precedent). */
    ShodanSource(Vault vault, HttpFetcher fetcher) {
        this.vault = Objects.requireNonNull(vault, "vault");
        this.fetcher = Objects.requireNonNull(fetcher, "fetcher");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Set<IntelSubjectKind> supportedSubjects() {
        return SUPPORTED_SUBJECTS;
    }

    @Override
    public IntelResult query(IntelSubject subject)
            throws IntelSourceException, InterruptedException {
        if (subject == null) {
            throw new IllegalArgumentException("subject must not be null");
        }
        if (!supports(subject)) {
            throw new IllegalArgumentException(
                    "unsupported subject kind for " + NAME + ": " + subject.kind());
        }

        String entryName = ApiKeyNames.forSource(NAME);
        Optional<String> stored;
        try {
            stored = vault.get(entryName);
        } catch (IllegalStateException e) {
            throw new IntelSourceException(NAME, "vault is closed", e);
        }
        if (stored.isEmpty()) {
            throw new MissingApiKeyException(NAME, entryName);
        }

        String apiKey;
        try {
            apiKey = ApiKeyValue.require(stored.get());
        } catch (IllegalArgumentException e) {
            throw new IntelSourceException(NAME,
                    "the stored Shodan API key is not usable; re-enter it in key vault settings",
                    e);
        }

        HttpRequestSpec spec = HttpRequestSpec.get(hostReportUri(subject, apiKey));

        HttpFetchResult result;
        try {
            result = fetcher.fetch(spec);
        } catch (IOException e) {
            throw new IntelSourceException(NAME, "Shodan request failed", e);
        }

        int status = result.statusCode();
        if (status == 404) {
            return IntelResult.unknown(NAME, subject);
        }
        if (status == 401) {
            throw new IntelSourceException(NAME, "Shodan rejected the API key", 401);
        }
        if (status == 403) {
            throw new IntelSourceException(NAME, "Shodan request was forbidden", 403);
        }
        if (status == 429) {
            throw new IntelSourceException(NAME, "Shodan rate limit exceeded", 429);
        }
        if (!result.isSuccess()) {
            throw new IntelSourceException(NAME, "Shodan returned HTTP " + status, status);
        }

        ShodanHostReport report = ShodanResponseParser.parse(result.body());
        return report.toIntelResult(subject);
    }

    /**
     * Package-private, tested directly. CARRIES SECRET MATERIAL: the returned URI contains the
     * API key in its query string. Never log it, never put it in an exception message. Tests
     * pass a dummy key only.
     *
     * The IP path segment needs no encoding — IpAddress has normalized it to digits/dots or hex
     * digits/colons, and ':' is a legal RFC 3986 pchar. The key, however, goes into the query
     * string and ApiKeyValue's charset is deliberately permissive, so it is percent-encoded
     * (plan §3.3/R8) to avoid an undocumented IllegalArgumentException out of query() and to
     * avoid a silently malformed request for a key containing '&amp;', '=' or '#'.
     */
    static URI hostReportUri(IntelSubject subject, String apiKey) {
        String encodedKey = URLEncoder.encode(apiKey, StandardCharsets.UTF_8);
        return URI.create(API_BASE + "shodan/host/" + subject.value()
                + "?" + API_KEY_QUERY_PARAM + "=" + encodedKey);
    }
}
