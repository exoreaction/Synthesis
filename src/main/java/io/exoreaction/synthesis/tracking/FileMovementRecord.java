package io.exoreaction.synthesis.tracking;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;

/**
 * Immutable record representing a detected file movement.
 */
public record FileMovementRecord(
        long id,
        Instant timestamp,
        String contentHash,
        String sourceWorkspace,
        String sourcePath,
        String targetWorkspace,
        String targetPath,
        long fileSize,
        String fileType,
        MovementStatus status,
        DetectionMethod detectionMethod,
        Instant safetyExpiry,
        String notes
) {

    /**
     * Creates a new record for a freshly detected movement (id = 0, assigned by DB).
     */
    public static FileMovementRecord detected(
            String contentHash,
            String sourceWorkspace, String sourcePath,
            String targetWorkspace, String targetPath,
            long fileSize, String fileType,
            DetectionMethod method
    ) {
        return new FileMovementRecord(
                0, Instant.now(), contentHash,
                sourceWorkspace, sourcePath,
                targetWorkspace, targetPath,
                fileSize, fileType,
                MovementStatus.DETECTED, method, null, null
        );
    }

    /**
     * Creates a record from a database ResultSet.
     */
    public static FileMovementRecord fromResultSet(ResultSet rs) throws SQLException {
        long safetyEpoch = rs.getLong("safety_expiry");
        return new FileMovementRecord(
                rs.getLong("id"),
                Instant.ofEpochSecond(rs.getLong("timestamp")),
                rs.getString("content_hash"),
                rs.getString("source_workspace"),
                rs.getString("source_path"),
                rs.getString("target_workspace"),
                rs.getString("target_path"),
                rs.getLong("file_size"),
                rs.getString("file_type"),
                MovementStatus.fromDbValue(rs.getString("status")),
                DetectionMethod.fromDbValue(rs.getString("detection_method")),
                safetyEpoch > 0 ? Instant.ofEpochSecond(safetyEpoch) : null,
                rs.getString("notes")
        );
    }
}
