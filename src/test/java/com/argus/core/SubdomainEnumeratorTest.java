package com.argus.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link SubdomainEnumerator} orchestration against a {@link FakeHttpFetcher} — zero sockets,
 * zero off-box network (plan §7.2 layer 1). {@link JdkHttpFetcherTest} covers the real fetcher
 * separately against a loopback server.
 */
class SubdomainEnumeratorTest {

    private static final List<Subdomain> GOLDEN_LIST = List.of(
            new Subdomain("*.example.com"),
            new Subdomain("api.example.com"),
            new Subdomain("assets.example.com"),
            new Subdomain("dev.example.com"),
            new Subdomain("example.com"),
            new Subdomain("legacy.example.com"),
            new Subdomain("mail.example.com"),
            new Subdomain("www.example.com"));

    @Test
    void buildsTheExpectedCrtShUri() {
        assertEquals(URI.create("https://crt.sh/?q=%25.example.com&output=json"),
                SubdomainEnumerator.crtShUri("example.com"));
    }

    @Test
    void normalizesDomainBeforeBuildingTheUri() throws Exception {
        FakeHttpFetcher fake = new FakeHttpFetcher();
        fake.willReturn(new HttpFetchResult(200, "[]"));
        SubdomainEnumerator enumerator = new SubdomainEnumerator(fake);

        enumerator.enumerate("  Example.COM. ");

        assertEquals(URI.create("https://crt.sh/?q=%25.example.com&output=json"),
                fake.requestedUris().get(0));
    }

    @Test
    void issuesExactlyOneRequest() throws Exception {
        FakeHttpFetcher fake = new FakeHttpFetcher();
        fake.willReturn(new HttpFetchResult(200, "[]"));
        SubdomainEnumerator enumerator = new SubdomainEnumerator(fake);

        enumerator.enumerate("example.com");

        assertEquals(1, fake.callCount());
    }

    @Test
    void happyPathReturnsNormalizedResults() throws Exception {
        FakeHttpFetcher fake = new FakeHttpFetcher();
        fake.willReturn(new HttpFetchResult(200, Fixtures.read("example-com.json")));
        SubdomainEnumerator enumerator = new SubdomainEnumerator(fake);

        assertEquals(GOLDEN_LIST, enumerator.enumerate("example.com"));
    }

    @Test
    void emptyArrayReturnsEmptyListNotAnError() throws Exception {
        FakeHttpFetcher fake = new FakeHttpFetcher();
        fake.willReturn(new HttpFetchResult(200, "[]"));
        SubdomainEnumerator enumerator = new SubdomainEnumerator(fake);

        assertEquals(List.of(), enumerator.enumerate("example.com"));
    }

    @Test
    void emptyBodyReturnsEmptyListNotAnError() throws Exception {
        FakeHttpFetcher fake = new FakeHttpFetcher();
        fake.willReturn(new HttpFetchResult(200, ""));
        SubdomainEnumerator enumerator = new SubdomainEnumerator(fake);

        assertEquals(List.of(), enumerator.enumerate("example.com"));
    }

    @Test
    void rejectsMalformedDomainBeforeAnyIo() {
        for (String malformed : List.of(
                "localhost", "*.example.com", "https://example.com", "1.2.3.4")) {
            FakeHttpFetcher fake = new FakeHttpFetcher();
            SubdomainEnumerator enumerator = new SubdomainEnumerator(fake);
            assertThrows(IllegalArgumentException.class, () -> enumerator.enumerate(malformed),
                    "expected rejection for: " + malformed);
            assertEquals(0, fake.callCount(), "no I/O expected for: " + malformed);
        }

        FakeHttpFetcher fakeForNull = new FakeHttpFetcher();
        SubdomainEnumerator enumeratorForNull = new SubdomainEnumerator(fakeForNull);
        assertThrows(IllegalArgumentException.class, () -> enumeratorForNull.enumerate(null));
        assertEquals(0, fakeForNull.callCount());

        FakeHttpFetcher fakeForEmpty = new FakeHttpFetcher();
        SubdomainEnumerator enumeratorForEmpty = new SubdomainEnumerator(fakeForEmpty);
        assertThrows(IllegalArgumentException.class, () -> enumeratorForEmpty.enumerate(""));
        assertEquals(0, fakeForEmpty.callCount());
    }

