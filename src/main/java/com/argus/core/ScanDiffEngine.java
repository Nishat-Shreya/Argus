package com.argus.core;

import com.argus.db.FindingRecord;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Compares the findings of two scans. A pure function and nothing else: no fields, no I/O, no
 * SQL, no clock, no logging. The class is uninstantiable.
 *
 * THREAD SAFETY: stateless, therefore trivially safe for concurrent use. Invariant 5 does not
 * apply — there is no shared mutable state to guard.
 *
 * INVARIANT 3: this method itself blocks on nothing and MAY be called from the FX Application
 * Thread. The two {@code FindingDao.findByScan} calls that produce its arguments MUST NOT be
 * (see {@code ScanRepository}'s own BLOCKING note) — call them on a background {@code Task}, then
 * call {@code diff()}, then {@code Platform.runLater(render)}.
 */
public final class ScanDiffEngine {

    private ScanDiffEngine() {}

    /**
     * @param baseline findings of the earlier scan ("what it looked like before")
     * @param current  findings of the later scan  ("what it looks like now")
     * @throws NullPointerException     if either list, or any element, is null
     * @throws IllegalArgumentException if either list contains two records with the same
     *                                  {@link FindingKey}
     */
    public static ScanDiff diff(List<FindingRecord> baseline, List<FindingRecord> current) {
        Objects.requireNonNull(baseline, "baseline must not be null");
        Objects.requireNonNull(current, "current must not be null");

        Map<FindingKey, FindingRecord> baselineByKey = indexByKey(baseline, "baseline");
        Map<FindingKey, FindingRecord> currentByKey = indexByKey(current, "current");

        List<FindingRecord> added = new ArrayList<>();
        List<FindingChange> changed = new ArrayList<>();
        for (FindingRecord currentRecord : current) {
            FindingKey key = FindingKey.of(currentRecord);
            FindingRecord baselineRecord = baselineByKey.get(key);
            if (baselineRecord == null) {
                added.add(currentRecord);
            } else if (!Objects.equals(baselineRecord.state(), currentRecord.state())) {
                changed.add(new FindingChange(baselineRecord, currentRecord));
            }
        }

        List<FindingRecord> removed = new ArrayList<>();
        for (FindingRecord baselineRecord : baseline) {
            FindingKey key = FindingKey.of(baselineRecord);
            if (!currentByKey.containsKey(key)) {
                removed.add(baselineRecord);
            }
        }

        return new ScanDiff(added, removed, changed);
    }

    private static Map<FindingKey, FindingRecord> indexByKey(
            List<FindingRecord> records, String label) {
        Objects.requireNonNull(records, label + " must not be null");
        Map<FindingKey, FindingRecord> byKey = new LinkedHashMap<>();
        for (FindingRecord record : records) {
            Objects.requireNonNull(record, label + " must not contain null elements");
            FindingKey key = FindingKey.of(record);
            FindingRecord previous = byKey.putIfAbsent(key, record);
            if (previous != null) {
                throw new IllegalArgumentException(
                        "duplicate finding identity within " + label + ": " + key);
            }
        }
        return byKey;
    }
}
