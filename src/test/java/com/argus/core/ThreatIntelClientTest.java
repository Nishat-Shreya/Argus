package com.argus.core;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Tests V/H/R/E/I/C/L/Z of the P2-06 plan (§7.6). */
@Timeout(30)
class ThreatIntelClientTest {

    private static final IntelSubject DOMAIN_SUBJECT = IntelSubject.domain("example.com");
    private static final IntelSubject IP_SUBJECT = IntelSubject.ip("1.2.3.4");

    private static ScriptedIntelSource domainSource(String name) {
        return new ScriptedIntelSource(name, Set.of(IntelSubjectKind.DOMAIN));
    }

    private static ScriptedIntelSource ipOnlySource(String name) {
        return new ScriptedIntelSource(name, Set.of(IntelSubjectKind.IP));
    }

    private static IntelResult okResult(String sourceName) {
        return IntelResult.unknown(sourceName, DOMAIN_SUBJECT);
    }

    private static IntelResult resultWithCves(String sourceName, String... cveIds) {
        return new IntelResult(sourceName, DOMAIN_SUBJECT, IntelVerdict.MALICIOUS, 90,
                List.of(java.util.Arrays.stream(cveIds).map(CveId::new).toArray(CveId[]::new)),
                java.util.Map.of());
    }

    // ---------- Construction / validation ----------

    @Test
    void nullSourceListThrowsNpe() {
        assertThrows(NullPointerException.class, () -> new ThreatIntelClient(null));
    }

    @Test
    void emptySourceListThrowsIae() {
        assertThrows(IllegalArgumentException.class, () -> new ThreatIntelClient(List.of()));
    }

    @Test
    void nullElementThrowsNpe() {
        List<IntelSource> withNull = new ArrayList<>();
        withNull.add(domainSource("virustotal"));
        withNull.add(null);
        assertThrows(NullPointerException.class, () -> new ThreatIntelClient(withNull));
    }

    @Test
    void duplicateSourceNameThrowsIae() {
        ScriptedIntelSource a = domainSource("virustotal");
        ScriptedIntelSource b = domainSource("virustotal");
        assertThrows(IllegalArgumentException.class,
                () -> new ThreatIntelClient(List.of(a, b)));
    }

    @Test
    void sourceNamesIsInjectedOrderAndUnmodifiable() throws Exception {
        ScriptedIntelSource a = domainSource("virustotal");
        ScriptedIntelSource b = domainSource("shodan");
        try (ThreatIntelClient client = new ThreatIntelClient(List.of(a, b))) {
            assertEquals(List.of("virustotal", "shodan"), client.sourceNames());
            assertThrows(UnsupportedOperationException.class,
                    () -> client.sourceNames().add("x"));
        }
    }

    @Test
    void defensiveCopyOfSourceList() throws Exception {
        List<IntelSource> mutable = new ArrayList<>();
        mutable.add(domainSource("virustotal"));
        try (ThreatIntelClient client = new ThreatIntelClient(mutable)) {
            mutable.add(domainSource("shodan"));
            assertEquals(1, client.sourceNames().size());
        }
    }

    @Test
    void enrichNullThrowsNpeAndQueriesNoSource() throws Exception {
        ScriptedIntelSource a = domainSource("virustotal");
        try (ThreatIntelClient client = new ThreatIntelClient(List.of(a))) {
            assertThrows(NullPointerException.class, () -> client.enrich(null));
            assertEquals(0, a.callCount());
        }
    }

    // ---------- Happy path ----------

    @Test
    void allSupportingSourcesReturnResults() throws Exception {
        ScriptedIntelSource a = domainSource("virustotal");
        a.willReturn(okResult("virustotal"));
        ScriptedIntelSource b = domainSource("shodan");
        b.willReturn(okResult("shodan"));
        ScriptedIntelSource c = domainSource("abuseipdb");
        c.willReturn(okResult("abuseipdb"));

        try (ThreatIntelClient client = new ThreatIntelClient(List.of(a, b, c))) {
            IntelReport report = client.enrich(DOMAIN_SUBJECT);

            assertEquals(3, report.results().size());
            assertTrue(report.failures().isEmpty());
            assertTrue(report.outcomes().stream().allMatch(IntelSourceOutcome::isOk));
        }
    }

