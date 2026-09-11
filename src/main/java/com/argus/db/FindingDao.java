package com.argus.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Findings of a scan. Stateless and thread-safe; same blocking contract as {@link ScanRepository}.
 *
 * Heterogeneity is handled by the {@code type} discriminator plus nullable type-specific columns
 * (plan §7.3) — NOT by a JSON blob and NOT by a core-side supertype (§7.1).
 */
public final class FindingDao {

    private final Database database;

    public FindingDao(Database database) {
        this.database = Objects.requireNonNull(database, "database must not be null");
    }

    /**
     * Inserts every finding against {@code scanId} in one transaction and returns the count.
     * An empty list is a no-op returning 0 — not an error.
     *
     * @throws PersistenceException if the scan does not exist (foreign key), if the batch
     *                              contains a duplicate identity (§4.1 ux_findings_identity),
     *                              or on any SQL failure. On failure NOTHING is inserted.
     */
    public int insertAll(long scanId, List<NewFinding> findings) throws PersistenceException {
        Objects.requireNonNull(findings, "findings must not be null");
        for (NewFinding finding : findings) {
            Objects.requireNonNull(finding, "findings must not contain null elements");
        }
        if (findings.isEmpty()) {
            return 0;
        }
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                int count = insertAll(connection, scanId, findings);
                connection.commit();
                return count;
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new PersistenceException("inserting findings for scan " + scanId, e);
        }
    }

    /** Findings of one scan in insertion order ({@code ORDER BY id}). Empty list if none. */
    public List<FindingRecord> findByScan(long scanId) throws PersistenceException {
        try (Connection connection = database.openConnection()) {
            return findByScan(connection, scanId);
        } catch (SQLException e) {
            throw new PersistenceException("finding findings for scan " + scanId, e);
        }
    }

    /** Findings of one scan of one type — the "all open ports of scan 7" query. */
    public List<FindingRecord> findByScanAndType(long scanId, FindingType type)
            throws PersistenceException {
        Objects.requireNonNull(type, "type must not be null");
        try (Connection connection = database.openConnection();
                PreparedStatement ps = connection.prepareStatement(
                        "SELECT id, scan_id, type, subject, port, state FROM findings "
                                + "WHERE scan_id = ? AND type = ? ORDER BY id")) {
            ps.setLong(1, scanId);
            ps.setString(2, type.name());
            try (ResultSet rs = ps.executeQuery()) {
                List<FindingRecord> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(readFindingRecord(rs));
                }
                return result;
            }
        } catch (SQLException e) {
            throw new PersistenceException(
                    "finding findings of type " + type + " for scan " + scanId, e);
        }
    }

    // --- package-private, connection-scoped: let ScanRepository compose them into one
    //     transaction without opening a second connection ---

    int insertAll(Connection connection, long scanId, List<NewFinding> findings)
            throws SQLException {
        if (findings.isEmpty()) {
            return 0;
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO findings(scan_id, type, subject, port, state) "
                        + "VALUES (?, ?, ?, ?, ?)")) {
            for (NewFinding finding : findings) {
                ps.setLong(1, scanId);
                ps.setString(2, finding.type().name());
                ps.setString(3, finding.subject());
                if (finding.port() == null) {
                    ps.setNull(4, Types.INTEGER);
                } else {
                    ps.setInt(4, finding.port());
                }
                if (finding.state() == null) {
                    ps.setNull(5, Types.VARCHAR);
                } else {
                    ps.setString(5, finding.state());
                }
                ps.addBatch();
            }
            ps.executeBatch();
        }
        return findings.size();
    }

    List<FindingRecord> findByScan(Connection connection, long scanId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT id, scan_id, type, subject, port, state FROM findings "
                        + "WHERE scan_id = ? ORDER BY id")) {
            ps.setLong(1, scanId);
            try (ResultSet rs = ps.executeQuery()) {
                List<FindingRecord> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(readFindingRecord(rs));
                }
                return result;
            }
        }
    }

    private static FindingRecord readFindingRecord(ResultSet rs) throws SQLException {
        long id = rs.getLong("id");
        long scanId = rs.getLong("scan_id");
        FindingType type = FindingType.valueOf(rs.getString("type"));
        String subject = rs.getString("subject");
        int portValue = rs.getInt("port");
        Integer port = rs.wasNull() ? null : portValue;
        String state = rs.getString("state");
        return new FindingRecord(id, scanId, type, subject, port, state);
    }
}
