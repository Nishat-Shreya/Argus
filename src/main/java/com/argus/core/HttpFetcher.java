package com.argus.core;

import java.io.IOException;

/** One-shot HTTP GET. The test seam that keeps live network calls out of {@code mvn verify}. */
@FunctionalInterface
interface HttpFetcher {
    HttpFetchResult fetch(HttpRequestSpec spec) throws IOException, InterruptedException;
}
