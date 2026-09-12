package com.argus.ui;

/**
 * One row of the key-vault table. Carries NO key material — by construction, not by
 * discipline: its only components are the catalogue entry and a boolean. Pinned by a
 * reflective test ({@code ApiKeyRowsTest}).
 */
record ApiKeyRow(ApiKeySource source, boolean configured) {

    /** Operator-facing title, delegated to the catalogue entry. */
    String displayName() {
        return source.displayName();
    }

    /** The vault entry name this row reflects, delegated to the catalogue entry. */
    String entryName() {
        return source.entryName();
    }

    /** {@code "configured"} or {@code "not configured"} — never the entry's value. */
    String statusText() {
        return configured ? "configured" : "not configured";
    }
}
