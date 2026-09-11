package com.argus.db;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * {@code DriverManager} plus the two per-connection pragmas of plan §4.2, issued as plain SQL
 * (no {@code org.sqlite.*} import, no {@code SQLiteConfig}): {@code PRAGMA foreign_keys = ON}
 * then {@code PRAGMA busy_timeout = 5000}, set immediately after {@code getConnection()} and
 * before any transaction starts — foreign key enforcement cannot be toggled mid-transaction.
 */
final class SqliteConnectionFactory implements ConnectionFactory {

    private final String jdbcUrl;

    SqliteConnectionFactory(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
    }

    @Override
    public Connection open() throws SQLException {
        Connection connection = DriverManager.getConnection(jdbcUrl);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA busy_timeout = 5000");
        } catch (SQLException e) {
            connection.close();
            throw e;
        }
        return connection;
    }
}
