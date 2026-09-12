package com.argus.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;

/**
 * {@link VirusTotalSource} — identity, routing, vault/key handling, the HTTP status ladder and
 * the happy path (plan §6.3-§6.6). Uses {@link FakeHttpFetcher} plus a real {@link Vault} via
 * {@code VaultStore.forTesting}. No live network calls.
 */
class VirusTotalSourceTest {

    // VaultCrypto.MIN_ITERATIONS floor (plan §8 R1 precedent, VaultRoundTripTest).
    private static final int TEST_ITERATIONS = 100_000;
    private static final String DUMMY_KEY = "test-key-not-a-real-virustotal-key";
    private static final IntelSubject A_DOMAIN = IntelSubject.domain("example.com");
    private static final IntelSubject AN_IP = IntelSubject.ip("1.2.3.4");

    private Vault newVault(Path tempDir) throws VaultException {
        VaultStore store = VaultStore.forTesting(tempDir, TEST_ITERATIONS);
        return store.create(OperatorId.of("nishat"), VaultFixtures.VALID_PASSWORD.clone());
    }

    private Vault newVaultWithKey(Path tempDir) throws VaultException {
        Vault vault = newVault(tempDir);
        vault.put(ApiKeyNames.forSource(VirusTotalSource.NAME), DUMMY_KEY);
        return vault;
    }

    // ---- 6.3 identity, routing, URI, header ----

    @Test
    void nameIsTheVirustotalLiteral(@TempDir Path tempDir) throws Exception {
        VirusTotalSource source = new VirusTotalSource(newVault(tempDir), new FakeHttpFetcher());
        assertEquals("virustotal", source.name());
        assertEquals("apikey.virustotal", ApiKeyNames.forSource(source.name()));
    }

    @Test
    void supportsBothDomainAndIpSubjects(@TempDir Path tempDir) throws Exception {
        VirusTotalSource source = new VirusTotalSource(newVault(tempDir), new FakeHttpFetcher());
        assertEquals(Set.of(IntelSubjectKind.DOMAIN, IntelSubjectKind.IP),
                source.supportedSubjects());
    }

    @Test
    void supportedSubjectsIsUnmodifiable(@TempDir Path tempDir) throws Exception {
        VirusTotalSource source = new VirusTotalSource(newVault(tempDir), new FakeHttpFetcher());
        assertThrows(UnsupportedOperationException.class,
                () -> source.supportedSubjects().add(IntelSubjectKind.DOMAIN));
    }

    @Test
    void domainSubjectHitsTheDomainsEndpoint() {
        URI uri = VirusTotalSource.reportUri(A_DOMAIN);
        assertEquals(URI.create("https://www.virustotal.com/api/v3/domains/example.com"), uri);
    }

    @Test
    void ipSubjectHitsTheIpAddressesEndpoint() {
        URI uri = VirusTotalSource.reportUri(AN_IP);
        assertEquals(URI.create("https://www.virustotal.com/api/v3/ip_addresses/1.2.3.4"), uri);
    }

    @Test
    void uriHasNoQueryStringSoTheKeyIsNeverInTheUrl() {
        assertEquals(null, VirusTotalSource.reportUri(A_DOMAIN).getQuery());
        assertEquals(null, VirusTotalSource.reportUri(AN_IP).getQuery());
    }

    @Test
    void apiKeyIsSentAsTheXApikeyHeader(@TempDir Path tempDir) throws Exception {
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        fetcher.willReturn(new HttpFetchResult(404, ""));
        VirusTotalSource source = new VirusTotalSource(newVaultWithKey(tempDir), fetcher);

        source.query(A_DOMAIN);

        assertEquals(DUMMY_KEY, fetcher.lastHeaders().get("x-apikey"));
    }

