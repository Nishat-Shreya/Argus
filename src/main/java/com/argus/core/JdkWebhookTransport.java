package com.argus.core;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * The real transport. Its own {@link HttpClient} — {@code Redirect.NEVER}, short timeouts,
 * discarding the response body — NOT a share of {@code JdkHttpFetcher}'s client (R10 of the
 * plan): sharing would mean modifying a frozen file to widen its visibility, and a webhook wants
 * a 10 s budget where intel wants 30 s.
 */
final class JdkWebhookTransport implements WebhookTransport {

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    static final String CONTENT_TYPE = "application/json; charset=utf-8";
    static final String USER_AGENT = "Argus/1.0 (attack-surface discovery)";

    // Redirect.NEVER (R8): a 3xx on a POST is ambiguous, and following one would replay a
    // credential-bearing request at a host the operator did not configure.
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    @Override
    public int post(URI uri, String jsonBody) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", CONTENT_TYPE)
                .header("User-Agent", USER_AGENT)
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                .build();

        // We do not read the receiver's body at all, so P1-02's 16 MiB cap question does not
        // arise here.
        HttpResponse<Void> response = CLIENT.send(request, BodyHandlers.discarding());
        return response.statusCode();
    }
}
