package com.argus.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link ScanRequest} is the immutable scan configuration: validated, defensively copied,
 * sorted/deduplicated port list, and default-applying factory (§3.4 of the plan).
 */
class ScanRequestTest {

    @Test
    void ofAppliesDefaults() {
        ScanRequest request = ScanRequest.of("127.0.0.1", List.of(80));
        assertEquals(ScanRequest.DEFAULT_THREAD_COUNT, request.threadCount());
        assertEquals(ScanRequest.DEFAULT_CONNECT_TIMEOUT, request.connectTimeout());
    }

    @Test
    void portsAreSortedDeduplicatedAndImmutable() {
        List<Integer> input = new ArrayList<>(List.of(443, 80, 80));
        ScanRequest request = ScanRequest.of("127.0.0.1", input);
        assertEquals(List.of(80, 443), request.ports());

        input.add(9999);
        assertEquals(List.of(80, 443), request.ports(),
                "mutating the caller's list after construction must not affect the request");

        assertThrows(UnsupportedOperationException.class, () -> request.ports().add(1));
    }

    @Test
    void withersReturnNewInstancesAndLeaveOriginalUntouched() {
        ScanRequest original = ScanRequest.of("127.0.0.1", List.of(80));

        ScanRequest withThreads = original.withThreadCount(4);
        assertEquals(4, withThreads.threadCount());
        assertEquals(ScanRequest.DEFAULT_THREAD_COUNT, original.threadCount());
        assertNotSame(original, withThreads);

        ScanRequest withTimeout = original.withConnectTimeout(Duration.ofMillis(500));
        assertEquals(Duration.ofMillis(500), withTimeout.connectTimeout());
        assertEquals(ScanRequest.DEFAULT_CONNECT_TIMEOUT, original.connectTimeout());
        assertNotSame(original, withTimeout);
    }

    @Test
    void rejectsBlankOrNullHost() {
        assertThrows(IllegalArgumentException.class,
                () -> ScanRequest.of(null, List.of(80)));
        assertThrows(IllegalArgumentException.class,
                () -> ScanRequest.of("  ", List.of(80)));
    }

    @Test
    void rejectsEmptyPortList() {
        assertThrows(IllegalArgumentException.class,
                () -> ScanRequest.of("127.0.0.1", List.of()));
    }

    @Test
    void rejectsOutOfRangePort() {
        assertThrows(IllegalArgumentException.class,
                () -> ScanRequest.of("127.0.0.1", List.of(0)));
        assertThrows(IllegalArgumentException.class,
                () -> ScanRequest.of("127.0.0.1", List.of(65536)));
    }

    @Test
    void rejectsThreadCountBelowOne() {
        assertThrows(IllegalArgumentException.class,
                () -> ScanRequest.of("127.0.0.1", List.of(80)).withThreadCount(0));
        assertThrows(IllegalArgumentException.class,
                () -> ScanRequest.of("127.0.0.1", List.of(80)).withThreadCount(-1));
    }

    @Test
    void rejectsZeroNegativeOrExcessiveConnectTimeout() {
        ScanRequest base = ScanRequest.of("127.0.0.1", List.of(80));
        assertThrows(IllegalArgumentException.class,
                () -> base.withConnectTimeout(Duration.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> base.withConnectTimeout(Duration.ofMillis(-1)));
        assertThrows(IllegalArgumentException.class,
                () -> base.withConnectTimeout(Duration.ofSeconds(61)));
    }

    @Test
    void connectTimeoutMillisIsPositiveInt() {
        ScanRequest request = ScanRequest.of("127.0.0.1", List.of(80))
                .withConnectTimeout(Duration.ofMillis(1500));
        assertEquals(1500, request.connectTimeoutMillis());
        assertTrue(request.connectTimeoutMillis() > 0);
    }
}
