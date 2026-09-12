package com.argus.core;

/**
 * The GCM tag did not verify. Cryptographically this means exactly one of: wrong master
 * password, wrong operator id, or a modified file — and those three are INDISTINGUISHABLE by
 * design. The message must say all three and must never echo the password.
 */
public class WrongMasterPasswordException extends VaultException {

    public WrongMasterPasswordException(String message) {
        super(message);
    }

    public WrongMasterPasswordException(String message, Throwable cause) {
        super(message, cause);
    }
}
