package com.argus.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link ShodanSource} — identity, routing, URI + key placement + redaction, vault/key
 * handling, the HTTP status ladder, and the happy path (plan §6.3-§6.6). Uses
 * {@link FakeHttpFetcher} plus a real {@link Vault} via {@code VaultStore.forTesting}. No live
 * network calls.
 */
class ShodanSourceTest {

    // VaultCrypto.MIN_ITERATIONS floor (plan §8 R1 precedent, VaultRoundTripTest).
    private static final int TEST_ITERATIONS = 100_000;
    private static final String DUMMY_KEY = "test-key-not-a-real-shodan-key-000001";
    private static final IntelSubject A_DOMAIN = IntelSubject.domain("example.com");
    private static final IntelSubject AN_IP = IntelSubject.ip("1.2.3.4");

    private Vault newVault(Path tempDir) throws VaultException {
        VaultStore store = VaultStore.forTesting(tempDir, TEST_ITERATIONS);
        return store.create(OperatorId.of("nishat"), VaultFixtures.VALID_PASSWORD.clone());
    }

    private Vault newVaultWithKey(Path tempDir) throws VaultException {
        Vault vault = newVault(tempDir);
        vault.put(ApiKeyNames.forSource(ShodanSource.NAME), DUMMY_KEY);
        return vault;
    }

    // ---- 6.3 identity, routing, URI, key placement ----

    @Test
    void nameIsTheShodanLiteralAndMapsToApikeyShodan(@TempDir Path tempDir) throws Exception {
        ShodanSource source = new ShodanSource(newVault(tempDir), new FakeHttpFetcher());
        assertEquals("shodan", source.name());
        assertEquals("apikey.shodan", ApiKeyNames.forSource(source.name()));
    }

    @Test
    void supportsOnlyIpSubjects(@TempDir Path tempDir) throws Exception {
        ShodanSource source = new ShodanSource(newVault(tempDir), new FakeHttpFetcher());
        assertEquals(Set.of(IntelSubjectKind.IP), source.supportedSubjects());
    }

    @Test
    void supportedSubjectsIsUnmodifiable(@TempDir Path tempDir) throws Exception {
        ShodanSource source = new ShodanSource(newVault(tempDir), new FakeHttpFetcher());
        assertThrows(UnsupportedOperationException.class,
                () -> source.supportedSubjects().add(IntelSubjectKind.DOMAIN));
    }

    @Test
    void ipSubjectHitsTheHostEndpoint() {
        URI uri = ShodanSource.hostReportUri(AN_IP, DUMMY_KEY);
        assertEquals("/shodan/host/1.2.3.4", uri.getRawPath());
    }

    @Test
    void ipv6SubjectProducesAWellFormedPathSegment() {
        IntelSubject ipv6 = IntelSubject.ip("2001:db8::1");
        URI uri = ShodanSource.hostReportUri(ipv6, DUMMY_KEY);
        assertEquals("/shodan/host/2001:db8::1", uri.getRawPath());
    }

    @Test
    void apiKeyIsSentInTheQueryStringAndInNoHeader(@TempDir Path tempDir) throws Exception {
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        fetcher.willReturn(new HttpFetchResult(404, ""));
        ShodanSource source = new ShodanSource(newVaultWithKey(tempDir), fetcher);

        source.query(AN_IP);

        HttpRequestSpec spec = fetcher.requestedSpecs().get(0);
        assertEquals("key=" + DUMMY_KEY, spec.uri().getRawQuery());
        assertTrue(spec.headers().isEmpty());
    }

    @Test
    void requestSpecToStringRedactsTheKey(@TempDir Path tempDir) throws Exception {
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        fetcher.willReturn(new HttpFetchResult(404, ""));
        ShodanSource source = new ShodanSource(newVaultWithKey(tempDir), fetcher);

        source.query(AN_IP);

        String text = fetcher.requestedSpecs().get(0).toString();
        assertFalse(text.contains(DUMMY_KEY));
    }

    @Test
    void apiKeyWithReservedCharactersIsPercentEncodedAndTheUriStillParses() {
        String trickyKey = "a&b=c%d#e";
        URI uri = ShodanSource.hostReportUri(AN_IP, trickyKey);

        String rawQuery = uri.getRawQuery();
        assertTrue(rawQuery.startsWith("key="));
        String encodedValue = rawQuery.substring("key=".length());
        // No bare delimiter characters may survive encoding.
        assertFalse(encodedValue.contains("&"));
        assertFalse(encodedValue.contains("#"));

        String decoded = URLDecoder.decode(encodedValue, StandardCharsets.UTF_8);
        assertEquals(trickyKey, decoded);
    }

