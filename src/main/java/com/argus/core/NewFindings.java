package com.argus.core;

import com.argus.db.NewFinding;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/** THE core -&gt; db finding mapping, P1-04 §7.1. One line per type, never re-invented per call
 *  site. state is an OPAQUE TOKEN to db — state.name(), no db-local copy of the PortState
 *  vocabulary.
 *
 *  Package-private is deliberate: it is what structurally prevents a {@code NewFinding} from
 *  ever reaching {@code ui} (P1-09 §0.2 reason 3). */
final class NewFindings {

    private NewFindings() {
    }

    /**
     * @throws NullPointerException     on null
     * @throws IllegalArgumentException on an unknown type
     */
    static NewFinding of(Object finding) {
        Objects.requireNonNull(finding, "finding");
        if (finding instanceof PortResult portResult) {
            return NewFinding.port(portResult.host(), portResult.port(),
                    portResult.state().name());
        }
        if (finding instanceof Subdomain subdomain) {
            return NewFinding.subdomain(subdomain.name());
        }
        throw new IllegalArgumentException(
                "Unknown finding type: " + finding.getClass().getSimpleName());
    }

    /** Preserves iteration order; returns an unmodifiable list. */
    static List<NewFinding> of(Collection<?> findings) {
        Objects.requireNonNull(findings, "findings");
        List<NewFinding> mapped = new ArrayList<>(findings.size());
        for (Object finding : findings) {
            mapped.add(of(finding));
        }
        return List.copyOf(mapped);
    }
}
