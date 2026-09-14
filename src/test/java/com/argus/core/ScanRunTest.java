package com.argus.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** T2's driver: {@link ScanRun} construction validation and shape. */
class ScanRunTest {

    private static final Instant STARTED = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant FINISHED = Instant.parse("2026-01-01T00:05:00Z");

    @Test
    void blankTargetIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new ScanRun("", STARTED, FINISHED, ScanCompletion.COMPLETED, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new ScanRun("   ", STARTED, FINISHED, ScanCompletion.COMPLETED, List.of()));
    }

    @Test
    void nullTargetIsRejected() {
        assertThrows(NullPointerException.class,
                () -> new ScanRun(null, STARTED, FINISHED, ScanCompletion.COMPLETED, List.of()));
    }

    @Test
    void nullInstantsAreRejected() {
        assertThrows(NullPointerException.class,
                () -> new ScanRun("example.com", null, FINISHED, ScanCompletion.COMPLETED, List.of()));
        assertThrows(NullPointerException.class,
                () -> new ScanRun("example.com", STARTED, null, ScanCompletion.COMPLETED, List.of()));
    }

    @Test
    void nullFindingsListIsRejected() {
        assertThrows(NullPointerException.class,
                () -> new ScanRun("example.com", STARTED, FINISHED, ScanCompletion.COMPLETED, null));
    }

    @Test
    void nullFindingsElementIsRejected() {
        List<Object> findings = new ArrayList<>();
        findings.add(new Subdomain("a.example.com"));
        findings.add(null);
        assertThrows(NullPointerException.class,
                () -> new ScanRun("example.com", STARTED, FINISHED, ScanCompletion.COMPLETED, findings));
    }

    @Test
    void nullCompletionIsRejected() {
        assertThrows(NullPointerException.class,
                () -> new ScanRun("example.com", STARTED, FINISHED, null, List.of()));
    }

    @Test
    void finishedBeforeStartedIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new ScanRun("example.com", FINISHED, STARTED, ScanCompletion.COMPLETED, List.of()));
    }

    @Test
    void findingsAreDefensivelyCopied() {
        List<Object> mutable = new ArrayList<>();
        mutable.add(new Subdomain("a.example.com"));
        ScanRun run = new ScanRun("example.com", STARTED, FINISHED, ScanCompletion.COMPLETED, mutable);

        mutable.add(new Subdomain("b.example.com"));

        assertEquals(1, run.findings().size());
    }

    @Test
    void hasExactlyFiveComponentsAndNoPerFindingTime() {
        RecordComponent[] components = ScanRun.class.getRecordComponents();
        assertEquals(5, components.length, "ScanRun must have exactly 5 record components");

        String[] expectedNames = {"target", "startedAt", "finishedAt", "completion", "findings"};
        assertEquals(Arrays.asList(expectedNames),
                Arrays.stream(components).map(RecordComponent::getName).toList());

        long timeComponents = Arrays.stream(components)
                .filter(c -> c.getType() == Instant.class)
                .count();
        assertEquals(2, timeComponents, "only startedAt/finishedAt may carry a java.time type");
    }
}