    // ---- 6.4 vault / key handling ----

    @Test
    void missingVaultEntryThrowsMissingApiKeyExceptionNamingTheEntry(@TempDir Path tempDir)
            throws Exception {
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        ShodanSource source = new ShodanSource(newVault(tempDir), fetcher);

        MissingApiKeyException e = assertThrows(MissingApiKeyException.class,
                () -> source.query(AN_IP));

        assertTrue(e.getMessage().contains("apikey.shodan"));
        assertEquals(0, fetcher.callCount());
    }

    @Test
    void closedVaultSurfacesAsIntelSourceExceptionNotMissingApiKey(@TempDir Path tempDir)
            throws Exception {
        Vault vault = newVaultWithKey(tempDir);
        vault.close();
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        ShodanSource source = new ShodanSource(vault, fetcher);

        IntelSourceException e = assertThrows(IntelSourceException.class,
                () -> source.query(AN_IP));
        assertFalse(e instanceof MissingApiKeyException);
    }

    @Test
    void unusableStoredKeySurfacesAsIntelSourceException(@TempDir Path tempDir) throws Exception {
        Vault vault = newVault(tempDir);
        vault.put(ApiKeyNames.forSource(ShodanSource.NAME), "bad key\rvalue");
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        ShodanSource source = new ShodanSource(vault, fetcher);

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
        ShodanSource source = new ShodanSource(vault, fetcher);

        source.query(AN_IP);
        assertEquals("key=" + DUMMY_KEY, fetcher.requestedSpecs().get(0).uri().getRawQuery());

        String secondKey = "second-key-not-a-real-shodan-key-000002";
        vault.put(ApiKeyNames.forSource(ShodanSource.NAME), secondKey);
        source.query(AN_IP);

        assertEquals("key=" + secondKey, fetcher.requestedSpecs().get(1).uri().getRawQuery());
    }

    @Test
    void noDeclaredInstanceFieldOfTypeStringOnTheSource() {
        for (Field field : ShodanSource.class.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            assertFalse(field.getType().equals(String.class),
                    "ShodanSource must not have a String instance field: " + field);
        }
    }

    // ---- 6.5 status ladder & invariant 7 ----

    @Test
    void notFoundYieldsUnknownResultNotAnException(@TempDir Path tempDir) throws Exception {
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        fetcher.willReturn(
                new HttpFetchResult(404, Fixtures.read("shodan", "error-not-found.json")));
        ShodanSource source = new ShodanSource(newVaultWithKey(tempDir), fetcher);

        IntelResult result = source.query(AN_IP);

        assertEquals(IntelVerdict.UNKNOWN, result.verdict());
        assertEquals(0, result.score());
        assertEquals(List.of(), result.cveIds());
        assertEquals(java.util.Map.of(), result.attributes());
    }

    @Test
    void unauthorizedThrowsWithStatus401AndIsNotAMissingApiKeyException(@TempDir Path tempDir)
            throws Exception {
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        fetcher.willReturn(new HttpFetchResult(401, "{\"error\":\"Invalid API key\"}"));
        ShodanSource source = new ShodanSource(newVaultWithKey(tempDir), fetcher);

        IntelSourceException e = assertThrows(IntelSourceException.class,
                () -> source.query(AN_IP));
        assertEquals(401, e.statusCode());
        assertFalse(e instanceof MissingApiKeyException);
    }

    @Test
    void forbiddenThrowsWithStatus403(@TempDir Path tempDir) throws Exception {
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        fetcher.willReturn(new HttpFetchResult(403, "{\"error\":\"Forbidden\"}"));
        ShodanSource source = new ShodanSource(newVaultWithKey(tempDir), fetcher);

        IntelSourceException e = assertThrows(IntelSourceException.class,
                () -> source.query(AN_IP));
        assertEquals(403, e.statusCode());
    }

