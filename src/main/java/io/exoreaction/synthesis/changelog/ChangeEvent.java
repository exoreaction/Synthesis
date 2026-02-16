package io.exoreaction.synthesis.changelog;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;

/**
 * A single change event detected between two workspace snapshots.
 */
public record ChangeEvent(
        long id,
        String workspacePath,
        Instant detectedTime,
        long baseSnapshotId,
        long compareSnapshotId,
        ChangeType changeType,
        String relativePath,
        String previousPath,
        String contentHash,
        long fileSize,
        String fileType,
        ChangeSignificance significance
) {

    public enum ChangeType {
        ADDED("added"),
        MODIFIED("modified"),
        DELETED("deleted"),
        MOVED("moved");

        private final String dbValue;

        ChangeType(String dbValue) {
            this.dbValue = dbValue;
        }

        public String dbValue() {
            return dbValue;
        }

        public static ChangeType fromDbValue(String value) {
            for (ChangeType t : values()) {
                if (t.dbValue.equals(value)) return t;
            }
            throw new IllegalArgumentException("Unknown change type: " + value);
        }
    }

    public static ChangeEvent fromResultSet(ResultSet rs) throws SQLException {
        return new ChangeEvent(
                rs.getLong("id"),
                rs.getString("workspace_path"),
                Instant.ofEpochSecond(rs.getLong("detected_time")),
                rs.getLong("base_snapshot_id"),
                rs.getLong("compare_snapshot_id"),
                ChangeType.fromDbValue(rs.getString("change_type")),
                rs.getString("relative_path"),
                rs.getString("previous_path"),
                rs.getString("content_hash"),
                rs.getLong("file_size"),
                rs.getString("file_type"),
                ChangeSignificance.fromDbValue(rs.getString("significance"))
        );
    }
}
