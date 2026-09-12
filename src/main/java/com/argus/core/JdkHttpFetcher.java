package com.argus.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/** The real fetcher: java.net.http.HttpClient, bounded body, JSON Accept header. */
final class JdkHttpFetcher implements HttpFetcher {

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    static final int MAX_BODY_BYTES = 16 * 1024 * 1024;
    static final String USER_AGENT = "Argus/1.0 (attack-surface discovery)";

    private static final int COPY_CHUNK_SIZE = 8192;

    // HttpClient is thread-safe and owns a selector thread plus an executor; one
    // process-lifetime client avoids leaking a thread pool per scan (plan §3.5).
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    JdkHttpFetcher() {}

    @Override
    public HttpFetchResult fetch(HttpRequestSpec spec) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(spec.uri())
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT)
                .GET();
        // Defaults first, then each spec header on top: a spec header of the same name
        // replaces the default (plan §3.2), every other default survives.
        for (Map.Entry<String, String> header : spec.headers().entrySet()) {
            builder.setHeader(header.getKey(), header.getValue());
        }
        HttpRequest request = builder.build();

        HttpResponse<InputStream> response =
                CLIENT.send(request, BodyHandlers.ofInputStream());
        String body = readBoundedUtf8(response.body());
        return new HttpFetchResult(response.statusCode(), body);
    }

    /** Bounded read: never accumulates more than {@code MAX_BODY_BYTES} in the heap. */
    private static String readBoundedUtf8(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[COPY_CHUNK_SIZE];
        int read;
        try (in) {
            while ((read = in.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
                if (buffer.size() > MAX_BODY_BYTES) {
                    throw new IOException(
                            "response body exceeded " + MAX_BODY_BYTES + " bytes");
                }
            }
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }
}
