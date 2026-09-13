package com.argus.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link AbuseIpdbSource} — identity, routing, URI + key placement + redaction, vault/key
 * handling, the HTTP status ladder, and the happy path (plan §6.4-§6.7). Uses
 * {@link FakeHttpFetcher} plus a real {@link Vault} via {@code VaultStore.forTesting}. No live
 * network calls.
 */
class AbuseIpdbSourceTest {

    // VaultCrypto.MIN_ITERATIONS floor (plan §8 R1 precedent, VaultRoundTripTest).
    private static final int TEST_ITERATIONS = 100_000;
    private static final String DUMMY_KEY = "test-key-not-a-real-abuseipdb-key-000001";
    private static final IntelSubject A_DOMAIN = IntelSubject.domain("example.com");
    private static final IntelSubject AN_IP = IntelSubject.ip("1.2.3.4");

    private Vault newVault(Path tempDir) throws VaultException {
        VaultStore store = VaultStore.forTesting(tempDir, TEST_ITERATIONS);
        return store.create(OperatorId.of("nishat"), VaultFixtures.VALID_PASSWORD.clone());
    }

    private Vault newVaultWithKey(Path tempDir) throws VaultException {
        Vault vault = newVault(tempDir);
        vault.put(ApiKeyNames.forSource(AbuseIpdbSource.NAME), DUMMY_KEY);
        return vault;
    }

    // ---- 6.4 identity, routing, URI, key placement ----

    @Test
    void nameIsTheAbuseipdbLiteralAndMapsToApikeyAbuseipdb(@TempDir Path tempDir)
            throws Exception {
        AbuseIpdbSource source = new AbuseIpdbSource(newVault(tempDir), new FakeHttpFetcher());
        assertEquals("abuseipdb", source.name());
        assertEquals("apikey.abuseipdb", ApiKeyNames.forSource(source.name()));
    }

    @Test
    void supportsOnlyIpSubjects(@TempDir Path tempDir) throws Exception {
        AbuseIpdbSource source = new AbuseIpdbSource(newVault(tempDir), new FakeHttpFetcher());
        assertEquals(Set.of(IntelSubjectKind.IP), source.supportedSubjects());
    }

    @Test
    void supportedSubjectsIsUnmodifiable(@TempDir Path tempDir) throws Exception {
        AbuseIpdbSource source = new AbuseIpdbSource(newVault(tempDir), new FakeHttpFetcher());
        assertThrows(UnsupportedOperationException.class,
                () -> source.supportedSubjects().add(IntelSubjectKind.DOMAIN));
    }

    @Test
    void checkUriForIpv4CarriesTheSubjectTheWindowAndVerbose() {
        URI uri = AbuseIpdbSource.checkUri(AN_IP);
        assertEquals("/api/v2/check", uri.getRawPath());
        assertTrue(uri.getRawQuery().contains("ipAddress=1.2.3.4"));
        assertTrue(uri.getRawQuery().contains("maxAgeInDays=" + AbuseIpdbSource.MAX_AGE_IN_DAYS));
        assertTrue(uri.getRawQuery().contains("verbose"));
    }

    @Test
    void checkUriForIpv6ParsesAndCarriesTheLiteralRawInTheQuery() {
        IntelSubject ipv6 = IntelSubject.ip("2001:db8::1");
        URI uri = AbuseIpdbSource.checkUri(ipv6);
        assertTrue(uri.getRawQuery().contains("ipAddress=2001:db8::1"));
    }

    @Test
    void theKeyIsSentInTheKeyHeaderAndAppearsNowhereInTheUri(@TempDir Path tempDir)
            throws Exception {
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        fetcher.willReturn(new HttpFetchResult(404, ""));
        AbuseIpdbSource source = new AbuseIpdbSource(newVaultWithKey(tempDir), fetcher);

        source.query(AN_IP);

        HttpRequestSpec spec = fetcher.requestedSpecs().get(0);
        assertEquals(DUMMY_KEY, spec.headers().get("Key"));
        assertFalse(spec.uri().toString().contains(DUMMY_KEY));
    }

