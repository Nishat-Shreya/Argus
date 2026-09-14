package com.argus.ui;

/**
 * One row of the diff table. All-{@code String}, blanks for "not applicable" (the
 * {@code FindingRow} precedent, plan §3.5).
 */
record DiffRow(String change, String type, String subject, String port,
        String baseline, String current) {

    /** CSS style class for this row's {@code change} category. */
    String styleClass() {
        return "diff-" + change;
    }
}
