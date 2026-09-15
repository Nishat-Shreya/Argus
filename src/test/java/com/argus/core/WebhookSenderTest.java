package com.argus.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Section 6.4 of the P3-03 plan: {@link WebhookSender} against a {@link FakeWebhookTransport} —
 * zero network. No retries (S9 pins R9).
 */
class WebhookSenderTest {

    private static final WebhookEndpoint ENDPOINT =
            WebhookEndpoint.parse("https://hooks.example.com/services/T1/B2/abc");
    private static final ScanAlert ALERT =
            new ScanAlert("example.com", 1L, 2L, 1, List.of("a.example.com"));

    @Test
    void s1HappyPath200() throws Exception {
        FakeWebhookTransport transport = new FakeWebhookTransport();
        transport.willReturn(200);
        WebhookSender sender = new WebhookSender(transport);

        sender.send(ENDPOINT, ALERT);

        assertEquals(1, transport.callCount());
        assertEquals(ENDPOINT.uri(), transport.requestedUris().get(0));
        assertEquals(WebhookPayloads.toJson(ALERT), transport.requestedBodies().get(0));
    }

    @Test
    void s2OtherTwoXxStatusesSucceed() throws Exception {
        for (int status : List.of(201, 202, 204)) {
            FakeWebhookTransport transport = new FakeWebhookTransport();
            transport.willReturn(status);
            WebhookSender sender = new WebhookSender(transport);
            sender.send(ENDPOINT, ALERT);
        }
    }

    @Test
    void s3NonSuccessStatusesFail() {
        for (int status : List.of(400, 401, 404, 500)) {
            FakeWebhookTransport transport = new FakeWebhookTransport();
            transport.willReturn(status);
            WebhookSender sender = new WebhookSender(transport);
            WebhookDeliveryException e = assertThrows(WebhookDeliveryException.class,
                    () -> sender.send(ENDPOINT, ALERT));
            assertEquals(status, e.statusCode());
        }
    }

    @Test
    void s4RedirectStatusesFail() {
        for (int status : List.of(301, 302, 307)) {
            FakeWebhookTransport transport = new FakeWebhookTransport();
            transport.willReturn(status);
            WebhookSender sender = new WebhookSender(transport);
            WebhookDeliveryException e = assertThrows(WebhookDeliveryException.class,
                    () -> sender.send(ENDPOINT, ALERT));
            assertEquals(status, e.statusCode());
        }
    }

    @Test
    void s5TransportIoExceptionYieldsNoStatusWithCausePreserved() {
        FakeWebhookTransport transport = new FakeWebhookTransport();
        IOException cause = new IOException("connection refused");
        transport.willThrow(cause);
        WebhookSender sender = new WebhookSender(transport);

        WebhookDeliveryException e = assertThrows(WebhookDeliveryException.class,
                () -> sender.send(ENDPOINT, ALERT));
        assertEquals(WebhookDeliveryException.NO_STATUS, e.statusCode());
        assertEquals(cause, e.getCause());
    }

    @Test
    void s6InterruptPropagatesUnwrapped() {
        FakeWebhookTransport transport = new FakeWebhookTransport();
        transport.willThrow(new InterruptedException("interrupted"));
        WebhookSender sender = new WebhookSender(transport);

        assertThrows(InterruptedException.class, () -> sender.send(ENDPOINT, ALERT));
    }

    @Test
    void s7SecretNeverInTheExceptionMessage() {
        WebhookEndpoint secretEndpoint =
                WebhookEndpoint.parse("https://hooks.example.com/services/SUPERSECRET");
        FakeWebhookTransport transport = new FakeWebhookTransport();
        transport.willReturn(500);
        WebhookSender sender = new WebhookSender(transport);

        WebhookDeliveryException e = assertThrows(WebhookDeliveryException.class,
                () -> sender.send(secretEndpoint, ALERT));
        assertFalse(e.getMessage().contains("SUPERSECRET"));
        assertFalse(e.toString().contains("SUPERSECRET"));
    }

    @Test
    void s8MalformedInputThrowsBeforeAnyTransportCall() {
        FakeWebhookTransport transport = new FakeWebhookTransport();
        WebhookSender sender = new WebhookSender(transport);

        assertThrows(NullPointerException.class, () -> sender.send(null, ALERT));
        assertThrows(NullPointerException.class, () -> sender.send(ENDPOINT, null));
        assertEquals(0, transport.callCount());
    }

    @Test
    void s9ExactlyOneTransportCallPerSendNoRetry() throws Exception {
        FakeWebhookTransport transport = new FakeWebhookTransport();
        transport.willReturn(200);
        WebhookSender sender = new WebhookSender(transport);

        sender.send(ENDPOINT, ALERT);

        assertEquals(1, transport.callCount());
    }

    @Test
    void s10FakeSeesUriByteIdenticalToEndpointUri() throws Exception {
        FakeWebhookTransport transport = new FakeWebhookTransport();
        transport.willReturn(200);
        WebhookSender sender = new WebhookSender(transport);

        sender.send(ENDPOINT, ALERT);

        assertTrue(ENDPOINT.uri() == transport.requestedUris().get(0)
                || ENDPOINT.uri().equals(transport.requestedUris().get(0)));
    }
}
