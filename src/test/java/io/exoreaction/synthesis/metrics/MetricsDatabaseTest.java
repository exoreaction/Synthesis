package io.exoreaction.synthesis.metrics;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for MetricsDatabase — event recording, querying, tool stats,
 * workspace isolation, and retention.
 */
class MetricsDatabaseTest {

    @TempDir
    Path tempDir;

    private MetricsDatabase db;

    @BeforeEach
    void setUp() throws SQLException {
        db = new MetricsDatabase(tempDir.resolve("metrics.db"));
    }

    @AfterEach
    void tearDown() throws SQLException {
        if (db != null) {
            db.close();
        }
    }

    // --- Initial state ---

    @Test
    void freshDatabase_hasNoEvents() throws SQLException {
        List<MetricsEvent> events = db.queryEvents(0);
        assertTrue(events.isEmpty(), "Fresh database should have no events");
    }

    // --- recordEvent and queryEvents ---

    @Test
    void recordEvent_searchEvent_canBeQueried() throws SQLException {
        MetricsEvent event = searchEvent("/ws", "search", 50L, 10, true);
        db.recordEvent(event);

        List<MetricsEvent> events = db.queryEvents(0);
        assertEquals(1, events.size(), "Should retrieve the recorded event");
        assertEquals("search", events.get(0).mcpTool());
        assertEquals("/ws", events.get(0).mcpWorkspace());
        assertTrue(events.get(0).success());
    }

