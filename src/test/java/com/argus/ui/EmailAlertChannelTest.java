package com.argus.ui;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.argus.core.EmailEndpoint;
import com.argus.core.EmailSender;
import com.argus.core.ScanAlert;
import java.net.ServerSocket;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * {@link EmailAlertChannel} against a REAL {@link EmailSender} (its fake-transport seam is
 * package-private to {@code core}, unreachable from here) -- the {@code WebhookAlertChannelTest}
 * precedent, adapted: {@code EmailSender}'s public constructor always uses real implicit TLS
 * (unlike {@code WebhookSender}'s plain-HTTP {@code HttpClient}), so there is no plain loopback
 * server this layer can hand it. Real delivery over the wire is already covered where it CAN be
 * tested plainly, with the injectable {@code SocketFactory} seam: {@code
 * core.JdkSmtpTransportTest} (protocol) and {@code core.EmailSenderTest} (composition). This
 * class verifies only what needs no live server: threading, not-configured, and
 * failure-containment.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class EmailAlertChannelTest {

    private static final ScanAlert ALERT =
            new ScanAlert("example.com", 1L, 2L, 1, List.of("a.example.com"));

    private static EmailEndpoint unboundEndpoint() throws Exception {
        int unboundPort;
        try (ServerSocket probe = new ServerSocket(0)) {
            unboundPort = probe.getLocalPort();
        }
        return new EmailEndpoint("127.0.0.1", unboundPort, "operator@example.com",
                "ops@example.com", "s3cret");
    }

    @Test
    void h1AConnectionFailureIsContainedNotThrown() throws Exception {
        EmailEndpoint deadEndpoint = unboundEndpoint();
        CountDownLatch resolvedLatch = new CountDownLatch(1);
        Supplier<Optional<EmailEndpoint>> supplier = () -> {
            resolvedLatch.countDown();
            return Optional.of(deadEndpoint);
        };

        EmailAlertChannel channel = new EmailAlertChannel(supplier, new EmailSender());
        try {
            assertDoesNotThrow(() -> channel.deliver(ALERT));
            assertTrue(resolvedLatch.await(10, TimeUnit.SECONDS));
        } finally {
            channel.close();
        }
    }

    @Test
    void h2ResolutionHappensOffTheFxThreadOnADaemonArgusEmailThread() throws Exception {
        CountDownLatch resolvedLatch = new CountDownLatch(1);
        AtomicReference<String> threadName = new AtomicReference<>();
        AtomicBoolean daemon = new AtomicBoolean();
        Supplier<Optional<EmailEndpoint>> supplier = () -> {
            threadName.set(Thread.currentThread().getName());
            daemon.set(Thread.currentThread().isDaemon());
            resolvedLatch.countDown();
            return Optional.empty();
        };

        EmailAlertChannel channel = new EmailAlertChannel(supplier, new EmailSender());
        try {
            channel.deliver(ALERT);
            assertTrue(resolvedLatch.await(10, TimeUnit.SECONDS));
            assertEquals(EmailAlertChannel.THREAD_NAME, threadName.get());
            assertTrue(daemon.get());
        } finally {
            channel.close();
        }
    }

    @Test
    void h3NotConfiguredIsASilentNoOp() throws Exception {
        CountDownLatch resolvedLatch = new CountDownLatch(1);
        Supplier<Optional<EmailEndpoint>> supplier = () -> {
            resolvedLatch.countDown();
            return Optional.empty();
        };

        EmailAlertChannel channel = new EmailAlertChannel(supplier, new EmailSender());
        try {
            assertDoesNotThrow(() -> channel.deliver(ALERT));
            assertTrue(resolvedLatch.await(10, TimeUnit.SECONDS));
        } finally {
            channel.close();
        }
    }

    @Test
    void h4TwoConsecutiveFailuresBothDeliverWithoutCrashingTheChannel() throws Exception {
        EmailEndpoint firstDead = unboundEndpoint();
        EmailEndpoint secondDead = unboundEndpoint();
        CountDownLatch secondResolved = new CountDownLatch(1);

        AtomicInteger callCount = new AtomicInteger();
        Supplier<Optional<EmailEndpoint>> supplier = () -> {
            boolean isFirst = callCount.incrementAndGet() == 1;
            if (!isFirst) {
                secondResolved.countDown();
            }
            return Optional.of(isFirst ? firstDead : secondDead);
        };

        EmailAlertChannel channel = new EmailAlertChannel(supplier, new EmailSender());
        try {
            assertDoesNotThrow(() -> channel.deliver(ALERT));
            assertDoesNotThrow(() -> channel.deliver(ALERT));

            assertTrue(secondResolved.await(10, TimeUnit.SECONDS),
                    "the channel must still process a delivery after a prior one failed");
        } finally {
            channel.close();
        }
    }

    @Test
    void h5SupplierThrowingIsContained() throws Exception {
        CountDownLatch secondResolved = new CountDownLatch(1);
        AtomicInteger callCount = new AtomicInteger();
        Supplier<Optional<EmailEndpoint>> supplier = () -> {
            if (callCount.incrementAndGet() == 1) {
                throw new RuntimeException("boom");
            }
            secondResolved.countDown();
            return Optional.empty();
        };

        EmailAlertChannel channel = new EmailAlertChannel(supplier, new EmailSender());
        try {
            assertDoesNotThrow(() -> channel.deliver(ALERT));
            assertDoesNotThrow(() -> channel.deliver(ALERT));

            assertTrue(secondResolved.await(10, TimeUnit.SECONDS),
                    "the channel must still process deliveries after a prior supplier failure");
        } finally {
            channel.close();
        }
    }

    @Test
    void h6ShutdownTerminatesThePoolAndFurtherDeliveriesAreSilentNoOps() {
        EmailAlertChannel channel = new EmailAlertChannel(Optional::empty, new EmailSender());
        channel.close();

        assertTrue(channel.isPoolTerminatedForTest());
        assertDoesNotThrow(() -> channel.deliver(ALERT));
    }

    @Test
    void h7CloseIsIdempotentAndDeliverNullThrowsBeforeSubmission() {
        AtomicBoolean supplierCalled = new AtomicBoolean(false);
        Supplier<Optional<EmailEndpoint>> supplier = () -> {
            supplierCalled.set(true);
            return Optional.empty();
        };
        EmailAlertChannel channel = new EmailAlertChannel(supplier, new EmailSender());

        assertThrows(NullPointerException.class, () -> channel.deliver(null));
        assertFalse(supplierCalled.get());

        channel.close();
        assertDoesNotThrow(channel::close);
    }
}
