package com.argus.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;

/** {@link EmailSender} against a {@link FakeEmailTransport} -- zero network. No retries. */
class EmailSenderTest {

    private static final EmailEndpoint ENDPOINT =
            new EmailEndpoint("smtp.example.com", 465, "operator@example.com",
                    "ops@example.com", "s3cret");
    private static final ScanAlert ALERT =
            new ScanAlert("example.com", 1L, 2L, 1, List.of("a.example.com"));

    @Test
    void s1HappyPathComposesAndSends() throws Exception {
        FakeEmailTransport transport = new FakeEmailTransport();
        EmailSender sender = new EmailSender(transport);

        sender.send(ENDPOINT, ALERT);

        assertEquals(1, transport.callCount());
        assertEquals(ENDPOINT, transport.requestedEndpoints().get(0));
        assertEquals(EmailPayloads.subject(ALERT), transport.requestedSubjects().get(0));
        assertEquals(EmailPayloads.body(ALERT), transport.requestedBodies().get(0));
    }

    @Test
    void s1bScanCompletedNoticeIsComposedAndSentOnce() throws Exception {
        FakeEmailTransport transport = new FakeEmailTransport();
        EmailSender sender = new EmailSender(transport);
        ScanCompletionNotice notice = new ScanCompletionNotice("example.com", 2L, 7,
                java.util.OptionalLong.of(1L), java.util.Optional.empty());

        sender.send(ENDPOINT, notice);

        assertEquals(1, transport.callCount());
        assertEquals(ENDPOINT, transport.requestedEndpoints().get(0));
        assertEquals(EmailPayloads.subject(notice), transport.requestedSubjects().get(0));
        assertEquals(EmailPayloads.body(notice), transport.requestedBodies().get(0));
    }

    @Test
    void s2TransportIoExceptionYieldsNoReplyCodeWithCausePreserved() {
        FakeEmailTransport transport = new FakeEmailTransport();
        IOException cause = new IOException("connection refused");
        transport.willThrow(cause);
        EmailSender sender = new EmailSender(transport);

        EmailDeliveryException e = assertThrows(EmailDeliveryException.class,
                () -> sender.send(ENDPOINT, ALERT));
        assertEquals(EmailDeliveryException.NO_REPLY_CODE, e.replyCode());
        assertEquals(cause, e.getCause());
    }

    @Test
    void s3AnEmailDeliveryExceptionFromTheTransportPropagatesWithItsRealReplyCode() {
        FakeEmailTransport transport = new FakeEmailTransport();
        transport.willThrow(new EmailDeliveryException("SMTP RCPT TO failed", 550));
        EmailSender sender = new EmailSender(transport);

        EmailDeliveryException e = assertThrows(EmailDeliveryException.class,
                () -> sender.send(ENDPOINT, ALERT));
        assertEquals(550, e.replyCode());
    }

    @Test
    void s4InterruptedExceptionPropagatesUnwrapped() {
        FakeEmailTransport transport = new FakeEmailTransport();
        transport.willThrow(new InterruptedException("interrupted"));
        EmailSender sender = new EmailSender(transport);

        assertThrows(InterruptedException.class, () -> sender.send(ENDPOINT, ALERT));
    }

    @Test
    void s5RejectsNullArguments() {
        EmailSender sender = new EmailSender(new FakeEmailTransport());
        assertThrows(NullPointerException.class, () -> sender.send(null, ALERT));
        assertThrows(NullPointerException.class, () -> sender.send(ENDPOINT, (ScanAlert) null));
        assertThrows(NullPointerException.class,
                () -> sender.send(ENDPOINT, (ScanCompletionNotice) null));
    }
}
