package com.argus.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
 * {@link CensysSource} — identity, routing, URI + key placement + redaction, vault/key
 * handling, the HTTP status ladder, and the happy path (plan §6.4-§6.7). Uses
 * {@link FakeHttpFetcher} plus a real {@link Vault} via {@code VaultStore.forTesting}. No live
 * network calls.
 */
class CensysSourceTest {

    // VaultCrypto.MIN_ITERATIONS floor (plan §8 R1 precedent, VaultRoundTripTest).
    private static final int TEST_ITERATIONS = 100_000;
    private static final String DUMMY_TOKEN = "test-token-not-a-real-censys-token-000001";
    private static final IntelSubject A_DOMAIN = IntelSubject.domain("example.com");
    private static final IntelSubject AN_IP = IntelSubject.ip("1.2.3.4");

    private Vault newVault(Path tempDir) throws VaultException {
        VaultStore store = VaultStore.forTesting(tempDir, TEST_ITERATIONS);
        return store.create(OperatorId.of("nishat"), VaultFixtures.VALID_PASSWORD.clone());
    }

    private Vault newVaultWithKey(Path tempDir) throws VaultException {
        Vault vault = newVault(tempDir);
        vault.put(ApiKeyNames.forSource(CensysSource.NAME), DUMMY_TOKEN);
        return vault;
    }

    // ---- 6.4 identity, routing, URI, headers, key placement ----

    @Test
    void nameIsTheCensysLiteralAndMapsToApikeyCensys(@TempDir Path tempDir) throws Exception {
        CensysSource source = new CensysSource(newVault(tempDir), new FakeHttpFetcher());
        assertEquals("censys", source.name());
        assertEquals("apikey.censys", ApiKeyNames.forSource(source.name()));
    }

    @Test
    void supportsBothDomainAndIpSubjects(@TempDir Path tempDir) throws Exception {
        CensysSource source = new CensysSource(newVault(tempDir), new FakeHttpFetcher());
        assertEquals(Set.of(IntelSubjectKind.DOMAIN, IntelSubjectKind.IP),
                source.supportedSubjects());
    }

    @Test
    void supportedSubjectsIsUnmodifiable(@TempDir Path tempDir) throws Exception {
        CensysSource source = new CensysSource(newVault(tempDir), new FakeHttpFetcher());
        assertThrows(UnsupportedOperationException.class,
                () -> source.supportedSubjects().add(IntelSubjectKind.DOMAIN));
    }

    @Test
    void assetUriForADomainTargetsTheWebPropertyEndpointOnPort443() {
        URI uri = CensysSource.assetUri(A_DOMAIN);
        assertTrue(uri.getRawPath().endsWith("/webproperty/example.com:443"));
    }

    @Test
    void assetUriForAnIpTargetsTheHostEndpoint() {
        URI uri = CensysSource.assetUri(AN_IP);
        assertTrue(uri.getRawPath().endsWith("/host/1.2.3.4"));
    }

    @Test
    void assetUriForIpv6ParsesAndCarriesTheLiteral() {
        IntelSubject ipv6 = IntelSubject.ip("2001:db8::1");
        URI uri = CensysSource.assetUri(ipv6);
        assertTrue(uri.getRawPath().endsWith("/host/2001:db8::1"));
    }

    @Test
    void assetUriHasNoQueryString() {
        assertNull(CensysSource.assetUri(A_DOMAIN).getRawQuery());
        assertNull(CensysSource.assetUri(AN_IP).getRawQuery());
    }

    @Test
    void theTokenIsSentAsAuthorizationBearerAndAppearsNowhereInTheUri(@TempDir Path tempDir)
            throws Exception {
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        fetcher.willReturn(new HttpFetchResult(404, ""));
        CensysSource source = new CensysSource(newVaultWithKey(tempDir), fetcher);

        source.query(A_DOMAIN);

        HttpRequestSpec spec = fetcher.requestedSpecs().get(0);
        assertEquals("Bearer " + DUMMY_TOKEN, spec.headers().get("Authorization"));
        assertFalse(spec.uri().toString().contains(DUMMY_TOKEN));
    }

    @Test
    void theAcceptHeaderIsTheVersionedVendorTypeForTheSubjectKind() {
        assertEquals("application/vnd.censys.api.v3.webproperty.v1+json",
                CensysSource.acceptFor(IntelSubjectKind.DOMAIN));
        assertEquals("application/vnd.censys.api.v3.host.v1+json",
                CensysSource.acceptFor(IntelSubjectKind.IP));
    }

    @Test
    void exactlyTwoHeadersAreSent(@TempDir Path tempDir) throws Exception {
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        fetcher.willReturn(new HttpFetchResult(404, ""));
        CensysSource source = new CensysSource(newVaultWithKey(tempDir), fetcher);

        source.query(A_DOMAIN);

        assertEquals(List.of("Accept", "Authorization"),
                fetcher.requestedSpecs().get(0).headerNames());
    }

