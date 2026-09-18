package com.argus.core;

import com.argus.db.Database;
import com.argus.db.IntelResultDao;
import com.argus.db.IntelResultRecord;
import com.argus.db.NewIntelResult;
import com.argus.db.PersistenceException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * The read/write gateway for a scan's persisted intel/KEV enrichment (P3-16). Immutable,
 * stateless, thread-safe. CONSTRUCTION DOES NO I/O (the {@code ScanArchive}/{@code TagArchive}
 * contract, so the FX thread may build one).
 *
 * {@link #save} both formats the one-line-per-source summary AND persists it -- kept together
 * here, not split into a separate {@code ui}-side formatter, since {@link IntelReport} is
 * already a {@code core} type and this is its only caller.
 */
public final class IntelArchive {

    private final Path databaseFile;

    private IntelArchive(Path databaseFile) {
        this.databaseFile = databaseFile;
    }

    /** An archive over an explicit database file. What every test uses ({@code @TempDir}). */
    public static IntelArchive at(Path databaseFile) {
        Objects.requireNonNull(databaseFile, "databaseFile");
        return new IntelArchive(databaseFile);
    }

    /** An archive over {@code ScanArchive.atDefaultLocation()}'s database file -- one source of
     *  truth for {@code <dataDir>/argus.db}. */
    public static IntelArchive atDefaultLocation() {
        return new IntelArchive(ScanArchive.atDefaultLocation().databaseFile());
    }

    public Path databaseFile() {
        return databaseFile;
    }

    /**
     * BLOCKING. Formats a one-line-per-source summary from {@code report} and {@code kevMatch},
     * then upserts it as the one intel result row for {@code scanId}.
     *
     * @throws ScanArchiveException if no scans row has that id, or on any persistence failure
     */
    public PersistedIntelResult save(long scanId, IntelReport report, KevMatchResult kevMatch)
            throws ScanArchiveException {
        Objects.requireNonNull(report, "report must not be null");
        Objects.requireNonNull(kevMatch, "kevMatch must not be null");
        String summary = summarize(report, kevMatch);
        NewIntelResult newResult =
                new NewIntelResult(summary, !kevMatch.matches().isEmpty(), Instant.now());
        try {
            IntelResultDao dao = new IntelResultDao(Database.open(databaseFile));
            IntelResultRecord record = dao.save(scanId, newResult);
            return IntelResults.of(record);
        } catch (PersistenceException e) {
            throw new ScanArchiveException("saving intel result for scan " + scanId, e);
        }
    }

    /** BLOCKING. The persisted intel result for {@code scanId}, if any. */
    public Optional<PersistedIntelResult> find(long scanId) throws ScanArchiveException {
        try {
            IntelResultDao dao = new IntelResultDao(Database.open(databaseFile));
            return dao.findByScan(scanId).map(IntelResults::of);
        } catch (PersistenceException e) {
            throw new ScanArchiveException(
                    "finding intel result for scan " + scanId + " in " + databaseFile, e);
        }
    }

    private static String summarize(IntelReport report, KevMatchResult kevMatch) {
        StringBuilder builder = new StringBuilder();
        for (IntelSourceOutcome outcome : report.outcomes()) {
            if (builder.length() > 0) {
                builder.append("; ");
            }
            builder.append(outcome.sourceName()).append(": ")
                    .append(outcome.status().name().toLowerCase(Locale.ROOT));
        }
        List<CveId> cveIds = report.allCveIds();
        if (!cveIds.isEmpty()) {
            builder.append("; cves: ").append(cveIds.size());
        }
        if (!kevMatch.matches().isEmpty()) {
            builder.append("; KEV MATCH: ").append(kevMatch.matches().size());
        }
        return builder.toString();
    }
}