    @Test
    void requestSpecToStringRedactsTheKey(@TempDir Path tempDir) throws Exception {
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        fetcher.willReturn(new HttpFetchResult(404, ""));
        AbuseIpdbSource source = new AbuseIpdbSource(newVaultWithKey(tempDir), fetcher);

        source.query(AN_IP);

        String text = fetcher.requestedSpecs().get(0).toString();
        assertFalse(text.contains(DUMMY_KEY));
    }

    @Test
    void exactlyOneHeaderIsSetByTheSource(@TempDir Path tempDir) throws Exception {
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        fetcher.willReturn(new HttpFetchResult(404, ""));
        AbuseIpdbSource source = new AbuseIpdbSource(newVaultWithKey(tempDir), fetcher);

        source.query(AN_IP);

        assertEquals(List.of("Key"), fetcher.requestedSpecs().get(0).headerNames());
    }

    // ---- 6.5 vault / key handling ----

    @Test
    void missingVaultEntryThrowsMissingApiKeyExceptionNamingTheEntry(@TempDir Path tempDir)
            throws Exception {
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        AbuseIpdbSource source = new AbuseIpdbSource(newVault(tempDir), fetcher);

        MissingApiKeyException e = assertThrows(MissingApiKeyException.class,
                () -> source.query(AN_IP));

        assertTrue(e.getMessage().contains("apikey.abuseipdb"));
        assertEquals(0, fetcher.callCount());
    }

    @Test
    void closedVaultSurfacesAsIntelSourceExceptionNotMissingApiKey(@TempDir Path tempDir)
            throws Exception {
        Vault vault = newVaultWithKey(tempDir);
        vault.close();
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        AbuseIpdbSource source = new AbuseIpdbSource(vault, fetcher);

        IntelSourceException e = assertThrows(IntelSourceException.class,
                () -> source.query(AN_IP));
        assertFalse(e instanceof MissingApiKeyException);
    }

    @Test
    void unusableStoredKeySurfacesAsIntelSourceException(@TempDir Path tempDir) throws Exception {
        Vault vault = newVault(tempDir);
        vault.put(ApiKeyNames.forSource(AbuseIpdbSource.NAME), "bad key\rvalue");
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        AbuseIpdbSource source = new AbuseIpdbSource(vault, fetcher);

        IntelSourceException e = assertThrows(IntelSourceException.class,
                () -> source.query(AN_IP));
        assertFalse(e instanceof MissingApiKeyException);
        assertFalse(e.getMessage().contains("bad key"));
        assertEquals(0, fetcher.callCount());
    }

    @Test
    void theKeyIsReadOnEveryQueryNotCached(@TempDir Path tempDir) throws Exception {
        Vault vault = newVaultWithKey(tempDir);
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        fetcher.willReturn(new HttpFetchResult(404, ""));
        AbuseIpdbSource source = new AbuseIpdbSource(vault, fetcher);

        source.query(AN_IP);
        assertEquals(DUMMY_KEY, fetcher.requestedSpecs().get(0).headers().get("Key"));

        String secondKey = "second-key-not-a-real-abuseipdb-key-000002";
        vault.put(ApiKeyNames.forSource(AbuseIpdbSource.NAME), secondKey);
        source.query(AN_IP);

        assertEquals(secondKey, fetcher.requestedSpecs().get(1).headers().get("Key"));
    }

    @Test
    void noDeclaredInstanceFieldOfTypeStringOnTheSource() {
        for (Field field : AbuseIpdbSource.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            assertFalse(field.getType().equals(String.class),
                    "AbuseIpdbSource must not have a String instance field: " + field);
        }
    }

    // ---- 6.6 status ladder & invariant 7 ----

