package com.argus.core;

/**
 * The outcome bucket for one {@link IntelSource} in one {@link IntelReport}. See
 * {@link IntelSourceOutcome} for the per-status invariants on {@code result}/{@code error}.
 */
public enum IntelSourceStatus { OK, UNSUPPORTED_SUBJECT, NOT_CONFIGURED, FAILED }
