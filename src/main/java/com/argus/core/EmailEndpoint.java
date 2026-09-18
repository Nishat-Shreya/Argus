package com.argus.core;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A validated SMTP destination and credential, parsed from one vault entry (the {@link
 * WebhookEndpoint} precedent, P3-17). {@code toString()} NEVER reveals the username, password
 * or recipient (invariant 7) -- only the host and port.
 *
 * Stored format: {@code host|port|username|to|password} -- password LAST and the split is
 * limited to 5 parts, so a password containing {@code |} is still captured whole rather than
 * truncated (a real-world credential must not be assumed delimiter-safe).
 */
public record EmailEndpoint(String host, int port, String username, String recipient,
        String password) {

    static final int MAX_FIELD_LENGTH = 256;

    private static final Pattern EMAIL_SHAPE = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    public EmailEndpoint {
        Objects.requireNonNull(host, "host must not be null");
        Objects.requireNonNull(username, "username must not be null");
        Objects.requireNonNull(recipient, "recipient must not be null");
        Objects.requireNonNull(password, "password must not be null");
        if (host.isBlank() || host.length() > MAX_FIELD_LENGTH) {
            throw new IllegalArgumentException("SMTP host is invalid");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("SMTP port must be between 1 and 65535");
        }
        if (username.isBlank() || username.length() > MAX_FIELD_LENGTH) {
            throw new IllegalArgumentException("SMTP username is invalid");
        }
        if (!EMAIL_SHAPE.matcher(recipient).matches() || recipient.length() > MAX_FIELD_LENGTH) {
            throw new IllegalArgumentException("recipient address is invalid");
        }
        if (password.isBlank()) {
            throw new IllegalArgumentException("SMTP password must not be blank");
        }
    }

    /**
     * Parses {@code "host|port|username|to|password"}.
     *
     * @throws IllegalArgumentException with a message that NEVER contains the input
     */
    public static EmailEndpoint parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("email settings must not be blank");
        }
        String[] parts = raw.strip().split("\\|", 5);
        if (parts.length != 5) {
            throw new IllegalArgumentException(
                    "email settings must have the form host|port|username|to|password");
        }
        int port;
        try {
            port = Integer.parseInt(parts[1].strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("SMTP port must be a number");
        }
        return new EmailEndpoint(parts[0].strip(), port, parts[2].strip(), parts[3].strip(),
                parts[4]);
    }

    /** {@code EmailEndpoint[smtp.example.com:465]} */
    @Override
    public String toString() {
        return "EmailEndpoint[" + host + ":" + port + "]";
    }
}
