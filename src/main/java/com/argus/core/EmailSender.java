package com.argus.core;

import java.io.IOException;
import java.util.Objects;

/**
 * Sends one {@link ScanAlert} as an email to one {@link EmailEndpoint}. Composes, sends, maps
 * the failure. BLOCKING -- never call from the FX thread (invariant 3).
 *
 * No retries, no backoff, no rate limiting, no queueing -- the {@link WebhookSender} precedent.
 */
public final class EmailSender {

    private final EmailTransport transport;

    public EmailSender() {
        this(new JdkSmtpTransport());
    }

    /** Package-private test seam (the {@code WebhookSender}/{@code SocketConnector} shape). */
    EmailSender(EmailTransport transport) {
        this.transport = transport;
    }

    /**
     * @throws EmailDeliveryException on any SMTP failure
     * @throws InterruptedException   propagated UNWRAPPED — interrupt is the cancel mechanism
     */
    public void send(EmailEndpoint endpoint, ScanAlert alert)
            throws EmailDeliveryException, InterruptedException {
        Objects.requireNonNull(endpoint, "endpoint must not be null");
        Objects.requireNonNull(alert, "alert must not be null");

        try {
            transport.send(endpoint, EmailPayloads.subject(alert), EmailPayloads.body(alert));
        } catch (EmailDeliveryException e) {
            // Already carries the real SMTP reply code (JdkSmtpTransport's own expect() check)
            // -- rethrown as-is rather than re-wrapped, so that code is not lost.
            throw e;
        } catch (IOException e) {
            throw new EmailDeliveryException(
                    "email delivery failed: transport error", EmailDeliveryException.NO_REPLY_CODE,
                    e);
        }
    }
}
