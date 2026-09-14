package com.argus.core;

import com.argus.db.Database;
import com.argus.db.PersistenceException;
import com.argus.db.ScanRecord;
import com.argus.db.ScanRepository;
import com.argus.db.ScanSession;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The read side of the on-disk scan history (plan §3.3). Immutable, stateless, thread-safe.
 *
 * CONSTRUCTION DOES NO I/O (the {@link ScanArchive} contract, so the FX thread may construct
 * one); {@link #listScans()} and {@link #compare(long, long)} are BLOCKING and must not run on
 * the FX thread.
 */
public final class ScanHistory {

    private final Path databaseFile;

    private ScanHistory(Path databaseFile) {
        this.databaseFile = databaseFile;
    }

    /** A history over an explicit database file. What every test uses ({@code @TempDir}). */
    public static ScanHistory at(Path databaseFile) {
        Objects.requireNonNull(databaseFile, "databaseFile");
        return new ScanHistory(databaseFile);
    }

    /** A history over {@code ScanArchive.atDefaultLocation()}'s database file. */
    public static ScanHistory atDefaultLocation() {
        return new ScanHistory(ScanArchive.atDefaultLocation().databaseFile());
    }

    public Path databaseFile() {
        return databaseFile;
    }

    /** BLOCKING. Every persisted scan, newest first, whatever its status. Empty if none. */
    public List<ScanSummary> listScans() throws ScanArchiveException {
        try {
            Database database = Database.open(databaseFile);
            ScanRepository repository = new ScanRepository(database);
            List<ScanRecord> records = repository.findAll();
            return ScanSummaries.of(records);
        } catch (PersistenceException e) {
            throw new ScanArchiveException("listing scans in " + databaseFile, e);
        }
    }

    /**
     * BLOCKING. Loads both scans' findings, diffs them, projects the result.
     *
     * @throws ScanArchiveException on any persistence failure, or if either id is absent
     */
    public ScanComparison compare(long baselineScanId, long currentScanId)
            throws ScanArchiveException {
        try {
            Database database = Database.open(databaseFile);
            ScanRepository repository = new ScanRepository(database);
            ScanSession baseline = load(repository, baselineScanId);
            ScanSession current = load(repository, currentScanId);

            ScanDiff diff = ScanDiffEngine.diff(baseline.findings(), current.findings());
            return new ScanComparison(ScanSummaries.of(baseline.scan()),
                    ScanSummaries.of(current.scan()), ScanDiffReports.of(diff));
        } catch (PersistenceException e) {
            throw new ScanArchiveException("comparing scans in " + databaseFile, e);
        }
    }

    private static ScanSession load(ScanRepository repository, long scanId)
            throws PersistenceException, ScanArchiveException {
        Optional<ScanSession> session = repository.loadSession(scanId);
        if (session.isEmpty()) {
            throw new ScanArchiveException("no scan with id " + scanId);
        }
        return session.get();
    }
}
