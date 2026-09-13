package com.argus.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Tests P1-P8 of the P2-06 plan (§7.3) — IntelReport constructed directly, no client. */
class IntelReportTest {

    private static final IntelSubject A_DOMAIN = IntelSubject.domain("example.com");

    private static IntelResult resultWithCves(String sourceName, String... cveIds) {
        return new IntelResult(sourceName, A_DOMAIN, IntelVerdict.MALICIOUS, 90,
                List.of(java.util.Arrays.stream(cveIds).map(CveId::new).toArray(CveId[]::new)),
                java.util.Map.of());
    }

    @Test
    void totalityExposesExactlyNOutcomesInGivenOrder() {
        List<IntelSourceOutcome> outcomes = List.of(
                IntelSourceOutcome.ok("virustotal", IntelResult.unknown("virustotal", A_DOMAIN)),
                IntelSourceOutcome.unsupported("shodan"),
                IntelSourceOutcome.notConfigured("censys",
                        new MissingApiKeyException("censys", "apikey.censys")));
        IntelReport report = new IntelReport(A_DOMAIN, outcomes);

        assertEquals(3, report.outcomes().size());
        assertEquals("virustotal", report.outcomes().get(0).sourceName());
        assertEquals("shodan", report.outcomes().get(1).sourceName());
        assertEquals("censys", report.outcomes().get(2).sourceName());
    }

    @Test
    void resultsReturnsOnlyOkResultsInOrder() {
        IntelResult vtResult = IntelResult.unknown("virustotal", A_DOMAIN);
        List<IntelSourceOutcome> outcomes = List.of(
                IntelSourceOutcome.ok("virustotal", vtResult),
                IntelSourceOutcome.unsupported("shodan"));
        IntelReport report = new IntelReport(A_DOMAIN, outcomes);

        assertEquals(List.of(vtResult), report.results());
    }

    @Test
    void resultsIsEmptyNotNullWhenNoOkOutcomes() {
        IntelReport report = new IntelReport(A_DOMAIN, List.of(IntelSourceOutcome.unsupported("shodan")));

        assertTrue(report.results().isEmpty());
    }

    @Test
    void failuresContainsOnlyFailedNotNotConfigured() {
        IntelSourceException failure = new IntelSourceException("virustotal", "boom", 500);
        List<IntelSourceOutcome> outcomes = List.of(
                IntelSourceOutcome.failed("virustotal", failure),
                IntelSourceOutcome.notConfigured("censys",
                        new MissingApiKeyException("censys", "apikey.censys")));
        IntelReport report = new IntelReport(A_DOMAIN, outcomes);

        assertEquals(1, report.failures().size());
        assertEquals("virustotal", report.failures().get(0).sourceName());
    }

    @Test
    void allCveIdsDeduplicatesOnCanonicalCveId() {
        List<IntelSourceOutcome> outcomes = List.of(
                IntelSourceOutcome.ok("shodan", resultWithCves("shodan", "cve-2021-44228")),
                IntelSourceOutcome.ok("censys", resultWithCves("censys", "CVE-2021-44228")));
        IntelReport report = new IntelReport(A_DOMAIN, outcomes);

        assertEquals(1, report.allCveIds().size());
        assertEquals(new CveId("CVE-2021-44228"), report.allCveIds().get(0));
    }

    @Test
    void allCveIdsIsSortedAndIgnoresNonOkOutcomes() {
        List<IntelSourceOutcome> outcomes = List.of(
                IntelSourceOutcome.ok("shodan", resultWithCves("shodan", "CVE-2022-1234")),
                IntelSourceOutcome.ok("censys", resultWithCves("censys", "CVE-2021-44228")),
                IntelSourceOutcome.unsupported("abuseipdb"));
        IntelReport report = new IntelReport(A_DOMAIN, outcomes);

        assertEquals(
                List.of(new CveId("CVE-2021-44228"), new CveId("CVE-2022-1234")),
                report.allCveIds());
    }

    @Test
    void forSourceFindsBySourceNameOrReturnsEmpty() {
        IntelReport report = new IntelReport(A_DOMAIN, List.of(IntelSourceOutcome.unsupported("shodan")));

        Optional<IntelSourceOutcome> present = report.forSource("shodan");
        assertTrue(present.isPresent());
        assertEquals(Optional.empty(), report.forSource("nope"));
    }

    @Test
    void duplicateSourceNameThrows() {
        List<IntelSourceOutcome> outcomes = List.of(
                IntelSourceOutcome.unsupported("shodan"),
                IntelSourceOutcome.unsupported("shodan"));

        assertThrows(IllegalArgumentException.class, () -> new IntelReport(A_DOMAIN, outcomes));
    }

    @Test
    void returnedListsAreUnmodifiableAndDefensivelyCopied() {
        List<IntelSourceOutcome> mutable = new ArrayList<>();
        mutable.add(IntelSourceOutcome.unsupported("shodan"));
        IntelReport report = new IntelReport(A_DOMAIN, mutable);

        mutable.add(IntelSourceOutcome.unsupported("censys"));
        assertEquals(1, report.outcomes().size());

        assertThrows(UnsupportedOperationException.class,
                () -> report.outcomes().add(IntelSourceOutcome.unsupported("censys")));
        assertThrows(UnsupportedOperationException.class,
                () -> report.results().add(null));
        assertThrows(UnsupportedOperationException.class,
                () -> report.failures().add(null));
        assertThrows(UnsupportedOperationException.class,
                () -> report.allCveIds().add(new CveId("CVE-2021-44228")));
    }
}
