package com.argus.db;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * File, URL and connection-level behaviour of {@link Database} (plan §6.2). No {@code Schema} is
 * wired yet at this point in the TDD sequence — every test here works against raw SQL through the
 * package-private {@link Database#openConnection()} seam.
 */
class DatabaseTest {

    @TempDir
    Path tempDir;

    @Test
    void openCreatesTheDatabaseFile() throws Exception {
        Path dbFile = tempDir.resolve("argus.db");
        Database.open(dbFile);
        assertTrue(Files.exists(dbFile), "database file was not created");
        assertTrue(Files.size(dbFile) > 0, "database file is empty");
    }

    @Test
    void openCreatesMissingParentDirectories() throws Exception {
        Path dbFile = tempDir.resolve("a").resolve("b").resolve("argus.db");
        Database.open(dbFile);
        assertTrue(Files.exists(dbFile), "database file was not created under nested parents");
    }

    @Test
    void jdbcUrlIsTheAbsolutePathPrefixedWithJdbcSqlite() throws Exception {
        Path dbFile = tempDir.resolve("argus.db");
        Database database = Database.open(dbFile);
        String url = database.jdbcUrl();
        assertTrue(url.startsWith("jdbc:sqlite:"), "url does not start with jdbc:sqlite: " + url);
        assertTrue(url.contains(dbFile.toAbsolutePath().normalize().toString()),
                "url does not contain the absolute normalised path: " + url);
        assertFalse(url.contains("?"), "url must not contain connection parameters: " + url);
    }

    @Test
    void openRejectsNullPath() {
        assertThrows(NullPointerException.class, () -> Database.open(null));
    }

    @Test
    void reopeningAnExistingFileKeepsItsData() throws Exception {
        Path dbFile = tempDir.resolve("argus.db");
        Database first = Database.open(dbFile);
        try (Connection c = first.openConnection();
                Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS probe (id INTEGER PRIMARY KEY, val TEXT)");
            st.execute("INSERT INTO probe(val) VALUES ('hello')");
        }

        Database second = Database.open(dbFile);
        try (Connection c = second.openConnection();
                Statement st = c.createStatement();
                ResultSet rs = st.executeQuery("SELECT val FROM probe")) {
            assertTrue(rs.next(), "expected the previously inserted row to survive reopen");
            assertEquals("hello", rs.getString("val"));
        }
    }

    @Test
    void everyConnectionEnforcesForeignKeys() throws Exception {
        Database database = TempDatabases.open(tempDir);
        try (Connection c = database.openConnection();
                Statement st = c.createStatement();
                ResultSet rs = st.executeQuery("PRAGMA foreign_keys")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1));
        }
    }

    @Test
    void theDriverIsAutoRegistered() throws Exception {
        Database database = TempDatabases.open(tempDir);
        assertDoesNotThrow(() -> {
            try (Connection c = database.openConnection()) {
                assertFalse(c.isClosed());
            }
        });
    }
}
