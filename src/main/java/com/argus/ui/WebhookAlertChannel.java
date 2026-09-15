package com.argus.ui;

import com.argus.core.ScanAlert;
import com.argus.core.WebhookDeliveryException;
import com.argus.core.WebhookEndpoint;
import com.argus.core.WebhookSender;
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
 * The webhook {@link AlertChannel}. FX thread: {@link #deliver(ScanAlert)} submits and returns.
 * All blocking work — resolving the endpoint from the vault and posting — runs on a single
 * dedicated daemon worker thread (plan §4.3), so a slow or dead receiver never delays the FX
 * thread or the desktop tray.
 *
 * A SUBTLE INVARIANT-3 TRAP, avoided deliberately: the endpoint is resolved INSIDE the worker
 * task, never in {@link #deliver(ScanAlert)} itself. {@code Vault.get(...)} takes the vault's
 * single lock, and {@code Vault.put}/{@code remove} hold that same lock across an
 * encrypt-and-rewrite of the file; resolving on the FX thread would risk blocking it for the
 * duration of a disk write. Do not "simplify" this by hoisting the supplier call up.
 */
final class WebhookAlertChannel implements AlertChannel {

    static final String THREAD_NAME = "argus-webhook";

    private static final System.Logger LOGGER =
            System.getLogger(WebhookAlertChannel.class.getName());

    private final Supplier<Optional<WebhookEndpoint>> endpoints;
    private final WebhookSender sender;
    private final ExecutorService executor;

    /**
     * @param endpoints resolved PER DELIVERY, on the worker thread — never on the FX thread
     *                   (§4.2). Empty means "not configured": {@link #deliver(ScanAlert)} is
     *                   then a silent no-op.
     */
    WebhookAlertChannel(Supplier<Optional<WebhookEndpoint>> endpoints, WebhookSender sender) {
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
            // A delivery arrived after close() during app exit. One log line, no throw.
            LOGGER.log(Level.WARNING, "webhook delivery skipped: channel is closed");
        }
    }

    private void deliverOnWorkerThread(ScanAlert alert) {
        try {
            Optional<WebhookEndpoint> endpoint = endpoints.get();
            if (endpoint.isEmpty()) {
                return;
            }
            sender.send(endpoint.get(), alert);
        } catch (WebhookDeliveryException e) {
            LOGGER.log(Level.WARNING,
                    "webhook delivery failed: status=" + e.statusCode(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "webhook delivery failed unexpectedly", e);
        }
    }

    /**
     * Invariant 6: {@code shutdown()} -&gt; {@code awaitTermination(5s)} -&gt;
     * {@code shutdownNow()} -&gt; {@code awaitTermination(5s)} -&gt; one WARNING. Idempotent,
     * never throws.
     */
    @Override
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    LOGGER.log(Level.WARNING, "webhook worker pool did not terminate in time");
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
