package com.argus.core;

import java.io.IOException;
import java.util.Objects;

/**
 * Sends one {@link ScanAlert} to one {@link WebhookEndpoint}. Composes, posts, maps the status.
 * BLOCKING — never call from the FX thread (invariant 3).
 *
 * No retries, no backoff, no rate limiting, no queueing (plan §3.2, R9): a retry policy drags a
 * clock into the test suite.
 */
public final class WebhookSender {

    private final WebhookTransport transport;

    public WebhookSender() {
        this(new JdkWebhookTransport());
    }

    /** Package-private test seam (the {@code SocketConnector}/{@code HttpFetcher} shape). */
    WebhookSender(WebhookTransport transport) {
        this.transport = transport;
    }

    /**
     * @throws WebhookDeliveryException on non-2xx, 3xx, or any transport failure
     * @throws InterruptedException     propagated UNWRAPPED — interrupt is the cancel mechanism
     *                                   (P1-02's standing error contract)
     */
    public void send(WebhookEndpoint endpoint, ScanAlert alert)
            throws WebhookDeliveryException, InterruptedException {
        Objects.requireNonNull(endpoint, "endpoint must not be null");
        Objects.requireNonNull(alert, "alert must not be null");

        String body = WebhookPayloads.toJson(alert);
        int status;
        try {
            status = transport.post(endpoint.uri(), body);
        } catch (IOException e) {
            throw new WebhookDeliveryException(
                    "webhook delivery failed: transport error", WebhookDeliveryException.NO_STATUS,
                    e);
        }

        if (status < 200 || status > 299) {
            throw new WebhookDeliveryException(
                    "webhook delivery failed: unexpected HTTP status", status);
        }
    }
}
