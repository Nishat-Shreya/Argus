package com.argus.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * {@link KevCatalogLoader} — the only class in this item that touches the network (plan
 * §3.5/§6.5). Uses the unmodified {@link FakeHttpFetcher}; no live network call.
 */
class KevCatalogLoaderTest {

    // ---- L1 ----

    @Test
    void twoHundredWithCatalogSmallYieldsAFourEntryCatalog() throws Exception {
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        fetcher.willReturn(new HttpFetchResult(200, Fixtures.read("kev", "catalog-small.json")));
        KevCatalogLoader loader = new KevCatalogLoader(fetcher);

        KevCatalog catalog = loader.load();

        assertEquals(4, catalog.size());
        assertEquals(1, fetcher.callCount());
    }

    // ---- L2 ----

    @Test
    void uriPinMatchesTheDocumentedLiteral() throws Exception {
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        fetcher.willReturn(new HttpFetchResult(200, Fixtures.read("kev", "catalog-small.json")));
        KevCatalogLoader loader = new KevCatalogLoader(fetcher);

        loader.load();

        assertEquals(KevCatalogLoader.CATALOG_URI, fetcher.requestedUris().get(0));
        assertEquals(
                "https://www.cisa.gov/sites/default/files/feeds/known_exploited_vulnerabilities.json",
                fetcher.requestedUris().get(0).toString());
    }

    // ---- L3 ----

    @Test
    void requestCarriesZeroHeadersAndNoQueryString() throws Exception {
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        fetcher.willReturn(new HttpFetchResult(200, Fixtures.read("kev", "catalog-small.json")));
        KevCatalogLoader loader = new KevCatalogLoader(fetcher);

        loader.load();

        HttpRequestSpec spec = fetcher.requestedSpecs().get(0);
        assertTrue(spec.headers().isEmpty());
        assertEquals(null, spec.uri().getRawQuery());
    }

    // ---- L4 ----

    @Test
    void blankBodyOnTwoHundredThrows() {
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        fetcher.willReturn(new HttpFetchResult(200, ""));
        KevCatalogLoader loader = new KevCatalogLoader(fetcher);

        assertThrows(KevCatalogException.class, loader::load);
    }

    // ---- L5 ----

    @Test
    void fourOhFourThrowsWithStatusCode404() {
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        fetcher.willReturn(new HttpFetchResult(404, ""));
        KevCatalogLoader loader = new KevCatalogLoader(fetcher);

        KevCatalogException e = assertThrows(KevCatalogException.class, loader::load);
        assertEquals(404, e.statusCode());
    }

    // ---- L6 ----

    @Test
    void fourTwentyNineThrowsWithStatusCode429AndNoRetry() {
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        fetcher.willReturn(new HttpFetchResult(429, ""));
        KevCatalogLoader loader = new KevCatalogLoader(fetcher);

        KevCatalogException e = assertThrows(KevCatalogException.class, loader::load);
        assertEquals(429, e.statusCode());
        assertEquals(1, fetcher.callCount());
    }

    // ---- L7 ----

    @Test
    void serverErrorsThrowWithStatusIntact() {
        for (int status : new int[] {500, 503}) {
            FakeHttpFetcher fetcher = new FakeHttpFetcher();
            fetcher.willReturn(new HttpFetchResult(status, ""));
            KevCatalogLoader loader = new KevCatalogLoader(fetcher);

            KevCatalogException e = assertThrows(KevCatalogException.class, loader::load);
            assertEquals(status, e.statusCode());
        }
    }

    // ---- L8 ----

    @Test
    void ioExceptionFromFetcherIsWrappedWithCausePreservedAndStatusZero() {
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        IOException cause = new IOException("connection reset");
        fetcher.willThrow(cause);
        KevCatalogLoader loader = new KevCatalogLoader(fetcher);

        KevCatalogException e = assertThrows(KevCatalogException.class, loader::load);
        assertEquals(cause, e.getCause());
        assertEquals(0, e.statusCode());
    }

    // ---- L9 ----

    @Test
    void interruptedExceptionPropagatesUnwrapped() {
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        fetcher.willThrow(new InterruptedException("cancelled"));
        KevCatalogLoader loader = new KevCatalogLoader(fetcher);

        assertThrows(InterruptedException.class, loader::load);
    }

    // ---- L10 ----

    @Test
    void twoHundredWithEmptyVulnerabilitiesYieldsAValidEmptyCatalogNoException() throws Exception {
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        fetcher.willReturn(
                new HttpFetchResult(200, Fixtures.read("kev", "catalog-empty-vulnerabilities.json")));
        KevCatalogLoader loader = new KevCatalogLoader(fetcher);

        KevCatalog catalog = loader.load();

        assertTrue(catalog.isEmpty());
    }

    // ---- L11: message hygiene ----

    @Test
    void noExceptionMessageContainsCisaGovOrTheFixtureBodyText() {
        String bodyLiteral = "never-seen-this-literal-kev-error";
        int[] statuses = {404, 429, 500, 503};
        for (int status : statuses) {
            FakeHttpFetcher fetcher = new FakeHttpFetcher();
            fetcher.willReturn(new HttpFetchResult(status, bodyLiteral));
            KevCatalogLoader loader = new KevCatalogLoader(fetcher);

            KevCatalogException e = assertThrows(KevCatalogException.class, loader::load);
            String message = e.getMessage().toLowerCase(Locale.ROOT);
            assertFalse(message.contains("cisa.gov"));
            assertFalse(message.contains(bodyLiteral.toLowerCase(Locale.ROOT)));
        }
    }
}
