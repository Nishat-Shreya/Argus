package com.argus.core;

import java.io.IOException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The fourth concrete {@link IntelSource}: Censys Platform web-property (domain) and host (IP)
 * lookup. Immutable and safe for concurrent use by multiple scan threads (invariant 5) —
 * exactly two final fields, neither a cache, both injected. The API token is a LOCAL read from
 * the vault on every {@link #query}, never a field: a cached token would outlive the vault's
 * unlock.
 *
 * The first source whose {@link #supportedSubjects()} includes {@code DOMAIN} (plan §0/§7.1):
 * Censys's web-property asset is keyed by {@code hostname:port} and is reachable on the free
 * tier. The first source that needs two request headers (plan §1.1(d)): {@code Authorization}
 * (the secret) and a versioned {@code Accept} vendor media type (pins the asset schema).
 *
 * BLOCKING: query() performs network I/O. com.argus.ui callers run it on a background thread
 * (invariant 3). Cancellation is thread interrupt — never wrapped, never swallowed.
 */
public final class CensysSource implements IntelSource {

    /** The IntelSource.name() literal. Public so ui/P2-06 can reference it without a Vault. */
    public static final String NAME = "censys";

    static final String ASSET_BASE = "https://api.platform.censys.io/v3/global/asset";

    static final String AUTHORIZATION_HEADER = "Authorization";
    static final String BEARER_PREFIX = "Bearer ";
    static final String ACCEPT_HEADER = "Accept";
    static final String HOST_ACCEPT = "application/vnd.censys.api.v3.host.v1+json";
    static final String WEB_PROPERTY_ACCEPT = "application/vnd.censys.api.v3.webproperty.v1+json";

    /** §3.3. A web property id is hostname:port; Argus subjects carry no port. */
    static final int WEB_PROPERTY_PORT = 443;

    private static final Set<IntelSubjectKind> SUPPORTED_SUBJECTS =
            Set.of(IntelSubjectKind.DOMAIN, IntelSubjectKind.IP);

    private final Vault vault;
    private final HttpFetcher fetcher;

    /** @param vault an UNLOCKED vault (P1-05: unlocked-ness is a type). Not closed by this class. */
    public CensysSource(Vault vault) {
        this(vault, new JdkHttpFetcher());
    }

    /** Package-private test seam: inject a stub fetcher (P1-02 precedent). */
    CensysSource(Vault vault, HttpFetcher fetcher) {
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

        String token;
        try {
            token = ApiKeyValue.require(stored.get());
        } catch (IllegalArgumentException e) {
            throw new IntelSourceException(NAME,
                    "the stored Censys API token is not usable; re-enter it in key vault settings",
                    e);
        }

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(AUTHORIZATION_HEADER, BEARER_PREFIX + token);
        headers.put(ACCEPT_HEADER, acceptFor(subject.kind()));
        HttpRequestSpec spec = HttpRequestSpec.get(assetUri(subject), headers);

        HttpFetchResult result;
        try {
            result = fetcher.fetch(spec);
        } catch (IOException e) {
            throw new IntelSourceException(NAME, "Censys request failed", e);
        }

        int status = result.statusCode();
        if (status == 404) {
            return IntelResult.unknown(NAME, subject);
        }
        if (status == 401) {
            throw new IntelSourceException(NAME, "Censys rejected the API token", 401);
        }
        if (status == 403) {
            throw new IntelSourceException(NAME, "Censys request was forbidden", 403);
        }
        if (status == 422) {
            throw new IntelSourceException(NAME, "Censys rejected the request parameters", 422);
        }
        if (status == 429) {
            throw new IntelSourceException(NAME, "Censys concurrency limit exceeded", 429);
        }
        if (status == 503) {
            throw new IntelSourceException(NAME, "Censys rate limit exceeded", 503);
        }
        if (status == 400) {
            throw new IntelSourceException(NAME, "Censys rejected the request", 400);
        }
        if (!result.isSuccess()) {
            throw new IntelSourceException(NAME, "Censys returned HTTP " + status, status);
        }
        if (result.body() == null || result.body().isBlank()) {
            throw new IntelSourceException(NAME, "Censys response body was blank");
        }

        CensysAssetReport report = CensysResponseParser.parse(subject.kind(), result.body());
        return report.toIntelResult(subject);
    }

    /**
     * Package-private, tested directly. CARRIES NO SECRET MATERIAL (plan §3.3): the token is a
     * header, not part of this URI.
     *
     * No percent-encoding layer for the subject — IntelSubject has already pushed the value
     * through DomainName.normalize (lowercase LDH + dots) or IpAddress.normalize (digits/dots,
     * or lowercase hex/colons). ':' is a legal RFC 3986 pchar, so both the ':443' suffix and a
     * bare IPv6 literal parse in a path segment of an absolute URI.
     */
    static URI assetUri(IntelSubject subject) {
        return switch (subject.kind()) {
            case DOMAIN -> URI.create(
                    ASSET_BASE + "/webproperty/" + subject.value() + ":" + WEB_PROPERTY_PORT);
            case IP -> URI.create(ASSET_BASE + "/host/" + subject.value());
        };
    }

    /** The versioned vendor media type for this subject kind (plan §1.1(d)). */
    static String acceptFor(IntelSubjectKind kind) {
        return switch (kind) {
            case DOMAIN -> WEB_PROPERTY_ACCEPT;
            case IP -> HOST_ACCEPT;
        };
    }
}
