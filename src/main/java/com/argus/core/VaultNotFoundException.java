package com.argus.core;

/** No vault file for that operator. The first-run signal (plan §7.7). */
public class VaultNotFoundException extends VaultException {

    public VaultNotFoundException(String message) {
        super(message);
    }

    public VaultNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
