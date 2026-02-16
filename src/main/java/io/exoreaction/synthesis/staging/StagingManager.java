package io.exoreaction.synthesis.staging;

import io.exoreaction.synthesis.config.SynthesisConfig;
import io.exoreaction.synthesis.config.SynthesisConfig.SubWorkspaceConfig;
import io.exoreaction.synthesis.db.SynthesisDatabase;
import io.exoreaction.synthesis.org.DownloadsClassifier;
import io.exoreaction.synthesis.org.DownloadsClassifier.ClassificationResult;
import io.exoreaction.synthesis.org.OrganizationRegistry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Manages the staging sub-workspace lifecycle: ingestion, classification,
 * promotion, and expiry of incoming files.
 *
 * <p>The staging workflow:
 * <ol>
 *   <li><strong>Ingest:</strong> New files detected in a staging sub-workspace
 *       are registered in the database with retention metadata.</li>
 *   <li><strong>Classify:</strong> If auto-classify is enabled, each file is
 *       analyzed by {@link DownloadsClassifier} to determine its likely
 *       organization/sub-workspace destination.</li>
 *   <li><strong>Promote:</strong> Files can be manually or automatically promoted
 *       to a permanent sub-workspace, moving them physically and updating
 *       the index.</li>
 *   <li><strong>Expire:</strong> Files that exceed the retention period without
 *       being promoted are flagged for cleanup.</li>
 * </ol>
 *
 * <p>Thread safety: methods that modify the database are synchronized on
 * the SynthesisDatabase connection.
 *
 * @since v1.4.0
 */
public class StagingManager {

    private static final Logger LOG = Logger.getLogger(StagingManager.class.getName());

    private final SynthesisDatabase database;
    private final SynthesisConfig.StagingConfig config;
    private final Path workspaceRoot;
    private final String workspacePath;

    /**
     * A staged file record from the database.
     *
     * @param id                        database row ID
     * @param relativePath              path relative to workspace root
     * @param subWorkspace              staging sub-workspace name
     * @param fileSize                  file size in bytes
     * @param fileType                  detected file type
     * @param contentHash               content hash for dedup
     * @param classifiedOrg             classified organization (may be null)
     * @param classificationConfidence  classification confidence (0.0-1.0)
     * @param suggestedDestination      suggested destination path
     * @param status                    staging status: pending, promoted, expired, deleted
     * @param ingestedAt                time of ingestion
     * @param expiresAt                 time of expiration
     * @param promotedAt                time of promotion (null if not promoted)
     * @param promotedTo                promotion destination (null if not promoted)
     */
    public record StagedFile(
            long id,
            String relativePath,
            String subWorkspace,
            long fileSize,
            String fileType,
            String contentHash,
            String classifiedOrg,
            double classificationConfidence,
            String suggestedDestination,
            String status,
            Instant ingestedAt,
            Instant expiresAt,
            Instant promotedAt,
            String promotedTo
    ) {
        /** Returns whether this file has exceeded its retention period. */
        public boolean isExpired() {
            return "expired".equals(status) || Instant.now().isAfter(expiresAt);
        }

        /** Returns whether this file has been promoted to a permanent location. */
        public boolean isPromoted() {
            return "promoted".equals(status);
        }

        /** Returns whether this file is still pending classification/promotion. */
        public boolean isPending() {
            return "pending".equals(status);
        }
    }

    /**
     * Summary statistics for staging operations.
     *
     * @param ingested  number of newly ingested files
     * @param classified number of auto-classified files
     * @param promoted  number of promoted files
     * @param expired   number of expired files
     * @param errors    number of errors encountered
     */
    public record StagingSummary(
            int ingested,
            int classified,
            int promoted,
            int expired,
            int errors
    ) {}

    /**
     * Creates a StagingManager for the given workspace.
     *
     * @param database      the shared Synthesis database
     * @param config        staging configuration
     * @param workspaceRoot the workspace root directory
     */
    public StagingManager(SynthesisDatabase database, SynthesisConfig.StagingConfig config,
                          Path workspaceRoot) {
        this.database = database;
        this.config = config;
        this.workspaceRoot = workspaceRoot;
        this.workspacePath = workspaceRoot.toString();
    }