    @Test
    void rateLimitedThrowsWithStatus429AndIsNotRetried(@TempDir Path tempDir) throws Exception {
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        fetcher.willReturn(new HttpFetchResult(429, "{\"error\":\"Rate limit exceeded\"}"));
        ShodanSource source = new ShodanSource(newVaultWithKey(tempDir), fetcher);

        IntelSourceException e = assertThrows(IntelSourceException.class,
                () -> source.query(AN_IP));
        assertEquals(429, e.statusCode());
        assertEquals(1, fetcher.callCount());
    }

    @Test
    void serverErrorThrowsWithStatus500(@TempDir Path tempDir) throws Exception {
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        fetcher.willReturn(new HttpFetchResult(500, "internal error"));
        ShodanSource source = new ShodanSource(newVaultWithKey(tempDir), fetcher);

        IntelSourceException e = assertThrows(IntelSourceException.class,
                () -> source.query(AN_IP));
        assertEquals(500, e.statusCode());
    }

    @Test
    void transportIoExceptionIsWrappedWithCausePreserved(@TempDir Path tempDir) throws Exception {
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        java.io.IOException cause = new java.io.IOException("connection reset");
        fetcher.willThrow(cause);
        ShodanSource source = new ShodanSource(newVaultWithKey(tempDir), fetcher);

        IntelSourceException e = assertThrows(IntelSourceException.class,
                () -> source.query(AN_IP));
        assertEquals(cause, e.getCause());
    }

    @Test
    void interruptedExceptionPropagatesUnwrapped(@TempDir Path tempDir) throws Exception {
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        fetcher.willThrow(new InterruptedException("cancelled"));
        ShodanSource source = new ShodanSource(newVaultWithKey(tempDir), fetcher);

        assertThrows(InterruptedException.class, () -> source.query(AN_IP));
    }

    @Test
    void noExceptionMessageContainsTheKeyTheUriTheQueryStringOrTheErrorBody(
            @TempDir Path tempDir) throws Exception {
        String errorLiteral = "never-seen-this-literal-shodan-error";
        int[] statuses = {401, 403, 429, 500};
        Vault vault = newVaultWithKey(tempDir);
        for (int status : statuses) {
            FakeHttpFetcher fetcher = new FakeHttpFetcher();
            fetcher.willReturn(new HttpFetchResult(status, "{\"error\":\"" + errorLiteral + "\"}"));
            ShodanSource source = new ShodanSource(vault, fetcher);

            IntelSourceException e = assertThrows(IntelSourceException.class,
                    () -> source.query(AN_IP));
            String message = e.getMessage();
            assertFalse(message.contains(DUMMY_KEY));
            assertFalse(message.toLowerCase(Locale.ROOT).contains("shodan.io"));
            assertFalse(message.contains("key="));
            assertFalse(message.contains(errorLiteral));
        }
    }

    // ---- 6.6 subject validation & happy path ----

    @Test
    void nullSubjectThrowsIllegalArgumentBeforeAnyVaultReadOrFetch(@TempDir Path tempDir)
            throws Exception {
        Vault vault = newVaultWithKey(tempDir);
        vault.close();
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        ShodanSource source = new ShodanSource(vault, fetcher);

        assertThrows(IllegalArgumentException.class, () -> source.query(null));
        assertEquals(0, fetcher.callCount());
    }

    @Test
    void domainSubjectThrowsIllegalArgumentBeforeAnyVaultReadOrFetch(@TempDir Path tempDir)
            throws Exception {
        Vault vault = newVaultWithKey(tempDir);
        vault.close();
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        ShodanSource source = new ShodanSource(vault, fetcher);

        assertThrows(IllegalArgumentException.class, () -> source.query(A_DOMAIN));
        assertEquals(0, fetcher.callCount());
    }

    @Test
    void hostWithVulnsFixtureProducesSuspiciousVerdictAndTheExpectedCveIds(@TempDir Path tempDir)
            throws Exception {
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        fetcher.willReturn(
                new HttpFetchResult(200, Fixtures.read("shodan", "host-with-vulns.json")));
        ShodanSource source = new ShodanSource(newVaultWithKey(tempDir), fetcher);

        IntelResult result = source.query(AN_IP);

        assertEquals(IntelVerdict.SUSPICIOUS, result.verdict());
        List<String> ids = result.cveIds().stream().map(CveId::id).toList();
        assertTrue(ids.contains("CVE-2021-44228"));
        assertTrue(ids.contains("CVE-2020-1938"));
        assertTrue(ids.contains("CVE-2019-0211"));
    }

