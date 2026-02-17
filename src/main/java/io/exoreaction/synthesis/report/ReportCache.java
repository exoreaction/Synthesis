package io.exoreaction.synthesis.report;

import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.Optional;

/**
 * Caches executive reports to avoid expensive AI regeneration.
 *
 * <p>Cache entries are keyed by:
 * <ul>
 *   <li>Workspace path</li>
 *   <li>Topic (weekly, pipeline, activities, executive, decisions)</li>
 *   <li>Target (ceo, board, investor)</li>
 *   <li>Period (1w, 2w, 1m)</li>
 *   <li>Document fingerprint (detects when source documents change)</li>
 * </ul>
 *
 * <p>Follows the same pattern as {@link io.exoreaction.synthesis.research.ResearchCache}.
 */
public class ReportCache {

    private final Connection connection;

    /**
     * Creates a report cache.
     *
     * @param connection database connection
     */
    public ReportCache(Connection connection) {
        this.connection = connection;
    }

    /**
     * Retrieves a cached report result if available.
     *
     * @param workspacePath        workspace root path
     * @param topic                report topic
     * @param target               report target
     * @param period               coverage period
     * @param documentFingerprint  hash of discovered doc paths + mtimes
     * @return cached result if available, empty otherwise
     */
    public Optional<ReportResult> get(Path workspacePath, ReportTopic topic,
                                       ReportTarget target, String period,
                                       String documentFingerprint) {
        String sql = """
            SELECT model, report_content, token_count, estimated_cost_usd, created_at
            FROM report_cache
            WHERE workspace_path = ?
              AND topic = ?
              AND target = ?
              AND period = ?
              AND document_fingerprint = ?
            """;

        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, workspacePath.toString());
            stmt.setString(2, topic.cliValue());
            stmt.setString(3, target.cliValue());
            stmt.setString(4, period);
            stmt.setString(5, documentFingerprint);

            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                String model = rs.getString("model");
                String reportContent = rs.getString("report_content");
                int tokenCount = rs.getInt("token_count");
                double estimatedCost = rs.getDouble("estimated_cost_usd");
                Instant createdAt = Instant.parse(rs.getString("created_at"));

                // Increment hit counter
                incrementHits(workspacePath, topic, target, period, documentFingerprint);

                ReportResult result = ReportResult.fromCache(
                        target, topic, reportContent, model,
                        tokenCount, estimatedCost, createdAt, period);

                return Optional.of(result);
            }

            return Optional.empty();

        } catch (Exception e) {
            System.err.println("Warning: Report cache lookup failed: " + e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Stores a report result in the cache.
     *
     * @param workspacePath        workspace root path
     * @param result               the report result to cache
     * @param documentFingerprint  hash of discovered doc paths + mtimes
     */
    public void put(Path workspacePath, ReportResult result, String documentFingerprint) {
        String sql = """
            INSERT OR REPLACE INTO report_cache
            (workspace_path, topic, target, period, document_fingerprint,
             model, report_content, token_count, estimated_cost_usd, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, workspacePath.toString());
            stmt.setString(2, result.topic().cliValue());
            stmt.setString(3, result.target().cliValue());
            stmt.setString(4, result.period());
            stmt.setString(5, documentFingerprint);
            stmt.setString(6, result.model());
            stmt.setString(7, result.finalReport());
            stmt.setInt(8, result.totalTokenCount());
            stmt.setDouble(9, result.estimatedCostUsd());
            stmt.setString(10, result.generatedAt().toString());

            stmt.executeUpdate();

        } catch (Exception e) {
            System.err.println("Warning: Report cache storage failed: " + e.getMessage());
        }
    }

    /**
     * Gets cache statistics for a workspace.
     */
    public CacheStats getStats(Path workspacePath) {
        String sql = """
            SELECT COUNT(*) as total,
                   COALESCE(SUM(hits), 0) as total_hits,
                   COALESCE(SUM(token_count), 0) as total_tokens,
                   COALESCE(SUM(estimated_cost_usd), 0) as total_cost
            FROM report_cache
            WHERE workspace_path = ?
            """;

        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, workspacePath.toString());
            ResultSet rs = stmt.executeQuery();

            if (rs.next()) {
                return new CacheStats(
                        rs.getInt("total"),
                        rs.getLong("total_hits"),
                        rs.getLong("total_tokens"),
                        rs.getDouble("total_cost")
                );
            }

            return new CacheStats(0, 0, 0, 0.0);

        } catch (SQLException e) {
            return new CacheStats(0, 0, 0, 0.0);
        }
    }

    /**
     * Clears all cache entries for a workspace.
     *
     * @param workspacePath workspace to clear
     * @return number of entries removed
     */
    public int clearWorkspace(Path workspacePath) {
        String sql = "DELETE FROM report_cache WHERE workspace_path = ?";

        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, workspacePath.toString());
            return stmt.executeUpdate();
        } catch (SQLException e) {
            System.err.println("Warning: Report cache clear failed: " + e.getMessage());
            return 0;
        }
    }

    private void incrementHits(Path workspacePath, ReportTopic topic,
                               ReportTarget target, String period,
                               String documentFingerprint) {
        String sql = """
            UPDATE report_cache
            SET hits = hits + 1
            WHERE workspace_path = ?
              AND topic = ?
              AND target = ?
              AND period = ?
              AND document_fingerprint = ?
            """;

        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, workspacePath.toString());
            stmt.setString(2, topic.cliValue());
            stmt.setString(3, target.cliValue());
            stmt.setString(4, period);
            stmt.setString(5, documentFingerprint);
            stmt.executeUpdate();
        } catch (SQLException e) {
            // Ignore -- hit counter is not critical
        }
    }

    /**
     * Cache statistics for executive reports.
     */
    public record CacheStats(int entries, long totalHits, long totalTokens, double totalCostUsd) {}
}
