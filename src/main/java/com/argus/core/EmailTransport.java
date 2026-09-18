package com.argus.core;

import java.io.IOException;

/** One-shot SMTP send. The test seam that keeps live network calls out of {@code mvn verify}
 *  (the {@code WebhookTransport}/{@code SocketConnector}/{@code HttpFetcher} shape). */
@FunctionalInterface
interface EmailTransport {
    void send(EmailEndpoint endpoint, String subject, String body)
            throws IOException, InterruptedException;
}
