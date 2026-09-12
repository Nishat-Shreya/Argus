package com.argus.ui;

/**
 * One row of the findings table. All-{@code String} by design: a subdomain has no port and no
 * state, and a blank cell is the honest rendering of "not applicable" (§7.5 of the plan).
 */
record FindingRow(String type, String subject, String port, String state) {

    /** The console line for this finding. Formatted once, here, so the log and the table agree. */
    String logLine() {
        if ("port".equals(type)) {
            return String.format("%-8s%s:%s", state, subject, port);
        }
        return String.format("%-8s%s", "subdomain", subject);
    }
}
