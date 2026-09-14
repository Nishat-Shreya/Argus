package com.argus.core;

import com.argus.db.Database;
import com.argus.db.PersistenceException;
import com.argus.db.ScanRepository;
import com.argus.db.ScanSession;
import java.nio.file.Path;
import java.util.Objects;

/**
 * The on-disk history of scan runs. Immutable, stateless, thread-safe.
 *
 * CONSTRUCTION DOES NO I/O — {@link #atDefaultLocation()}/{@link #at(Path)} only resolve a
 * {@code Path}, so they are safe to call from the JavaFX Application Thread. {@link #save} is
 * BLOCKING (file I/O + SQL) and must not be.
 */
public final class ScanArchive {

    public static final String DEFAULT_DATABASE_FILE_NAME = "argus.db";

    private final Path databaseFile;

    private ScanArchive(Path databaseFile) {
        this.databaseFile = databaseFile;
    }

    /** An archive over an explicit database file. What every test uses ({@code @TempDir}). */
    public static ScanArchive at(Path databaseFile) {
        Objects.requireNonNull(databaseFile, "databaseFile");
        return new ScanArchive(databaseFile);
    }

    /** An archive over {@code <dataDir>/argus.db}, sibling of {@code vaults/}. What
     *  {@code DashboardController} uses. */
    public static ScanArchive atDefaultLocation() {
        return new ScanArchive(AppDataDirectory.resolve().resolve(DEFAULT_DATABASE_FILE_NAME));
    }

    public Path databaseFile() {
        return databaseFile;
    }

    /**
     * Opens (creating if absent) the database, writes the run in ONE transaction, closes.
     *
     * @return the generated {@code scans.id}
     * @throws ScanArchiveException on any persistence failure — nothing is written
     * @throws NullPointerException if run is null
     */
    public long save(ScanRun run) throws ScanArchiveException {
        Objects.requireNonNull(run, "run");
        try {
            Database database = Database.open(databaseFile);
            ScanRepository repository = new ScanRepository(database);
            ScanSession session = repository.saveSession(
                    NewScans.of(run), NewFindings.of(run.findings()));
            return session.scan().id();
        } catch (PersistenceException e) {
            throw new ScanArchiveException("saving scan session to " + databaseFile, e);
        }
    }
}
