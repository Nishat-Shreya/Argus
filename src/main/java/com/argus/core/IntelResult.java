package com.argus.core;

import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/**
 * The normalized answer from one IntelSource about one IntelSubject. Immutable value type;
 * every collection component is an unmodifiable copy, so an instance is safely publishable
 * across scan threads with no synchronization (invariant 5, §4).
 *
 * NO TIMESTAMP — see plan §7.3. NO raw-JSON component — see plan §7.4.
 */
public record IntelResult(
        String sourceName,
        IntelSubject subject,
        IntelVerdict verdict,
        int score,
        List<CveId> cveIds,
        Map<String, String> attributes
) {
    public static final int MAX_SCORE = 100;
    public static final int MAX_ATTRIBUTES = 16;
    public static final int MAX_ATTRIBUTE_VALUE_LENGTH = 256;

    public IntelResult {
        if (sourceName == null || sourceName.isBlank()) {
            throw new IllegalArgumentException("sourceName must not be null or blank");
        }
        Objects.requireNonNull(subject, "subject must not be null");
        Objects.requireNonNull(verdict, "verdict must not be null");
        if (verdict == IntelVerdict.UNKNOWN && score != 0) {
            throw new IllegalArgumentException(
                    "UNKNOWN verdict must have score 0, was: " + score);
        }
        if (score < 0 || score > MAX_SCORE) {
            throw new IllegalArgumentException(
                    "score out of range [0," + MAX_SCORE + "]: " + score);
        }

        Objects.requireNonNull(cveIds, "cveIds must not be null");
        TreeSet<CveId> sortedDistinct = new TreeSet<>(Comparator.comparing(CveId::id));
        sortedDistinct.addAll(cveIds);
        cveIds = List.copyOf(sortedDistinct);

        Objects.requireNonNull(attributes, "attributes must not be null");
        if (attributes.size() > MAX_ATTRIBUTES) {
            throw new IllegalArgumentException(
                    "too many attributes (max " + MAX_ATTRIBUTES + "): " + attributes.size());
        }
        Map<String, String> copy = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : attributes.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("attribute key must not be null or blank");
            }
            Objects.requireNonNull(value, "attribute value must not be null: " + key);
            if (value.length() > MAX_ATTRIBUTE_VALUE_LENGTH) {
                throw new IllegalArgumentException(
                        "attribute value exceeds " + MAX_ATTRIBUTE_VALUE_LENGTH
                                + " characters: " + key);
            }
            copy.put(key, value);
        }
        attributes = Collections.unmodifiableMap(copy);
    }

    /** "This provider has no data on this subject." verdict=UNKNOWN, score=0, no CVEs, no attrs. */
    public static IntelResult unknown(String sourceName, IntelSubject subject) {
        return new IntelResult(sourceName, subject, IntelVerdict.UNKNOWN, 0, List.of(), Map.of());
    }

    /** True if verdict is SUSPICIOUS or MALICIOUS. The UI badge predicate. */
    public boolean isFlagged() {
        return verdict == IntelVerdict.SUSPICIOUS || verdict == IntelVerdict.MALICIOUS;
    }
}
