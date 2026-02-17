package io.exoreaction.synthesis.research;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Caches research reports to avoid expensive regeneration.
 *
 * <p>Cache entries are keyed by:
 * <ul>
 *   <li>Workspace path</li>
 *   <li>Target (chatgpt, notebooklm-infographic, notebooklm-presentation)</li>
 *   <li>Topic (full, architecture, security, etc.)</li>
 *   <li>Passes (comma-separated pass names)</li>
 *   <li>Index fingerprint (detects when index changes)</li>
 * </ul>
 *
 * <p>Follows the same pattern as {@link io.exoreaction.synthesis.summary.SummaryCache}.
 */
public class ResearchCache {

    private final Connection connection;
    private final ObjectMapper mapper;

    /**
     * Creates a research cache.
     *
     * @param connection database connection
     */
    public ResearchCache(Connection connection) {
        this.connection = connection;
        this.mapper = new ObjectMapper();
        this.mapper.registerModule(new JavaTimeModule());
    }

    /**
     * Retrieves a cached research result if available.
     *
     * @param workspacePath    workspace root path
     * @param target           research target
     * @param topic            research topic
     * @param passes           comma-separated pass names
     * @param indexFingerprint  current index state hash
     * @return cached result if available, empty otherwise
     */
    public Optional<ResearchResult> get(Path workspacePath, ResearchTarget target,
                                         ResearchTopic topic, String passes,
                                         String indexFingerprint) {
        String sql = """
            SELECT target, topic, passes, model, report_content, pass_results,
                   token_count, estimated_cost_usd, created_at
            FROM research_cache
            WHERE workspace_path = ?
              AND target = ?
              AND topic = ?
              AND passes = ?
              AND index_fingerprint = ?
            """;

        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, workspacePath.toString());
            stmt.setString(2, target.cliValue());
            stmt.setString(3, topic.cliValue());
            stmt.setString(4, passes);
            stmt.setString(5, indexFingerprint);

            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                // Deserialize pass results from JSON
                String passResultsJson = rs.getString("pass_results");
                List<ResearchPassResult> passResults = mapper.readValue(
                        passResultsJson, new TypeReference<List<ResearchPassResult>>() {});

                String model = rs.getString("model");
                String reportContent = rs.getString("report_content");
                int tokenCount = rs.getInt("token_count");
                double estimatedCost = rs.getDouble("estimated_cost_usd");
                Instant createdAt = Instant.parse(rs.getString("created_at"));

                // Increment hit counter
                incrementHits(workspacePath, target, topic, passes, indexFingerprint);

                ResearchResult result = ResearchResult.fromCache(
                        target, topic, passResults, reportContent, model,
                        tokenCount, estimatedCost, createdAt, 0);

                return Optional.of(result);
            }

            return Optional.empty();

        } catch (Exception e) {
            System.err.println("Warning: Research cache lookup failed: " + e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Stores a research result in the cache.
     *
     * @param workspacePath    workspace root path
     * @param result           the research result to cache
     * @param indexFingerprint  current index state hash
     */
    public void put(Path workspacePath, ResearchResult result, String indexFingerprint) {
        String sql = """
            INSERT OR REPLACE INTO research_cache
            (workspace_path, target, topic, passes, index_fingerprint,
             model, report_content, pass_results, token_count, estimated_cost_usd, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            // Serialize pass results to JSON
            String passResultsJson = mapper.writeValueAsString(result.passes());

            String passesStr = String.join(",", result.passNames());

            stmt.setString(1, workspacePath.toString());
            stmt.setString(2, result.target().cliValue());
            stmt.setString(3, result.topic().cliValue());
            stmt.setString(4, passesStr);
            stmt.setString(5, indexFingerprint);
            stmt.setString(6, result.model());
            stmt.setString(7, result.finalReport());
            stmt.setString(8, passResultsJson);
            stmt.setInt(9, result.totalTokenCount());
            stmt.setDouble(10, result.estimatedCostUsd());
            stmt.setString(11, result.generatedAt().toString());

            stmt.executeUpdate();

        } catch (Exception e) {
            System.err.println("Warning: Research cache storage failed: " + e.getMessage());
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
            FROM research_cache
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
        String sql = "DELETE FROM research_cache WHERE workspace_path = ?";

        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, workspacePath.toString());
            return stmt.executeUpdate();
        } catch (SQLException e) {
            System.err.println("Warning: Research cache clear failed: " + e.getMessage());
            return 0;
        }
    }

    private void incrementHits(Path workspacePath, ResearchTarget target,
                               ResearchTopic topic, String passes,
                               String indexFingerprint) {
        String sql = """
            UPDATE research_cache
            SET hits = hits + 1
            WHERE workspace_path = ?
              AND target = ?
              AND topic = ?
              AND passes = ?
              AND index_fingerprint = ?
            """;

        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, workspacePath.toString());
            stmt.setString(2, target.cliValue());
            stmt.setString(3, topic.cliValue());
            stmt.setString(4, passes);
            stmt.setString(5, indexFingerprint);
            stmt.executeUpdate();
        } catch (SQLException e) {
            // Ignore - hit counter is not critical
        }
    }

    /**
     * Cache statistics for research reports.
     */
    public record CacheStats(int entries, long totalHits, long totalTokens, double totalCostUsd) {}
}
