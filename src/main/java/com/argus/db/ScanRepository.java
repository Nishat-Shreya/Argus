package com.argus.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Scan sessions. Stateless and thread-safe: one connection per operation, never shared, never
 * cached (plan §4.3). Instances are cheap; create one and reuse it.
 *
 * BLOCKING: every method performs file I/O. Never call one on the JavaFX Application Thread
 * (invariant 3) and never while holding the {@code ScanPipeline} lock (P1-03 §4.3.5, SEI CERT
 * LCK09-J).
 */
public final class ScanRepository {

    private final Database database;
    private final FindingDao findingDao;

    public ScanRepository(Database database) {
        this.database = Objects.requireNonNull(database, "database must not be null");
        this.findingDao = new FindingDao(database);
    }

    /** Inserts one {@code scans} row and returns it with the generated id. */
    public ScanRecord insert(NewScan scan) throws PersistenceException {
        Objects.requireNonNull(scan, "scan must not be null");
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                ScanRecord inserted = insert(connection, scan);
                connection.commit();
                return inserted;
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new PersistenceException("inserting scan for target " + scan.target(), e);
        }
    }

    /**
     * Marks a running scan finished. Terminal status only.
     *
     * @throws IllegalArgumentException if status is RUNNING, finishedAt is null, or
     *                                  finishedAt precedes the stored startedAt
     * @throws PersistenceException     if no scan has that id, or on a SQL failure
     */
    public ScanRecord finish(long scanId, Instant finishedAt, ScanStatus status)
            throws PersistenceException {
        Objects.requireNonNull(finishedAt, "finishedAt must not be null");
        Objects.requireNonNull(status, "status must not be null");
        if (status == ScanStatus.RUNNING) {
            throw new IllegalArgumentException("finish() requires a terminal status, got RUNNING");
        }

        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                ScanRecord existing = findById(connection, scanId)
                        .orElseThrow(() -> new PersistenceException(
                                "no scan found with id " + scanId));
                if (finishedAt.isBefore(existing.startedAt())) {
                    throw new IllegalArgumentException(
                            "finishedAt must not precede the stored startedAt: startedAt="
                                    + existing.startedAt() + ", finishedAt=" + finishedAt);
                }
                try (PreparedStatement ps = connection.prepareStatement(
                        "UPDATE scans SET finished_at = ?, status = ? WHERE id = ?")) {
                    ps.setLong(1, finishedAt.toEpochMilli());
                    ps.setString(2, status.name());
                    ps.setLong(3, scanId);
                    ps.executeUpdate();
                }
                connection.commit();
                return new ScanRecord(
                        scanId, existing.target(), existing.startedAt(), finishedAt, status);
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new PersistenceException("finishing scan " + scanId, e);
        }
    }

    /**
     * THE acceptance-criteria method: one {@code scans} row + all its findings in a SINGLE
     * transaction. Either everything is stored or nothing is. Returns the persisted session with
     * all ids assigned.
     */
    public ScanSession saveSession(NewScan scan, List<NewFinding> findings)
            throws PersistenceException {
        Objects.requireNonNull(scan, "scan must not be null");
        Objects.requireNonNull(findings, "findings must not be null");
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                ScanRecord scanRecord = insert(connection, scan);
                findingDao.insertAll(connection, scanRecord.id(), findings);
                List<FindingRecord> findingRecords =
                        findingDao.findByScan(connection, scanRecord.id());
                connection.commit();
                return new ScanSession(scanRecord, findingRecords);
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new PersistenceException("saving scan session for target " + scan.target(), e);
        }
    }

    /** The scan and all of its findings, eagerly loaded on one connection (§7.8). Empty if no
     *  such scan. */
    public Optional<ScanSession> loadSession(long scanId) throws PersistenceException {
        try (Connection connection = database.openConnection()) {
            Optional<ScanRecord> scanRecord = findById(connection, scanId);
            if (scanRecord.isEmpty()) {
                return Optional.empty();
            }
            List<FindingRecord> findingRecords = findingDao.findByScan(connection, scanId);
            return Optional.of(new ScanSession(scanRecord.get(), findingRecords));
        } catch (SQLException e) {
            throw new PersistenceException("loading scan session " + scanId, e);
        }
    }

    public Optional<ScanRecord> findById(long scanId) throws PersistenceException {
        try (Connection connection = database.openConnection()) {
            return findById(connection, scanId);
        } catch (SQLException e) {
            throw new PersistenceException("finding scan " + scanId, e);
        }
    }

    /** All scans, newest first ({@code ORDER BY started_at DESC, id DESC}). The history list
     *  P1-06 and P2-09 read. */
    public List<ScanRecord> findAll() throws PersistenceException {
        try (Connection connection = database.openConnection();
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(
                        "SELECT id, target, started_at, finished_at, status FROM scans "
                                + "ORDER BY started_at DESC, id DESC")) {
            List<ScanRecord> result = new ArrayList<>();
            while (rs.next()) {
                result.add(readScanRecord(rs));
            }
            return result;
        } catch (SQLException e) {
            throw new PersistenceException("finding all scans", e);
        }
    }

    // --- connection-scoped helpers -------------------------------------------------------

    private ScanRecord insert(Connection connection, NewScan scan) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO scans(target, started_at, finished_at, status) VALUES (?, ?, ?, ?)")) {
            ps.setString(1, scan.target());
            ps.setLong(2, scan.startedAt().toEpochMilli());
            if (scan.finishedAt() == null) {
                ps.setNull(3, Types.INTEGER);
            } else {
                ps.setLong(3, scan.finishedAt().toEpochMilli());
            }
            ps.setString(4, scan.status().name());
            ps.executeUpdate();
        }
        long id = lastInsertRowId(connection);
        return new ScanRecord(id, scan.target(), scan.startedAt(), scan.finishedAt(), scan.status());
    }

    private Optional<ScanRecord> findById(Connection connection, long scanId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT id, target, started_at, finished_at, status FROM scans WHERE id = ?")) {
            ps.setLong(1, scanId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(readScanRecord(rs));
            }
        }
    }

    private static ScanRecord readScanRecord(ResultSet rs) throws SQLException {
        long id = rs.getLong("id");
        String target = rs.getString("target");
        Instant startedAt = Instant.ofEpochMilli(rs.getLong("started_at"));
        long finishedAtMillis = rs.getLong("finished_at");
        Instant finishedAt = rs.wasNull() ? null : Instant.ofEpochMilli(finishedAtMillis);
        ScanStatus status = ScanStatus.valueOf(rs.getString("status"));
        return new ScanRecord(id, target, startedAt, finishedAt, status);
    }

    private static long lastInsertRowId(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("SELECT last_insert_rowid()")) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
