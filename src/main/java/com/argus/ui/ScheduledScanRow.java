package com.argus.ui;

/** One scheduled-scan row for the config screen's table -- plain formatted strings only, the
 *  {@code ApiKeyRow} / {@code NoteRow} precedent. */
record ScheduledScanRow(long id, String target, String interval, String enabledText,
        String lastRun, String nextRun) {
}
