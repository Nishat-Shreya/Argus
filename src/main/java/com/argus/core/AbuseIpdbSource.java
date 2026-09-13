package com.argus.core;

import java.io.IOException;
import java.net.URI;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The third concrete {@link IntelSource}: AbuseIPDB IP abuse-reputation lookup. Immutable and
 * safe for concurrent use by multiple scan threads (invariant 5) — exactly two final fields,
 * neither a cache, both injected. The API key is a LOCAL read from the vault on every
 * {@link #query}, never a field (plan §4): a cached key would outlive the vault's unlock.
 *
 * Request shape is a third variation on the two precedents (plan §3.3): the subject IP goes in
 * the query string (it is not secret), the API key goes in the {@code Key} request header (it
 * is). Since the key never touches the URI, {@link #checkUri} is secret-free.
 *
 * BLOCKING: query() performs network I/O. com.argus.ui callers run it on a background thread
 * (invariant 3). Cancellation is thread interrupt — never wrapped, never swallowed.
 */
public final class AbuseIpdbSource implements IntelSource {

    /** The IntelSource.name() literal. Public so ui/P2-06 can reference it without a Vault. */
    public static final String NAME = "abuseipdb";

    static final String CHECK_ENDPOINT = "https://api.abuseipdb.com/api/v2/check";
    static final String API_KEY_HEADER = "Key";

    /** Pinned explicitly, never left to the server default (plan §3.3). Scores and counts are
     *  computed over this window, so it is part of the result's meaning. */
    static final int MAX_AGE_IN_DAYS = 30;

    private static final Set<IntelSubjectKind> SUPPORTED_SUBJECTS = Set.of(IntelSubjectKind.IP);

    private final Vault vault;
    private final HttpFetcher fetcher;

    /** @param vault an UNLOCKED vault (P1-05: unlocked-ness is a type). Not closed by this class. */
    public AbuseIpdbSource(Vault vault) {
        this(vault, new JdkHttpFetcher());
    }

    /** Package-private test seam: inject a stub fetcher (P1-02 precedent). */
    AbuseIpdbSource(Vault vault, HttpFetcher fetcher) {
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
                    "the stored AbuseIPDB API key is not usable; re-enter it in key vault settings",
                    e);
        }

        HttpRequestSpec spec = HttpRequestSpec.get(checkUri(subject), API_KEY_HEADER, apiKey);

        HttpFetchResult result;
        try {
            result = fetcher.fetch(spec);
        } catch (IOException e) {
            throw new IntelSourceException(NAME, "AbuseIPDB request failed", e);
        }

        int status = result.statusCode();
        if (status == 404) {
            return IntelResult.unknown(NAME, subject);
        }
        if (status == 401) {
            throw new IntelSourceException(NAME, "AbuseIPDB rejected the API key", 401);
        }
        if (status == 403) {
            throw new IntelSourceException(NAME, "AbuseIPDB request was forbidden", 403);
        }
        if (status == 422) {
            throw new IntelSourceException(
                    NAME, "AbuseIPDB rejected the request parameters", 422);
        }
        if (status == 429) {
            throw new IntelSourceException(NAME, "AbuseIPDB rate limit exceeded", 429);
        }
        if (!result.isSuccess()) {
            throw new IntelSourceException(NAME, "AbuseIPDB returned HTTP " + status, status);
        }

        AbuseIpdbReport report = AbuseIpdbResponseParser.parse(result.body());
        return report.toIntelResult(subject);
    }

    /**
     * Package-private, tested directly. CARRIES NO SECRET MATERIAL (plan §3.3): the key is a
     * header, not part of this URI. No percent-encoding layer for the IP — IntelSubject has
     * already pushed the value through IpAddress.normalize (IPv4 digits/dots, IPv6 lowercase
     * hex/colons), and ':' is a legal RFC 3986 query character.
     */
    static URI checkUri(IntelSubject subject) {
        return URI.create(CHECK_ENDPOINT
                + "?ipAddress=" + subject.value()
                + "&maxAgeInDays=" + MAX_AGE_IN_DAYS
                + "&verbose");
    }
}
