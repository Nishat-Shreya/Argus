package com.argus.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Section 6.2: {@code ScanPlan} — the validated request the coordinator executes. */
class ScanPlanTest {

    @Test
    void ofUsesTheDefaultPorts() {
        ScanPlan plan = ScanPlan.of("example.com");
        assertEquals(ScanPlan.DEFAULT_PORTS, plan.ports());
    }

    @Test
    void defaultPortsAreNonEmptySortedAndDistinct() {
        List<Integer> ports = ScanPlan.DEFAULT_PORTS;
        assertTrue(!ports.isEmpty());
        for (int port : ports) {
            assertTrue(port >= 1 && port <= 65535, "port out of range: " + port);
        }
        List<Integer> sorted = new ArrayList<>(ports);
        sorted.sort(null);
        assertEquals(sorted, ports);
        assertEquals(ports.size(), ports.stream().distinct().count());
    }

    @Test
    void portsAreImmutable() {
        ScanPlan plan = ScanPlan.of("example.com");
        assertThrows(UnsupportedOperationException.class, () -> plan.ports().add(1));

        List<Integer> mutable = new ArrayList<>(List.of(80, 443));
        ScanPlan custom = new ScanPlan("example.com", mutable);
        mutable.add(9999);
        assertEquals(List.of(80, 443), custom.ports());
    }

    @Test
    void rejectsBlankOrMalformedTarget() {
        assertThrows(IllegalArgumentException.class, () -> new ScanPlan("", List.of(80)));
        assertThrows(IllegalArgumentException.class, () -> new ScanPlan(null, List.of(80)));
        assertThrows(IllegalArgumentException.class, () -> new ScanPlan("localhost", List.of(80)));
    }

    @Test
    void rejectsEmptyPortList() {
        assertThrows(IllegalArgumentException.class, () -> new ScanPlan("example.com", List.of()));
        assertThrows(IllegalArgumentException.class, () -> new ScanPlan("example.com", null));
    }
}
