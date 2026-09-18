package com.argus.db;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * DDL, idempotency and constraint tests for {@link Schema} (plan §6.3). Exercises the schema
 * with raw SQL through {@link Database#openConnection()} — no {@code ScanRepository} or
 * {@code FindingDao} exists yet at this point in the TDD sequence.
 */
class SchemaTest {

    @TempDir
    Path tempDir;

    private Database database;

    @BeforeEach
    void setUp() throws Exception {
        database = TempDatabases.open(tempDir);
    }

    @Test
    void allSevenTablesExist() throws Exception {
        Set<String> expected = Set.of(
                "scans", "findings", "annotations", "tags", "finding_tags", "scheduled_scans",
                "scan_intel");
        Set<String> actual = new HashSet<>();
        try (Connection c = database.openConnection();
                Statement st = c.createStatement();
                ResultSet rs = st.executeQuery(
                        "SELECT name FROM sqlite_master WHERE type = 'table'")) {
            while (rs.next()) {
                actual.add(rs.getString("name"));
            }
        }
        assertTrue(actual.containsAll(expected), "missing tables, found: " + actual);
    }

    @Test
    void findingIdentityIndexExists() throws Exception {
        try (Connection c = database.openConnection();
                Statement st = c.createStatement();
                ResultSet rs = st.executeQuery(
                        "SELECT name FROM sqlite_master WHERE type = 'index' "
                                + "AND name = 'ux_findings_identity'")) {
            assertTrue(rs.next(), "ux_findings_identity index not found");
        }
    }

    @Test
    void initializeSchemaIsIdempotent() {
        assertDoesNotThrow(() -> {
            database.initializeSchema();
            database.initializeSchema();
            database.initializeSchema();
        });
    }

    @Test
    void initializeSchemaPreservesExistingRows() throws Exception {
        try (Connection c = database.openConnection();
                Statement st = c.createStatement()) {
            st.execute("INSERT INTO scans(target, started_at, status) "
                    + "VALUES ('example.com', 1000, 'RUNNING')");
        }

        database.initializeSchema();

        try (Connection c = database.openConnection();
                Statement st = c.createStatement();
                ResultSet rs = st.executeQuery("SELECT target FROM scans")) {
            assertTrue(rs.next(), "row inserted before re-init disappeared");
            assertEquals("example.com", rs.getString("target"));
        }
    }

    @Test
    void schemaVersionIsStamped() throws Exception {
        assertEquals(Schema.VERSION, database.schemaVersion());
        assertEquals(1, database.schemaVersion());
    }

    @Test
    void findingsRowRequiresAnExistingScan() throws Exception {
        try (Connection c = database.openConnection();
                Statement st = c.createStatement()) {
            assertThrows(SQLException.class, () -> st.execute(
                    "INSERT INTO findings(scan_id, type, subject, port, state) "
                            + "VALUES (9999, 'PORT', 'host', 80, 'OPEN')"));
        }
    }

    @Test
    void deletingAScanCascadesToItsFindings() throws Exception {
        try (Connection c = database.openConnection();
                Statement st = c.createStatement()) {
            st.execute("INSERT INTO scans(target, started_at, status) "
                    + "VALUES ('example.com', 1000, 'RUNNING')");
            st.execute("INSERT INTO findings(scan_id, type, subject, port, state) "
                    + "VALUES (1, 'PORT', 'host', 80, 'OPEN')");
            st.execute("DELETE FROM scans WHERE id = 1");

            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM findings")) {
                rs.next();
                assertEquals(0, rs.getInt(1));
            }
        }
    }

    @Test
    void deletingAFindingCascadesToAnnotationsAndTagLinks() throws Exception {
        try (Connection c = database.openConnection();
                Statement st = c.createStatement()) {
            st.execute("INSERT INTO scans(target, started_at, status) "
                    + "VALUES ('example.com', 1000, 'RUNNING')");
            st.execute("INSERT INTO findings(scan_id, type, subject, port, state) "
                    + "VALUES (1, 'PORT', 'host', 80, 'OPEN')");
            st.execute("INSERT INTO annotations(finding_id, body, created_at) "
                    + "VALUES (1, 'note', 1000)");
            st.execute("INSERT INTO tags(name) VALUES ('prod')");
            st.execute("INSERT INTO finding_tags(finding_id, tag_id) VALUES (1, 1)");

            st.execute("DELETE FROM findings WHERE id = 1");

            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM annotations")) {
                rs.next();
                assertEquals(0, rs.getInt(1));
            }
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM finding_tags")) {
                rs.next();
                assertEquals(0, rs.getInt(1));
            }
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM tags")) {
                rs.next();
                assertEquals(1, rs.getInt(1));
            }
        }
    }

    @Test
    void portFindingWithoutPortOrStateIsRejected() throws Exception {
        try (Connection c = database.openConnection();
                Statement st = c.createStatement()) {
            st.execute("INSERT INTO scans(target, started_at, status) "
                    + "VALUES ('example.com', 1000, 'RUNNING')");
            assertThrows(SQLException.class, () -> st.execute(
                    "INSERT INTO findings(scan_id, type, subject, port, state) "
                            + "VALUES (1, 'PORT', 'host', NULL, NULL)"));
        }
    }

    @Test
    void subdomainFindingWithAPortIsRejected() throws Exception {
        try (Connection c = database.openConnection();
                Statement st = c.createStatement()) {
            st.execute("INSERT INTO scans(target, started_at, status) "
                    + "VALUES ('example.com', 1000, 'RUNNING')");
            assertThrows(SQLException.class, () -> st.execute(
                    "INSERT INTO findings(scan_id, type, subject, port, state) "
                            + "VALUES (1, 'SUBDOMAIN', 'a.example.com', 80, NULL)"));
        }
    }

    @Test
    void duplicateFindingIdentityIsRejected() throws Exception {
        try (Connection c = database.openConnection();
                Statement st = c.createStatement()) {
            st.execute("INSERT INTO scans(target, started_at, status) "
                    + "VALUES ('example.com', 1000, 'RUNNING')");
            st.execute("INSERT INTO findings(scan_id, type, subject, port, state) "
                    + "VALUES (1, 'PORT', 'host', 80, 'OPEN')");
            assertThrows(SQLException.class, () -> st.execute(
                    "INSERT INTO findings(scan_id, type, subject, port, state) "
                            + "VALUES (1, 'PORT', 'host', 80, 'CLOSED')"));

            st.execute("INSERT INTO findings(scan_id, type, subject, port, state) "
                    + "VALUES (1, 'SUBDOMAIN', 'a.example.com', NULL, NULL)");
            assertThrows(SQLException.class, () -> st.execute(
                    "INSERT INTO findings(scan_id, type, subject, port, state) "
                            + "VALUES (1, 'SUBDOMAIN', 'a.example.com', NULL, NULL)"));
        }
    }

    @Test
    void tagNamesAreUniqueCaseInsensitively() throws Exception {
        try (Connection c = database.openConnection();
                Statement st = c.createStatement()) {
            st.execute("INSERT INTO tags(name) VALUES ('prod')");
            assertThrows(SQLException.class,
                    () -> st.execute("INSERT INTO tags(name) VALUES ('PROD')"));
        }
    }

    @Test
    void deletingAScanCascadesToItsScanIntelRow() throws Exception {
        try (Connection c = database.openConnection();
                Statement st = c.createStatement()) {
            st.execute("INSERT INTO scans(target, started_at, status) "
                    + "VALUES ('example.com', 1000, 'RUNNING')");
            st.execute("INSERT INTO scan_intel(scan_id, summary, kev_matched, created_at) "
                    + "VALUES (1, 'virustotal: ok', 0, 1000)");
            st.execute("DELETE FROM scans WHERE id = 1");

            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM scan_intel")) {
                rs.next();
                assertEquals(0, rs.getInt(1));
            }
        }
    }

    @Test
    void scanIntelScanIdIsUnique() throws Exception {
        try (Connection c = database.openConnection();
                Statement st = c.createStatement()) {
            st.execute("INSERT INTO scans(target, started_at, status) "
                    + "VALUES ('example.com', 1000, 'RUNNING')");
            st.execute("INSERT INTO scan_intel(scan_id, summary, kev_matched, created_at) "
                    + "VALUES (1, 'virustotal: ok', 0, 1000)");
            assertThrows(SQLException.class, () -> st.execute(
                    "INSERT INTO scan_intel(scan_id, summary, kev_matched, created_at) "
                            + "VALUES (1, 'another summary', 0, 1001)"));
        }
    }
}
