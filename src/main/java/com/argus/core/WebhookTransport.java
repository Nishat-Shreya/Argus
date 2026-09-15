package com.argus.core;

import java.io.IOException;
import java.net.URI;

/** One-shot HTTP POST. The test seam that keeps live network calls out of {@code mvn verify}
 *  (plan §0.3) — the {@code SocketConnector}/{@code HttpFetcher} shape, purpose-built for
 *  webhook delivery so the frozen intel-source seam is never widened. */
@FunctionalInterface
interface WebhookTransport {
    /** @return the HTTP status code. The response body is discarded. */
    int post(URI uri, String jsonBody) throws IOException, InterruptedException;
}
