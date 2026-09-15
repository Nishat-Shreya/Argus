package com.argus.core;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * A validated webhook destination. {@code toString()} NEVER reveals the path or query — the
 * secret in a Slack/Discord webhook URL is the PATH (plan §0.4), which {@code HttpRequestSpec}'s
 * redaction does not cover.
 */
public record WebhookEndpoint(URI uri) {

    /** Rejected outright: a URL longer than this is refused before any other check. */
    static final int MAX_URL_LENGTH = 2048;

    private static final Set<String> LOOPBACK_HOSTS = Set.of("127.0.0.1", "::1", "[::1]");

    public WebhookEndpoint {
        Objects.requireNonNull(uri, "uri must not be null");
        if (!uri.isAbsolute()) {
            throw new IllegalArgumentException("webhook URL is not a valid URL");
        }
        if (uri.toString().length() > MAX_URL_LENGTH) {
            throw new IllegalArgumentException("webhook URL exceeds the maximum length");
        }
        String host = uri.getHost();
        if (host == null) {
            throw new IllegalArgumentException("webhook URL is not a valid URL");
        }
        if (uri.getUserInfo() != null) {
            throw new IllegalArgumentException("webhook URL must not contain a userinfo component");
        }
        if (uri.getFragment() != null) {
            throw new IllegalArgumentException("webhook URL must not contain a fragment");
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        boolean loopback = isLoopbackHost(host);
        if (!scheme.equals("https") && !(scheme.equals("http") && loopback)) {
            throw new IllegalArgumentException("webhook URL must use https");
        }
    }

    /**
     * Parses and validates operator input.
     *
     * @throws IllegalArgumentException with a message that NEVER contains the input
     */
    public static WebhookEndpoint parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("webhook URL must not be blank");
        }
        URI parsed;
        try {
            parsed = new URI(raw.strip());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("webhook URL is not a valid URL");
        }
        return new WebhookEndpoint(parsed);
    }

    private static boolean isLoopbackHost(String host) {
        return LOOPBACK_HOSTS.contains(host) || "localhost".equalsIgnoreCase(host);
    }

    /** {@code WebhookEndpoint[https://hooks.slack.com/<redacted>]} */
    @Override
    public String toString() {
        return "WebhookEndpoint[" + uri.getScheme() + "://" + uri.getAuthority() + "/<redacted>]";
    }
}
