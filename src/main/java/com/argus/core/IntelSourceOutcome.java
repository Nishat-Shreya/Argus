package com.argus.core;

/**
 * One {@link IntelSource}'s outcome for one {@link IntelSubject}, as part of one
 * {@link IntelReport}. Immutable value type.
 *
 * The two nullable components are structurally enforced by the compact constructor, the same
 * technique {@link IntelResult} uses for {@code UNKNOWN => score == 0}:
 * <ul>
 *   <li>{@code result} is non-null iff {@code status == OK}</li>
 *   <li>{@code error} is non-null iff {@code status} is {@code FAILED} or {@code NOT_CONFIGURED}</li>
 * </ul>
 *
 * {@code NOT_CONFIGURED} retains its {@link MissingApiKeyException} because
 * {@code vaultEntryName()} is the actionable piece a UI needs ("configure apikey.censys").
 */
public record IntelSourceOutcome(
        String sourceName,
        IntelSourceStatus status,
        IntelResult result,
        IntelSourceException error
) {
    public IntelSourceOutcome {
        if (sourceName == null || sourceName.isBlank()) {
            throw new IllegalArgumentException("sourceName must not be null or blank");
        }
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
        boolean resultAllowed = status == IntelSourceStatus.OK;
        if (resultAllowed && result == null) {
            throw new IllegalArgumentException("result must not be null when status is OK");
        }
        if (!resultAllowed && result != null) {
            throw new IllegalArgumentException("result must be null unless status is OK");
        }
        boolean errorAllowed =
                status == IntelSourceStatus.FAILED || status == IntelSourceStatus.NOT_CONFIGURED;
        if (errorAllowed && error == null) {
            throw new IllegalArgumentException(
                    "error must not be null when status is FAILED or NOT_CONFIGURED");
        }
        if (!errorAllowed && error != null) {
            throw new IllegalArgumentException(
                    "error must be null unless status is FAILED or NOT_CONFIGURED");
        }
    }

    public static IntelSourceOutcome ok(String sourceName, IntelResult result) {
        return new IntelSourceOutcome(sourceName, IntelSourceStatus.OK, result, null);
    }

    public static IntelSourceOutcome unsupported(String sourceName) {
        return new IntelSourceOutcome(sourceName, IntelSourceStatus.UNSUPPORTED_SUBJECT, null, null);
    }

    public static IntelSourceOutcome notConfigured(String sourceName, MissingApiKeyException e) {
        return new IntelSourceOutcome(sourceName, IntelSourceStatus.NOT_CONFIGURED, null, e);
    }

    public static IntelSourceOutcome failed(String sourceName, IntelSourceException e) {
        return new IntelSourceOutcome(sourceName, IntelSourceStatus.FAILED, null, e);
    }

    public boolean isOk() {
        return status == IntelSourceStatus.OK;
    }

    /** HTTP status behind a FAILED outcome, or 0 when there was none / not FAILED. */
    public int statusCode() {
        return status == IntelSourceStatus.FAILED ? error.statusCode() : 0;
    }
}
