package com.argus.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * {@link KevScorer} — the item's headline class; the acceptance criteria live here (plan
 * §3.4/§6.6). Pure function object: injected catalog, no I/O.
 */
class KevScorerTest {

    private static final CveId LOG4SHELL = new CveId("CVE-2021-44228");
    private static final CveId ZEROLOGON = new CveId("CVE-2020-1472");
    private static final CveId FOLLINA = new CveId("CVE-2022-30190");
    private static final CveId NOT_LISTED_1 = new CveId("CVE-2015-0001");
    private static final CveId NOT_LISTED_2 = new CveId("CVE-2015-0002");
    private static final CveId NOT_LISTED_3 = new CveId("CVE-2015-0003");

    private static KevEntry entry(CveId id, boolean ransomware) {
        return new KevEntry(id, "Vendor", "Product", "Name", ransomware);
    }

    private static KevCatalog smallCatalog() {
        return new KevCatalog("2026.09.11", List.of(
                entry(LOG4SHELL, true),
                entry(ZEROLOGON, true),
                entry(FOLLINA, false)));
    }

    // ---- S1 ----

    @Test
    void matchByCve() {
        KevScorer scorer = new KevScorer(smallCatalog());

        KevMatchResult result = scorer.score(List.of(LOG4SHELL));

        assertEquals(1, result.matchCount());
        assertEquals(LOG4SHELL, result.matches().get(0).cveId());
        assertEquals(50, result.score());
    }

    // ---- S2 ----

    @Test
    void matchByCveCanonicalisation() {
        KevScorer scorer = new KevScorer(smallCatalog());
        CveId lowercaseQuery = new CveId("cve-2021-44228");

        KevMatchResult result = scorer.score(List.of(lowercaseQuery));

        assertEquals(1, result.matchCount());
        assertEquals(LOG4SHELL, result.matches().get(0).cveId());
    }

    // ---- S3 ----

    @Test
    void noMatchCaseIsNormal() {
        KevScorer scorer = new KevScorer(smallCatalog());

        KevMatchResult result = scorer.score(List.of(NOT_LISTED_1, NOT_LISTED_2, NOT_LISTED_3));

        assertFalse(result.hasMatches());
        assertEquals(0, result.matchCount());
        assertEquals(0, result.score());
        assertEquals(List.of(), result.matches());
    }

    // ---- S4 ----

    @Test
    void emptyInputIsNormal() {
        KevScorer scorer = new KevScorer(smallCatalog());

        KevMatchResult result = scorer.score(List.of());

        assertFalse(result.hasMatches());
        assertEquals(0, result.score());
    }

    // ---- S5 ----

    @Test
    void emptyCatalogWithNonEmptyCveList() {
        KevScorer scorer = new KevScorer(KevCatalog.empty());

        KevMatchResult result = scorer.score(List.of(LOG4SHELL, ZEROLOGON));

        assertFalse(result.hasMatches());
        assertEquals(0, result.score());
    }

    // ---- S6 ----

    @Test
    void partialMatch() {
        KevScorer scorer = new KevScorer(smallCatalog());

        KevMatchResult result = scorer.score(
                List.of(LOG4SHELL, NOT_LISTED_1, FOLLINA, NOT_LISTED_2, NOT_LISTED_3));

        assertEquals(2, result.matchCount());
        assertEquals(List.of(LOG4SHELL, FOLLINA), sortedIds(result));
    }

    private static List<CveId> sortedIds(KevMatchResult result) {
        List<CveId> ids = new ArrayList<>(result.matchedCveIds());
        ids.sort((a, b) -> a.id().compareTo(b.id()));
        return ids;
    }

    // ---- S7 ----

    @Test
    void duplicateInputCvesCollapse() {
        KevScorer scorer = new KevScorer(smallCatalog());

        KevMatchResult result = scorer.score(List.of(LOG4SHELL, LOG4SHELL, FOLLINA));

        assertEquals(2, result.matchCount());
        assertEquals(50 + 20, result.score());
    }

    // ---- S8 ----

    @Test
    void scoreNullThrowsNpe() {
        KevScorer scorer = new KevScorer(smallCatalog());
        assertThrows(NullPointerException.class, () -> scorer.score(null));
    }

    @Test
    void collectionContainingNullThrowsNpe() {
        KevScorer scorer = new KevScorer(smallCatalog());
        List<CveId> withNull = new ArrayList<>();
        withNull.add(LOG4SHELL);
        withNull.add(null);
        assertThrows(NullPointerException.class, () -> scorer.score(withNull));
    }

    // ---- S9 ----

