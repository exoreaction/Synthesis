package io.exoreaction.synthesis.metrics;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.logging.Logger;

/**
 * SQLite database for storing Synthesis MCP metrics.
 *
 * <p>Stores operational metrics about MCP tool invocations, search performance,
 * AI feature usage, and workspace health. All data is privacy-safe (no query
 * content, only patterns and performance metrics).
 *
 * <p>Retention policy: 90 days (automatic cleanup on initialization).
 *
 * <p>Thread-safe: All operations are synchronized.
 */
public class MetricsDatabase implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(MetricsDatabase.class.getName());

    private static final String SCHEMA_VERSION = "1";
    private static final int RETENTION_DAYS = 90;

    private final Path dbPath;
    private Connection connection;

    /**
     * Creates or opens the metrics database at the given path.
     *
     * @param dbPath path to SQLite database file
     * @throws SQLException if database initialization fails
     */
    public MetricsDatabase(Path dbPath) throws SQLException {
        this.dbPath = dbPath;
        initialize();
    }

    /**
     * Returns the default metrics database path (~/.synthesis/metrics.db).
     */
    public static Path getDefaultPath() {
        return Path.of(System.getProperty("user.home"), ".synthesis", "metrics.db");
    }

    /**
     * Initializes the database connection and schema.
     */
    private synchronized void initialize() throws SQLException {
        try {
            // Ensure parent directory exists
            if (dbPath.getParent() != null) {
                Files.createDirectories(dbPath.getParent());
            }

            // Load SQLite JDBC driver
            Class.forName("org.sqlite.JDBC");

            // Connect to database
            String url = "jdbc:sqlite:" + dbPath.toAbsolutePath();
            connection = DriverManager.getConnection(url);
            connection.setAutoCommit(true);

            // Create tables if they don't exist
            createTables();

            // Clean up old records
            cleanupOldRecords();

            LOG.info("Metrics database initialized: " + dbPath);
        } catch (IOException | ClassNotFoundException e) {
            throw new SQLException("Failed to initialize metrics database", e);
        }
    }

    /**
     * Creates the database schema.
     */
    private void createTables() throws SQLException {
        String schemaSql = """
            CREATE TABLE IF NOT EXISTS metrics (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                timestamp INTEGER NOT NULL,
                event_type TEXT NOT NULL,
                mcp_tool TEXT,
                mcp_workspace TEXT,
                execution_time_ms INTEGER,
                result_count INTEGER,
                success INTEGER NOT NULL,
                error_message TEXT,
                search_pattern TEXT,
                ai_feature TEXT,
                ai_tokens_used INTEGER,
                ai_retry INTEGER
            );

            CREATE INDEX IF NOT EXISTS idx_timestamp ON metrics(timestamp);
            CREATE INDEX IF NOT EXISTS idx_mcp_tool ON metrics(mcp_tool);
            CREATE INDEX IF NOT EXISTS idx_workspace ON metrics(mcp_workspace);
            CREATE INDEX IF NOT EXISTS idx_event_type ON metrics(event_type);

            CREATE TABLE IF NOT EXISTS metadata (
                key TEXT PRIMARY KEY,
                value TEXT NOT NULL
            );
            """;

        try (Statement stmt = connection.createStatement()) {
            stmt.executeUpdate(schemaSql);

            // Store schema version
            String versionSql = "INSERT OR REPLACE INTO metadata (key, value) VALUES ('schema_version', ?)";
            try (PreparedStatement ps = connection.prepareStatement(versionSql)) {
                ps.setString(1, SCHEMA_VERSION);
                ps.executeUpdate();
            }
        }
    }

    /**
     * Deletes records older than the retention period.
     */
    private void cleanupOldRecords() throws SQLException {
        long cutoffEpoch = Instant.now().minusSeconds(RETENTION_DAYS * 24L * 60 * 60).getEpochSecond();

        String deleteSql = "DELETE FROM metrics WHERE timestamp < ?";
        try (PreparedStatement ps = connection.prepareStatement(deleteSql)) {
            ps.setLong(1, cutoffEpoch);
            int deleted = ps.executeUpdate();
            if (deleted > 0) {
                LOG.info("Cleaned up " + deleted + " metrics records older than " + RETENTION_DAYS + " days");
            }
        }
    }

    /**
     * Records a metrics event.
     */
    public synchronized void recordEvent(MetricsEvent event) throws SQLException {
        String insertSql = """
            INSERT INTO metrics (
                timestamp, event_type, mcp_tool, mcp_workspace, execution_time_ms,
                result_count, success, error_message, search_pattern, ai_feature,
                ai_tokens_used, ai_retry
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

        try (PreparedStatement ps = connection.prepareStatement(insertSql)) {
            ps.setLong(1, event.timestamp().getEpochSecond());
            ps.setString(2, event.eventType());
            ps.setString(3, event.mcpTool());
            ps.setString(4, event.mcpWorkspace());
            ps.setObject(5, event.executionTimeMs());
            ps.setObject(6, event.resultCount());
            ps.setInt(7, event.success() ? 1 : 0);
            ps.setString(8, event.errorMessage());
            ps.setString(9, event.searchPattern());
            ps.setString(10, event.aiFeature());
            ps.setObject(11, event.aiTokensUsed());
            ps.setObject(12, event.aiRetry() ? 1 : null);

            ps.executeUpdate();
        }
    }

    /**
     * Returns metrics for the specified period.
     *
     * @param periodDays number of days to query (0 = all data)
     * @return list of metrics events
     */
    public synchronized List<MetricsEvent> queryEvents(int periodDays) throws SQLException {
        long cutoffEpoch = periodDays > 0
                ? Instant.now().minusSeconds(periodDays * 24L * 60 * 60).getEpochSecond()
                : 0;

        String querySql = """
            SELECT timestamp, event_type, mcp_tool, mcp_workspace, execution_time_ms,
                   result_count, success, error_message, search_pattern, ai_feature,
                   ai_tokens_used, ai_retry
            FROM metrics
            WHERE timestamp >= ?
            ORDER BY timestamp DESC
            """;

        List<MetricsEvent> events = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(querySql)) {
            ps.setLong(1, cutoffEpoch);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    events.add(MetricsEvent.fromResultSet(rs));
                }
            }
        }
        return events;
    }

    /**
     * Returns aggregate statistics for MCP tool usage.
     */
    public synchronized Map<String, ToolStats> getToolStats(int periodDays) throws SQLException {
        long cutoffEpoch = periodDays > 0
                ? Instant.now().minusSeconds(periodDays * 24L * 60 * 60).getEpochSecond()
                : 0;

        String querySql = """
            SELECT mcp_tool, COUNT(*) as count, AVG(execution_time_ms) as avg_time,
                   MAX(execution_time_ms) as max_time, SUM(CASE WHEN success = 1 THEN 1 ELSE 0 END) as success_count
            FROM metrics
            WHERE mcp_tool IS NOT NULL AND timestamp >= ?
            GROUP BY mcp_tool
            ORDER BY count DESC
            """;

        Map<String, ToolStats> stats = new LinkedHashMap<>();
        try (PreparedStatement ps = connection.prepareStatement(querySql)) {
            ps.setLong(1, cutoffEpoch);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String tool = rs.getString("mcp_tool");
                    int count = rs.getInt("count");
                    double avgTime = rs.getDouble("avg_time");
                    long maxTime = rs.getLong("max_time");
                    int successCount = rs.getInt("success_count");

                    stats.put(tool, new ToolStats(tool, count, avgTime, maxTime, successCount));
                }
            }
        }
        return stats;
    }

    /**
     * Returns the database file size in bytes.
     */
    public long getDatabaseSize() {
        try {
            return Files.size(dbPath);
        } catch (IOException e) {
            return -1;
        }
    }

    /**
     * Returns the total number of metrics records.
     */
    public synchronized int getRecordCount() throws SQLException {
        String querySql = "SELECT COUNT(*) FROM metrics";
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(querySql)) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    /**
     * Closes the database connection.
     */
    @Override
    public synchronized void close() throws SQLException {
        if (connection != null && !connection.isClosed()) {
            connection.close();
            LOG.info("Metrics database closed");
        }
    }

    /**
     * Statistics for a specific MCP tool.
     */
    public record ToolStats(
            String toolName,
            int invocationCount,
            double avgExecutionTimeMs,
            long maxExecutionTimeMs,
            int successCount
    ) {
        public double successRate() {
            return invocationCount > 0 ? (double) successCount / invocationCount * 100.0 : 0.0;
        }
    }
}
