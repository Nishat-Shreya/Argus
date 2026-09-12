package com.argus.core;

/**
 * The coarse, cross-provider bucket. UNKNOWN means "this provider holds no data on this
 * subject" — a successful answer, not a failure, and NOT the same as HARMLESS.
 */
public enum IntelVerdict { UNKNOWN, HARMLESS, SUSPICIOUS, MALICIOUS }
