package com.argus.core;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.RecordComponent;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** {@link IntelResult} — the normalized per-source enrichment answer (plan §3.5/§6.6). */
class IntelResultTest {

    private static final IntelSubject SUBJECT = IntelSubject.ip("1.2.3.4");

    @Test
    void unknownFactoryProducesTheNoDataShape() {
        IntelResult result = IntelResult.unknown("fake", SUBJECT);

        assertEquals(IntelVerdict.UNKNOWN, result.verdict());
        assertEquals(0, result.score());
        assertTrue(result.cveIds().isEmpty());
        assertTrue(result.attributes().isEmpty());
        assertFalse(result.isFlagged());
    }

    @Test
    void unknownIsNotHarmless() {
        IntelResult unknown = IntelResult.unknown("fake", SUBJECT);
        IntelResult harmless = new IntelResult(
                "fake", SUBJECT, IntelVerdict.HARMLESS, 0, List.of(), Map.of());
        assertNotEquals(unknown, harmless);
    }

    @Test
    void rejectsUnknownVerdictWithNonZeroScore() {
        assertThrows(IllegalArgumentException.class, () -> new IntelResult(
                "fake", SUBJECT, IntelVerdict.UNKNOWN, 50, List.of(), Map.of()));
    }

    @Test
    void rejectsScoreOutOfRange() {
        assertThrows(IllegalArgumentException.class, () -> new IntelResult(
                "fake", SUBJECT, IntelVerdict.HARMLESS, -1, List.of(), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new IntelResult(
                "fake", SUBJECT, IntelVerdict.MALICIOUS, 101, List.of(), Map.of()));
        assertDoesNotThrow(() -> new IntelResult(
                "fake", SUBJECT, IntelVerdict.HARMLESS, 0, List.of(), Map.of()));
        assertDoesNotThrow(() -> new IntelResult(
                "fake", SUBJECT, IntelVerdict.MALICIOUS, 100, List.of(), Map.of()));
    }

    @Test
    void rejectsBlankSourceName() {
        assertThrows(IllegalArgumentException.class, () -> new IntelResult(
                null, SUBJECT, IntelVerdict.HARMLESS, 0, List.of(), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new IntelResult(
                "", SUBJECT, IntelVerdict.HARMLESS, 0, List.of(), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new IntelResult(
                "  ", SUBJECT, IntelVerdict.HARMLESS, 0, List.of(), Map.of()));
    }

    @Test
    void cveIdsAreDedupedAndSorted() {
        IntelResult result = new IntelResult("fake", SUBJECT, IntelVerdict.MALICIOUS, 90,
                List.of(new CveId("CVE-2021-44228"), new CveId("CVE-2019-0708"),
                        new CveId("CVE-2021-44228")),
                Map.of());

        assertEquals(List.of(new CveId("CVE-2019-0708"), new CveId("CVE-2021-44228")),
                result.cveIds());
    }

    @Test
    void equalsIgnoresInputOrderOfCvesAndAttributes() {
        Map<String, String> attrsA = new LinkedHashMap<>();
        attrsA.put("ports", "22, 80");
        attrsA.put("isp", "Example ISP");

        Map<String, String> attrsB = new LinkedHashMap<>();
        attrsB.put("isp", "Example ISP");
        attrsB.put("ports", "22, 80");

        IntelResult a = new IntelResult("fake", SUBJECT, IntelVerdict.SUSPICIOUS, 60,
                List.of(new CveId("CVE-2021-44228"), new CveId("CVE-2019-0708")), attrsA);
        IntelResult b = new IntelResult("fake", SUBJECT, IntelVerdict.SUSPICIOUS, 60,
                List.of(new CveId("CVE-2019-0708"), new CveId("CVE-2021-44228")), attrsB);

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void collectionsAreDefensivelyCopiedAndUnmodifiable() {
        List<CveId> callerList = new java.util.ArrayList<>();
        callerList.add(new CveId("CVE-2021-44228"));
        Map<String, String> callerMap = new LinkedHashMap<>();
        callerMap.put("ports", "22");

        IntelResult result = new IntelResult(
                "fake", SUBJECT, IntelVerdict.MALICIOUS, 90, callerList, callerMap);

        callerList.add(new CveId("CVE-2019-0708"));
        callerMap.put("extra", "value");
        assertEquals(1, result.cveIds().size());
        assertEquals(1, result.attributes().size());

        assertThrows(UnsupportedOperationException.class,
                () -> result.cveIds().add(new CveId("CVE-2019-0708")));
        assertThrows(UnsupportedOperationException.class,
                () -> result.attributes().put("new", "value"));
    }

    @Test
    void rejectsTooManyAttributes() {
        Map<String, String> tooMany = new LinkedHashMap<>();
        for (int i = 0; i <= IntelResult.MAX_ATTRIBUTES; i++) {
            tooMany.put("attr-" + i, "value");
        }
        assertThrows(IllegalArgumentException.class, () -> new IntelResult(
                "fake", SUBJECT, IntelVerdict.HARMLESS, 0, List.of(), tooMany));
    }

    @Test
    void rejectsOverlongAttributeValueAndBlankKey() {
        String overlong = "x".repeat(IntelResult.MAX_ATTRIBUTE_VALUE_LENGTH + 1);
        assertThrows(IllegalArgumentException.class, () -> new IntelResult(
                "fake", SUBJECT, IntelVerdict.HARMLESS, 0, List.of(), Map.of("key", overlong)));
        assertThrows(IllegalArgumentException.class, () -> new IntelResult(
                "fake", SUBJECT, IntelVerdict.HARMLESS, 0, List.of(), Map.of(" ", "value")));
    }

    @Test
    void isFlaggedCoversSuspiciousAndMalicious() {
        assertTrue(new IntelResult(
                "fake", SUBJECT, IntelVerdict.SUSPICIOUS, 50, List.of(), Map.of()).isFlagged());
        assertTrue(new IntelResult(
                "fake", SUBJECT, IntelVerdict.MALICIOUS, 90, List.of(), Map.of()).isFlagged());
        assertFalse(new IntelResult(
                "fake", SUBJECT, IntelVerdict.HARMLESS, 0, List.of(), Map.of()).isFlagged());
        assertFalse(IntelResult.unknown("fake", SUBJECT).isFlagged());
    }

    @Test
    void hasNoTimestampComponent() {
        RecordComponent[] components = IntelResult.class.getRecordComponents();
        assertEquals(6, components.length);
        for (RecordComponent component : components) {
            assertFalse(component.getType().getPackageName().startsWith("java.time"),
                    "unexpected java.time component: " + component.getName());
        }
    }

    @Test
    void hasNoRawPayloadComponent() {
        RecordComponent[] components = IntelResult.class.getRecordComponents();
        for (RecordComponent component : components) {
            String name = component.getName().toLowerCase(java.util.Locale.ROOT);
            assertFalse(name.equals("raw") || name.equals("rawjson") || name.equals("body"),
                    "unexpected raw-payload component: " + component.getName());
            assertFalse(component.getType().equals(byte[].class),
                    "unexpected byte[] component: " + component.getName());
        }
    }

}
