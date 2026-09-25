package com.argus.core;

/**
 * Builds the subject and plain-text body for one {@link ScanAlert} (P3-17). Pure, exact-value
 * testable. No timestamp field, deliberately -- the same "no new clock read" rule {@link
 * WebhookPayloads} follows.
 */
final class EmailPayloads {

    private EmailPayloads() {
    }

    static String subject(ScanAlert alert) {
        return "Argus: " + alert.addedCount() + " new finding(s) on " + alert.target();
    }

    /** ASCII only: the SMTP transport writes US-ASCII, so a non-ASCII character would be sent as
     *  "?". */
    static String subject(ScanCompletionNotice notice) {
        StringBuilder subject = new StringBuilder("Argus: scan #").append(notice.scanId())
                .append(" of ").append(notice.target()).append(" completed (")
                .append(notice.findingCount()).append(" findings");
        if (notice.baselineScanId().isPresent()) {
            subject.append(", ").append(notice.newFindingCount()).append(" new");
        }
        return subject.append(')').toString();
    }

    static String body(ScanCompletionNotice notice) {
        StringBuilder body = new StringBuilder();
        body.append("Argus scan #").append(notice.scanId()).append(" of ")
                .append(notice.target()).append(" completed successfully.\n\n");
        body.append("Findings in this scan: ").append(notice.findingCount()).append('\n');
        if (notice.baselineScanId().isEmpty()) {
            body.append("Comparison: no earlier completed scan of this target is available.\n");
            return body.toString();
        }
        body.append("Compared with scan #").append(notice.baselineScanId().getAsLong())
                .append(": ").append(notice.newFindingCount()).append(" new finding(s).\n");
        notice.newFindings().ifPresent(alert -> {
            body.append('\n');
            for (String subject : alert.addedSubjects()) {
                body.append("  - ").append(subject).append('\n');
            }
            if (alert.subjectsTruncated()) {
                body.append("  ... and more (list truncated)\n");
            }
        });
        return body.toString();
    }

    static String body(ScanAlert alert) {
        StringBuilder body = new StringBuilder();
        body.append("Argus detected ").append(alert.addedCount())
                .append(" new finding(s) on ").append(alert.target()).append(".\n\n");
        body.append("Baseline scan: #").append(alert.baselineScanId()).append('\n');
        body.append("Current scan: #").append(alert.currentScanId()).append("\n\n");
        for (String subject : alert.addedSubjects()) {
            body.append("  - ").append(subject).append('\n');
        }
        if (alert.subjectsTruncated()) {
            body.append("  ... and more (list truncated)\n");
        }
        return body.toString();
    }
}