    /**
     * Ingests a file into the staging system.
     *
     * <p>Registers the file in the staging_files table with retention metadata.
     * If auto-classify is enabled, also classifies the file.
     *
     * @param relativePath  the file's path relative to workspace root
     * @param subWorkspace  the staging sub-workspace name
     * @param fileSize      file size in bytes
     * @param fileType      detected file type (e.g., "CODE", "MARKDOWN")
     * @param contentHash   content hash for dedup (may be null)
     * @return the ingested StagedFile record
     */
    public StagedFile ingest(String relativePath, String subWorkspace,
                              long fileSize, String fileType, String contentHash) throws SQLException {
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(config.getRetentionDays() * 24L * 3600);

        Connection conn = database.getConnection();
        synchronized (database) {
            String sql = """
                INSERT OR REPLACE INTO staging_files
                (workspace_path, sub_workspace, relative_path, file_size, file_type,
                 content_hash, status, ingested_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, 'pending', ?, ?)
                """;

            try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, workspacePath);
                ps.setString(2, subWorkspace);
                ps.setString(3, relativePath);
                ps.setLong(4, fileSize);
                ps.setString(5, fileType);
                ps.setString(6, contentHash);
                ps.setLong(7, now.getEpochSecond());
                ps.setLong(8, expiresAt.getEpochSecond());
                ps.executeUpdate();

                ResultSet rs = ps.getGeneratedKeys();
                long id = rs.next() ? rs.getLong(1) : -1;

                return new StagedFile(id, relativePath, subWorkspace, fileSize, fileType,
                        contentHash, null, 0.0, null, "pending", now, expiresAt, null, null);
            }
        }
    }

    /**
     * Classifies a staged file using the DownloadsClassifier.
     *
     * <p>Updates the staging_files record with the classification result.
     *
     * @param stagedFile   the file to classify
     * @param classifier   the downloads classifier
     * @return the classification result
     */
    public ClassificationResult classify(StagedFile stagedFile, DownloadsClassifier classifier)
            throws SQLException {
        Path filePath = workspaceRoot.resolve(stagedFile.relativePath());
        if (!Files.exists(filePath)) {
            return null;
        }

        ClassificationResult result = classifier.classify(filePath);

        if (result.organization() != null) {
            Connection conn = database.getConnection();
            synchronized (database) {
                String sql = """
                    UPDATE staging_files SET
                        classified_org = ?,
                        classification_confidence = ?,
                        suggested_destination = ?
                    WHERE workspace_path = ? AND relative_path = ?
                    """;

                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, result.organization());
                    ps.setDouble(2, result.confidence());
                    ps.setString(3, result.suggestedDestination() != null
                            ? result.suggestedDestination().toString() : null);
                    ps.setString(4, workspacePath);
                    ps.setString(5, stagedFile.relativePath());
                    ps.executeUpdate();
                }
            }
        }

        return result;
    }

    /**
     * Promotes a staged file to a permanent sub-workspace.
     *
     * <p>Physically moves the file from the staging directory to the
     * destination sub-workspace directory, and updates the database record.
     *
     * @param stagedFile       the file to promote
     * @param targetSubWorkspace the target sub-workspace name
     * @param targetPath       the target relative path within the workspace
     * @return true if promotion succeeded
     */
    public boolean promote(StagedFile stagedFile, String targetSubWorkspace,
                            String targetPath) throws SQLException, IOException {
        Path sourcePath = workspaceRoot.resolve(stagedFile.relativePath());
        Path destPath = workspaceRoot.resolve(targetPath);

        if (!Files.exists(sourcePath)) {
            LOG.warning("Staged file not found: " + sourcePath);
            return false;
        }

        // Create destination directories
        Files.createDirectories(destPath.getParent());

        // Move the file
        Files.move(sourcePath, destPath, StandardCopyOption.REPLACE_EXISTING);

        // Update database
        Connection conn = database.getConnection();
        synchronized (database) {
            String sql = """
                UPDATE staging_files SET
                    status = 'promoted',
                    promoted_at = ?,
                    promoted_to = ?
                WHERE workspace_path = ? AND relative_path = ?
                """;

            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, Instant.now().getEpochSecond());
                ps.setString(2, targetSubWorkspace + ":" + targetPath);
                ps.setString(3, workspacePath);
                ps.setString(4, stagedFile.relativePath());
                ps.executeUpdate();
            }
        }

        LOG.info("Promoted: " + stagedFile.relativePath() + " -> " + targetPath);
        return true;
    }

    /**
     * Lists all staged files matching the given status filter.
     *
     * @param statusFilter optional status filter (null = all)
     * @return list of staged file records
     */
    public List<StagedFile> list(String statusFilter) throws SQLException {
        Connection conn = database.getConnection();
        List<StagedFile> results = new ArrayList<>();

        String sql = "SELECT * FROM staging_files WHERE workspace_path = ?";
        if (statusFilter != null && !statusFilter.isBlank()) {
            sql += " AND status = ?";
        }
        sql += " ORDER BY ingested_at DESC";

        synchronized (database) {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, workspacePath);
                if (statusFilter != null && !statusFilter.isBlank()) {
                    ps.setString(2, statusFilter);
                }

                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    results.add(fromResultSet(rs));
                }
            }
        }

        return results;
    }

    /**
     * Finds expired files that have exceeded the retention period.
     *
     * @return list of expired staged files
     */
    public List<StagedFile> findExpired() throws SQLException {
        Connection conn = database.getConnection();
        List<StagedFile> results = new ArrayList<>();
        long now = Instant.now().getEpochSecond();

        synchronized (database) {
            String sql = """
                SELECT * FROM staging_files
                WHERE workspace_path = ? AND status = 'pending' AND expires_at < ?
                ORDER BY expires_at ASC
                """;

            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, workspacePath);
                ps.setLong(2, now);

                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    results.add(fromResultSet(rs));
                }
            }
        }

        return results;
    }

    /**
     * Marks expired files and optionally deletes them from the filesystem.
     *
     * @return the number of files expired
     */
    public int processExpired() throws SQLException {
        List<StagedFile> expired = findExpired();
        if (expired.isEmpty()) return 0;

        Connection conn = database.getConnection();
        int count = 0;

        synchronized (database) {
            for (StagedFile file : expired) {
                String sql = "UPDATE staging_files SET status = 'expired' WHERE id = ?";
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setLong(1, file.id());
                    ps.executeUpdate();
                }

                // Optionally delete the file from the filesystem
                if (config.isCleanupExpired()) {
                    Path filePath = workspaceRoot.resolve(file.relativePath());
                    try {
                        if (Files.exists(filePath)) {
                            Files.delete(filePath);
                            LOG.info("Cleaned up expired file: " + file.relativePath());
                        }
                    } catch (IOException e) {
                        LOG.warning("Failed to delete expired file: " + file.relativePath()
                                + ": " + e.getMessage());
                    }
                }

                count++;
            }
        }

        return count;
    }

    /**
     * Returns staging statistics for the workspace.
     *
     * @return summary of staging state
     */
    public StagingSummary getStats() throws SQLException {
        Connection conn = database.getConnection();
        int pending = 0, promoted = 0, expired = 0;

        synchronized (database) {
            String sql = """
                SELECT status, COUNT(*) as cnt
                FROM staging_files
                WHERE workspace_path = ?
                GROUP BY status
                """;

            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, workspacePath);
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    String status = rs.getString("status");
                    int cnt = rs.getInt("cnt");
                    switch (status) {
                        case "pending" -> pending = cnt;
                        case "promoted" -> promoted = cnt;
                        case "expired" -> expired = cnt;
                    }
                }
            }
        }

        return new StagingSummary(pending, 0, promoted, expired, 0);
    }

    /**
     * Finds staging sub-workspaces from the configuration.
     *
     * @param subWorkspaces the list of sub-workspace configurations
     * @return staging sub-workspace configs
     */
    public static List<SubWorkspaceConfig> findStagingSubWorkspaces(
            List<SubWorkspaceConfig> subWorkspaces) {
        if (subWorkspaces == null) return List.of();
        return subWorkspaces.stream()
                .filter(SubWorkspaceConfig::isStaging)
                .toList();
    }

    /**
     * Converts a ResultSet row to a StagedFile record.
     */
    private StagedFile fromResultSet(ResultSet rs) throws SQLException {
        return new StagedFile(
                rs.getLong("id"),
                rs.getString("relative_path"),
                rs.getString("sub_workspace"),
                rs.getLong("file_size"),
                rs.getString("file_type"),
                rs.getString("content_hash"),
                rs.getString("classified_org"),
                rs.getDouble("classification_confidence"),
                rs.getString("suggested_destination"),
                rs.getString("status"),
                Instant.ofEpochSecond(rs.getLong("ingested_at")),
                Instant.ofEpochSecond(rs.getLong("expires_at")),
                rs.getLong("promoted_at") > 0
                        ? Instant.ofEpochSecond(rs.getLong("promoted_at")) : null,
                rs.getString("promoted_to")
        );
    }
}