    @Test
    void httpErrorStatusThrowsWithStatusCode() {
        for (int status : List.of(429, 500, 502, 503, 404)) {
            FakeHttpFetcher fake = new FakeHttpFetcher();
            fake.willReturn(new HttpFetchResult(status, "irrelevant body"));
            SubdomainEnumerator enumerator = new SubdomainEnumerator(fake);

            SubdomainEnumerationException e = assertThrows(SubdomainEnumerationException.class,
                    () -> enumerator.enumerate("example.com"));
            assertEquals(status, e.statusCode());
        }
    }

    @Test
    void htmlBodyWithSuccessStatusThrows() throws Exception {
        FakeHttpFetcher fake = new FakeHttpFetcher();
        fake.willReturn(new HttpFetchResult(200, Fixtures.read("bad-gateway.html")));
        SubdomainEnumerator enumerator = new SubdomainEnumerator(fake);

        SubdomainEnumerationException e = assertThrows(SubdomainEnumerationException.class,
                () -> enumerator.enumerate("example.com"));
        assertEquals(0, e.statusCode());
    }

    @Test
    void transportIoExceptionIsWrappedWithCausePreserved() {
        FakeHttpFetcher fake = new FakeHttpFetcher();
        ConnectException connectException = new ConnectException("refused");
        fake.willThrow(connectException);
        SubdomainEnumerator enumerator = new SubdomainEnumerator(fake);

        SubdomainEnumerationException e = assertThrows(SubdomainEnumerationException.class,
                () -> enumerator.enumerate("example.com"));
        assertSame(connectException, e.getCause());
    }

    @Test
    void requestTimeoutIsWrapped() {
        FakeHttpFetcher fake = new FakeHttpFetcher();
        HttpTimeoutException timeout = new HttpTimeoutException("timed out");
        fake.willThrow(timeout);
        SubdomainEnumerator enumerator = new SubdomainEnumerator(fake);

        SubdomainEnumerationException e = assertThrows(SubdomainEnumerationException.class,
                () -> enumerator.enumerate("example.com"));
        assertSame(timeout, e.getCause());
    }

    @Test
    void interruptedExceptionPropagatesUnwrapped() {
        FakeHttpFetcher fake = new FakeHttpFetcher();
        fake.willThrow(new InterruptedException("cancelled"));
        SubdomainEnumerator enumerator = new SubdomainEnumerator(fake);

        assertThrows(InterruptedException.class, () -> enumerator.enumerate("example.com"));
    }

    @Test
    void interruptFlagIsNotSwallowed() throws Exception {
        FakeHttpFetcher fake = new FakeHttpFetcher();
        fake.willThrow(new InterruptedException("cancelled"));
        SubdomainEnumerator enumerator = new SubdomainEnumerator(fake);

        assertThrows(InterruptedException.class, () -> enumerator.enumerate("example.com"));
        // enumerate() never catches InterruptedException, so it never touches the flag: it
        // must be clear here, not set, because nothing set it in the first place.
        assertFalse(Thread.interrupted());
    }

    @Test
    void isReusableForASecondDomain() throws Exception {
        FakeHttpFetcher fake = new FakeHttpFetcher();
        SubdomainEnumerator enumerator = new SubdomainEnumerator(fake);

        fake.willReturn(new HttpFetchResult(200, "[]"));
        assertTrue(enumerator.enumerate("example.com").isEmpty());

        fake.willReturn(new HttpFetchResult(200, "[]"));
        assertTrue(enumerator.enumerate("example.org").isEmpty());

        assertEquals(2, fake.callCount());
    }
}
