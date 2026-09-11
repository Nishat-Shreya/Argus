package com.argus.db;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Test double: delegates to a real {@link SqliteConnectionFactory} and records every
 * {@link Connection} it hands out, so resource-closure tests can assert the real property —
 * {@code Connection.isClosed()} — instead of inspecting source (plan §6.7).
 */
final class RecordingConnectionFactory implements ConnectionFactory {

    private final ConnectionFactory delegate;
    private final List<Connection> opened = new ArrayList<>();

    RecordingConnectionFactory(ConnectionFactory delegate) {
        this.delegate = delegate;
    }

    @Override
    public Connection open() throws SQLException {
        Connection connection = delegate.open();
        opened.add(connection);
        return connection;
    }

    /** True iff every connection ever handed out is now closed. */
    boolean allClosed() throws SQLException {
        for (Connection connection : opened) {
            if (!connection.isClosed()) {
                return false;
            }
        }
        return true;
    }

    /** How many connections have been handed out so far. */
    int openedCount() {
        return opened.size();
    }
}
