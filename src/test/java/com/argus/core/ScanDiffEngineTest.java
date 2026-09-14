package com.argus.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.argus.db.FindingRecord;
import com.argus.db.FindingType;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * {@link ScanDiffEngine} — the main TDD test class (plan §6.3, §6.4). Covers the happy path, the
 * four named acceptance criteria, ordering guarantees, and the malformed-input / symmetry edge
 * cases.
 */
class ScanDiffEngineTest {

    // ==================== 6.3 happy path & the four named criteria ====================

    // ---- 6.3.1 ----

    @Test
    void identicalScansProduceAnEmptyDiff() {
        // Different ids and scanIds on both sides — an implementation that leaned on
        // FindingRecord.equals would fail this test (plan §7.2).
        List<FindingRecord> baseline = List.of(
                portRow(1L, 10L, "host", 80, "OPEN"),
                subdomainRow(2L, 10L, "a.example.com"));
        List<FindingRecord> current = List.of(
                portRow(100L, 20L, "host", 80, "OPEN"),
                subdomainRow(101L, 20L, "a.example.com"));

        ScanDiff diff = ScanDiffEngine.diff(baseline, current);

        assertTrue(diff.isEmpty());
    }

    // ---- 6.3.2 ----

    @Test
    void bothScansEmptyProducesAnEmptyDiff() {
        ScanDiff diff = ScanDiffEngine.diff(List.of(), List.of());
        assertTrue(diff.isEmpty());
    }

    // ---- 6.3.3 ----

    @Test
    void aFindingOnlyInCurrentIsAnAddition() {
        FindingRecord onlyInCurrent = portRow(1L, 20L, "host", 80, "OPEN");
        ScanDiff diff = ScanDiffEngine.diff(List.of(), List.of(onlyInCurrent));

        assertEquals(List.of(onlyInCurrent), diff.added());
        assertTrue(diff.removed().isEmpty());
        assertTrue(diff.changed().isEmpty());
    }

    // ---- 6.3.4 ----

    @Test
    void everythingIsAnAdditionWhenBaselineIsEmpty() {
        FindingRecord a = portRow(1L, 20L, "host", 80, "OPEN");
        FindingRecord b = portRow(2L, 20L, "host", 443, "OPEN");

        ScanDiff diff = ScanDiffEngine.diff(List.of(), List.of(a, b));

        assertEquals(List.of(a, b), diff.added());
        assertTrue(diff.removed().isEmpty());
        assertTrue(diff.changed().isEmpty());
    }

    // ---- 6.3.5 ----

    @Test
    void aFindingOnlyInBaselineIsARemoval() {
        FindingRecord onlyInBaseline = portRow(1L, 10L, "host", 80, "OPEN");
        ScanDiff diff = ScanDiffEngine.diff(List.of(onlyInBaseline), List.of());

        assertEquals(List.of(onlyInBaseline), diff.removed());
        assertTrue(diff.added().isEmpty());
        assertTrue(diff.changed().isEmpty());
    }

    // ---- 6.3.6 ----

    @Test
    void everythingIsARemovalWhenCurrentIsEmpty() {
        FindingRecord a = portRow(1L, 10L, "host", 80, "OPEN");
        FindingRecord b = portRow(2L, 10L, "host", 443, "OPEN");

        ScanDiff diff = ScanDiffEngine.diff(List.of(a, b), List.of());

        assertEquals(List.of(a, b), diff.removed());
        assertTrue(diff.added().isEmpty());
        assertTrue(diff.changed().isEmpty());
    }

    // ---- 6.3.7: the P1-04 payoff assertion ----

    @Test
    void samePortWithADifferentStateIsOneChangeNotAnAdditionPlusARemoval() {
        FindingRecord baselineRow = portRow(1L, 10L, "host", 80, "OPEN");
        FindingRecord currentRow = portRow(2L, 20L, "host", 80, "FILTERED");

        ScanDiff diff = ScanDiffEngine.diff(List.of(baselineRow), List.of(currentRow));

        assertEquals(1, diff.changed().size());
        FindingChange change = diff.changed().get(0);
        assertEquals("OPEN", change.baseline().state());
        assertEquals("FILTERED", change.current().state());
        assertTrue(diff.added().isEmpty());
        assertTrue(diff.removed().isEmpty());
    }

    // ---- 6.3.8 ----

    @Test
    void aSubdomainPresentInBothScansIsNeverChanged() {
        FindingRecord baselineRow = subdomainRow(1L, 10L, "a.example.com");
        FindingRecord currentRow = subdomainRow(2L, 20L, "a.example.com");

        ScanDiff diff = ScanDiffEngine.diff(List.of(baselineRow), List.of(currentRow));

        assertTrue(diff.isEmpty());
    }