    @Test
    void requestSpecToStringRedactsTheKey(@TempDir Path tempDir) throws Exception {
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        fetcher.willReturn(new HttpFetchResult(404, ""));
        VirusTotalSource source = new VirusTotalSource(newVaultWithKey(tempDir), fetcher);

        source.query(A_DOMAIN);

        String text = fetcher.requestedSpecs().get(0).toString();
        assertFalse(text.contains(DUMMY_KEY));
    }

    @Test
    void ipv6SubjectProducesAWellFormedPathSegment() {
        IntelSubject ipv6 = IntelSubject.ip("2001:db8::1");
        URI uri = VirusTotalSource.reportUri(ipv6);
        assertEquals("/api/v3/ip_addresses/2001:db8::1", uri.getRawPath());
    }

    // ---- 6.4 vault / key handling ----

    @Test
    void missingVaultEntryThrowsMissingApiKeyExceptionNamingTheEntry(@TempDir Path tempDir)
            throws Exception {
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        VirusTotalSource source = new VirusTotalSource(newVault(tempDir), fetcher);

        MissingApiKeyException e = assertThrows(MissingApiKeyException.class,
                () -> source.query(A_DOMAIN));

        assertTrue(e.getMessage().contains("apikey.virustotal"));
        assertEquals(0, fetcher.callCount());
    }

    @Test
    void closedVaultSurfacesAsIntelSourceExceptionNotIllegalState(@TempDir Path tempDir)
            throws Exception {
        Vault vault = newVaultWithKey(tempDir);
        vault.close();
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        VirusTotalSource source = new VirusTotalSource(vault, fetcher);

        IntelSourceException e = assertThrows(IntelSourceException.class,
                () -> source.query(A_DOMAIN));
        assertFalse(e instanceof MissingApiKeyException);
    }

    @Test
    void unusableStoredKeySurfacesAsIntelSourceException(@TempDir Path tempDir) throws Exception {
        Vault vault = newVault(tempDir);
        vault.put(ApiKeyNames.forSource(VirusTotalSource.NAME), "bad key\rvalue");
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        VirusTotalSource source = new VirusTotalSource(vault, fetcher);

        IntelSourceException e = assertThrows(IntelSourceException.class,
                () -> source.query(A_DOMAIN));
        assertFalse(e instanceof MissingApiKeyException);
        assertFalse(e.getMessage().contains("bad key"));
        assertEquals(0, fetcher.callCount());
    }

    @Test
    void theKeyIsReadOnEveryQueryNotCached(@TempDir Path tempDir) throws Exception {
        Vault vault = newVaultWithKey(tempDir);
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        fetcher.willReturn(new HttpFetchResult(404, ""));
        VirusTotalSource source = new VirusTotalSource(vault, fetcher);

        source.query(A_DOMAIN);
        assertEquals(DUMMY_KEY, fetcher.lastHeaders().get("x-apikey"));

        String secondKey = "second-key-not-a-real-virustotal-key";
        vault.put(ApiKeyNames.forSource(VirusTotalSource.NAME), secondKey);
        source.query(A_DOMAIN);

        assertEquals(secondKey, fetcher.lastHeaders().get("x-apikey"));
    }

    @Test
    void noDeclaredFieldOfTypeStringOnTheSource() {
        // The NAME literal is a public static final constant, not per-instance state; the
        // pin is about the API KEY never becoming a field, so only instance fields count.
        for (Field field : VirusTotalSource.class.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            assertFalse(field.getType().equals(String.class),
                    "VirusTotalSource must not have a String instance field: " + field);
        }
    }

    // ---- 6.5 status ladder & errors ----

    @Test
    void notFoundYieldsUnknownResultNotAnException(@TempDir Path tempDir) throws Exception {
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        fetcher.willReturn(new HttpFetchResult(404, Fixtures.read("virustotal", "error-not-found.json")));
        VirusTotalSource source = new VirusTotalSource(newVaultWithKey(tempDir), fetcher);

        IntelResult result = source.query(A_DOMAIN);

        assertEquals(IntelVerdict.UNKNOWN, result.verdict());
        assertEquals(0, result.score());
        assertEquals(List.of(), result.cveIds());
    }

