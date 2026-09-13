package com.argus.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link KevMatchResult} — matches + the score rule (plan §3.3/§6.3). No JSON, no HTTP, no
 * catalog: hand-built {@link KevEntry}s only. M12 pins the second half of §0 item 4 — no
 * {@code catalogVersion} may ever appear on a result.
 */
class KevMatchResultTest {

    private static KevEntry plain(String cve) {
        return new KevEntry(new CveId(cve), "Vendor", "Product", "Name", false);
    }

    private static KevEntry ransomware(String cve) {
        return new KevEntry(new CveId(cve), "Vendor", "Product", "Name", true);
    }

    private static final String[] PLAIN_CVES = {
        "CVE-2021-44228", "CVE-2021-34527", "CVE-2022-30190", "CVE-2020-1472",
        "CVE-2019-19781", "CVE-2018-13379", "CVE-2017-0144"
    };

    private static final String[] RANSOMWARE_CVES = {
        "CVE-2021-26855", "CVE-2021-27065", "CVE-2020-0688"
    };

    private static List<KevEntry> plainMatches(int n) {
        List<KevEntry> entries = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            entries.add(plain(PLAIN_CVES[i]));
        }
        return entries;
    }

    private static List<KevEntry> ransomwareMatches(int n) {
        List<KevEntry> entries = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            entries.add(ransomware(RANSOMWARE_CVES[i]));
        }
        return entries;
    }

    // ---- M1 ----

    @Test
    void noneIsScoreZeroNoMatches() {
        KevMatchResult none = KevMatchResult.none();

        assertEquals(0, none.score());
        assertFalse(none.hasMatches());
        assertEquals(0, none.matchCount());
        assertEquals(List.of(), none.matches());
        assertEquals(List.of(), none.matchedCveIds());
        assertEquals(List.of(), none.ransomwareMatches());
    }

    // ---- M2 ----

    @Test
    void plainMatchesScoreTwentyEach() {
        assertEquals(20, new KevMatchResult(plainMatches(1)).score());
        assertEquals(40, new KevMatchResult(plainMatches(2)).score());
        assertEquals(60, new KevMatchResult(plainMatches(3)).score());
        assertEquals(80, new KevMatchResult(plainMatches(4)).score());
    }

    // ---- M3 ----

    @Test
    void ransomwareMatchesScoreFiftyEach() {
        assertEquals(50, new KevMatchResult(ransomwareMatches(1)).score());
        assertEquals(100, new KevMatchResult(ransomwareMatches(2)).score());
    }

    // ---- M4 ----

    @Test
    void onePlainPlusOneRansomwareScoresSeventy() {
        List<KevEntry> entries = new ArrayList<>();
        entries.add(plain(PLAIN_CVES[0]));
        entries.add(ransomware(RANSOMWARE_CVES[0]));

        assertEquals(70, new KevMatchResult(entries).score());
    }

    // ---- M5: saturation ----

    @Test
    void scoreSaturatesAtMaxAndNeverExceedsOrGoesNegative() {
        assertEquals(100, new KevMatchResult(plainMatches(5)).score());
        assertEquals(100, new KevMatchResult(plainMatches(6)).score());
        assertEquals(100, new KevMatchResult(plainMatches(7)).score());
        assertEquals(100, new KevMatchResult(ransomwareMatches(3)).score());
    }

    // ---- M6: score ordering chain ----

    @Test
    void scoreOrderingChain() {
        int none = KevMatchResult.none().score();
        int onePlain = new KevMatchResult(plainMatches(1)).score();
        int twoPlain = new KevMatchResult(plainMatches(2)).score();
        int oneRansomware = new KevMatchResult(ransomwareMatches(1)).score();
        List<KevEntry> onePlainOneRansomware = new ArrayList<>();
        onePlainOneRansomware.add(plain(PLAIN_CVES[0]));
        onePlainOneRansomware.add(ransomware(RANSOMWARE_CVES[0]));
        int onePlainOneRansomwareScore = new KevMatchResult(onePlainOneRansomware).score();

        assertTrue(none < onePlain);
        assertTrue(onePlain < twoPlain);
        assertTrue(twoPlain < oneRansomware);
        assertTrue(oneRansomware < onePlainOneRansomwareScore);

        assertEquals(0, none);
        assertEquals(20, onePlain);
        assertEquals(40, twoPlain);
        assertEquals(50, oneRansomware);
        assertEquals(70, onePlainOneRansomwareScore);
    }

    // ---- M7 ----

    @Test
    void oneRansomwareMatchOutscoresTwoPlainMatchesEvenThoughMatchCountIsLower() {
        KevMatchResult oneRansomware = new KevMatchResult(ransomwareMatches(1));
        KevMatchResult twoPlain = new KevMatchResult(plainMatches(2));

        assertTrue(oneRansomware.score() > twoPlain.score());
        assertEquals(1, oneRansomware.matchCount());
        assertEquals(2, twoPlain.matchCount());
    }

    // ---- M8: monotonicity ----

    @Test
    void addingOneMoreMatchNeverDecreasesTheScorePlain() {
        int previous = KevMatchResult.none().score();
        for (int n = 1; n <= 6; n++) {
            int current = new KevMatchResult(plainMatches(n)).score();
            assertTrue(current >= previous, "n=" + n);
            previous = current;
        }
    }

    @Test
    void addingOneMoreMatchNeverDecreasesTheScoreRansomware() {
        int previous = KevMatchResult.none().score();
        for (int n = 1; n <= 3; n++) {
            int current = new KevMatchResult(ransomwareMatches(n)).score();
            assertTrue(current >= previous, "n=" + n);
            previous = current;
        }
    }

    // ---- M9 ----

    @Test
    void duplicateCveIdInMatchesThrowsIllegalArgument() {
        List<KevEntry> entries = new ArrayList<>();
        entries.add(plain(PLAIN_CVES[0]));
        entries.add(plain(PLAIN_CVES[0]));

        assertThrows(IllegalArgumentException.class, () -> new KevMatchResult(entries));
    }

    // ---- M10 ----

    @Test
    void matchesIsSortedByCveIdAndDefensivelyCopied() {
        List<KevEntry> entries = new ArrayList<>();
        entries.add(plain("CVE-2022-30190"));
        entries.add(plain("CVE-2020-1472"));
        entries.add(plain("CVE-2021-44228"));

        KevMatchResult result = new KevMatchResult(entries);
        assertEquals(
                List.of(new CveId("CVE-2020-1472"), new CveId("CVE-2021-44228"),
                        new CveId("CVE-2022-30190")),
                result.matchedCveIds());

        entries.clear();
        assertEquals(3, result.matchCount());
    }

    @Test
    void nullListThrowsNpe() {
        assertThrows(NullPointerException.class, () -> new KevMatchResult(null));
    }

    @Test
    void nullElementThrowsNpe() {
        List<KevEntry> entries = new ArrayList<>();
        entries.add(plain(PLAIN_CVES[0]));
        entries.add(null);
        assertThrows(NullPointerException.class, () -> new KevMatchResult(entries));
    }

    // ---- M11 ----

    @Test
    void matchedCveIdsAndRansomwareMatchesAreConsistentWithMatches() {
        List<KevEntry> entries = new ArrayList<>();
        entries.add(plain(PLAIN_CVES[0]));
        entries.add(plain(PLAIN_CVES[1]));
        entries.add(ransomware(RANSOMWARE_CVES[0]));

        KevMatchResult result = new KevMatchResult(entries);

        assertEquals(3, result.matchCount());
        assertEquals(1, result.ransomwareCount());
        int plainCount = result.matchCount() - result.ransomwareCount();
        assertEquals(2, plainCount);
        assertEquals(result.ransomwareCount() + plainCount, result.matchCount());
        assertEquals(1, result.ransomwareMatches().size());
    }

    // ---- M12: no-clock pin ----

    @Test
    void reflectiveNoClockPinExactlyOneComponentNoCatalogVersion() {
        RecordComponent[] components = KevMatchResult.class.getRecordComponents();
        assertEquals(1, components.length,
                "KevMatchResult must have exactly 1 record component");
        assertFalse(components[0].getType().getPackageName().startsWith("java.time"));

        for (RecordComponent component : components) {
            assertFalse(component.getName().toLowerCase().contains("catalogversion"),
                    "KevMatchResult must not have a catalogVersion component");
        }
        for (var method : KevMatchResult.class.getDeclaredMethods()) {
            assertFalse(method.getName().toLowerCase().contains("catalogversion"),
                    "KevMatchResult must not have a catalogVersion accessor: " + method);
        }
    }
}