    @Test
    void requestSpecToStringRedactsTheToken(@TempDir Path tempDir) throws Exception {
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        fetcher.willReturn(new HttpFetchResult(404, ""));
        CensysSource source = new CensysSource(newVaultWithKey(tempDir), fetcher);

        source.query(A_DOMAIN);

        String text = fetcher.requestedSpecs().get(0).toString();
        assertFalse(text.contains(DUMMY_TOKEN));
    }

    @Test
    void exactlyOneHttpCallIsMadePerQuery(@TempDir Path tempDir) throws Exception {
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        fetcher.willReturn(new HttpFetchResult(404, ""));
        CensysSource source = new CensysSource(newVaultWithKey(tempDir), fetcher);

        source.query(A_DOMAIN);

        assertEquals(1, fetcher.callCount());
    }

    // ---- 6.5 vault / key handling ----

    @Test
    void missingVaultEntryThrowsMissingApiKeyException(@TempDir Path tempDir) throws Exception {
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        CensysSource source = new CensysSource(newVault(tempDir), fetcher);

        MissingApiKeyException e = assertThrows(MissingApiKeyException.class,
                () -> source.query(A_DOMAIN));

        assertTrue(e.getMessage().contains("apikey.censys"));
        assertEquals(0, fetcher.callCount());
    }

    @Test
    void closedVaultThrowsIntelSourceExceptionNotMissingApiKey(@TempDir Path tempDir)
            throws Exception {
        Vault vault = newVaultWithKey(tempDir);
        vault.close();
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        CensysSource source = new CensysSource(vault, fetcher);

        IntelSourceException e = assertThrows(IntelSourceException.class,
                () -> source.query(A_DOMAIN));
        assertFalse(e instanceof MissingApiKeyException);
    }

    @Test
    void unusableStoredValueThrowsIntelSourceExceptionNotMissingApiKey(@TempDir Path tempDir)
            throws Exception {
        Vault vault = newVault(tempDir);
        vault.put(ApiKeyNames.forSource(CensysSource.NAME), "bad token\rvalue");
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        CensysSource source = new CensysSource(vault, fetcher);

        IntelSourceException e = assertThrows(IntelSourceException.class,
                () -> source.query(A_DOMAIN));
        assertFalse(e instanceof MissingApiKeyException);
        assertFalse(e.getMessage().contains("bad token"));
        assertEquals(0, fetcher.callCount());
    }

    @Test
    void theTokenIsReadFreshFromTheVaultOnEveryQuery(@TempDir Path tempDir) throws Exception {
        Vault vault = newVaultWithKey(tempDir);
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        fetcher.willReturn(new HttpFetchResult(404, ""));
        CensysSource source = new CensysSource(vault, fetcher);

        source.query(A_DOMAIN);
        assertEquals("Bearer " + DUMMY_TOKEN,
                fetcher.requestedSpecs().get(0).headers().get("Authorization"));

        String secondToken = "second-token-not-a-real-censys-token-000002";
        vault.put(ApiKeyNames.forSource(CensysSource.NAME), secondToken);
        source.query(A_DOMAIN);

        assertEquals("Bearer " + secondToken,
                fetcher.requestedSpecs().get(1).headers().get("Authorization"));
    }

    @Test
    void sourceHasNoStringFieldAtAll() {
        for (Field field : CensysSource.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            assertFalse(field.getType().equals(String.class),
                    "CensysSource must not have a String instance field: " + field);
        }
    }

    @Test
    void subjectValidationHappensBeforeAnyVaultTouch(@TempDir Path tempDir) throws Exception {
        Vault vault = newVaultWithKey(tempDir);
        vault.close();
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        CensysSource source = new CensysSource(vault, fetcher);

        assertThrows(IllegalArgumentException.class, () -> source.query(null));
        assertEquals(0, fetcher.callCount());
    }

    // ---- 6.6 status ladder & invariant 7 ----

    @Test
    void notFoundYieldsUnknownResultNotAnException(@TempDir Path tempDir) throws Exception {
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        fetcher.willReturn(new HttpFetchResult(404, ""));
        CensysSource source = new CensysSource(newVaultWithKey(tempDir), fetcher);

        IntelResult result = source.query(A_DOMAIN);

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
                new HttpFetchResult(401, Fixtures.read("censys", "error-unauthorized.json")));
        CensysSource source = new CensysSource(newVaultWithKey(tempDir), fetcher);