    @Test
    void notFoundYieldsUnknownResultNotAnException(@TempDir Path tempDir) throws Exception {
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        fetcher.willReturn(new HttpFetchResult(404, ""));
        AbuseIpdbSource source = new AbuseIpdbSource(newVaultWithKey(tempDir), fetcher);

        IntelResult result = source.query(AN_IP);

        assertEquals(IntelVerdict.UNKNOWN, result.verdict());
        assertEquals(0, result.score());
        assertEquals(List.of(), result.cveIds());
        assertEquals(Map.of(), result.attributes());
    }

    @Test
    void unauthorizedThrowsWithStatus401AndIsNotAMissingApiKeyException(@TempDir Path tempDir)
            throws Exception {
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        fetcher.willReturn(
                new HttpFetchResult(401, Fixtures.read("abuseipdb", "error-unauthorized.json")));
        AbuseIpdbSource source = new AbuseIpdbSource(newVaultWithKey(tempDir), fetcher);

        IntelSourceException e = assertThrows(IntelSourceException.class,
                () -> source.query(AN_IP));
        assertEquals(401, e.statusCode());
        assertFalse(e instanceof MissingApiKeyException);
    }

    @Test
    void forbiddenThrowsWithStatus403(@TempDir Path tempDir) throws Exception {
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        fetcher.willReturn(new HttpFetchResult(403, "{\"errors\":[{\"detail\":\"Forbidden\"}]}"));
        AbuseIpdbSource source = new AbuseIpdbSource(newVaultWithKey(tempDir), fetcher);

        IntelSourceException e = assertThrows(IntelSourceException.class,
                () -> source.query(AN_IP));
        assertEquals(403, e.statusCode());
    }

    @Test
    void unprocessableThrowsWithStatus422AndIsNotAnUnknownResult(@TempDir Path tempDir)
            throws Exception {
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        fetcher.willReturn(
                new HttpFetchResult(422, Fixtures.read("abuseipdb", "error-unprocessable.json")));
        AbuseIpdbSource source = new AbuseIpdbSource(newVaultWithKey(tempDir), fetcher);

        IntelSourceException e = assertThrows(IntelSourceException.class,
                () -> source.query(AN_IP));
        assertEquals(422, e.statusCode());
    }

    @Test
    void rateLimitedThrowsWithStatus429AndIsNotRetried(@TempDir Path tempDir) throws Exception {
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        fetcher.willReturn(
                new HttpFetchResult(429, "{\"errors\":[{\"detail\":\"Rate limit exceeded\"}]}"));
        AbuseIpdbSource source = new AbuseIpdbSource(newVaultWithKey(tempDir), fetcher);

        IntelSourceException e = assertThrows(IntelSourceException.class,
                () -> source.query(AN_IP));
        assertEquals(429, e.statusCode());
        assertEquals(1, fetcher.callCount());
    }

    @Test
    void serverErrorThrowsWithStatus500(@TempDir Path tempDir) throws Exception {
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        fetcher.willReturn(new HttpFetchResult(500, "internal error"));
        AbuseIpdbSource source = new AbuseIpdbSource(newVaultWithKey(tempDir), fetcher);

        IntelSourceException e = assertThrows(IntelSourceException.class,
                () -> source.query(AN_IP));
        assertEquals(500, e.statusCode());
    }

    @Test
    void transportIoExceptionIsWrappedWithCausePreserved(@TempDir Path tempDir) throws Exception {
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        java.io.IOException cause = new java.io.IOException("connection reset");
        fetcher.willThrow(cause);
        AbuseIpdbSource source = new AbuseIpdbSource(newVaultWithKey(tempDir), fetcher);

        IntelSourceException e = assertThrows(IntelSourceException.class,
                () -> source.query(AN_IP));
        assertEquals(cause, e.getCause());
    }

    @Test
    void interruptedExceptionPropagatesUnwrapped(@TempDir Path tempDir) throws Exception {
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        fetcher.willThrow(new InterruptedException("cancelled"));
        AbuseIpdbSource source = new AbuseIpdbSource(newVaultWithKey(tempDir), fetcher);

        assertThrows(InterruptedException.class, () -> source.query(AN_IP));
    }

