package io.exoreaction.synthesis.metrics;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;

/**
 * Represents a single metrics event.
 *
 * <p>Privacy-safe by design: contains ONLY operational metadata,
 * never workspace content, file names, user data, or credentials.
 *
 * @param timestamp when the event occurred
 * @param eventType type of event (mcp_tool_invocation, search, ai_feature)
 * @param mcpTool MCP tool name (search, relate, graph, stats, ask, enrich, explain)
 * @param mcpWorkspace workspace path
 * @param executionTimeMs execution duration in milliseconds
 * @param resultCount number of results returned
 * @param success whether the operation succeeded
 * @param errorMessage error message if failed
 * @param searchPattern search pattern metadata (e.g., "terms:3 operators:AND")
 * @param aiFeature AI feature name (ask, explain, enrich)
 * @param aiTokensUsed tokens consumed by AI call
 * @param aiRetry whether AI call was retried
 */
public record MetricsEvent(
        Instant timestamp,
        String eventType,
        String mcpTool,
        String mcpWorkspace,
        Long executionTimeMs,
        Integer resultCount,
        boolean success,
        String errorMessage,
        String searchPattern,
        String aiFeature,
        Integer aiTokensUsed,
        Boolean aiRetry
) {

    /**
     * Creates a metrics event from a database ResultSet.
     *
     * <p>Uses untyped {@code getObject()} then casts with null-safety
     * because SQLite JDBC throws "Bad value for type Integer/Long"
     * when reading NULL columns via typed {@code getObject(col, Class)}.
     */
    public static MetricsEvent fromResultSet(ResultSet rs) throws SQLException {
        Object execTimeRaw = rs.getObject("execution_time_ms");
        Long executionTimeMs = execTimeRaw instanceof Number n ? n.longValue() : null;

        Object resultCountRaw = rs.getObject("result_count");
        Integer resultCount = resultCountRaw instanceof Number n ? n.intValue() : null;

        Object aiTokensRaw = rs.getObject("ai_tokens_used");
        Integer aiTokensUsed = aiTokensRaw instanceof Number n ? n.intValue() : null;

        Object aiRetryRaw = rs.getObject("ai_retry");
        Boolean aiRetry = aiRetryRaw instanceof Number n ? (n.intValue() != 0) : null;

        return new MetricsEvent(
                Instant.ofEpochSecond(rs.getLong("timestamp")),
                rs.getString("event_type"),
                rs.getString("mcp_tool"),
                rs.getString("mcp_workspace"),
                executionTimeMs,
                resultCount,
                rs.getInt("success") == 1,
                rs.getString("error_message"),
                rs.getString("search_pattern"),
                rs.getString("ai_feature"),
                aiTokensUsed,
                aiRetry
        );
    }

    /**
     * Builder for creating metrics events.
     */
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private Instant timestamp = Instant.now();
        private String eventType = "mcp_tool_invocation";
        private String mcpTool;
        private String mcpWorkspace;
        private Long executionTimeMs;
        private Integer resultCount;
        private boolean success = true;
        private String errorMessage;
        private String searchPattern;
        private String aiFeature;
        private Integer aiTokensUsed;
        private Boolean aiRetry;

        public Builder timestamp(Instant timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        public Builder eventType(String eventType) {
            this.eventType = eventType;
            return this;
        }

        public Builder mcpTool(String mcpTool) {
            this.mcpTool = mcpTool;
            return this;
        }

        public Builder mcpWorkspace(String mcpWorkspace) {
            this.mcpWorkspace = mcpWorkspace;
            return this;
        }

        public Builder executionTimeMs(long executionTimeMs) {
            this.executionTimeMs = executionTimeMs;
            return this;
        }

        public Builder resultCount(int resultCount) {
            this.resultCount = resultCount;
            return this;
        }

        public Builder success(boolean success) {
            this.success = success;
            return this;
        }

        public Builder errorMessage(String errorMessage) {
            this.errorMessage = errorMessage;
            return this;
        }

        public Builder searchPattern(String searchPattern) {
            this.searchPattern = searchPattern;
            return this;
        }

        public Builder aiFeature(String aiFeature) {
            this.aiFeature = aiFeature;
            return this;
        }

        public Builder aiTokensUsed(int aiTokensUsed) {
            this.aiTokensUsed = aiTokensUsed;
            return this;
        }

        public Builder aiRetry(boolean aiRetry) {
            this.aiRetry = aiRetry;
            return this;
        }

        public MetricsEvent build() {
            return new MetricsEvent(
                    timestamp, eventType, mcpTool, mcpWorkspace, executionTimeMs,
                    resultCount, success, errorMessage, searchPattern, aiFeature,
                    aiTokensUsed, aiRetry
            );
        }
    }
}
