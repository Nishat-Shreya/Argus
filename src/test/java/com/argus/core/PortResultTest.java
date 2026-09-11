package com.argus.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * {@link PortResult} is a structural value type: host, port and {@link PortState} only. No
 * banner, no timestamp, no latency (§3.2 of the plan) — {@code ScanDiffEngine} (P2-08) depends
 * on set equality staying stable across rescans.
 */
class PortResultTest {

    @Test
    void isOpenReflectsState() {
        assertTrue(new PortResult("127.0.0.1", 80, PortState.OPEN).isOpen());
        assertFalse(new PortResult("127.0.0.1", 80, PortState.CLOSED).isOpen());
        assertFalse(new PortResult("127.0.0.1", 80, PortState.FILTERED).isOpen());
    }

    @Test
    void equalityIsStructural() {
        PortResult a = new PortResult("127.0.0.1", 80, PortState.OPEN);
        PortResult b = new PortResult("127.0.0.1", 80, PortState.OPEN);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        Set<PortResult> results = new HashSet<>();
        results.add(a);
        results.add(b);
        assertEquals(1, results.size());
    }

    @Test
    void rejectsInvalidHostPortOrState() {
        assertThrows(IllegalArgumentException.class,
                () -> new PortResult(" ", 80, PortState.OPEN));
        assertThrows(IllegalArgumentException.class,
                () -> new PortResult("127.0.0.1", 0, PortState.OPEN));
        assertThrows(IllegalArgumentException.class,
                () -> new PortResult("127.0.0.1", 65536, PortState.OPEN));
        assertThrows(NullPointerException.class,
                () -> new PortResult("127.0.0.1", 80, null));
    }
}