    @Test
    void noExceptionMessageContainsTheKeyTheHostTheQueryStringOrTheErrorBody(
            @TempDir Path tempDir) throws Exception {
        String errorLiteral = "never-seen-this-literal-abuseipdb-error";
        int[] statuses = {401, 403, 422, 429, 500};
        Vault vault = newVaultWithKey(tempDir);
        for (int status : statuses) {
            FakeHttpFetcher fetcher = new FakeHttpFetcher();
            fetcher.willReturn(new HttpFetchResult(status,
                    "{\"errors\":[{\"detail\":\"" + errorLiteral + "\"}]}"));
            AbuseIpdbSource source = new AbuseIpdbSource(vault, fetcher);

            IntelSourceException e = assertThrows(IntelSourceException.class,
                    () -> source.query(AN_IP));
            String message = e.getMessage();
            assertFalse(message.contains(DUMMY_KEY));
            assertFalse(message.toLowerCase(Locale.ROOT).contains("abuseipdb.com"));
            assertFalse(message.contains("ipAddress="));
            assertFalse(message.contains(DUMMY_KEY));
            assertFalse(message.contains(errorLiteral));
        }
    }

    // ---- 6.7 subject validation & happy path ----

    @Test
    void nullSubjectThrowsIllegalArgumentBeforeAnyVaultReadOrFetch(@TempDir Path tempDir)
            throws Exception {
        Vault vault = newVaultWithKey(tempDir);
        vault.close();
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        AbuseIpdbSource source = new AbuseIpdbSource(vault, fetcher);

        assertThrows(IllegalArgumentException.class, () -> source.query(null));
        assertEquals(0, fetcher.callCount());
    }

    @Test
    void domainSubjectThrowsIllegalArgumentBeforeAnyVaultReadOrFetch(@TempDir Path tempDir)
            throws Exception {
        Vault vault = newVaultWithKey(tempDir);
        vault.close();
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        AbuseIpdbSource source = new AbuseIpdbSource(vault, fetcher);

        assertThrows(IllegalArgumentException.class, () -> source.query(A_DOMAIN));
        assertEquals(0, fetcher.callCount());
    }

    @Test
    void maliciousFixtureProducesMaliciousVerdictScoreOneHundred(@TempDir Path tempDir)
            throws Exception {
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        fetcher.willReturn(
                new HttpFetchResult(200, Fixtures.read("abuseipdb", "check-malicious.json")));
        AbuseIpdbSource source = new AbuseIpdbSource(newVaultWithKey(tempDir), fetcher);

        IntelResult result = source.query(AN_IP);

        assertEquals(IntelVerdict.MALICIOUS, result.verdict());
        assertEquals(100, result.score());
        assertEquals(AbuseIpdbSource.NAME, result.sourceName());
        assertEquals(AN_IP, result.subject());
    }

    @Test
    void suspiciousFixtureProducesSuspiciousVerdictWithCategoriesAttribute(@TempDir Path tempDir)
            throws Exception {
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        fetcher.willReturn(
                new HttpFetchResult(200, Fixtures.read("abuseipdb", "check-suspicious.json")));
        AbuseIpdbSource source = new AbuseIpdbSource(newVaultWithKey(tempDir), fetcher);

        IntelResult result = source.query(AN_IP);

        assertEquals(IntelVerdict.SUSPICIOUS, result.verdict());
        assertEquals(42, result.score());
        assertTrue(result.attributes().containsKey("categories"));
    }

    @Test
    void cleanFixtureProducesHarmlessVerdictScoreZero(@TempDir Path tempDir) throws Exception {
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        fetcher.willReturn(
                new HttpFetchResult(200, Fixtures.read("abuseipdb", "check-clean.json")));
        AbuseIpdbSource source = new AbuseIpdbSource(newVaultWithKey(tempDir), fetcher);

        IntelResult result = source.query(AN_IP);

        assertEquals(IntelVerdict.HARMLESS, result.verdict());
        assertEquals(0, result.score());
    }

