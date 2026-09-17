package com.argus.db;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * "Resources always closed" (plan §6.7): every public method opens its own {@code Connection}
 * and closes it before returning, on every path including exceptions. Uses
 * {@link RecordingConnectionFactory} to assert the real property rather than inspecting source.
 */
class ResourceClosureTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @TempDir
    Path tempDir;

    private RecordingConnectionFactory recordingFactory;
    private ScanRepository scanRepository;
    private FindingDao findingDao;
    private AnnotationDao annotationDao;
    private TagDao tagDao;

    @BeforeEach
    void setUp() throws Exception {
        Path dbFile = tempDir.resolve("argus.db").toAbsolutePath().normalize();
        Database.open(dbFile); // creates the file and the schema up front

        String jdbcUrl = "jdbc:sqlite:" + dbFile;
        recordingFactory = new RecordingConnectionFactory(new SqliteConnectionFactory(jdbcUrl));
        Database database = Database.usingFactory(recordingFactory, dbFile);
        scanRepository = new ScanRepository(database);
        findingDao = new FindingDao(database);
        annotationDao = new AnnotationDao(database);
        tagDao = new TagDao(database);
    }

    @Test
    void everyReadClosesItsConnection() throws Exception {
        ScanRecord scan = scanRepository.insert(NewScan.running("example.com", NOW));
        findingDao.insertAll(scan.id(), List.of(NewFinding.port("host", 80, "OPEN")));
        long findingId = findingDao.findByScan(scan.id()).get(0).id();
        annotationDao.insert(findingId, new NewAnnotation("note", NOW));

        scanRepository.findById(scan.id());
        scanRepository.findAll();
        findingDao.findByScan(scan.id());
        annotationDao.findByFinding(findingId);
        annotationDao.findByScan(scan.id());
        tagDao.findByFinding(findingId);
        tagDao.findByScan(scan.id());

        assertTrue(recordingFactory.allClosed());
        assertTrue(recordingFactory.openedCount() > 0);
    }

    @Test
    void everyWriteClosesItsConnection() throws Exception {
        ScanRecord scan = scanRepository.insert(NewScan.running("example.com", NOW));
        findingDao.insertAll(scan.id(), List.of(NewFinding.port("host", 80, "OPEN")));
        scanRepository.saveSession(NewScan.running("example.org", NOW), List.of());
        scanRepository.finish(scan.id(), NOW.plusSeconds(1), ScanStatus.COMPLETED);
        long findingId = findingDao.findByScan(scan.id()).get(0).id();
        AnnotationRecord annotation = annotationDao.insert(findingId, new NewAnnotation("note", NOW));
        annotationDao.deleteById(annotation.id());
        FindingTagRecord tag = tagDao.assign(findingId, new NewTag("prod"));
        tagDao.unassign(findingId, tag.tagId());

        assertTrue(recordingFactory.allClosed());
    }

    @Test
    void aFailedWriteStillClosesItsConnection() {
        assertThrows(PersistenceException.class, () -> findingDao.insertAll(
                9999L, List.of(NewFinding.port("host", 80, "OPEN"))));

        assertTrue(assertDoesNotThrowSql(recordingFactory::allClosed));
    }

    @Test
    void aFailedAnnotationInsertStillClosesItsConnection() {
        assertThrows(PersistenceException.class,
                () -> annotationDao.insert(9999L, new NewAnnotation("note", NOW)));

        assertTrue(assertDoesNotThrowSql(recordingFactory::allClosed));
    }

    @Test
    void aFailedAssignStillClosesItsConnection() {
        assertThrows(PersistenceException.class,
                () -> tagDao.assign(9999L, new NewTag("prod")));

        assertTrue(assertDoesNotThrowSql(recordingFactory::allClosed));
    }

    @Test
    void aFailedSessionSaveStillClosesItsConnection() {
        List<NewFinding> duplicateBatch = List.of(
                NewFinding.port("host", 80, "OPEN"),
                NewFinding.port("host", 80, "CLOSED"));

        assertThrows(PersistenceException.class, () -> scanRepository.saveSession(
                NewScan.running("example.com", NOW), duplicateBatch));

        assertTrue(assertDoesNotThrowSql(recordingFactory::allClosed));
    }

    @Test
    void saveSessionUsesExactlyOneConnection() throws Exception {
        scanRepository.saveSession(NewScan.running("example.com", NOW),
                List.of(NewFinding.port("host", 80, "OPEN")));

        assertEquals(1, recordingFactory.openedCount());
    }

    @FunctionalInterface
    private interface SqlSupplier {
        boolean get() throws java.sql.SQLException;
    }

    private static boolean assertDoesNotThrowSql(SqlSupplier supplier) {
        try {
            return supplier.get();
        } catch (java.sql.SQLException e) {
            throw new AssertionError(e);
        }
    }
}