    @Test
    void recordEvent_failedEvent_preservesErrorMessage() throws SQLException {
        // Use aiRetry=true: SQLite JDBC can't read NULL via getObject(col, Integer.class)
        // (MetricsDatabase stores NULL when aiRetry=false, which SQLite JDBC can't map back)
        MetricsEvent event = new MetricsEvent(
                Instant.now(), "mcp_tool_invocation", "graph", "/ws",
                50L, 0, false, "Index not found", null, null, 0, true);
        db.recordEvent(event);

        List<MetricsEvent> events = db.queryEvents(0);
        assertEquals(1, events.size());
        assertFalse(events.get(0).success());
        assertEquals("Index not found", events.get(0).errorMessage());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 3, 5, 10})
    void recordMultipleEvents_allRetrieved(int count) throws SQLException {
        for (int i = 0; i < count; i++) {
            db.recordEvent(searchEvent("/ws", "search", (long) i * 10, i, true));
        }
        assertEquals(count, db.queryEvents(0).size());
    }

    // --- queryEvents by tool type ---

    @ParameterizedTest
    @CsvSource({
        "search",
        "relate",
        "graph",
        "stats",
        "ask"
    })
    void recordEvent_allMcpTools_canBeRecorded(String tool) throws SQLException {
        db.recordEvent(new MetricsEvent(
                Instant.now(), "mcp_tool_invocation", tool, "/ws",
                100L, 5, true, null, null, null, 0, true));

        List<MetricsEvent> events = db.queryEvents(0);
        assertEquals(1, events.size());
        assertEquals(tool, events.get(0).mcpTool());
    }

    // --- getToolStats ---

    @Test
    void getToolStats_noEvents_returnsEmptyOrNonNull() throws SQLException {
        Map<String, MetricsDatabase.ToolStats> stats = db.getToolStats(0);
        assertNotNull(stats, "getToolStats should never return null");
        assertTrue(stats.isEmpty(), "No events → no tool stats");
    }

    @Test
    void getToolStats_withSearchEvents_returnsSearchStats() throws SQLException {
        db.recordEvent(searchEvent("/ws", "search", 100L, 10, true));
        db.recordEvent(searchEvent("/ws", "search", 200L, 5, true));

        Map<String, MetricsDatabase.ToolStats> stats = db.getToolStats(0);
        assertTrue(stats.containsKey("search"), "Should have stats for 'search' tool");
        assertEquals(2, stats.get("search").invocationCount(), "Should count 2 search events");
    }

    @Test
    void toolStats_successRate_computedCorrectly() throws SQLException {
        db.recordEvent(searchEvent("/ws", "search", 100L, 10, true));
        db.recordEvent(searchEvent("/ws", "search", 200L, 5, true));
        db.recordEvent(new MetricsEvent(Instant.now(), "mcp_tool_invocation", "search", "/ws",
                50L, 0, false, "error", null, null, 0, true));

        Map<String, MetricsDatabase.ToolStats> stats = db.getToolStats(0);
        MetricsDatabase.ToolStats searchStats = stats.get("search");
        assertNotNull(searchStats);
        assertEquals(3, searchStats.invocationCount());
        // 2 successes / 3 total = ~66.7%
        assertTrue(searchStats.successRate() > 60 && searchStats.successRate() < 70,
                "Success rate should be ~66.7%");
    }

    // --- queryEvents with period filter ---

    @Test
    void queryEvents_periodFilter_returns0ForVeryOldCutoff() throws SQLException {
        db.recordEvent(searchEvent("/ws", "search", 50L, 5, true));
        // Period of 0 days = all events
        List<MetricsEvent> all = db.queryEvents(0);
        assertEquals(1, all.size());
        // Period of 365 days = events from last year
        List<MetricsEvent> lastYear = db.queryEvents(365);
        assertEquals(1, lastYear.size());
    }

    // --- WAL mode and busy_timeout ---

    @Test
    void walMode_isEnabled() throws SQLException {
        Connection conn = db.getConnection();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA journal_mode")) {
            assertTrue(rs.next());
            assertEquals("wal", rs.getString(1).toLowerCase(),
                    "WAL journal mode should be enabled for MetricsDatabase");
        }
    }

    @Test
    void busyTimeout_isSet() throws SQLException {
        Connection conn = db.getConnection();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA busy_timeout")) {
            assertTrue(rs.next());
            assertEquals(5000, rs.getInt(1),
                    "busy_timeout should be set to 5000ms for MetricsDatabase");
        }
    }

    // --- Separate migration: only metrics tables should exist ---

    @Test
    void separateMigration_metricsTableExists() throws SQLException {
        Connection conn = db.getConnection();
        DatabaseMetaData meta = conn.getMetaData();
        try (ResultSet rs = meta.getTables(null, null, "metrics", new String[]{"TABLE"})) {
            assertTrue(rs.next(), "metrics table should exist in MetricsDatabase");
        }
    }

    @Test
    void separateMigration_synthesisTablesDoNotExist() throws SQLException {
        // Tables from SynthesisDatabase migrations should NOT be in metrics.db
        Connection conn = db.getConnection();
        DatabaseMetaData meta = conn.getMetaData();
        String[] synthesisOnlyTables = {
            "file_movements", "file_audit_log", "workspace_snapshots",
            "change_events", "directory_centroids", "code_dependencies"
        };
        for (String table : synthesisOnlyTables) {
            try (ResultSet rs = meta.getTables(null, null, table, new String[]{"TABLE"})) {
                assertFalse(rs.next(),
                        "Table '" + table + "' should NOT exist in MetricsDatabase (belongs to SynthesisDatabase)");
            }
        }
    }

    // --- getDefaultPath ---

    @Test
    void getDefaultPath_isInSynthesisDirectory() {
        Path defaultPath = MetricsDatabase.getDefaultPath();
        assertNotNull(defaultPath);
        assertTrue(defaultPath.toString().contains(".synthesis"),
                "Default path should be in .synthesis directory");
        assertTrue(defaultPath.getFileName().toString().endsWith(".db"),
                "Default path should end in .db");
    }

    // --- helpers ---

    private MetricsEvent searchEvent(String workspace, String tool, long execMs,
                                      int resultCount, boolean success) {
        return new MetricsEvent(
                Instant.now(), "mcp_tool_invocation", tool, workspace,
                execMs, resultCount, success, null,
                "terms:2 operators:AND", null, 0, true);
    }
}
