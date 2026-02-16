package io.exoreaction.synthesis.changelog;

import io.exoreaction.synthesis.core.FileMetadata;
import io.exoreaction.synthesis.core.ScanResult;
import io.exoreaction.synthesis.db.SynthesisDatabase;

import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Manages workspace snapshots and change detection.
 *
 * <p>Takes snapshots (from scan results or fresh scans), compares them
 * to detect changes, and stores the results in the shared SQLite database.
 */
public class SnapshotManager {

    private static final Logger LOG = Logger.getLogger(SnapshotManager.class.getName());

    private final SynthesisDatabase db;
    private final SignificanceClassifier classifier;

    public SnapshotManager(SynthesisDatabase db) {
        this(db, new SignificanceClassifier());
    }

    public SnapshotManager(SynthesisDatabase db, SignificanceClassifier classifier) {
        this.db = db;
        this.classifier = classifier;
    }

    /**
     * Takes a snapshot from an existing scan result (no additional I/O needed).
     *
     * @param workspacePath workspace root path
     * @param workspaceName display name
     * @param scanResult    the scan result to snapshot
     * @param trigger       what triggered this snapshot (scan, maintain, scheduled, manual)
     * @return the snapshot ID
     */
    public synchronized long takeSnapshotFromScanResult(String workspacePath, String workspaceName,
                                                         ScanResult scanResult, String trigger)
            throws SQLException {

        Connection conn = db.getConnection();

        // Insert snapshot header
        long snapshotId;
        String insertSnapshot = """
            INSERT INTO workspace_snapshots (workspace_path, workspace_name, snapshot_time,
                                              file_count, total_size_bytes, trigger)
            VALUES (?, ?, ?, ?, ?, ?)
            """;
        try (PreparedStatement ps = conn.prepareStatement(insertSnapshot, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, workspacePath);
            ps.setString(2, workspaceName);
            ps.setLong(3, Instant.now().getEpochSecond());
            ps.setInt(4, scanResult.fileCount());
            ps.setLong(5, scanResult.totalSizeBytes());
            ps.setString(6, trigger);
            ps.executeUpdate();

            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (!keys.next()) throw new SQLException("Failed to get snapshot ID");
                snapshotId = keys.getLong(1);
            }
        }

        // Insert file entries in batches
        String insertEntry = """
            INSERT INTO snapshot_entries (snapshot_id, relative_path, content_hash,
                                          file_size, last_modified, file_type)
            VALUES (?, ?, ?, ?, ?, ?)
            """;
        try (PreparedStatement ps = conn.prepareStatement(insertEntry)) {
            int batch = 0;
            for (FileMetadata file : scanResult.files()) {
                ps.setLong(1, snapshotId);
                ps.setString(2, file.relativePath());
                ps.setString(3, file.contentHash());
                ps.setLong(4, file.sizeBytes());
                ps.setLong(5, file.lastModified().getEpochSecond());
                ps.setString(6, file.fileType() != null ? file.fileType().name() : null);
                ps.addBatch();

                if (++batch % 500 == 0) {
                    ps.executeBatch();
                }
            }
            ps.executeBatch();
        }

