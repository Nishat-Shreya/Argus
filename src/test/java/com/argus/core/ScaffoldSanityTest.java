package com.argus.core;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Verifies the build toolchain and that dependency resolution actually succeeded — not
 * merely that JUnit runs. Pure JUnit 5, zero JavaFX (invariant 2).
 */
class ScaffoldSanityTest {

    @Test
    void runtimeIsJava21OrLater() {
        assertTrue(Runtime.version().feature() >= 21,
                "Argus targets Java 21+, runtime is " + Runtime.version());
    }

    @Test
    void sqliteJdbcDriverIsOnTheClasspath() {
        assertDoesNotThrow(() -> Class.forName("org.sqlite.JDBC"));
    }

    @Test
    void jacksonObjectMapperRoundTripsAMap() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Map<String, String> original = Map.of("argus", "ok");
        String json = mapper.writeValueAsString(original);
        @SuppressWarnings("unchecked")
        Map<String, String> restored = mapper.readValue(json, Map.class);
        assertEquals(original, restored);
    }

    @Test
    void sqliteOpensAnInMemoryDatabase() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            assertNotNull(connection);
            assertFalse(connection.isClosed());
        }
    }
}
