package com.argus.db;

import java.nio.file.Path;

/** Test helper: one place that knows the test database file name. */
final class TempDatabases {

    private TempDatabases() {}

    static Database open(Path tempDir) throws PersistenceException {
        return Database.open(tempDir.resolve("argus.db"));
    }
}