    @Test
    void outcomeOrderIsInjectedOrderNotCompletionOrder() throws Exception {
        ScriptedIntelSource a = domainSource("virustotal");
        a.willReturn(okResult("virustotal"));
        CountDownLatch releaseA = new CountDownLatch(1);
        a.gate(null, releaseA);

        ScriptedIntelSource b = domainSource("shodan");
        b.willReturn(okResult("shodan"));

        ScriptedIntelSource c = domainSource("abuseipdb");
        c.willReturn(okResult("abuseipdb"));

        try (ThreatIntelClient client = new ThreatIntelClient(List.of(a, b, c))) {
            AtomicReference<IntelReport> reportRef = new AtomicReference<>();
            Thread t = new Thread(() -> {
                try {
                    reportRef.set(client.enrich(DOMAIN_SUBJECT));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            t.start();

            while (b.callCount() < 1 || c.callCount() < 1) {
                Thread.onSpinWait();
            }
            releaseA.countDown();
            t.join(10_000);

            List<String> names = reportRef.get().outcomes().stream()
                    .map(IntelSourceOutcome::sourceName)
                    .toList();
            assertEquals(List.of("virustotal", "shodan", "abuseipdb"), names);
        }
    }

    // ---------- Routing ----------

    @Test
    void unsupportedSubjectIsNeverQueried() throws Exception {
        ScriptedIntelSource ipOnly = ipOnlySource("shodan");
        try (ThreatIntelClient client = new ThreatIntelClient(List.of(ipOnly))) {
            IntelReport report = client.enrich(DOMAIN_SUBJECT);

            assertEquals(IntelSourceStatus.UNSUPPORTED_SUBJECT,
                    report.forSource("shodan").orElseThrow().status());
            assertEquals(0, ipOnly.callCount());
        }
    }

    @Test
    void bothSourcesQueriedWhenBothSupportSubjectKind() throws Exception {
        ScriptedIntelSource both =
                new ScriptedIntelSource("censys", Set.of(IntelSubjectKind.DOMAIN, IntelSubjectKind.IP));
        both.willReturn(IntelResult.unknown("censys", IP_SUBJECT));
        ScriptedIntelSource ipOnly = ipOnlySource("shodan");
        ipOnly.willReturn(IntelResult.unknown("shodan", IP_SUBJECT));

        try (ThreatIntelClient client = new ThreatIntelClient(List.of(both, ipOnly))) {
            IntelReport report = client.enrich(IP_SUBJECT);

            assertEquals(1, both.callCount());
            assertEquals(1, ipOnly.callCount());
            assertTrue(report.forSource("censys").orElseThrow().isOk());
            assertTrue(report.forSource("shodan").orElseThrow().isOk());
        }
    }

    @Test
    void totalityHoldsUnderMixedStatuses() throws Exception {
        ScriptedIntelSource ok = domainSource("virustotal");
        ok.willReturn(okResult("virustotal"));
        ScriptedIntelSource unsupported = ipOnlySource("shodan");
        ScriptedIntelSource notConfigured = domainSource("censys");
        notConfigured.willThrow(new MissingApiKeyException("censys", "apikey.censys"));
        ScriptedIntelSource failed = domainSource("abuseipdb");
        failed.willThrow(new IntelSourceException("abuseipdb", "boom", 500));

        try (ThreatIntelClient client =
                new ThreatIntelClient(List.of(ok, unsupported, notConfigured, failed))) {
            IntelReport report = client.enrich(DOMAIN_SUBJECT);

            assertEquals(4, report.outcomes().size());
        }
    }

    // ---------- Exception routing ----------

    @Test
    void missingApiKeyBecomesNotConfiguredAndIsExcludedFromFailures() throws Exception {
        ScriptedIntelSource missing = domainSource("censys");
        missing.willThrow(new MissingApiKeyException("censys", "apikey.censys"));
        ScriptedIntelSource ok = domainSource("virustotal");
        ok.willReturn(okResult("virustotal"));

        try (ThreatIntelClient client = new ThreatIntelClient(List.of(missing, ok))) {
            IntelReport report = client.enrich(DOMAIN_SUBJECT);

            IntelSourceOutcome outcome = report.forSource("censys").orElseThrow();
            assertEquals(IntelSourceStatus.NOT_CONFIGURED, outcome.status());
            assertTrue(report.failures().isEmpty());
            assertEquals(1, report.results().size());
            assertTrue(report.forSource("virustotal").orElseThrow().isOk());
        }
    }

    @Test
    void otherIntelSourceExceptionBecomesFailedWithStatusCode() throws Exception {
        ScriptedIntelSource failing = domainSource("virustotal");
        failing.willThrow(new IntelSourceException("virustotal", "rate limited", 429));
        ScriptedIntelSource ok = domainSource("shodan");
        ok.willReturn(okResult("shodan"));

        try (ThreatIntelClient client = new ThreatIntelClient(List.of(failing, ok))) {
            IntelReport report = client.enrich(DOMAIN_SUBJECT);

            IntelSourceOutcome outcome = report.forSource("virustotal").orElseThrow();
            assertEquals(IntelSourceStatus.FAILED, outcome.status());
            assertEquals(429, outcome.statusCode());
            assertTrue(report.forSource("shodan").orElseThrow().isOk());
        }
    }

    @Test
    void missingApiKeyExceptionNeverMisclassifiedAsFailed() throws Exception {
        ScriptedIntelSource missing = domainSource("censys");
        missing.willThrow(new MissingApiKeyException("censys", "apikey.censys"));

        try (ThreatIntelClient client = new ThreatIntelClient(List.of(missing))) {
            IntelReport report = client.enrich(DOMAIN_SUBJECT);

            assertEquals(IntelSourceStatus.NOT_CONFIGURED,
                    report.forSource("censys").orElseThrow().status());
        }
    }

    @Test
    void runtimeExceptionBecomesFailedAndOthersStillComplete() throws Exception {
        ScriptedIntelSource buggy = domainSource("virustotal");
        buggy.willThrow(new NullPointerException("parser bug"));
        ScriptedIntelSource ok = domainSource("shodan");
        ok.willReturn(okResult("shodan"));

        try (ThreatIntelClient client = new ThreatIntelClient(List.of(buggy, ok))) {
            IntelReport report = client.enrich(DOMAIN_SUBJECT);

            assertEquals(IntelSourceStatus.FAILED,
                    report.forSource("virustotal").orElseThrow().status());
            assertTrue(report.forSource("shodan").orElseThrow().isOk());
        }
    }

    @Test
    void nullReturningSourceBecomesFailedWithoutThrowing() throws Exception {
        ScriptedIntelSource nullReturning = domainSource("virustotal");
        nullReturning.willReturnNull();

        try (ThreatIntelClient client = new ThreatIntelClient(List.of(nullReturning))) {
            IntelReport report = client.enrich(DOMAIN_SUBJECT);

            assertEquals(IntelSourceStatus.FAILED,
                    report.forSource("virustotal").orElseThrow().status());
        }
    }

    @Test
    void errorPropagatesOutOfEnrichNeverSwallowed() throws Exception {
        ScriptedIntelSource erroring = domainSource("virustotal");
        erroring.willThrow(new AssertionError("simulated provider bug"));

        try (ThreatIntelClient client = new ThreatIntelClient(List.of(erroring))) {
            assertThrows(AssertionError.class, () -> client.enrich(DOMAIN_SUBJECT));
        }
    }

    // ---------- Cancellation ----------

    @Test
    void callerInterruptedWhileGatedSourceInsideQueryAbortsWithNoReport() throws Exception {
        ScriptedIntelSource gated = domainSource("censys");
        gated.willReturn(okResult("censys"));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1); // never counted down by the test
        gated.gate(entered, release);

        try (ThreatIntelClient client = new ThreatIntelClient(List.of(gated))) {
            AtomicReference<Throwable> thrown = new AtomicReference<>();
            AtomicReference<IntelReport> report = new AtomicReference<>();
            Thread caller = new Thread(() -> {
                try {
                    report.set(client.enrich(DOMAIN_SUBJECT));
                } catch (Throwable t) {
                    thrown.set(t);
                }
            });
            caller.start();

            assertTrue(entered.await(10, TimeUnit.SECONDS));
            caller.interrupt();
            caller.join(10_000);

            assertNull(report.get());
            assertNotNull(thrown.get());
            assertTrue(thrown.get() instanceof InterruptedException);

            while (!gated.sawInterruptForTest()) {
                Thread.onSpinWait();
            }
        }
    }

    @Test
    void sourceThrowingInterruptedExceptionAbortsFanOutUnwrapped() throws Exception {
        ScriptedIntelSource interrupting = domainSource("censys");
        interrupting.willThrow(new InterruptedException("cancelled"));

        try (ThreatIntelClient client = new ThreatIntelClient(List.of(interrupting))) {
            assertThrows(InterruptedException.class, () -> client.enrich(DOMAIN_SUBJECT));
        }
    }

    // ---------- Concurrency — the headline assertions ----------

    @Test
    void perSourceInFlightCapHoldsAcrossConcurrentEnrichCalls() throws Exception {
        ScriptedIntelSource censys = domainSource("censys");
        censys.willReturn(okResult("censys"));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        censys.gate(entered, release);

        // A second, differently-named source (never queried for a DOMAIN subject) so the pool
        // has 2 worker threads available — otherwise a 1-source pool would serialize the two
        // enrich() calls by itself and the permit cap would never be exercised.
        ScriptedIntelSource other = ipOnlySource("other");

        try (ThreatIntelClient client = new ThreatIntelClient(List.of(censys, other))) {
            AtomicReference<IntelReport> report1 = new AtomicReference<>();
            AtomicReference<IntelReport> report2 = new AtomicReference<>();

            Thread t1 = new Thread(() -> {
                try {
                    report1.set(client.enrich(DOMAIN_SUBJECT));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            t1.start();
            assertTrue(entered.await(10, TimeUnit.SECONDS));

            Thread t2 = new Thread(() -> {
                try {
                    report2.set(client.enrich(DOMAIN_SUBJECT));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            t2.start();

            while (client.waitingForTest("censys") != 1) {
                Thread.onSpinWait();
            }

            release.countDown();

            t1.join(10_000);
            t2.join(10_000);

            assertEquals(1, censys.maxConcurrentForTest());
            assertNotNull(report1.get());
            assertNotNull(report2.get());
            assertTrue(report1.get().forSource("censys").orElseThrow().isOk());
            assertTrue(report2.get().forSource("censys").orElseThrow().isOk());
        }
    }

    @Test
    void fanOutIsGenuinelyParallel() throws Exception {
        CountDownLatch bEntered = new CountDownLatch(1);

        ScriptedIntelSource a = domainSource("virustotal");
        a.willReturn(okResult("virustotal"));
        a.gate(null, bEntered); // a blocks until b enters query()

        ScriptedIntelSource b = domainSource("shodan");
        b.willReturn(okResult("shodan"));
        b.gate(bEntered, null); // b signals entry, never blocks itself

        try (ThreatIntelClient client = new ThreatIntelClient(List.of(a, b))) {
            IntelReport report = client.enrich(DOMAIN_SUBJECT);

            assertTrue(report.forSource("virustotal").orElseThrow().isOk());
            assertTrue(report.forSource("shodan").orElseThrow().isOk());
        }
    }

    @Test
    void differentSourcesAreNotSerializedByPermitCap() throws Exception {
        CountDownLatch bothEntered = new CountDownLatch(2);

        ScriptedIntelSource a = domainSource("virustotal");
        a.willReturn(okResult("virustotal"));
        CountDownLatch releaseA = new CountDownLatch(1);
        a.gate(bothEntered, releaseA);

        ScriptedIntelSource b = domainSource("shodan");
        b.willReturn(okResult("shodan"));
        CountDownLatch releaseB = new CountDownLatch(1);
        b.gate(bothEntered, releaseB);

        try (ThreatIntelClient client = new ThreatIntelClient(List.of(a, b))) {
            AtomicReference<IntelReport> reportRef = new AtomicReference<>();
            Thread t = new Thread(() -> {
                try {
                    reportRef.set(client.enrich(DOMAIN_SUBJECT));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            t.start();

            assertTrue(bothEntered.await(10, TimeUnit.SECONDS));

            releaseA.countDown();
            releaseB.countDown();
            t.join(10_000);

            assertNotNull(reportRef.get());
            assertTrue(reportRef.get().forSource("virustotal").orElseThrow().isOk());
            assertTrue(reportRef.get().forSource("shodan").orElseThrow().isOk());
        }
    }

    // ---------- Lifecycle ----------

    @Test
    void closeTerminatesPoolAndIsIdempotent() throws Exception {
        ScriptedIntelSource ok = domainSource("virustotal");
        ok.willReturn(okResult("virustotal"));
        ThreatIntelClient client = new ThreatIntelClient(List.of(ok));
        client.enrich(DOMAIN_SUBJECT);

        client.close();
        assertTrue(client.isPoolTerminatedForTest());

        assertDoesNotThrow(client::close);
    }

    @Test
    void enrichAfterCloseThrowsAndQueriesNoSource() throws Exception {
        ScriptedIntelSource ok = domainSource("virustotal");
        ok.willReturn(okResult("virustotal"));
        ThreatIntelClient client = new ThreatIntelClient(List.of(ok));
        client.close();

        assertThrows(IllegalStateException.class, () -> client.enrich(DOMAIN_SUBJECT));
        assertEquals(0, ok.callCount());
    }

    @Test
    void poolThreadsAreDaemonAndNamedArgusIntel() throws Exception {
        RecordingThreadFactory recording =
                new RecordingThreadFactory(ThreatIntelClient.defaultThreadFactory());
        ScriptedIntelSource ok = domainSource("virustotal");
        ok.willReturn(okResult("virustotal"));

        try (ThreatIntelClient client = new ThreatIntelClient(List.of(ok), recording)) {
            client.enrich(DOMAIN_SUBJECT);

            List<Thread> created = recording.createdThreads();
            assertFalse(created.isEmpty());
            for (Thread thread : created) {
                assertTrue(thread.isDaemon());
                assertTrue(thread.getName().matches("argus-intel-\\d+"));
            }
        }
    }

    @Test
    void closeFromAnotherThreadLetsInFlightEnrichComplete() throws Exception {
        ScriptedIntelSource gated = domainSource("virustotal");
        gated.willReturn(okResult("virustotal"));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        gated.gate(entered, release);

        ThreatIntelClient client = new ThreatIntelClient(List.of(gated));
        AtomicReference<IntelReport> reportRef = new AtomicReference<>();
        Thread caller = new Thread(() -> {
            try {
                reportRef.set(client.enrich(DOMAIN_SUBJECT));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        caller.start();

        assertTrue(entered.await(10, TimeUnit.SECONDS));

        Thread closer = new Thread(client::close);
        closer.start();

        release.countDown();

        caller.join(10_000);
        closer.join(10_000);

        assertNotNull(reportRef.get());
        assertTrue(reportRef.get().forSource("virustotal").orElseThrow().isOk());
        assertTrue(client.isPoolTerminatedForTest());
    }

    // ---------- Edge / robustness ----------

    @Test
    void mostlyEmptyResultIsNormalOkOutcome() throws Exception {
        ScriptedIntelSource empty = domainSource("virustotal");
        empty.willReturn(IntelResult.unknown("virustotal", DOMAIN_SUBJECT));

        try (ThreatIntelClient client = new ThreatIntelClient(List.of(empty))) {
            IntelReport report = client.enrich(DOMAIN_SUBJECT);

            IntelSourceOutcome outcome = report.forSource("virustotal").orElseThrow();
            assertTrue(outcome.isOk());
            assertEquals(1, report.results().size());
            assertTrue(report.allCveIds().isEmpty());
            assertTrue(report.failures().isEmpty());
        }
    }

    @Test
    void everySourceUnsupportedYieldsZeroResultsZeroFailuresNoException() throws Exception {
        ScriptedIntelSource ipOnlyA = ipOnlySource("shodan");
        ScriptedIntelSource ipOnlyB = ipOnlySource("abuseipdb");

        try (ThreatIntelClient client = new ThreatIntelClient(List.of(ipOnlyA, ipOnlyB))) {
            IntelReport report = client.enrich(DOMAIN_SUBJECT);

            assertTrue(report.results().isEmpty());
            assertTrue(report.failures().isEmpty());
            assertEquals(2, report.outcomes().size());
            assertTrue(report.outcomes().stream()
                    .allMatch(o -> o.status() == IntelSourceStatus.UNSUPPORTED_SUBJECT));
        }
    }

    @Test
    void allCveIdsThroughClientUnionsMixedCaseAcrossSources() throws Exception {
        ScriptedIntelSource shodan = domainSource("shodan");
        shodan.willReturn(resultWithCves("shodan", "cve-2021-44228"));
        ScriptedIntelSource censys = domainSource("censys");
        censys.willReturn(resultWithCves("censys", "CVE-2021-44228"));

        try (ThreatIntelClient client = new ThreatIntelClient(List.of(shodan, censys))) {
            IntelReport report = client.enrich(DOMAIN_SUBJECT);

            assertEquals(1, report.allCveIds().size());
            assertEquals(new CveId("CVE-2021-44228"), report.allCveIds().get(0));
        }
    }
}
