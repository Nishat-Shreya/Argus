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
