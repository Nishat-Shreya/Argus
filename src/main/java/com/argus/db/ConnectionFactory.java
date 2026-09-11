package com.argus.db;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Package-private seam: "give me a ready-to-use connection". One production impl, one test
 * double. Same shape as core's {@code SocketConnector} / {@code HttpFetcher}.
 */
interface ConnectionFactory {
    Connection open() throws SQLException;
}
