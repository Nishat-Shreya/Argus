package com.argus.core;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Test double for {@link HttpFetcher}: scripted to return a given {@link HttpFetchResult}, or
 * throw a given {@link IOException} / {@link InterruptedException}; records every
 * {@link HttpRequestSpec} it was asked for. No sockets, no off-box network.
 */
final class FakeHttpFetcher implements HttpFetcher {

    private HttpFetchResult scriptedResult;
    private Exception scriptedFailure;
    private final List<HttpRequestSpec> requestedSpecs = new ArrayList<>();

    void willReturn(HttpFetchResult result) {
        this.scriptedResult = result;
        this.scriptedFailure = null;
    }

    void willThrow(Exception failure) {
        this.scriptedFailure = failure;
        this.scriptedResult = null;
    }

    /** The {@link URI} of every spec this fake was asked to fetch, in call order. */
    List<URI> requestedUris() {
        List<URI> uris = new ArrayList<>();
        for (HttpRequestSpec spec : requestedSpecs) {
            uris.add(spec.uri());
        }
        return List.copyOf(uris);
    }

    List<HttpRequestSpec> requestedSpecs() {
        return List.copyOf(requestedSpecs);
    }

    /** The headers of the most recent spec this fake was asked to fetch. */
    Map<String, String> lastHeaders() {
        if (requestedSpecs.isEmpty()) {
            throw new IllegalStateException("FakeHttpFetcher has not been asked to fetch yet");
        }
        return requestedSpecs.get(requestedSpecs.size() - 1).headers();
    }

    int callCount() {
        return requestedSpecs.size();
    }

    @Override
    public HttpFetchResult fetch(HttpRequestSpec spec) throws IOException, InterruptedException {
        requestedSpecs.add(spec);
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
