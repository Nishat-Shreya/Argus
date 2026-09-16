package com.argus.ui;

/** One row of the findings-detail table. Carries the db {@code findings.id} so a note can be
 *  keyed to it — the one thing {@link FindingRow} deliberately does not carry. Everything else
 *  is display-only {@code String}s, the {@code FindingRow} convention. */
record NoteRow(long findingId, String type, String subject, String port, String state) { }
