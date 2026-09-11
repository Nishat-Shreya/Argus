package com.argus.core;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * Test double for {@link HttpFetcher}: scripted to return a given {@link HttpFetchResult}, or
 * throw a given {@link IOException} / {@link InterruptedException}; records every {@link URI}
 * it was asked for. No sockets, no off-box network.
 */
final class FakeHttpFetcher implements HttpFetcher {

    private HttpFetchResult scriptedResult;
    private Exception scriptedFailure;
    private final List<URI> requestedUris = new ArrayList<>();

    void willReturn(HttpFetchResult result) {
        this.scriptedResult = result;
        this.scriptedFailure = null;
    }

    void willThrow(Exception failure) {
        this.scriptedFailure = failure;
        this.scriptedResult = null;
    }

    List<URI> requestedUris() {
        return List.copyOf(requestedUris);
    }

    int callCount() {
        return requestedUris.size();
    }

    @Override
    public HttpFetchResult fetch(URI uri) throws IOException, InterruptedException {
        requestedUris.add(uri);
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
        if (scriptedResult == null) {
            throw new IllegalStateException("FakeHttpFetcher has no scripted result or failure");
        }
        return scriptedResult;
    }
}
