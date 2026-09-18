package com.argus.ui;

import com.argus.core.EmailDeliveryException;
import com.argus.core.EmailEndpoint;
import com.argus.core.EmailSender;
import com.argus.core.ScanAlert;
import java.lang.System.Logger.Level;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * The email {@link AlertChannel} (P3-17). The {@link WebhookAlertChannel} twin, field for
 * field: FX thread submits and returns; the SMTP endpoint is resolved on the worker thread,
 * never on the FX thread, for the identical reason ({@code Vault.get} takes the vault's lock).
 */
final class EmailAlertChannel implements AlertChannel {

    static final String THREAD_NAME = "argus-email";

    private static final System.Logger LOGGER =
            System.getLogger(EmailAlertChannel.class.getName());

    private final Supplier<Optional<EmailEndpoint>> endpoints;
    private final EmailSender sender;
    private final ExecutorService executor;

    /** @param endpoints resolved PER DELIVERY, on the worker thread. Empty means "not
     *                    configured": {@link #deliver(ScanAlert)} is then a silent no-op. */
    EmailAlertChannel(Supplier<Optional<EmailEndpoint>> endpoints, EmailSender sender) {
        this.endpoints = Objects.requireNonNull(endpoints, "endpoints");
        this.sender = Objects.requireNonNull(sender, "sender");
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, THREAD_NAME);
            thread.setDaemon(true);
            return thread;
        };
        this.executor = Executors.newSingleThreadExecutor(factory);
    }

    /** FX thread: submits and returns. No I/O, no vault read, no blocking call. */
    @Override
    public void deliver(ScanAlert alert) {
        Objects.requireNonNull(alert, "alert");
        try {
            executor.submit(() -> deliverOnWorkerThread(alert));
        } catch (RejectedExecutionException e) {
            LOGGER.log(Level.WARNING, "email delivery skipped: channel is closed");
        }
    }

    private void deliverOnWorkerThread(ScanAlert alert) {
        try {
            Optional<EmailEndpoint> endpoint = endpoints.get();
            if (endpoint.isEmpty()) {
                return;
            }
            sender.send(endpoint.get(), alert);
        } catch (EmailDeliveryException e) {
            LOGGER.log(Level.WARNING, "email delivery failed: replyCode=" + e.replyCode(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "email delivery failed unexpectedly", e);
        }
    }

    /** Invariant 6: {@code shutdown()} -&gt; {@code awaitTermination(5s)} -&gt;
     *  {@code shutdownNow()} -&gt; {@code awaitTermination(5s)} -&gt; one WARNING. */
    @Override
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    LOGGER.log(Level.WARNING, "email worker pool did not terminate in time");
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }

    /** Test-support only: true once the pool has fully terminated. */
    boolean isPoolTerminatedForTest() {
        return executor.isTerminated();
    }
}
