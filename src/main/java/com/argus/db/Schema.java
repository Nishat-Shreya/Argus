package com.argus.db;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Package-private holder for the DDL script and its execution. DDL text lives here and nowhere
 * else — no SQL string is duplicated in a DAO (plan §3.2/§4.1).
 */
final class Schema {

    static final int VERSION = 1;

    private Schema() {}

    /**
     * Idempotent: {@code CREATE TABLE}/{@code CREATE INDEX ... IF NOT EXISTS} for all six
     * tables, run in one transaction, followed by stamping {@code PRAGMA user_version}
     * (executed outside the transaction — plan §7.7).
     */
    static void createIfAbsent(Connection connection) throws SQLException {
        connection.setAutoCommit(false);
        try {
            try (Statement statement = connection.createStatement()) {
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS scans (
                            id          INTEGER PRIMARY KEY,
                            target      TEXT    NOT NULL,
                            started_at  INTEGER NOT NULL,
                            finished_at INTEGER,
                            status      TEXT    NOT NULL,
                            CHECK (length(target) > 0),
                            CHECK (finished_at IS NULL OR finished_at >= started_at)
                        )
                        """);

                statement.execute("""
                        CREATE TABLE IF NOT EXISTS findings (
                            id      INTEGER PRIMARY KEY,
                            scan_id INTEGER NOT NULL REFERENCES scans(id) ON DELETE CASCADE,
                            type    TEXT    NOT NULL,
                            subject TEXT    NOT NULL,
                            port    INTEGER,
                            state   TEXT,
                            CHECK (length(subject) > 0),
                            CHECK (port IS NULL OR (port BETWEEN 1 AND 65535)),
                            CHECK (type <> 'PORT'      OR (port IS NOT NULL AND state IS NOT NULL)),
                            CHECK (type <> 'SUBDOMAIN' OR (port IS NULL     AND state IS NULL))
                        )
                        """);

                statement.execute("""
                        CREATE UNIQUE INDEX IF NOT EXISTS ux_findings_identity
                            ON findings (scan_id, type, subject, ifnull(port, -1))
                        """);

                statement.execute("""
                        CREATE TABLE IF NOT EXISTS annotations (
                            id         INTEGER PRIMARY KEY,
                            finding_id INTEGER NOT NULL REFERENCES findings(id) ON DELETE CASCADE,
                            body       TEXT    NOT NULL,
                            created_at INTEGER NOT NULL,
                            CHECK (length(body) > 0)
                        )
                        """);
                statement.execute(
                        "CREATE INDEX IF NOT EXISTS ix_annotations_finding "
                                + "ON annotations (finding_id)");

                statement.execute("""
                        CREATE TABLE IF NOT EXISTS tags (
                            id   INTEGER PRIMARY KEY,
                            name TEXT NOT NULL COLLATE NOCASE UNIQUE,
                            CHECK (length(name) > 0)
                        )
                        """);

                statement.execute("""
                        CREATE TABLE IF NOT EXISTS finding_tags (
                            finding_id INTEGER NOT NULL REFERENCES findings(id) ON DELETE CASCADE,
                            tag_id     INTEGER NOT NULL REFERENCES tags(id)     ON DELETE CASCADE,
                            PRIMARY KEY (finding_id, tag_id)
                        )
                        """);
                statement.execute(
                        "CREATE INDEX IF NOT EXISTS ix_finding_tags_tag "
                                + "ON finding_tags (tag_id)");

                statement.execute("""
                        CREATE TABLE IF NOT EXISTS scheduled_scans (
                            id              INTEGER PRIMARY KEY,
                            target          TEXT    NOT NULL,
                            cron_expression TEXT    NOT NULL,
                            enabled         INTEGER NOT NULL DEFAULT 1 CHECK (enabled IN (0, 1)),
                            created_at      INTEGER NOT NULL,
                            last_run_at     INTEGER,
                            next_run_at     INTEGER,
                            CHECK (length(target) > 0),
                            CHECK (length(cron_expression) > 0)
                        )
                        """);
            }
            connection.commit();
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(true);
        }

        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA user_version = " + VERSION);
        }
    }
}
