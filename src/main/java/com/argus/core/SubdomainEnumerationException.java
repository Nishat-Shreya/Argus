package com.argus.core;

/** Every failure mode of a crt.name query, with the cause preserved. */
public class SubdomainEnumerationException extends Exception {

    private final int statusCode;

    public SubdomainEnumerationException(String message) {
        super(message);
        this.statusCode = 0;
    }

    public SubdomainEnumerationException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = 0;
    }

    public SubdomainEnumerationException(String message, int statusCode) {
        super(message);
        this.statusCode = statusCode;
    }

    /** The HTTP status that caused this, or {@code 0} if the failure was not an HTTP status. */
    public int statusCode() {
        return statusCode;
    }
}
