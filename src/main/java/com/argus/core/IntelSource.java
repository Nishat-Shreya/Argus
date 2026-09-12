package com.argus.core;

import java.util.Set;

/**
 * Strategy for one threat-intel provider. Implementations are constructed with a Vault
 * (P1-05: "no keys before login" is a compile-time fact) and pull their API key from it at
 * query time; NO key material appears in this interface.
 *
 * Implementations MUST be immutable and safe for concurrent use by multiple scan threads: no
 * caches, no memo fields, no mutable counters (invariant 5, §4).
 *
 * BLOCKING: query() performs network I/O. com.argus.ui callers run it on a background thread
 * (invariant 3). Cancellation is thread interrupt.
 *
 * Discovery only: a provider is read, never written to. No submission, no reporting, no action.
 */
public interface IntelSource {

    /** Stable lowercase identifier, {@code [a-z0-9]+}, e.g. "virustotal". Never a display title. */
    String name();

    /** Non-empty, immutable. What this provider can be asked about. */
    Set<IntelSubjectKind> supportedSubjects();

    /** The single authoritative routing predicate — P2-06 and the sources use only this. */
    default boolean supports(IntelSubject subject) {
        return subject != null && supportedSubjects().contains(subject.kind());
    }

    /**
     * @throws IllegalArgumentException  subject null, or its kind is not in supportedSubjects()
     *                                   — thrown BEFORE any I/O
     * @throws MissingApiKeyException    no key for this source in the vault (fixable by operator)
     * @throws IntelSourceException      HTTP status, transport, oversized or unparseable body,
     *                                   or the vault is closed
     * @throws InterruptedException      the calling thread was interrupted (= cancellation);
     *                                   NEVER wrapped, NEVER swallowed
     * @return never null; {@link IntelResult#unknown} when the provider has no data on the
     *         subject (typically its 404) — that is a success, not a failure
     */
    IntelResult query(IntelSubject subject) throws IntelSourceException, InterruptedException;
}
