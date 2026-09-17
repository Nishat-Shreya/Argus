package com.argus.core;

import com.argus.db.Database;
import com.argus.db.FindingTagRecord;
import com.argus.db.NewTag;
import com.argus.db.PersistenceException;
import com.argus.db.TagDao;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * The read/write gateway for finding tags (plan §3.2). Immutable, stateless, thread-safe.
 *
 * CONSTRUCTION DOES NO I/O (the {@link ScanArchive}/{@link ScanHistory}/{@link AnnotationArchive}
 * contract, so the FX thread may build one). Every other method is BLOCKING.
 *
 * A NEW gateway, not methods on {@link ScanHistory} or {@link AnnotationArchive}: one gateway per
 * persistence subsystem (plan §3.2).
 */
public final class TagArchive {

    private final Path databaseFile;

    private TagArchive(Path databaseFile) {
        this.databaseFile = databaseFile;
    }

    /** An archive over an explicit database file. What every test uses ({@code @TempDir}). */
    public static TagArchive at(Path databaseFile) {
        Objects.requireNonNull(databaseFile, "databaseFile");
        return new TagArchive(databaseFile);
    }

    /** An archive over {@code ScanArchive.atDefaultLocation()}'s database file — one source of
     *  truth for {@code <dataDir>/argus.db}. */
    public static TagArchive atDefaultLocation() {
        return new TagArchive(ScanArchive.atDefaultLocation().databaseFile());
    }

    public Path databaseFile() {
        return databaseFile;
    }

    /** BLOCKING. One finding's tags, by name. Empty if none. */
    public List<FindingTag> listForFinding(long findingId) throws ScanArchiveException {
        try {
            TagDao dao = new TagDao(Database.open(databaseFile));
            return FindingTags.of(dao.findByFinding(findingId));
        } catch (PersistenceException e) {
            throw new ScanArchiveException(
                    "listing tags for finding " + findingId + " in " + databaseFile, e);
        }
    }

    /** BLOCKING. Every tag assignment across one scan's findings — the screen's one-shot
     *  prefetch, and the only source the tag filter draws on. */
    public List<FindingTag> listForScan(long scanId) throws ScanArchiveException {
        try {
            TagDao dao = new TagDao(Database.open(databaseFile));
            return FindingTags.of(dao.findByScan(scanId));
        } catch (PersistenceException e) {
            throw new ScanArchiveException(
                    "listing tags for scan " + scanId + " in " + databaseFile, e);
        }
    }

    /**
     * BLOCKING. Creates the tag if absent and assigns it; idempotent.
     *
     * @throws ScanArchiveException     if no finding has that id, or on any persistence failure
     * @throws IllegalArgumentException if name is blank ({@code NewTag}'s guard, unwrapped)
     */
    public FindingTag add(long findingId, String name) throws ScanArchiveException {
        NewTag tag = new NewTag(name);
        try {
            TagDao dao = new TagDao(Database.open(databaseFile));
            FindingTagRecord record = dao.assign(findingId, tag);
            return FindingTags.of(record);
        } catch (PersistenceException e) {
            throw new ScanArchiveException(
                    "adding a tag to finding " + findingId + " in " + databaseFile, e);
        }
    }

    /** BLOCKING. @return true if the tag was on the finding, false if it was already absent. */
    public boolean remove(long findingId, long tagId) throws ScanArchiveException {
        try {
            TagDao dao = new TagDao(Database.open(databaseFile));
            return dao.unassign(findingId, tagId);
        } catch (PersistenceException e) {
            throw new ScanArchiveException(
                    "removing tag " + tagId + " from finding " + findingId + " in " + databaseFile,
                    e);
        }
    }
}
