package com.argus.db;

/**
 * Every failure in {@code com.argus.db}. CHECKED, deliberately: the P1-02 error contract set the
 * convention (one checked domain exception per subsystem, cause preserved), and a JavaFX
 * {@code Task.call()} already throws {@code Exception} so P1-06 pays nothing.
 *
 * Always constructed with the {@code SQLException} as the cause where one exists; the message
 * names the operation, never the row data.
 */
public class PersistenceException extends Exception {

    public PersistenceException(String message, Throwable cause) {
        super(message, cause);
    }

    public PersistenceException(String message) {
        super(message);
    }
}
