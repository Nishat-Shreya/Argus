package com.argus.core;

/**
 * Every failure in the vault subsystem. CHECKED, deliberately: the P1-02/P1-04 error contract
 * set the convention (one checked domain exception per subsystem, cause preserved), and a
 * JavaFX {@code Task.call()} already throws {@code Exception} so the login screen pays nothing.
 *
 * The message names the operation, never the master password or a vault entry's name/value.
 */
public class VaultException extends Exception {

    public VaultException(String message) {
        super(message);
    }

    public VaultException(String message, Throwable cause) {
        super(message, cause);
    }
}