    @Test
    void unauthorizedThrowsWithStatus401AndIsNotAMissingApiKeyException(@TempDir Path tempDir)
            throws Exception {
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        fetcher.willReturn(new HttpFetchResult(401, "{\"error\":{\"code\":\"WrongCredentialsError\"}}"));
        VirusTotalSource source = new VirusTotalSource(newVaultWithKey(tempDir), fetcher);

        IntelSourceException e = assertThrows(IntelSourceException.class,
                () -> source.query(A_DOMAIN));
        assertEquals(401, e.statusCode());
        assertFalse(e instanceof MissingApiKeyException);
    }

    @Test
    void forbiddenThrowsWithStatus403(@TempDir Path tempDir) throws Exception {
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        fetcher.willReturn(new HttpFetchResult(403, "{}"));
        VirusTotalSource source = new VirusTotalSource(newVaultWithKey(tempDir), fetcher);

        IntelSourceException e = assertThrows(IntelSourceException.class,
                () -> source.query(A_DOMAIN));
        assertEquals(403, e.statusCode());
    }

    @Test
    void rateLimitedThrowsWithStatus429(@TempDir Path tempDir) throws Exception {
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        fetcher.willReturn(new HttpFetchResult(429, "{}"));
        VirusTotalSource source = new VirusTotalSource(newVaultWithKey(tempDir), fetcher);

        IntelSourceException e = assertThrows(IntelSourceException.class,
                () -> source.query(A_DOMAIN));
        assertEquals(429, e.statusCode());
        assertEquals(1, fetcher.callCount());
    }

    @Test
    void serverErrorThrowsWithStatus500(@TempDir Path tempDir) throws Exception {
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        fetcher.willReturn(new HttpFetchResult(500, "internal error"));
        VirusTotalSource source = new VirusTotalSource(newVaultWithKey(tempDir), fetcher);

        IntelSourceException e = assertThrows(IntelSourceException.class,
                () -> source.query(A_DOMAIN));
        assertEquals(500, e.statusCode());
    }

    @Test
    void transportIoExceptionIsWrappedWithCausePreserved(@TempDir Path tempDir) throws Exception {
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        java.io.IOException cause = new java.io.IOException("connection reset");
        fetcher.willThrow(cause);
        VirusTotalSource source = new VirusTotalSource(newVaultWithKey(tempDir), fetcher);

        IntelSourceException e = assertThrows(IntelSourceException.class,
                () -> source.query(A_DOMAIN));
        assertEquals(cause, e.getCause());
    }

    @Test
    void interruptedExceptionPropagatesUnwrapped(@TempDir Path tempDir) throws Exception {
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        fetcher.willThrow(new InterruptedException("cancelled"));
        VirusTotalSource source = new VirusTotalSource(newVaultWithKey(tempDir), fetcher);

        assertThrows(InterruptedException.class, () -> source.query(A_DOMAIN));
    }

    @Test
    void noExceptionMessageContainsTheApiKeyTheUriOrTheErrorBody(@TempDir Path tempDir)
            throws Exception {
        String errorMessage = "Domain 'never-seen-this-one.example' not found";
        int[] statuses = {401, 403, 429, 500};
        Vault vault = newVaultWithKey(tempDir);
        for (int status : statuses) {
            FakeHttpFetcher fetcher = new FakeHttpFetcher();
            fetcher.willReturn(new HttpFetchResult(status,
                    "{\"error\":{\"code\":\"X\",\"message\":\"" + errorMessage + "\"}}"));
            VirusTotalSource source = new VirusTotalSource(vault, fetcher);

            IntelSourceException e = assertThrows(IntelSourceException.class,
                    () -> source.query(A_DOMAIN));
            String message = e.getMessage();
            assertFalse(message.contains(DUMMY_KEY));
            assertFalse(message.toLowerCase(Locale.ROOT).contains("virustotal.com"));
            assertFalse(message.contains(errorMessage));
        }
    }

