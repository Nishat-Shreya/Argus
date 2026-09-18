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
 * Recurring scan schedules (P3-08). Stateless and thread-safe; same BLOCKING contract as the
 * other DAOs in this package -- never call one on the FX thread.
 */
public final class ScheduledScanDao {

    private final Database database;

    public ScheduledScanDao(Database database) {
        this.database = Objects.requireNonNull(database, "database must not be null");
    }

    /** Inserts a new, enabled schedule and returns it with the generated id. */
    public ScheduledScanRecord insert(NewScheduledScan schedule) throws PersistenceException {
        Objects.requireNonNull(schedule, "schedule must not be null");
        try (Connection connection = database.openConnection();
                PreparedStatement ps = connection.prepareStatement(
                        "INSERT INTO scheduled_scans"
                                + "(target, cron_expression, enabled, created_at, next_run_at) "
                                + "VALUES (?, ?, 1, ?, ?)")) {
            ps.setString(1, schedule.target());
            ps.setString(2, schedule.cronExpression());
            ps.setLong(3, schedule.createdAt().toEpochMilli());
            ps.setLong(4, schedule.nextRunAt().toEpochMilli());
            ps.executeUpdate();
            long id = lastInsertRowId(connection);
            return new ScheduledScanRecord(id, schedule.target(), schedule.cronExpression(), true,
                    schedule.createdAt(), null, schedule.nextRunAt());
        } catch (SQLException e) {
            throw new PersistenceException(
                    "inserting scheduled scan for " + schedule.target(), e);
        }
    }

    /** Every schedule, oldest first ({@code ORDER BY id}). Empty if none. */
    public List<ScheduledScanRecord> findAll() throws PersistenceException {
        try (Connection connection = database.openConnection();
                PreparedStatement ps = connection.prepareStatement(
                        "SELECT id, target, cron_expression, enabled, created_at, last_run_at, "
                                + "next_run_at FROM scheduled_scans ORDER BY id")) {
            try (ResultSet rs = ps.executeQuery()) {
                List<ScheduledScanRecord> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(readScheduledScanRecord(rs));
                }
                return result;
            }
        } catch (SQLException e) {
            throw new PersistenceException("listing scheduled scans", e);
        }
    }

    /** @return true if a row with that id was updated, false if the id was absent. */
    public boolean setEnabled(long id, boolean enabled) throws PersistenceException {
        try (Connection connection = database.openConnection();
                PreparedStatement ps = connection.prepareStatement(
                        "UPDATE scheduled_scans SET enabled = ? WHERE id = ?")) {
            ps.setInt(1, enabled ? 1 : 0);
            ps.setLong(2, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new PersistenceException("setting enabled for scheduled scan " + id, e);
        }
    }

    /** Records that a schedule ran at {@code ranAt} and stores the caller-computed next run.
     *  @return true if a row with that id was updated, false if the id was absent. */
    public boolean recordRun(long id, Instant ranAt, Instant nextRunAt)
            throws PersistenceException {
        try (Connection connection = database.openConnection();
                PreparedStatement ps = connection.prepareStatement(
                        "UPDATE scheduled_scans SET last_run_at = ?, next_run_at = ? "
                                + "WHERE id = ?")) {
            ps.setLong(1, ranAt.toEpochMilli());
            ps.setLong(2, nextRunAt.toEpochMilli());
            ps.setLong(3, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new PersistenceException("recording run for scheduled scan " + id, e);
        }
    }

    /** @return true if a row was deleted, false if the id was already absent. */
    public boolean delete(long id) throws PersistenceException {
        try (Connection connection = database.openConnection();
                PreparedStatement ps =
                        connection.prepareStatement("DELETE FROM scheduled_scans WHERE id = ?")) {
            ps.setLong(1, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new PersistenceException("deleting scheduled scan " + id, e);
        }
    }

    private static ScheduledScanRecord readScheduledScanRecord(ResultSet rs) throws SQLException {
        long id = rs.getLong("id");
        String target = rs.getString("target");
        String cronExpression = rs.getString("cron_expression");
        boolean enabled = rs.getInt("enabled") != 0;
        Instant createdAt = Instant.ofEpochMilli(rs.getLong("created_at"));
        Instant lastRunAt = rs.getObject("last_run_at") == null
                ? null : Instant.ofEpochMilli(rs.getLong("last_run_at"));
        Instant nextRunAt = rs.getObject("next_run_at") == null
                ? null : Instant.ofEpochMilli(rs.getLong("next_run_at"));
        return new ScheduledScanRecord(id, target, cronExpression, enabled, createdAt, lastRunAt,
                nextRunAt);
    }

    /** Duplicated (~6 lines) rather than widened out of another DAO -- the {@code AnnotationDao}
     *  precedent (P3-06 R5): nothing composes scheduled scans into another DAO's transaction
     *  today. This is a plain INSERT (no {@code OR IGNORE}), so {@code last_insert_rowid()} is
     *  safe here, unlike {@code TagDao.assign}. */
    private static long lastInsertRowId(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("SELECT last_insert_rowid()")) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
