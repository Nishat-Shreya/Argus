package com.argus.ui;

import com.argus.core.PortResult;
import com.argus.core.Subdomain;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Maps the heterogeneous findings of a {@code ScanPipeline<Object>} onto {@link FindingRow}.
 * Pure, toolkit-free. This is the {@code ui} mirror of P1-04's {@code type} discriminator, and
 * it is NOT shared with {@code com.argus.db}'s row types (§7.5 of the plan).
 */
final class FindingRows {

    private FindingRows() {
    }

    /**
     * @throws NullPointerException     on null
     * @throws IllegalArgumentException on an unknown type
     */
    static FindingRow of(Object finding) {
        Objects.requireNonNull(finding, "finding");
        if (finding instanceof PortResult portResult) {
            return new FindingRow("port", portResult.host(), String.valueOf(portResult.port()),
                    portResult.state().name().toLowerCase(Locale.ROOT));
        }
        if (finding instanceof Subdomain subdomain) {
            return new FindingRow("subdomain", subdomain.name(), "", "");
        }
        throw new IllegalArgumentException(
                "Unknown finding type: " + finding.getClass().getSimpleName());
    }

    /** Preserves iteration order; returns an unmodifiable list. */
    static List<FindingRow> of(Collection<?> findings) {
        Objects.requireNonNull(findings, "findings");
        List<FindingRow> rows = new ArrayList<>(findings.size());
        for (Object finding : findings) {
            rows.add(of(finding));
        }
        return List.copyOf(rows);
    }
}
