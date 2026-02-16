package io.exoreaction.synthesis.summary;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.exoreaction.synthesis.summary.CodebaseProfile.Profile;

import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.*;
import java.time.Instant;
import java.util.Optional;

/**
 * Caches AI-enhanced summaries to avoid expensive regeneration.
 *
 * <p>Cache entries are keyed by:
 * <ul>
 *   <li>Workspace path</li>
 *   <li>Summary level (executive/manager/developer)</li>
 *   <li>Perspective (general/executive/architect/etc.)</li>
 *   <li>Index fingerprint (detects when index changes)</li>
 * </ul>
 *
 * <p>Cache is automatically invalidated when the index changes.
 */
public class SummaryCache {

    private final Connection connection;
    private final ObjectMapper mapper;
    private final long ttlSeconds;

    /**
     * Creates a summary cache.
     *
     * @param connection database connection
     * @param ttlSeconds time-to-live in seconds (0 = never expire)
     */
    public SummaryCache(Connection connection, long ttlSeconds) {
        this.connection = connection;
        this.ttlSeconds = ttlSeconds;
        this.mapper = new ObjectMapper();
        this.mapper.registerModule(new JavaTimeModule());
    }

    /**
     * Retrieves a cached summary if available and not expired.
     *
     * @param workspacePath workspace root path
     * @param level summary detail level
     * @param perspective role-based perspective
     * @param indexFingerprint current index state hash
     * @return cached result if available, empty otherwise
     */
    public Optional<SummaryResult> get(Path workspacePath,
                                       SummaryLevel level,
                                       SummaryPerspective perspective,
                                       String indexFingerprint) {
        String sql = """
            SELECT profile_json, ai_summary, generation_time_ms, created_at, model_used, expires_at
            FROM summary_cache
            WHERE workspace_path = ?
              AND summary_level = ?
              AND perspective = ?
              AND index_fingerprint = ?
              AND (expires_at IS NULL OR expires_at > ?)
            """;

        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, workspacePath.toString());
            stmt.setString(2, level.cliValue());
            stmt.setString(3, perspective.cliValue());
            stmt.setString(4, indexFingerprint);
            stmt.setTimestamp(5, Timestamp.from(Instant.now()));

            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                // Deserialize profile
                String profileJson = rs.getString("profile_json");
                Profile profile = mapper.readValue(profileJson, Profile.class);

                // Get other fields
                String aiSummary = rs.getString("ai_summary");
                long generationTimeMs = rs.getLong("generation_time_ms");
                Instant createdAt = rs.getTimestamp("created_at").toInstant();

                // Increment hit counter
                incrementHits(workspacePath, level, perspective, indexFingerprint);

                // Create result
                SummaryResult result = new SummaryResult(
                    profile,
                    aiSummary,
                    level,
                    perspective,
                    null,  // temporal context (Phase 5)
                    createdAt,
                    generationTimeMs,
                    true,  // fromCache = true
                    indexFingerprint
                );

