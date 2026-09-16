package com.argus.core;

import com.argus.db.AnnotationDao;
import com.argus.db.AnnotationRecord;
import com.argus.db.Database;
import com.argus.db.NewAnnotation;
import com.argus.db.PersistenceException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * The read/write gateway for finding annotations (plan §3.2). Immutable, stateless,
 * thread-safe.
 *
 * CONSTRUCTION DOES NO I/O (the {@link ScanArchive}/{@link ScanHistory} contract, so the FX
 * thread may build one). Every other method is BLOCKING.
 *
 * THIS CLASS READS NO CLOCK: {@code createdAt} is always a parameter (plan §0.2) — the one
 * current-time read in this feature is fenced to a single call site in {@code ui}
 * ({@code FindingsDetailController.onAddNote()}).
 *
 * Not added to {@link ScanHistory}: that class's own contract is "the read side" of scan
 * history, and annotations are read+write on a different table keyed on a different identity
 * (plan §3.2's decision record).
 */
public final class AnnotationArchive {

    private final Path databaseFile;

    private AnnotationArchive(Path databaseFile) {
        this.databaseFile = databaseFile;
    }

    /** An archive over an explicit database file. What every test uses ({@code @TempDir}). */
    public static AnnotationArchive at(Path databaseFile) {
        Objects.requireNonNull(databaseFile, "databaseFile");
        return new AnnotationArchive(databaseFile);
    }

    /** An archive over {@code ScanArchive.atDefaultLocation()}'s database file — one source of
     *  truth for {@code <dataDir>/argus.db}. */
    public static AnnotationArchive atDefaultLocation() {
        return new AnnotationArchive(ScanArchive.atDefaultLocation().databaseFile());
    }

    public Path databaseFile() {
        return databaseFile;
    }

    /** BLOCKING. One finding's notes, oldest first. Empty if none. */
    public List<FindingNote> listForFinding(long findingId) throws ScanArchiveException {
        try {
            AnnotationDao dao = new AnnotationDao(Database.open(databaseFile));
            return FindingNotes.of(dao.findByFinding(findingId));
        } catch (PersistenceException e) {
            throw new ScanArchiveException(
                    "listing annotations for finding " + findingId + " in " + databaseFile, e);
        }
    }

    /** BLOCKING. Every note on every finding of one scan — the screen's one-shot prefetch. */
    public List<FindingNote> listForScan(long scanId) throws ScanArchiveException {
        try {
            AnnotationDao dao = new AnnotationDao(Database.open(databaseFile));
            return FindingNotes.of(dao.findByScan(scanId));
        } catch (PersistenceException e) {
            throw new ScanArchiveException(
                    "listing annotations for scan " + scanId + " in " + databaseFile, e);
        }
    }

    /**
     * BLOCKING. Appends one note and returns it with its generated id.
     *
     * @throws ScanArchiveException     if no finding has that id, or on any persistence failure
     * @throws IllegalArgumentException if body is blank ({@code NewAnnotation}'s guard, unwrapped
     *                                  — a programming error, not a persistence failure)
     */
    public FindingNote add(long findingId, String body, Instant createdAt)
            throws ScanArchiveException {
        NewAnnotation annotation = new NewAnnotation(body, createdAt);
        try {
            AnnotationDao dao = new AnnotationDao(Database.open(databaseFile));
            AnnotationRecord record = dao.insert(findingId, annotation);
            return FindingNotes.of(record);
        } catch (PersistenceException e) {
            throw new ScanArchiveException(
                    "adding an annotation to finding " + findingId + " in " + databaseFile, e);
        }
    }

    /** BLOCKING. @return true if a note was deleted, false if the id was already absent. */
    public boolean delete(long noteId) throws ScanArchiveException {
        try {
            AnnotationDao dao = new AnnotationDao(Database.open(databaseFile));
            return dao.deleteById(noteId);
        } catch (PersistenceException e) {
            throw new ScanArchiveException(
                    "deleting annotation " + noteId + " in " + databaseFile, e);
        }
    }
}
