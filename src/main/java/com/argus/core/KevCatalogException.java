package com.argus.core;

/** Every failure mode of loading the CISA KEV catalog, with the cause preserved. */
public class KevCatalogException extends Exception {

    private final int statusCode;

    public KevCatalogException(String message) {
        super(message);
        this.statusCode = 0;
    }

    public KevCatalogException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = 0;
    }

    public KevCatalogException(String message, int statusCode) {
        super(message);
        this.statusCode = statusCode;
    }

    /** The HTTP status that caused this, or {@code 0} if the failure was not an HTTP status. */
    public int statusCode() {
        return statusCode;
    }
}
