package com.argus.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Custom tags on findings (plan §3.1). Stateless and thread-safe; same BLOCKING contract as
 * {@link ScanRepository} / {@link FindingDao} / {@link AnnotationDao} — never call one on the FX
 * thread, never while holding the {@code ScanPipeline} lock.
 */
public final class TagDao {

    private final Database database;

    public TagDao(Database database) {
        this.database = Objects.requireNonNull(database, "database must not be null");
    }

    /**
     * Creates the tag if absent and assigns it to {@code findingId}, in ONE transaction.
     * Idempotent: re-assigning an already-assigned tag is a no-op that returns the same row
     * (the composite PRIMARY KEY is what makes this well-defined — plan §0.1/2).
     *
     * @return the assignment, carrying the tag's generated-or-existing id and its STORED name
     * @throws PersistenceException if no findings row has that id (FK), or on any SQL failure
     * @throws NullPointerException if tag is null
     */
    public FindingTagRecord assign(long findingId, NewTag tag) throws PersistenceException {
        Objects.requireNonNull(tag, "tag must not be null");
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                FindingTagRecord record = assign(connection, findingId, tag);
                connection.commit();
                return record;
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new PersistenceException("assigning tag " + tag.name()
                    + " to finding " + findingId, e);
        }
    }

    /** Removes one tag from one finding. The {@code tags} row itself is never deleted.
     *  @return true if a row was removed, false if that pairing was already absent. */
    public boolean unassign(long findingId, long tagId) throws PersistenceException {
        try (Connection connection = database.openConnection();
                PreparedStatement ps = connection.prepareStatement(
                        "DELETE FROM finding_tags WHERE finding_id = ? AND tag_id = ?")) {
            ps.setLong(1, findingId);
            ps.setLong(2, tagId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new PersistenceException(
                    "unassigning tag " + tagId + " from finding " + findingId, e);
        }
    }

    /** One finding's tags, by name ({@code ORDER BY t.name, t.id}). Empty if none. */
    public List<FindingTagRecord> findByFinding(long findingId) throws PersistenceException {
        try (Connection connection = database.openConnection();
                PreparedStatement ps = connection.prepareStatement(
                        "SELECT ft.finding_id, t.id AS tag_id, t.name FROM finding_tags ft "
                                + "JOIN tags t ON t.id = ft.tag_id "
                                + "WHERE ft.finding_id = ? ORDER BY t.name, t.id")) {
            ps.setLong(1, findingId);
            try (ResultSet rs = ps.executeQuery()) {
                List<FindingTagRecord> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(readFindingTagRecord(rs));
                }
                return result;
            }
        } catch (SQLException e) {
            throw new PersistenceException("finding tags for finding " + findingId, e);
        }
    }

    /**
     * Every tag assignment across every finding of one scan — the bulk prefetch the screen loads
     * once ({@code JOIN findings ... JOIN tags ...  WHERE findings.scan_id = ?},
     * {@code ORDER BY ft.finding_id, t.name, t.id}). Empty if none.
     */
    public List<FindingTagRecord> findByScan(long scanId) throws PersistenceException {
        try (Connection connection = database.openConnection();
                PreparedStatement ps = connection.prepareStatement(
                        "SELECT ft.finding_id, t.id AS tag_id, t.name FROM finding_tags ft "
                                + "JOIN findings f ON f.id = ft.finding_id "
                                + "JOIN tags t ON t.id = ft.tag_id "
                                + "WHERE f.scan_id = ? "
                                + "ORDER BY ft.finding_id, t.name, t.id")) {
            ps.setLong(1, scanId);
            try (ResultSet rs = ps.executeQuery()) {
                List<FindingTagRecord> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(readFindingTagRecord(rs));
                }
                return result;
            }
        } catch (SQLException e) {
            throw new PersistenceException("finding tags for scan " + scanId, e);
        }
    }

    // --- connection-scoped helper, private: assign is itself multi-statement (INSERT OR IGNORE
    //     tag, SELECT its id, INSERT OR IGNORE the pairing), so it composes inside one
    //     transaction (plan §3.1). No package-private overload -- nothing composes tags into
    //     another DAO's transaction today. ---

    private FindingTagRecord assign(Connection connection, long findingId, NewTag tag)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT OR IGNORE INTO tags(name) VALUES (?)")) {
            ps.setString(1, tag.name());
            ps.executeUpdate();
        }

        long tagId;
        String storedName;
        // Never last_insert_rowid() here (plan §0.5/1): a no-op INSERT OR IGNORE leaves it
        // stale. The id (and the stored, possibly differently-cased, name) is always read back
        // unconditionally.
        try (PreparedStatement ps =
                connection.prepareStatement("SELECT id, name FROM tags WHERE name = ?")) {
            ps.setString(1, tag.name());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                tagId = rs.getLong("id");
                storedName = rs.getString("name");
            }
        }

        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT OR IGNORE INTO finding_tags(finding_id, tag_id) VALUES (?, ?)")) {
            ps.setLong(1, findingId);
            ps.setLong(2, tagId);
            ps.executeUpdate();
        }

        return new FindingTagRecord(findingId, tagId, storedName);
    }

    private static FindingTagRecord readFindingTagRecord(ResultSet rs) throws SQLException {
        long findingId = rs.getLong("finding_id");
        long tagId = rs.getLong("tag_id");
        String name = rs.getString("name");
        return new FindingTagRecord(findingId, tagId, name);
    }
}
