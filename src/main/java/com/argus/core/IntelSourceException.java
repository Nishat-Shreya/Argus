package com.argus.core;

/**
 * Every failure mode of one IntelSource query, with the cause preserved and the source named.
 * Messages must never contain a request URI, a header value or an API key (invariant 7).
 */
public class IntelSourceException extends Exception {

    private final String sourceName;
    private final int statusCode;

    public IntelSourceException(String sourceName, String message) {
        super(message);
        this.sourceName = sourceName;
        this.statusCode = 0;
    }

    public IntelSourceException(String sourceName, String message, Throwable cause) {
        super(message, cause);
        this.sourceName = sourceName;
        this.statusCode = 0;
    }

    public IntelSourceException(String sourceName, String message, int statusCode) {
        super(message);
        this.sourceName = sourceName;
        this.statusCode = statusCode;
    }

    public String sourceName() {
        return sourceName;
    }

    /** The HTTP status that caused this, or 0 if the failure was not an HTTP status. */
    public int statusCode() {
        return statusCode;
    }
}