    @Test
    void scoreWorksIdenticallyForASet() {
        KevScorer scorer = new KevScorer(smallCatalog());
        Set<CveId> set = new HashSet<>(List.of(LOG4SHELL, FOLLINA));

        KevMatchResult result = scorer.score(set);

        assertEquals(2, result.matchCount());
    }

    // ---- S10 ----

    @Test
    void isKnownExploitedAgreesWithScore() {
        KevScorer scorer = new KevScorer(smallCatalog());

        assertTrue(scorer.isKnownExploited(LOG4SHELL));
        assertFalse(scorer.isKnownExploited(NOT_LISTED_1));

        assertTrue(scorer.score(List.of(LOG4SHELL)).hasMatches());
        assertFalse(scorer.score(List.of(NOT_LISTED_1)).hasMatches());
    }

    // ---- S11 ----

    @Test
    void scoreOrderingEndToEnd() {
        KevScorer scorer = new KevScorer(smallCatalog());

        int low = scorer.score(List.of(NOT_LISTED_1)).score();
        int medium = scorer.score(List.of(FOLLINA)).score();
        int high = scorer.score(List.of(FOLLINA, LOG4SHELL)).score();

        assertTrue(low < medium);
        assertTrue(medium < high);
    }

    // ---- S12: integration with the real P2-06 output shape ----

    @Test
    void integrationWithIntelReportAllCveIds() {
        IntelSubject subject = IntelSubject.domain("example.com");
        IntelResult shodanResult = new IntelResult(
                "shodan", subject, IntelVerdict.SUSPICIOUS, 20,
                List.of(LOG4SHELL, NOT_LISTED_1), Map.of());
        IntelResult censysResult = new IntelResult(
                "censys", subject, IntelVerdict.SUSPICIOUS, 30,
                List.of(FOLLINA), Map.of());
        IntelReport report = new IntelReport(subject, List.of(
                IntelSourceOutcome.ok("shodan", shodanResult),
                IntelSourceOutcome.ok("censys", censysResult)));

        KevScorer scorer = new KevScorer(smallCatalog());
        KevMatchResult result = scorer.score(report.allCveIds());

        assertEquals(2, result.matchCount());
        assertEquals(List.of(LOG4SHELL, FOLLINA), sortedIds(result));
        // reads only — does not modify the report
        assertEquals(2, report.outcomes().size());
    }

    // ---- S13: thread-safety smoke ----

    @Test
    @Timeout(30)
    void concurrentScoringFromSeveralThreadsReturnsEqualResults() throws Exception {
        KevScorer scorer = new KevScorer(smallCatalog());
        int threadCount = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        List<AtomicReference<KevMatchResult>> results = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            results.add(new AtomicReference<>());
        }
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);

        try {
            for (int i = 0; i < threadCount; i++) {
                AtomicReference<KevMatchResult> slot = results.get(i);
                pool.submit(() -> {
                    try {
                        start.await();
                        slot.set(scorer.score(List.of(LOG4SHELL, FOLLINA, NOT_LISTED_1)));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(20, TimeUnit.SECONDS));
        } finally {
            pool.shutdown();
            if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                pool.shutdownNow();
            }
        }

        KevMatchResult expected = scorer.score(List.of(LOG4SHELL, FOLLINA, NOT_LISTED_1));
        for (AtomicReference<KevMatchResult> slot : results) {
            assertEquals(expected, slot.get());
        }
    }

    // ---- S14 ----

    @Test
    void nullCatalogThrowsNpe() {
        assertThrows(NullPointerException.class, () -> new KevScorer(null));
    }

    // ---- X1: no-clock source scan (plan §6.7). This is what makes §0 an enforced rule rather
    // than a promise: it fails a build the moment any of this item's seven production files
    // imports java.time or calls a system clock, whether or not any other test would notice. ----

    private static final String[] KEV_PRODUCTION_FILES = {
        "KevEntry.java", "KevCatalog.java", "KevMatchResult.java", "KevScorer.java",
        "KevCatalogLoader.java", "KevCatalogException.java", "KevCatalogParser.java"
    };

    private static final String[] FORBIDDEN_CLOCK_LITERALS = {
        "java.time", "Instant.now", "LocalDate.now", "System.currentTimeMillis", "System.nanoTime"
    };

    @Test
    void noClockLiteralAnywhereInTheSevenNewProductionSourceFiles() {
        Path core = Path.of("src/main/java/com/argus/core");
        for (String fileName : KEV_PRODUCTION_FILES) {
            Path file = core.resolve(fileName);
            assertTrue(Files.isRegularFile(file), "expected production file: " + file);
            String contents;
            try {
                contents = Files.readString(file);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            for (String literal : FORBIDDEN_CLOCK_LITERALS) {
                assertFalse(contents.contains(literal),
                        file + " contains the forbidden clock literal: " + literal);
            }
        }
    }
}
