package com.argus.core;

import java.io.IOException;
import java.net.URI;

/** One-shot HTTP GET. The test seam that keeps live network calls out of {@code mvn verify}. */
@FunctionalInterface
interface HttpFetcher {
    HttpFetchResult fetch(URI uri) throws IOException, InterruptedException;
}