    // ---- 6.3.9: mixed scenario, also stands in for 6.4.7 (no FindingType switch needed) ----

    @Test
    void additionsRemovalsAndChangesAreReportedTogether() {
        FindingRecord untouchedPort = portRow(1L, 10L, "host", 22, "OPEN");
        FindingRecord removedPort = portRow(2L, 10L, "host", 8080, "OPEN");
        FindingRecord changedBaseline = portRow(3L, 10L, "host", 80, "OPEN");
        FindingRecord untouchedSubdomain = subdomainRow(4L, 10L, "a.example.com");

        FindingRecord untouchedPortCurrent = portRow(101L, 20L, "host", 22, "OPEN");
        FindingRecord changedCurrent = portRow(103L, 20L, "host", 80, "FILTERED");
        FindingRecord untouchedSubdomainCurrent = subdomainRow(104L, 20L, "a.example.com");
        FindingRecord addedSubdomain = subdomainRow(105L, 20L, "b.example.com");

        List<FindingRecord> baseline =
                List.of(untouchedPort, removedPort, changedBaseline, untouchedSubdomain);
        List<FindingRecord> current = List.of(
                untouchedPortCurrent, changedCurrent, untouchedSubdomainCurrent, addedSubdomain);

        ScanDiff diff = ScanDiffEngine.diff(baseline, current);

        assertEquals(List.of(addedSubdomain), diff.added());
        assertEquals(List.of(removedPort), diff.removed());
        assertEquals(1, diff.changed().size());
        FindingChange change = diff.changed().get(0);
        assertEquals(changedBaseline, change.baseline());
        assertEquals(changedCurrent, change.current());
    }

    // ---- 6.3.10 ----

    @Test
    void addedFollowsCurrentOrderAndRemovedFollowsBaselineOrder() {
        FindingRecord removedFirst = portRow(1L, 10L, "host", 21, "OPEN");
        FindingRecord removedSecond = portRow(2L, 10L, "host", 22, "OPEN");

        FindingRecord addedFirst = portRow(101L, 20L, "host", 443, "OPEN");
        FindingRecord addedSecond = portRow(102L, 20L, "host", 8080, "OPEN");

        // Baseline order: removedFirst, removedSecond. Current order: addedSecond, addedFirst
        // (reversed) to prove the engine doesn't happen to preserve baseline order by accident.
        ScanDiff diff = ScanDiffEngine.diff(
                List.of(removedFirst, removedSecond), List.of(addedSecond, addedFirst));

        assertEquals(List.of(addedSecond, addedFirst), diff.added());
        assertEquals(List.of(removedFirst, removedSecond), diff.removed());
    }

    // ---- 6.3.11 ----

    @Test
    void changedFollowsCurrentOrder() {
        FindingRecord baselineA = portRow(1L, 10L, "host", 21, "OPEN");
        FindingRecord baselineB = portRow(2L, 10L, "host", 22, "OPEN");

        FindingRecord currentA = portRow(101L, 20L, "host", 21, "FILTERED");
        FindingRecord currentB = portRow(102L, 20L, "host", 22, "FILTERED");

        // Current order is B then A — reversed relative to baseline.
        ScanDiff diff = ScanDiffEngine.diff(
                List.of(baselineA, baselineB), List.of(currentB, currentA));

        assertEquals(2, diff.changed().size());
        assertEquals(FindingKey.of(baselineB), diff.changed().get(0).key());
        assertEquals(FindingKey.of(baselineA), diff.changed().get(1).key());
    }

    // ---- 6.3.12 ----

    @Test
    void theReturnedListsAreUnmodifiable() {
        FindingRecord added = portRow(1L, 20L, "host", 80, "OPEN");
        ScanDiff diff = ScanDiffEngine.diff(List.of(), List.of(added));

        assertThrows(UnsupportedOperationException.class,
                () -> diff.added().add(added));
        assertThrows(UnsupportedOperationException.class,
                () -> diff.removed().add(added));
        assertThrows(UnsupportedOperationException.class,
                () -> diff.changed().add(
                        new FindingChange(portRow(2L, 10L, "host", 81, "OPEN"),
                                portRow(3L, 20L, "host", 81, "FILTERED"))));
    }

    // ==================== 6.4 edge cases, malformed input, symmetry ====================

    // ---- 6.4.1: the explicit anti-equals test ----

    @Test
    void differingRowIdsAndScanIdsDoNotMakeAFindingLookChanged() {
        FindingRecord baselineRow = portRow(1L, 10L, "host", 80, "OPEN");
        FindingRecord currentRow = portRow(999L, 888L, "host", 80, "OPEN");

        ScanDiff diff = ScanDiffEngine.diff(List.of(baselineRow), List.of(currentRow));

        assertTrue(diff.isEmpty());
    }

    // ---- 6.4.2 ----

