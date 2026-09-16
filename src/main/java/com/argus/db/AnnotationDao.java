package com.argus.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Notes on findings (plan §3.1). Stateless and thread-safe; same BLOCKING contract as
 * {@link ScanRepository} / {@link FindingDao} — never call one on the FX thread, never while
 * holding the {@code ScanPipeline} lock.
 *
 * CRUD surface is deliberately create / list-by-finding / list-by-scan / delete — NO update:
 * the {@code annotations} table has no {@code updated_at} column, so an in-place edit would
 * misrepresent when the current text was authored (plan §0.1, §3.1).
 */
public final class AnnotationDao {

    private final Database database;

    public AnnotationDao(Database database) {
        this.database = Objects.requireNonNull(database, "database must not be null");
    }

    /**
     * Appends one note against {@code findingId}, in one transaction, and returns it with the
     * generated id.
     *
     * @throws PersistenceException if no findings row has that id (FK), or on any SQL failure
     * @throws NullPointerException if annotation is null
     */
    public AnnotationRecord insert(long findingId, NewAnnotation annotation)
            throws PersistenceException {
        Objects.requireNonNull(annotation, "annotation must not be null");
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                AnnotationRecord inserted = insert(connection, findingId, annotation);
                connection.commit();
                return inserted;
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new PersistenceException("inserting annotation for finding " + findingId, e);
        }
    }

    /** One finding's notes, oldest first ({@code ORDER BY created_at, id}). Empty if none. */
    public List<AnnotationRecord> findByFinding(long findingId) throws PersistenceException {
        try (Connection connection = database.openConnection();
                PreparedStatement ps = connection.prepareStatement(
                        "SELECT id, finding_id, body, created_at FROM annotations "
                                + "WHERE finding_id = ? ORDER BY created_at, id")) {
            ps.setLong(1, findingId);
            try (ResultSet rs = ps.executeQuery()) {
                List<AnnotationRecord> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(readAnnotationRecord(rs));
                }
                return result;
            }
        } catch (SQLException e) {
            throw new PersistenceException("finding annotations for finding " + findingId, e);
        }
    }

    /**
     * Every note on every finding of one scan — the bulk prefetch the findings-detail screen
     * loads once ({@code JOIN findings ... WHERE findings.scan_id = ?},
     * {@code ORDER BY finding_id, created_at, id}). Empty if none.
     */
    public List<AnnotationRecord> findByScan(long scanId) throws PersistenceException {
        try (Connection connection = database.openConnection();
                PreparedStatement ps = connection.prepareStatement(
                        "SELECT a.id, a.finding_id, a.body, a.created_at FROM annotations a "
                                + "JOIN findings f ON f.id = a.finding_id "
                                + "WHERE f.scan_id = ? "
                                + "ORDER BY a.finding_id, a.created_at, a.id")) {
            ps.setLong(1, scanId);
            try (ResultSet rs = ps.executeQuery()) {
                List<AnnotationRecord> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(readAnnotationRecord(rs));
                }
                return result;
            }
        } catch (SQLException e) {
            throw new PersistenceException("finding annotations for scan " + scanId, e);
        }
    }

    /** Deletes one note. @return true if a row was deleted, false if the id was already absent. */
    public boolean deleteById(long annotationId) throws PersistenceException {
        try (Connection connection = database.openConnection();
                PreparedStatement ps =
                        connection.prepareStatement("DELETE FROM annotations WHERE id = ?")) {
            ps.setLong(1, annotationId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new PersistenceException("deleting annotation " + annotationId, e);
        }
    }

    // --- connection-scoped helper, private: insert is itself multi-statement (INSERT then
    //     SELECT last_insert_rowid()), so it composes inside one transaction (plan §3.1) ---

    private AnnotationRecord insert(Connection connection, long findingId, NewAnnotation annotation)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO annotations(finding_id, body, created_at) VALUES (?, ?, ?)")) {
            ps.setLong(1, findingId);
            ps.setString(2, annotation.body());
            ps.setLong(3, annotation.createdAt().toEpochMilli());
            ps.executeUpdate();
        }
        long id = lastInsertRowId(connection);
        return new AnnotationRecord(id, findingId, annotation.body(), annotation.createdAt());
    }

    private static AnnotationRecord readAnnotationRecord(ResultSet rs) throws SQLException {
        long id = rs.getLong("id");
        long findingId = rs.getLong("finding_id");
        String body = rs.getString("body");
        Instant createdAt = Instant.ofEpochMilli(rs.getLong("created_at"));
        return new AnnotationRecord(id, findingId, body, createdAt);
    }

    /** Duplicated (~6 lines) rather than widened out of {@link ScanRepository} — deliberate,
     *  accepted duplication (plan R5): nothing composes annotations into another DAO's
     *  transaction today, so widening a private helper speculatively would be the YAGNI this
     *  plan refuses. {@code getGeneratedKeys()} is forbidden (P1-04). */
    private static long lastInsertRowId(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("SELECT last_insert_rowid()")) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
