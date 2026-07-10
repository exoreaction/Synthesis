package io.exoreaction.synthesis.tracking;

import io.exoreaction.synthesis.db.SynthesisDatabase;

import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Data access object for file movement tracking tables.
 * All methods synchronize on the parent {@link SynthesisDatabase}.
 */
public class FileTrackingDatabase {

    private static final Logger LOG = Logger.getLogger(FileTrackingDatabase.class.getName());

    private final SynthesisDatabase db;

    public FileTrackingDatabase(SynthesisDatabase db) {
        this.db = db;
    }

    /**
     * Records a new file movement and returns the assigned ID.
     */
    public synchronized long recordMovement(FileMovementRecord record) throws SQLException {
        String sql = """
            INSERT INTO file_movements (
                timestamp, content_hash, source_workspace, source_path,
                target_workspace, target_path, file_size, file_type,
                status, detection_method, safety_expiry, notes
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

        Connection conn = db.getConnection();
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, record.timestamp().getEpochSecond());
            ps.setString(2, record.contentHash());
            ps.setString(3, record.sourceWorkspace());
            ps.setString(4, record.sourcePath());
            ps.setString(5, record.targetWorkspace());
            ps.setString(6, record.targetPath());
            ps.setLong(7, record.fileSize());
            ps.setString(8, record.fileType());
            ps.setString(9, record.status().dbValue());
            ps.setString(10, record.detectionMethod().dbValue());
            ps.setObject(11, record.safetyExpiry() != null
                    ? record.safetyExpiry().getEpochSecond() : null);
            ps.setString(12, record.notes());

            ps.executeUpdate();

            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    long id = keys.getLong(1);
                    recordAudit(id, "detected",
                            "Movement detected: " + record.sourcePath() + " -> " + record.targetPath());
                    return id;
                }
            }
        }
        return -1;
    }

    /**
     * Updates the status of a movement and records an audit entry.
     */
    public synchronized void updateStatus(long movementId, MovementStatus newStatus,
                                           String details) throws SQLException {
        Connection conn = db.getConnection();
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE file_movements SET status = ? WHERE id = ?")) {
            ps.setString(1, newStatus.dbValue());
            ps.setLong(2, movementId);
            ps.executeUpdate();
        }
        recordAudit(movementId, newStatus.dbValue(), details);
    }

    /**
     * Sets the safety expiry on a movement (starts the safety period).
     */
    public synchronized void startSafetyPeriod(long movementId, int safetyDays) throws SQLException {
        Instant expiry = Instant.now().plusSeconds(safetyDays * 24L * 3600);
        Connection conn = db.getConnection();
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE file_movements SET safety_expiry = ?, status = 'confirmed' WHERE id = ?")) {
            ps.setLong(1, expiry.getEpochSecond());
            ps.setLong(2, movementId);
            ps.executeUpdate();
        }
        recordAudit(movementId, "safety_started",
                "Safety period: " + safetyDays + " days, expires " + expiry);
    }

    /**
     * Returns movements that have passed their safety period.
     */
    public synchronized List<FileMovementRecord> getCleanupEligible() throws SQLException {
        String sql = """
            SELECT * FROM file_movements
            WHERE status = 'confirmed' AND safety_expiry IS NOT NULL
              AND safety_expiry < ?
            ORDER BY safety_expiry ASC
            """;
        Connection conn = db.getConnection();
        List<FileMovementRecord> results = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, Instant.now().getEpochSecond());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(FileMovementRecord.fromResultSet(rs));
                }
            }
        }
        return results;
    }

    /**
     * Returns movements filtered by status.
     */
    public synchronized List<FileMovementRecord> getByStatus(MovementStatus status) throws SQLException {
        String sql = "SELECT * FROM file_movements WHERE status = ? ORDER BY timestamp DESC";
        Connection conn = db.getConnection();
        List<FileMovementRecord> results = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, status.dbValue());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(FileMovementRecord.fromResultSet(rs));
                }
            }
        }
        return results;
    }

    /**
     * Returns all movements since a given time.
     */
    public synchronized List<FileMovementRecord> getMovementsSince(Instant since) throws SQLException {
        String sql = "SELECT * FROM file_movements WHERE timestamp >= ? ORDER BY timestamp DESC";
        Connection conn = db.getConnection();
        List<FileMovementRecord> results = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, since.getEpochSecond());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(FileMovementRecord.fromResultSet(rs));
                }
            }
        }
        return results;
    }

    /**
     * Finds movements by content hash (for audit trail). Matches by prefix so the
     * truncated hash shown in `track` output (see TrackCommand#printMovement) is
     * directly usable in `--audit`.
     */
    public synchronized List<FileMovementRecord> getByContentHash(String hash) throws SQLException {
        String sql = "SELECT * FROM file_movements WHERE content_hash LIKE ? ORDER BY timestamp DESC";
        Connection conn = db.getConnection();
        List<FileMovementRecord> results = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, hash + "%");
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(FileMovementRecord.fromResultSet(rs));
                }
            }
        }
        return results;
    }

    /**
     * Finds recently deleted file hashes that have not yet been matched.
     * These are "pending" deletions awaiting a matching addition in another workspace.
     */
    public synchronized List<FileMovementRecord> getPendingDeletions() throws SQLException {
        String sql = """
            SELECT * FROM file_movements
            WHERE target_path IS NULL AND status = 'detected'
            ORDER BY timestamp DESC
            """;
        Connection conn = db.getConnection();
        List<FileMovementRecord> results = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(FileMovementRecord.fromResultSet(rs));
                }
            }
        }
        return results;
    }

    /**
     * Returns the total number of tracked movements.
     */
    public synchronized int getMovementCount() throws SQLException {
        Connection conn = db.getConnection();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM file_movements")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    /**
     * Records an audit log entry.
     */
    private void recordAudit(long movementId, String action, String details) throws SQLException {
        String sql = "INSERT INTO file_audit_log (timestamp, movement_id, action, details) VALUES (?, ?, ?, ?)";
        Connection conn = db.getConnection();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, Instant.now().getEpochSecond());
            ps.setLong(2, movementId);
            ps.setString(3, action);
            ps.setString(4, details);
            ps.executeUpdate();
        }
    }

    /**
     * Returns audit log entries for a specific movement.
     */
    public synchronized List<AuditEntry> getAuditLog(long movementId) throws SQLException {
        String sql = "SELECT * FROM file_audit_log WHERE movement_id = ? ORDER BY timestamp ASC";
        Connection conn = db.getConnection();
        List<AuditEntry> entries = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, movementId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    entries.add(new AuditEntry(
                            rs.getLong("id"),
                            Instant.ofEpochSecond(rs.getLong("timestamp")),
                            rs.getLong("movement_id"),
                            rs.getString("action"),
                            rs.getString("details")
                    ));
                }
            }
        }
        return entries;
    }

    /**
     * Immutable audit log entry.
     */
    public record AuditEntry(long id, Instant timestamp, long movementId, String action, String details) {}
}
