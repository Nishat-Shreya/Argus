package com.argus.ui;

import java.time.LocalDate;

/** One selectable scan in a picker (plan §3.5). {@code label}: {@code "14:32:07 · example.com"}. */
record ScanChoice(long scanId, LocalDate date, String label) { }
