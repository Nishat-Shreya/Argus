package com.argus.db;

/**
 * Discriminator for a {@code findings} row. {@code PORT} and {@code SUBDOMAIN} are what Phase 1
 * produces; P2-07 adds its own value and its own nullable columns/table then (plan §7.3).
 */
public enum FindingType { PORT, SUBDOMAIN }
