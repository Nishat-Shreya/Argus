package com.argus.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Test double for {@link EmailTransport}: scripts a thrown exception (success is the default,
 *  no-op); records every {@code (endpoint, subject, body)} triple. No sockets, no off-box
 *  network -- the {@code FakeWebhookTransport} shape. */
final class FakeEmailTransport implements EmailTransport {

    private Exception scriptedFailure;
    private final List<EmailEndpoint> requestedEndpoints = new ArrayList<>();
    private final List<String> requestedSubjects = new ArrayList<>();
    private final List<String> requestedBodies = new ArrayList<>();

    void willThrow(Exception failure) {
        this.scriptedFailure = failure;
    }

    List<EmailEndpoint> requestedEndpoints() {
        return List.copyOf(requestedEndpoints);
    }

    List<String> requestedSubjects() {
        return List.copyOf(requestedSubjects);
    }

    List<String> requestedBodies() {
        return List.copyOf(requestedBodies);
    }

    int callCount() {
        return requestedEndpoints.size();
    }

    @Override
    public void send(EmailEndpoint endpoint, String subject, String body)
            throws IOException, InterruptedException {
        requestedEndpoints.add(endpoint);
        requestedSubjects.add(subject);
        requestedBodies.add(body);
        if (scriptedFailure != null) {
            if (scriptedFailure instanceof IOException io) {
                throw io;
            }
            if (scriptedFailure instanceof InterruptedException ie) {
                throw ie;
            }
            if (scriptedFailure instanceof RuntimeException re) {
                throw re;
            }
            throw new AssertionError(
                    "Unsupported scripted failure type: " + scriptedFailure.getClass());
        }
    }
}
