package com.argus.core;

import com.argus.db.Database;
import com.argus.db.NewScheduledScan;
import com.argus.db.PersistenceException;
import com.argus.db.ScheduledScanDao;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The read/write gateway for recurring scan schedules (P3-08). Immutable, stateless,
 * thread-safe. CONSTRUCTION DOES NO I/O (the {@code ScanArchive}/{@code TagArchive} contract, so
 * the FX thread may build one).
 *
 * The clock reads here (in {@link #schedule} and {@link #recordRun}) are legitimate operational
 * scheduling timestamps -- when a schedule was created and when it last/next runs -- not a
 * per-finding timestamp and not a clock feeding any scan-comparison or verdict logic (the
 * barred pattern).
 */
public final class ScheduledScanArchive {

    private final Path databaseFile;

    private ScheduledScanArchive(Path databaseFile) {
        this.databaseFile = databaseFile;
    }

    /** An archive over an explicit database file. What every test uses ({@code @TempDir}). */
    public static ScheduledScanArchive at(Path databaseFile) {
        Objects.requireNonNull(databaseFile, "databaseFile");
        return new ScheduledScanArchive(databaseFile);
    }

    /** An archive over {@code ScanArchive.atDefaultLocation()}'s database file -- one source of
     *  truth for {@code <dataDir>/argus.db}. */
    public static ScheduledScanArchive atDefaultLocation() {
        return new ScheduledScanArchive(ScanArchive.atDefaultLocation().databaseFile());
    }

    public Path databaseFile() {
        return databaseFile;
    }

    /**
     * BLOCKING. Creates a new, enabled schedule for {@code target}, due to run for the first
     * time {@code intervalMinutes} from now.
     *
     * @throws IllegalArgumentException if intervalMinutes is not positive
     */
    public ScheduledScan schedule(String target, int intervalMinutes) throws ScanArchiveException {
        if (intervalMinutes <= 0) {
            throw new IllegalArgumentException("intervalMinutes must be positive");
        }
        Instant now = Instant.now();
        Instant nextRunAt = now.plus(intervalMinutes, ChronoUnit.MINUTES);
        NewScheduledScan newSchedule =
                new NewScheduledScan(target, String.valueOf(intervalMinutes), now, nextRunAt);
        try {
            ScheduledScanDao dao = new ScheduledScanDao(Database.open(databaseFile));
            return ScheduledScans.of(dao.insert(newSchedule));
        } catch (PersistenceException e) {
            throw new ScanArchiveException("scheduling a recurring scan for " + target, e);
        }
    }

    /** BLOCKING. Every schedule, oldest first. Empty if none. */
    public List<ScheduledScan> list() throws ScanArchiveException {
        try {
            ScheduledScanDao dao = new ScheduledScanDao(Database.open(databaseFile));
            return ScheduledScans.of(dao.findAll());
        } catch (PersistenceException e) {
            throw new ScanArchiveException("listing scheduled scans in " + databaseFile, e);
        }
    }

    /** BLOCKING. Every enabled schedule whose next run is at or before {@code now}. */
    public List<ScheduledScan> due(Instant now) throws ScanArchiveException {
        Objects.requireNonNull(now, "now");
        List<ScheduledScan> due = new ArrayList<>();
        for (ScheduledScan candidate : list()) {
            if (candidate.enabled() && candidate.nextRunAt() != null
                    && !candidate.nextRunAt().isAfter(now)) {
                due.add(candidate);
            }
        }
        return List.copyOf(due);
    }

    /** BLOCKING. @return true if the schedule existed, false otherwise. */
    public boolean setEnabled(long id, boolean enabled) throws ScanArchiveException {
        try {
            ScheduledScanDao dao = new ScheduledScanDao(Database.open(databaseFile));
            return dao.setEnabled(id, enabled);
        } catch (PersistenceException e) {
            throw new ScanArchiveException(
                    "setting enabled for scheduled scan " + id + " in " + databaseFile, e);
        }
    }

    /**
     * BLOCKING. Records that a schedule ran at {@code ranAt} and advances its next run by its
     * own {@code intervalMinutes}. The caller (which already holds the {@link ScheduledScan}
     * from {@link #due}) passes the interval, so this needs no extra read.
     *
     * @return true if the schedule existed, false otherwise
     */
    public boolean recordRun(long id, int intervalMinutes, Instant ranAt)
            throws ScanArchiveException {
        Objects.requireNonNull(ranAt, "ranAt");
        Instant nextRunAt = ranAt.plus(intervalMinutes, ChronoUnit.MINUTES);
        try {
            ScheduledScanDao dao = new ScheduledScanDao(Database.open(databaseFile));
            return dao.recordRun(id, ranAt, nextRunAt);
        } catch (PersistenceException e) {
            throw new ScanArchiveException(
                    "recording run for scheduled scan " + id + " in " + databaseFile, e);
        }
    }

    /** BLOCKING. @return true if the schedule existed, false otherwise. */
    public boolean delete(long id) throws ScanArchiveException {
        try {
            ScheduledScanDao dao = new ScheduledScanDao(Database.open(databaseFile));
            return dao.delete(id);
        } catch (PersistenceException e) {
            throw new ScanArchiveException(
                    "deleting scheduled scan " + id + " in " + databaseFile, e);
        }
    }
}
