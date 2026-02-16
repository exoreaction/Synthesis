package io.exoreaction.synthesis.changelog;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;

/**
 * Metadata for a workspace snapshot event.
 */
public record WorkspaceSnapshot(
        long id,
        String workspacePath,
        String workspaceName,
        Instant snapshotTime,
        int fileCount,
        long totalSizeBytes,
        String trigger
) {

    public static WorkspaceSnapshot fromResultSet(ResultSet rs) throws SQLException {
        return new WorkspaceSnapshot(
                rs.getLong("id"),
                rs.getString("workspace_path"),
                rs.getString("workspace_name"),
                Instant.ofEpochSecond(rs.getLong("snapshot_time")),
                rs.getInt("file_count"),
                rs.getLong("total_size_bytes"),
                rs.getString("trigger")
        );
    }
}
