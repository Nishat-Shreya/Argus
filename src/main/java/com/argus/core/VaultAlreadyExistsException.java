package com.argus.core;

/** {@code create()} refused to overwrite an existing vault. */
public class VaultAlreadyExistsException extends VaultException {

    public VaultAlreadyExistsException(String message) {
        super(message);
    }

    public VaultAlreadyExistsException(String message, Throwable cause) {
        super(message, cause);
    }
}
