package com.argus.db;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;

/**
 * One SQLite database file. Immutable and thread-safe: it holds no {@code Connection} and no
 * other resource, so it has NO {@code close()} and is deliberately not {@code AutoCloseable}
 * (plan §4.3) — every resource in this package lives inside a single method's
 * try-with-resources.
 */
public final class Database {

    private final ConnectionFactory factory;
    private final Path file;

    private Database(ConnectionFactory factory, Path file) {
        this.factory = factory;
        this.file = file;
    }

    /**
     * Opens (creating if absent) the database at {@code file}, creates missing parent
     * directories, and runs {@link #initializeSchema()}.
     *
     * @throws NullPointerException if file is null
     * @throws PersistenceException if the directory cannot be created, the driver is missing,
     *                              or DDL fails
     */
    public static Database open(Path file) throws PersistenceException {
        Objects.requireNonNull(file, "file must not be null");
        Path absolute = file.toAbsolutePath().normalize();
        try {
            Path parent = absolute.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (IOException e) {
            throw new PersistenceException("creating parent directories for " + absolute, e);
        }

        String jdbcUrl = "jdbc:sqlite:" + absolute;
        Database database = usingFactory(new SqliteConnectionFactory(jdbcUrl), absolute);
        database.initializeSchema();
        return database;
    }

    /** Idempotent. Executes the CREATE TABLE / CREATE INDEX IF NOT EXISTS script (§4.1) in one
     *  transaction and stamps {@code PRAGMA user_version}. Safe to call any number of times. */
    public void initializeSchema() throws PersistenceException {
        try (Connection connection = openConnection()) {
            Schema.createIfAbsent(connection);
        } catch (SQLException e) {
            throw new PersistenceException("initializing schema for " + file, e);
        }
    }

    /** {@code "jdbc:sqlite:" + absolute normalized path}. Exposed for diagnostics and tests. */
    public String jdbcUrl() {
        return "jdbc:sqlite:" + file;
    }

    public Path file() {
        return file;
    }

    /** {@code PRAGMA user_version} — the migration hook (§7.7, R5). */
    public int schemaVersion() throws PersistenceException {
        try (Connection connection = openConnection();
                var statement = connection.createStatement();
                var resultSet = statement.executeQuery("PRAGMA user_version")) {
            resultSet.next();
            return resultSet.getInt(1);
        } catch (SQLException e) {
            throw new PersistenceException("reading schema version for " + file, e);
        }
    }

    /** A fresh connection with foreign keys enforced and a busy timeout set (§4.2).
     *  Callers MUST use try-with-resources. */
    Connection openConnection() throws SQLException {
        return factory.open();
    }

    /** Test seam (§6.7): builds a Database over an injected factory, mirroring P1-01's
     *  SocketConnector and P1-02's HttpFetcher seams. */
    static Database usingFactory(ConnectionFactory factory, Path file) {
        return new Database(factory, file);
    }
}