    @Test
    void antisymmetry() {
        FindingRecord onlyInA = portRow(1L, 10L, "host", 21, "OPEN");
        FindingRecord shared = portRow(2L, 10L, "host", 22, "OPEN");
        FindingRecord onlyInB = portRow(101L, 20L, "host", 443, "OPEN");
        FindingRecord sharedInB = portRow(102L, 20L, "host", 22, "OPEN");

        List<FindingRecord> a = List.of(onlyInA, shared);
        List<FindingRecord> b = List.of(onlyInB, sharedInB);

        ScanDiff diffAtoB = ScanDiffEngine.diff(a, b);
        ScanDiff diffBtoA = ScanDiffEngine.diff(b, a);

        assertEquals(diffAtoB.added(), diffBtoA.removed());
        assertEquals(diffAtoB.removed(), diffBtoA.added());
    }

    // ---- 6.4.3 ----

    @Test
    void changedIsSymmetricWithSidesSwapped() {
        FindingRecord aRow = portRow(1L, 10L, "host", 80, "OPEN");
        FindingRecord bRow = portRow(101L, 20L, "host", 80, "FILTERED");

        ScanDiff diffAtoB = ScanDiffEngine.diff(List.of(aRow), List.of(bRow));
        ScanDiff diffBtoA = ScanDiffEngine.diff(List.of(bRow), List.of(aRow));

        Map<FindingKey, FindingChange> forward = diffAtoB.changed().stream()
                .collect(Collectors.toMap(FindingChange::key, c -> c));
        Map<FindingKey, FindingChange> backward = diffBtoA.changed().stream()
                .collect(Collectors.toMap(FindingChange::key, c -> c));

        assertEquals(forward.keySet(), backward.keySet());
        for (FindingKey key : forward.keySet()) {
            assertEquals(forward.get(key).baseline(), backward.get(key).current());
            assertEquals(forward.get(key).current(), backward.get(key).baseline());
        }
    }

    // ---- 6.4.4 ----

    @Test
    void duplicateKeyInBaselineIsRejected() {
        FindingRecord first = portRow(1L, 10L, "host", 80, "OPEN");
        FindingRecord duplicate = portRow(2L, 10L, "host", 80, "FILTERED");

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> ScanDiffEngine.diff(List.of(first, duplicate), List.of()));
        assertTrue(exception.getMessage().contains(FindingKey.of(first).toString()));
    }

    @Test
    void duplicateKeyInCurrentIsRejected() {
        FindingRecord first = portRow(1L, 20L, "host", 80, "OPEN");
        FindingRecord duplicate = portRow(2L, 20L, "host", 80, "FILTERED");

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> ScanDiffEngine.diff(List.of(), List.of(first, duplicate)));
        assertTrue(exception.getMessage().contains(FindingKey.of(first).toString()));
    }

    // ---- 6.4.5 ----

    @Test
    void nullArgumentsAreRejected() {
        assertThrows(NullPointerException.class, () -> ScanDiffEngine.diff(null, List.of()));
        assertThrows(NullPointerException.class, () -> ScanDiffEngine.diff(List.of(), null));
    }

    @Test
    void nullElementsAreRejected() {
        List<FindingRecord> withNull = new java.util.ArrayList<>();
        withNull.add(portRow(1L, 10L, "host", 80, "OPEN"));
        withNull.add(null);

        assertThrows(NullPointerException.class, () -> ScanDiffEngine.diff(withNull, List.of()));
        assertThrows(NullPointerException.class, () -> ScanDiffEngine.diff(List.of(), withNull));
    }

    // ---- 6.4.6 ----

    @Test
    void aStateAppearingWhereThereWasNoneIsAChange() {
        // Unreachable through the schema's CHECKs today; proves Objects.equals is used rather
        // than String.equals on a possibly-null receiver (plan §6.4.6).
        FindingRecord baselineRow = new FindingRecord(1L, 10L, FindingType.PORT, "host", 80, null);
        FindingRecord currentRow =
                new FindingRecord(2L, 20L, FindingType.PORT, "host", 80, "OPEN");

        ScanDiff diff = ScanDiffEngine.diff(List.of(baselineRow), List.of(currentRow));

        assertEquals(1, diff.changed().size());
        FindingChange change = diff.changed().get(0);
        assertEquals(null, change.baseline().state());
        assertEquals("OPEN", change.current().state());
    }

    // ---- fixtures ----

    private static FindingRecord portRow(long id, long scanId, String host, int port,
            String state) {
        return new FindingRecord(id, scanId, FindingType.PORT, host, port, state);
    }

    private static FindingRecord subdomainRow(long id, long scanId, String name) {
        return new FindingRecord(id, scanId, FindingType.SUBDOMAIN, name, null, null);
    }
}
