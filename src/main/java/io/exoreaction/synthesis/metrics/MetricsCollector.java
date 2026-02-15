package io.exoreaction.synthesis.metrics;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Asynchronous metrics collection service.
 *
 * <p>Collects operational metrics about MCP tool usage and stores them in a local
 * SQLite database. Collection is async (never blocks command execution) and
 * privacy-safe (only patterns and performance, never content).
 *
 * <p>Thread-safe: Events are queued on a single daemon thread.
 *
 * <p>Usage:
 * <pre>
 *   MetricsCollector collector = MetricsCollector.create();
 *   collector.recordMcpInvocation("search", workspace, 123, 42, true, null);
 *   collector.shutdown(); // in app shutdown hook
 * </pre>
 */
public class MetricsCollector {

    private static final Logger LOG = Logger.getLogger(MetricsCollector.class.getName());

    private final MetricsDatabase database;
    private final ExecutorService executor;
    private final boolean enabled;

    /**
     * Creates a MetricsCollector with the given database.
     *
     * @param database metrics database
     * @param enabled whether metrics collection is enabled
     */
    public MetricsCollector(MetricsDatabase database, boolean enabled) {
        this.database = database;
        this.enabled = enabled;

        if (enabled) {
            this.executor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "synthesis-metrics");
                t.setDaemon(true);
                return t;
            });
        } else {
            this.executor = null;
        }
    }

    /**
     * Creates a MetricsCollector using the default database path.
     *
     * <p>Metrics collection is enabled by default. To disable, set the environment
     * variable {@code SYNTHESIS_METRICS_ENABLED=false}.
     *
     * @return configured MetricsCollector
     */
    public static MetricsCollector create() {
        boolean enabled = isMetricsEnabled();

        if (!enabled) {
            LOG.info("Metrics collection disabled (SYNTHESIS_METRICS_ENABLED=false)");
            return new MetricsCollector(null, false);
        }

        try {
            Path dbPath = MetricsDatabase.getDefaultPath();
            MetricsDatabase database = new MetricsDatabase(dbPath);
            LOG.info("Metrics collector initialized: " + dbPath);
            return new MetricsCollector(database, true);
        } catch (SQLException e) {
            LOG.warning("Failed to initialize metrics database: " + e.getMessage());
            return new MetricsCollector(null, false);
        }
    }

    /**
     * Returns whether metrics collection is enabled.
     */
    private static boolean isMetricsEnabled() {
        String envValue = System.getenv("SYNTHESIS_METRICS_ENABLED");
        if (envValue != null && !envValue.isBlank()) {
            return Boolean.parseBoolean(envValue);
        }
        return true; // enabled by default
    }

    /**
     * Records an MCP tool invocation.
     *
     * @param tool MCP tool name (search, relate, graph, stats, ask, enrich, explain)
     * @param workspace workspace path
     * @param executionTimeMs execution duration in milliseconds
     * @param resultCount number of results returned (or null)
     * @param success whether the operation succeeded
     * @param errorMessage error message if failed (or null)
     */
    public void recordMcpInvocation(String tool, String workspace, long executionTimeMs,
                                     Integer resultCount, boolean success, String errorMessage) {
        if (!enabled) return;

        MetricsEvent event = MetricsEvent.builder()
                .eventType("mcp_tool_invocation")
                .mcpTool(tool)
                .mcpWorkspace(workspace)
                .executionTimeMs(executionTimeMs)
                .resultCount(resultCount)
                .success(success)
                .errorMessage(errorMessage)
                .build();

        recordEvent(event);
    }

    /**
     * Records a search operation with pattern metadata.
     *
     * @param workspace workspace path
     * @param executionTimeMs execution duration in milliseconds
     * @param resultCount number of results returned
     * @param searchPattern pattern metadata (e.g., "terms:3 operators:AND")
     * @param success whether the search succeeded
     */
    public void recordSearch(String workspace, long executionTimeMs, int resultCount,
                             String searchPattern, boolean success) {
        if (!enabled) return;

        MetricsEvent event = MetricsEvent.builder()
                .eventType("search")
                .mcpTool("search")
                .mcpWorkspace(workspace)
                .executionTimeMs(executionTimeMs)
                .resultCount(resultCount)
                .searchPattern(searchPattern)
                .success(success)
                .build();

        recordEvent(event);
    }

    /**
     * Records an AI feature usage.
     *
     * @param feature AI feature name (ask, explain, enrich)
     * @param workspace workspace path
     * @param executionTimeMs execution duration in milliseconds
     * @param tokensUsed tokens consumed by AI call
     * @param success whether the call succeeded
     * @param retry whether the call was retried
     */
    public void recordAiFeature(String feature, String workspace, long executionTimeMs,
                                int tokensUsed, boolean success, boolean retry) {
        if (!enabled) return;

        MetricsEvent event = MetricsEvent.builder()
                .eventType("ai_feature")
                .mcpTool(feature)
                .aiFeature(feature)
                .mcpWorkspace(workspace)
                .executionTimeMs(executionTimeMs)
                .aiTokensUsed(tokensUsed)
                .success(success)
                .aiRetry(retry)
                .build();

        recordEvent(event);
    }

    /**
     * Records a metrics event asynchronously.
     */
    private void recordEvent(MetricsEvent event) {
        if (!enabled || executor == null) return;

        executor.submit(() -> {
            try {
                database.recordEvent(event);
            } catch (Exception e) {
                // Silently ignore -- metrics collection should never affect the user
                LOG.fine("Failed to record metrics event: " + e.getMessage());
            }
        });
    }

    /**
     * Returns whether metrics collection is enabled.
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Returns the metrics database (or null if disabled).
     */
    public MetricsDatabase getDatabase() {
        return database;
    }

    /**
     * Shuts down the metrics collector, waiting up to 2 seconds for pending events.
     */
    public void shutdown() {
        if (executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        if (database != null) {
            try {
                database.close();
            } catch (SQLException e) {
                LOG.warning("Failed to close metrics database: " + e.getMessage());
            }
        }
    }
}
