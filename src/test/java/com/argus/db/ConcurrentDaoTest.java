package com.argus.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Regression guard, not exploration (plan §6.9): {@link Database}, {@link ScanRepository} and
 * {@link FindingDao} hold only immutable fields and open a fresh connection per call, so
 * instances are safe to share across threads with no synchronisation. These tests fail if a
 * later "optimisation" caches a shared {@code Connection}.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class ConcurrentDaoTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @TempDir
    Path tempDir;

    @Test
    void twoThreadsWritingDifferentScansThroughOneDaoBothSucceed() throws Exception {
        Database database = TempDatabases.open(tempDir);
        ScanRepository scanRepository = new ScanRepository(database);
        FindingDao findingDao = new FindingDao(database);

        long scanId1 = scanRepository.insert(NewScan.running("a.example.com", NOW)).id();
        long scanId2 = scanRepository.insert(NewScan.running("b.example.com", NOW)).id();

        AtomicReference<Exception> failure1 = new AtomicReference<>();
        AtomicReference<Exception> failure2 = new AtomicReference<>();

        Thread t1 = new Thread(() -> {
            try {
                findingDao.insertAll(scanId1, portFindings(50));
            } catch (Exception e) {
                failure1.set(e);
            }
        });
        Thread t2 = new Thread(() -> {
            try {
                findingDao.insertAll(scanId2, portFindings(50));
            } catch (Exception e) {
                failure2.set(e);
            }
        });

        t1.start();
        t2.start();
        t1.join(10_000);
        t2.join(10_000);

        assertNull(failure1.get(), "thread 1 failed: " + failure1.get());
        assertNull(failure2.get(), "thread 2 failed: " + failure2.get());
        assertEquals(50, findingDao.findByScan(scanId1).size());
        assertEquals(50, findingDao.findByScan(scanId2).size());
    }

    @Test
    void aReadDuringAWriteSeesAConsistentScan() throws Exception {
        Database database = TempDatabases.open(tempDir);
        ScanRepository scanRepository = new ScanRepository(database);

        int sessionsToWrite = 20;
        int findingsPerSession = 30;
        CountDownLatch writerDone = new CountDownLatch(1);
        AtomicReference<Exception> writerFailure = new AtomicReference<>();
        AtomicReference<AssertionError> readerFailure = new AtomicReference<>();

        Thread writer = new Thread(() -> {
            try {
                for (int i = 0; i < sessionsToWrite; i++) {
                    scanRepository.saveSession(
                            NewScan.running("scan-" + i + ".example.com", NOW.plusSeconds(i)),
                            portFindings(findingsPerSession));
                }
            } catch (Exception e) {
                writerFailure.set(e);
            } finally {
                writerDone.countDown();
            }
        });

        Thread reader = new Thread(() -> {
            try {
                while (writerDone.getCount() > 0) {
                    for (ScanRecord scan : scanRepository.findAll()) {
                        ScanSession session = scanRepository.loadSession(scan.id()).orElseThrow();
                        if (session.findings().size() != findingsPerSession) {
                            readerFailure.set(new AssertionError(
                                    "inconsistent scan " + scan.id() + ": "
                                            + session.findings().size() + " findings"));
                            return;
                        }
                    }
                }
            } catch (Exception e) {
                readerFailure.set(new AssertionError(e));
            }
        });

        reader.start();
        writer.start();
        writer.join(20_000);
        reader.join(20_000);

        assertNull(writerFailure.get(), "writer failed: " + writerFailure.get());
        assertNull(readerFailure.get());
    }

    @Test
    void nThreadsInsertingAnnotationsAgainstTheSameFindingAllSucceed() throws Exception {
        Database database = TempDatabases.open(tempDir);
        ScanRepository scanRepository = new ScanRepository(database);
        FindingDao findingDao = new FindingDao(database);
        AnnotationDao annotationDao = new AnnotationDao(database);

        long scanId = scanRepository.insert(NewScan.running("example.com", NOW)).id();
        findingDao.insertAll(scanId, List.of(NewFinding.port("host", 80, "OPEN")));
        long findingId = findingDao.findByScan(scanId).get(0).id();

        int threadCount = 8;
        List<Thread> threads = new ArrayList<>();
        List<AtomicReference<Exception>> failures = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            int index = i;
            AtomicReference<Exception> failure = new AtomicReference<>();
            failures.add(failure);
            Thread thread = new Thread(() -> {
                try {
                    annotationDao.insert(findingId, new NewAnnotation("note " + index, NOW));
                } catch (Exception e) {
                    failure.set(e);
                }
            });
            threads.add(thread);
        }

        for (Thread thread : threads) {
            thread.start();
        }
        for (Thread thread : threads) {
            thread.join(10_000);
        }

        for (AtomicReference<Exception> failure : failures) {
            assertNull(failure.get(), "a thread failed: " + failure.get());
        }
        assertEquals(threadCount, annotationDao.findByFinding(findingId).size());
    }

    @Test
    void nThreadsAssigningTheSameTagNameToTheSameFindingAllSucceedAndLeaveExactlyOneTagRow()
            throws Exception {
        Database database = TempDatabases.open(tempDir);
        ScanRepository scanRepository = new ScanRepository(database);
        FindingDao findingDao = new FindingDao(database);
        TagDao tagDao = new TagDao(database);

        long scanId = scanRepository.insert(NewScan.running("example.com", NOW)).id();
        findingDao.insertAll(scanId, List.of(NewFinding.port("host", 80, "OPEN")));
        long findingId = findingDao.findByScan(scanId).get(0).id();

        int threadCount = 8;
        List<Thread> threads = new ArrayList<>();
        List<AtomicReference<Exception>> failures = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            AtomicReference<Exception> failure = new AtomicReference<>();
            failures.add(failure);
            Thread thread = new Thread(() -> {
                try {
                    tagDao.assign(findingId, new NewTag("prod"));
                } catch (Exception e) {
                    failure.set(e);
                }
            });
            threads.add(thread);
        }

        for (Thread thread : threads) {
            thread.start();
        }
        for (Thread thread : threads) {
            thread.join(10_000);
        }

        for (AtomicReference<Exception> failure : failures) {
            assertNull(failure.get(), "a thread failed: " + failure.get());
        }
        assertEquals(1, tagDao.findByFinding(findingId).size());
    }

    private static List<NewFinding> portFindings(int count) {
        List<NewFinding> findings = new ArrayList<>();
        for (int port = 1; port <= count; port++) {
            findings.add(NewFinding.port("host", port, "OPEN"));
        }
        return findings;
    }
}
