package com.argus.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Test double for {@link IntelSource}: scripted to return a given {@link IntelResult}, or throw
 * a given {@link IntelSourceException} / {@link InterruptedException}; records every
 * {@link IntelSubject} it was actually asked about. No vault, no HTTP.
 */
final class FakeIntelSource implements IntelSource {

    private final String name;
    private final Set<IntelSubjectKind> supportedSubjects;
    private IntelResult scriptedResult;
    private IntelSourceException scriptedFailure;
    private InterruptedException scriptedInterrupt;
    private final List<IntelSubject> queriedSubjects = new ArrayList<>();

    FakeIntelSource(String name, Set<IntelSubjectKind> supportedSubjects) {
        this.name = name;
        this.supportedSubjects = Set.copyOf(supportedSubjects);
    }

    void willReturn(IntelResult result) {
        this.scriptedResult = result;
        this.scriptedFailure = null;
        this.scriptedInterrupt = null;
    }

    void willThrow(IntelSourceException failure) {
        this.scriptedFailure = failure;
        this.scriptedResult = null;
        this.scriptedInterrupt = null;
    }

    void willThrow(InterruptedException failure) {
        this.scriptedInterrupt = failure;
        this.scriptedResult = null;
        this.scriptedFailure = null;
    }

    /** Every subject actually passed to query() (i.e. after the "before any I/O" checks). */
    List<IntelSubject> queriedSubjects() {
        return List.copyOf(queriedSubjects);
    }

    int queryCount() {
        return queriedSubjects.size();
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Set<IntelSubjectKind> supportedSubjects() {
        return supportedSubjects;
    }

    @Override
    public IntelResult query(IntelSubject subject)
            throws IntelSourceException, InterruptedException {
        if (subject == null) {
            throw new IllegalArgumentException("subject must not be null");
        }
        if (!supports(subject)) {
            throw new IllegalArgumentException(
                    "unsupported subject kind for " + name + ": " + subject.kind());
        }
        queriedSubjects.add(subject);

        if (scriptedInterrupt != null) {
            throw scriptedInterrupt;
        }
        if (scriptedFailure != null) {
            throw scriptedFailure;
        }
        if (scriptedResult == null) {
            throw new IllegalStateException("FakeIntelSource has no scripted result or failure");
        }
        return scriptedResult;
    }
}
