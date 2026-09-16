package com.argus.ui;

import com.argus.core.FindingSnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Maps persisted {@link FindingSnapshot}s onto {@link NoteRow} (plan §3.3). Pure, toolkit-free.
 * Ordering is DELEGATED to {@link SnapshotRows#ordered(List)} (plan R1) — there is one ordering
 * authority shared with the Timeline and Report screens.
 */
final class NoteRows {

    private NoteRows() {
    }

    static List<NoteRow> of(List<FindingSnapshot> findings) {
        Objects.requireNonNull(findings, "findings");
        List<NoteRow> rows = new ArrayList<>();
        for (FindingSnapshot finding : SnapshotRows.ordered(findings)) {
            rows.add(toRow(finding));
        }
        return List.copyOf(rows);
    }

    private static NoteRow toRow(FindingSnapshot finding) {
        String type = finding.type().toLowerCase(Locale.ROOT);
        String subject = finding.subject();
        String port = finding.port() == null ? "" : String.valueOf(finding.port());
        String state = finding.state() == null ? "" : finding.state().toLowerCase(Locale.ROOT);
        return new NoteRow(finding.id(), type, subject, port, state);
    }
}