        IntelSourceException e = assertThrows(IntelSourceException.class,
                () -> source.query(A_DOMAIN));
        assertEquals(401, e.statusCode());
        assertFalse(e instanceof MissingApiKeyException);
    }

    @Test
    void forbiddenThrowsWithStatus403(@TempDir Path tempDir) throws Exception {
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        fetcher.willReturn(new HttpFetchResult(403, "{\"error\":{\"message\":\"Forbidden\"}}"));
        CensysSource source = new CensysSource(newVaultWithKey(tempDir), fetcher);

        IntelSourceException e = assertThrows(IntelSourceException.class,
                () -> source.query(A_DOMAIN));
        assertEquals(403, e.statusCode());
    }

    @Test
    void unprocessableThrowsWithStatus422(@TempDir Path tempDir) throws Exception {
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        fetcher.willReturn(
                new HttpFetchResult(422, Fixtures.read("censys", "error-problem-json.json")));
        CensysSource source = new CensysSource(newVaultWithKey(tempDir), fetcher);

        IntelSourceException e = assertThrows(IntelSourceException.class,
                () -> source.query(A_DOMAIN));
        assertEquals(422, e.statusCode());
    }

    @Test
    void concurrencyLimitThrowsWithStatus429AndIsNotRetried(@TempDir Path tempDir)
            throws Exception {
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        fetcher.willReturn(new HttpFetchResult(429, "{\"title\":\"Too many requests\"}"));
        CensysSource source = new CensysSource(newVaultWithKey(tempDir), fetcher);

        IntelSourceException e = assertThrows(IntelSourceException.class,
                () -> source.query(A_DOMAIN));
        assertEquals(429, e.statusCode());
        assertEquals(1, fetcher.callCount());
    }

    @Test
    void rateLimitThrowsWithStatus503AndIsNotRetried(@TempDir Path tempDir) throws Exception {
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        fetcher.willReturn(new HttpFetchResult(503, "{\"title\":\"Service unavailable\"}"));
        CensysSource source = new CensysSource(newVaultWithKey(tempDir), fetcher);

        IntelSourceException e = assertThrows(IntelSourceException.class,
                () -> source.query(A_DOMAIN));
        assertEquals(503, e.statusCode());
        assertEquals(1, fetcher.callCount());
    }

    @Test
    void badRequestThrowsWithStatus400(@TempDir Path tempDir) throws Exception {
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        fetcher.willReturn(new HttpFetchResult(400, "{\"title\":\"Bad request\"}"));
        CensysSource source = new CensysSource(newVaultWithKey(tempDir), fetcher);

        IntelSourceException e = assertThrows(IntelSourceException.class,
                () -> source.query(A_DOMAIN));
        assertEquals(400, e.statusCode());
    }

    @Test
    void serverErrorThrowsWithStatus500(@TempDir Path tempDir) throws Exception {
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        fetcher.willReturn(new HttpFetchResult(500, "internal error"));
        CensysSource source = new CensysSource(newVaultWithKey(tempDir), fetcher);

        IntelSourceException e = assertThrows(IntelSourceException.class,
                () -> source.query(A_DOMAIN));
        assertEquals(500, e.statusCode());
    }

    @Test
    void genericNonTwoHundredStatusThrows(@TempDir Path tempDir) throws Exception {
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        fetcher.willReturn(new HttpFetchResult(418, "teapot"));
        CensysSource source = new CensysSource(newVaultWithKey(tempDir), fetcher);

        IntelSourceException e = assertThrows(IntelSourceException.class,
                () -> source.query(A_DOMAIN));
        assertEquals(418, e.statusCode());
    }

    @Test
    void blank2xxBodyThrows(@TempDir Path tempDir) throws Exception {
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        fetcher.willReturn(new HttpFetchResult(200, ""));
        CensysSource source = new CensysSource(newVaultWithKey(tempDir), fetcher);

        assertThrows(IntelSourceException.class, () -> source.query(A_DOMAIN));
    }

    @Test
    void ioExceptionBecomesIntelSourceExceptionWithCausePreserved(@TempDir Path tempDir)
            throws Exception {
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        java.io.IOException cause = new java.io.IOException("connection reset");
        fetcher.willThrow(cause);
        CensysSource source = new CensysSource(newVaultWithKey(tempDir), fetcher);

        IntelSourceException e = assertThrows(IntelSourceException.class,
                () -> source.query(A_DOMAIN));
        assertEquals(cause, e.getCause());
    }

    @Test
    void interruptedExceptionPropagatesUnwrapped(@TempDir Path tempDir) throws Exception {
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        fetcher.willThrow(new InterruptedException("cancelled"));
        CensysSource source = new CensysSource(newVaultWithKey(tempDir), fetcher);

        assertThrows(InterruptedException.class, () -> source.query(A_DOMAIN));
    }

    @Test
    void noErrorPathRetries(@TempDir Path tempDir) throws Exception {
        int[] statuses = {400, 401, 403, 422, 429, 500, 503};
        Vault vault = newVaultWithKey(tempDir);
        for (int status : statuses) {
            FakeHttpFetcher fetcher = new FakeHttpFetcher();
            fetcher.willReturn(new HttpFetchResult(status, "{}"));
            CensysSource source = new CensysSource(vault, fetcher);

            assertThrows(IntelSourceException.class, () -> source.query(A_DOMAIN));
            assertEquals(1, fetcher.callCount(), "status " + status);
        }
    }

    @Test
    void noExceptionMessageLeaksTheTokenTheUriOrTheErrorBody(@TempDir Path tempDir)
            throws Exception {
        String errorLiteral = "Access credentials are invalid";
        int[] statuses = {401, 403, 422, 429, 503, 400, 500};
        Vault vault = newVaultWithKey(tempDir);
        for (int status : statuses) {
            FakeHttpFetcher fetcher = new FakeHttpFetcher();
            fetcher.willReturn(new HttpFetchResult(status,
                    "{\"error\":{\"message\":\"" + errorLiteral + "\"}}"));
            CensysSource source = new CensysSource(vault, fetcher);

            IntelSourceException e = assertThrows(IntelSourceException.class,
                    () -> source.query(A_DOMAIN));
            String message = e.getMessage();
            assertFalse(message.contains(DUMMY_TOKEN));
            assertFalse(message.toLowerCase(Locale.ROOT).contains("platform.censys.io"));
            assertFalse(message.contains(errorLiteral));
            if (e.getCause() != null) {
                String causeMessage = e.getCause().getMessage();
                if (causeMessage != null) {
                    assertFalse(causeMessage.contains(DUMMY_TOKEN));
                    assertFalse(causeMessage.contains(errorLiteral));
                }
            }
        }
    }

    // NOTE (plan §3.2 step 2 / §6.6): "!supports(subject) -> IllegalArgumentException" is
    // genuinely unreachable for CensysSource today, since supportedSubjects() == {DOMAIN, IP}
    // == every IntelSubjectKind constant that exists. No IntelSubject can be constructed with
    // an unsupported kind, so there is nothing to pin with a test here (kept for the contract,
    // per the plan's own caveat, not tested until IntelSubjectKind gains a third constant).

    // ---- 6.7 happy path, end to end ----

    @Test
    void domainHappyPathReturnsANormalizedIntelResult(@TempDir Path tempDir) throws Exception {
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        fetcher.willReturn(new HttpFetchResult(200,
                Fixtures.read("censys", "webproperty-vulns-with-kev.json")));
        CensysSource source = new CensysSource(newVaultWithKey(tempDir), fetcher);

        IntelResult result = source.query(A_DOMAIN);

        assertEquals(CensysSource.NAME, result.sourceName());
        assertEquals(A_DOMAIN, result.subject());
        assertEquals(IntelVerdict.SUSPICIOUS, result.verdict());
        assertTrue(result.score() > 0);
        assertFalse(result.cveIds().isEmpty());
        assertTrue(result.attributes().containsKey("hostname"));
    }

    @Test
    void ipHappyPathReturnsANormalizedIntelResult(@TempDir Path tempDir) throws Exception {
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        fetcher.willReturn(
                new HttpFetchResult(200, Fixtures.read("censys", "host-service-vulns.json")));
        CensysSource source = new CensysSource(newVaultWithKey(tempDir), fetcher);

        IntelResult result = source.query(AN_IP);

        assertEquals(CensysSource.NAME, result.sourceName());
        assertEquals(AN_IP, result.subject());
        assertEquals(IntelVerdict.SUSPICIOUS, result.verdict());
        assertFalse(result.cveIds().isEmpty());
    }

    @Test
    void resultIsFlaggedForSuspiciousAndMalicious(@TempDir Path tempDir) throws Exception {
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        fetcher.willReturn(
                new HttpFetchResult(200, Fixtures.read("censys", "webproperty-compromised.json")));
        CensysSource source = new CensysSource(newVaultWithKey(tempDir), fetcher);

        IntelResult result = source.query(A_DOMAIN);

        assertEquals(IntelVerdict.MALICIOUS, result.verdict());
        assertTrue(result.isFlagged());
    }

    @Test
    void unknownResultCarriesScoreZeroAndNoCves(@TempDir Path tempDir) throws Exception {
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        fetcher.willReturn(
                new HttpFetchResult(200, Fixtures.read("censys", "webproperty-empty-resource.json")));
        CensysSource source = new CensysSource(newVaultWithKey(tempDir), fetcher);

        IntelResult result = source.query(A_DOMAIN);

        assertEquals(IntelVerdict.UNKNOWN, result.verdict());
        assertEquals(0, result.score());
        assertTrue(result.cveIds().isEmpty());
    }
}
