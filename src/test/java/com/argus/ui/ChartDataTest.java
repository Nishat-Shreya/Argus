package com.argus.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.argus.core.FindingSnapshot;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Section 7.1 of the plan: the toolkit-free chart-data-preparation battery. Imports only
 * {@code java.*} and {@code com.argus.core.FindingSnapshot} -- runs with zero JavaFX on the
 * classpath.
 */
class ChartDataTest {

    // 21 probes: 3 open (distinct ports), 2 filtered (distinct ports), 16 closed (one port,
    // many subjects) -- ports {21,22,80,143,443,8080}, chosen so portCategories proves numeric
    // (not lexicographic) ordering.
    private static List<FindingSnapshot> probeFixture() {
        List<FindingSnapshot> findings = new ArrayList<>();
        long id = 1;
        findings.add(port(id++, "host", 22, "OPEN"));
        findings.add(port(id++, "host", 443, "OPEN"));
        findings.add(port(id++, "host", 8080, "OPEN"));
        findings.add(port(id++, "host", 21, "FILTERED"));
        findings.add(port(id++, "host", 143, "FILTERED"));
        for (int i = 0; i < 16; i++) {
            findings.add(port(id++, "host" + i, 80, "CLOSED"));
        }
        return List.copyOf(findings);
    }

    private static List<FindingSnapshot> probeAndSubdomainFixture() {
        List<FindingSnapshot> findings = new ArrayList<>(probeFixture());
        long id = 1000;
        for (int i = 0; i < 57; i++) {
            findings.add(subdomain(id++, "sub" + i + ".example.com"));
        }
        return List.copyOf(findings);
    }

    private static FindingSnapshot port(long id, String subject, Integer port, String state) {
        return new FindingSnapshot(id, "PORT", subject, port, state);
    }

    private static FindingSnapshot subdomain(long id, String subject) {
        return new FindingSnapshot(id, "SUBDOMAIN", subject, null, null);
    }

    // ---- happy path ----

    @Test
    void portStateSlicesGroupsAlphabeticallyByStateToken() {
        List<ChartSlice> slices = ChartData.portStateSlices(probeFixture());

        assertEquals(List.of(new ChartSlice("CLOSED", 16), new ChartSlice("FILTERED", 2),
                new ChartSlice("OPEN", 3)), slices);
    }

    @Test
    void chartSliceLabelIsLowercaseTokenAndCountInParens() {
        assertEquals("open (3)", new ChartSlice("OPEN", 3).label());
    }

    @Test
    void portBarsGroupsByPortAndStatePortsAscending() {
        List<ChartBar> bars = ChartData.portBars(probeFixture());

        assertEquals(List.of(
                new ChartBar(21, "FILTERED", 1),
                new ChartBar(22, "OPEN", 1),
                new ChartBar(80, "CLOSED", 16),
                new ChartBar(143, "FILTERED", 1),
                new ChartBar(443, "OPEN", 1),
                new ChartBar(8080, "OPEN", 1)), bars);
    }

    @Test
    void portCategoriesAreNumericallyOrderedNotLexicographically() {
        List<ChartBar> bars = ChartData.portBars(probeFixture());

        assertEquals(List.of("21", "22", "80", "143", "443", "8080"),
                ChartData.portCategories(bars));
    }

    @Test
    void stateSeriesIsDistinctAndAlphabetical() {
        List<ChartBar> bars = ChartData.portBars(probeFixture());

        assertEquals(List.of("CLOSED", "FILTERED", "OPEN"), ChartData.stateSeries(bars));
    }

    @Test
    void barAxisUpperBoundIsTheLargestPerPortStackedTotal() {
        List<FindingSnapshot> twoHostsOpenOn443 = List.of(
                port(1, "host1", 443, "OPEN"),
                port(2, "host2", 443, "OPEN"));
        List<ChartBar> bars = ChartData.portBars(twoHostsOpenOn443);

        assertEquals(List.of(new ChartBar(443, "OPEN", 2)), bars);
        assertEquals(2, ChartData.barAxisUpperBound(bars));
    }

    @Test
    void summaryLineContainsProbeTotalStateCountsAndNonProbeTypeGroupsInOrder() {
        String line = ChartData.summaryLine(probeAndSubdomainFixture());

        assertEquals("21 port probes · closed 16 · filtered 2 · open 3 "
                + "· subdomain 57", line);
    }

    @Test
    void hasProbeFindingsIsTrueWithProbesFalseWithout() {
        assertTrue(ChartData.hasProbeFindings(probeFixture()));
        assertFalse(ChartData.hasProbeFindings(List.of(subdomain(1, "a.example.com"))));
    }

    @Test
    void styleClassForLowercasesUsingLocaleRoot() {
        assertEquals("chart-state-filtered", ChartData.styleClassFor("FILTERED"));
    }

    // ---- malformed / edge input ----

