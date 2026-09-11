package com.argus.db;

/** A persisted {@code findings} row. */
public record FindingRecord(long id, long scanId, FindingType type, String subject, Integer port,
        String state) { }
