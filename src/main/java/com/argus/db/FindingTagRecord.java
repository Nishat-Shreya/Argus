package com.argus.db;

/**
 * One persisted tag assignment: a {@code finding_tags} row joined to its {@code tags} row.
 * {@code name} is the STORED casing, which may differ from what the caller typed —
 * {@code tags.name} is {@code COLLATE NOCASE UNIQUE}, so the first writer's casing wins (plan
 * §0.1/3).
 */
public record FindingTagRecord(long findingId, long tagId, String name) { }