    @Test
    void emptyFindingsProduceEmptySlicesAndBarsAndAFloorOfOneOnTheAxis() {
        assertEquals(List.of(), ChartData.portStateSlices(List.of()));
        assertEquals(List.of(), ChartData.portBars(List.of()));
        assertEquals(1, ChartData.barAxisUpperBound(List.of()));
    }

    @Test
    void subdomainOnlyScanHasNoSlicesNoBarsAndAReportingSummaryLine() {
        List<FindingSnapshot> findings = List.of(
                subdomain(1, "a.example.com"), subdomain(2, "b.example.com"));

        assertEquals(List.of(), ChartData.portStateSlices(findings));
        assertEquals(List.of(), ChartData.portBars(findings));
        assertFalse(ChartData.hasProbeFindings(findings));
        assertEquals("0 port probes · subdomain 2", ChartData.summaryLine(findings));
    }

    @Test
    void aNonNullStateWithANullPortIsCountedInThePieButSkippedByPortBars() {
        List<FindingSnapshot> findings = List.of(port(1, "host", null, "OPEN"));

        assertEquals(List.of(new ChartSlice("OPEN", 1)), ChartData.portStateSlices(findings));
        assertEquals(List.of(), ChartData.portBars(findings));
    }

    @Test
    void anUnrecognisedStateTokenFlowsThroughUnmodified() {
        List<FindingSnapshot> findings = List.of(port(1, "host", 9999, "THROTTLED"));

        assertEquals(List.of(new ChartSlice("THROTTLED", 1)), ChartData.portStateSlices(findings));
        List<ChartBar> bars = ChartData.portBars(findings);
        assertEquals(List.of(new ChartBar(9999, "THROTTLED", 1)), bars);
        assertEquals(List.of("THROTTLED"), ChartData.stateSeries(bars));
        assertEquals("chart-state-throttled", bars.get(0).styleClass());
    }

    @Test
    void nullArgumentToEveryStaticMethodThrowsNpe() {
        assertThrows(NullPointerException.class, () -> ChartData.portStateSlices(null));
        assertThrows(NullPointerException.class, () -> ChartData.portBars(null));
        assertThrows(NullPointerException.class, () -> ChartData.portCategories(null));
        assertThrows(NullPointerException.class, () -> ChartData.stateSeries(null));
        assertThrows(NullPointerException.class, () -> ChartData.barAxisUpperBound(null));
        assertThrows(NullPointerException.class, () -> ChartData.summaryLine(null));
        assertThrows(NullPointerException.class, () -> ChartData.hasProbeFindings(null));
        assertThrows(NullPointerException.class, () -> ChartData.styleClassFor(null));
    }

    @Test
    void chartSliceRejectsMalformedInput() {
        assertThrows(NullPointerException.class, () -> new ChartSlice(null, 1));
        assertThrows(IllegalArgumentException.class, () -> new ChartSlice("", 1));
        assertThrows(IllegalArgumentException.class, () -> new ChartSlice("open", 0));
    }

    @Test
    void chartBarRejectsMalformedInput() {
        assertThrows(NullPointerException.class, () -> new ChartBar(80, null, 1));
        assertThrows(IllegalArgumentException.class, () -> new ChartBar(80, "", 1));
        assertThrows(IllegalArgumentException.class, () -> new ChartBar(80, "open", 0));
        assertThrows(IllegalArgumentException.class, () -> new ChartBar(0, "open", 1));
        assertThrows(IllegalArgumentException.class, () -> new ChartBar(65536, "open", 1));
    }

    @Test
    void returnedListsAreUnmodifiable() {
        List<ChartSlice> slices = ChartData.portStateSlices(probeFixture());
        List<ChartBar> bars = ChartData.portBars(probeFixture());
        List<String> categories = ChartData.portCategories(bars);
        List<String> series = ChartData.stateSeries(bars);

        assertThrows(UnsupportedOperationException.class,
                () -> slices.add(new ChartSlice("OPEN", 1)));
        assertThrows(UnsupportedOperationException.class,
                () -> bars.add(new ChartBar(1, "OPEN", 1)));
        assertThrows(UnsupportedOperationException.class, () -> categories.add("1"));
        assertThrows(UnsupportedOperationException.class, () -> series.add("OPEN"));
    }

    @Test
    void sameInputShuffledProducesTheSameOrdering() {
        List<FindingSnapshot> original = probeFixture();
        List<FindingSnapshot> shuffled = new ArrayList<>(original);
        Collections.shuffle(shuffled, new Random(42));

        assertEquals(ChartData.portStateSlices(original), ChartData.portStateSlices(shuffled));
        List<ChartBar> originalBars = ChartData.portBars(original);
        List<ChartBar> shuffledBars = ChartData.portBars(shuffled);
        assertEquals(originalBars, shuffledBars);
        assertEquals(ChartData.portCategories(originalBars), ChartData.portCategories(shuffledBars));
        assertEquals(ChartData.stateSeries(originalBars), ChartData.stateSeries(shuffledBars));
    }
}