    @Test
    void hostWithVulnsFixtureScoreMatchesPointsPerCveTimesCveCount(@TempDir Path tempDir)
            throws Exception {
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        fetcher.willReturn(
                new HttpFetchResult(200, Fixtures.read("shodan", "host-with-vulns.json")));
        ShodanSource source = new ShodanSource(newVaultWithKey(tempDir), fetcher);

        IntelResult result = source.query(AN_IP);

        assertEquals(Math.min(IntelResult.MAX_SCORE,
                ShodanHostReport.POINTS_PER_CVE * result.cveIds().size()), result.score());
    }

    @Test
    void hostWithoutVulnsFixtureProducesHarmlessAndEmptyCveIds(@TempDir Path tempDir)
            throws Exception {
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        fetcher.willReturn(
                new HttpFetchResult(200, Fixtures.read("shodan", "host-no-vulns.json")));
        ShodanSource source = new ShodanSource(newVaultWithKey(tempDir), fetcher);

        IntelResult result = source.query(AN_IP);

        assertEquals(IntelVerdict.HARMLESS, result.verdict());
        assertEquals(List.of(), result.cveIds());
    }

    @Test
    void emptyHostObjectProducesUnknownWithScoreZero(@TempDir Path tempDir) throws Exception {
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        fetcher.willReturn(
                new HttpFetchResult(200, Fixtures.read("shodan", "host-empty-object.json")));
        ShodanSource source = new ShodanSource(newVaultWithKey(tempDir), fetcher);

        IntelResult result = source.query(AN_IP);

        assertEquals(IntelVerdict.UNKNOWN, result.verdict());
        assertEquals(0, result.score());
    }

    @Test
    void cveIdsAreCanonicalUppercaseAndSortedInTheResult(@TempDir Path tempDir) throws Exception {
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        fetcher.willReturn(
                new HttpFetchResult(200, Fixtures.read("shodan", "host-with-vulns.json")));
        ShodanSource source = new ShodanSource(newVaultWithKey(tempDir), fetcher);

        IntelResult result = source.query(AN_IP);

        List<String> ids = result.cveIds().stream().map(CveId::id).toList();
        List<String> sorted = new java.util.ArrayList<>(ids);
        sorted.sort(java.util.Comparator.naturalOrder());
        assertEquals(sorted, ids);
        for (String id : ids) {
            assertEquals(id.toUpperCase(Locale.ROOT), id);
        }
    }

    @Test
    void resultSourceNameAndSubjectRoundTrip(@TempDir Path tempDir) throws Exception {
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        fetcher.willReturn(
                new HttpFetchResult(200, Fixtures.read("shodan", "host-with-vulns.json")));
        ShodanSource source = new ShodanSource(newVaultWithKey(tempDir), fetcher);

        IntelResult result = source.query(AN_IP);

        assertEquals(ShodanSource.NAME, result.sourceName());
        assertEquals(AN_IP, result.subject());
    }

    @Test
    void twoIdenticalQueriesProduceEqualResults(@TempDir Path tempDir) throws Exception {
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        fetcher.willReturn(
                new HttpFetchResult(200, Fixtures.read("shodan", "host-with-vulns.json")));
        ShodanSource source = new ShodanSource(newVaultWithKey(tempDir), fetcher);

        IntelResult first = source.query(AN_IP);
        IntelResult second = source.query(AN_IP);

        assertEquals(first, second);
    }

    @Test
    void noFixtureEverProducesAMaliciousVerdict(@TempDir Path tempDir) throws Exception {
        String[] fixtures = {
                "host-with-vulns.json", "host-no-vulns.json", "host-banner-vulns-only.json",
                "host-overlapping-vulns.json", "host-many-vulns.json", "host-minimal.json",
                "host-empty-object.json"
        };
        Vault vault = newVaultWithKey(tempDir);
        for (String fixture : fixtures) {
            FakeHttpFetcher fetcher = new FakeHttpFetcher();
            fetcher.willReturn(new HttpFetchResult(200, Fixtures.read("shodan", fixture)));
            ShodanSource source = new ShodanSource(vault, fetcher);

            IntelResult result = source.query(AN_IP);
            assertNotEquals(IntelVerdict.MALICIOUS, result.verdict(), fixture);
        }
    }
}