                return Optional.of(result);
            }

            return Optional.empty();

        } catch (Exception e) {
            // Cache failures should not break functionality
            System.err.println("Warning: Cache lookup failed: " + e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Stores a summary in the cache.
     *
     * @param workspacePath workspace root path
     * @param level summary detail level
     * @param perspective role-based perspective
     * @param indexFingerprint current index state hash
     * @param result the result to cache
     * @param modelUsed the AI model used (null if --no-ai)
     */
    public void put(Path workspacePath,
                    SummaryLevel level,
                    SummaryPerspective perspective,
                    String indexFingerprint,
                    SummaryResult result,
                    String modelUsed) {
        String sql = """
            INSERT OR REPLACE INTO summary_cache
            (workspace_path, summary_level, perspective, index_fingerprint,
             profile_json, ai_summary, generation_time_ms, model_used, expires_at, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            // Serialize profile to JSON
            String profileJson = mapper.writeValueAsString(result.profile());

            // Calculate expiration
            Timestamp expiresAt = ttlSeconds > 0 ?
                Timestamp.from(Instant.now().plusSeconds(ttlSeconds)) : null;

            stmt.setString(1, workspacePath.toString());
            stmt.setString(2, level.cliValue());
            stmt.setString(3, perspective.cliValue());
            stmt.setString(4, indexFingerprint);
            stmt.setString(5, profileJson);
            stmt.setString(6, result.aiSummary());
            stmt.setLong(7, result.generationTimeMs());
            stmt.setString(8, modelUsed);
            stmt.setTimestamp(9, expiresAt);
            stmt.setTimestamp(10, Timestamp.from(result.generatedAt()));

            stmt.executeUpdate();

        } catch (Exception e) {
            // Cache failures should not break functionality
            System.err.println("Warning: Cache storage failed: " + e.getMessage());
        }
    }

    /**
     * Generates a fingerprint of the current index state.
     *
     * <p>The fingerprint changes when the index is updated, automatically
     * invalidating cached summaries.
     *
     * @param indexPath path to the index directory
     * @return fingerprint hash
     */
    public static String generateIndexFingerprint(Path indexPath) {
        try {
            // Get index metadata
            Path indexDir = indexPath.resolve("index");
            if (!indexDir.toFile().exists()) {
                return "no-index";
            }

            // Hash directory last modified time + file count
            long lastModified = indexDir.toFile().lastModified();
            int fileCount = indexDir.toFile().listFiles() != null ?
                indexDir.toFile().listFiles().length : 0;

            String input = lastModified + ":" + fileCount;

            // Generate SHA-256 hash
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes());

            // Convert to hex string
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }

            return hexString.substring(0, 16);  // First 16 chars

        } catch (Exception e) {
            // Fallback to timestamp if hashing fails
            return String.valueOf(System.currentTimeMillis());
        }
    }

    /**
     * Clears expired cache entries.
     *
     * @return number of entries removed
     */
    public int clearExpired() {
        String sql = """
            DELETE FROM summary_cache
            WHERE expires_at IS NOT NULL AND expires_at < ?
            """;

        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setTimestamp(1, Timestamp.from(Instant.now()));
            return stmt.executeUpdate();
        } catch (SQLException e) {
            System.err.println("Warning: Cache cleanup failed: " + e.getMessage());
            return 0;
        }
    }

    /**
     * Clears all cache entries for a workspace.
     *
     * @param workspacePath workspace to clear
     * @return number of entries removed
     */
    public int clearWorkspace(Path workspacePath) {
        String sql = "DELETE FROM summary_cache WHERE workspace_path = ?";

        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, workspacePath.toString());
            return stmt.executeUpdate();
        } catch (SQLException e) {
            System.err.println("Warning: Cache clear failed: " + e.getMessage());
            return 0;
        }
    }

    /**
     * Gets cache statistics for a workspace.
     */
    public CacheStats getStats(Path workspacePath) {
        String sql = """
            SELECT COUNT(*) as total,
                   SUM(hits) as total_hits,
                   AVG(generation_time_ms) as avg_generation_time
            FROM summary_cache
            WHERE workspace_path = ?
            """;

        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, workspacePath.toString());
            ResultSet rs = stmt.executeQuery();

            if (rs.next()) {
                return new CacheStats(
                    rs.getInt("total"),
                    rs.getLong("total_hits"),
                    rs.getLong("avg_generation_time")
                );
            }

            return new CacheStats(0, 0, 0);

        } catch (SQLException e) {
            return new CacheStats(0, 0, 0);
        }
    }

    private void incrementHits(Path workspacePath,
                              SummaryLevel level,
                              SummaryPerspective perspective,
                              String indexFingerprint) {
        String sql = """
            UPDATE summary_cache
            SET hits = hits + 1
            WHERE workspace_path = ?
              AND summary_level = ?
              AND perspective = ?
              AND index_fingerprint = ?
            """;

        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, workspacePath.toString());
            stmt.setString(2, level.cliValue());
            stmt.setString(3, perspective.cliValue());
            stmt.setString(4, indexFingerprint);
            stmt.executeUpdate();
        } catch (SQLException e) {
            // Ignore - hit counter is not critical
        }
    }

    public record CacheStats(int entries, long totalHits, long avgGenerationTimeMs) {}
}
