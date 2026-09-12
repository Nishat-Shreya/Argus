package com.argus.core;

import java.io.IOException;
import java.net.URI;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The first concrete {@link IntelSource}: VirusTotal v3 domain/IP reputation. Immutable and
 * safe for concurrent use by multiple scan threads (invariant 5) — exactly two final fields,
 * neither a cache, both injected. The API key is a LOCAL read from the vault on every
 * {@link #query}, never a field (plan §4): a cached key would outlive the vault's unlock.
 *
 * BLOCKING: query() performs network I/O. com.argus.ui callers run it on a background thread
 * (invariant 3). Cancellation is thread interrupt — never wrapped, never swallowed.
 */
public final class VirusTotalSource implements IntelSource {

    /** The IntelSource.name() literal. Public so ui/P2-06 can reference it without a Vault. */
    public static final String NAME = "virustotal";

    static final String API_BASE = "https://www.virustotal.com/api/v3/";
    static final String API_KEY_HEADER = "x-apikey";

    private static final Set<IntelSubjectKind> SUPPORTED_SUBJECTS =
            Set.of(IntelSubjectKind.DOMAIN, IntelSubjectKind.IP);

    private final Vault vault;
    private final HttpFetcher fetcher;

    /** @param vault an UNLOCKED vault (P1-05: unlocked-ness is a type). Not closed by this class. */
    public VirusTotalSource(Vault vault) {
        this(vault, new JdkHttpFetcher());
    }

    /** Package-private test seam: inject a stub fetcher (P1-02 precedent). */
    VirusTotalSource(Vault vault, HttpFetcher fetcher) {
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
                    "the stored VirusTotal API key is not usable; re-enter it in key vault settings",
                    e);
        }

        HttpRequestSpec spec = HttpRequestSpec.get(reportUri(subject), API_KEY_HEADER, apiKey);

        HttpFetchResult result;
        try {
            result = fetcher.fetch(spec);
        } catch (IOException e) {
            throw new IntelSourceException(NAME, "VirusTotal request failed", e);
        }

        int status = result.statusCode();
        if (status == 404) {
            return IntelResult.unknown(NAME, subject);
        }
        if (status == 401) {
            throw new IntelSourceException(NAME, "VirusTotal rejected the API key", 401);
        }
        if (status == 403) {
            throw new IntelSourceException(NAME, "VirusTotal request was forbidden", 403);
        }
        if (status == 429) {
            throw new IntelSourceException(NAME, "VirusTotal rate limit exceeded", 429);
        }
        if (!result.isSuccess()) {
            throw new IntelSourceException(NAME, "VirusTotal returned HTTP " + status, status);
        }

        VirusTotalReport report = VirusTotalResponseParser.parse(result.body(), subject.kind());
        return report.toIntelResult(subject);
    }

    /**
     * Package-private, tested directly: the exact endpoint URI for a validated subject. No
     * percent-encoding layer: IntelSubject's constructor has already pushed the value through
     * DomainName.normalize (lowercase LDH) or IpAddress.normalize (IPv4 digits/dots, IPv6 hex
     * digits/colons — and ':' is a legal pchar in a path segment per RFC 3986).
     */
    static URI reportUri(IntelSubject subject) {
        return switch (subject.kind()) {
            case DOMAIN -> URI.create(API_BASE + "domains/" + subject.value());
            case IP -> URI.create(API_BASE + "ip_addresses/" + subject.value());
        };
    }
}
