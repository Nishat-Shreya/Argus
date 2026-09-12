package com.argus.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Section 6.4 group C: invariant 5 regression guard. A {@code HashMap} without the lock loses
 * entries under this workload; these tests exist to catch exactly that regression, not to
 * measure timing (hence {@code @Timeout} as a hang detector only).
 */
class VaultConcurrencyTest {

    private static final int TEST_ITERATIONS = 100_000;
    private static final int THREADS = 8;
    private static final int KEYS_PER_THREAD = 25;

    @Test
    @Timeout(30)
    void concurrentPutsOfDistinctKeysAllSurvive(@TempDir Path tempDir) throws Exception {
        VaultStore store = VaultStore.forTesting(tempDir, TEST_ITERATIONS);
        OperatorId operator = OperatorId.of("nishat");

        try (Vault vault = store.create(operator, VaultFixtures.VALID_PASSWORD.clone())) {
            runConcurrentPuts(vault);
        }

        try (Vault reopened = store.open(operator, VaultFixtures.VALID_PASSWORD.clone())) {
            assertEquals(THREADS * KEYS_PER_THREAD, reopened.keys().size());
            for (int t = 0; t < THREADS; t++) {
                for (int k = 0; k < KEYS_PER_THREAD; k++) {
                    String name = "t" + t + "-k" + k;
                    assertEquals(java.util.Optional.of(name + "-value"), reopened.get(name));
                }
            }
        }
    }

    @Test
    @Timeout(30)
    void concurrentPutsLeaveTheFileReadable(@TempDir Path tempDir) throws Exception {
        VaultStore store = VaultStore.forTesting(tempDir, TEST_ITERATIONS);
        OperatorId operator = OperatorId.of("nishat");

        try (Vault vault = store.create(operator, VaultFixtures.VALID_PASSWORD.clone())) {
            runConcurrentPuts(vault);
        }

        try (Vault reopened = store.open(operator, VaultFixtures.VALID_PASSWORD.clone())) {
            assertTrue(reopened.keys().size() > 0);
        }
    }

    @Test
    @Timeout(30)
    void concurrentPutsAndRemovesDoNotCorruptState(@TempDir Path tempDir) throws Exception {
        VaultStore store = VaultStore.forTesting(tempDir, TEST_ITERATIONS);
        OperatorId operator = OperatorId.of("nishat");
        Map<String, String> expected = new TreeMap<>();
        Object expectedLock = new Object();

        try (Vault vault = store.create(operator, VaultFixtures.VALID_PASSWORD.clone())) {
            int threads = 6;
            CyclicBarrier barrier = new CyclicBarrier(threads);
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            try {
                List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();
                for (int t = 0; t < threads; t++) {
                    int threadIndex = t;
                    futures.add(pool.submit(() -> {
                        try {
                            barrier.await();
                            for (int i = 0; i < 20; i++) {
                                String name = "shared-" + (i % 5);
                                String value = "thread" + threadIndex + "-round" + i;
                                vault.put(name, value);
                                synchronized (expectedLock) {
                                    expected.put(name, value);
                                }
                                if (i % 3 == 0) {
                                    String removeTarget = "shared-" + ((i + 1) % 5);
                                    boolean removed = vault.remove(removeTarget);
                                    synchronized (expectedLock) {
                                        if (removed) {
                                            expected.remove(removeTarget);
                                        }
                                    }
                                }
                            }
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    }));
                }
                for (var future : futures) {
                    future.get();
                }
            } finally {
                pool.shutdown();
                pool.awaitTermination(10, TimeUnit.SECONDS);
            }

            synchronized (expectedLock) {
                assertEquals(new TreeMap<>(expected).keySet().stream().sorted().toList(),
                        vault.keys());
            }
        }
    }

    @Test
    @Timeout(30)
    void closeDuringConcurrentAccessFailsLoudlyNotSilently(@TempDir Path tempDir)
            throws Exception {
        VaultStore store = VaultStore.forTesting(tempDir, TEST_ITERATIONS);
        OperatorId operator = OperatorId.of("nishat");
        Vault vault = store.create(operator, VaultFixtures.VALID_PASSWORD.clone());

        int threads = 6;
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger closedRejections = new AtomicInteger();
        AtomicInteger unexpected = new AtomicInteger();
        CyclicBarrier barrier = new CyclicBarrier(threads + 1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();
            for (int t = 0; t < threads; t++) {
                int index = t;
                futures.add(pool.submit(() -> {
                    try {
                        barrier.await();
                        for (int i = 0; i < 20; i++) {
                            try {
                                vault.put("race-" + index + "-" + i, "value");
                                successes.incrementAndGet();
                            } catch (IllegalStateException closed) {
                                closedRejections.incrementAndGet();
                            } catch (VaultException e) {
                                unexpected.incrementAndGet();
                            }
                        }
                    } catch (Exception e) {
                        unexpected.incrementAndGet();
                    }
                }));
            }
            barrier.await();
            vault.close();
            for (var future : futures) {
                future.get();
            }
        } finally {
            pool.shutdown();
            pool.awaitTermination(10, TimeUnit.SECONDS);
        }

        assertEquals(0, unexpected.get());
        assertTrue(successes.get() + closedRejections.get() > 0);
    }

    private static void runConcurrentPuts(Vault vault) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(THREADS);
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();
            for (int t = 0; t < THREADS; t++) {
                int threadIndex = t;
                futures.add(pool.submit(() -> {
                    try {
                        barrier.await();
                        for (int k = 0; k < KEYS_PER_THREAD; k++) {
                            String name = "t" + threadIndex + "-k" + k;
                            vault.put(name, name + "-value");
                        }
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }));
            }
            for (var future : futures) {
                future.get();
            }
        } finally {
            pool.shutdown();
            pool.awaitTermination(10, TimeUnit.SECONDS);
        }
    }
}
