package com.argus.core;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * Test double for {@link WebhookTransport}: scripts a status code or a thrown exception;
 * records every {@code (uri, jsonBody)} pair it was asked to post. No sockets, no off-box
 * network — the {@code FakeHttpFetcher} shape.
 */
final class FakeWebhookTransport implements WebhookTransport {

    private Integer scriptedStatus;
    private Exception scriptedFailure;
    private final List<URI> requestedUris = new ArrayList<>();
    private final List<String> requestedBodies = new ArrayList<>();

    void willReturn(int status) {
        this.scriptedStatus = status;
        this.scriptedFailure = null;
    }

    void willThrow(Exception failure) {
        this.scriptedFailure = failure;
        this.scriptedStatus = null;
    }

    List<URI> requestedUris() {
        return List.copyOf(requestedUris);
    }

    List<String> requestedBodies() {
        return List.copyOf(requestedBodies);
    }

    int callCount() {
        return requestedUris.size();
    }

    @Override
    public int post(URI uri, String jsonBody) throws IOException, InterruptedException {
        requestedUris.add(uri);
        requestedBodies.add(jsonBody);
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
        if (scriptedStatus == null) {
            throw new IllegalStateException("FakeWebhookTransport has no scripted result or failure");
        }
        return scriptedStatus;
    }
}
