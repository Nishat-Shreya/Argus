package com.argus.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Intel/KEV enrichment results, one per scan (P3-16). Stateless and thread-safe; same BLOCKING
 * contract as the other DAOs in this package -- never call one on the FX thread.
 */
public final class IntelResultDao {

    private final Database database;

    public IntelResultDao(Database database) {
        this.database = Objects.requireNonNull(database, "database must not be null");
    }

    /**
     * Upserts the one intel result row for {@code scanId} -- {@code scan_id} is UNIQUE, so a
     * second save for the same scan replaces the first rather than accumulating rows (plan:
     * a scan's enrichment is a single current snapshot, not a history).
     *
     * @throws PersistenceException if no scans row has that id (FK), or on any SQL failure
     */
    public IntelResultRecord save(long scanId, NewIntelResult result) throws PersistenceException {
        Objects.requireNonNull(result, "result must not be null");
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement delete = connection.prepareStatement(
                        "DELETE FROM scan_intel WHERE scan_id = ?")) {
                    delete.setLong(1, scanId);
                    delete.executeUpdate();
                }
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO scan_intel(scan_id, summary, kev_matched, created_at) "
                                + "VALUES (?, ?, ?, ?)")) {
                    insert.setLong(1, scanId);
                    insert.setString(2, result.summary());
                    insert.setInt(3, result.kevMatched() ? 1 : 0);
                    insert.setLong(4, result.createdAt().toEpochMilli());
                    insert.executeUpdate();
                }
                long id = lastInsertRowId(connection);
                connection.commit();
                return new IntelResultRecord(id, scanId, result.summary(), result.kevMatched(),
                        result.createdAt());
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new PersistenceException("saving intel result for scan " + scanId, e);
        }
    }

    /** The one intel result row for {@code scanId}, if any. */
    public Optional<IntelResultRecord> findByScan(long scanId) throws PersistenceException {
        try (Connection connection = database.openConnection();
                PreparedStatement ps = connection.prepareStatement(
                        "SELECT id, scan_id, summary, kev_matched, created_at FROM scan_intel "
                                + "WHERE scan_id = ?")) {
            ps.setLong(1, scanId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(readIntelResultRecord(rs));
            }
        } catch (SQLException e) {
            throw new PersistenceException("finding intel result for scan " + scanId, e);
        }
    }

    private static IntelResultRecord readIntelResultRecord(ResultSet rs) throws SQLException {
        long id = rs.getLong("id");
        long scanId = rs.getLong("scan_id");
        String summary = rs.getString("summary");
        boolean kevMatched = rs.getInt("kev_matched") != 0;
        Instant createdAt = Instant.ofEpochMilli(rs.getLong("created_at"));
        return new IntelResultRecord(id, scanId, summary, kevMatched, createdAt);
    }

    /** Duplicated (~6 lines) rather than widened out of another DAO -- the {@code AnnotationDao}
     *  precedent (P3-06 R5). A plain INSERT (no {@code OR IGNORE}), so {@code
     *  last_insert_rowid()} is safe here. */
    private static long lastInsertRowId(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("SELECT last_insert_rowid()")) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
