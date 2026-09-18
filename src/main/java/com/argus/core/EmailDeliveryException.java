package com.argus.core;

import java.io.IOException;

/**
 * Delivery failed. Carries the SMTP reply code when one was received. The message NEVER
 * contains the username, password or recipient (invariant 7) -- only a fixed literal.
 *
 * Extends {@link IOException} (unlike the sibling {@code WebhookDeliveryException}) so {@code
 * EmailTransport}'s {@code throws IOException} signature can carry a protocol-level failure
 * (a bad SMTP reply code) all the way from {@code JdkSmtpTransport} without a second exception
 * type -- there is no separate int-status return the way {@code WebhookTransport.post} has one,
 * since one SMTP dialog has several sequential reply codes, not one.
 */
public class EmailDeliveryException extends IOException {

    /** No SMTP reply was ever received (a transport/timeout failure). */
    public static final int NO_REPLY_CODE = -1;

    private final int replyCode;

    public EmailDeliveryException(String message, int replyCode) {
        super(message);
        this.replyCode = replyCode;
    }

    public EmailDeliveryException(String message, int replyCode, Throwable cause) {
        super(message, cause);
        this.replyCode = replyCode;
    }

    public int replyCode() {
        return replyCode;
    }
}
