package com.argus.core;

/**
 * Delivery failed. Carries the HTTP status when there was one. The message NEVER contains the
 * endpoint URL (invariant 7) — only the status and a fixed literal.
 */
public class WebhookDeliveryException extends Exception {

    /** No HTTP status was ever received (a transport/timeout failure). */
    public static final int NO_STATUS = -1;

    private final int statusCode;

    public WebhookDeliveryException(String message, int statusCode) {
        super(message);
        this.statusCode = statusCode;
    }

    public WebhookDeliveryException(String message, int statusCode, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    public int statusCode() {
        return statusCode;
    }
}