    @Test
    void privateIpFixtureProducesUnknownWithIsPublicAttribute(@TempDir Path tempDir)
            throws Exception {
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        fetcher.willReturn(
                new HttpFetchResult(200, Fixtures.read("abuseipdb", "check-private-ip.json")));
        AbuseIpdbSource source = new AbuseIpdbSource(newVaultWithKey(tempDir), fetcher);

        IntelResult result = source.query(AN_IP);

        assertEquals(IntelVerdict.UNKNOWN, result.verdict());
        assertEquals(0, result.score());
        assertEquals("false", result.attributes().get("is_public"));
    }

    @Test
    void whitelistedFixtureVerdictIsNotHarmless(@TempDir Path tempDir) throws Exception {
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        fetcher.willReturn(new HttpFetchResult(200,
                Fixtures.read("abuseipdb", "check-whitelisted-with-score.json")));
        AbuseIpdbSource source = new AbuseIpdbSource(newVaultWithKey(tempDir), fetcher);

        IntelResult result = source.query(AN_IP);

        assertNotEquals(IntelVerdict.HARMLESS, result.verdict());
    }

    @Test
    void twoIdenticalQueriesProduceEqualResults(@TempDir Path tempDir) throws Exception {
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        fetcher.willReturn(
                new HttpFetchResult(200, Fixtures.read("abuseipdb", "check-malicious.json")));
        AbuseIpdbSource source = new AbuseIpdbSource(newVaultWithKey(tempDir), fetcher);

        IntelResult first = source.query(AN_IP);
        IntelResult second = source.query(AN_IP);

        assertEquals(first, second);
    }

    @Test
    void cveIdsAreEmptyForEveryTwoHundredFixture(@TempDir Path tempDir) throws Exception {
        String[] fixtures = {
                "check-clean.json", "check-suspicious.json", "check-malicious.json",
                "check-score-at-threshold.json", "check-score-below-threshold.json",
                "check-score-one.json", "check-private-ip.json",
                "check-whitelisted-with-score.json", "check-tor.json",
                "check-missing-score.json", "check-out-of-range-score.json",
                "check-unknown-category.json", "check-overflowing-fields.json",
                "check-empty-object.json"
        };
        Vault vault = newVaultWithKey(tempDir);
        for (String fixture : fixtures) {
            FakeHttpFetcher fetcher = new FakeHttpFetcher();
            fetcher.willReturn(new HttpFetchResult(200, Fixtures.read("abuseipdb", fixture)));
            AbuseIpdbSource source = new AbuseIpdbSource(vault, fetcher);

            IntelResult result = source.query(AN_IP);
            assertTrue(result.cveIds().isEmpty(), fixture);
        }
    }

    @Test
    void noFixtureEverProducesMoreThanThirteenAttributes(@TempDir Path tempDir) throws Exception {
        String[] fixtures = {
                "check-clean.json", "check-suspicious.json", "check-malicious.json",
                "check-score-at-threshold.json", "check-score-below-threshold.json",
                "check-score-one.json", "check-private-ip.json",
                "check-whitelisted-with-score.json", "check-tor.json",
                "check-missing-score.json", "check-out-of-range-score.json",
                "check-unknown-category.json", "check-overflowing-fields.json",
                "check-empty-object.json"
        };
        Vault vault = newVaultWithKey(tempDir);
        for (String fixture : fixtures) {
            FakeHttpFetcher fetcher = new FakeHttpFetcher();
            fetcher.willReturn(new HttpFetchResult(200, Fixtures.read("abuseipdb", fixture)));
            AbuseIpdbSource source = new AbuseIpdbSource(vault, fetcher);

            IntelResult result = source.query(AN_IP);
            assertTrue(result.attributes().size() <= 13, fixture);
        }
    }
}
