package com.argus.core;

/**
 * Every failure in {@link ScanArchive}. CHECKED, deliberately: the {@code VaultException}
 * precedent — one checked exception per {@code core} subsystem, cause preserved, message names
 * the operation. Wraps {@code db.PersistenceException} so it cannot leak silently and so
 * {@code ui} never needs to name a {@code db} type in a {@code catch}.
 *
 * Messages may name the database file path (not a secret); they carry no vault or key material.
 */
public class ScanArchiveException extends Exception {

    public ScanArchiveException(String message, Throwable cause) {
        super(message, cause);
    }
}
