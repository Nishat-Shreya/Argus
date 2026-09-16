package com.argus.ui;

/** One rendered note in the notes list. Pre-formatted timestamp {@code String}, never an
 *  {@code Instant} — the {@code TimelinePoint} rule, so the zone is the caller's and the type is
 *  testable at a fixed zone. */
record NoteEntry(long noteId, String timestamp, String body) { }