        LOG.info("Snapshot #" + snapshotId + " taken: " + scanResult.fileCount()
                + " files in " + workspaceName);
        return snapshotId;
    }

    /**
     * Returns the latest snapshot for a workspace.
     */
    public synchronized WorkspaceSnapshot getLatestSnapshot(String workspacePath) throws SQLException {
        String sql = """
            SELECT * FROM workspace_snapshots
            WHERE workspace_path = ?
            ORDER BY snapshot_time DESC
            LIMIT 1
            """;
        Connection conn = db.getConnection();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workspacePath);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return WorkspaceSnapshot.fromResultSet(rs);
                }
            }
        }
        return null;
    }

    /**
     * Returns all snapshots for a workspace, ordered by time descending.
     */
    public synchronized List<WorkspaceSnapshot> getSnapshots(String workspacePath, int limit) throws SQLException {
        String sql = """
            SELECT * FROM workspace_snapshots
            WHERE workspace_path = ?
            ORDER BY snapshot_time DESC
            LIMIT ?
            """;
        Connection conn = db.getConnection();
        List<WorkspaceSnapshot> snapshots = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workspacePath);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    snapshots.add(WorkspaceSnapshot.fromResultSet(rs));
                }
            }
        }
        return snapshots;
    }

    /**
     * Compares two snapshots and produces a list of change events.
     * Stores the events in the database and returns them.
     *
     * @param baseSnapshotId    the older snapshot (baseline)
     * @param compareSnapshotId the newer snapshot (comparison)
     * @return list of detected change events
     */
    public synchronized List<ChangeEvent> compareSnapshots(long baseSnapshotId,
                                                            long compareSnapshotId) throws SQLException {
        Connection conn = db.getConnection();

        // Get workspace path from snapshot
        String workspacePath = getSnapshotWorkspace(conn, compareSnapshotId);
        Instant now = Instant.now();

        List<ChangeEvent> events = new ArrayList<>();

        // Find ADDED files (in compare but not in base)
        String addedSql = """
            SELECT c.* FROM snapshot_entries c
            LEFT JOIN snapshot_entries b ON b.snapshot_id = ? AND b.relative_path = c.relative_path
            WHERE c.snapshot_id = ? AND b.id IS NULL
            """;
        try (PreparedStatement ps = conn.prepareStatement(addedSql)) {
            ps.setLong(1, baseSnapshotId);
            ps.setLong(2, compareSnapshotId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String path = rs.getString("relative_path");
                    String fileType = rs.getString("file_type");
                    long size = rs.getLong("file_size");
                    ChangeSignificance sig = classifier.classify(path, fileType, size,
                            ChangeEvent.ChangeType.ADDED);

                    events.add(insertChangeEvent(conn, workspacePath, now, baseSnapshotId,
                            compareSnapshotId, ChangeEvent.ChangeType.ADDED, path, null,
                            rs.getString("content_hash"), size, fileType, sig));
                }
            }
        }

        // Find DELETED files (in base but not in compare)
        String deletedSql = """
            SELECT b.* FROM snapshot_entries b
            LEFT JOIN snapshot_entries c ON c.snapshot_id = ? AND c.relative_path = b.relative_path
            WHERE b.snapshot_id = ? AND c.id IS NULL
            """;
        try (PreparedStatement ps = conn.prepareStatement(deletedSql)) {
            ps.setLong(1, compareSnapshotId);
            ps.setLong(2, baseSnapshotId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String path = rs.getString("relative_path");
                    String fileType = rs.getString("file_type");
                    long size = rs.getLong("file_size");
                    ChangeSignificance sig = classifier.classify(path, fileType, size,
                            ChangeEvent.ChangeType.DELETED);

                    events.add(insertChangeEvent(conn, workspacePath, now, baseSnapshotId,
                            compareSnapshotId, ChangeEvent.ChangeType.DELETED, path, null,
                            rs.getString("content_hash"), size, fileType, sig));
                }
            }
        }

        // Find MODIFIED files (same path, different hash or size)
        String modifiedSql = """
            SELECT c.*, b.content_hash as base_hash, b.file_size as base_size
            FROM snapshot_entries c
            JOIN snapshot_entries b ON b.snapshot_id = ? AND b.relative_path = c.relative_path
            WHERE c.snapshot_id = ?
              AND (c.content_hash != b.content_hash
                   OR (c.content_hash IS NULL AND b.content_hash IS NULL
                       AND (c.file_size != b.file_size OR c.last_modified != b.last_modified)))
            """;
        try (PreparedStatement ps = conn.prepareStatement(modifiedSql)) {
            ps.setLong(1, baseSnapshotId);
            ps.setLong(2, compareSnapshotId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String path = rs.getString("relative_path");
                    String fileType = rs.getString("file_type");
                    long size = rs.getLong("file_size");
                    ChangeSignificance sig = classifier.classify(path, fileType, size,
                            ChangeEvent.ChangeType.MODIFIED);

                    events.add(insertChangeEvent(conn, workspacePath, now, baseSnapshotId,
                            compareSnapshotId, ChangeEvent.ChangeType.MODIFIED, path, null,
                            rs.getString("content_hash"), size, fileType, sig));
                }
            }
        }

        LOG.info("Compared snapshots #" + baseSnapshotId + " vs #" + compareSnapshotId
                + ": " + events.size() + " changes detected");
        return events;
    }

    /**
     * Returns change events since a given time, optionally filtered by significance.
     */
    public synchronized List<ChangeEvent> getChangesSince(Instant since,
                                                           ChangeSignificance minSignificance) throws SQLException {
        String sql;
        if (minSignificance == null || minSignificance == ChangeSignificance.NOISE) {
            sql = "SELECT * FROM change_events WHERE detected_time >= ? ORDER BY detected_time DESC";
        } else {
            sql = """
                SELECT * FROM change_events WHERE detected_time >= ?
                AND significance IN (?, ?, ?)
                ORDER BY detected_time DESC
                """;
        }

        Connection conn = db.getConnection();
        List<ChangeEvent> events = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, since.getEpochSecond());

            if (minSignificance != null && minSignificance != ChangeSignificance.NOISE) {
                // Include all significances at or above the minimum
                List<String> included = new ArrayList<>();
                for (ChangeSignificance s : ChangeSignificance.values()) {
                    if (s.isAtLeast(minSignificance)) included.add(s.dbValue());
                }
                for (int i = 0; i < 3; i++) {
                    ps.setString(2 + i, i < included.size() ? included.get(i) : "___none___");
                }
            }

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    events.add(ChangeEvent.fromResultSet(rs));
                }
            }
        }
        return events;
    }

    /**
     * Returns change events for a specific workspace since a given time.
     */
    public synchronized List<ChangeEvent> getChangesForWorkspace(String workspacePath,
                                                                   Instant since) throws SQLException {
        String sql = """
            SELECT * FROM change_events
            WHERE workspace_path = ? AND detected_time >= ?
            ORDER BY detected_time DESC
            """;
        Connection conn = db.getConnection();
        List<ChangeEvent> events = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workspacePath);
            ps.setLong(2, since.getEpochSecond());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    events.add(ChangeEvent.fromResultSet(rs));
                }
            }
        }
        return events;
    }

    /**
     * Prunes snapshots older than the retention period.
     * CASCADE delete removes snapshot_entries automatically.
     */
    public synchronized int pruneSnapshots(int retentionDays) throws SQLException {
        long cutoff = Instant.now().minusSeconds(retentionDays * 24L * 3600).getEpochSecond();
        Connection conn = db.getConnection();
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM workspace_snapshots WHERE snapshot_time < ?")) {
            ps.setLong(1, cutoff);
            return ps.executeUpdate();
        }
    }

    // --- Private helpers ---

    private String getSnapshotWorkspace(Connection conn, long snapshotId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT workspace_path FROM workspace_snapshots WHERE id = ?")) {
            ps.setLong(1, snapshotId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString("workspace_path") : "unknown";
            }
        }
    }

    private ChangeEvent insertChangeEvent(Connection conn, String workspacePath, Instant time,
                                           long baseId, long compareId,
                                           ChangeEvent.ChangeType type, String path,
                                           String previousPath, String hash,
                                           long size, String fileType,
                                           ChangeSignificance sig) throws SQLException {
        String sql = """
            INSERT INTO change_events (workspace_path, detected_time, base_snapshot_id,
                                        compare_snapshot_id, change_type, relative_path,
                                        previous_path, content_hash, file_size, file_type, significance)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, workspacePath);
            ps.setLong(2, time.getEpochSecond());
            ps.setLong(3, baseId);
            ps.setLong(4, compareId);
            ps.setString(5, type.dbValue());
            ps.setString(6, path);
            ps.setString(7, previousPath);
            ps.setString(8, hash);
            ps.setLong(9, size);
            ps.setString(10, fileType);
            ps.setString(11, sig.dbValue());
            ps.executeUpdate();

            try (ResultSet keys = ps.getGeneratedKeys()) {
                long id = keys.next() ? keys.getLong(1) : 0;
                return new ChangeEvent(id, workspacePath, time, baseId, compareId,
                        type, path, previousPath, hash, size, fileType, sig);
            }
        }
    }
}
