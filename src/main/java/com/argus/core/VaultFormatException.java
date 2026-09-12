package com.argus.core;

/**
 * The file is not a readable argus-vault envelope: not JSON, wrong format marker, unknown
 * version, missing/short field, bad base64, iterations out of range.
 */
public class VaultFormatException extends VaultException {

    public VaultFormatException(String message) {
        super(message);
    }

    public VaultFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