    // ---- 6.6 subject validation & happy path ----

    @Test
    void nullSubjectThrowsBeforeAnyVaultReadOrFetch(@TempDir Path tempDir) throws Exception {
        Vault vault = newVaultWithKey(tempDir);
        vault.close();
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        VirusTotalSource source = new VirusTotalSource(vault, fetcher);

        assertThrows(IllegalArgumentException.class, () -> source.query(null));
        assertEquals(0, fetcher.callCount());
    }

    @Test
    void maliciousDomainFixtureProducesMaliciousVerdictAndExpectedScore(@TempDir Path tempDir)
            throws Exception {
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        fetcher.willReturn(new HttpFetchResult(200,
                Fixtures.read("virustotal", "domain-malicious.json")));
        VirusTotalSource source = new VirusTotalSource(newVaultWithKey(tempDir), fetcher);

        IntelResult result = source.query(A_DOMAIN);

        assertEquals(IntelVerdict.MALICIOUS, result.verdict());
        assertEquals(3, result.score());
    }

    @Test
    void harmlessDomainFixtureProducesHarmlessVerdictAndScoreZero(@TempDir Path tempDir)
            throws Exception {
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        fetcher.willReturn(new HttpFetchResult(200,
                Fixtures.read("virustotal", "domain-harmless.json")));
        VirusTotalSource source = new VirusTotalSource(newVaultWithKey(tempDir), fetcher);

        IntelResult result = source.query(A_DOMAIN);

        assertEquals(IntelVerdict.HARMLESS, result.verdict());
        assertEquals(0, result.score());
    }

    @Test
    void suspiciousIpFixtureProducesSuspiciousVerdict(@TempDir Path tempDir) throws Exception {
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        fetcher.willReturn(new HttpFetchResult(200,
                Fixtures.read("virustotal", "ip-suspicious.json")));
        VirusTotalSource source = new VirusTotalSource(newVaultWithKey(tempDir), fetcher);

        IntelResult result = source.query(AN_IP);

        assertEquals(IntelVerdict.SUSPICIOUS, result.verdict());
    }

    @Test
    void resultCveIdsAreAlwaysEmpty(@TempDir Path tempDir) throws Exception {
        String[] fixtures = {"domain-malicious.json", "domain-harmless.json"};
        Vault vault = newVaultWithKey(tempDir);
        for (String fixture : fixtures) {
            FakeHttpFetcher fetcher = new FakeHttpFetcher();
            fetcher.willReturn(new HttpFetchResult(200, Fixtures.read("virustotal", fixture)));
            VirusTotalSource source = new VirusTotalSource(vault, fetcher);

            IntelResult result = source.query(A_DOMAIN);
            assertEquals(List.of(), result.cveIds());
        }
    }

    @Test
    void resultSourceNameAndSubjectRoundTrip(@TempDir Path tempDir) throws Exception {
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        fetcher.willReturn(new HttpFetchResult(200,
                Fixtures.read("virustotal", "domain-malicious.json")));
        VirusTotalSource source = new VirusTotalSource(newVaultWithKey(tempDir), fetcher);

        IntelResult result = source.query(A_DOMAIN);

        assertEquals(VirusTotalSource.NAME, result.sourceName());
        assertEquals(A_DOMAIN, result.subject());
    }

    @Test
    void twoIdenticalQueriesProduceEqualResults(@TempDir Path tempDir) throws Exception {
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        fetcher.willReturn(new HttpFetchResult(200,
                Fixtures.read("virustotal", "domain-malicious.json")));
        VirusTotalSource source = new VirusTotalSource(newVaultWithKey(tempDir), fetcher);

        IntelResult first = source.query(A_DOMAIN);
        IntelResult second = source.query(A_DOMAIN);

        assertEquals(first, second);
    }
}
