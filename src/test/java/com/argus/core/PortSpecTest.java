package com.argus.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link PortSpec} turns user-facing port specifications into a validated, sorted,
 * duplicate-free port list. No JavaFX, no network (invariant 2, P0-03 §4(c)).
 */
class PortSpecTest {

    @Test
    void parsesSingleListOfPorts() {
        assertEquals(List.of(22, 80, 443), PortSpec.parse("22,80,443"));
    }

    @Test
    void parsesRange() {
        assertEquals(List.of(8000, 8001, 8002, 8003), PortSpec.parse("8000-8003"));
    }

    @Test
    void parsesMixedListAndRangeSortedAndDeduplicated() {
        assertEquals(List.of(79, 80, 81, 443), PortSpec.parse("443, 80, 80, 79-81"));
    }

    @Test
    void toleratesSurroundingWhitespace() {
        assertEquals(List.of(80, 443), PortSpec.parse(" 80 , 443 "));
    }

    @Test
    void returnedListIsUnmodifiable() {
        List<Integer> parsed = PortSpec.parse("22,80,443");
        assertThrows(UnsupportedOperationException.class, () -> parsed.add(1));
    }

    @Test
    void rejectsNullBlankAndEmptyElements() {
        assertThrows(IllegalArgumentException.class, () -> PortSpec.parse(null));
        assertThrows(IllegalArgumentException.class, () -> PortSpec.parse(""));
        assertThrows(IllegalArgumentException.class, () -> PortSpec.parse("  "));
        assertThrows(IllegalArgumentException.class, () -> PortSpec.parse("80,,443"));
    }

    @Test
    void rejectsNonNumericAndOutOfRange() {
        assertThrows(IllegalArgumentException.class, () -> PortSpec.parse("http"));
        assertThrows(IllegalArgumentException.class, () -> PortSpec.parse("0"));
        assertThrows(IllegalArgumentException.class, () -> PortSpec.parse("65536"));
        assertThrows(IllegalArgumentException.class, () -> PortSpec.parse("-1"));
    }

    @Test
    void rejectsMalformedRanges() {
        assertThrows(IllegalArgumentException.class, () -> PortSpec.parse("443-80"));
        assertThrows(IllegalArgumentException.class, () -> PortSpec.parse("80-"));
        assertThrows(IllegalArgumentException.class, () -> PortSpec.parse("-80"));
        assertThrows(IllegalArgumentException.class, () -> PortSpec.parse("1-2-3"));
    }

    @Test
    void rangeRejectsReversedOrOutOfRangeBounds() {
        assertThrows(IllegalArgumentException.class, () -> PortSpec.range(443, 80));
        assertThrows(IllegalArgumentException.class, () -> PortSpec.range(0, 10));
        assertThrows(IllegalArgumentException.class, () -> PortSpec.range(1, 65536));
    }
}
