package io.exoreaction.synthesis.metrics;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for MetricsCollector — enabled/disabled state, database access,
 * fire-and-forget recording (no exception), and shutdown.
 */
class MetricsCollectorTest {

    @TempDir
    Path tempDir;

    // --- disabled collector (no database) ---

    @Test
    void disabled_isEnabled_returnsFalse() {
        MetricsCollector collector = new MetricsCollector(null, false);
        assertFalse(collector.isEnabled());
    }

    @Test
    void disabled_getDatabase_returnsNull() {
        MetricsCollector collector = new MetricsCollector(null, false);
        assertNull(collector.getDatabase(), "Disabled collector should have null database");
    }

    @Test
    void disabled_recordMcpInvocation_doesNotThrow() {
        MetricsCollector collector = new MetricsCollector(null, false);
        assertDoesNotThrow(() -> collector.recordMcpInvocation(
                "search", "/ws", 100L, 5, true, null));
    }

    @Test
    void disabled_recordSearch_doesNotThrow() {
        MetricsCollector collector = new MetricsCollector(null, false);
        assertDoesNotThrow(() -> collector.recordSearch("/ws", 100L, 5, "terms:2", true));
    }

    @Test
    void disabled_recordAiFeature_doesNotThrow() {
        MetricsCollector collector = new MetricsCollector(null, false);
        assertDoesNotThrow(() -> collector.recordAiFeature("ask", "/ws", 200L, 500, true, false));
    }

    @Test
    void disabled_shutdown_doesNotThrow() {
        MetricsCollector collector = new MetricsCollector(null, false);
        assertDoesNotThrow(collector::shutdown);
    }

    // --- enabled collector (with real database) ---

    @Test
    void enabled_isEnabled_returnsTrue() throws SQLException {
        MetricsDatabase db = new MetricsDatabase(tempDir.resolve("metrics.db"));
        MetricsCollector collector = new MetricsCollector(db, true);
        try {
            assertTrue(collector.isEnabled());
        } finally {
            collector.shutdown();
        }
    }

    @Test
    void enabled_getDatabase_returnsNonNull() throws SQLException {
        MetricsDatabase db = new MetricsDatabase(tempDir.resolve("metrics.db"));
        MetricsCollector collector = new MetricsCollector(db, true);
        try {
            assertNotNull(collector.getDatabase());
            assertSame(db, collector.getDatabase());
        } finally {
            collector.shutdown();
        }
    }

    @Test
    void enabled_shutdown_doesNotThrow() throws SQLException {
        MetricsDatabase db = new MetricsDatabase(tempDir.resolve("metrics.db"));
        MetricsCollector collector = new MetricsCollector(db, true);
        assertDoesNotThrow(collector::shutdown);
    }

    @Test
    void enabled_recordMcpInvocation_doesNotThrow() throws SQLException {
        MetricsDatabase db = new MetricsDatabase(tempDir.resolve("metrics.db"));
        MetricsCollector collector = new MetricsCollector(db, true);
        try {
            assertDoesNotThrow(() -> collector.recordMcpInvocation(
                    "search", "/ws", 100L, 10, true, null));
        } finally {
            collector.shutdown();
        }
    }

    @Test
    void enabled_recordSearch_doesNotThrow() throws SQLException {
        MetricsDatabase db = new MetricsDatabase(tempDir.resolve("metrics.db"));
        MetricsCollector collector = new MetricsCollector(db, true);
        try {
            assertDoesNotThrow(() -> collector.recordSearch("/ws", 50L, 3, "terms:1", true));
        } finally {
            collector.shutdown();
        }
    }

    @Test
    void enabled_recordAiFeature_doesNotThrow() throws SQLException {
        MetricsDatabase db = new MetricsDatabase(tempDir.resolve("metrics.db"));
        MetricsCollector collector = new MetricsCollector(db, true);
        try {
            assertDoesNotThrow(() -> collector.recordAiFeature("ask", "/ws", 300L, 200, true, false));
        } finally {
            collector.shutdown();
        }
    }

    // --- enabled/disabled parametrized ---

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void constructor_isEnabled_matchesParameter(boolean enabled) {
        MetricsCollector collector = new MetricsCollector(null, enabled);
        assertEquals(enabled, collector.isEnabled());
    }

    @ParameterizedTest
    @ValueSource(strings = {"search", "relate", "graph", "stats", "ask"})
    void disabled_recordMcpInvocation_allTools_doesNotThrow(String tool) {
        MetricsCollector collector = new MetricsCollector(null, false);
        assertDoesNotThrow(() -> collector.recordMcpInvocation(tool, "/ws", 100L, 5, true, null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ask", "explain", "enrich"})
    void disabled_recordAiFeature_allFeatures_doesNotThrow(String feature) {
        MetricsCollector collector = new MetricsCollector(null, false);
        assertDoesNotThrow(() -> collector.recordAiFeature(feature, "/ws", 200L, 100, true, false));
    }

    // --- shutdown multiple times ---

    @Test
    void shutdown_calledTwice_doesNotThrow() throws SQLException {
        MetricsDatabase db = new MetricsDatabase(tempDir.resolve("metrics.db"));
        MetricsCollector collector = new MetricsCollector(db, true);
        assertDoesNotThrow(() -> {
            collector.shutdown();
            collector.shutdown();
        });
    }
}
