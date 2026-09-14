package com.argus.core;

import com.argus.db.FindingRecord;
import com.argus.db.FindingType;
import java.util.Objects;

/**
 * The scan-independent identity of a finding: {@code (type, subject, port)}.
 *
 * This is the Java twin of the schema's identity index
 * {@code ux_findings_identity ON findings (scan_id, type, subject, ifnull(port, -1))},
 * minus the {@code scan_id} column — dropping {@code scan_id} is exactly what turns
 * "unique within a scan" into "the same finding across two scans" (P1-04 carry-forward).
 *
 * {@code port} is a NULLABLE {@link Integer}, NOT a {@code -1} sentinel: the schema needs
 * {@code -1} only because SQL UNIQUE treats NULLs as distinct, a problem {@link Objects#equals}
 * does not have. The two encodings induce the identical partition because the schema's
 * {@code CHECK (port IS NULL OR (port BETWEEN 1 AND 65535))} makes {@code -1} unreachable.
 *
 * {@code id} and {@code scanId} are deliberately absent — they are surrogate/foreign keys that
 * differ between two scans by construction. {@code state} is deliberately absent too: it is
 * P1-04's ruling that "state is an attribute, not identity", the entire reason a diff can report
 * a "changed" category at all (plan §7.1).
 *
 * {@code subject} is compared verbatim — no case-folding, no trimming (plan §7.4). The schema's
 * {@code findings.subject} column uses SQLite's default BINARY collation, so
 * {@code ux_findings_identity} already treats {@code "Host"} and {@code "host"} as distinct rows;
 * folding here would let two rows the database considers distinct collide into one key.
 */
public record FindingKey(FindingType type, String subject, Integer port) {

    public FindingKey {
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(subject, "subject must not be null");
        if (subject.isBlank()) {
            throw new IllegalArgumentException("subject must not be blank");
        }
    }

    /** Reads the identity out of a persisted row. Verbatim: no case-folding, no trimming. */
    public static FindingKey of(FindingRecord record) {
        Objects.requireNonNull(record, "record must not be null");
        return new FindingKey(record.type(), record.subject(), record.port());
    }
}
